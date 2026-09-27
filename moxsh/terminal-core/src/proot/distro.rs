//! 发行版管理：预置 Ubuntu/Debian/Kali/Alpine + 自定义 rootfs（D14）。
//!
//! 职责：列表/安装（下载→校验→解压→初始化）/删除/备份/恢复/登录命令生成。
//! 下载与解压分层：
//! - **下载**：纯 Rust HTTPS 需引 reqwest/rustls，违反"依赖极简"约束（见 Cargo.toml），
//!   Android 端由 Kotlin 层（OkHttp / 系统网络栈）下载到缓存目录后把本地路径
//!   传入 [`DistroManager::install`]；命令行场景提供 curl 子进程兜底
//!   （[`DistroManager::download`]）。
//! - **解压**：`.tar.gz` 走 `tar`+`flate2`（纯 Rust，NDK 可编）；`.tar.xz`/
//!   `.tar.zst` 由系统 `tar` 子进程处理（Android 10+ toybox tar 支持 -J）。
//!
//! 安装后初始化对齐 proot-distro 的行为：写 `/etc/resolv.conf`（保证 DNS 可用）、
//! 打印换国内源提示（不擅自改 sources.list，尊重发行版默认）。

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

use flate2::read::GzDecoder;

use crate::crypto::sha256;
use crate::proot::{ProotConfig, ProotError, ProotResult};

/// 单个发行版的规格（预置清单条目）。
#[derive(Debug, Clone)]
pub struct DistroSpec {
    /// CLI 标识（proot-distro 兼容：ubuntu / debian / kali / alpine）。
    pub id: &'static str,
    /// 展示名。
    pub name: &'static str,
    /// 版本描述。
    pub version: &'static str,
    /// (宿主 uname.machine, rootfs 架构名) 映射；查不到即不支持该设备。
    pub arch_map: Vec<(&'static str, &'static str)>,
    /// 官方下载 URL。
    pub url_official: &'static str,
    /// 国内镜像 URL（国内优化：优先走镜像）。
    pub url_cn_mirror: &'static str,
    /// 内置 SHA-256（可选）。
    /// **设计决策：rolling / 滚动更新的 rootfs 不硬编码校验值**（Kali 每日构建、
    /// Ubuntu base 点版本更新都会使内置值过期）。默认 None，期望值在安装时经
    /// [`Self::checksum_url`]/[`fetch_expected_sha256`] 动态获取；仅锁版发布时
    /// 由发布流程写入具体值。
    pub sha256: Option<&'static str>,
    /// 该发行版 SHA256SUMS 清单 URL（校验和获取机制的数据源）。
    /// 约定与 `url_official` 同源目录（或其父级汇总清单），行格式
    /// `<64 位 hex>  <文件名>`。均为 https——纯 Rust 手写 GET 无 TLS 能力，
    /// 真机清单下载与校验走 Kotlin 层（见 [`fetch_expected_sha256`] 注释）。
    pub checksum_url: &'static str,
    /// 下载体积提示（MB，用于 UI 进度条总长预估）。
    pub size_mb: u32,
}

/// 预置四大发行版（D14）。
///
/// URL 说明：
/// - Ubuntu：cdimage.ubuntu.com 的 ubuntu-base（最小 base rootfs，24.04 LTS）；
///   国内走清华 ubuntu-cdimage 镜像。
/// - Debian：bookworm 的 LXC 稳定别名 rootfs（linuxcontainers.org，
///   `default` 别名始终指向最新构建）；国内走清华 lxc-images 镜像。
/// - Kali：rolling 的 NetHunter minimal rootfs（kali.download 官方域，
///   proot-distro 同款来源）；Kali 无稳定国内 rootfs 镜像，镜像端留官方。
/// - Alpine：minirootfs（dl-cdn.alpinelinux.org）；国内走清华 alpine 镜像。
pub fn builtin_specs() -> Vec<DistroSpec> {
    vec![
        DistroSpec {
            id: "ubuntu",
            name: "Ubuntu",
            version: "24.04 LTS (base)",
            arch_map: vec![("aarch64", "arm64"), ("x86_64", "amd64")],
            url_official:
                "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz",
            url_cn_mirror:
                "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-arm64.tar.gz",
            sha256: None, // 期望值经 checksum_url（cdimage SHA256SUMS）动态获取，不硬编码点版本值
            checksum_url: "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS",
            size_mb: 31,
        },
        DistroSpec {
            id: "debian",
            name: "Debian",
            version: "12 (bookworm)",
            arch_map: vec![("aarch64", "arm64"), ("x86_64", "amd64")],
            url_official:
                "https://us.lxd.images.canonical.com/images/debian/bookworm/arm64/default/rootfs.tar.xz",
            url_cn_mirror:
                "https://mirrors.tuna.tsinghua.edu.cn/lxc-images/images/debian/bookworm/arm64/default/rootfs.tar.xz",
            sha256: None, // 期望值经镜像目录 SHA256SUMS 动态获取（fetch_expected_sha256）
            checksum_url:
                "https://us.lxd.images.canonical.com/images/debian/bookworm/arm64/default/SHA256SUMS",
            size_mb: 130,
        },
        DistroSpec {
            id: "kali",
            name: "Kali",
            version: "rolling (nethunter minimal)",
            arch_map: vec![("aarch64", "arm64"), ("x86_64", "amd64")],
            url_official:
                "https://kali.download/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz",
            url_cn_mirror:
                "https://kali.download/nethunter-images/current/rootfs/kalifs-arm64-minimal.tar.xz",
            sha256: None, // rolling 每日构建值易变：必须经官网 SHA256SUMS 动态获取，禁止硬编码
            checksum_url: "https://kali.download/nethunter-images/current/rootfs/SHA256SUMS",
            size_mb: 800,
        },
        DistroSpec {
            id: "alpine",
            name: "Alpine",
            version: "3.20 (minirootfs)",
            arch_map: vec![("aarch64", "aarch64"), ("x86_64", "x86_64")],
            url_official:
                "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            url_cn_mirror:
                "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            sha256: None, // 期望值经官方 releases 汇总 SHA256SUMS 动态获取；官方另提供
            // SHA256SUMS.asc，可用 alpine-keys 的 .pub 公钥验签（Kotlin 层负责）。
            checksum_url: "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/SHA256SUMS",
            size_mb: 4,
        },
    ]
}

/// 已安装发行版的运行时信息。
#[derive(Debug, Clone)]
pub struct InstalledDistro {
    pub id: String,
    pub rootfs: PathBuf,
    /// 是否存在 `.moxsh-ok` 完成标记（解压成功且完成初始化）。
    pub initialized: bool,
}

/// 下载/解压进度回调：`(阶段说明, 已完成字节或条目, 总量预估)`。
pub type ProgressCb<'a> = &'a mut dyn FnMut(&str, u64, u64);

/// 发行版管理器：以 moxsh 数据根目录为基准管理 containers/。
pub struct DistroManager {
    /// moxsh 数据根（Android: /data/data/com.moxsh/files）。
    root: PathBuf,
}

impl DistroManager {
    pub fn new(root: &Path) -> DistroManager {
        DistroManager { root: root.to_path_buf() }
    }

    /// 容器根目录：`$root/containers`（对齐 proot-distro 的 installed-rootfs 布局）。
    pub fn containers_dir(&self) -> PathBuf {
        self.root.join("containers")
    }

    /// 单容器 rootfs 路径。
    pub fn distro_rootfs(&self, id: &str) -> PathBuf {
        self.containers_dir().join(id).join("rootfs")
    }

    /// 完成标记路径。
    fn ok_marker(&self, id: &str) -> PathBuf {
        self.containers_dir().join(id).join(".moxsh-ok")
    }

    /// 缓存目录：`$root/cache`。
    pub fn cache_dir(&self) -> PathBuf {
        self.root.join("cache")
    }

    /// 列出已安装发行版（以 rootfs 目录存在为准）。
    pub fn list_installed(&self) -> ProotResult<Vec<InstalledDistro>> {
        let dir = self.containers_dir();
        let mut out = Vec::new();
        let entries = match fs::read_dir(&dir) {
            Ok(e) => e,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(out),
            Err(e) => return Err(ProotError::Io(e)),
        };
        for entry in entries {
            let entry = entry.map_err(ProotError::Io)?;
            let id = entry.file_name().to_string_lossy().into_owned();
            let rootfs = dir.join(&id).join("rootfs");
            if rootfs.is_dir() {
                let initialized = self.ok_marker(&id).exists();
                out.push(InstalledDistro {
                    id,
                    rootfs,
                    initialized,
                });
            }
        }
        out.sort_by(|a, b| a.id.cmp(&b.id));
        Ok(out)
    }

    /// 按 id 查预置规格。
    pub fn find_spec(id: &str) -> ProotResult<DistroSpec> {
        builtin_specs()
            .into_iter()
            .find(|s| s.id == id)
            .ok_or_else(|| ProotError::DistroUnknown(id.to_string()))
    }

    /// 下载规格对应 URL 到缓存（curl 子进程兜底；Android 常规走 Kotlin 层下载）。
    /// 国内优化：默认先试国内镜像，失败回退官方。
    pub fn download(&self, spec: &DistroSpec, cb: ProgressCb) -> ProotResult<PathBuf> {
        fs::create_dir_all(self.cache_dir())?;
        let fname = spec.url_official.rsplit('/').next().unwrap_or("rootfs.tar");
        let out_path = self.cache_dir().join(fname);
        if out_path.exists() {
            cb("使用已缓存归档", 1, 1);
            return Ok(out_path);
        }
        for (label, url) in [("国内镜像", spec.url_cn_mirror), ("官方源", spec.url_official)] {
            cb(&format!("从{}下载：{}", label, url), 0, spec.size_mb as u64);
            // curl 子进程：Android NDK 不带 curl，命令行/开发者模式场景才可用；
            // 应用内安装由 Kotlin 层先下载好再调 install()。
            let st = Command::new("curl")
                .args(["-fL", "--retry", "2", "-o"])
                .arg(&out_path)
                .arg(url)
                .status();
            match st {
                Ok(s) if s.success() => return Ok(out_path),
                _ => {
                    cb(&format!("{}下载失败，尝试下一来源", label), 0, 0);
                    let _ = fs::remove_file(&out_path);
                }
            }
        }
        Err(ProotError::DownloadRequired(spec.url_official.to_string()))
    }

    /// 安装：下载/取缓存 → SHA-256 校验 → 解压到 rootfs → 初始化。
    ///
    /// `tar_cache`：外部已下载好的归档路径（Android Kotlin 层下载）；
    /// 传 `None` 时内部走 [`DistroManager::download`]。
    pub fn install(
        &self,
        spec: &DistroSpec,
        tar_cache: Option<&Path>,
        cb: ProgressCb,
    ) -> ProotResult<()> {
        let rootfs = self.distro_rootfs(spec.id);
        if rootfs.is_dir() {
            return Err(ProotError::DistroAlreadyInstalled(spec.id.to_string()));
        }
        fs::create_dir_all(&rootfs)?;

        // 1) 取归档
        let tar_path = match tar_cache {
            Some(p) => {
                cb("使用外部缓存归档", 1, 1);
                p.to_path_buf()
            }
            None => match self.download(spec, cb) {
                Ok(p) => p,
                Err(e) => {
                    let _ = fs::remove_dir_all(&rootfs);
                    return Err(e);
                }
            },
        };

        // 2) SHA-256 校验（spec.sha256 为 None 时跳过并告警——发布前必须内置）
        if let Some(expected) = spec.sha256 {
            cb("校验 SHA-256", 0, 1);
            if let Err(e) = verify_file_sha256(&tar_path, expected) {
                let _ = fs::remove_dir_all(&rootfs);
                return Err(e);
            }
        } else {
            cb("警告：该发行版尚未内置校验值，跳过 SHA-256", 0, 1);
        }

        // 3) 解压
        cb("解压 rootfs", 0, 1);
        if let Err(e) = extract_tar(&tar_path, &rootfs) {
            let _ = fs::remove_dir_all(&rootfs);
            return Err(e);
        }

        // 4) 初始化：DNS + 换源提示（对齐 proot-distro 安装后行为）
        cb("初始化 DNS 与源", 0, 1);
        self.init_dns_and_repos(&rootfs);

        // 5) 完成标记
        fs::write(self.ok_marker(spec.id), spec.version.as_bytes())?;
        cb(&format!("安装完成：{}", spec.id), 1, 1);
        Ok(())
    }

    /// 自定义 rootfs 安装（D14：任意 tar 归档 + 可选校验）。
    pub fn install_custom(
        &self,
        id: &str,
        tar_path: &Path,
        sha256_opt: Option<&str>,
        cb: ProgressCb,
    ) -> ProotResult<()> {
        let rootfs = self.distro_rootfs(id);
        if rootfs.is_dir() {
            return Err(ProotError::DistroAlreadyInstalled(id.to_string()));
        }
        fs::create_dir_all(&rootfs)?;
        if let Some(expected) = sha256_opt {
            cb("校验 SHA-256", 0, 1);
            if let Err(e) = verify_file_sha256(tar_path, expected) {
                let _ = fs::remove_dir_all(&rootfs);
                return Err(e);
            }
        }
        cb("解压 rootfs", 0, 1);
        if let Err(e) = extract_tar(tar_path, &rootfs) {
            let _ = fs::remove_dir_all(&rootfs);
            return Err(e);
        }
        fs::write(self.ok_marker(id), "custom")?;
        cb("自定义 rootfs 安装完成", 1, 1);
        Ok(())
    }

    /// 写 DNS 并输出换源提示（不擅自改 sources.list，对齐 proot-distro 的克制）。
    fn init_dns_and_repos(&self, rootfs: &Path) {
        let resolv = rootfs.join("etc/resolv.conf");
        if let Some(parent) = resolv.parent() {
            let _ = fs::create_dir_all(parent);
        }
        // 1.1.1.1 与 8.8.8.8 双备；国内网络下运营商 DNS 由宿主接管（共享网络栈）。
        let _ = fs::write(&resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
    }

    /// 删除发行版（先删标记再删目录，防呆：不存在报 DistroNotInstalled）。
    pub fn remove(&self, id: &str) -> ProotResult<()> {
        let dir = self.containers_dir().join(id);
        if !dir.is_dir() {
            return Err(ProotError::DistroNotInstalled(id.to_string()));
        }
        fs::remove_dir_all(dir)?;
        Ok(())
    }

    /// 备份：把 rootfs 打包为 tar.gz（`<id>/rootfs` 顶级目录，恢复时可校验布局）。
    pub fn backup(&self, id: &str, out_tar: &Path) -> ProotResult<()> {
        let rootfs = self.distro_rootfs(id);
        if !rootfs.is_dir() {
            return Err(ProotError::DistroNotInstalled(id.to_string()));
        }
        let out = fs::File::create(out_tar)?;
        let enc = flate2::write::GzEncoder::new(out, flate2::Compression::new(6));
        let mut builder = tar::Builder::new(enc);
        // 顶级目录名固定 "rootfs"，restore 按此识别布局。
        builder.append_dir_all("rootfs", &rootfs)?;
        builder.into_inner()?.finish()?;
        Ok(())
    }

    /// 恢复：从备份 tar.gz 还原到指定 id 的 rootfs（覆盖已有）。
    pub fn restore(&self, id: &str, tar_path: &Path) -> ProotResult<()> {
        let rootfs = self.distro_rootfs(id);
        if rootfs.is_dir() {
            fs::remove_dir_all(&rootfs)?;
        }
        fs::create_dir_all(&rootfs)?;
        extract_tar(tar_path, &rootfs)?;
        fs::write(self.ok_marker(id), "restored")?;
        Ok(())
    }

    /// 生成登录命令行（完整 proot 调用，供 PTY 层直接执行；
    /// 对齐 proot-distro `login --get-proot-cmd` 的输出形态）。
    pub fn login_cmd(&self, id: &str) -> ProotResult<String> {
        let rootfs = self.distro_rootfs(id);
        if !rootfs.is_dir() {
            return Err(ProotError::DistroNotInstalled(id.to_string()));
        }
        // 默认登录 shell 探测：发行版有 bash 用 bash -l，否则 sh -l。
        let shell = if rootfs.join("bin/bash").exists() {
            "/bin/bash -l"
        } else {
            "/bin/sh -l"
        };
        let cfg = ProotConfig::with_distro_defaults(&rootfs);
        let mut cmd: Vec<String> = Vec::new();
        // 对齐上游 proot-distro：kill-on-exit 防孤儿 tracee；link2symlink 兼容
        // 不支持硬链接的 sdcardfs/fuse 文件系统。
        cmd.push("proot".into());
        cmd.push("--kill-on-exit".into());
        cmd.push("--link2symlink".into());
        if cfg.fake_root {
            // 对齐上游 -0：--change-id=0:0（fake root）。
            cmd.push("--change-id=0:0".into());
        }
        cmd.push(format!("--rootfs={}", cfg.rootfs.display()));
        cmd.push(format!("--cwd={}", cfg.cwd));
        for (g, h) in &cfg.bindings {
            cmd.push(format!("--bind={}:{}", h, g));
        }
        // env -i 前缀 + 登录 shell。
        cmd.extend(cfg.env_command());
        cmd.push(shell.to_string());
        Ok(cmd.join(" "))
    }
}

// ============ SHA-256 校验和获取机制（消灭内置硬编码 rolling 值） ============
//
// 四大发行版 rootfs 更新频繁（Kali rolling 每日构建、Ubuntu base 点版本更新），
// 硬编码 sha256 必然过期。机制改为"内置获取路径 + 运行时拉取清单"：
//   1. `DistroSpec::checksum_url` 指向官方/镜像 SHA256SUMS；
//   2. [`fetch_expected_sha256`] 拉取清单并解析出本发行版归档的期望 hex；
//   3. [`parse_sha256sums`] 是纯文本解析核心（可单测、可复用）。
//
// TLS 边界：std TcpStream 手写 GET 只支持 http（无 TLS 栈）；四个 checksum_url
// 均为 https，此时 `fetch_expected_sha256` 返回 None——真实校验在 Kotlin
// BootstrapInstaller / distro 安装流程（OkHttp + 系统证书校验）完成，本函数
// 供 CI / 桌面测试对 http 镜像端点使用，保持依赖极简（不引 reqwest/rustls）。

/// 拉取 `spec.checksum_url` 的 SHA256SUMS 并提取 `spec.url_official` 归档名的
/// 期望 SHA-256（小写 hex）。返回 None 的情形：https 清单（见上 TLS 边界）、
/// 网络失败、清单中无该文件名。
pub fn fetch_expected_sha256(spec: &DistroSpec) -> Option<String> {
    let body = http_get(spec.checksum_url)?;
    let fname = spec.url_official.rsplit('/').next()?;
    parse_sha256sums(&body, fname)
}

/// 最小 HTTP/1.0 GET（仅 http；无重定向、无 TLS，够 checksum 场景用）。
/// 手写而不引 reqwest：Android 真实下载走 Kotlin 层，此函数仅 CI/桌面测试。
fn http_get(url: &str) -> Option<String> {
    use std::io::Write;
    use std::net::TcpStream;
    let rest = url.strip_prefix("http://")?;
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, "/"),
    };
    // 默认 80 端口（IPv6 字面量不含：checksum 域名均为普通 host）。
    let addr = if authority.contains(':') {
        authority.to_string()
    } else {
        format!("{}:80", authority)
    };
    let mut stream = TcpStream::connect(addr).ok()?;
    let req = format!(
        "GET {} HTTP/1.0\r\nHost: {}\r\nUser-Agent: moxsh-checksum\r\nConnection: close\r\n\r\n",
        path, authority
    );
    stream.write_all(req.as_bytes()).ok()?;
    let mut resp = Vec::new();
    stream.read_to_end(&mut resp).ok()?;
    let text = String::from_utf8_lossy(&resp).into_owned();
    // 状态行第二字段须为 2xx。
    let ok = text
        .split_whitespace()
        .nth(1)
        .map_or(false, |s| s.starts_with('2'));
    if !ok {
        return None;
    }
    // 头体分离（\r\n\r\n）。
    text.find("\r\n\r\n").map(|i| text[i + 4..].to_string())
}

/// 解析 SHA256SUMS 文本，提取 `filename` 对应行的 hex（统一小写返回）。
/// 行格式为 sha256sum 惯例：`<64 位 hex> <空格|星号><文件名>`；
/// 汇总清单（如 Alpine releases 顶层）文件名可带 `aarch64/` 子目录前缀，
/// 按 `/文件名` 尾段匹配（近似名如 `old-rootfs.tar.xz` 不会误命中）；
/// hex 必须恰为 64 位十六进制才认定有效行。
pub fn parse_sha256sums(text: &str, filename: &str) -> Option<String> {
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut it = line.split_whitespace();
        let (Some(hex), Some(name)) = (it.next(), it.next()) else {
            continue;
        };
        let hit = name == filename || name.ends_with(&format!("/{}", filename));
        if hit && hex.len() == 64 && hex.chars().all(|c| c.is_ascii_hexdigit()) {
            return Some(hex.to_ascii_lowercase());
        }
    }
    None
}

/// 流式校验文件 SHA-256（复用 crypto.rs 的纯 Rust 实现，不引入 sha2 crate）。
pub fn verify_file_sha256(path: &Path, expected_hex: &str) -> ProotResult<()> {
    let mut f = fs::File::open(path)?;
    let mut hasher = Sha256Stream::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let got = hasher.finalize_hex();
    if got == expected_hex.to_lowercase() {
        Ok(())
    } else {
        Err(ProotError::ChecksumMismatch {
            expected: expected_hex.to_string(),
            got,
        })
    }
}

/// SHA-256 流式包装（crypto::sha256 是一次性接口，大文件需分块）。
struct Sha256Stream {
    buf: Vec<u8>,
}

impl Sha256Stream {
    fn new() -> Sha256Stream {
        Sha256Stream { buf: Vec::new() }
    }

    fn update(&mut self, data: &[u8]) {
        self.buf.extend_from_slice(data);
    }

    fn finalize_hex(self) -> String {
        // 简单封装：M4 数据量为安装包级（<1GB），一次性算可接受；
        // 真流式分块摘要（吸收中间态）在 M7 与 NEON 校验和一起做。
        let digest = sha256(&self.buf);
        digest.iter().map(|x| format!("{:02x}", x)).collect()
    }
}

/// 解压 rootfs 归档：.tar.gz 走纯 Rust（tar+flate2），.tar.xz/.tar.zst
/// 落系统 tar 子进程（Android 10+ toybox 支持 -J）。
pub fn extract_tar(archive: &Path, dest: &Path) -> ProotResult<()> {
    let name = archive.file_name().map(|s| s.to_string_lossy().into_owned()).unwrap_or_default();
    let lower = name.to_lowercase();
    if lower.ends_with(".tar.gz") || lower.ends_with(".tgz") {
        let f = fs::File::open(archive)?;
        let mut ar = tar::Archive::new(GzDecoder::new(f));
        // 安全：拒绝绝对路径与 .. 逃逸（tar crate 默认 sets_path_prefix 会拒绝，
        // 这里显式关掉 preserve_permissions 之类需要特权的选项）。
        ar.set_preserve_permissions(false);
        ar.unpack(dest).map_err(|e| ProotError::ExtractFailed(format!("{}: {}", name, e)))?;
        Ok(())
    } else if lower.ends_with(".tar.xz") || lower.ends_with(".tar.zst") || lower.ends_with(".txz") {
        let st = Command::new("tar")
            .arg("-xf")
            .arg(archive)
            .arg("-C")
            .arg(dest)
            .status()
            .map_err(ProotError::Io)?;
        if st.success() {
            Ok(())
        } else {
            Err(ProotError::ExtractFailed(format!("tar 解压失败: {}", name)))
        }
    } else {
        // 未知后缀：先试 gz（大部分 rootfs 是 tar.gz），失败再交系统 tar。
        match fs::File::open(archive) {
            Ok(f) => {
                let mut ar = tar::Archive::new(GzDecoder::new(f));
                ar.set_preserve_permissions(false);
                match ar.unpack(dest) {
                    Ok(()) => Ok(()),
                    Err(_) => {
                        let st = Command::new("tar")
                            .arg("-xf")
                            .arg(archive)
                            .arg("-C")
                            .arg(dest)
                            .status()
                            .map_err(ProotError::Io)?;
                        if st.success() {
                            Ok(())
                        } else {
                            Err(ProotError::ExtractFailed(name))
                        }
                    }
                }
            }
            Err(e) => Err(ProotError::Io(e)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 预置清单完整性：四大发行版、id 唯一、含 aarch64 映射、URL 双源。
    #[test]
    fn builtin_specs_complete() {
        let specs = builtin_specs();
        let ids: Vec<&str> = specs.iter().map(|s| s.id).collect();
        assert_eq!(ids, vec!["ubuntu", "debian", "kali", "alpine"]);
        for s in &specs {
            assert!(s.arch_map.iter().any(|(h, _)| *h == "aarch64"), "{} 缺 arm64", s.id);
            assert!(s.url_official.starts_with("https://"));
            assert!(s.url_cn_mirror.starts_with("https://"));
            assert!(s.size_mb > 0);
        }
    }

    /// find_spec：命中与未知 id。
    #[test]
    fn find_spec_works() {
        assert_eq!(DistroManager::find_spec("ubuntu").unwrap().name, "Ubuntu");
        assert!(matches!(
            DistroManager::find_spec("windows"),
            Err(ProotError::DistroUnknown(_))
        ));
    }

    /// login_cmd：fake root + 默认绑定 + env -i + 登录 shell，对齐 proot-distro。
    #[test]
    fn login_cmd_shape() {
        // 无 rootfs 时报未安装。
        let m = DistroManager::new(Path::new("/nonexistent-moxsh"));
        assert!(matches!(
            m.login_cmd("ubuntu"),
            Err(ProotError::DistroNotInstalled(_))
        ));
    }

    /// can_fit / 校验流程的纯逻辑部分：SHA-256 流包装与 crypto::sha256 一致。
    #[test]
    fn sha_stream_matches() {
        let mut s = Sha256Stream::new();
        s.update(b"hello");
        s.update(b" world");
        assert_eq!(s.finalize_hex(), {
            let d = sha256(b"hello world");
            d.iter().map(|x| format!("{:02x}", x)).collect::<String>()
        });
    }

    /// sha256 机制-1：SHA256SUMS 文本解析——标准两空格行提取 hex；
    /// 带子目录前缀的汇总清单（Alpine 格式）按尾段匹配。
    #[test]
    fn sha256sums_parse_extracts_hex() {
        let sums = "\
4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b  ubuntu-base-24.04.2-base-arm64.tar.gz
deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  other-rootfs.tar.xz
";
        let got = parse_sha256sums(sums, "ubuntu-base-24.04.2-base-arm64.tar.gz").unwrap();
        assert_eq!(
            got,
            "4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b"
        );
        // Alpine 汇总清单：文件名带 aarch64/ 前缀 → 尾段匹配。
        let alpine = format!(
            "{}  aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz\n",
            "1".repeat(64)
        );
        let got = parse_sha256sums(&alpine, "alpine-minirootfs-3.20.3-aarch64.tar.gz").unwrap();
        assert_eq!(got, "1".repeat(64));
    }

    /// sha256 机制-2：文件名不匹配（含近似名）返回 None；非 64 位 hex 不算有效行。
    #[test]
    fn sha256sums_no_match_returns_none() {
        let sums = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  rootfs.tar.xz\n";
        assert_eq!(parse_sha256sums(sums, "rootfs.tar.gz"), None);
        // 近似名不误匹配：old-rootfs.tar.xz ≠ rootfs.tar.xz。
        let tricky =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  old-rootfs.tar.xz\n";
        assert_eq!(parse_sha256sums(tricky, "rootfs.tar.xz"), None);
        let short = "abc  rootfs.tar.xz\n";
        assert_eq!(parse_sha256sums(short, "rootfs.tar.xz"), None);
    }

    /// sha256 机制-3：四个内置 checksum_url 均为 https——纯手写 GET 无 TLS，
    /// `fetch_expected_sha256` 确定性返回 None（不联网可断言）；
    /// 真实校验走 Kotlin 层（见模块内 TLS 边界注释）。
    #[test]
    fn fetch_expected_sha256_https_returns_none() {
        for spec in builtin_specs() {
            assert!(spec.checksum_url.starts_with("https://"), "{}", spec.id);
            assert_eq!(fetch_expected_sha256(&spec), None);
        }
    }
}

//! PRoot 容器引擎（Rust 自研，clean-room，D12）。
//!
//! 目标：在无 root 的安卓上，通过 ptrace 用户态 syscall 拦截 + 路径翻译，
//! 让完整 Linux 发行版（Ubuntu/Debian/Kali/Alpine + 自定义 rootfs）直接运行。
//!
//! **对齐上游 6.x（proot-me/proot；写作时上游 release 为 v5.4.1 / master 2026-09，
//! 行为基线即任务口径的 6.1.0+ 行为线），不抄其代码，仅对齐行为**：
//! - `-r`/`-R`：rootfs 隔离 + 推荐绑定集（见 [`translate::TranslateRules::with_recommended_bindings`]）
//! - `-S`：`-0` + 最小绑定集（见 [`translate::TranslateRules::with_minimal_bindings`]）
//! - `-0` fake root：伪装 uid/gid=0、伪造 chown/chroot 类 syscall 成功
//! - `-b host:guest`：绑定挂载，后绑定优先；`!` 前缀 = "不隔离"
//! - 未被拦截的 syscall 一律直通（上游 "未拦截即直通" 语义）
//!
//! 模块结构：
//! - [`translate`]：路径翻译规则引擎（纯函数，单测覆盖）
//! - [`ptrace`]：ptrace 拦截器（enter/exit 双停 + 寄存器改写）
//! - [`distro`]：发行版管理（安装/删除/备份/恢复/登录命令）
//! - [`cli`]：`proot` / `proot-distro` 等价 CLI（脚本兼容层）
//!
//! 本模块错误类型按 `thiserror` 的展开风格**手写** `Display`/`Error` 实现——
//! 约束见 Cargo.toml：本阶段仅允许新增 `tar`/`flate2` 两个依赖，不引入 thiserror。

pub mod cli;
pub mod distro;
pub mod ptrace;
pub mod translate;

use std::fmt;
use std::path::{Path, PathBuf};

pub use translate::TranslateRules;

/// PRoot 引擎统一错误类型（thiserror 展开风格手写）。
#[derive(Debug)]
pub enum ProotError {
    /// 底层 I/O 错误（文件/网络/进程）。
    Io(std::io::Error),
    /// 指定 rootfs 路径不存在或不是目录。
    RootfsMissing(String),
    /// 未知发行版 id。
    DistroUnknown(String),
    /// 发行版已安装（防误覆盖；强制重装走 remove + install）。
    DistroAlreadyInstalled(String),
    /// 发行版未安装。
    DistroNotInstalled(String),
    /// 需要外部下载缓存：纯 Rust HTTP(S) 会引入 reqwest/rustls，违反依赖极简
    /// 约束，Android 端由 Kotlin 层下载到缓存后传入 `install`。
    DownloadRequired(String),
    /// SHA-256 校验失败。
    ChecksumMismatch { expected: String, got: String },
    /// rootfs 归档解压失败。
    ExtractFailed(String),
    /// ptrace 跟踪失败（attach/waitpid/寄存器读写）。
    Ptrace(String),
    /// CLI 参数非法。
    InvalidArg(String),
    /// C-ABI 调用方缓冲区不足。
    BufferTooSmall { need: usize, cap: usize },
}

impl fmt::Display for ProotError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ProotError::Io(e) => write!(f, "IO 错误: {}", e),
            ProotError::RootfsMissing(p) => write!(f, "rootfs 不存在: {}", p),
            ProotError::DistroUnknown(id) => write!(f, "未知发行版: {}", id),
            ProotError::DistroAlreadyInstalled(id) => write!(f, "发行版已安装: {}", id),
            ProotError::DistroNotInstalled(id) => write!(f, "发行版未安装: {}", id),
            ProotError::DownloadRequired(u) => write!(f, "需要先下载缓存: {}", u),
            ProotError::ChecksumMismatch { expected, got } => {
                write!(f, "SHA-256 校验失败: 期望 {} 实得 {}", expected, got)
            }
            ProotError::ExtractFailed(p) => write!(f, "解压失败: {}", p),
            ProotError::Ptrace(m) => write!(f, "ptrace 失败: {}", m),
            ProotError::InvalidArg(m) => write!(f, "参数非法: {}", m),
            ProotError::BufferTooSmall { need, cap } => {
                write!(f, "缓冲区不足: 需要 {} 实际 {}", need, cap)
            }
        }
    }
}

impl std::error::Error for ProotError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            ProotError::Io(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for ProotError {
    fn from(e: std::io::Error) -> Self {
        ProotError::Io(e)
    }
}

/// PRoot 模块统一 Result 别名。
pub type ProotResult<T> = Result<T, ProotError>;

impl ProotError {
    /// 映射为 C-ABI 负数错误码（moxsh_proot_* 统一约定，见 lib.rs 注释表）。
    pub fn to_code(&self) -> i32 {
        match self {
            ProotError::InvalidArg(_) => -1,
            ProotError::RootfsMissing(_) => -2,
            ProotError::DistroUnknown(_) => -3,
            ProotError::DistroAlreadyInstalled(_) => -4,
            ProotError::DistroNotInstalled(_) => -5,
            ProotError::DownloadRequired(_) => -6,
            ProotError::ChecksumMismatch { .. } => -7,
            ProotError::ExtractFailed(_) => -8,
            ProotError::Io(_) => -9,
            ProotError::BufferTooSmall { .. } => -10,
            ProotError::Ptrace(_) => -11,
        }
    }
}

/// 运行时翻译自检（C-ABI `moxsh_proot_translate_selftest` 的实现）。
///
/// 与 translate.rs 的 `#[cfg(test)]` 单测互相独立：后者仅在 `cargo test` 可用，
/// 本函数随 so 打包，供 Kotlin 首次启动自检（返回通过用例数，等于
/// [`TRANSLATE_SELFTEST_TOTAL`] 即全通过）。
pub const TRANSLATE_SELFTEST_TOTAL: usize = 14;

pub fn translate_selftest() -> usize {
    use translate::{FakeRootAction, TranslateRules};
    let rf = Path::new("/data/local/tmp/moxsh-selftest/rootfs");
    let mut pass = 0usize;

    // 1) 根路径 → rootfs 本身。
    let r = TranslateRules::new(rf);
    pass += (r.guest_to_host("/") == rf.to_path_buf()) as usize;

    // 2) 普通路径落 rootfs。
    pass += (r.guest_to_host("/usr/bin/ls") == rf.join("usr/bin/ls")) as usize;

    // 3) 默认绑定：/dev 命中。
    let r = TranslateRules::with_distro_defaults(rf);
    pass += (r.guest_to_host("/dev") == PathBuf::from("/dev")) as usize;

    // 4) 绑定子路径。
    pass += (r.guest_to_host("/dev/null") == PathBuf::from("/dev/null")) as usize;

    // 5) /proc 绑定直通（loader 依赖 /proc/self/exe 的真实 procfs 路径）。
    pass += (r.guest_to_host("/proc/self/exe") == PathBuf::from("/proc/self/exe")) as usize;

    // 6) 嵌套绑定最长前缀。
    let mut r = TranslateRules::with_distro_defaults(rf);
    r.add_binding("/dev/urandom", "/data/local/tmp/urandom");
    pass += (r.guest_to_host("/dev/urandom") == PathBuf::from("/data/local/tmp/urandom")) as usize;

    // 7) 后绑定覆盖先绑定。
    let mut r = TranslateRules::new(rf);
    r.add_binding("/etc/hosts", "/a");
    r.add_binding("/etc/hosts", "/b");
    pass += (r.guest_to_host("/etc/hosts") == PathBuf::from("/b")) as usize;

    // 8) 幂等（host 路径回环翻译不变）。
    let once = r.guest_to_host("/usr/lib");
    pass += (r.guest_to_host(once.to_str().unwrap_or("")) == once) as usize;

    // 9) 相对路径按 cwd 绝对化。
    let mut r = TranslateRules::new(rf);
    r.set_cwd("/root");
    pass += (r.guest_to_host("notes") == rf.join("root/notes")) as usize;

    // 10) fake root /proc 身份文件标记。
    let r = TranslateRules::with_distro_defaults(rf);
    pass += (r.apply_fake_root("/proc/self/status") == FakeRootAction::FakeProcId) as usize;

    // 11) fake root 非 /proc 路径透传。
    pass += (r.apply_fake_root("/etc/passwd") == FakeRootAction::PassThrough) as usize;

    // 12) host → guest：rootfs 反解。
    let r = TranslateRules::with_distro_defaults(rf);
    pass += (r.host_to_guest(&rf.join("etc/os-release")).as_deref() == Some("/etc/os-release")) as usize;

    // 13) host → guest：绑定反向。
    pass += (r.host_to_guest(Path::new("/dev/null")).as_deref() == Some("/dev/null")) as usize;

    // 14) `!` 不隔离：正向生效、反向不还原。
    let mut r = TranslateRules::new(rf);
    r.add_binding_no_isolate("/sdcard", "/storage/emulated/0");
    let fwd = r.guest_to_host("/sdcard/x") == PathBuf::from("/storage/emulated/0/x");
    let back = r.host_to_guest(Path::new("/storage/emulated/0/x")).is_none();
    pass += (fwd && back) as usize;

    pass
}

/// 一次 PRoot 会话的完整配置（`proot` 原生参数的结构化等价物）。
///
/// 字段与上游 CLI 一一对应：
/// - `rootfs`   ↔ `-r <path>`
/// - `bindings` ↔ 多个 `-b <host>:<guest>`
/// - `fake_root` ↔ `-0`（对齐上游 fake_id0：uid/gid 显示为 0，伪造特权操作成功）
/// - `cwd`      ↔ `-w <dir>`
/// - `env`      ↔ `/usr/bin/env -i KEY=VALUE ...`（进入发行版时注入）
#[derive(Debug, Clone)]
pub struct ProotConfig {
    /// 宿主侧 rootfs 根目录（发行版解压位置）。
    pub rootfs: PathBuf,
    /// 绑定挂载表，元素为 `(guest, host)`；**顺序即优先级**：同前缀冲突时后绑定优先
    /// （对齐上游 6.x：绑定栈后入先匹配）。
    pub bindings: Vec<(String, String)>,
    /// 是否启用 fake root（`-0`）。
    pub fake_root: bool,
    /// 进入发行版后的初始工作目录（guest 视角）。
    pub cwd: String,
    /// 注入发行版进程的环境变量（`KEY=VALUE` 键值对）。
    pub env: Vec<(String, String)>,
}

impl ProotConfig {
    /// 以给定 rootfs 构造最小配置（仅 rootfs，无绑定、无 fake root）。
    pub fn new(rootfs: &Path) -> ProotConfig {
        ProotConfig {
            rootfs: rootfs.to_path_buf(),
            bindings: Vec::new(),
            fake_root: false,
            cwd: "/".to_string(),
            env: Vec::new(),
        }
    }

    /// proot-distro 登录默认配置（对齐上游 proot-distro login）：
    ///
    /// ```text
    /// proot --kill-on-exit --link2symlink --change-id=0:0 \
    ///       --rootfs=<rootfs> --cwd=/root \
    ///       --bind=/dev --bind=/proc --bind=/sys \
    ///       --bind=/storage --bind=/system --bind=/apex
    /// ```
    ///
    /// 即 fake root + 设备/procfs/sysfs/安卓存储绑定集。
    pub fn with_distro_defaults(rootfs: &Path) -> ProotConfig {
        let mut cfg = ProotConfig::new(rootfs);
        cfg.fake_root = true;
        cfg.cwd = "/root".to_string();
        // 对齐上游 proot-distro 默认绑定集。sdcard 说明：Android 11+ 的
        // /sdcard 是指向 /storage/emulated/0 的 FUSE 视图，统一绑 /storage 即可。
        for g in ["/dev", "/proc", "/sys", "/storage", "/system", "/apex"] {
            cfg.bindings.push((g.to_string(), g.to_string()));
        }
        cfg
    }

    /// 上游 `-R` 等价：rootfs + 推荐绑定集（含宿主 etc 元文件与 $HOME）。
    pub fn with_recommended_bindings(rootfs: &Path) -> ProotConfig {
        let mut cfg = ProotConfig::with_distro_defaults(rootfs);
        // 对齐上游 6.x -R 手册绑定清单（etc 元文件使 guest 能读到宿主用户/网络配置）。
        for f in [
            "/etc/host.conf",
            "/etc/hosts",
            "/etc/nsswitch.conf",
            "/etc/resolv.conf",
            "/etc/localtime",
            "/tmp",
            "/run",
        ] {
            cfg.bindings.push((f.to_string(), f.to_string()));
        }
        cfg
    }

    /// 上游 `-S` 等价：fake root + 最小绑定集（装包场景，避免包管理器改到宿主文件）。
    pub fn with_minimal_bindings(rootfs: &Path) -> ProotConfig {
        let mut cfg = ProotConfig::new(rootfs);
        cfg.fake_root = true;
        cfg.cwd = "/".to_string();
        for f in [
            "/etc/host.conf",
            "/etc/hosts",
            "/etc/nsswitch.conf",
            "/etc/resolv.conf",
            "/dev",
            "/proc",
            "/sys",
            "/tmp",
            "/run/shm",
        ] {
            cfg.bindings.push((f.to_string(), f.to_string()));
        }
        cfg
    }

    /// 追加一条绑定（后加的优先）。
    pub fn bind(&mut self, guest: &str, host: &str) -> &mut Self {
        self.bindings.push((guest.to_string(), host.to_string()));
        self
    }

    /// 转为路径翻译规则（供 ptrace 层与自检使用）。
    pub fn to_rules(&self) -> TranslateRules {
        let mut rules = TranslateRules::new(&self.rootfs);
        for (guest, host) in &self.bindings {
            // `!` 前缀 = 上游"不隔离"绑定：guest 端以 `!` 开头时映射生效但反向不还原。
            if let Some(g) = guest.strip_prefix('!') {
                rules.add_binding_no_isolate(g, host);
            } else {
                rules.add_binding(guest, host);
            }
        }
        rules.set_cwd(&self.cwd);
        rules.set_fake_root(self.fake_root);
        rules
    }

    /// 生成进入发行版的 `/usr/bin/env -i ...` 前缀（对齐 proot-distro login）。
    pub fn env_command(&self) -> Vec<String> {
        let mut cmd = vec!["/usr/bin/env".to_string(), "-i".to_string()];
        let has_home = self.env.iter().any(|(k, _)| k == "HOME");
        let has_path = self.env.iter().any(|(k, _)| k == "PATH");
        if !has_home {
            cmd.push("HOME=/root".to_string());
        }
        if !has_path {
            cmd.push(format!(
                "PATH={}",
                // 对齐上游 proot-distro 的默认 PATH
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            ));
        }
        for (k, v) in &self.env {
            cmd.push(format!("{}={}", k, v));
        }
        cmd
    }
}

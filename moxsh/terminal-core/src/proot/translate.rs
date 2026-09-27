//! 路径翻译规则引擎（纯函数，可完整单测）。
//!
//! 这是 PRoot 的核心：把"发行版视角"（guest）的路径映射到"安卓真实"（host）
//! 路径，以及反向还原（用于 getcwd/readlink/argv[0] 等返回值改写）。
//!
//! 语义对齐上游 6.x（proot-me/proot）：
//! - 绑定表"后绑定优先"；工程上取"最长前缀优先、同长时后绑定覆盖先绑定"，
//!   兼容 proot-distro 默认集（/dev 与 /dev/urandom 无冲突场景）。
//! - 绑定 guest 端 `!` 前缀 = "不隔离"（no-isolate）：正向映射生效，
//!   但反向还原与 stat 伪装跳过（guest 看到的是宿主真实属性）。
//! - 未命中任何绑定的绝对路径一律落到 rootfs 前缀下（`-r` 语义）。
//! - 相对路径按 guest 当前 cwd 绝对化后再翻译（对齐上游 build_current_path）。
//! - `/` 与 rootfs 根等价；已含 rootfs 前缀的 host 路径重复翻译是幂等的。
//!
//! 所有函数不触碰进程/文件系统状态，全部可用 `cargo test` 直接验证。

use std::path::{Path, PathBuf};

/// 单条绑定规则（上游 `-b <host>:<guest>` 的结构化形式）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Binding {
    /// 发行版内视角路径（绝对）。
    pub guest: PathBuf,
    /// 安卓宿主真实路径（绝对）。
    pub host: PathBuf,
    /// 是否"隔离"：true = 默认；false = 上游 `!` 前缀（不隔离）。
    pub isolated: bool,
}

/// fake root（上游 `-0` / fake_id0 扩展）对一条 `/proc` 路径的处理动作。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FakeRootAction {
    /// 无需伪装，直接透传。
    PassThrough,
    /// 需要内容级改写的进程身份文件（/proc/<pid>/status|stat|loginuid）：
    /// 上游 fake_id0 在读取返回后把 uid/gid 字段改写为 0。
    FakeProcId,
    /// 需要内容级改写的 uid/gid 映射文件（/proc/<pid>/uid_map|gid_map）：
    /// fake root 下声明 "0:真实uid:1" 的映射视图，使容器内工具读到一致身份。
    FakeProcMap,
}

/// 路径翻译规则集。
///
/// 持有：rootfs 前缀、绑定表（顺序即优先级，后绑定优先）、fake root 标志、
/// guest 侧当前工作目录（用于相对路径绝对化）。
#[derive(Debug, Clone)]
pub struct TranslateRules {
    rootfs: PathBuf,
    bindings: Vec<Binding>,
    fake_root: bool,
    cwd: PathBuf,
}

impl TranslateRules {
    /// 构造仅含 rootfs 的规则集（`-r <rootfs>` 语义）。
    pub fn new(rootfs: &Path) -> TranslateRules {
        TranslateRules {
            // 规范化 rootfs，去掉结尾 '/'，统一组件比较。
            rootfs: normalize(&rootfs.to_string_lossy()),
            bindings: Vec::new(),
            fake_root: false,
            cwd: PathBuf::from("/"),
        }
    }

    /// 追加一条"隔离"绑定（默认形态）。
    pub fn add_binding(&mut self, guest: &str, host: &str) -> &mut Self {
        self.bindings.push(Binding {
            guest: normalize(guest),
            host: normalize(host),
            isolated: true,
        });
        self
    }

    /// 追加一条"不隔离"绑定（上游 `!` 前缀语义）。
    pub fn add_binding_no_isolate(&mut self, guest: &str, host: &str) -> &mut Self {
        self.bindings.push(Binding {
            guest: normalize(guest),
            host: normalize(host),
            isolated: false,
        });
        self
    }

    /// 设置 guest 侧初始 cwd（相对路径翻译的基准，默认 `/`）。
    pub fn set_cwd(&mut self, cwd: &str) -> &mut Self {
        self.cwd = normalize(cwd);
        self
    }

    /// 启用/关闭 fake root（上游 `-0`）。
    pub fn set_fake_root(&mut self, on: bool) -> &mut Self {
        self.fake_root = on;
        self
    }

    /// proot-distro 默认绑定集构造（对齐 proot-distro login 的 --bind 列表）。
    pub fn with_distro_defaults(rootfs: &Path) -> TranslateRules {
        let mut r = TranslateRules::new(rootfs);
        for g in ["/dev", "/proc", "/sys", "/storage", "/system", "/apex"] {
            r.add_binding(g, g);
        }
        r.set_cwd("/root");
        r.set_fake_root(true);
        r
    }

    /// 上游 `-R` 等价构造：推荐绑定集（rootfs + etc 元文件 + /dev /proc /sys /tmp /run）。
    pub fn with_recommended_bindings(rootfs: &Path) -> TranslateRules {
        let mut r = Self::with_distro_defaults(rootfs);
        for f in [
            "/etc/host.conf",
            "/etc/hosts",
            "/etc/nsswitch.conf",
            "/etc/resolv.conf",
            "/etc/localtime",
            "/tmp",
            "/run",
        ] {
            r.add_binding(f, f);
        }
        r
    }

    /// 上游 `-S` 等价构造：fake root + 最小绑定集（装包场景）。
    pub fn with_minimal_bindings(rootfs: &Path) -> TranslateRules {
        let mut r = TranslateRules::new(rootfs);
        r.set_fake_root(true);
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
            r.add_binding(f, f);
        }
        r
    }

    /// guest → host 主入口。
    ///
    /// 规则顺序：
    /// 1. 规范化 + 相对路径按 cwd 绝对化；
    /// 2. 幂等：已是 host 路径（带 rootfs 前缀）则原样返回；
    /// 3. 绑定表最长前缀命中（同长时后绑定覆盖）→ host 绑定前缀 + 剩余组件；
    /// 4. 未命中 → rootfs 前缀拼接。
    pub fn guest_to_host(&self, path: &str) -> PathBuf {
        let norm = normalize(path);
        let abs = if norm.is_absolute() {
            norm
        } else {
            // 相对路径：按 guest cwd 绝对化（内核随后按已翻译的 host cwd 解析，
            // 但显式 open("/...") 前的中间拼接仍需正确视图）。
            join_path(&self.cwd, &norm)
        };

        // 幂等：调用方把已翻译的 host 路径再次传入（典型：readlink 返回值回环），
        // 直接返回，避免二次叠加 rootfs 前缀。
        if self.has_rootfs_prefix(&abs) {
            return abs;
        }

        let comps = split_comps(&abs);

        // 最长前缀优先；用 `>=` 使同长后绑定覆盖先绑定（对齐上游绑定栈顶优先）。
        let mut best_len = 0usize;
        let mut best: Option<&Binding> = None;
        for b in &self.bindings {
            let gcomps = split_comps(&b.guest);
            if strip_prefix_comps(&comps, &gcomps).is_some() && gcomps.len() >= best_len {
                best_len = gcomps.len();
                best = Some(b);
            }
        }

        if let Some(b) = best {
            let gcomps = split_comps(&b.guest);
            let rest = strip_prefix_comps(&comps, &gcomps).unwrap_or(&[] as &[String]);
            return join_path(&b.host, &make_rel(rest));
        }

        // 兜底：落 rootfs 前缀。
        self.under_rootfs(&comps)
    }

    /// host → guest 反向还原（用于 getcwd/readlink/argv[0]/proc 链接目标等）。
    ///
    /// 返回 `None` 表示该 host 路径在 guest 视角不存在（调用方应保持原样，
    /// 对齐上游 detranslate_path 失败即透传的行为）。
    pub fn host_to_guest(&self, path: &Path) -> Option<String> {
        let norm = normalize(&path.to_string_lossy());
        let comps = split_comps(&norm);

        // 1) 绑定反向还原（仅隔离绑定；`!` 不隔离不还原——guest 看到真实属性）。
        //    同样最长前缀优先、同长后绑定覆盖。
        let mut best_len = 0usize;
        let mut best: Option<&Binding> = None;
        for b in self.bindings.iter().filter(|b| b.isolated) {
            let hcomps = split_comps(&b.host);
            if let Some(rest) = strip_prefix_comps(&comps, &hcomps) {
                if hcomps.len() >= best_len {
                    best_len = hcomps.len();
                    best = Some(b);
                }
            }
        }
        if let Some(b) = best {
            let hcomps = split_comps(&b.host);
            let rest = strip_prefix_comps(&comps, &hcomps).unwrap_or(&[] as &[String]);
            return Some(join_path(&b.guest, &make_rel(rest)).to_string_lossy().into_owned());
        }

        // 2) rootfs 前缀反去：rootfs/usr/bin → /usr/bin；rootfs 本身 → /。
        if let Some(rest) = self.strip_rootfs(&comps) {
            return Some(if rest.is_empty() {
                "/".to_string()
            } else {
                format!("/{}", rest.join("/"))
            });
        }
        None
    }

    /// fake root（上游 `-0`）对给定 guest 路径的处理动作判定。
    ///
    /// 上游 fake_id0 扩展机制说明（行为对齐，非代码对齐）：
    /// - getuid/getgid/geteuid/... 在 enter 阶段被替换为 getuid32 类，exit 阶段
    ///   返回值强制 0（`-i uid:gid` 时为映射值，moxsh 固定 0:0）；
    /// - chown/fchown/chownat/setuid/setgid/setreuid/... 伪造成功（返回 0，不实际执行）；
    /// - /proc 下身份文件在读取出口做内容改写，即本函数标记的三类。
    pub fn apply_fake_root(&self, path: &str) -> FakeRootAction {
        if !self.fake_root {
            return FakeRootAction::PassThrough;
        }
        let comps = split_comps(&normalize(path));
        // 形如 /proc/<pid|self|thread-self>/<file>
        if comps.first().map(|s| s.as_str()) != Some("proc") || comps.len() < 3 {
            return FakeRootAction::PassThrough;
        }
        let owner = comps[1].as_str();
        let is_pid = owner == "self"
            || owner == "thread-self"
            || owner.chars().all(|c| c.is_ascii_digit());
        if !is_pid {
            return FakeRootAction::PassThrough;
        }
        match comps[2].as_str() {
            "status" | "stat" | "loginuid" => FakeRootAction::FakeProcId,
            "uid_map" | "gid_map" => FakeRootAction::FakeProcMap,
            _ => FakeRootAction::PassThrough,
        }
    }

    pub fn fake_root(&self) -> bool {
        self.fake_root
    }

    pub fn rootfs(&self) -> &Path {
        &self.rootfs
    }

    /// 把绝对组件列表落到 rootfs 前缀下。
    fn under_rootfs(&self, comps: &[String]) -> PathBuf {
        let mut p = self.rootfs.clone();
        for c in comps {
            p.push(c);
        }
        p
    }

    /// 判断绝对路径是否已带 rootfs 前缀（幂等翻译判定）。
    fn has_rootfs_prefix(&self, abs: &Path) -> bool {
        let comps = split_comps(abs);
        let rcomps = split_comps(&self.rootfs);
        if self.rootfs == Path::new("/") {
            // rootfs=/ 时任何绝对路径都视为已带前缀（上游 rootfs=/ 的平凡情形）。
            return abs.is_absolute();
        }
        strip_prefix_comps(&comps, &rcomps).is_some()
    }

    /// 去掉 rootfs 前缀，返回剩余组件（非前缀返回 None）。
    fn strip_rootfs<'a>(&self, comps: &'a [String]) -> Option<Vec<&'a str>> {
        if self.rootfs == Path::new("/") {
            return Some(comps.iter().map(|s| s.as_str()).collect());
        }
        let rcomps = split_comps(&self.rootfs);
        strip_prefix_comps(comps, &rcomps)
            .map(|rest| rest.iter().map(|s| s.as_str()).collect())
    }
}

/// 路径规范化（纯字符串级，不触碰文件系统）：折叠 `//`、消除 `.`、解析 `..`。
///
/// `/..` 在根处收敛为 `/`（POSIX 语义）；相对路径的 `..` 保留为前导组件。
pub fn normalize(path: &str) -> PathBuf {
    let absolute = path.starts_with('/');
    let mut comps: Vec<&str> = Vec::new();
    for c in path.split('/') {
        match c {
            "" | "." => {}
            ".." => {
                if !comps.is_empty() && comps.last() != Some(&"..") {
                    comps.pop();
                } else if !absolute {
                    comps.push("..");
                }
                // 绝对路径在根处 ".." 直接消解（/.. == /）。
            }
            _ => comps.push(c),
        }
    }
    let joined = comps.join("/");
    if absolute {
        PathBuf::from(format!("/{}", joined))
    } else if joined.is_empty() {
        PathBuf::from(".")
    } else {
        PathBuf::from(joined)
    }
}

/// 把路径拆成组件（不含根斜杠与空组件）。
fn split_comps(p: &Path) -> Vec<String> {
    p.components()
        .filter_map(|c| match c {
            std::path::Component::Normal(s) => Some(s.to_string_lossy().into_owned()),
            std::path::Component::ParentDir => Some("..".to_string()),
            _ => None,
        })
        .collect()
}

/// 组件级前缀剥离：`path` 以 `prefix`（组件序列）开头时返回剩余组件。
///
/// 用组件比较而非字节前缀，避免 `/dev` 误命中 `/device`（上游
/// compare_paths 的等价正确性要求）。
fn strip_prefix_comps<'a>(path: &'a [String], prefix: &[String]) -> Option<&'a [String]> {
    if prefix.len() > path.len() {
        return None;
    }
    if path[..prefix.len()] == prefix[..] {
        Some(&path[prefix.len()..])
    } else {
        None
    }
}

/// 拼接 base 与（可能为空的）相对部分；base 保留绝对性。
fn join_path(base: &Path, rel: &Path) -> PathBuf {
    // 注意：Path 是 DST，`base.clone()` 克隆的是引用（&Path）而非 PathBuf。
    let mut out = base.to_path_buf();
    for c in rel.components() {
        out.push(c);
    }
    out
}

/// 把剩余组件转回相对 Path（空 → "."）。
fn make_rel(rest: &[String]) -> PathBuf {
    if rest.is_empty() {
        PathBuf::from(".")
    } else {
        PathBuf::from(rest.join("/"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 构造典型规则：rootfs=/data/data/com.moxsh/containers/ubuntu/rootfs，
    /// proot-distro 默认绑定 + 手工 /dev/urandom 细绑定。
    fn rules() -> TranslateRules {
        let mut r = TranslateRules::with_distro_defaults(Path::new(
            "/data/data/com.moxsh/containers/ubuntu/rootfs",
        ));
        // 嵌套绑定测试素材：/dev/urandom 细绑定覆盖 /dev 粗绑定（最长前缀优先）。
        r.add_binding("/dev/urandom", "/data/local/tmp/urandom");
        r
    }

    /// 1. 根路径 `/` 落到 rootfs 本身。
    #[test]
    fn guest_root_maps_to_rootfs() {
        let r = rules();
        assert_eq!(
            r.guest_to_host("/"),
            PathBuf::from("/data/data/com.moxsh/containers/ubuntu/rootfs")
        );
    }

    /// 2. 普通绝对路径未命中绑定 → rootfs 前缀拼接。
    #[test]
    fn plain_path_falls_to_rootfs() {
        let r = rules();
        assert_eq!(
            r.guest_to_host("/usr/bin/ls"),
            PathBuf::from("/data/data/com.moxsh/containers/ubuntu/rootfs/usr/bin/ls")
        );
    }

    /// 3. 绑定前缀命中：/dev → 宿主 /dev（默认绑定集）。
    #[test]
    fn binding_prefix_hit() {
        let r = rules();
        assert_eq!(r.guest_to_host("/dev"), PathBuf::from("/dev"));
    }

    /// 4. 绑定子路径继承：/dev/null → /dev/null。
    #[test]
    fn binding_child_inherits() {
        let r = rules();
        assert_eq!(r.guest_to_host("/dev/null"), PathBuf::from("/dev/null"));
    }

    /// 5. 嵌套绑定最长前缀优先：/dev/urandom 命中细绑定而非 /dev。
    #[test]
    fn nested_binding_longest_prefix_wins() {
        let r = rules();
        assert_eq!(
            r.guest_to_host("/dev/urandom"),
            PathBuf::from("/data/local/tmp/urandom")
        );
    }

    /// 6. 绑定优先级覆盖：同 guest 两个绑定，后绑定覆盖先绑定（上游栈顶优先）。
    #[test]
    fn later_binding_overrides() {
        let mut r = TranslateRules::new(Path::new("/rf"));
        r.add_binding("/etc/hosts", "/a/hosts");
        r.add_binding("/etc/hosts", "/b/hosts");
        assert_eq!(r.guest_to_host("/etc/hosts"), PathBuf::from("/b/hosts"));
    }

    /// 7. 幂等：已含 rootfs 前缀的 host 路径再翻译不叠加前缀。
    #[test]
    fn idempotent_host_path() {
        let r = rules();
        let host = r.guest_to_host("/usr/lib");
        assert_eq!(r.guest_to_host(host.to_str().unwrap()), host);
    }

    /// 8. 相对路径按 guest cwd 绝对化后翻译。
    #[test]
    fn relative_path_joins_cwd() {
        let r = rules();
        let mut r2 = r.clone();
        r2.set_cwd("/root");
        assert_eq!(
            r2.guest_to_host("notes.txt"),
            PathBuf::from("/data/data/com.moxsh/containers/ubuntu/rootfs/root/notes.txt")
        );
    }

    /// 9. fake root：/proc/self/status 标记为身份文件内容伪装。
    #[test]
    fn fake_root_proc_status() {
        let r = rules(); // with_distro_defaults 已开 fake_root
        assert_eq!(r.apply_fake_root("/proc/self/status"), FakeRootAction::FakeProcId);
        assert_eq!(r.apply_fake_root("/proc/1234/stat"), FakeRootAction::FakeProcId);
        assert_eq!(r.apply_fake_root("/proc/self/uid_map"), FakeRootAction::FakeProcMap);
    }

    /// 10. fake root 关闭或非身份路径：透传。
    #[test]
    fn fake_root_passthrough() {
        let mut r = rules();
        r.set_fake_root(false);
        assert_eq!(r.apply_fake_root("/proc/self/status"), FakeRootAction::PassThrough);
        assert_eq!(r.apply_fake_root("/etc/passwd"), FakeRootAction::PassThrough);
        // /proc 顶层（无 pid 段）不伪装——对齐上游 #438 修复的顶层 /proc 条目语义。
        assert_eq!(r.apply_fake_root("/proc/mounts"), FakeRootAction::PassThrough);
    }

    /// 11. host → guest：rootfs 下路径反解为 guest 绝对路径。
    #[test]
    fn host_to_guest_rootfs_strip() {
        let r = rules();
        assert_eq!(
            r.host_to_guest(Path::new(
                "/data/data/com.moxsh/containers/ubuntu/rootfs/etc/os-release"
            )),
            Some("/etc/os-release".to_string())
        );
        assert_eq!(
            r.host_to_guest(Path::new("/data/data/com.moxsh/containers/ubuntu/rootfs")),
            Some("/".to_string())
        );
    }

    /// 12. host → guest：绑定路径反向还原。
    #[test]
    fn host_to_guest_binding() {
        let r = rules();
        assert_eq!(r.host_to_guest(Path::new("/dev/null")), Some("/dev/null".to_string()));
    }

    /// 13. `!` 不隔离绑定：正向映射生效、反向不还原（guest 看到宿主真实属性）。
    #[test]
    fn no_isolate_binding_semantics() {
        let mut r = TranslateRules::new(Path::new("/rf"));
        r.add_binding_no_isolate("/sdcard", "/storage/emulated/0");
        assert_eq!(
            r.guest_to_host("/sdcard/DCIM/a.jpg"),
            PathBuf::from("/storage/emulated/0/DCIM/a.jpg")
        );
        // 不隔离：反向不还原。
        assert_eq!(r.host_to_guest(Path::new("/storage/emulated/0/DCIM/a.jpg")), None);
    }

    /// 14. `..` 规范化：/usr/../etc 折叠为 /etc 后落 rootfs。
    #[test]
    fn dotdot_normalized() {
        let r = rules();
        assert_eq!(
            r.guest_to_host("/usr/../etc/hostname"),
            PathBuf::from("/data/data/com.moxsh/containers/ubuntu/rootfs/etc/hostname")
        );
    }

    /// 15. 尾斜杠与多余斜杠折叠（/usr/ 与 //usr 等价）。
    #[test]
    fn slash_normalization() {
        let r = rules();
        assert_eq!(r.guest_to_host("/usr/"), r.guest_to_host("//usr"));
        assert_eq!(r.guest_to_host("/dev//null/"), PathBuf::from("/dev/null"));
    }

    /// 16. /proc 绑定直通：/proc/self/exe → 宿主 /proc/self/exe（上游语义：
    ///     loader 借此定位自身，必须保持真实 procfs 路径）。
    #[test]
    fn proc_self_exe_through_binding() {
        let r = rules();
        assert_eq!(r.guest_to_host("/proc/self/exe"), PathBuf::from("/proc/self/exe"));
    }

    /// 17. host → guest 无关路径返回 None（调用方透传）。
    ///
    /// 注意 /system 是 with_distro_defaults 的默认绑定（/dev /proc /sys
    /// /storage /system /apex），host /system/bin/sh 反解为 guest 同名路径是
    /// 正确行为；此处选一条既不在默认绑定也不在 rootfs 下的路径验证 None。
    #[test]
    fn host_to_guest_unrelated_none() {
        let r = rules();
        assert_eq!(r.host_to_guest(Path::new("/vendor/bin/sh")), None);
    }

    /// 18. 组件级前缀的正确性：/dev 不误命中 /device。
    #[test]
    fn no_byte_prefix_false_positive() {
        let r = rules();
        assert_eq!(
            r.guest_to_host("/device"),
            PathBuf::from("/data/data/com.moxsh/containers/ubuntu/rootfs/device")
        );
    }

    /// 19. rootfs=/ 的平凡情形：一切绝对路径视为已带前缀（幂等直通）。
    #[test]
    fn rootfs_slash_trivial() {
        let r = TranslateRules::new(Path::new("/"));
        assert_eq!(r.guest_to_host("/usr/bin"), PathBuf::from("/usr/bin"));
        assert_eq!(r.host_to_guest(Path::new("/usr/bin")), Some("/usr/bin".to_string()));
    }

    /// 20. 同长绑定覆盖在最长前缀选择中依然成立（两绑定不同 guest 但与目标同长前缀）。
    #[test]
    fn same_length_binding_later_wins() {
        let mut r = TranslateRules::new(Path::new("/rf"));
        r.add_binding("/opt", "/opt-first");
        r.add_binding("/opt", "/opt-second");
        assert_eq!(r.guest_to_host("/opt/x"), PathBuf::from("/opt-second/x"));
    }
}

//! 绝对符号链接规范化 + 运行时走查（「link2symlink」问题的升级解法）。
//!
//! # 问题
//!
//! guest rootfs 里的符号链接常带**绝对 target**（实测 alpine minirootfs 的
//! `bin/sh -> /bin/busybox` 全是这种；debian 的 `ld-linux-x86-64.so.2 ->
//! /lib/x86_64-linux-gnu/ld-2.36.so` 同理）。引擎只翻译 syscall 的**路径
//! 参数**，链接 target 由**内核在宿主命名空间**解析——绝对 target 会直接
//! 跳到宿主根目录：要么 ENOENT，更糟的是 silently 命中宿主同名文件
//! （`/lib/libc.so.6` 在 glibc 宿主上真实存在！）。
//!
//! # 上游方案及其缺陷（读码所得，不抄）
//!
//! 上游 `link2symlink` 扩展（629 行）的做法：guest 访问/创建符号链接时，
//! 在**宿主侧把目标文件搬走**（改名成 `.l2s.xxxx` 前缀），再把 guest 的
//! 链接改成指向搬运后的相对位置。缺陷：
//! - **在用户的 rootfs 里乱搬文件**：rootfs 内容被 proot 悄悄改写，
//!   `sha256` 校验/重新打包/跨机器迁移全部对不上；
//! - `.l2s.` 前缀泄漏进 guest 视野（readlink 结果被污染）；
//! - 每访问一次链就搬一次，IO 放大；
//! - 与并发访问 race（边搬边读）。
//!
//! # 本模块的升级方案（三层防御）
//!
//! 1. **启动/安装时规范化**（主路径）：把「映射进 rootfs 的绝对链接」
//!    改写为**相对形式**（`/bin/sh -> /bin/busybox` ⇒ `sh -> busybox`）。
//!    相对链接由内核原生解析、天然不出 rootfs——之后**运行时零开销**，
//!    rootfs 内容保持自洽（usrmerge 时代的发行版本来就大量使用相对链接）。
//!    指向绑定目录（`/proc`、`/sys`、`/dev`…）的链接**保持绝对**——
//!    它们的正确解析依赖绑定表，交给绑定语义。
//! 2. **运行时走查兜底**（`resolve_host_path`）：规范化失败（只读 rootfs）
//!    或漏网场景下，路径翻译沿组件走查，踩到绝对链接时按**当前会话的
//!    绑定表**映射回 rootfs/绑定位置继续——语义完全跟随会话配置，
//!    不受「规范化时用的是哪套绑定」影响。
//! 3. **创建钩子保持成果**（ptrace 层调用 [`rewrite_link_in_place`]）：
//!    `symlink/symlinkat` 创建绝对 target 链接时当场改写为相对，
//!    让规范化成果不被运行期操作稀释。

use std::fs;
use std::path::{Component, Path, PathBuf};

use crate::proot::translate::TranslateRules;
use crate::proot::ProotResult;

/// 链接走查深度上限（对齐内核 `MAXSYMLINKS`=40 的量级，防环）。
const MAX_LINK_DEPTH: usize = 40;

/// 规范化遍历的条目数上限（防御病态巨型树；正常 rootfs 远低于此）。
const MAX_SCAN_ENTRIES: usize = 500_000;

/// 规范化结果统计。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct NormalizeStats {
    /// 遍历到的符号链接数。
    pub links_seen: usize,
    /// 改写为相对形式的链接数。
    pub rewritten: usize,
}

/// 纯函数：把绝对 `target` 改写为相对于 `link_dir` 的相对路径。
///
/// 数学与视角无关：guest 空间（`/bin/sh` 的目录 `/bin` 到 `/bin/busybox`）
/// 与宿主空间（`rootfs/bin` 到 `rootfs/bin/busybox`）算出同一个结果——
/// rootfs 前缀在差集中抵消。调用方按手头视角传参即可。
///
/// 示例：
/// - `("/a/b/c", "/a/b/d/e")` ⇒ `../d/e`
/// - `("/a", "/a/b")` ⇒ `b`
/// - `("/a/b", "/a")` ⇒ `..`
/// - `("/", "/bin/sh")` ⇒ `bin/sh`
/// - `("/a/b", "/")` ⇒ `../..`
/// - `("/usr/local/bin", "/bin/bash")` ⇒ `../../../bin/bash`
pub fn abs_to_rel(link_dir: &Path, target: &Path) -> PathBuf {
    let dir: Vec<&std::ffi::OsStr> = link_dir
        .components()
        .filter_map(|c| match c {
            Component::Normal(s) => Some(s),
            _ => None,
        })
        .collect();
    let tgt: Vec<&std::ffi::OsStr> = target
        .components()
        .filter_map(|c| match c {
            Component::Normal(s) => Some(s),
            _ => None,
        })
        .collect();
    let common = dir
        .iter()
        .zip(tgt.iter())
        .take_while(|(a, b)| a == b)
        .count();
    let mut out = PathBuf::new();
    for _ in common..dir.len() {
        out.push("..");
    }
    for c in &tgt[common..] {
        out.push(c);
    }
    if out.as_os_str().is_empty() {
        // target 与 link_dir 同目录（病态：链接指向自己所在目录）——
        // 用 "." 保持合法相对链接语义。
        out.push(".");
    }
    out
}

/// 判断 guest 绝对 target 是否映射进 **rootfs 区域**（而非绑定/宿主路径）。
///
/// `-r /`（无隔离直通）恒为 `false`——该模式下宿主就是 guest 的世界，
/// 绝对链接原样正确，绝不该改写（更不能遍历宿主根）。
pub fn target_maps_into_rootfs(target: &Path, rules: &TranslateRules) -> bool {
    if !target.is_absolute() {
        return false;
    }
    let host = rules.guest_to_host(&target.to_string_lossy());
    rules.is_under_rootfs(&host)
}

/// 若 `link`（宿主路径）是「绝对 target 且映射进 rootfs」的符号链接，
/// 就地改写为相对形式。返回是否发生了改写。
///
/// 三处调用方共用：rootfs 规范化遍历、`symlink/symlinkat` 创建钩子、
/// `renameat/linkat` 搬入防御。改写非原子（临时名 + rename），崩溃最坏
/// 损失一个链接，重跑幂等修复。
pub fn rewrite_link_in_place(link: &Path, rules: &TranslateRules) -> bool {
    let md = match fs::symlink_metadata(link) {
        Ok(m) => m,
        Err(_) => return false,
    };
    if !md.file_type().is_symlink() {
        return false;
    }
    let target = match fs::read_link(link) {
        Ok(t) => t,
        Err(_) => return false,
    };
    if !target.is_absolute() || !target_maps_into_rootfs(&target, rules) {
        return false;
    }
    let link_dir = link.parent().unwrap_or(link);
    // host 视角算相对式（与 guest 视角等价，见 abs_to_rel 文档）
    let host_target = rules.guest_to_host(&target.to_string_lossy());
    let rel = abs_to_rel(link_dir, &host_target);
    if rel.as_os_str().is_empty() {
        return false;
    }
    let Some(name) = link.file_name() else {
        return false;
    };
    let tmp = link.with_file_name(format!(".{}.moxsh-l2s-tmp", name.to_string_lossy()));
    let _ = fs::remove_file(&tmp);
    if std::os::unix::fs::symlink(&rel, &tmp).is_err() {
        let _ = fs::remove_file(&tmp);
        return false;
    }
    if fs::rename(&tmp, link).is_err() {
        let _ = fs::remove_file(&tmp);
        return false;
    }
    true
}

/// rootfs 全树规范化：绝对链接（映射进 rootfs 的）改写为相对。
///
/// - `rootfs == "/"`（无隔离直通）：直接返回零值——宿主即 guest，遍历宿主
///   根既危险又无意义；
/// - 顶层 `proc/sys/dev` 目录不下钻（虚拟文件系统/设备节点，无合法链接可修，
///   且防用户把宿主挂载点套进 rootfs 时走穿）；
/// - 单条目失败（权限/竞争）跳过不中断；条数超 [`MAX_SCAN_ENTRIES`] 停。
pub fn normalize_rootfs_symlinks(rootfs: &Path, rules: &TranslateRules) -> ProotResult<NormalizeStats> {
    let mut stats = NormalizeStats::default();
    if rootfs == Path::new("/") {
        return Ok(stats);
    }
    let mut budget = MAX_SCAN_ENTRIES;
    walk_tree(rootfs, rootfs, rules, &mut stats, &mut budget, true)?;
    Ok(stats)
}

fn walk_tree(
    dir: &Path,
    rootfs: &Path,
    rules: &TranslateRules,
    stats: &mut NormalizeStats,
    budget: &mut usize,
    top_level: bool,
) -> ProotResult<()> {
    let entries = match fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return Ok(()), // 单目录不可读：跳过（只读 rootfs 场景）
    };
    for entry in entries {
        if *budget == 0 {
            return Ok(());
        }
        *budget -= 1;
        let entry = match entry {
            Ok(e) => e,
            Err(_) => continue,
        };
        // file_type() 不走符号链接（read_dir 的 d_type/no-follow 语义）。
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        let path = entry.path();
        if ft.is_symlink() {
            stats.links_seen += 1;
            if rewrite_link_in_place(&path, rules) {
                stats.rewritten += 1;
            }
        } else if ft.is_dir() {
            if top_level {
                // 顶层 proc/sys/dev 不下钻（见函数文档）。
                if let Some(n) = path.file_name().and_then(|s| s.to_str()) {
                    if matches!(n, "proc" | "sys" | "dev") && path.parent() == Some(rootfs) {
                        continue;
                    }
                }
            }
            walk_tree(&path, rootfs, rules, stats, budget, false)?;
        }
    }
    Ok(())
}

/// 运行时走查：把宿主路径沿组件解析，踩到绝对 target 的链接时按当前
/// 会话的绑定表映射回 rootfs/绑定位置继续走。
///
/// `follow_last`：是否解析**最后一个组件**（openat/access 系为 true；
/// statx/lstat/unlinkat 系为 false——它们要的是链接自身而非目标）。
///
/// 语义对齐内核：相对链接在**最后一停**时原样交还内核（openat 自然
/// 跟随，结果一致）；中间组件的相对链接由本函数继续走查（其目标下方
/// 可能还有未规范化的绝对链接）；绝对链接——内核唯一会跑飞的场景——
/// 按绑定表映射后继续。深度/环路上限 [`MAX_LINK_DEPTH`]。
pub fn resolve_host_path(host: &Path, rules: &TranslateRules, follow_last: bool) -> PathBuf {
    resolve_inner(host, rules, follow_last, 0)
}

fn resolve_inner(host: &Path, rules: &TranslateRules, follow_last: bool, depth: usize) -> PathBuf {
    if depth > MAX_LINK_DEPTH {
        return host.to_path_buf(); // 环/病态：交还内核（ELOOP 由内核裁决）
    }
    let comps: Vec<Component> = host.components().collect();
    let mut cur = PathBuf::new();
    for (i, c) in comps.iter().enumerate() {
        cur.push(c);
        let is_last = i + 1 == comps.len();
        if is_last && !follow_last {
            return cur; // 最后一停不跟随：cur 即原路径
        }
        let rest: PathBuf = comps[i + 1..].iter().collect();
        let md = match fs::symlink_metadata(&cur) {
            Ok(m) => m,
            Err(_) => {
                // 不存在/无权限：剩余组件词法补全后交还内核——
                // 下方不可能有链接可走查，ENOENT 由内核权威给出。
                cur.push(rest);
                return cur;
            }
        };
        if !md.file_type().is_symlink() {
            continue;
        }
        let target = match fs::read_link(&cur) {
            Ok(t) => t,
            Err(_) => {
                cur.push(rest);
                return cur;
            }
        };
        if target.is_absolute() {
            // 绝对 target：按绑定表映射（rootfs 内 → rootfs 前缀；绑定 → 宿主绑定点）
            let mapped = rules.guest_to_host(&target.to_string_lossy());
            if !mapped.is_absolute() {
                cur.push(rest); // 病态：交还内核
                return cur;
            }
            let joined = mapped.join(&rest);
            // 落进绑定区/宿主直通（含 `-r /`）：不再走查——
            // `/proc/self` 之类链接的解析依赖「谁在执行」，原样交给内核才对。
            if !rules.is_under_rootfs(&mapped) {
                return joined;
            }
            return resolve_inner(&joined, rules, follow_last, depth + 1);
        }
        // 相对链接：内核同目录拼合的解析与我们一致，但**中间组件**必须
        // 继续走查——其目标下方可能还有未规范化的绝对链接（只读 rootfs
        // 场景），原样交还内核会在宿主空间解析错。最后一停的相对链接
        // 保留原样（内核解析才对，openat 时自然跟随）。
        if rest.as_os_str().is_empty() {
            return cur;
        }
        let resolved = match cur.parent() {
            Some(p) => p.join(&target),
            None => target.clone(),
        };
        return resolve_inner(&resolved.join(&rest), rules, follow_last, depth + 1);
    }
    cur
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rf() -> PathBuf {
        // 每次调用唯一目录：并行测试互不踩（同 PID 多线程共享会互相删）。
        static SEQ: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let n = SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        std::env::temp_dir().join(format!("moxsh-l2s-{}-{}", std::process::id(), n))
    }

    fn fresh() -> PathBuf {
        let p = rf();
        let _ = fs::remove_dir_all(&p);
        fs::create_dir_all(&p).unwrap();
        p
    }

    #[test]
    fn relpath_math() {
        assert_eq!(abs_to_rel(Path::new("/a/b/c"), Path::new("/a/b/d/e")), Path::new("../d/e"));
        assert_eq!(abs_to_rel(Path::new("/a"), Path::new("/a/b")), Path::new("b"));
        assert_eq!(abs_to_rel(Path::new("/a/b"), Path::new("/a")), Path::new(".."));
        assert_eq!(abs_to_rel(Path::new("/"), Path::new("/bin/sh")), Path::new("bin/sh"));
        assert_eq!(abs_to_rel(Path::new("/a/b"), Path::new("/")), Path::new("../.."));
        assert_eq!(abs_to_rel(Path::new("/x"), Path::new("/x")), Path::new("."));
    }

    #[test]
    fn relpath_guest_host_equivalence() {
        // guest 视角与 host 视角（rootfs 前缀下）必须同解——这是
        // 「symlink 钩子无需知道 guest cwd」的根据。
        let rootfs = Path::new("/data/rf");
        let g_link_dir = Path::new("/usr/local/bin");
        let g_target = Path::new("/bin/bash");
        let h_link_dir = rootfs.join("usr/local/bin");
        let h_target = rootfs.join("bin/bash");
        assert_eq!(abs_to_rel(g_link_dir, g_target), abs_to_rel(&h_link_dir, &h_target));
    }

    #[test]
    fn normalize_rewrites_intra_rootfs_abs_links() {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        fs::create_dir_all(base.join("usr/local/bin")).unwrap();
        fs::write(base.join("bin/busybox"), b"x").unwrap();
        std::os::unix::fs::symlink("/bin/busybox", base.join("bin/sh")).unwrap();
        std::os::unix::fs::symlink("/bin/busybox", base.join("usr/local/bin/mybb")).unwrap();
        let rules = TranslateRules::new(&base);
        let stats = normalize_rootfs_symlinks(&base, &rules).unwrap();
        assert_eq!(stats.links_seen, 2);
        assert_eq!(stats.rewritten, 2);
        assert_eq!(fs::read_link(base.join("bin/sh")).unwrap(), Path::new("busybox"));
        assert_eq!(
            fs::read_link(base.join("usr/local/bin/mybb")).unwrap(),
            Path::new("../../../bin/busybox")
        );
        // 幂等：再跑一次零改写
        let stats2 = normalize_rootfs_symlinks(&base, &rules).unwrap();
        assert_eq!(stats2.rewritten, 0);
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn normalize_keeps_relative_and_binding_links() {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        fs::write(base.join("bin/busybox"), b"x").unwrap();
        std::os::unix::fs::symlink("busybox", base.join("bin/ls")).unwrap(); // 相对：不动
        std::os::unix::fs::symlink("/proc/self", base.join("bin/pself")).unwrap(); // 绑定目标：不动
        let rules = TranslateRules::new(&base);
        // 造一个 /proc 绑定，让 /proc 目标映射到宿主而非 rootfs
        let mut rules_b = TranslateRules::new(&base);
        rules_b.add_binding("/proc", "/proc");
        let stats = normalize_rootfs_symlinks(&base, &rules_b).unwrap();
        assert_eq!(stats.links_seen, 2);
        assert_eq!(stats.rewritten, 0);
        assert_eq!(fs::read_link(base.join("bin/ls")).unwrap(), Path::new("busybox"));
        assert_eq!(fs::read_link(base.join("bin/pself")).unwrap(), Path::new("/proc/self"));
        let _ = rules;
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn normalize_passthrough_mode_is_noop() {
        // -r / ：绝不能遍历/改写宿主
        let base = fresh();
        fs::write(base.join("realfile"), b"x").unwrap();
        std::os::unix::fs::symlink("/nonexistent-xyz", base.join("abslink")).unwrap();
        let rules = TranslateRules::new(Path::new("/"));
        let stats = normalize_rootfs_symlinks(Path::new("/"), &rules).unwrap();
        assert_eq!(stats, NormalizeStats::default());
        assert_eq!(fs::read_link(base.join("abslink")).unwrap(), Path::new("/nonexistent-xyz"));
        fs::remove_dir_all(&base).ok();
    }

    /// 造一棵树：/rf/bin/prog 是普通文件；/rf/lib 是指向 /rf/usr/lib 的**绝对**链接。
    fn abs_link_tree() -> PathBuf {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        fs::create_dir_all(base.join("usr/lib")).unwrap();
        fs::write(base.join("bin/prog"), b"x").unwrap();
        fs::write(base.join("usr/lib/libc.so.6"), b"x").unwrap();
        std::os::unix::fs::symlink("/usr/lib", base.join("lib")).unwrap();
        base
    }

    #[test]
    fn resolve_follows_absolute_intermediate_link() {
        let base = abs_link_tree();
        let rules = TranslateRules::new(&base);
        // /rf/lib/libc.so.6：/rf/lib 是绝对链接（→/usr/lib），必须映射回 rootfs
        let got = resolve_host_path(&base.join("lib/libc.so.6"), &rules, true);
        assert_eq!(got, base.join("usr/lib/libc.so.6"));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn resolve_no_follow_last_keeps_link() {
        let base = abs_link_tree();
        let rules = TranslateRules::new(&base);
        std::os::unix::fs::symlink("/bin/prog", base.join("bin/plink")).unwrap();
        // follow_last=false：最后一停（bin/plink 自身是链接）不跟随
        let got = resolve_host_path(&base.join("bin/plink"), &rules, false);
        assert_eq!(got, base.join("bin/plink"));
        // follow_last=true：跟随到目标
        let got2 = resolve_host_path(&base.join("bin/plink"), &rules, true);
        assert_eq!(got2, base.join("bin/prog"));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn resolve_relative_link_untouched() {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        fs::write(base.join("bin/busybox"), b"x").unwrap();
        std::os::unix::fs::symlink("busybox", base.join("bin/ls")).unwrap();
        let rules = TranslateRules::new(&base);
        // 相对链接：走查原样交还（内核解析才对）
        let got = resolve_host_path(&base.join("bin/ls"), &rules, true);
        assert_eq!(got, base.join("bin/ls"));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn resolve_missing_path_untouched() {
        let base = fresh();
        let rules = TranslateRules::new(&base);
        let got = resolve_host_path(&base.join("no/such/file"), &rules, true);
        assert_eq!(got, base.join("no/such/file"));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn resolve_absolute_link_loop_terminates() {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        std::os::unix::fs::symlink("/bin/b", base.join("bin/a")).unwrap();
        std::os::unix::fs::symlink("/bin/a", base.join("bin/b")).unwrap();
        let rules = TranslateRules::new(&base);
        // 必须终止（不死循环），结果交还内核裁环
        let got = resolve_host_path(&base.join("bin/a"), &rules, true);
        assert!(got.starts_with(&base));
        fs::remove_dir_all(&base).ok();
    }

    #[test]
    fn resolve_binding_target_maps_to_binding() {
        let base = fresh();
        fs::create_dir_all(base.join("bin")).unwrap();
        std::os::unix::fs::symlink("/proc/self/fd/0", base.join("bin/mystdin")).unwrap();
        let mut rules = TranslateRules::new(&base);
        rules.add_binding("/proc", "/proc");
        // 绑定目标：映射到宿主 /proc，不出 rootfs
        let got = resolve_host_path(&base.join("bin/mystdin"), &rules, true);
        assert_eq!(got, Path::new("/proc/self/fd/0"));
        fs::remove_dir_all(&base).ok();
    }
}

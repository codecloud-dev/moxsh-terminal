//! `proot` / `proot-distro` 等价 CLI（脚本兼容层，老用户零迁移，M4）。
//!
//! 定位：把文本形式的命令路由到 [`crate::proot::distro::DistroManager`]，
//! 返回执行结果文本（由 PTY 层回显）。参数解析手写（不引 clap，依赖极简），
//! 仅覆盖 proot-distro 的常用面：list/install/login/remove/backup/restore/shell。
//!
//! 透传约定：`compat::classify` 返回 `Shell` 的命令里，`proot` 与
//! `proot-distro` 由执行引擎先经 [`route`] 进入本模块（见 compat.rs 注释）。

use std::path::{Path, PathBuf};

use crate::proot::distro::DistroManager;

/// 命令路由入口：`command` 为 `proot` 或 `proot-distro`，`args` 为其余参数。
pub fn route(command: &str, args: &[&str], moxsh_root: &Path) -> String {
    match command {
        "proot-distro" => proot_distro(args, moxsh_root),
        // `proot` 原生参数面：moxsh 引擎内嵌（无独立二进制），给出等效用法说明
        // 并支持 -r/-0/-b/-w 组装预览；实际启动走 moxsh UI 或 login 命令。
        "proot" => proot_native(args, moxsh_root),
        other => format!("proot: 未知命令 {}", other),
    }
}

/// `proot-distro` 主路由（对齐上游子命令集）。
pub fn proot_distro(args: &[&str], moxsh_root: &Path) -> String {
    let mgr = DistroManager::new(moxsh_root);
    let (sub, rest) = args.split_first().map_or(("", &[][..]), |(s, r)| (*s, r));
    match sub {
        "list" => cmd_list(&mgr),
        "install" => cmd_install(&mgr, rest),
        "login" => cmd_login(&mgr, rest),
        "shell" => cmd_shell(&mgr, rest),
        "remove" => cmd_remove(&mgr, rest),
        "backup" => cmd_backup(&mgr, rest),
        "restore" => cmd_restore(&mgr, rest, moxsh_root),
        "help" | "--help" | "-h" | "" => HELP.to_string(),
        other => format!("proot-distro: 未知子命令 {}\n{}", other, HELP),
    }
}

/// `list`：已装/可装一览。
fn cmd_list(mgr: &DistroManager) -> String {
    let installed = mgr.list_installed().unwrap_or_default();
    let mut out = String::from("已安装发行版：\n");
    if installed.is_empty() {
        out.push_str("  （无）\n");
    }
    for d in &installed {
        out.push_str(&format!(
            "  {:<12} {}\n",
            d.id,
            d.rootfs.display()
        ));
    }
    out.push_str("\n可安装（proot-distro install <id>）：\n");
    for s in crate::proot::distro::builtin_specs() {
        let mark = if installed.iter().any(|d| d.id == s.id) { "（已安装）" } else { "" };
        out.push_str(&format!(
            "  {:<12} {:<8} {}{} 约{}MB\n",
            s.id, s.version, s.name, mark, s.size_mb
        ));
    }
    out
}

/// `install <id>`：安装（缓存归档优先，否则内部下载兜底）。
fn cmd_install(mgr: &DistroManager, args: &[&str]) -> String {
    let Some(id) = args.first() else {
        return "用法: proot-distro install <id>".into();
    };
    let spec = match DistroManager::find_spec(id) {
        Ok(s) => s,
        Err(e) => return format!("proot-distro: {}", e),
    };
    // --cache <path>：指定外部已下载归档（Android 场景由 UI 层下载）。
    let mut cache: Option<PathBuf> = None;
    let mut it = args.iter();
    while let Some(a) = it.next() {
        if *a == "--cache" {
            cache = it.next().map(PathBuf::from);
        }
    }
    let mut progress: Box<dyn FnMut(&str, u64, u64)> = Box::new(|msg, done, total| {
        let _ = (msg, done, total); // 文本 CLI 简化：完成时统一输出
    });
    match mgr.install(&spec, cache.as_deref(), &mut progress) {
        Ok(()) => install_hint(spec.id),
        Err(e) => format!("proot-distro: 安装失败: {}", e),
    }
}

/// 安装后的换源提示（国内优化，尊重发行版默认源，只提示不代改）。
fn install_hint(id: &str) -> String {
    let repo = match id {
        "ubuntu" | "debian" => "sed -i 's|deb.debian.org\\|archive.ubuntu.com|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list*",
        "kali" => "sed -i 's|http.kali.org|mirrors.ustc.edu.cn|g' /etc/apt/sources.list",
        "alpine" => "sed -i 's|dl-cdn.alpinelinux.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apk/repositories",
        _ => return "安装完成".into(),
    };
    format!(
        "安装完成。\n提示：国内网络建议切换镜像源（登录后执行）：\n  {}",
        repo
    )
}

/// `login <id>`：输出可执行的 proot 命令行（由 PTY 层执行）。
fn cmd_login(mgr: &DistroManager, args: &[&str]) -> String {
    let Some(id) = args.first() else {
        return "用法: proot-distro login <id>".into();
    };
    match mgr.login_cmd(id) {
        Ok(cmd) => cmd,
        Err(e) => format!("proot-distro: {}", e),
    }
}

/// `shell <id>`：非登录 shell 快捷方式（等价 login 但用 /bin/sh）。
fn cmd_shell(mgr: &DistroManager, args: &[&str]) -> String {
    let Some(id) = args.first() else {
        return "用法: proot-distro shell <id>".into();
    };
    let rootfs = mgr.distro_rootfs(id);
    if !rootfs.is_dir() {
        return format!("proot-distro: 发行版未安装: {}", id);
    }
    // 与 login 相同骨架，仅 shell 换 /bin/sh（非 -l）。
    match mgr.login_cmd(id) {
        Ok(mut cmd) => {
            // 把末尾 shell 段替换为 /bin/sh。
            if let Some(pos) = cmd.rfind(" /bin/") {
                cmd.truncate(pos);
                cmd.push_str(" /bin/sh");
            }
            cmd
        }
        Err(e) => format!("proot-distro: {}", e),
    }
}

/// `remove <id>`：删除。
fn cmd_remove(mgr: &DistroManager, args: &[&str]) -> String {
    let Some(id) = args.first() else {
        return "用法: proot-distro remove <id>".into();
    };
    match mgr.remove(id) {
        Ok(()) => format!("已删除 {}", id),
        Err(e) => format!("proot-distro: {}", e),
    }
}

/// `backup <id> <out.tar.gz>`：备份。
fn cmd_backup(mgr: &DistroManager, args: &[&str]) -> String {
    let (Some(id), Some(out)) = (args.first(), args.get(1)) else {
        return "用法: proot-distro backup <id> <out.tar.gz>".into();
    };
    match mgr.backup(id, Path::new(out)) {
        Ok(()) => format!("已备份到 {}", out),
        Err(e) => format!("proot-distro: {}", e),
    }
}

/// `restore <id> <tar>`：恢复。
fn cmd_restore(mgr: &DistroManager, args: &[&str], _moxsh_root: &Path) -> String {
    let (Some(id), Some(tar)) = (args.first(), args.get(1)) else {
        return "用法: proot-distro restore <id> <tar.tar.gz>".into();
    };
    match mgr.restore(id, Path::new(tar)) {
        Ok(()) => format!("已恢复 {}", id),
        Err(e) => format!("proot-distro: {}", e),
    }
}

/// `proot` 原生参数面：识别 -r/-0/-b/-w 并输出等效 moxsh 引擎配置预览。
fn proot_native(args: &[&str], _moxsh_root: &Path) -> String {
    let mut rootfs: Option<String> = None;
    let mut fake_root = false;
    let mut cwd = "/".to_string();
    let mut binds: Vec<String> = Vec::new();
    let mut it = args.iter();
    while let Some(a) = it.next() {
        match *a {
            "-r" | "--rootfs" => rootfs = it.next().map(|s| s.to_string()),
            "-0" | "--root-id" => fake_root = true,
            "-w" | "--cwd" => cwd = it.next().map(|s| s.to_string()).unwrap_or_else(|| "/".into()),
            "-b" | "--bind" => {
                if let Some(b) = it.next() {
                    binds.push(b.to_string());
                }
            }
            "-h" | "--help" => return PROOT_HELP.to_string(),
            other => return format!("proot: 暂不支持参数 {}（moxsh 引擎内嵌，UI 启动或 proot-distro login）", other),
        }
    }
    let Some(rootfs) = rootfs else {
        return "用法: proot -r <rootfs> [-0] [-b host:guest]... [-w /dir] -- cmd".into();
    };
    let mut out = format!(
        "moxsh proot 引擎配置预览：\n  rootfs: {}\n  fake_root(-0): {}\n  cwd(-w): {}\n  绑定(-b):\n",
        rootfs, fake_root, cwd
    );
    for b in binds {
        out.push_str(&format!("    {}\n", b));
    }
    out.push_str("（实际启动：moxsh 图形界面选择发行版，或 proot-distro login <id>）");
    out
}

/// proot-distro 帮助文本（对齐上游子命令集）。
const HELP: &str = "proot-distro（moxsh 兼容实现）
用法:
  proot-distro list                      列出已安装与可安装发行版
  proot-distro install <id> [--cache <tar>]   安装（--cache 指定已下载归档）
  proot-distro login <id>                进入发行版登录 shell
  proot-distro shell <id>                进入发行版非登录 shell
  proot-distro remove <id>               删除发行版
  proot-distro backup <id> <out.tar.gz>  备份
  proot-distro restore <id> <tar.tar.gz> 恢复";

/// proot 原生参数帮助。
const PROOT_HELP: &str = "proot（moxsh 引擎内嵌版，支持参数：-r/--rootfs -0/--root-id -b/--bind -w/--cwd）";

#[cfg(test)]
mod tests {
    use super::*;

    /// list 子命令：空目录输出"（无）"并列出可装清单。
    #[test]
    fn list_empty_shows_available() {
        let out = proot_distro(&["list"], Path::new("/nonexistent"));
        assert!(out.contains("（无）"));
        assert!(out.contains("ubuntu"));
        assert!(out.contains("alpine"));
    }

    /// 未知 id 安装报错文本。
    #[test]
    fn install_unknown_id() {
        let out = proot_distro(&["install", "windows"], Path::new("/tmp"));
        assert!(out.contains("未知发行版"));
    }

    /// login 未安装报错。
    #[test]
    fn login_not_installed() {
        let out = proot_distro(&["login", "ubuntu"], Path::new("/nonexistent"));
        assert!(out.contains("未安装"));
    }

    /// help 与未知子命令。
    #[test]
    fn help_and_unknown() {
        assert!(proot_distro(&["help"], Path::new("/x")).contains("proot-distro"));
        assert!(proot_distro(&["frobnicate"], Path::new("/x")).contains("未知子命令"));
    }

    /// proot 原生参数组装预览。
    #[test]
    fn proot_native_preview() {
        let out = route(
            "proot",
            &["-r", "/rf", "-0", "-b", "/dev:/dev", "-w", "/root"],
            Path::new("/x"),
        );
        assert!(out.contains("rootfs: /rf"));
        assert!(out.contains("fake_root(-0): true"));
        assert!(out.contains("cwd(-w): /root"));
        assert!(out.contains("/dev:/dev"));
    }
}

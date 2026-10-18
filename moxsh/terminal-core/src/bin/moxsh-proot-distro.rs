//! `moxsh-proot-distro` 独立可执行文件入口（原版 Termux 用户专用）。
//!
//! 与 `moxsh-proot` 的分工：后者按上游 proot 参数面跑**已有 rootfs**；
//! 本二进制管理 rootfs 本身（list/install/login/shell/remove/backup/restore），
//! 语义对齐上游 `proot-distro` 子命令集。安装由一键脚本
//! `scripts/install-moxsh-proot.sh` 一并落盘。
//!
//! 目录布局（Termux 默认）：`$HOME/.moxsh/`
//!
//! - `containers/<id>/rootfs`   发行版根文件系统
//! - `cache/`                   下载的归档缓存
//!
//! 可用 `MOXSH_ROOT` 环境变量覆盖根目录。

use std::env;
use std::path::{Path, PathBuf};
use std::process;

use moxshcore::proot::distro::DistroManager;
use moxshcore::proot::{cli, ProotConfig};

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let root = default_root();
    let code = match args.first().map(String::as_str) {
        // login/shell 需要真起引擎（其余子命令是文本输出，走共享路由）。
        Some("login") | Some("shell") => exec_login(&root, &args),
        _ => {
            let refs: Vec<&str> = args.iter().map(String::as_str).collect();
            print!("{}", cli::proot_distro(&refs, &root));
            0
        }
    };
    process::exit(code);
}

/// 默认根目录：`$MOXSH_ROOT` > `$HOME/.moxsh`。
/// Termux 的 `$HOME` 即 `/data/data/com.termux/files/home`，落在 App 私有
/// 目录内（exec 允许、noexec 外部存储之外），符合 Android W^X 约束。
fn default_root() -> PathBuf {
    if let Some(r) = env::var_os("MOXSH_ROOT") {
        return PathBuf::from(r);
    }
    let home = env::var_os("HOME")
        .unwrap_or_else(|| PathBuf::from("/data/data/com.termux/files/home").into_os_string());
    Path::new(&home).join(".moxsh")
}

/// login/shell：解析 rootfs → 结构化配置 → 交引擎执行（不 textualize 成
/// 上游 proot 命令行再解析——结构化路径少一层往返，也不会丢选项）。
fn exec_login(root: &Path, args: &[String]) -> i32 {
    let sub = args[0].as_str();
    let Some(id) = args.get(1) else {
        eprintln!("用法: moxsh-proot-distro {} <id>", sub);
        return 2;
    };
    let mgr = DistroManager::new(root);
    let rootfs = mgr.distro_rootfs(id);
    if !rootfs.is_dir() {
        eprintln!(
            "moxsh-proot-distro: 发行版未安装: {}（先 install {}）",
            id, id
        );
        return 2;
    }
    // 登录 shell 探测：有 bash 用 bash -l，否则 sh -l（与 login_cmd 一致）。
    let shell: Vec<String> = if rootfs.join("bin/bash").exists() {
        if sub == "login" {
            vec!["/bin/bash".into(), "-l".into()]
        } else {
            vec!["/bin/bash".into()]
        }
    } else if sub == "login" {
        vec!["/bin/sh".into(), "-l".into()]
    } else {
        vec!["/bin/sh".into()]
    };

    let cfg = ProotConfig::with_distro_defaults(&rootfs);
    // env_command() 形如 ["/usr/bin/env", "-i", "HOME=...", ...]，跳过前两个
    // 前缀词即纯 KEY=VALUE 列表（spawn 直接 execve 用，无需再包一层 env）。
    let env: Vec<String> = cfg.env_command().into_iter().skip(2).collect();
    cli::run_config(&cfg, &shell, &env)
}

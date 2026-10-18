//! `proot` / `proot-distro` 等价 CLI（脚本兼容层，老用户零迁移，M4）。
//!
//! 定位：把文本形式的命令路由到 [`crate::proot::distro::DistroManager`]，
//! 返回执行结果文本（由 PTY 层回显）。参数解析手写（不引 clap，依赖极简），
//! 仅覆盖 proot-distro 的常用面：list/install/login/remove/backup/restore/shell。
//!
//! 透传约定：`compat::classify` 返回 `Shell` 的命令里，`proot` 与
//! `proot-distro` 由执行引擎先经 [`route`] 进入本模块（见 compat.rs 注释）。
//!
//! ## 独立二进制入口（[`run_native`]）
//!
//! 除 App 内嵌路径外，本 crate 还编出 `moxsh-proot` 独立可执行文件
//! （`src/bin/moxsh-proot.rs`）：让**原版 Termux 用户**不装 moxsh App 也能
//! 用上同一套自研引擎（Rust ptrace 路径翻译，非上游 C 版 proot 的封装）。
//! 参数面与上游 proot 常用选项对齐：`-r/-0/-b/-w/-R/-S/-e`，`--` 后接命令。

use std::path::{Path, PathBuf};

use crate::proot::distro::DistroManager;
use crate::proot::elf::{resolve_exec_chain, ExecPlan};
use crate::proot::net;
use crate::proot::ptrace::Tracer;
use crate::proot::translate::TranslateRules;

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

/// 独立二进制入口：解析上游风格参数，真起 ptrace 引擎跑目标命令。
///
/// 返回进程退出码（0 = 成功；非 0 = 引擎报错或目标命令退出码）。
/// 错误信息写 stderr（不混入 stdout，管道/重定向不受污染）。
///
/// 与 [`route`] 的分工：`route` 服务 App 内嵌 shell 的**文本交互**；
/// 本函数服务**真执行**（原版 Termux 用户直接 `moxsh-proot ...`）。
pub fn run_native(args: &[String]) -> i32 {
    let cfg = match parse_native_args(args) {
        Ok(c) => c,
        Err(e) => {
            if e.is_empty() {
                // -h/--help：正常请求而非错误——用法走 stdout、退出 0
                // （对齐上游 proot 与常见 CLI 惯例；安装脚本自检也依赖此语义）。
                print_usage(&mut std::io::stdout());
                return 0;
            }
            eprintln!("moxsh-proot: {}", e);
            usage_to_stderr();
            return 2;
        }
    };
    let mut cmd = cfg.command.clone();
    if cmd.is_empty() {
        // 对齐上游：未给命令时默认 /bin/sh。
        cmd.push("/bin/sh".to_string());
    }
    run_config(&cfg.config, &cmd, &cfg.env)
}

/// 用结构化配置直接起引擎跑目标命令（login/shell 与 [`run_native`] 共用）。
///
/// 与 `run_native` 的分工：后者解析上游风格 CLI 参数面；本函数服务
/// "已有 [`ProotConfig`] 就直接执行"的场景（`moxsh-proot-distro login`）。
/// 返回进程退出码（0 = 成功；非 0 = 引擎报错或目标命令退出码）。
pub fn run_config(
    config: &crate::proot::ProotConfig,
    command: &[String],
    env: &[String],
) -> i32 {
    let rules = config.to_rules();
    let initial_cwd = initial_cwd_for(config, &rules);
    // 初始 execve 宿主侧预解析（见 prepare_initial_exec 文档）：
    // 子进程首停是 exec 后的 plain SIGTRAP，syscall-enter 停被取代——
    // 初始命令无法在 tracer 内拦截，必须在这里解析成内核能直接加载的形式。
    let (spawn_cmd, guest_prog) = prepare_initial_exec(command, &rules, &config.cwd);
    let mut tracer = Tracer::new(rules);
    if let Some(p) = guest_prog {
        tracer.set_initial_guest_prog(p);
    }
    let cmd_refs: Vec<&str> = spawn_cmd.iter().map(|s| s.as_str()).collect();
    let env_refs: Vec<&str> = env.iter().map(|s| s.as_str()).collect();
    let child = match Tracer::spawn_traced_with_cwd(&cmd_refs, &env_refs, initial_cwd.as_deref()) {
        Ok(p) => p,
        Err(e) => {
            eprintln!("moxsh-proot: 启动失败: {}", e);
            return 126;
        }
    };
    match tracer.run(child) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("moxsh-proot: 引擎错误: {}", e);
            125
        }
    }
}

/// 初始 execve 的宿主侧预解析（guest 解释器问题的入口解法，M6）。
///
/// 子进程 TRACEME 后的首个停止是 exec **之后** 的 plain SIGTRAP——
/// syscall-enter 停被 exec 陷阱取代，初始 execve 无法在 tracer 内拦截
/// （SIGSTOP 握手方案在本环境已被实测证伪，见 ptrace.rs 首停注释）。
/// 因此初始命令必须在宿主侧解析成「内核能直接加载的形式」：
///
/// - **动态 ELF**（带 PT_INTERP）⇒ 显式调用 guest 自带 loader：
///   `execve(host_ldso, [ldso, prog, ...原参数])`——与 qemu-user 同款。
///   musl/glibc 的 loader 均 standalone（无 PT_INTERP，已实证），内核
///   可直接加载；此后 loader 的所有 openat 都落在引擎翻译链上；
/// - **shebang 脚本** ⇒ 解释器进链；解释器若仍是动态 ELF，继续上溯到
///   它的 loader（链式走查，与内核 shebang 语义同构）；
/// - **静态 ELF / 非 ELF / 链尾 loader 缺失** ⇒ Direct：以翻译后的
///   host 路径 exec（文件不存在时内核给出权威 ENOENT，不会静默落到
///   宿主同名文件——`/bin/sh` 在 glibc 宿主上真实存在，直穿会酿成
///   「guest sh 变宿主 dash」类事故）。
///
/// 返回 `(spawn argv, guest 原始程序路径)`；后者非空时调用方应设入
/// [`Tracer::set_initial_guest_prog`]（comm 伪装 + `/proc/self/exe` 还原）——
/// exec 的是 loader，这两处不修则 guest 视角全错。
fn prepare_initial_exec(
    command: &[String],
    rules: &TranslateRules,
    guest_cwd: &str,
) -> (Vec<String>, Option<String>) {
    let Some(prog) = command.first() else {
        return (vec!["/bin/sh".to_string()], None);
    };
    // 裸命令名（无 `/`）：无 PATH 信息无法可靠解析，Direct 交内核。
    if !prog.contains('/') {
        return (command.to_vec(), None);
    }
    // guest 视角的规范绝对路径（相对路径按 guest 初始 cwd 拼）。
    let guest_path = if prog.starts_with('/') {
        crate::proot::translate::normalize(prog)
            .to_string_lossy()
            .into_owned()
    } else {
        let joined = if guest_cwd.starts_with('/') {
            format!("{}/{}", guest_cwd.trim_end_matches('/'), prog)
        } else {
            prog.clone()
        };
        crate::proot::translate::normalize(&joined)
            .to_string_lossy()
            .into_owned()
    };
    let initial_host = rules.guest_to_host(&guest_path);
    match resolve_exec_chain(&guest_path, &initial_host, rules) {
        ExecPlan::Rewrite { exec_host, argv_prefix } => {
            // argv 布局：[loader 宿主路径, loader guest 路径, ...链上程序, 原参数]。
            // argv[0] 用宿主路径（内核 exec 需要真实文件）；argv[1] 起是
            // guest 视角——loader 按 argv[1] 找目标程序，其 openat 被翻译。
            let mut out = Vec::with_capacity(argv_prefix.len() + command.len());
            out.push(exec_host);
            out.extend(argv_prefix);
            out.extend(command[1..].iter().cloned());
            (out, Some(guest_path))
        }
        ExecPlan::Direct => {
            // host 路径 exec：不存在 → 内核 ENOENT（exit 127），
            // 杜绝静默落到宿主同名文件。comm/exe 天然正确，无需覆盖。
            let mut out = vec![initial_host.to_string_lossy().into_owned()];
            out.extend(command[1..].iter().cloned());
            (out, None)
        }
    }
}

/// 初始 cwd 落地（M5.2：此前 -w 只存进规则集、从未真正切换子进程 cwd）。
///
/// - 显式 `-w`：绝对路径按规则翻译（走 rootfs/绑定）；相对路径——有 rootfs
///   时按 rootfs 解析（guest 视角相对量），无 rootfs 时 `None`（继承 tracer
///   cwd，按宿主当前目录解析相对量）；
/// - 未给 `-w` 但有 rootfs：cwd = rootfs 根（对齐 proot `-r` 语义，否则
///   子进程继承 tracer 宿主 cwd，guest 直接看到 rootfs 外真实路径）；
/// - 两者皆无：`None` = 继承 tracer cwd（对齐上游 proot 无 `-r` 行为）。
///
/// 纯函数（除 `guest_to_host` 的规则查询外无副作用），便于单测钉死语义。
fn initial_cwd_for(
    cfg: &crate::proot::ProotConfig,
    rules: &crate::proot::translate::TranslateRules,
) -> Option<std::path::PathBuf> {
    if cfg.cwd != "/" {
        let p = std::path::Path::new(&cfg.cwd);
        if p.is_absolute() {
            Some(rules.guest_to_host(&cfg.cwd))
        } else if !cfg.rootfs.as_os_str().is_empty() {
            Some(cfg.rootfs.join(p))
        } else {
            None
        }
    } else if !cfg.rootfs.as_os_str().is_empty() {
        Some(rules.guest_to_host("/"))
    } else {
        None
    }
}

/// `run_native` 的解析产物（与 [`ProotConfig`] + 待执行命令分离，便于单测）。
#[derive(Debug)]
struct NativeArgs {
    config: crate::proot::ProotConfig,
    command: Vec<String>,
    env: Vec<String>,
}

/// 解析 `moxsh-proot` 参数面（对齐上游 proot 常用选项）。
///
/// `-b` 语法与上游一致：`host_path:guest_location`（host 在前）。
/// guest_location 尾缀 `!` = 不反向还原（沿用本仓 translate 层语义）。
fn parse_native_args(args: &[String]) -> Result<NativeArgs, String> {
    let mut rootfs: Option<PathBuf> = None;
    let mut fake_root = false;
    let mut cwd = "/".to_string();
    let mut binds: Vec<(String, String)> = Vec::new();
    let mut env: Vec<String> = Vec::new();
    let mut recommended = false;
    let mut minimal = false;
    let mut command: Vec<String> = Vec::new();

    let mut i = 0;
    while i < args.len() {
        let a = args[i].as_str();
        match a {
            "-h" | "--help" => {
                return Err(String::new()); // 空错误 → 调用方只打印用法
            }
            "-r" | "--rootfs" => {
                i += 1;
                let v = args.get(i).ok_or("-r 缺少参数")?;
                rootfs = Some(PathBuf::from(v));
            }
            "-0" | "--root-id" => fake_root = true,
            "-b" | "--bind" | "-m" | "--mount" => {
                i += 1;
                let v = args.get(i).ok_or("-b 缺少参数")?;
                binds.push(parse_binding(v)?);
            }
            "-w" | "--pwd" | "--cwd" => {
                i += 1;
                let v = args.get(i).ok_or("-w 缺少参数")?;
                cwd = v.to_string();
            }
            "-e" | "--env" => {
                i += 1;
                let v = args.get(i).ok_or("-e 缺少参数")?;
                if !v.contains('=') {
                    return Err(format!("-e 需要 KEY=VALUE 形式，得到 {}", v));
                }
                env.push(v.clone());
            }
            "-R" => {
                i += 1;
                let v = args.get(i).ok_or("-R 缺少参数")?;
                rootfs = Some(PathBuf::from(v));
                recommended = true;
            }
            "-S" => {
                i += 1;
                let v = args.get(i).ok_or("-S 缺少参数")?;
                rootfs = Some(PathBuf::from(v));
                minimal = true;
            }
            "--" => {
                i += 1;
                command.extend_from_slice(&args[i..]);
                break;
            }
            other if other.starts_with("--rootfs=") => {
                rootfs = Some(PathBuf::from(&other["--rootfs=".len()..]));
            }
            other if other.starts_with("--bind=") || other.starts_with("--mount=") => {
                let v = other.split_once('=').map(|x| x.1).unwrap_or("");
                binds.push(parse_binding(v)?);
            }
            other if other.starts_with("--cwd=") => {
                cwd = other["--cwd=".len()..].to_string();
            }
            other if other.starts_with("--pwd=") => {
                cwd = other["--pwd=".len()..].to_string();
            }
            other if other.starts_with("--env=") => {
                let v = &other["--env=".len()..];
                if !v.contains('=') {
                    return Err(format!("-e 需要 KEY=VALUE 形式，得到 {}", v));
                }
                env.push(v.to_string());
            }
            other if other.starts_with('-') => {
                // 注意：宏参数里的相邻字符串字面量**不会**隐式拼接（与普通表达式
                // 不同），必须写成单个字面量，否则 rustc 报 "expected `,`"。
                return Err(format!(
                    "不支持的选项 {}（可用项见 moxsh-proot --help；上游 -q/-k/-v 等未实现，勿假装支持）",
                    other
                ));
            }
            // 首个非选项词即命令起点（与上游"选项后接命令"一致，无需强制 --）。
            _ => {
                command = args[i..].to_vec();
                break;
            }
        }
        i += 1;
    }

    let Some(rf) = rootfs else {
        return Err("缺少 -r/--rootfs（或用 -R/-S 简写）".into());
    };
    if !rf.is_dir() {
        return Err(format!("rootfs 目录不存在: {}", rf.display()));
    }

    let mut config = if recommended {
        crate::proot::ProotConfig::with_recommended_bindings(&rf)
    } else if minimal {
        crate::proot::ProotConfig::with_minimal_bindings(&rf)
    } else {
        crate::proot::ProotConfig::new(&rf)
    };
    config.fake_root = fake_root;
    config.cwd = cwd;
    for (guest, host) in binds {
        config.bind(&guest, &host);
    }
    // 命令显式指定 -e 时以所给环境为准；否则 CLI 走"继承 tracer 环境"路径
    // （env 为空时 spawn_traced_with_env 沿用历史 NULL envp 语义——
    //  注意：那会得到空环境。故 CLI 在未给 -e 时主动填入 std::env::vars）。
    let final_env = if env.is_empty() {
        std::env::vars()
            .map(|(k, v)| format!("{}={}", k, v))
            .collect()
    } else {
        env
    };
    Ok(NativeArgs {
        config,
        command,
        env: final_env,
    })
}

/// 解析上游 `-b host:guest`（host 在前）。返回 `(guest, host)`——
/// 与 [`crate::proot::ProotConfig::bind`] 的参数顺序一致。
fn parse_binding(spec: &str) -> Result<(String, String), String> {
    // 单路径形式（无冒号）：host 与 guest 同路径（上游默认语义）。
    let (host, guest) = match spec.split_once(':') {
        Some((h, g)) => (h.to_string(), g.to_string()),
        None => (spec.to_string(), spec.to_string()),
    };
    if host.is_empty() || guest.is_empty() {
        return Err(format!("绑定参数不合法: {}（期望 host:guest）", spec));
    }
    // guest 尾缀 ! = 不反向还原（沿用 translate 层 no-isolate 语义）。
    if let Some(g) = guest.strip_suffix('!') {
        return Ok((format!("!{}", g), host));
    }
    Ok((guest, host))
}

/// 用法文本（`-h` 走 stdout 退出 0；参数错误走 stderr 退出 2）。
fn print_usage(w: &mut dyn std::io::Write) {
    write!(
        w,
        "用法: moxsh-proot [-r rootfs | -R rootfs | -S rootfs] [-0] \
         [-b host:guest]... [-w dir] [-e K=V]... [--] 命令 [参数...]\n\
         \n\
           -r, --rootfs <path>   guest 根目录\n\
           -R <path>            -r + 推荐绑定集（/etc 元文件、/tmp、/run…）\n\
           -S <path>            -r + 最小绑定集（装包场景）\n\
           -0, --root-id        fake root（uid/gid 显示为 0，伪装特权成功）\n\
           -b, --bind <h:g>     绑定宿主路径到 guest（host:guest，可多次）\n\
           -w, --cwd <dir>      初始工作目录\n\
           -e, --env <K=V>      注入环境变量（给了则清空继承环境）\n\
           --                   其后按原样视为命令\n\
         \n\
         未给命令时默认 /bin/sh；未给 -e 时继承当前环境。\n"
    )
    .ok();
}

fn usage_to_stderr() {
    print_usage(&mut std::io::stderr());
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
        "net" => cmd_net(&mgr, rest),
        "help" | "--help" | "-h" | "" => HELP.to_string(),
        other => format!("proot-distro: 未知子命令 {}\n{}", other, HELP),
    }
}

/// `net [id]`：容器网络边界报告（M7 网络栈重写）。
///
/// 无参数时报告宿主侧网络环境（DNS/IPv6/出网探测）；带发行版 id 时
/// 叠加该 rootfs 的 resolv.conf/hosts 现状。纯报告，不改配置。
fn cmd_net(mgr: &DistroManager, args: &[&str]) -> String {
    match args.first().copied() {
        None | Some("") => {
            // 宿主环境画像（未指定发行版）
            let profile = net::build_profile("");
            net::network_report(&profile, 0, net::probe_egress())
        }
        Some(id) => match mgr.net_report(id) {
            Ok(r) => r,
            Err(e) => format!("proot-distro net: {}\n", e),
        },
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
    let mut progress: crate::ProgressCallback = Box::new(|msg, done, total| {
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

    /// -h/--help：哨兵空错误——run_native 据此走 stdout 用法 + 退出 0
    /// （安装脚本自检依赖此退出码语义）。
    #[test]
    fn parse_native_args_help_sentinel() {
        assert!(parse_native_args(&["--help".to_string()])
            .unwrap_err()
            .is_empty());
        assert!(parse_native_args(&["-h".to_string()])
            .unwrap_err()
            .is_empty());
    }

    /// -b 绑定语义：上游 `host:guest`（host 在前），parse_binding 返回
    /// `(guest, host)`——顺序错了会把宿主路径当 guest 挂，隔离直接反了。
    #[test]
    fn parse_binding_host_first() {        assert_eq!(
            parse_binding("/data/data/x:/rf").unwrap(),
            ("/rf".to_string(), "/data/data/x".to_string())
        );
        // 单路径形式（无冒号）：host 与 guest 同路径（上游默认语义）。
        assert_eq!(
            parse_binding("/proc").unwrap(),
            ("/proc".to_string(), "/proc".to_string())
        );
        // guest 尾缀 ! = 不反向还原：转成 "!guest" 前缀（translate 层 no-isolate）。
        assert_eq!(
            parse_binding("/host:/guest!").unwrap(),
            ("!/guest".to_string(), "/host".to_string())
        );
        // 空边非法。
        assert!(parse_binding(":/guest").is_err());
        assert!(parse_binding("/host:").is_err());
    }

    /// parse_native_args 全选项面：-R 推荐绑定 + -0 + -w + -e + `--` 命令切分。
    /// 依赖真实目录（rf.is_dir() 校验），用临时目录隔离。
    #[test]
    fn parse_native_args_full_surface() {
        let rf = std::env::temp_dir().join("moxsh-test-rf");
        std::fs::create_dir_all(&rf).unwrap();
        let rf_s = rf.to_str().unwrap();

        // 缺 -r 报错（默认不假设 rootfs）。
        let err = parse_native_args(&["/bin/sh".to_string()]).unwrap_err();
        assert!(err.contains("缺少 -r"));

        // rootfs 不存在报错。
        let err = parse_native_args(&["-r".to_string(), "/no/such/dir".to_string()])
            .unwrap_err();
        assert!(err.contains("rootfs 目录不存在"));

        // -R + -0 + -w + -e + -- 命令切分。
        let na = parse_native_args(&[
            "-R".to_string(),
            rf_s.to_string(),
            "-0".to_string(),
            "-b".to_string(),
            "/storage/emulated/0:/sdcard".to_string(),
            "-w".to_string(),
            "/root".to_string(),
            "-e".to_string(),
            "TERM=xterm".to_string(),
            "--".to_string(),
            "/bin/sh".to_string(),
            "-c".to_string(),
            "echo hi".to_string(),
        ])
        .unwrap();
        assert_eq!(na.config.rootfs, rf);
        assert!(na.config.fake_root);
        assert_eq!(na.config.cwd, "/root");
        // 显式 -b 应叠加进绑定表（host:guest → (guest, host)）。
        assert!(
            na.config
                .bindings
                .iter()
                .any(|(g, h)| g == "/sdcard" && h == "/storage/emulated/0"),
            "显式 -b 应生效: {:?}",
            na.config.bindings
        );
        // -e 给了即清空继承：env 恰为所给一条。
        assert_eq!(na.env, vec!["TERM=xterm".to_string()]);
        // `--` 之后原样进命令（连 -c 也不解析）。
        assert_eq!(
            na.command,
            vec![
                "/bin/sh".to_string(),
                "-c".to_string(),
                "echo hi".to_string()
            ]
        );
        // 未知选项报错且不带假支持。
        let err = parse_native_args(&["-q".to_string(), rf_s.to_string()]).unwrap_err();
        assert!(err.contains("不支持的选项"));

        // 未给命令时 command 为空（调用方默认 /bin/sh）。
        let na2 = parse_native_args(&["-r".to_string(), rf_s.to_string()]).unwrap();
        assert!(na2.command.is_empty());
        assert!(!na2.config.fake_root);
        assert_eq!(na2.config.cwd, "/");
        std::fs::remove_dir_all(&rf).ok();
    }

    /// 初始 cwd 语义（M5.2）四种组合：显式 -w 绝对 / 显式 -w 相对 /
    /// 未给 -w 有 rootfs / 两者皆无。
    #[test]
    fn initial_cwd_semantics() {
        let rf = std::env::temp_dir().join("moxsh-test-cwd");
        std::fs::create_dir_all(&rf).unwrap();

        // 1) 显式 -w 绝对路径：走规则翻译（rootfs 前缀命中 → 原样；否则拼 rootfs）。
        let mut cfg = crate::proot::ProotConfig::new(&rf);
        cfg.cwd = "/home".to_string();
        let rules = cfg.to_rules();
        assert_eq!(
            initial_cwd_for(&cfg, &rules),
            Some(rf.join("home")),
            "-w 绝对路径应翻译进 rootfs"
        );

        // 2) 显式 -w 相对路径：按 rootfs 解析（guest 视角相对量）。
        let mut cfg = crate::proot::ProotConfig::new(&rf);
        cfg.cwd = "build".to_string();
        let rules = cfg.to_rules();
        assert_eq!(initial_cwd_for(&cfg, &rules), Some(rf.join("build")));

        // 3) 未给 -w（默认 "/"）且有 rootfs：cwd = rootfs 根。
        let cfg = crate::proot::ProotConfig::new(&rf);
        let rules = cfg.to_rules();
        assert_eq!(
            initial_cwd_for(&cfg, &rules),
            Some(rf.clone()),
            "未给 -w 时默认进 rootfs 根，不泄露 tracer 宿主 cwd"
        );

        // 4) 无 rootfs 也无 -w：None = 继承 tracer cwd（上游无 -r 行为）。
        let mut cfg = crate::proot::ProotConfig::new(Path::new(""));
        cfg.rootfs = PathBuf::new();
        let rules = cfg.to_rules();
        assert_eq!(initial_cwd_for(&cfg, &rules), None);

        // 5) 无 rootfs + 显式相对 -w：None（按 tracer 当前 cwd 解析）。
        let mut cfg = crate::proot::ProotConfig::new(Path::new(""));
        cfg.rootfs = PathBuf::new();
        cfg.cwd = "somedir".to_string();
        let rules = cfg.to_rules();
        assert_eq!(initial_cwd_for(&cfg, &rules), None);

        std::fs::remove_dir_all(&rf).ok();
    }
}

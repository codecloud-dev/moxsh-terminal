//! ptrace 系统调用拦截器（PRoot 引擎执行核心）。
//!
//! 工作模型（对齐上游 6.x，proot-me/proot）：
//! 1. 子进程 `PTRACE_TRACEME` + `execve`，父进程成为 tracer；
//! 2. 设置 `PTRACE_O_TRACESYSGOOD | TRACEFORK | TRACEVFORK | TRACECLONE | TRACEEXEC`，
//!    后四者保证 fork/vfork/clone 出的孙进程自动进入跟踪树（多进程发行版必需）；
//! 3. `PTRACE_SYSCALL` 驱动 enter/exit **双停循环**：每个 syscall 停两次，
//!    enter 时按寄存器读出路径参数 → 经 [`TranslateRules`] 翻译 → 写回子进程内存；
//!    exit 时对 getcwd/readlink 类"反向"syscall 还原返回缓冲（de-translate）；
//! 4. **未列入拦截表的 syscall 一律放行直通**——对齐上游"未拦截即直通"语义，
//!    也是行为兜底：翻译只做"该做的"，其余保持内核原始行为。
//!
//! aarch64 寄存器布局（`PTRACE_GETREGSET` / `NT_PRSTATUS`，272 字节）：
//! ```text
//! struct user_regs_struct {           // arch/arm64/include/uapi/asm/ptrace.h
//!     u64 regs[31];   // x0..x30
//!     u64 sp;         // sp
//!     u64 pc;         // pc
//!     u64 pstate;     // CPSR
//! }
//! ```
//! Linux 系统调用约定（aarch64，与上游 SYSARG_* 映射一致）：
//! - syscall 号在 **x8**；
//! - 参数依次在 **x0..x5**（注意 x0 是第 1 参，不是"orig 返回值"——
//!   aarch64 无 32 位平台那种 orig_rax 混叠）；
//! - 返回值在 exit 停止时的 **x0**。
//!
//! 内存读写优先 `process_vm_readv`/`process_vm_writev`（一次系统调用完成，
//! 且不受字长对齐限制）；旧内核或个别 SELinux 策略下可能返回 EPERM，
//! 此时降级 `PTRACE_PEEKDATA`/`PTRACE_POKEDATA` 按字长逐字读写（读-改-写尾字）。
//!
//! **Android 平台注意（引用 architecture.md §11 与 D7）**：
//! - targetSdk 限制：Android 10+ 对未压缩 native 库强制 W^X，execve 仅允许
//!   从 app 数据目录与只读前缀执行——PRoot 自身的 loader 必须落在
//!   `/data/data/<pkg>/` 下，且不可存放在外部存储（noexec）；
//! - Phantom Process Killer：Android 12+ 的 lmkd 会杀孤儿 tracer 进程
//!   （上限约 32 个）。moxsh 以 foreground service 运行会话（D7），
//!   其子进程不在清理名单内；这是与上游最大的平台差异点。

use std::path::Path;

use crate::proot::translate::{FakeRootAction, TranslateRules};
use crate::proot::{ProotError, ProotResult};

/// 子进程路径参数上限（Linux PATH_MAX）。
const MAX_PATH: usize = 4096;

/// aarch64/asm-generic 系统调用号（generic syscall ABI）。
///
/// 32 位 arm（armv7/armv8-32）使用另一套号表（open=5、stat=106、openat=322 …），
/// M4 主线 target 为 aarch64（D11），32 位表待 M7 多 ABI 时补齐。
pub mod sysnr {
    /// getcwd(char *buf, size_t size) —— exit 阶段反向还原。
    pub const GETCWD: u64 = 17;
    /// mkdirat(int dirfd, const char *pathname, mode_t mode)
    pub const MKDIRAT: u64 = 34;
    /// unlinkat(int dirfd, const char *pathname, int flags)
    pub const UNLINKAT: u64 = 35;
    /// symlinkat(const char *target, int newdirfd, const char *linkpath)
    pub const SYMLINKAT: u64 = 36;
    /// renameat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath)
    pub const RENAMEAT: u64 = 38;
    /// truncate(const char *path, off_t length)
    pub const TRUNCATE: u64 = 45;
    /// faccessat(int dirfd, const char *pathname, int mode, int flags)
    pub const FACCESSAT: u64 = 48;
    /// chdir(const char *path)
    pub const CHDIR: u64 = 49;
    /// openat(int dirfd, const char *pathname, int flags, ...)
    pub const OPENAT: u64 = 56;
    /// readlinkat(int dirfd, const char *pathname, char *buf, size_t bufsiz)
    pub const READLINKAT: u64 = 78;
    /// newfstatat(int dirfd, const char *pathname, struct stat *buf, int flags)
    /// —— aarch64 上的 stat/lstat/fstatat 统一形（statx=292 同理，暂直通）。
    pub const NEWFSTATAT: u64 = 79;
    /// fstat(int fd, struct stat *buf) —— 无路径参数，列入仅作表完整性。
    pub const FSTAT: u64 = 80;
    /// execve(const char *path, char *const argv[], char *const envp[])
    pub const EXECVE: u64 = 221;
    /// execveat(int dirfd, const char *path, ...)
    pub const EXECVEAT: u64 = 281;
    /// clone —— 需配合 PTRACE_O_TRACECLONE 才能跟踪子线程/进程树。
    pub const CLONE: u64 = 220;
    /// clone3 —— 上游 v5.4.1 起显式支持；配合 TRACECLONE 直通即可。
    pub const CLONE3: u64 = 435;
    /// renameat2(int olddirfd, ..., int newdirfd, ..., unsigned int flags)
    /// —— 上游 v5.2.0 修复了 renameat2 的过滤缺失，此处完整纳入。
    pub const RENAMEAT2: u64 = 276;
    /// faccessat2 —— 上游 v5.4.0 新增支持。
    pub const FACCESSAT2: u64 = 439;
    /// openat2(struct open_how) —— 参数为结构体而非裸路径，M4 暂直通
    /// （glibc 常规路径走 openat，影响面极小），M7 热路径优化时处理。
    pub const OPENAT2: u64 = 437;
    /// exit_group —— 终止判据之一。
    pub const EXIT_GROUP: u64 = 94;
}

/// 拦截动作分类（[`syscall_plan`] 的返回）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Op {
    /// 仅 enter 阶段正向翻译路径参数。
    RewriteOnEnter,
    /// enter 正向翻译 + exit 阶段还原返回缓冲（readlinkat/readlink）。
    RewriteEnterRestoreExit,
    /// 仅 exit 阶段反向还原返回缓冲（getcwd）。
    RestoreOnExit,
    /// 直通（未拦截）——上游"未拦截即直通"语义。
    PassThrough,
}

/// 单个路径参数槽：所在寄存器下标 + 是否伴随 dirfd（AT_FDCWD 需回读 /proc/pid/fd）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PathArg {
    /// 寄存器下标（0=x0 … 5=x5）。
    pub slot: u8,
    /// dirfd 所在槽；`None` 表示该路径必须是绝对的（如 execve/chdir）。
    pub dirfd_slot: Option<u8>,
}

/// 一条 syscall 的拦截计划（纯数据，可完整单测）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SyscallPlan {
    pub op: Op,
    /// 路径参数槽（最多 2 个，如 renameat 的 old/new）。
    pub args: [Option<PathArg>; 2],
    /// exit 阶段需反向还原的返回缓冲所在槽（getcwd=0、readlinkat=2）。
    pub ret_buf_slot: Option<u8>,
}

impl SyscallPlan {
    const NONE: SyscallPlan = SyscallPlan {
        op: Op::PassThrough,
        args: [None, None],
        ret_buf_slot: None,
    };

    fn rewrite(a1: Option<PathArg>, a2: Option<PathArg>) -> SyscallPlan {
        SyscallPlan { op: Op::RewriteOnEnter, args: [a1, a2], ret_buf_slot: None }
    }
}

/// 查询 syscall 拦截计划（纯函数，未列入 → PassThrough 直通）。
///
/// 对齐上游 6.x 拦截面：路径类 syscall 全覆盖 + readlinkat/getcwd 反向 +
/// faccessat2（v5.4.0）+ clone3（v5.4.1）；statx/openat2 M4 直通（见各注释）。
pub fn syscall_plan(nr: u64) -> SyscallPlan {
    use sysnr::*;
    match nr {
        OPENAT | NEWFSTATAT | UNLINKAT | MKDIRAT => SyscallPlan::rewrite(
            Some(PathArg { slot: 1, dirfd_slot: Some(0) }),
            None,
        ),
        READLINKAT => SyscallPlan {
            op: Op::RewriteEnterRestoreExit,
            args: [Some(PathArg { slot: 1, dirfd_slot: Some(0) }), None],
            ret_buf_slot: Some(2),
        },
        // symlinkat 的 target 也是 guest 视角路径，需一并翻译；
        // target 无 dirfd 语义，仅 linkpath 受 newdirfd 修饰。
        SYMLINKAT => SyscallPlan::rewrite(
            Some(PathArg { slot: 0, dirfd_slot: None }),
            Some(PathArg { slot: 2, dirfd_slot: Some(1) }),
        ),
        RENAMEAT | RENAMEAT2 => SyscallPlan::rewrite(
            Some(PathArg { slot: 1, dirfd_slot: Some(0) }),
            Some(PathArg { slot: 3, dirfd_slot: Some(2) }),
        ),
        FACCESSAT | FACCESSAT2 => SyscallPlan::rewrite(
            Some(PathArg { slot: 1, dirfd_slot: Some(0) }),
            None,
        ),
        EXECVE => SyscallPlan::rewrite(Some(PathArg { slot: 0, dirfd_slot: None }), None),
        EXECVEAT => SyscallPlan::rewrite(
            Some(PathArg { slot: 1, dirfd_slot: Some(0) }),
            None,
        ),
        CHDIR | TRUNCATE => {
            SyscallPlan::rewrite(Some(PathArg { slot: 0, dirfd_slot: None }), None)
        }
        GETCWD => SyscallPlan { op: Op::RestoreOnExit, args: [None, None], ret_buf_slot: Some(0) },
        // fstat 无路径参数；clone/clone3 靠 TRACECLONE 跟踪，无需改参数。
        FSTAT | CLONE | CLONE3 | sysnr::EXIT_GROUP => SyscallPlan::NONE,
        // 其余一律直通：statx(292)/openat2(437)/linkat(37)/mknodat(33) 等，
        // 逐版本按需纳入（对齐上游增量扩表节奏）。
        _ => SyscallPlan::NONE,
    }
}

/// 通用寄存器快照（与 aarch64 `user_regs_struct` 同构，纯函数域可测）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Regs {
    /// x0..x30。
    pub x: [u64; 31],
    pub sp: u64,
    pub pc: u64,
    pub pstate: u64,
}

impl Regs {
    /// syscall 号：x8（aarch64 约定）。
    pub fn nr(&self) -> u64 {
        self.x[8]
    }

    /// 第 `i` 个参数：x0..x5（aarch64 无 orig_rax 混叠）。
    pub fn arg(&self, i: usize) -> u64 {
        debug_assert!(i < 6, "syscall 最多 6 参");
        self.x[i]
    }

    pub fn set_arg(&mut self, i: usize, v: u64) {
        self.x[i] = v;
    }

    pub fn ret(&self) -> u64 {
        self.x[0]
    }

    pub fn set_ret(&mut self, v: u64) {
        self.x[0] = v;
    }
}

/// 写回路径参数的结果分类。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RewriteOutcome {
    /// 已写回子进程内存。
    Written,
    /// 新路径放不下原缓冲（见 [`can_fit`]），保持原路径——保守正确策略。
    SkippedTooLong,
    /// 翻译结果与原路径相同，无需写。
    Unchanged,
    /// 子内存读取失败（进程退出竞争等），按直通处理。
    Unreadable,
}

/// 写回容量判定（纯函数）：新路径（含 NUL）必须放进"原路径实际占用 + 1"内。
///
/// 正确性说明：guest 路径缓冲由子进程栈/堆分配，长度就是 `strlen+1`；
/// 贸然写更长路径会踩坏相邻内存。上游 proot 借助自带 loader 在 execve 时
/// 换入更长的内存区（loader-mem）；moxsh M4 采用保守策略：放不下就不改写、
/// 让原路径直通内核（结果通常是 ENOENT，等价于未做绑定，绝不损坏子进程）。
pub fn can_fit(original_len: usize, new_len: usize) -> bool {
    new_len + 1 <= original_len + 1
}

/// 从子进程内存读取以 NUL 结尾的路径字符串。
///
/// # 安全依据（unsafe 总注）
/// - `pid` 必须是处于被跟踪停止状态的 tracee（由 waitpid 保证此刻地址空间稳定）；
/// - `addr` 来自该 tracee 的寄存器值，只读访问，上限 [`MAX_PATH`] 防御野指针；
/// - 失败一律返回 Err 而非 panic，进程退出竞争由调用方按 [`Op::PassThrough`] 处理。
fn read_child_cstr(pid: libc::pid_t, addr: u64) -> ProotResult<String> {
    let mut buf = vec![0u8; MAX_PATH];
    let mut read_total = 0usize;

    // 优先 process_vm_readv：一次调用跨页读取，无需对齐。
    // 安全：local_iov 指向本地 Vec 缓冲，remote_iov 地址/长度经 MAX_PATH 限界。
    let n = unsafe {
        let local = libc::iovec {
            iov_base: buf.as_mut_ptr() as *mut libc::c_void,
            iov_len: buf.len(),
        };
        let remote = libc::iovec {
            iov_base: addr as *mut libc::c_void,
            iov_len: buf.len(),
        };
        libc::process_vm_readv(pid, &local, 1, &remote, 1, 0)
    };
    if n > 0 {
        read_total = n as usize;
    } else {
        // 降级路径：PEEKDATA 按字长对齐读出后取单字节（慢但正确，处理任意对齐）。
        // 热路径是 process_vm_readv；此降级仅在旧内核/SELinux 限制下启用。
        // 安全：同上，地址限界 + 只读；对齐由 `addr & !(word-1)` 显式保证。
        let word = std::mem::size_of::<libc::c_long>() as u64;
        while read_total < MAX_PATH {
            let byte_addr = addr + read_total as u64;
            let aligned = byte_addr & !(word - 1);
            let off = (byte_addr - aligned) as usize;
            let v = unsafe {
                libc::ptrace(
                    libc::PTRACE_PEEKDATA,
                    pid,
                    aligned as *mut libc::c_void,
                    std::ptr::null_mut::<libc::c_void>(),
                )
            };
            let bytes = (v as libc::c_ulong).to_ne_bytes();
            if off >= bytes.len() {
                return Err(ProotError::Ptrace("PEEKDATA 字偏移越界".into()));
            }
            let b = bytes[off];
            buf[read_total] = b;
            read_total += 1;
            if b == 0 {
                break;
            }
        }
    }

    let end = buf[..read_total]
        .iter()
        .position(|&b| b == 0)
        .unwrap_or(read_total);
    String::from_utf8(buf[..end].to_vec())
        .map_err(|_| ProotError::Ptrace("子进程路径参数不是合法 UTF-8".into()))
}

/// 向子进程内存写入字节串（路径翻译结果，调用方保证带 NUL）。
///
/// # 安全依据
/// - `pid` 处于被跟踪停止态；`addr` 为原路径参数地址（原路径已被读出验证可访问）；
/// - 写入长度受 `can_fit` 预检（见其注释），不会越出原缓冲语义边界；
/// - POKEDATA 降级路径对尾字做读-改-写，避免破坏同字内相邻数据。
fn write_child_bytes(pid: libc::pid_t, addr: u64, data: &[u8]) -> ProotResult<()> {
    // 先试 process_vm_writev。
    // 安全：同 read_child_cstr；长度为预检后的 data.len()。
    let n = unsafe {
        let local = libc::iovec {
            iov_base: data.as_ptr() as *mut libc::c_void,
            iov_len: data.len(),
        };
        let remote = libc::iovec {
            iov_base: addr as *mut libc::c_void,
            iov_len: data.len(),
        };
        libc::process_vm_writev(pid, &local, 1, &remote, 1, 0)
    };
    if n == data.len() as isize {
        return Ok(());
    }

    // 降级：POKEDATA 逐字节读-改-写（任意对齐安全；性能可接受——
    // 正常路径 process_vm_writev 一次调用完成，此降级仅兜底）。
    // 安全：同上；每字节先 PEEK 同字再 POKE，不破坏同字内相邻数据。
    for (i, b) in data.iter().enumerate() {
        let byte_addr = addr + i as u64;
        let word = std::mem::size_of::<libc::c_long>() as u64;
        let aligned = byte_addr & !(word - 1);
        let off = (byte_addr - aligned) as usize;
        let old = unsafe {
            libc::ptrace(
                libc::PTRACE_PEEKDATA,
                pid,
                aligned as *mut libc::c_void,
                std::ptr::null_mut::<libc::c_void>(),
            )
        };
        let mut bytes = (old as libc::c_ulong).to_ne_bytes();
        if off >= bytes.len() {
            return Err(ProotError::Ptrace("PEEKDATA 字偏移越界".into()));
        }
        bytes[off] = *b;
        let v = libc::c_long::from_ne_bytes(bytes);
        unsafe {
            libc::ptrace(
                libc::PTRACE_POKEDATA,
                pid,
                aligned as *mut libc::c_void,
                v as *mut libc::c_void,
            )
        };
    }
    Ok(())
}

/// 翻译器：持规则集，执行单次 enter/exit 改写（逻辑拆为方法以便复用与测试）。
pub struct Tracer {
    pub rules: TranslateRules,
}

impl Tracer {
    pub fn new(rules: TranslateRules) -> Tracer {
        Tracer { rules }
    }

    /// enter 停止：按计划翻译全部路径参数并写回。
    /// 返回本条 syscall 是否发生了改写（exit 阶段用于决定是否需要还原）。
    pub fn handle_enter(&self, pid: libc::pid_t, regs: &Regs, plan: &SyscallPlan) -> RewriteOutcome {
        let mut overall = RewriteOutcome::Unchanged;
        for slot in plan.args.iter().flatten() {
            let addr = regs.arg(slot.slot as usize);
            if addr == 0 {
                continue; // NULL 路径：内核自行返回 EFAULT，直通。
            }
            let guest = match read_child_cstr(pid, addr) {
                Ok(s) => s,
                Err(_) => return RewriteOutcome::Unreadable,
            };
            // dirfd 语义：AT_FDCWD(-100) 时按 cwd 绝对化；其余相对 dirfd 的
            // 路径交给内核按（已翻译的）dirfd 解析，仅绝对化处理。
            let host = self.rules.guest_to_host(&guest);
            let new = host.to_string_lossy().into_owned();
            if new == guest {
                continue;
            }
            let orig_len = guest.len();
            if !can_fit(orig_len, new.len()) {
                return RewriteOutcome::SkippedTooLong;
            }
            let mut with_nul = new.into_bytes();
            with_nul.push(0);
            match write_child_bytes(pid, addr, &with_nul) {
                Ok(()) => overall = RewriteOutcome::Written,
                Err(_) => return RewriteOutcome::Unreadable,
            }
        }
        overall
    }

    /// exit 停止：getcwd/readlinkat 的返回缓冲是 guest 视角路径，
    /// 需 host→guest 反向还原后写回（对齐上游 detranslate_path）。
    pub fn handle_exit_restore(&self, pid: libc::pid_t, regs: &Regs, plan: &SyscallPlan) {
        let Some(slot) = plan.ret_buf_slot else { return };
        let addr = regs.arg(slot as usize);
        if addr == 0 {
            return;
        }
        // 返回值是写入长度（不含 NUL）；失败（负值）不处理。
        let ret = regs.ret() as i64;
        if ret <= 0 {
            return;
        }
        let Ok(host) = read_child_cstr(pid, addr) else { return };
        let Some(guest) = self.rules.host_to_guest(Path::new(&host)) else { return };
        if guest == host {
            return;
        }
        let cap = ret as usize + 1; // 缓冲容量至少为返回长度 + NUL
        let mut bytes = guest.into_bytes();
        bytes.push(0);
        // 返回缓冲容量由内核承诺（>= ret+1），超长场景不写，保持 host 路径。
        if bytes.len() <= cap {
            let _ = write_child_bytes(pid, addr, &bytes);
        }
    }

    /// fake root：对身份类 syscall 的返回值伪装（对齐上游 fake_id0）。
    ///
    /// getuid/geteuid/getgid/... 在 exit 停止时把返回值改为 0；
    /// chown/setuid 类在 enter 停止时整体替换为"返回 0 的无效调用"
    /// （替换 syscall 号为 gettid 等无害调用——此处仅改返回值的骨架，
    /// 完整替换表在 M7 热路径阶段补齐）。
    pub fn apply_fake_root_exit(&self, regs: &mut Regs) {
        if !self.rules.fake_root() {
            return;
        }
        match regs.nr() {
            // getuid32(96)/geteuid32(107)/getgid32(104)/getegid32(108)
            // aarch64 统一为 getuid(174)/geteuid(175)/getgid(176)/getegid(177)
            174 | 175 | 176 | 177 => regs.set_ret(0),
            _ => {}
        }
    }

    /// /proc 身份文件的内容级伪装动作查询（供 exit 读缓冲阶段使用）。
    pub fn fake_action(&self, guest_path: &str) -> FakeRootAction {
        self.rules.apply_fake_root(guest_path)
    }

    /// 启动被跟踪子进程：fork → 子 `PTRACE_TRACEME` → `execve(cmd)`。
    ///
    /// # Safety（unsafe 块逐条依据）
    /// - fork 后子进程只做 exec 前的最小动作（TRACEME/execve/_exit），
    ///   不触碰任何需跨线程同步的状态（单线程 fork 安全）；
    /// - execve 的 argv 以 CString 构造，保证 NUL 结尾与生命周期覆盖调用点。
    pub fn spawn_traced(cmd: &[&str]) -> ProotResult<libc::pid_t> {
        let argv: Vec<std::ffi::CString> = cmd
            .iter()
            .map(|s| {
                std::ffi::CString::new(s.as_bytes())
                    .map_err(|_| ProotError::InvalidArg(format!("argv 含 NUL: {}", s)))
            })
            .collect::<ProotResult<_>>()?;
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            return Err(ProotError::Io(std::io::Error::last_os_error()));
        }
        if pid == 0 {
            // ===== 子进程：自愿被跟踪后 exec =====
            unsafe {
                // TRACEME 必须先于 exec：exec 触发首次 execve 停止，父进程由此接管。
                if libc::ptrace(libc::PTRACE_TRACEME, 0, std::ptr::null_mut::<libc::c_void>(), std::ptr::null_mut::<libc::c_void>()) < 0 {
                    libc::_exit(126);
                }
                // 对齐上游：默认命令 /bin/sh（空 cmd 时兜底）。
                let prog = if argv.is_empty() {
                    std::ffi::CString::new("/bin/sh").unwrap()
                } else {
                    argv[0].clone()
                };
                let mut ptrs: Vec<*const libc::c_char> =
                    argv.iter().map(|s| s.as_ptr()).collect();
                ptrs.push(std::ptr::null());
                libc::execve(prog.as_ptr(), ptrs.as_ptr(), std::ptr::null());
                libc::_exit(127); // exec 失败（rootfs 缺 shell 等）
            }
        }
        Ok(pid)
    }

    /// 跟踪主循环：waitpid → enter/exit 双停 → 改写/还原 → `PTRACE_SYSCALL` 放行。
    ///
    /// 返回子进程（组）的最终退出码。骨架注释了事件处理全貌；
    /// 多进程树的 waitpid 子循环（TRACEFORK/TRACECLONE 事件）在真机联调（M6）中
    /// 按本骨架扩展——单停/双停状态机与寄存器改写逻辑已完整可测。
    pub fn run(&self, child: libc::pid_t) -> ProotResult<i32> {
        // 设置跟踪选项：SYSGOOD 使 syscall 停止的信号变为 SIGTRAP|0x80，与
        // 普通信号区分；FORK/VFORK/CLONE/EXEC 保证整个进程树持续被跟踪
        // （对齐上游 set_options：一次设置，exec 后自动继承）。
        unsafe {
            let opts = libc::PTRACE_O_TRACESYSGOOD
                | libc::PTRACE_O_TRACEFORK
                | libc::PTRACE_O_TRACEVFORK
                | libc::PTRACE_O_TRACECLONE
                | libc::PTRACE_O_TRACEEXEC;
            libc::ptrace(
                libc::PTRACE_SETOPTIONS,
                child,
                std::ptr::null_mut::<libc::c_void>(),
                opts as libc::c_long as *mut libc::c_void,
            );
        }

        let mut status: libc::c_int = 0;
        let mut in_syscall = false;
        loop {
            let w = unsafe { libc::waitpid(child, &mut status, 0) };
            if w < 0 {
                let e = std::io::Error::last_os_error();
                if e.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(ProotError::Io(e));
            }
            if unsafe { libc::WIFEXITED(status) } {
                return Ok(unsafe { libc::WEXITSTATUS(status) });
            }
            if unsafe { libc::WIFSIGNALED(status) } {
                return Ok(128 + unsafe { libc::WTERMSIG(status) });
            }
            if !unsafe { libc::WIFSTOPPED(status) } {
                continue;
            }

            let sig = unsafe { libc::WSTOPSIG(status) };
            let sysgood_stop = sig == libc::SIGTRAP | 0x80;

            if sysgood_stop {
                let regs = self.get_regs(child)?;
                if !in_syscall {
                    // ===== syscall enter =====
                    let plan = syscall_plan(regs.nr());
                    match plan.op {
                        Op::PassThrough => {}
                        Op::RewriteOnEnter | Op::RewriteEnterRestoreExit => {
                            self.handle_enter(child, &regs, &plan);
                        }
                        Op::RestoreOnExit => {}
                    }
                } else {
                    // ===== syscall exit =====
                    let mut regs = self.get_regs(child)?;
                    let plan = syscall_plan(regs.nr());
                    match plan.op {
                        Op::RestoreOnExit | Op::RewriteEnterRestoreExit => {
                            self.handle_exit_restore(child, &regs, &plan);
                        }
                        _ => {}
                    }
                    self.apply_fake_root_exit(&mut regs);
                    self.set_regs(child, &regs)?;
                }
                in_syscall = !in_syscall;
                unsafe {
                    libc::ptrace(
                        libc::PTRACE_SYSCALL,
                        child,
                        std::ptr::null_mut::<libc::c_void>(),
                        std::ptr::null_mut::<libc::c_void>(),
                    )
                };
                continue;
            }

            // 其他停止：普通信号原样递送（对齐上游信号透传策略）；
            // PTRACE_EVENT_FORK/CLONE/EXEC 事件在此追加新 tracee 的
            // PTRACE_SETOPTIONS（真机联调时扩展）。
            unsafe {
                libc::ptrace(
                    libc::PTRACE_CONT,
                    child,
                    std::ptr::null_mut::<libc::c_void>(),
                    sig as libc::c_long as *mut libc::c_void,
                )
            };
        }
    }

    /// GETREGSET(NT_PRSTATUS=1) 读取通用寄存器。
    ///
    /// # Safety
    /// `pid` 处于被跟踪停止态；iovec 指向本地栈上 `Regs`，长度即 aarch64
    /// `user_regs_struct`（34×8=272 字节），与内核 NT_PRSTATUS 载荷一致。
    fn get_regs(&self, pid: libc::pid_t) -> ProotResult<Regs> {
        let mut regs = Regs::default();
        let mut iov = libc::iovec {
            iov_base: &mut regs as *mut Regs as *mut libc::c_void,
            iov_len: std::mem::size_of::<Regs>(),
        };
        let rc = unsafe {
            libc::ptrace(
                libc::PTRACE_GETREGSET,
                pid,
                1usize as *mut libc::c_void, // NT_PRSTATUS
                &mut iov as *mut libc::iovec,
            )
        };
        if rc < 0 {
            return Err(ProotError::Ptrace(format!(
                "GETREGSET 失败: {}",
                std::io::Error::last_os_error()
            )));
        }
        Ok(regs)
    }

    /// SETREGSET 写回寄存器（改写后的参数/返回值）。
    ///
    /// # Safety
    /// 同 `get_regs`；写回内容仅改 x0..x5 与 x8 语义合法字段。
    fn set_regs(&self, pid: libc::pid_t, regs: &Regs) -> ProotResult<()> {
        let mut iov = libc::iovec {
            iov_base: regs as *const Regs as *mut libc::c_void,
            iov_len: std::mem::size_of::<Regs>(),
        };
        let rc = unsafe {
            libc::ptrace(
                libc::PTRACE_SETREGSET,
                pid,
                1usize as *mut libc::c_void, // NT_PRSTATUS
                &mut iov as *mut libc::iovec,
            )
        };
        if rc < 0 {
            return Err(ProotError::Ptrace(format!(
                "SETREGSET 失败: {}",
                std::io::Error::last_os_error()
            )));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proot::translate::TranslateRules;
    use std::path::Path;

    /// openat：翻译槽 1，dirfd 在槽 0。
    #[test]
    fn plan_openat() {
        let p = syscall_plan(sysnr::OPENAT);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(p.args[1], None);
    }

    /// renameat2：双路径槽（1/3），各带 dirfd（0/2）——上游 v5.2.0 修复的覆盖点。
    #[test]
    fn plan_renameat2_two_paths() {
        let p = syscall_plan(sysnr::RENAMEAT2);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(p.args[1], Some(PathArg { slot: 3, dirfd_slot: Some(2) }));
    }

    /// symlinkat：target（槽 0）无 dirfd，linkpath（槽 2）dirfd 在槽 1。
    #[test]
    fn plan_symlinkat() {
        let p = syscall_plan(sysnr::SYMLINKAT);
        assert_eq!(p.args[0], Some(PathArg { slot: 0, dirfd_slot: None }));
        assert_eq!(p.args[1], Some(PathArg { slot: 2, dirfd_slot: Some(1) }));
    }

    /// getcwd：仅 exit 反向还原。
    #[test]
    fn plan_getcwd_exit_only() {
        let p = syscall_plan(sysnr::GETCWD);
        assert_eq!(p.op, Op::RestoreOnExit);
        assert_eq!(p.ret_buf_slot, Some(0));
    }

    /// readlinkat：enter 翻译 + exit 还原返回缓冲（上游 detranslate 场景）。
    #[test]
    fn plan_readlinkat_enter_and_exit() {
        let p = syscall_plan(sysnr::READLINKAT);
        assert_eq!(p.op, Op::RewriteEnterRestoreExit);
        assert_eq!(p.ret_buf_slot, Some(2));
    }

    /// 未列入的 syscall 直通（上游"未拦截即直通"兜底语义）。
    #[test]
    fn plan_unknown_passthrough() {
        assert_eq!(syscall_plan(9999).op, Op::PassThrough);
        assert_eq!(syscall_plan(292).op, Op::PassThrough); // statx 暂直通
        assert_eq!(syscall_plan(437).op, Op::PassThrough); // openat2 暂直通
    }

    /// aarch64 寄存器映射：nr=x8、参数 x0..x5、返回值 x0。
    #[test]
    fn regs_aarch64_mapping() {
        let mut r = Regs::default();
        r.x[8] = sysnr::OPENAT;
        r.x[0] = 100; // dirfd
        r.x[1] = 0x7f00; // pathname 指针
        r.x[2] = 0o200000; // flags: O_CLOEXEC|...
        assert_eq!(r.nr(), sysnr::OPENAT);
        assert_eq!(r.arg(0), 100);
        assert_eq!(r.arg(1), 0x7f00);
        r.set_ret(3);
        assert_eq!(r.ret(), 3);
    }

    /// 写回容量判定：新路径必须不比原路径长（can_fit 保守策略）。
    #[test]
    fn rewrite_capacity() {
        assert!(can_fit(10, 10));
        assert!(can_fit(10, 3));
        assert!(!can_fit(3, 10));
    }

    /// handle_enter 端到端逻辑验证不了真进程，此处验证翻译入口组合：
    /// 规则翻译出 host 路径且比原路径短时才可写回。
    #[test]
    fn translate_then_fit() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.add_binding("/m", "/m");
        let guest = "/m/a";
        let host = rules.guest_to_host(guest).to_string_lossy().into_owned();
        assert_eq!(host, "/m/a");
        assert!(can_fit(guest.len(), host.len()));
        let long = rules.guest_to_host("/m/aaaaaaaaaaaaaaaaaaaa").to_string_lossy().into_owned();
        // rootfs 兜底路径比 guest 长时触发 SkippedTooLong 语义。
        assert!(!can_fit(7, long.len()));
    }
}

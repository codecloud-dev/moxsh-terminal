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

use crate::proot::elf::{resolve_exec_chain, ExecPlan};
use crate::proot::translate::{normalize, FakeRootAction, TranslateRules};
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
    /// —— aarch64 上的 stat/lstat/fstatat 老形；glibc 2.28+ 新形走 statx(291)。
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
    /// mknodat(int dirfd, const char *pathname, mode_t mode, dev_t dev)
    /// —— M4.1 补齐：dpkg 装 /dev 节点类包、静态 busybox mknod 走它。
    pub const MKNODAT: u64 = 33;
    /// linkat(int olddirfd, const char *oldpath, int newdirfd,
    /// const char *newpath, int flags) —— M4.1 补齐：ln/dpkg 硬链接装包常用。
    pub const LINKAT: u64 = 37;
    /// readlink(const char *path, char *buf, size_t bufsiz) —— 老接口，
    /// glibc 内部转 readlinkat，但静态链接旧程序直发；M4.1 补齐。
    pub const READLINK: u64 = 87;
    /// statx(int dirfd, const char *pathname, unsigned int flags,
    /// unsigned int mask, struct statx *buf) —— M4.1 补齐。
    ///
    /// **编号订正**：aarch64 通用表中 statx = **291**；292 是 io_pgetevents
    /// （asm-generic/unistd.h 已核实）。此前注释误写 292，若照其实现
    /// 将拦错 syscall。glibc 2.28+ 的 stat/lstat/fstatat 大量走 statx，
    /// 不拦它等于路径绑定对所有 stat 类调用失效。
    pub const STATX: u64 = 291;
    /// openat2(int dirfd, const char *pathname, struct open_how *how, size_t size)
    /// —— M4.1 补齐：参数结构与 openat 同构（路径槽 1 + dirfd 槽 0），
    /// Go 1.15+ runtime 与部分新程序直发；how 结构体不改，仅翻译路径。
    pub const OPENAT2: u64 = 437;
    /// exit_group —— 终止判据之一。
    pub const EXIT_GROUP: u64 = 94;
    /// open(const char *pathname, int flags, ...) —— x86_64/i386 独有的老接口。
    ///
    /// **内部号用哨兵值 0x8002**：asm-generic（aarch64）编号空间没有裸
    /// `open`（只有 openat），其 nr 2 是 io_submit——若直接把 2 当内部号，
    /// aarch64 上每个 io_submit 都会被误判为 open 走路径翻译。哨兵值落在
    /// 两架构真实编号空间之外，`to_native_nr` 显式映射回 x86_64 的 2。
    ///
    /// 为什么必须拦：musl 动态加载器加载主程序走裸 `open()`（实测），
    /// 不拦则 loader 在宿主命名空间找 guest 路径直接 ENOENT——
    /// 整个「显式解释器调用」方案在 x86_64 上失效。
    pub const OPEN: u64 = 0x8002;
    /// stat(const char *path, struct stat *buf) —— x86_64/i386 老接口。
    ///
    /// 哨兵值 0x8003（同 OPEN 的理由：asm-generic 无裸 stat，其 nr 4 是
    /// `clone3` 之外的预留位/`statx` 前置位，直接透传会被误判）。
    /// musl x86_64 的 stat()/lstat() 包装直发原生命（不走 newfstatat），
    /// busybox `ls -l` 的 lstat 全 ENOENT 即缺此两条（M6 e2e 实证）。
    pub const STAT: u64 = 0x8003;
    /// lstat(const char *path, struct stat *buf) —— x86_64/i386 老接口，
    /// 符号链接不跟随（语义同 fstatat 的 AT_SYMLINK_NOFOLLOW，但无 dirfd）。
    pub const LSTAT: u64 = 0x8004;
    /// utimensat(int dirfd, const char *path, const struct timespec times[2], int flags)
    /// —— aarch64 88（asm-generic 真实号；x86_64 老接口 utimes/futimesat 见下）。
    /// busybox `touch` 先发 utimensat，不拦则 host 命名空间 ENOENT（M6 e2e 实证）。
    pub const UTIMENSAT: u64 = 88;
    /// utimes(const char *path, const struct timeval times[2]) —— x86_64 老接口
    /// （235），asm-generic 无此号，哨兵 0x8005。
    pub const UTIMES: u64 = 0x8005;
    /// futimesat(int fd, const char *path, const struct timeval times[2])
    /// —— x86_64 老接口（261），哨兵 0x8006。
    pub const FUTIMESAT: u64 = 0x8006;
    /// chmod(const char *path, mode_t mode) —— x86_64 老接口（90），哨兵 0x8007。
    pub const CHMOD: u64 = 0x8007;
    /// fchmodat(int dirfd, const char *path, mode_t mode, int flags) —— aarch64 53。
    pub const FCHMODAT: u64 = 53;
    /// fchmod(int fd, mode_t mode) —— fd 形无路径，aarch64 52；计划直通。
    pub const FCHMOD: u64 = 52;
    /// chown(const char *path, uid_t, gid_t) —— x86_64 老接口（92），哨兵 0x8008。
    /// glibc x86_64 直发；musl 走 fchownat。fake root 阉割 + fake_root 关闭时翻路径。
    pub const CHOWN: u64 = 0x8008;
    /// lchown(const char *path, uid_t, gid_t) —— x86_64 老接口（94），哨兵 0x8009。
    pub const LCHOWN: u64 = 0x8009;
    /// rename(const char *oldpath, const char *newpath) —— x86_64 老接口（82），
    /// 双路径无 dirfd，哨兵 0x800A。
    pub const RENAME: u64 = 0x800A;
    /// mkdir(const char *path, mode_t) —— x86_64 老接口（83），哨兵 0x800B。
    pub const MKDIR: u64 = 0x800B;
    /// rmdir(const char *path) —— x86_64 老接口（84），哨兵 0x800C。
    pub const RMDIR: u64 = 0x800C;
    /// unlink(const char *path) —— x86_64 老接口（87），哨兵 0x800D。
    /// musl unlink() 在 x86_64 直发老接口（不走 unlinkat），busybox `rm`
    /// 全 ENOENT 即缺此条（M6 e2e 实证）。
    pub const UNLINK: u64 = 0x800D;
    /// symlink(const char *target, const char *linkpath) —— x86_64 老接口（88），
    /// 哨兵 0x800E。target 是链接**内容**也要翻（宿主内核按字面解析它，
    /// 与 symlinkat 同策略，见计划表注释）；linkpath 是创建位置。
    pub const SYMLINK: u64 = 0x800E;
    /// link(const char *oldpath, const char *newpath) —— x86_64 老接口（86），
    /// 哨兵 0x800F，双路径无 dirfd（同 rename 的槽 0/1 布局）。
    pub const LINK: u64 = 0x800F;
    /// access(const char *path, int mode) —— x86_64 老接口（21），哨兵 0x8010。
    pub const ACCESS: u64 = 0x8010;
    /// mknod(const char *path, mode_t, dev_t) —— x86_64 老接口（133），哨兵 0x8011。
    pub const MKNOD: u64 = 0x8011;

    // ===== fake root 身份类 syscall（编号经 asm-generic/unistd.h 核实）=====
    /// fchownat(int dirfd, const char *pathname, uid_t owner, gid_t group, int flags)
    /// —— arm64 无独立 chown/lchown 号，glibc chown()/lchown() 均走此形。
    pub const FCHOWNAT: u64 = 54;
    /// fchown(int fd, uid_t owner, gid_t group) —— chown 系 fd 形。
    pub const FCHOWN: u64 = 55;
    /// setregid/setgid/setreuid/setuid/setresuid/getresuid/setresgid/getresgid。
    pub const SETREGID: u64 = 143;
    pub const SETGID: u64 = 144;
    pub const SETREUID: u64 = 145;
    pub const SETUID: u64 = 146;
    pub const SETRESUID: u64 = 147;
    pub const GETRESUID: u64 = 148;
    pub const SETRESGID: u64 = 149;
    pub const GETRESGID: u64 = 150;
    /// setfsuid/setfsgid —— 返回原 fs uid/gid，伪装为 0。
    pub const SETFSUID: u64 = 151;
    pub const SETFSGID: u64 = 152;
    /// getgroups(int size, gid_t list[]) —— 伪装为 0 个组。
    pub const GETGROUPS: u64 = 158;
    /// setgroups(int size, const gid_t list[]) —— enter 阶段阉割。
    pub const SETGROUPS: u64 = 159;
    /// getuid/geteuid/getgid/getegid —— exit 阶段返回值伪装为 0（M4 已支持）。
    pub const GETUID: u64 = 174;
    pub const GETEUID: u64 = 175;
    pub const GETGID: u64 = 176;
    pub const GETEGID: u64 = 177;
    /// getpid —— enter 阶段阉割 setuid/chown 系时的替身 syscall
    /// （无副作用、必然成功，对齐上游 fake_id0 的做法）。
    pub const GETPID: u64 = 172;
    /// mmap(void *addr, size_t length, int prot, int flags, int fd, off_t off)
    /// —— scratch 注入专用（不在拦截表内：内核替身执行，不经翻译）。
    pub const MMAP: u64 = 222;
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
    /// enter 阶段阉割身份类 syscall（setuid/chown 系）：nr 替换为 getpid
    /// 并记录原值，exit 阶段返回 0（对齐上游 fake_id0）。
    FakeRootEnter,
    /// 仅 exit 阶段伪装返回值（getuid 系、setfsuid、getgroups、getresuid/gid）。
    FakeRootExit,
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

/// 平台适配层：把各架构的「原生 syscall 号」归一化为 aarch64 内部号
/// （plan 表与 fake root 逻辑全部以 aarch64 号书写，一处适配、两平台一致）。
///
/// - **aarch64**（Android 主目标）：恒等映射，零开销；
/// - **x86_64**（CI/sandbox 集成测试用）：x86 号 → aarch64 等价号，
///   **仅列入实际拦截面涉及的 syscall**。
///
/// # 为什么未列入的必须返回 `None`（而非原值透传）
/// 两个架构的编号空间互相冲突：x86_64 原生 45 是 `recvfrom`，
/// 而 aarch64 45 是 `truncate`。若未列入也按原值透传，x86_64 上每个
/// `recvfrom` 都会被误判为 truncate 走进路径翻译（读到 fd 小整数当指针，
/// 触发数百次 PEEKDATA 降级扫描）。返回 `None` = 明确"本平台此号不拦"。
#[cfg(target_arch = "x86_64")]
fn to_internal_nr(nr: u64) -> Option<u64> {
    use sysnr::*;
    Some(match nr {
        2 => OPEN,             // open（x86_64 独有老接口；musl loader 实测走它）
        4 => STAT,             // stat（x86_64 老接口；musl stat() 直发）
        5 => FSTAT,            // fstat（fd 形，无路径；计划直通，建档供将来）
        6 => LSTAT,            // lstat（x86_64 老接口；musl lstat() 直发）
        82 => RENAME,          // rename 老接口（glibc x86_64 可能直发）
        83 => MKDIR,           // mkdir 老接口
        84 => RMDIR,           // rmdir 老接口
        21 => ACCESS,          // access 老接口
        86 => LINK,            // link 老接口
        87 => UNLINK,          // unlink 老接口（musl x86_64 直发）
        88 => SYMLINK,         // symlink 老接口
        133 => MKNOD,          // mknod 老接口
        90 => CHMOD,           // chmod 老接口
        91 => FCHMOD,          // fchmod（fd 形无路径；计划直通）
        92 => CHOWN,           // chown 老接口（glibc x86_64 直发）
        94 => LCHOWN,          // lchown 老接口
        235 => UTIMES,         // utimes 老接口
        261 => FUTIMESAT,      // futimesat 老接口
        268 => FCHMODAT,       // fchmodat
        280 => UTIMENSAT,      // utimensat
        39 => GETPID,          // getpid
        56 => CLONE,           // clone
        59 => EXECVE,          // execve
        76 => TRUNCATE,        // truncate
        79 => GETCWD,          // getcwd
        80 => CHDIR,           // chdir
        89 => READLINK,        // readlink
        93 => FCHOWN,          // fchown
        257 => OPENAT,         // openat
        258 => MKDIRAT,        // mkdirat
        259 => MKNODAT,        // mknodat
        260 => FCHOWNAT,       // fchownat
        262 => NEWFSTATAT,     // newfstatat
        263 => UNLINKAT,       // unlinkat
        264 => RENAMEAT,       // renameat
        265 => LINKAT,         // linkat
        266 => SYMLINKAT,      // symlinkat
        267 => READLINKAT,     // readlinkat
        297 => FACCESSAT,      // faccessat
        316 => RENAMEAT2,      // renameat2
        322 => EXECVEAT,       // execveat
        332 => STATX,          // statx
        231 => EXIT_GROUP,     // exit_group
        102 => GETUID,         // getuid
        104 => GETGID,         // getgid
        105 => SETUID,         // setuid
        106 => SETGID,         // setgid
        107 => GETEUID,        // geteuid
        108 => GETEGID,        // getegid
        113 => SETREUID,       // setreuid
        114 => SETREGID,       // setregid
        115 => GETGROUPS,      // getgroups
        116 => SETGROUPS,      // setgroups
        117 => SETRESUID,      // setresuid
        118 => GETRESUID,      // getresuid
        119 => SETRESGID,      // setresgid
        120 => GETRESGID,      // getresgid
        122 => SETFSUID,       // setfsuid
        123 => SETFSGID,       // setfsgid
        _ => return None, // 未列入：本平台此号不拦（防跨架构编号误判）
    })
}

/// 内部号 → 平台原生号（`to_internal_nr` 的逆映射）。
///
/// 生产用途：enter 阶段把 nr 换成 getpid 替身时，需把内部号写回平台寄存器
/// （x86_64 写 `orig_rax`，见 `Tracer::set_regs`）。单测亦用它把内部号
/// 常量装进当前平台的寄存器快照，保证测试跨架构一致。
#[cfg(target_arch = "x86_64")]
fn to_native_nr(internal: u64) -> u64 {
    use sysnr::*;
    match internal {
        GETPID => 39,
        OPEN => 2, // 裸 open：x86_64 独有（aarch64 无此号，恒等分支不产生它）
        STAT => 4, // 裸 stat：x86_64 独有
        LSTAT => 6, // 裸 lstat：x86_64 独有
        FSTAT => 5, // 裸 fstat：x86_64 独有（计划直通，仅号映射完整性）
        RENAME => 82, // rename 老接口
        MKDIR => 83,  // mkdir 老接口
        RMDIR => 84,  // rmdir 老接口
        UNLINK => 87, // unlink 老接口
        SYMLINK => 88, // symlink 老接口
        LINK => 86,   // link 老接口
        ACCESS => 21, // access 老接口
        MKNOD => 133, // mknod 老接口
        CHMOD => 90,  // chmod 老接口
        FCHMOD => 91, // fchmod（fd 形）
        CHOWN => 92,  // chown 老接口
        LCHOWN => 94, // lchown 老接口
        UTIMES => 235,       // utimes 老接口
        FUTIMESAT => 261,    // futimesat 老接口
        FCHMODAT => 268,     // fchmodat
        UTIMENSAT => 280,    // utimensat
        MMAP => 9, // x86_64 mmap = 9（aarch64 222 恒等，见 not(x86_64) 分支）
        CLONE => 56,
        EXECVE => 59,
        TRUNCATE => 76,
        GETCWD => 79,
        CHDIR => 80,
        READLINK => 89,
        FCHOWN => 93,
        OPENAT => 257,
        MKDIRAT => 258,
        MKNODAT => 259,
        FCHOWNAT => 260,
        NEWFSTATAT => 262,
        UNLINKAT => 263,
        RENAMEAT => 264,
        LINKAT => 265,
        SYMLINKAT => 266,
        READLINKAT => 267,
        FACCESSAT => 297,
        RENAMEAT2 => 316,
        EXECVEAT => 322,
        STATX => 332,
        EXIT_GROUP => 231,
        GETUID => 102,
        GETGID => 104,
        SETUID => 105,
        SETGID => 106,
        GETEUID => 107,
        GETEGID => 108,
        SETREUID => 113,
        SETREGID => 114,
        GETGROUPS => 115,
        SETGROUPS => 116,
        SETRESUID => 117,
        GETRESUID => 118,
        SETRESGID => 119,
        GETRESGID => 120,
        SETFSUID => 122,
        SETFSGID => 123,
        other => other,
    }
}

#[cfg(not(target_arch = "x86_64"))]
#[inline]
fn to_internal_nr(nr: u64) -> Option<u64> {
    Some(nr)
}

#[cfg(not(target_arch = "x86_64"))]
#[inline]
fn to_native_nr(internal: u64) -> u64 {
    internal
}

/// 查询 syscall 拦截计划（纯函数，未列入 → PassThrough 直通）。
///
/// 入参 `nr` 为**平台原生号**；入口先经 [`to_internal_nr`] 归一化，
/// 因此计划表只按 aarch64 号书写。归一化失败（本平台不拦）→ 直通。
///
/// 对齐上游 6.x 拦截面：路径类全覆盖（M4.1 补 mknodat/linkat/statx/
/// openat2/readlink）+ readlinkat/getcwd 反向 + faccessat2（v5.4.0）+
/// clone3（v5.4.1）+ 身份类 fake root（setuid/chown 系 enter 阉割、
/// getuid 系 exit 伪装）。
pub fn syscall_plan(nr: u64) -> SyscallPlan {
    match to_internal_nr(nr) {
        Some(internal) => syscall_plan_internal(internal),
        None => SyscallPlan::NONE,
    }
}

/// 按 aarch64 内部号查计划（单测与跨架构断言直接走此入口，
/// 避免平台号与内部号混用）。
pub fn syscall_plan_internal(inr: u64) -> SyscallPlan {
    use sysnr::*;
    match inr {
        OPENAT | NEWFSTATAT | UNLINKAT | MKDIRAT | MKNODAT | STATX | OPENAT2 | UTIMENSAT
        | FUTIMESAT | FCHMODAT => SyscallPlan::rewrite(Some(PathArg { slot: 1, dirfd_slot: Some(0) }), None),
        READLINKAT | READLINK => {
            let slot: Option<PathArg> = if inr == READLINKAT {
                Some(PathArg { slot: 1, dirfd_slot: Some(0) })
            } else {
                Some(PathArg { slot: 0, dirfd_slot: None })
            };
            SyscallPlan {
                op: Op::RewriteEnterRestoreExit,
                args: [slot, None],
                ret_buf_slot: if inr == READLINKAT { Some(2) } else { Some(1) },
            }
        }
        // symlinkat 的 target 也是 guest 视角路径，需一并翻译；
        // target 无 dirfd 语义，仅 linkpath 受 newdirfd 修饰。
        SYMLINKAT => SyscallPlan::rewrite(
            Some(PathArg { slot: 0, dirfd_slot: None }),
            Some(PathArg { slot: 2, dirfd_slot: Some(1) }),
        ),
        // linkat/renameat/renameat2 同为双路径 + 双 dirfd 结构（1/3 槽）。
        RENAMEAT | RENAMEAT2 | LINKAT => SyscallPlan::rewrite(
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
        CHDIR | TRUNCATE | STAT | LSTAT | UTIMES | CHMOD | MKDIR | RMDIR | UNLINK | ACCESS
        | MKNOD => SyscallPlan::rewrite(Some(PathArg { slot: 0, dirfd_slot: None }), None),
        // rename/link 老接口：双路径（0/1）无 dirfd——别照抄 renameat 的
        // 1/3 槽布局（那里 0/2 是 dirfd）。
        sysnr::RENAME | sysnr::LINK => SyscallPlan::rewrite(
            Some(PathArg { slot: 0, dirfd_slot: None }),
            Some(PathArg { slot: 1, dirfd_slot: None }),
        ),
        // symlink 老接口：target（槽 0）是链接内容也要翻——宿主内核按字面
        // 解析链接目标，不翻则链指向宿主 /bin/...（与 symlinkat 同策略）；
        // linkpath（槽 1）是创建位置。
        sysnr::SYMLINK => SyscallPlan::rewrite(
            Some(PathArg { slot: 0, dirfd_slot: None }),
            Some(PathArg { slot: 1, dirfd_slot: None }),
        ),
        // 裸 open（x86_64）：与 chdir 同形的单路径参数（flags 是第 2 参，
        // 不碰）。musl loader 加载主程序、旧版静态程序直发。
        sysnr::OPEN => SyscallPlan::rewrite(Some(PathArg { slot: 0, dirfd_slot: None }), None),
        GETCWD => SyscallPlan { op: Op::RestoreOnExit, args: [None, None], ret_buf_slot: Some(0) },
        // fstat/fchmod 无路径参数；clone/clone3 靠 TRACECLONE 跟踪，无需改参数。
        FSTAT | FCHMOD | CLONE | CLONE3 | sysnr::EXIT_GROUP => SyscallPlan::NONE,
        // ===== fake root 身份类 =====
        // enter 阉割系：setuid/gid 家族 + setgroups + fchown（fd 形无路径）。
        SETREGID | SETGID | SETREUID | SETUID | SETRESUID | SETRESGID | SETGROUPS
        | FCHOWN => SyscallPlan {
            op: Op::FakeRootEnter,
            args: [None, None],
            ret_buf_slot: None,
        },
        // chown/lchown 老接口（x86_64 92/94，glibc 直发；musl 走 fchownat）：
        // 阉割命中时 nr 已换 getpid、路径无人读；fake_root 关闭时阉割不生效，
        // 仍需翻路径（槽 0 无 dirfd），故计划带路径参数。
        CHOWN | LCHOWN => SyscallPlan {
            op: Op::FakeRootEnter,
            args: [
                Some(PathArg {
                    slot: 0,
                    dirfd_slot: None,
                }),
                None,
            ],
            ret_buf_slot: None,
        },
        // fchownat：同上，但路径在槽 1 + dirfd 槽 0。
        FCHOWNAT => SyscallPlan {
            op: Op::FakeRootEnter,
            args: [
                Some(PathArg {
                    slot: 1,
                    dirfd_slot: Some(0),
                }),
                None,
            ],
            ret_buf_slot: None,
        },
        // exit 伪装系：getuid/gid 家族 + getresuid/gid + setfsuid/gid + getgroups。
        // 其中 getresuid/getresgid 的三个出参指针需 enter 阶段缓存（见 Tracer）。
        GETUID | GETEUID | GETGID | GETEGID | GETRESUID | GETRESGID | SETFSUID | SETFSGID
        | GETGROUPS => SyscallPlan {
            op: Op::FakeRootExit,
            args: [None, None],
            ret_buf_slot: None,
        },
        // 其余一律直通（io_pgetevents 292 等），逐版本按需纳入
        // （对齐上游增量扩表节奏）。
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
    /// syscall 号（aarch64：x8；x86_64：enter/exit 均取 `orig_rax`）。
    ///
    /// 返回**平台原生号**；跨平台比较处（syscall_plan/fake root 匹配）
    /// 一律先过 [`to_internal_nr`] 归一化为 aarch64 内部号。
    pub fn nr(&self) -> u64 {
        self.x[8]
    }

    /// 设置 syscall 号（enter 阶段阉割替身用；内部号经 [`to_native_nr`] 转平台号）。
    pub fn set_nr(&mut self, internal_nr: u64) {
        self.x[8] = to_native_nr(internal_nr);
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
    /// 新路径放不下原缓冲，已改写入 tracee scratch 区并重指参数寄存器。
    WrittenRelocated,
    /// 新路径放不下原缓冲且无 scratch 可用，保持原路径——保守正确策略。
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
    new_len < original_len + 1
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

/// 从子进程内存**精确**读满 `buf.len()` 字节。
///
/// `process_vm_readv` 允许短读（跨页时常见），与 [`read_child_cstr` 的
/// "读到 NUL 即止"不同——argv 指针数组等定长结构必须读满，故循环补足；
/// 短读重试后仍失败则降级 PEEKDATA 逐字（与 read_child_cstr 同款兜底）。
fn read_child_exact(pid: libc::pid_t, addr: u64, buf: &mut [u8]) -> ProotResult<()> {
    let mut done = 0usize;
    while done < buf.len() {
        // 安全：local/remote iovec 长度均以 buf 剩余为界；只读。
        let n = unsafe {
            let local = libc::iovec {
                iov_base: buf.as_mut_ptr().add(done) as *mut libc::c_void,
                iov_len: buf.len() - done,
            };
            let remote = libc::iovec {
                iov_base: (addr + done as u64) as *mut libc::c_void,
                iov_len: buf.len() - done,
            };
            libc::process_vm_readv(pid, &local, 1, &remote, 1, 0)
        };
        if n > 0 {
            done += n as usize;
            continue;
        }
        // 降级：PEEKDATA 按字长对齐读单字节（任意对齐安全）。
        // 安全：地址来自调用方限界的 buf 区间；只读。
        let byte_addr = addr + done as u64;
        let word = std::mem::size_of::<libc::c_long>() as u64;
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
        buf[done] = bytes[off];
        done += 1;
    }
    Ok(())
}

/// argv 数组元素数上限（对齐 Linux MAX_ARG_STRINGS 量级的防御值，
/// 防病态/恶意 argv 把 tracer 拖死）。
const MAX_ARGC: usize = 4096;

/// 读 tracee 的 argv 指针数组（`rsi` 指向的 NULL 结尾 `char*` 数组）内容。
///
/// EXECVE 改写专用：把 `execve(path, argv, envp)` 换成显式解释器调用
/// `execve(loader, [loader, prog, ...原 argv[1..]], envp)` 时，必须先把
/// 原 argv 各字符串整体读出（寄存器只能改一个指针，数组要重建）。
fn read_child_argv(pid: libc::pid_t, addr: u64) -> ProotResult<Vec<String>> {
    let mut out: Vec<String> = Vec::new();
    let mut cur = addr;
    for _ in 0..MAX_ARGC {
        let mut raw = [0u8; 8];
        read_child_exact(pid, cur, &mut raw)?;
        let ptr = u64::from_ne_bytes(raw);
        if ptr == 0 {
            return Ok(out);
        }
        out.push(read_child_cstr(pid, ptr)?);
        cur += 8;
    }
    Err(ProotError::Ptrace("argv 数组超过 MAX_ARGC 上限".into()))
}

/// 翻译器：持规则集，执行单次 enter/exit 改写（逻辑拆为方法以便复用与测试）。
pub struct Tracer {
    pub rules: TranslateRules,
    /// per-tracee 的 fake root 待处理记录（enter 阶段写入，exit 阶段消费）。
    ///
    /// - `FakeRootEnter` 类（setuid/chown 系）：记录原 syscall 号，nr 已换成
    ///   getpid，exit 时读不到原号，必须靠此表还原语义并返回 0；
    /// - `getresuid/getresgid`：enter 时缓存三个出参指针（exit 时 x0 已被
    ///   返回值覆盖，读不到原指针），exit 时向三处写 0。
    neutered: std::cell::RefCell<std::collections::HashMap<libc::pid_t, NeuteredCall>>,
    /// per-tracee 的 exit 还原缓冲指针缓存（enter 阶段写入，exit 阶段消费）。
    ///
    /// 两架构的 syscall-exit 停都用返回值覆盖 arg0 槽（x86_64 `rax` /
    /// aarch64 `x0`），而 getcwd 的返回缓冲正是 arg0——exit 停的寄存器里
    /// 已读不到原指针（原始 getcwd 系统调用返回的是**长度**而非指针，
    /// 指针转换是 glibc 包装层做的事；strace 实证 `getcwd(...,4096) = 5`）。
    /// 故 enter 停缓存 `ret_buf_slot` 指向的地址，exit 停改用它。
    /// readlinkat 的 buf 在 slot 2（x86_64 rdx）exit 停存活，缓存仅缺失时
    /// 的兜底直取路径。
    exit_bufs: std::cell::RefCell<
        std::collections::HashMap<libc::pid_t, (u8, u64, Option<String>)>, // (slot, 缓冲地址, self-exe 覆盖值)
    >,
    /// per-tracee 的 tracee 地址空间内 scratch 缓冲（`mmap` 注入分配）。
    ///
    /// 动机：`-r` 场景下 host 路径几乎必然长于 guest 路径（guest `/etc` →
    /// host `/data/data/com.termux/files/usr/var/rootfs/etc`），原缓冲装不下。
    /// 早期策略"放不下就跳过翻译"直接让隔离失效（实测 `-r` 下 guest 看到的是
    /// host 真实根目录）。故改为 proot loader-mem 的等价机制：在 tracee **自己
    /// 的地址空间**里 mmap 一块匿名内存，长路径写进去，再把参数寄存器指过去。
    ///
    /// 环形 bump 分配：`used` 单调递增，越界即归零复用（上一轮写入者的 syscall
    /// 已执行完毕，内容无人再读；单个 syscall 的路径参数在本次 enter 停一次性
    /// 写齐，执行期间内核 copy_from_user 读取，之后不再有引用）。
    ///
    /// 分配时机：tracee 尚无 scratch 时的第一条 syscall enter 停——偷换该
    /// syscall 的参数执行分配，exit 停恢复现场并回退 PC 重执行原 syscall
    /// （方案 I，见 [`Tracer::stolen_mmap_regs`] 的机制文档）。
    scratch: std::cell::RefCell<
        std::collections::HashMap<libc::pid_t, ScratchRegion>,
    >,
    /// 最近一次 exec 的 guest 程序路径（comm 伪装与 `/proc/self/exe` 还原用）。
    ///
    /// 初始命令经宿主侧预解析后，tracee 实际 exec 的是 guest loader
    /// （见 `cli::run_config` 的 `prepare_initial_exec`）——comm 会变成
    /// `ld-musl-x86_64.so.1`、`/proc/self/exe` 指向 loader 文件，guest
    /// 视角全错。这里记下用户原始程序的 guest 路径：首停时改写
    /// `/proc/<pid>/comm`，readlink `/proc/self/exe` 时覆盖返回缓冲。
    ///
    /// 运行时 execve 同样经 [`Tracer::handle_execve_enter`] 走显式解释器
    /// 调用（loader 再次成为 `/proc/self/exe`），故每次成功改写都刷新此值
    /// ——guest shell 里 exec 的程序读 self/exe 也能得到正确路径。
    /// RefCell：拦截方法全是 `&self`（单线程停循环内串行消费，无竞争）。
    initial_guest_prog: std::cell::RefCell<Option<String>>,
}

/// tracee 地址空间内的 scratch 区（mmap 注入的产物）。
#[derive(Debug, Clone, Copy)]
struct ScratchRegion {
    /// 基址（tracee 视角）。
    addr: u64,
    /// 总大小（字节）。
    total: usize,
    /// 已用游标（环形）。
    used: usize,
}

impl ScratchRegion {
    /// 环形分配 `len` 字节，返回偏移后的地址。
    fn alloc(&mut self, len: usize) -> u64 {
        let start = if self.used + len > self.total {
            0 // 环绕复用（旧内容引用已消亡）
        } else {
            self.used
        };
        self.used = start + len;
        self.addr + start as u64
    }
}

/// 每个 tracee 的 scratch 总量（字节）。覆盖最坏情况：单个 syscall 两条
/// PATH_MAX 路径（renameat）+ 余量。
const SCRATCH_TOTAL: usize = 3 * MAX_PATH;

/// 偷换 mmap 的重试上限（每 pid）。分配失败（mmap 返回 -errno）时若无上限，
/// "失败→下一条 mmap 再偷→再失败"会无限循环；超限即放弃，长路径改写降级
/// 为跳过翻译（保守正确，等价旧策略）。
const STEAL_MAX_ATTEMPTS: u32 = 4;

/// syscall 指令宽度（PC 回退重执行用）：x86_64 `syscall`（0f 05）= 2 字节；
/// aarch64 `svc #0` = 4 字节。exit 停时 PC 已越过该指令，回退此宽度即
/// 重新指向它（gdb 式注入的标准收尾）。
#[cfg(target_arch = "x86_64")]
const SYSCALL_INSN_LEN: i64 = 2;
#[cfg(not(target_arch = "x86_64"))]
const SYSCALL_INSN_LEN: i64 = 4;

/// 一条被伪装的 syscall 的待处理状态。
#[derive(Debug, Clone, Copy)]
struct NeuteredCall {
    /// 原 syscall 号（exit 阶段按它决定如何处理）。
    orig_nr: u64,
    /// getresuid/getresgid 的三个出参指针；其余场景全 0 忽略。
    out_ptrs: [u64; 3],
}

impl Tracer {
    pub fn new(rules: TranslateRules) -> Tracer {
        Tracer {
            rules,
            neutered: std::cell::RefCell::new(std::collections::HashMap::new()),
            exit_bufs: std::cell::RefCell::new(std::collections::HashMap::new()),
            scratch: std::cell::RefCell::new(std::collections::HashMap::new()),
            initial_guest_prog: std::cell::RefCell::new(None),
        }
    }

    /// 设置初始 guest 程序路径（comm 伪装 + `/proc/self/exe` 还原）。
    /// 无 rootfs 的直通模式调用方可不设（exec 的就是原程序本身）。
    pub fn set_initial_guest_prog(&mut self, prog: String) {
        *self.initial_guest_prog.borrow_mut() = Some(prog);
    }

    /// enter 停止：按计划翻译全部路径参数并写回。
    /// 返回本条 syscall 是否发生了改写（exit 阶段用于决定是否需要还原）。
    pub fn handle_enter(
        &self,
        pid: libc::pid_t,
        regs: &mut Regs,
        plan: &SyscallPlan,
    ) -> RewriteOutcome {
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
            // 相对路径由 guest_to_host 原样直通（M5.2）：内核按已翻译的
            // cwd/dirfd 解析——chdir 在 enter 停已翻成 host 路径（内核 cwd
            // 即 host 目录），dirfd 来自已翻译的 openat。绝对路径才走绑定/
            // rootfs 翻译。
            let host = self.rules.guest_to_host(&guest);
            let new = host.to_string_lossy().into_owned();
            if std::env::var("MOXSH_DBG").is_ok() {
                eprintln!("[dbg] pid={} enter nr={} slot={} guest={} host={}", pid, regs.nr(), slot.slot, guest, new);
            }
            if new == guest {
                continue;
            }
            let orig_len = guest.len();
            if !can_fit(orig_len, new.len()) {
                // 长路径（`-r` 场景常态）：写入 tracee 自有 scratch 区并重指
                // 参数寄存器——proot loader-mem 的等价机制。scratch 来自偷换
                // tracee 自身第一条 syscall 的分配（方案 I，见
                // [`Tracer::stolen_mmap_regs`] 文档）；无 scratch（尚未轮到
                // 或分配失败）时降级为跳过翻译，行为同旧策略。
                let target = self
                    .scratch
                    .borrow_mut()
                    .get_mut(&pid)
                    .map(|region| region.alloc(new.len() + 1));
                let Some(target) = target else {
                    return RewriteOutcome::SkippedTooLong;
                };
                let mut with_nul = new.into_bytes();
                with_nul.push(0);
                if write_child_bytes(pid, target, &with_nul).is_err() {
                    return RewriteOutcome::Unreadable;
                }
                regs.set_arg(slot.slot as usize, target);
                overall = RewriteOutcome::WrittenRelocated;
                continue;
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

    /// EXECVE enter：动态 ELF 的「显式解释器调用」改写（运行时版，
    /// 与初始 exec 的 `cli::prepare_initial_exec` 同一套
    /// [`resolve_exec_chain`] 机制）。
    ///
    /// # 为什么不能只翻路径
    /// 内核 exec 一个带 `PT_INTERP` 的 ELF 时，会按 ELF 头里的解释器路径
    /// （guest 视角的 `/lib/ld-musl-x86_64.so.1`）在**宿主**命名空间打开
    /// ——必然 ENOENT。M6 e2e 实证：guest shell 自身能跑（初始 exec 走了
    /// 显式解释器调用），但 shell 再 exec 任何动态二进制（`cat`/`ls`/`id`）
    /// 全部 "not found"，正是此因。
    ///
    /// # 改写布局
    /// `execve(path, [prog, args...], envp)` →
    /// `execve(loader_host, [loader_host] + argv_prefix + 原 argv[1..], envp)`。
    /// argv[0] 让位给链上第一个 guest 程序路径（busybox 等多二进制依赖
    /// argv[0] basename 选 applet，guest 路径与调用方 argv[0] 通常同基名）；
    /// envp 原样不动。argv_prefix 不含 loader 自身（`ld.so EXECUTABLE-FILE
    /// [ARGS...]` 语义，与 elf.rs 实测结论一致）。
    ///
    /// 链尾是静态 ELF / 非 ELF / 读不到时退化为普通路径翻译（只翻 path，
    /// 同上游"只翻路径"语义）；无 scratch 或 argv 读不出时整体不改写
    /// （返回 SkippedTooLong/Unreadable，内核给出权威错误，绝不写坏内存）。
    pub fn handle_execve_enter(&self, pid: libc::pid_t, regs: &mut Regs) -> RewriteOutcome {
        let path_addr = regs.arg(0);
        let argv_addr = regs.arg(1);
        if path_addr == 0 || argv_addr == 0 {
            return RewriteOutcome::Unchanged; // NULL：内核权威 EFAULT
        }
        let Ok(guest_path) = read_child_cstr(pid, path_addr) else {
            return RewriteOutcome::Unreadable;
        };
        // 相对路径按 guest cwd 绝对化（sh 的 `./script`）：内核 cwd 是已
        // 翻译的宿主目录（chdir 在 enter 停已翻），readlink 后 host→guest
        // 反推；失败则原样传入（resolve_exec_chain 读不到会停链 → Direct）。
        let guest_abs = if guest_path.starts_with('/') {
            guest_path.clone()
        } else {
            match self.guest_cwd(pid) {
                Some(cwd) => normalize(&format!("{}/{}", cwd, guest_path))
                    .to_string_lossy()
                    .into_owned(),
                None => guest_path.clone(),
            }
        };
        let host = self.rules.guest_to_host(&guest_abs);
        let (exec_host, argv_prefix) = match resolve_exec_chain(&guest_abs, &host, &self.rules) {
            ExecPlan::Rewrite {
                exec_host,
                argv_prefix,
            } => (exec_host, argv_prefix),
            ExecPlan::Direct => {
                // 静态 ELF / 链尾 loader 缺失：只翻路径（handle_enter 同款）。
                let plan = SyscallPlan::rewrite(
                    Some(PathArg {
                        slot: 0,
                        dirfd_slot: None,
                    }),
                    None,
                );
                return self.handle_enter(pid, regs, &plan);
            }
        };
        let Ok(orig_argv) = read_child_argv(pid, argv_addr) else {
            return RewriteOutcome::Unreadable;
        };
        // 新 argv：[loader] + prefix + 原 argv[1..]（argv[0] 让位给链上
        // 第一个 guest 程序，见方法文档）。
        let mut new_argv: Vec<String> = Vec::with_capacity(2 + argv_prefix.len() + orig_argv.len());
        new_argv.push(exec_host.clone());
        new_argv.extend(argv_prefix);
        if orig_argv.len() > 1 {
            new_argv.extend(orig_argv[1..].iter().cloned());
        }
        // 单块 scratch 布局：[loader_path\0][s0\0 s1\0 ...][ptr0..ptrn NULL]。
        // 一次 alloc 整块——分两次 alloc 在 SCRATCH_TOTAL 紧张时会环绕互踩。
        let str_bytes: usize = new_argv.iter().map(|s| s.len() + 1).sum::<usize>()
            + exec_host.len()
            + 1;
        let total = str_bytes + 8 * (new_argv.len() + 1);
        let Some(base) = self
            .scratch
            .borrow_mut()
            .get_mut(&pid)
            .filter(|r| r.total >= total)
            .map(|r| r.alloc(total))
        else {
            return RewriteOutcome::SkippedTooLong; // 无 scratch：不翻译，内核 ENOENT
        };
        let mut off = base;
        let mut path_bytes = exec_host.clone().into_bytes();
        path_bytes.push(0);
        if write_child_bytes(pid, off, &path_bytes).is_err() {
            return RewriteOutcome::Unreadable;
        }
        off += path_bytes.len() as u64;
        let mut ptrs: Vec<u8> = Vec::with_capacity(8 * (new_argv.len() + 1));
        let mut cur_ptr_off = base + (exec_host.len() + 1) as u64; // 首字符串基址
        for s in &new_argv {
            let mut b = s.clone().into_bytes();
            b.push(0);
            if write_child_bytes(pid, off, &b).is_err() {
                return RewriteOutcome::Unreadable;
            }
            ptrs.extend_from_slice(&cur_ptr_off.to_ne_bytes());
            cur_ptr_off += b.len() as u64;
            off += b.len() as u64;
        }
        ptrs.extend_from_slice(&[0u8; 8]); // argv NULL 结尾
        debug_assert_eq!(off, base + str_bytes as u64, "指针数组必须紧跟字符串段");
        if write_child_bytes(pid, off, &ptrs).is_err() {
            return RewriteOutcome::Unreadable;
        }
        regs.set_arg(0, base); // path = loader 宿主路径
        regs.set_arg(1, off); // argv = 指针数组
        // 刷新 self/exe 还原基准：此后该 tracee 的 /proc/self/exe 是
        // loader 文件，guest 视角应看到本次 exec 的 guest 程序。
        *self.initial_guest_prog.borrow_mut() = Some(guest_abs);
        RewriteOutcome::WrittenRelocated
    }

    /// guest 视角 cwd：readlink `/proc/<pid>/cwd` 得宿主目录（chdir 已被
    /// 翻译成 host 路径），再 host→guest 反推。失败（进程退出竞争等）→ None。
    fn guest_cwd(&self, pid: libc::pid_t) -> Option<String> {
        let host = std::fs::read_link(format!("/proc/{}/cwd", pid)).ok()?;
        let guest = self.rules.host_to_guest(&host)?;
        Some(guest)
    }

    /// 构造"偷换 guest 自身 mmap"用的参数快照（纯函数，便于单测）。
    ///
    /// 调用方把返回值 SETREGSET 写回 enter 停现场，原快照由 [`Tracer`] 的
    /// `stealing` 表保管，exit 停恢复（见 `run` 主循环的偷换分支）。
    ///
    /// # 机制总览（方案 I：gdb 式 syscall 注入）
    ///
    /// 早期方案（首停偷换 syscall 号注入 mmap）已被实测**证伪**：exec 后
    /// SIGTRAP / clone attach 停止等**非 syscall 停**改 `orig_rax` 无效——
    /// tracee 恢复后执行 `syscall` 指令时用 rax（x8）选号，且 glibc 包装会
    /// 先 `mov rax, NR` 覆盖写入值（实证：注入 mmap 后 tracee 仍执行 brk，
    /// 读到 `orig_nr=12`）。syscall 停则不同：内核按 orig_rax 取号执行，
    /// 改 orig_rax + 参数即完成替换。
    ///
    /// 故改为偷换 tracee **自己**的 syscall 调用（不限类型，号与参数成套替换）：
    /// 1. enter 停（该 tracee 尚无 scratch）：syscall 号与参数改成
    ///    `mmap(NULL, SCRATCH_TOTAL, RW, PRIVATE|ANON, -1, 0)`，保存原快照；
    /// 2. exit 停：rax/x0 即基址（或 -errno）→ 入 [`Tracer::scratch`] 表；
    ///    恢复原参数寄存器与 syscall 号，再把 PC 回退一条 syscall 指令宽度
    ///    （x86_64 `rip-2` / aarch64 `pc-4`）重新执行原 syscall——原 syscall
    ///    尚未执行过（参数在 enter 停已被替换），恢复+重放语义完全无损。
    ///
    /// **为什么必须偷第一条 syscall 而非等第一条 mmap**：musl 动态加载器
    /// 实测打开主程序发生在任何 mmap 之前（先 open 读 ELF 头再映射），
    /// 只偷 mmap 则那条 open 等不到 scratch，长路径（rootfs 前缀）翻译
    /// 被跳过，loader 在宿主命名空间找 guest 路径直接 ENOENT——
    /// 整个「显式解释器调用」方案在 x86_64 上失效（M6 e2e 实证）。
    ///
    /// 失败兜底：mmap 返回负值不插表，长路径改写保守跳过（旧策略）；
    /// steal_attempts 计数防同一 tracee 无限重偷。
    ///
    /// # 为什么必须连 syscall 号一起换（M6 e2e 实证的 EFAULT）
    /// 只换参数不换号是致命 bug：x86_64 上内核按 `orig_rax` 取号执行，
    /// 号还是原 syscall（如 musl loader 开主程序的 `open`），配的却是
    /// mmap 的六参 → `open(NULL, 0x10000, PROT_RW, …)`，pathname 为空指针
    /// 直接 EFAULT，guest loader 报 "Bad address" 退出，重放机会都没有。
    /// 必须 `set_nr(MMAP)`（内部号，`set_nr` 转平台号：x86_64=9 /
    /// aarch64=222）与六参一起写回，偷出来的才真是 mmap。
    fn stolen_mmap_regs(saved: &Regs) -> Regs {
        let mut r = *saved;
        r.set_nr(sysnr::MMAP); // 号与参数必须成套替换，见方法文档
        r.x[0] = 0; // addr = NULL（让内核自选）
        r.x[1] = SCRATCH_TOTAL as u64; // length
        r.x[2] = 0x3; // PROT_READ | PROT_WRITE
        r.x[3] = 0x22; // MAP_PRIVATE | MAP_ANONYMOUS
        r.x[4] = u64::MAX; // fd = -1
        r.x[5] = 0; // offset
        r
    }

    /// readlink 类 syscall 读 `/proc/self/exe` 时返回 guest 原始程序路径。
    ///
    /// 初始 exec 经宿主侧预解析后执行的是 guest loader（见
    /// `cli::run_config` 的 `prepare_initial_exec`），不覆盖的话 guest
    /// 读 `/proc/self/exe` 得到的是 loader 文件（如 ld-musl-x86_64.so.1），
    /// 进程自查（`/proc/self/exe` 读自身路径的守护进程/自更新程序）全错。
    /// None = 无需覆盖（无初始程序记录或不是 readlink self/exe）。
    fn self_exe_override_for(
        &self,
        pid: libc::pid_t,
        plan: &SyscallPlan,
        regs: &Regs,
    ) -> Option<String> {
        let prog = self.initial_guest_prog.borrow().clone()?;
        if !matches!(plan.op, Op::RestoreOnExit) {
            return None; // getcwd 不可能是 self/exe
        }
        // 只查 plan 声明的路径槽（readlink=0 / readlinkat=1），顺带排除
        // getcwd（无路径槽）。
        for slot in plan.args.iter().flatten() {
            let addr = regs.arg(slot.slot as usize);
            if addr == 0 {
                continue;
            }
            if let Ok(s) = read_child_cstr(pid, addr) {
                if s == "/proc/self/exe" {
                    return Some(prog);
                }
            }
        }
        None
    }

    /// exit 停止：getcwd/readlinkat 的返回缓冲是 guest 视角路径，
    /// 需 host→guest 反向还原后写回（对齐上游 detranslate_path）。
    pub fn handle_exit_restore(&self, pid: libc::pid_t, regs: &Regs, plan: &SyscallPlan) {
        let Some(slot) = plan.ret_buf_slot else { return };
        // enter 停缓存的缓冲地址优先（exit 停 arg0 槽被返回值覆盖，见
        // [Tracer::exit_bufs] 文档）；缓存缺失（单测直调等）时退回寄存器
        // 直取——slot>=1 的参数槽在两架构 exit 停均存活。
        let cached = self.exit_bufs.borrow_mut().remove(&pid);
        let (addr, exe_override) = match cached {
            Some((s, a, ov)) if s == slot => (a, ov),
            _ => (regs.arg(slot as usize), None),
        };
        if addr == 0 {
            return;
        }
        // 返回值是写入长度（不含 NUL）；失败（负值）不处理。
        let ret = regs.ret() as i64;
        if ret <= 0 {
            return;
        }
        // `/proc/self/exe` 覆盖：初始 exec 的是 loader，exe 链接指向 loader
        // 文件；guest 视角必须是用户原始程序（见 initial_guest_prog 文档）。
        // 容量以原返回长度为安全下界（loader 宿主路径通常更长）。
        if let Some(prog) = exe_override {
            let cap = ret as usize + 1;
            let mut bytes = prog.into_bytes();
            bytes.push(0);
            if bytes.len() <= cap {
                let _ = write_child_bytes(pid, addr, &bytes);
            }
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

    /// fake root：enter 阶段阉割身份类 syscall（对齐上游 fake_id0）。
    ///
    /// - `setuid/setgid/setreuid/setregid/setresuid/setresgid/setgroups/
    ///   fchownat/fchown`：nr 替换为 getpid（无副作用且必然成功），原号
    ///   记入 [`Tracer::neutered`]，exit 阶段返回 0——调用方以为成功了；
    /// - `getresuid/getresgid`：不替换，仅缓存三个出参指针供 exit 写 0
    ///   （exit 时 x0 已被返回值覆盖，原指针丢失）。
    ///
    /// 返回 `true` 表示寄存器已改、调用方需 `set_regs` 写回。
    /// fake root 关闭时原样放行（非特权 uid 调 setuid 本就该 EPERM）。
    pub fn neuter_enter(&self, pid: libc::pid_t, regs: &mut Regs) -> bool {
        use sysnr::*;
        if !self.rules.fake_root() {
            return false;
        }
        // 归一化为内部号后再匹配（x86_64 上原生号与 aarch64 编号不同）。
        let Some(internal) = to_internal_nr(regs.nr()) else {
            return false;
        };
        match internal {
            SETREGID | SETGID | SETREUID | SETUID | SETRESUID | SETRESGID | SETGROUPS
            | FCHOWNAT | FCHOWN | CHOWN | LCHOWN => {
                self.neutered.borrow_mut().insert(
                    pid,
                    NeuteredCall { orig_nr: internal, out_ptrs: [0; 3] },
                );
                regs.set_nr(GETPID);
                true
            }
            GETRESUID | GETRESGID => {
                self.neutered.borrow_mut().insert(
                    pid,
                    NeuteredCall {
                        orig_nr: internal,
                        out_ptrs: [regs.arg(0), regs.arg(1), regs.arg(2)],
                    },
                );
                false // 不改寄存器，仅记录
            }
            _ => false,
        }
    }

    /// fake root：对身份类 syscall 的返回值伪装（对齐上游 fake_id0）。
    ///
    /// - [`Tracer::neutered`] 里有记录的（enter 阉割的 setuid/chown 系、
    ///   getresuid/gid）：一律返回 0；getresuid/gid 另向三个出参指针写 0；
    /// - exit-only 类（getuid/geteuid/getgid/getegid/setfsuid/setfsgid/
    ///   getgroups）：直接把返回值改成 0。
    ///
    /// 返回 `true` 表示 regs 已改、调用方需 `set_regs` 写回。
    pub fn apply_fake_root_exit(&self, pid: libc::pid_t, regs: &mut Regs) -> bool {
        use sysnr::*;
        if !self.rules.fake_root() {
            return false;
        }
        if let Some(n) = self.neutered.borrow_mut().remove(&pid) {
            match n.orig_nr {
                GETRESUID | GETRESGID => {
                    // 三个出参指针（enter 时缓存；exit 的 x0 已是返回值读不到）。
                    for p in n.out_ptrs {
                        if p != 0 {
                            let _ = write_child_bytes(pid, p, &[0u8; 8]);
                        }
                    }
                }
                _ => {}
            }
            regs.set_ret(0);
            return true;
        }
        // exit-only 类：getuid/geteuid/getgid/getegid/setfsuid/setfsgid/
        // getgroups——直接把返回值改成 0。
        //
        // 注意 x86_64 语义：exit 时 `orig_rax` 是**实际执行过的** syscall 号
        // （enter 阉割替身执行过的情况 orig_rax 已是 getpid，但那条走上面
        // 的 map 命中路径，到不了这里）；能走到这里的必然是未被替换的
        // 原生命，归一化后匹配。clippy::collapsible_match：Option 解包 +
        // 单分支 match 合并为带 or 模式的 if let。
        if let Some(GETUID | GETEUID | GETGID | GETEGID | SETFSUID | SETFSGID | GETGROUPS) =
            to_internal_nr(regs.nr())
        {
            regs.set_ret(0);
            return true;
        }
        false
    }

    /// /proc 身份文件的内容级伪装动作查询（供 exit 读缓冲阶段使用）。
    pub fn fake_action(&self, guest_path: &str) -> FakeRootAction {
        self.rules.apply_fake_root(guest_path)
    }

    /// 启动被跟踪子进程：fork → 子 `PTRACE_TRACEME` → `execve(cmd)`。
    ///
    /// **不再有 SIGSTOP 握手**（M4.1 演进）：早期版本让子进程 TRACEME 后
    /// raise(SIGSTOP)，父进程在首停设选项。但实测（sandbox x86_64 与
    /// strace 交叉验证）：抑制子进程自 raise 的 SIGSTOP 后，该环境随后的
    /// syscall 停止整体不再递送（strace 跟踪 SIGSTOP 程序同样挂死）——
    /// 握手把"设置选项"与"抑制停止信号"耦合在一起，脆弱且非内核保证
    /// 语义。新方案：子进程 TRACEME 后**立即 execve**，首个停止必是 exec
    /// SIGTRAP（停 = TRACEME 已执行完的铁证），父进程在该停止设置全部
    /// 选项——既消除原 SETOPTIONS 的 ESRCH race，又不碰停止信号语义。
    ///
    /// # Safety（unsafe 块逐条依据）
    /// - fork 后子进程只做 exec 前的最小动作（TRACEME/execve/_exit），
    ///   不触碰任何需跨线程同步的状态（单线程 fork 安全）；
    /// - execve 的 argv 以 CString 构造，保证 NUL 结尾与生命周期覆盖调用点。
    pub fn spawn_traced(cmd: &[&str]) -> ProotResult<libc::pid_t> {
        Self::spawn_traced_with_env(cmd, &[])
    }

    /// [`Self::spawn_traced`] 的环境变量变体：`env` 非空时子进程以该环境
    /// （`KEY=VALUE` 列表）execve；为空时沿用历史行为（envp=NULL，内核视为
    /// 空环境）——moxsh App 内嵌路径与既有单测均依赖该语义，勿改。
    ///
    /// CLI 直跑（`moxsh-proot`）场景：未显式 `-e` 时继承 tracer 当前环境，
    /// 这是交互 shell 的预期行为；显式 `-e` 则清空并以所给为准（对齐
    /// proot-distro login 的 `env -i` 语义）。
    pub fn spawn_traced_with_env(
        cmd: &[&str],
        env: &[&str],
    ) -> ProotResult<libc::pid_t> {
        Self::spawn_traced_with_cwd(cmd, env, None)
    }

    /// [`Self::spawn_traced_with_env` 的初始 cwd 变体：execve 前把子进程
    /// chdir 到 `cwd`（**已翻译的 host 路径**，由调用方经规则算出）。
    ///
    /// 为什么需要：guest 的初始 cwd 必须是 host 视图的正确位置，否则
    /// ① `-r` 下子进程继承 tracer 的宿主 cwd（如 /root/ws/...），guest
    /// 直接看到 rootfs 外的真实路径——隔离穿帮；② `-w` 广告了"初始工作
    /// 目录"却只把值存进规则集、从未真正切换（M5.2 修复：宁可少做，
    /// 不可假装支持）。无 rootfs 且无 `-w` 时传 None = 继承 tracer cwd
    /// （对齐上游 proot 无 -r 时的行为）。
    pub fn spawn_traced_with_cwd(
        cmd: &[&str],
        env: &[&str],
        cwd: Option<&Path>,
    ) -> ProotResult<libc::pid_t> {
        let argv: Vec<std::ffi::CString> = cmd
            .iter()
            .map(|s| {
                std::ffi::CString::new(s.as_bytes())
                    .map_err(|_| ProotError::InvalidArg(format!("argv 含 NUL: {}", s)))
            })
            .collect::<ProotResult<_>>()?;
        let envp: Option<Vec<std::ffi::CString>> = if env.is_empty() {
            None
        } else {
            Some(
                env.iter()
                    .map(|s| {
                        std::ffi::CString::new(s.as_bytes())
                            .map_err(|_| ProotError::InvalidArg(format!("env 含 NUL: {}", s)))
                    })
                    .collect::<ProotResult<_>>()?,
            )
        };
        let pid = unsafe { libc::fork() };
        if pid < 0 {
            return Err(ProotError::Io(std::io::Error::last_os_error()));
        }
        if pid == 0 {
            // ===== 子进程：自愿被跟踪后立即 exec =====
            unsafe {
                // TRACEME 必须先于 exec：exec 触发首次 exec 停止，父进程由此接管
                // 并设置跟踪选项（见 Tracer::run 首停分支）。
                if libc::ptrace(libc::PTRACE_TRACEME, 0, std::ptr::null_mut::<libc::c_void>(), std::ptr::null_mut::<libc::c_void>()) < 0 {
                    libc::_exit(126);
                }
                // 初始 cwd：翻译后的 host 路径。失败必须显式退出而非默默
                // 继承 tracer cwd（那会让 guest 看到 rootfs 外真实路径）。
                if let Some(dir) = cwd {
                    let d = match std::ffi::CString::new(dir.as_os_str().as_encoded_bytes()) {
                        Ok(d) => d,
                        Err(_) => libc::_exit(125),
                    };
                    if libc::chdir(d.as_ptr()) < 0 {
                        libc::_exit(125); // cwd 切换失败（host 路径不存在等）
                    }
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
                let env_ptrs: Vec<*const libc::c_char> = match &envp {
                    Some(v) => v.iter().map(|s| s.as_ptr()).chain(std::iter::once(std::ptr::null())).collect(),
                    None => Vec::new(),
                };
                let env_raw = if env_ptrs.is_empty() {
                    std::ptr::null()
                } else {
                    env_ptrs.as_ptr()
                };
                libc::execve(prog.as_ptr(), ptrs.as_ptr(), env_raw);
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
    /// 跟踪主循环：`waitpid(-1, __WALL)` 收取**全进程树**事件并按类型分发。
    ///
    /// # 多进程树模型（对齐上游 tracee 树，M4.1 修复）
    /// 早期版本只 `waitpid(child, 0)` 等初始 child：TRACEFORK/CLONE 产生的
    /// 孙进程/线程事件无人收取，新 tracee 停在 attach 停止永不唤醒，
    /// 且 `in_syscall` 单标志在多 pid 下必然错位——任何 fork 密集型负载
    /// （apt/dpkg/管道/sh -c）都会挂死。现模型：
    /// - 初始 child 的**首个停止**（exec SIGTRAP）内一次性
    ///   `PTRACE_SETOPTIONS`（SYSGOOD|FORK|VFORK|CLONE|EXEC|EXITKILL）；
    ///   fork/vfork/clone 出的新 tracee **自动继承**选项（ptrace(2) 语义），
    ///   无需逐个补设；放在首停而非入口，是因为"有停止"即证明子进程已执行
    ///   TRACEME，SETOPTIONS 不可能撞 ESRCH race（入口处设置曾因此静默失败，
    ///   全部选项丢失、翻译链脱管）；
    /// - `waitpid(-1, __WALL)` 等待任意 tracee（含 clone 线程子任务）；
    /// - per-pid 状态：fake root 记录在 [`Self::neutered`]（成员，跨
    ///   enter/exit 两次停止存活），exit 还原缓冲指针缓存在
    ///   [`Self::exit_bufs`]（同为成员，enter 写 exit 消费），enter/exit
    ///   双停相位在本函数局部 HashMap（仅单次停止消费）；
    /// - 事件分发：
    ///   - `SIGTRAP|0x80`：syscall enter/exit 双停；
    ///   - `PTRACE_EVENT_EXEC(4)`：execve 的 stop 序列为
    ///     `syscall-ENTER 停`（rdi=路径指针，**可改写**）→ `EVENT_EXEC` →
    ///     `syscall-EXIT 停`（M5.1 在 x86_64 实测证实，aarch64 同——
    ///     ptrace(2) man page 示例与 strace 行为一致），enter/exit 双停
    ///     跨事件保持交替，**相位不动**；旧结论"execve 无 syscall-exit
    ///     stop、事件后须重置相位"系 aarch64 单点观察的误推，已证伪；
    ///   - `PTRACE_EVENT_FORK/VFORK/CLONE(1/2/3)`：`GETEVENTMSG` 取新 tracee
    ///     pid，入集合并以 `PTRACE_SYSCALL` 唤醒进入跟踪；
    ///   - 其他停止：普通信号原样递送（对齐上游信号透传策略）。
    /// - 终止：所有 tracee 退出（集合空）或 `ECHILD`；返回 initial child 退出码。
    pub fn run(&self, child: libc::pid_t) -> ProotResult<i32> {
        use std::collections::{HashMap, HashSet};
        // 跟踪选项（SYSGOOD/FORK/VFORK/CLONE/EXEC/EXITKILL）**不在入口设置**：
        // 入口处 SETOPTIONS 存在 race——child 刚 fork 出来、尚未执行到
        // TRACEME 时 ptrace 请求返回 ESRCH（静默失败），全部选项丢失，
        // syscall stop 不再带 0x80、exec 无 EVENT_EXEC，翻译链整体脱管。
        // 改为在初始 child 的首停内设置：能等到停止即证明 TRACEME 已执行
        // （M4.1 修复：SIGSTOP 握手方案在本环境反而掐断 syscall 停止递送，
        // 见 spawn_traced 注释）。
        let trace_opts: libc::c_long = (libc::PTRACE_O_TRACESYSGOOD
            | libc::PTRACE_O_TRACEFORK
            | libc::PTRACE_O_TRACEVFORK
            | libc::PTRACE_O_TRACECLONE
            | libc::PTRACE_O_TRACEEXEC
            | libc::PTRACE_O_EXITKILL) as libc::c_long;

        // 存活 tracee 集合（初始 child + 事件新 attach 的子孙/线程）。
        let mut tracees: HashSet<libc::pid_t> = HashSet::new();
        tracees.insert(child);
        // 首停待处理标记：初始 child 的第一个停止必是 exec SIGTRAP
        // （spawn_traced 让子进程 TRACEME 后立即 execve），在该停止内
        // SETOPTIONS + 相位归零。None = 已处理。
        let mut first_stop: Option<libc::pid_t> = Some(child);
        // per-pid enter/exit 相位：true=下次 syscall 停止是 exit。
        let mut in_syscall: HashMap<libc::pid_t, bool> = HashMap::new();
        // scratch 分配状态机（方案 I：偷换 guest 自身 mmap，详见
        // [`Tracer::stolen_mmap_regs`] 的机制文档）：
        // - `stealing`：参数已被偷换、正在等其 enter/exit 双停的 tracee
        //   → 保存的入口寄存器快照（exit 停恢复 + PC 回退重执行用）；
        // - `steal_attempts`：每 pid 的偷换计数——分配失败（mmap 返回
        //   -errno）时若无限重偷会死循环，超上限即放弃（长路径降级跳过）。
        let mut stealing: HashMap<libc::pid_t, Regs> = HashMap::new();
        let mut steal_attempts: HashMap<libc::pid_t, u32> = HashMap::new();
        let mut exit_code: Option<i32> = None;
        let mut status: libc::c_int = 0;

        loop {
            // __WALL：等待任意 tracee（含 clone 子任务/线程），否则孙进程
            // 事件永远等不到。tracer 无其他普通子进程，-1 不会混入杂项。
            let w = unsafe { libc::waitpid(-1, &mut status, libc::__WALL) };
            if w < 0 {
                let e = std::io::Error::last_os_error();
                if e.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                // ECHILD：所有 tracee 已退出，正常收尾。
                if e.raw_os_error() == Some(libc::ECHILD) {
                    break;
                }
                return Err(ProotError::Io(e));
            }
            let pid = w;

            // ===== 退出事件 =====
            if libc::WIFEXITED(status) {
                tracees.remove(&pid);
                in_syscall.remove(&pid);
                self.neutered.borrow_mut().remove(&pid);
                self.exit_bufs.borrow_mut().remove(&pid);
                self.scratch.borrow_mut().remove(&pid);
                stealing.remove(&pid);
                steal_attempts.remove(&pid);
                if pid == child {
                    exit_code = Some(libc::WEXITSTATUS(status));
                }
                if tracees.is_empty() {
                    break;
                }
                continue;
            }
            if libc::WIFSIGNALED(status) {
                tracees.remove(&pid);
                in_syscall.remove(&pid);
                self.neutered.borrow_mut().remove(&pid);
                self.exit_bufs.borrow_mut().remove(&pid);
                self.scratch.borrow_mut().remove(&pid);
                stealing.remove(&pid);
                steal_attempts.remove(&pid);
                if pid == child {
                    exit_code = Some(128 + libc::WTERMSIG(status));
                }
                if tracees.is_empty() {
                    break;
                }
                continue;
            }
            if !libc::WIFSTOPPED(status) {
                continue; // 继续运行（WIFCONTINUED 等）
            }

            let sig = libc::WSTOPSIG(status);

            // ===== 初始 child 首停（exec SIGTRAP）：设选项 + 相位归零 =====
            // 能等到停止即证明子进程已 TRACEME，SETOPTIONS 必成功；失败则
            // syscall stop 不带 0x80、exec 无 EVENT_EXEC，后续链路全脱管，
            // 必须显式报错而非静默降级。
            if first_stop == Some(pid) {
                first_stop = None;
                unsafe {
                    let rc = libc::ptrace(
                        libc::PTRACE_SETOPTIONS,
                        pid,
                        std::ptr::null_mut::<libc::c_void>(),
                        trace_opts as *mut libc::c_void,
                    );
                    if rc < 0 {
                        return Err(ProotError::Ptrace(format!(
                            "SETOPTIONS 失败: {}",
                            std::io::Error::last_os_error()
                        )));
                    }
                    // exec 已完成：相位归零（下一个 syscall 停必是 enter）。
                    in_syscall.remove(&pid);
                    // comm 伪装：初始 exec 的是 guest loader，内核把 comm
                    // 设成 loader 名（ld-musl-x86_64.so.1）；改写回用户
                    // 原始程序名，guest `ps`/任务名才对。best-effort。
                    if let Some(prog) = self.initial_guest_prog.borrow().as_ref() {
                        let base = Path::new(prog)
                            .file_name()
                            .map(|s| s.to_string_lossy().into_owned())
                            .unwrap_or_else(|| prog.clone());
                        let comm: String = base.chars().take(15).collect();
                        let _ = std::fs::write(format!("/proc/{}/comm", pid), comm);
                    }
                    // PTRACE_SYSCALL 放行：此后该 tracee 的所有 syscall 都会
                    // 双停（PTRACE_CONT 放行的进程不会在 syscall 停）。
                    // scratch 不在此处分配——首停是 exec 后 plain SIGTRAP，
                    // 非 syscall 停，注入 syscall 不可行（实测证伪，见
                    // [`Tracer::stolen_mmap_regs`] 机制文档）；改为偷换该
                    // tracee 后续第一条自身 syscall（见 syscall 停分支）。
                    libc::ptrace(
                        libc::PTRACE_SYSCALL,
                        pid,
                        std::ptr::null_mut::<libc::c_void>(),
                        std::ptr::null_mut::<libc::c_void>(),
                    )
                };
                continue;
            }

            // ===== syscall 停止（SYSGOOD：SIGTRAP|0x80）=====
            if sig == libc::SIGTRAP | 0x80 {
                let entering = !in_syscall.get(&pid).copied().unwrap_or(false);

                // ===== 偷换 guest 自身 syscall 分配 scratch（方案 I）=====
                // 详见 [`Tracer::stolen_mmap_regs`] 的机制文档。两个半停：
                // - enter 停：该 tracee 尚无 scratch、未超重试上限 → 参数改成
                //   mmap(匿名, SCRATCH_TOTAL)，保存原快照。**不限 syscall
                //   类型**：musl loader 实测打开主程序发生在任何 mmap 之前，
                //   只偷 mmap 会永远等不到 scratch，长路径翻译全跳过；
                // - exit 停：`stealing` 命中 → 取基址入表，恢复参数与 syscall
                //   号，PC 回退一条指令宽度，重新执行被偷换的原 syscall
                //   （原 syscall 尚未执行过，恢复+重放语义无损）。
                if entering
                    && !self.scratch.borrow().contains_key(&pid)
                    && !stealing.contains_key(&pid)
                    && steal_attempts.get(&pid).copied().unwrap_or(0) < STEAL_MAX_ATTEMPTS
                {
                    let saved = self.get_regs(pid, true)?;
                    let stolen = Self::stolen_mmap_regs(&saved);
                    self.set_regs(pid, &stolen, true)?;
                    stealing.insert(pid, saved);
                    steal_attempts.insert(pid, steal_attempts.get(&pid).copied().unwrap_or(0) + 1);
                    // 相位照常推进：本停是 stolen mmap 的 enter，下一停
                    // 必是其 exit（由下面的 exit 半停消费）。
                    in_syscall.insert(pid, entering);
                    unsafe {
                        libc::ptrace(
                            libc::PTRACE_SYSCALL,
                            pid,
                            std::ptr::null_mut::<libc::c_void>(),
                            std::ptr::null_mut::<libc::c_void>(),
                        )
                    };
                    continue;
                } else if !entering {
                    if let Some(saved) = stealing.remove(&pid) {
                        // stolen mmap exit 停：rax/x0 = 基址（或 -errno）。
                        let ret = self.get_regs(pid, false)?.ret();
                        if std::env::var("MOXSH_DBG").is_ok() {
                            eprintln!(
                                "[dbg] pid={} steal-mmap-ret={:#x} nr={}",
                                pid,
                                ret,
                                saved.nr()
                            );
                        }
                        if (ret as i64) > 0 {
                            self.scratch.borrow_mut().insert(
                                pid,
                                ScratchRegion {
                                    addr: ret,
                                    total: SCRATCH_TOTAL,
                                    used: 0,
                                },
                            );
                        }
                        // 分配失败：不插表，长路径改写降级跳过（旧策略）；
                        // steal_attempts 已计数，不会无限重偷同一 pid。
                        // 恢复现场：参数寄存器 + syscall 号（exit 停 rax 是
                        // 我们 mmap 的返回值，x86_64 重执行前必须写回原号；
                        // aarch64 x8 未被返回值覆盖，set_regs 已一并恢复）。
                        self.set_regs(pid, &saved, true)?;
                        self.set_exec_reg(pid, saved.nr())?;
                        // PC 回退到 syscall 指令，让原 mmap 真正执行一遍
                        // （guest 语义无损：mmap 无副作用，可安全重执行）。
                        self.adjust_pc(pid, -SYSCALL_INSN_LEN)?;
                        // 重执行的第一次停止必是该 mmap 的 enter。
                        in_syscall.insert(pid, false);
                        unsafe {
                            libc::ptrace(
                                libc::PTRACE_SYSCALL,
                                pid,
                                std::ptr::null_mut::<libc::c_void>(),
                                std::ptr::null_mut::<libc::c_void>(),
                            )
                        };
                        continue;
                    }
                }

                self.handle_syscall_stop(pid, entering)?;
                // 相位推进：刚处理的是 enter（entering=true）→ 下一停必是
                // exit；刚处理的是 exit → 下一停必是 enter。即"存入
                // entering"——曾误写 !entering，导致每个停都被当作 enter，
                // 全部 exit 阶段逻辑（fake root 返回值伪装、getcwd/readlinkat
                // 反向还原）静默失效（M4.1 修复，由 setpriv  e2e 用例抓出）。
                in_syscall.insert(pid, entering);
                unsafe {
                    libc::ptrace(
                        libc::PTRACE_SYSCALL,
                        pid,
                        std::ptr::null_mut::<libc::c_void>(),
                        std::ptr::null_mut::<libc::c_void>(),
                    )
                };
                continue;
            }

            // ===== ptrace 事件停止（SIGTRAP + event 码在 status 高位）=====
            if sig == libc::SIGTRAP {
                let event = (status >> 16) & 0xff;
                match event {
                    libc::PTRACE_EVENT_EXEC => {
                        // execve 的 syscall-ENTER 停（sig=133，rdi=路径
                        // 指针，可改写）→ 本事件 → syscall-EXIT 停（rax=0）
                        // 完整交替，相位由 syscall 分支自行推进，此处**不**
                        // 重置——曾误判"execve 无 exit 停"而在此归零，导致
                        // x86_64 上 execve 后续每个相位错位一格，geteuid 等
                        // exit-only 伪装静默失效（M5.1 修复）。
                        //
                        // 但**地址空间缓存必须作废**：exec 已完成，tracee 的
                        // 地址空间焕然一新，scratch 基址（偷换 mmap 的产物）
                        // 与 exit_bufs 里的返回缓冲指针全部指向旧映像的无效
                        // 内存。不清的后果（M6 e2e 实证）：新映像的第一条
                        // 长路径翻译写进陈旧地址——要么写失败静默跳过翻译
                        // （loader 在宿主命名空间找 guest 路径 ENOENT），要么
                        // 更阴毒地写中新映像碰巧映射到同地址的区域，loader
                        // 内部结构被踩坏后连 open() 都传野指针（"cannot load
                        // /bin/cat: Bad address"）。guest shell exec 任何
                        // 动态二进制全灭即此因。
                        // 相位不动的理由同上：execve 双停照常交替。
                        self.scratch.borrow_mut().remove(&pid);
                        self.exit_bufs.borrow_mut().remove(&pid);
                        if std::env::var("MOXSH_DBG").is_ok() {
                            eprintln!("[dbg] pid={} exec-event: 旧地址空间缓存已作废", pid);
                        }
                    }
                    libc::PTRACE_EVENT_FORK
                    | libc::PTRACE_EVENT_VFORK
                    | libc::PTRACE_EVENT_CLONE => {
                        // 新 tracee pid 经 GETEVENTMSG 取出；选项已由内核
                        // 自动继承，只需唤醒它进入 syscall 跟踪。
                        let mut msg: libc::c_ulong = 0;
                        unsafe {
                            libc::ptrace(
                                libc::PTRACE_GETEVENTMSG,
                                pid,
                                std::ptr::null_mut::<libc::c_void>(),
                                &mut msg as *mut libc::c_ulong as *mut libc::c_void,
                            )
                        };
                        let new_pid = msg as libc::pid_t;
                        if new_pid > 0 && tracees.insert(new_pid) {
                            // 新 tracee 的 scratch 由它自己的第一条 syscall
                            // enter 停按需偷换分配（独立地址空间，父的地址
                            // 无效），无需在此标记——方案 I 对每个 tracee
                            // 自动生效。
                            // 竞争：新 tracee 可能在唤醒前已退出（ESRCH），忽略。
                            unsafe {
                                libc::ptrace(
                                    libc::PTRACE_SYSCALL,
                                    new_pid,
                                    std::ptr::null_mut::<libc::c_void>(),
                                    std::ptr::null_mut::<libc::c_void>(),
                                )
                            };
                        }
                    }
                    _ => {} // 其他事件（未订阅）直接放行
                }
                // 事件停止后必须用 PTRACE_SYSCALL 继续：PTRACE_CONT 放行的
                // tracee 不会在 syscall 停，此后整个翻译链静默失效
                // （EVENT_EXEC 后尤其致命——exec 已完成，后续 syscall
                // 全部脱管）。信号递送场景同理见普通信号分支。
                unsafe {
                    libc::ptrace(
                        libc::PTRACE_SYSCALL,
                        pid,
                        std::ptr::null_mut::<libc::c_void>(),
                        std::ptr::null_mut::<libc::c_void>(),
                    )
                };
                continue;
            }

            // ===== 普通信号停止：递送信号 + 保持 syscall 跟踪 =====
            // PTRACE_SYSCALL 的第 4 参即待递送信号：一个请求同时完成
            // 「递送/抑制信号」与「下个 syscall 停」，避免 CONT 放行后
            // tracee 脱离 syscall 跟踪。
            unsafe {
                libc::ptrace(
                    libc::PTRACE_SYSCALL,
                    pid,
                    std::ptr::null_mut::<libc::c_void>(),
                    sig as libc::c_long as *mut libc::c_void,
                )
            };
        }

        Ok(exit_code.unwrap_or(-1))
    }

    /// 单次 syscall 停止的处理（enter 或 exit，由调用方按 per-pid 相位传入）。
    ///
    /// 从 [Tracer::run] 的多进程树循环抽出——纯"一次停止的寄存器级改写"，
    /// 与等待/分发逻辑解耦，便于单测与复用。
    pub fn handle_syscall_stop(
        &self,
        pid: libc::pid_t,
        entering: bool,
    ) -> ProotResult<()> {
        let mut regs = self.get_regs(pid, entering)?;
        if entering {
            // ===== syscall enter =====
            let plan = syscall_plan(regs.nr());
            // exit 还原类（getcwd/readlinkat/readlink）：enter 停缓存
            // ret_buf_slot 指向的缓冲地址。exit 停 arg0 槽已被返回值覆盖
            // （x86_64 rax / aarch64 x0——getcwd 原始系统调用返回长度而非
            // 指针），不缓存则 exit 停读不到原指针，还原静默失效。
            if let Some(slot) = plan.ret_buf_slot {
                // readlink(/at) 读 `/proc/self/exe`：初始 exec 的是 loader，
                // 结果将是 loader 宿主路径。记下 guest 原始程序，exit 停
                // 覆盖返回缓冲（见 [Tracer::handle_exit_restore]）。
                let exe_override = self.self_exe_override_for(pid, &plan, &regs);
                self.exit_bufs
                    .borrow_mut()
                    .insert(pid, (slot, regs.arg(slot as usize), exe_override));
            }
            match plan.op {
                Op::PassThrough | Op::RestoreOnExit | Op::FakeRootExit => {
                    // getresuid/getresgid（FakeRootExit 子类）需在 enter
                    // 缓存三个出参指针，exit 时 x0 已被返回值覆盖。
                    if self.neuter_enter(pid, &mut regs) {
                        self.set_regs(pid, &regs, entering)?;
                    }
                }
                Op::RewriteOnEnter | Op::RewriteEnterRestoreExit => {
                    // EXECVE 特判：动态 ELF 必须走「显式解释器调用」——
                    // 只翻 path 的话内核按 PT_INTERP 在宿主命名空间找 guest
                    // loader 必然 ENOENT（guest shell exec 动态二进制全灭，
                    // 见 [Tracer::handle_execve_enter] 文档）。
                    // 改写可能重指参数寄存器（path/argv 双槽走 scratch 重定位），
                    // 故需在改写后把寄存器写回 tracee。
                    let outcome = if to_internal_nr(regs.nr()) == Some(sysnr::EXECVE) {
                        self.handle_execve_enter(pid, &mut regs)
                    } else {
                        self.handle_enter(pid, &mut regs, &plan)
                    };
                    if matches!(outcome, RewriteOutcome::WrittenRelocated) {
                        self.set_regs(pid, &regs, entering)?;
                    }
                }
                Op::FakeRootEnter => {
                    // setuid/chown 系：nr 换成 getpid 并记录原号。
                    if self.neuter_enter(pid, &mut regs) {
                        self.set_regs(pid, &regs, entering)?;
                    } else if plan.args.iter().flatten().next().is_some() {
                        // fake root 关闭：阉割不生效，但 chown/lchown/fchownat
                        // 的路径参数仍要翻译（否则 guest 路径在宿主命名空间
                        // 必然 ENOENT）——与 RewriteOnEnter 同款处理。
                        let outcome = self.handle_enter(pid, &mut regs, &plan);
                        if matches!(outcome, RewriteOutcome::WrittenRelocated) {
                            self.set_regs(pid, &regs, entering)?;
                        }
                    }
                }
            }
        } else {
            // ===== syscall exit =====
            // 先走 fake root：map 命中（enter 阉割的 setuid/chown 系、
            // getresuid/gid）或 exit-only 伪装（getuid 系等）。
            let changed = self.apply_fake_root_exit(pid, &mut regs);
            if !changed {
                // 常规 exit：还原返回缓冲（getcwd/readlinkat/readlink）。
                let plan = syscall_plan(regs.nr());
                if matches!(plan.op, Op::RestoreOnExit | Op::RewriteEnterRestoreExit) {
                    self.handle_exit_restore(pid, &regs, &plan);
                }
            }
            if changed {
                self.set_regs(pid, &regs, entering)?;
            }
        }
        Ok(())
    }

    /// GETREGSET(NT_PRSTATUS=1) 读取通用寄存器并转换为内部 [`Regs`] 视图。
    ///
    /// `entering` 决定参数/返回值的装载方式（两架构语义对齐的关键）：
    /// - **enter 停**：x0..x5 = 六个 syscall 参数；
    /// - **exit 停**：x0 = 返回值，x1..x5 = 内核**原样保留**的入口参数
    ///   （pt_regs 仅 x0/rax 被返回值覆盖）。注意 x0 在 exit 停是返回值
    ///   而非 arg0——getcwd 的返回缓冲在 arg0，必须靠 enter 停缓存
    ///   （[Tracer::exit_bufs]），readlinkat 的 buf 在 x2 才可直接读。
    ///
    /// # Safety
    /// `pid` 处于被跟踪停止态；iovec 指向本地栈上缓冲，长度即该架构
    /// `user_regs_struct`，与内核 NT_PRSTATUS 载荷一致。
    fn get_regs(&self, pid: libc::pid_t, entering: bool) -> ProotResult<Regs> {
        #[cfg(target_arch = "x86_64")]
        {
            // x86_64 布局（27×u64，与 libc::user_regs_struct 同构）。
            // 实测内核语义（sandbox x86_64 6.6 验证）：
            // - enter 停：rax = -ENOSYS，orig_rax = syscall 号，参数在
            //   rdi/rsi/rdx/r10/r8/r9；
            // - exit 停：rax = 返回值，orig_rax = 实际执行的号（阉割替身
            //   场景下已是 getpid，故 exit 阶段绝不能靠它反查原号——
            //   fake root 用 per-pid map 记录原号，见 NeuteredCall）。
            let mut raw: libc::user_regs_struct = unsafe { std::mem::zeroed() };
            let mut iov = libc::iovec {
                iov_base: &mut raw as *mut libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
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
            let mut r = Regs::default();
            // x8 恒为 syscall 号（enter/exit 都取 orig_rax，与 aarch64 x8 对齐）。
            r.x[8] = raw.orig_rax;
            if entering {
                r.x[0] = raw.rdi;
                r.x[1] = raw.rsi;
                r.x[2] = raw.rdx;
                r.x[3] = raw.r10;
                r.x[4] = raw.r8;
                r.x[5] = raw.r9;
            } else {
                r.x[0] = raw.rax; // 返回值
                r.x[1] = raw.rsi;
                r.x[2] = raw.rdx;
                r.x[3] = raw.r10;
                r.x[4] = raw.r8;
                r.x[5] = raw.r9;
            }
            Ok(r)
        }
        #[cfg(not(target_arch = "x86_64"))]
        {
            // aarch64 无 orig_rax/rax 混叠：x8 恒为号、x0..x5 恒为参数槽，
            // enter/exit 两停的寄存器布局同构，无需 entering 区分。
            let _ = entering;
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
    }

    /// SETREGSET 写回寄存器（改写后的参数/返回值）。
    ///
    /// `entering`：enter 停写 syscall 号 + 六个参数槽（当前仅 nr 替换用到）；
    /// exit 停只写返回值（fake root 的 set_ret(0) 语义）。
    ///
    /// # Safety
    /// 同 `get_regs`。x86_64 走读-改-写：只动自己负责的槽，
    /// 不碰 rip/rsp/flags 等非语义字段（防止意外损坏 tracee）。
    ///
    /// 把 syscall 号写入执行寄存器（x86_64 `rax` / aarch64 `x8`）。
    ///
    /// **消费场景（方案 I 的恢复半停）**：偷换的 mmap 执行完毕后，x86_64 的
    /// `rax` 是我们 mmap 的返回值，而重执行原 syscall 指令时内核从 `rax`
    /// 取号——必须先写回原号。aarch64 的 `x8` 不被返回值覆盖（仅 x0 被覆盖），
    /// `set_regs(entering=true)` 已一并恢复，此处为空操作。
    ///
    /// **为什么不能在非 syscall 停靠改 orig_rax 注入 syscall**（历史证伪）：
    /// exec 后 SIGTRAP / clone attach 停止时内核尚未读 orig_rax，tracee 恢复
    /// 后执行 `syscall` 指令用的是 rax（x8），且 glibc 包装会先
    /// `mov rax, NR` 覆盖写入值——实测注入 mmap 后 tracee 仍执行了 brk
    /// （读到 orig_nr=12）。syscall 停则无此问题：内核按 orig_rax 取号。
    fn set_exec_reg(&self, pid: libc::pid_t, nr: u64) -> ProotResult<()> {
        #[cfg(target_arch = "x86_64")]
        {
            let mut raw: libc::user_regs_struct = unsafe { std::mem::zeroed() };
            let mut giov = libc::iovec {
                iov_base: &mut raw as *mut libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_GETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
                    &mut giov as *mut libc::iovec,
                )
            };
            if rc < 0 {
                return Err(ProotError::Ptrace(format!(
                    "GETREGSET 失败: {}",
                    std::io::Error::last_os_error()
                )));
            }
            raw.rax = nr;
            let mut iov = libc::iovec {
                iov_base: &raw as *const libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_SETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
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
        #[cfg(not(target_arch = "x86_64"))]
        {
            // aarch64：x8 就是 nr 槽，set_regs(entering=true) 已写入，无需额外操作。
            let _ = (pid, nr);
            Ok(())
        }
    }

    /// 把 tracee 的 PC 回退 `delta` 字节（负值即向前回退）。
    ///
    /// 方案 I 的标准收尾：偷换的 syscall 在 exit 停执行完毕，PC 已越过
    /// `syscall`/`svc` 指令；恢复参数后回退 [`SYSCALL_INSN_LEN`] 即重新
    /// 指向该指令，放行后原 syscall 完整重执行一遍（enter/exit 双停照常
    /// 产生，相位由调用方置位）。
    ///
    /// x86_64 动 `rip`，aarch64 动 `pc`；均走 GETREGSET 读-改-写，只碰 PC
    /// 一个字段。
    fn adjust_pc(&self, pid: libc::pid_t, delta: i64) -> ProotResult<()> {
        #[cfg(target_arch = "x86_64")]
        {
            let mut raw: libc::user_regs_struct = unsafe { std::mem::zeroed() };
            let mut iov = libc::iovec {
                iov_base: &mut raw as *mut libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_GETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
                    &mut iov as *mut libc::iovec,
                )
            };
            if rc < 0 {
                return Err(ProotError::Ptrace(format!(
                    "GETREGSET 失败: {}",
                    std::io::Error::last_os_error()
                )));
            }
            raw.rip = (raw.rip as i64 + delta) as u64;
            let mut iov = libc::iovec {
                iov_base: &raw as *const libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_SETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
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
        #[cfg(not(target_arch = "x86_64"))]
        {
            // aarch64 无 orig_rax/rax 混叠：x8 恒为号、x0..x5 恒为参数槽，
            // enter/exit 两停的寄存器布局同构，无需 entering 区分。
            let _ = entering;
            let mut regs = Regs::default();
            let mut iov = libc::iovec {
                iov_base: &mut regs as *mut Regs as *mut libc::c_void,
                iov_len: std::mem::size_of::<Regs>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_GETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
                    &mut iov as *mut libc::iovec,
                )
            };
            if rc < 0 {
                return Err(ProotError::Ptrace(format!(
                    "GETREGSET 失败: {}",
                    std::io::Error::last_os_error()
                )));
            }
            regs.pc = (regs.pc as i64 + delta) as u64;
            let mut iov = libc::iovec {
                iov_base: &regs as *const Regs as *mut libc::c_void,
                iov_len: std::mem::size_of::<Regs>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_SETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
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

    fn set_regs(&self, pid: libc::pid_t, regs: &Regs, entering: bool) -> ProotResult<()> {
        #[cfg(target_arch = "x86_64")]
        {
            let mut raw: libc::user_regs_struct = unsafe { std::mem::zeroed() };
            let mut giov = libc::iovec {
                iov_base: &mut raw as *mut libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
            };
            let rc = unsafe {
                libc::ptrace(
                    libc::PTRACE_GETREGSET,
                    pid,
                    1usize as *mut libc::c_void,
                    &mut giov as *mut libc::iovec,
                )
            };
            if rc < 0 {
                return Err(ProotError::Ptrace(format!(
                    "GETREGSET 失败: {}",
                    std::io::Error::last_os_error()
                )));
            }
            if entering {
                // 内核在 enter 停按 orig_rax 取号执行（实测：改 orig_rax
                // 即完成 syscall 替换，rax=-ENOSYS 无需理会）。
                //
                // x[8] 恒持**平台原生号**（get_regs 从 orig_rax 原样装入；
                // Regs::set_nr 已把内部号 to_native_nr 转原生），故此处直接
                // 写回、不再套 to_native_nr——早年二次映射对已是原生号的值
                // 非恒等臂会错译：x86_64 原生 56（clone）被错译成 257
                // （openat）、79（getcwd）错译成 262（newfstatat），偷换
                // 恢复路径会静默把原 syscall 改号重放。
                raw.orig_rax = regs.x[8];
                raw.rdi = regs.x[0];
                raw.rsi = regs.x[1];
                raw.rdx = regs.x[2];
                raw.r10 = regs.x[3];
                raw.r8 = regs.x[4];
                raw.r9 = regs.x[5];
            } else {
                raw.rax = regs.x[0];
            }
            let mut iov = libc::iovec {
                iov_base: &raw as *const libc::user_regs_struct as *mut libc::c_void,
                iov_len: std::mem::size_of::<libc::user_regs_struct>(),
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
        #[cfg(not(target_arch = "x86_64"))]
        {
            // aarch64：regs 快照即完整 pt_regs（x8=号、x0..x5=参数/返回值），
            // 整块写回，无需 entering 区分槽位（x86_64 才需 orig_rax vs rax）。
            let _ = entering;
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
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proot::translate::TranslateRules;
    use std::path::Path;

    /// openat：翻译槽 1，dirfd 在槽 0。
    #[test]
    fn plan_openat() {
        let p = syscall_plan_internal(sysnr::OPENAT);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(p.args[1], None);
    }

    /// renameat2：双路径槽（1/3），各带 dirfd（0/2）——上游 v5.2.0 修复的覆盖点。
    #[test]
    fn plan_renameat2_two_paths() {
        let p = syscall_plan_internal(sysnr::RENAMEAT2);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(p.args[1], Some(PathArg { slot: 3, dirfd_slot: Some(2) }));
    }

    /// symlinkat：target（槽 0）无 dirfd，linkpath（槽 2）dirfd 在槽 1。
    #[test]
    fn plan_symlinkat() {
        let p = syscall_plan_internal(sysnr::SYMLINKAT);
        assert_eq!(p.args[0], Some(PathArg { slot: 0, dirfd_slot: None }));
        assert_eq!(p.args[1], Some(PathArg { slot: 2, dirfd_slot: Some(1) }));
    }

    /// stat/lstat（x86_64 老接口）：单路径槽 0，无 dirfd——musl stat()/
    /// lstat() 直发原生命，不拦则 busybox ls -l 全 ENOENT（M6 e2e 实证）。
    #[test]
    fn plan_stat_lstat_old_iface() {
        for nr in [sysnr::STAT, sysnr::LSTAT] {
            let p = syscall_plan_internal(nr);
            assert_eq!(p.op, Op::RewriteOnEnter);
            assert_eq!(p.args[0], Some(PathArg { slot: 0, dirfd_slot: None }));
            assert_eq!(p.args[1], None);
        }
    }

    /// fstat：fd 形无路径，必须直通（误拦会把 fd 小整数当指针读）。
    #[test]
    fn plan_fstat_passthrough() {
        assert_eq!(syscall_plan_internal(sysnr::FSTAT).op, Op::PassThrough);
    }

    /// x86_64 原生号 → 内部号：stat/lstat/fstat 老接口三兄弟（哨兵值
    /// 不落在 asm-generic 编号空间，防跨架构误判）。
    #[cfg(target_arch = "x86_64")]
    #[test]
    fn x86_64_stat_family_native_mapping() {
        assert_eq!(to_internal_nr(4), Some(sysnr::STAT));
        assert_eq!(to_internal_nr(6), Some(sysnr::LSTAT));
        assert_eq!(to_internal_nr(5), Some(sysnr::FSTAT));
        assert_eq!(to_native_nr(sysnr::STAT), 4);
        assert_eq!(to_native_nr(sysnr::LSTAT), 6);
        // 哨兵值必须远离两架构真实编号空间（防 aarch64 误判）。
        assert!(sysnr::STAT > 0x1000 && sysnr::LSTAT > 0x1000);
    }

    /// getcwd：仅 exit 反向还原。
    #[test]
    fn plan_getcwd_exit_only() {
        let p = syscall_plan_internal(sysnr::GETCWD);
        assert_eq!(p.op, Op::RestoreOnExit);
        assert_eq!(p.ret_buf_slot, Some(0));
    }

    /// readlinkat：enter 翻译 + exit 还原返回缓冲（上游 detranslate 场景）。
    #[test]
    fn plan_readlinkat_enter_and_exit() {
        let p = syscall_plan_internal(sysnr::READLINKAT);
        assert_eq!(p.op, Op::RewriteEnterRestoreExit);
        assert_eq!(p.ret_buf_slot, Some(2));
    }

    /// 未列入的 syscall 直通（上游"未拦截即直通"兜底语义）。
    /// 292 是 io_pgetevents——曾与 statx 编号混淆，此处钉死防回退。
    #[test]
    fn plan_unknown_passthrough() {
        assert_eq!(syscall_plan_internal(9999).op, Op::PassThrough);
        assert_eq!(syscall_plan_internal(292).op, Op::PassThrough); // io_pgetevents
    }

    /// M4.1 补齐：statx=291（glibc 2.28+ 的 stat 家族主路径）。
    #[test]
    fn plan_statx_291() {
        let p = syscall_plan_internal(sysnr::STATX);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(sysnr::STATX, 291);
    }

    /// M4.1 补齐：openat2 与 openat 同构（路径槽 1 + dirfd 槽 0）。
    #[test]
    fn plan_openat2() {
        let p = syscall_plan_internal(sysnr::OPENAT2);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
    }

    /// M4.1 补齐：mknodat（dpkg 装 /dev 节点常用）。
    #[test]
    fn plan_mknodat() {
        let p = syscall_plan_internal(sysnr::MKNODAT);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
    }

    /// M4.1 补齐：linkat 与 renameat 同为双路径双 dirfd（槽 1/3）。
    #[test]
    fn plan_linkat_two_paths() {
        let p = syscall_plan_internal(sysnr::LINKAT);
        assert_eq!(p.op, Op::RewriteOnEnter);
        assert_eq!(p.args[0], Some(PathArg { slot: 1, dirfd_slot: Some(0) }));
        assert_eq!(p.args[1], Some(PathArg { slot: 3, dirfd_slot: Some(2) }));
    }

    /// M4.1 补齐：readlink（无 dirfd 形）enter 翻译 + exit 还原（缓冲槽 1）。
    #[test]
    fn plan_readlink_no_dirfd() {
        let p = syscall_plan_internal(sysnr::READLINK);
        assert_eq!(p.op, Op::RewriteEnterRestoreExit);
        assert_eq!(p.args[0], Some(PathArg { slot: 0, dirfd_slot: None }));
        assert_eq!(p.ret_buf_slot, Some(1));
    }

    /// fake root：setuid 系 + chown 系 enter 阶段阉割。
    #[test]
    fn plan_fake_root_enter_family() {
        for nr in [
            sysnr::SETUID,
            sysnr::SETGID,
            sysnr::SETREUID,
            sysnr::SETREGID,
            sysnr::SETRESUID,
            sysnr::SETRESGID,
            sysnr::SETGROUPS,
            sysnr::FCHOWNAT,
            sysnr::FCHOWN,
        ] {
            assert_eq!(syscall_plan_internal(nr).op, Op::FakeRootEnter, "nr={}", nr);
        }
    }

    /// fake root：getuid 系 + getresuid/gid + setfsuid/gid + getgroups 仅 exit 伪装。
    #[test]
    fn plan_fake_root_exit_family() {
        for nr in [
            sysnr::GETUID,
            sysnr::GETEUID,
            sysnr::GETGID,
            sysnr::GETEGID,
            sysnr::GETRESUID,
            sysnr::GETRESGID,
            sysnr::SETFSUID,
            sysnr::SETFSGID,
            sysnr::GETGROUPS,
        ] {
            assert_eq!(syscall_plan_internal(nr).op, Op::FakeRootExit, "nr={}", nr);
        }
    }

    /// fake root 开启时：neuter_enter 把 nr 换成 getpid 并记录原号。
    #[test]
    fn neuter_enter_swaps_nr_and_records() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.set_fake_root(true);
        let t = Tracer::new(rules);
        let mut r = Regs::default();
        r.set_nr(sysnr::SETUID);
        r.x[0] = 0;
        assert!(t.neuter_enter(4242, &mut r));
        assert_eq!(r.nr(), to_native_nr(sysnr::GETPID));
        let guard = t.neutered.borrow();
        let rec = guard.get(&4242).expect("应记录");
        assert_eq!(rec.orig_nr, sysnr::SETUID); // 记录内部号
    }

    /// fake root 开启时：exit 阶段消费记录，返回 0 并清除。
    #[test]
    fn fake_root_exit_consumes_record() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.set_fake_root(true);
        let t = Tracer::new(rules);
        let mut r = Regs::default();
        r.set_nr(sysnr::FCHOWNAT);
        t.neuter_enter(77, &mut r);
        // exit 时 x8 已被内核换成 getpid（模拟）
        let mut r2 = r;
        r2.set_nr(sysnr::GETPID);
        r2.set_ret(12345); // getpid 的"真实"返回值
        assert!(t.apply_fake_root_exit(77, &mut r2));
        assert_eq!(r2.ret(), 0);
        assert!(t.neutered.borrow().is_empty(), "记录应被消费");
    }

    /// getresuid：enter 缓存三个出参指针，exit 返回 0。
    #[test]
    fn getresuid_caches_out_ptrs() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.set_fake_root(true);
        let t = Tracer::new(rules);
        let mut r = Regs::default();
        r.set_nr(sysnr::GETRESUID);
        r.x[0] = 0x1000;
        r.x[1] = 0x2000;
        r.x[2] = 0x3000;
        // enter 只记录、不改寄存器
        assert!(!t.neuter_enter(88, &mut r));
        let guard = t.neutered.borrow();
        let rec = guard.get(&88).expect("应记录");
        assert_eq!(rec.out_ptrs, [0x1000, 0x2000, 0x3000]);
    }

    /// exit-only 伪装：getuid 直接改返回值为 0，不进 map。
    #[test]
    fn fake_root_exit_getuid() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.set_fake_root(true);
        let t = Tracer::new(rules);
        let mut r = Regs::default();
        r.set_nr(sysnr::GETUID);
        r.set_ret(10123); // 真实 uid
        assert!(t.apply_fake_root_exit(99, &mut r));
        assert_eq!(r.ret(), 0);
        assert!(t.neutered.borrow().is_empty());
    }

    /// fake root 关闭时：全部直通，寄存器不动、map 不记。
    #[test]
    fn fake_root_off_passthrough() {
        let mut rules = TranslateRules::new(Path::new("/rf"));
        rules.set_fake_root(false);
        let t = Tracer::new(rules);
        let mut r = Regs::default();
        r.set_nr(sysnr::SETUID);
        assert!(!t.neuter_enter(55, &mut r));
        assert_eq!(r.nr(), to_native_nr(sysnr::SETUID));
        r.set_ret(4321);
        assert!(!t.apply_fake_root_exit(55, &mut r));
        assert_eq!(r.ret(), 4321);
    }

    /// aarch64 寄存器映射：nr=x8、参数 x0..x5、返回值 x0。
    #[test]
    fn regs_aarch64_mapping() {
        let mut r = Regs::default();
        r.set_nr(sysnr::OPENAT);
        r.x[0] = 100; // dirfd
        r.x[1] = 0x7f00; // pathname 指针
        r.x[2] = 0o200000; // flags: O_CLOEXEC|...
        assert_eq!(r.nr(), to_native_nr(sysnr::OPENAT));
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

    /// 平台适配表自洽：每个被拦截的内部号，经
    /// `to_native_nr → to_internal_nr` 往返必须还原（防止两表改漏一处，
    /// 进入"换了替身号但 exit 认不出"的静默错位）。
    #[test]
    fn platform_table_round_trip() {
        for internal in [
            sysnr::GETPID,
            sysnr::SETUID,
            sysnr::SETGID,
            sysnr::SETREUID,
            sysnr::SETREGID,
            sysnr::SETRESUID,
            sysnr::SETRESGID,
            sysnr::SETGROUPS,
            sysnr::FCHOWNAT,
            sysnr::FCHOWN,
            sysnr::GETUID,
            sysnr::GETEUID,
            sysnr::GETGID,
            sysnr::GETEGID,
            sysnr::GETRESUID,
            sysnr::GETRESGID,
            sysnr::SETFSUID,
            sysnr::SETFSGID,
            sysnr::GETGROUPS,
            sysnr::OPENAT,
            sysnr::STATX,
            sysnr::READLINKAT,
            sysnr::READLINK,
            sysnr::GETCWD,
            sysnr::EXECVE,
        ] {
            let native = to_native_nr(internal);
            assert_eq!(
                to_internal_nr(native),
                Some(internal),
                "往返失败: internal={} native={}",
                internal,
                native
            );
            // 原生号查计划必须命中与内部号查计划相同的分类。
            assert_eq!(
                syscall_plan(native).op,
                syscall_plan_internal(internal).op,
                "计划不一致: internal={} native={}",
                internal,
                native
            );
        }
    }

    /// x86_64 专属：未列入拦截表的原生命必须返回 None——
    /// 45 在 x86_64 是 recvfrom，若按原值透传会被误判成 aarch64 的
    /// truncate（走进路径翻译读 fd 当指针）。
    #[cfg(target_arch = "x86_64")]
    #[test]
    fn x86_64_unlisted_native_not_intercepted() {
        assert_eq!(to_internal_nr(45), None); // recvfrom
        assert_eq!(syscall_plan(45).op, Op::PassThrough);
        // 原值透传会把 45 当 truncate 拦——此处钉死不许回退。
        assert_ne!(syscall_plan(45).op, syscall_plan_internal(sysnr::TRUNCATE).op);
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

    /// exit 还原缓冲指针缓存（M5.1）：enter 停写 (slot, addr)，exit 停 arg0
    /// 槽已被返回值覆盖（原始 getcwd 返回**长度**而非指针，strace 实证
    /// `getcwd(...,4096) = 5`），不靠缓存则 read_child_cstr 拿长度当地址必
    /// 失败、还原静默失效。此处验证缓存契约：exit 停消费即删。
    #[test]
    fn exit_bufs_cached_addr_consumed() {
        let t = Tracer::new(TranslateRules::new(Path::new("/")));
        // 模拟 enter 停的缓存写入（handle_syscall_stop enter 分支）。
        t.exit_bufs.borrow_mut().insert(7, (0, 0xDEAD, None));
        // 模拟 exit 停寄存器：x0 = 返回值（长度），arg0 原指针已不可见。
        let mut regs = Regs::default();
        regs.set_ret(5);
        let plan = syscall_plan_internal(sysnr::GETCWD);
        assert_eq!(plan.ret_buf_slot, Some(0));
        // 假 pid：读进程内存必失败 → 静默返回，但缓存应先被消费。
        t.handle_exit_restore(7, &regs, &plan);
        assert!(
            !t.exit_bufs.borrow().contains_key(&7),
            "exit 停应消费 enter 写入的缓冲指针缓存"
        );
        // 无缓存直调（单测/异常路径）：走寄存器直取兜底，不 panic。
        t.handle_exit_restore(7, &regs, &plan);
    }

    /// 偷换快照（M5.2 方案 I）正确性：syscall 号换成 mmap + 六参数
    /// addr=NULL / length=SCRATCH_TOTAL / prot=RW / flags=PRIVATE|ANON /
    /// fd=-1 / offset=0。
    /// 任何一个错了，偷出来的 mmap 要么失败（-errno → 无 scratch），要么
    /// 映射错区（文件映射语义破坏子进程地址空间）。
    #[test]
    fn stolen_mmap_regs_six_args() {
        let mut saved = Regs::default();
        // 原 syscall 必须用**非 mmap** 的号：早年测试拿 MMAP 当原号，
        // "号不动"的断言把「只换参数不换号」的 EFAULT bug 牢牢焊死
        // （M6 e2e 实证：x86_64 上 open 配 mmap 六参 → open(NULL)
        // → EFAULT，musl loader 报 "Bad address" 退出）。
        saved.set_nr(sysnr::OPENAT);
        // 塞入一条"真实" mmap 的参数与无关寄存器，验证快照只动六个槽。
        saved.x[0] = 0x1234_5678; // 原 addr
        saved.x[1] = 0x2000; // 原 length
        saved.x[2] = 0x1; // 原 prot
        saved.x[3] = 0x02; // 原 flags
        saved.x[4] = 7; // 原 fd
        saved.x[5] = 0x1000; // 原 offset
        saved.x[6] = 0xAAAA; // 无关寄存器（syscall 不用 x6，必须原样保留）
        saved.sp = 0xBEEF;
        saved.pc = 0x8000;
        saved.pstate = 0x6000_0000;

        let stolen = Tracer::stolen_mmap_regs(&saved);
        assert_eq!(stolen.x[0], 0, "addr 必须为 NULL（内核自选基址）");
        assert_eq!(stolen.x[1], SCRATCH_TOTAL as u64);
        assert_eq!(stolen.x[2], 0x3, "PROT_READ|PROT_WRITE");
        assert_eq!(stolen.x[3], 0x22, "MAP_PRIVATE|MAP_ANONYMOUS");
        assert_eq!(stolen.x[4], u64::MAX, "fd = -1");
        assert_eq!(stolen.x[5], 0, "offset = 0");
        // 号必须换成 mmap（set_nr 已转平台号：x86_64=9 / aarch64=222）——
        // 与六参成套替换，缺一即 EFAULT（见 stolen_mmap_regs 文档）。
        assert_eq!(stolen.nr(), to_native_nr(sysnr::MMAP));
        assert_ne!(stolen.nr(), saved.nr(), "号不得保持原值");
        assert_eq!(stolen.pc, saved.pc);
        assert_eq!(stolen.x[6], 0xAAAA, "无关寄存器必须原样保留");
        assert_eq!(stolen.sp, saved.sp);
        assert_eq!(stolen.pstate, saved.pstate);
    }

    /// 环形 bump：区内单调推进；越界归零复用（旧引用已消亡的语义）。
    /// 边界值用刚好耗尽 SCRATCH_TOTAL 的组合钉死。
    #[test]
    fn scratch_region_ring_reuse() {
        let mut r = ScratchRegion {
            addr: 0x5000_0000,
            total: 100,
            used: 0,
        };
        assert_eq!(r.alloc(30), 0x5000_0000);
        assert_eq!(r.alloc(40), 0x5000_0000 + 30); // used=70，仍放得下
        // 再要 31 字节：70+31 > 100 → 归零复用
        assert_eq!(r.alloc(31), 0x5000_0000, "越界应环绕回基址");
        assert_eq!(r.used, 31);
        // 恰好耗尽（69+31=100，不越界，走 bump 分支）
        assert_eq!(r.alloc(69), 0x5000_0000 + 31);
        // 100+1 > 100 → 再环绕
        assert_eq!(r.alloc(1), 0x5000_0000);
    }

    /// 偷换重试上限语义常量（防止"失败→再偷→再失败"无限循环的数值假设）。
    /// 钉死具体值：改大拖慢无 scratch 场景，改小则偶发分配失败即降级。
    #[test]
    fn steal_attempt_cap_is_sane() {
        assert_eq!(STEAL_MAX_ATTEMPTS, 4);
    }

    /// syscall 指令宽度常量（PC 回退重执行的依据）：x86_64=2、其余=4。
    #[cfg(target_arch = "x86_64")]
    #[test]
    fn syscall_insn_len_x86_64() {
        assert_eq!(SYSCALL_INSN_LEN, 2); // `syscall` = 0f 05
    }
}

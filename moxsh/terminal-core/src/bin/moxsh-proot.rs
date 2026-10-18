//! `moxsh-proot` 独立可执行文件入口。
//!
//! 让**原版 Termux 用户**不装 moxsh App 也能直接用上自研 PRoot 引擎
//! （Rust ptrace 路径翻译，非上游 C 版 proot 的封装）。一键安装脚本
//! 从 Release 下载本文件（aarch64-linux-android）并放入
//! `$PREFIX/bin/moxsh-proot`，之后即可 `moxsh-proot -r ~/ubuntu -- bash`。
//!
//! 参数解析与真执行逻辑在 [`moxshcore::proot::cli::run_native`]（可单测），
//! 本文件只做进程退出码转发，保持薄壳。

use std::env;
use std::process;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let code = moxshcore::proot::cli::run_native(&args);
    process::exit(code);
}

/// Android bionic 的 `getrandom()` 包装函数直到 **API 28**（Android 9）才加入
/// libc.so；而 Rust std 的 `HashMap` RandomState 初始化走 `libc::getrandom`，
/// 以 API 24（Termux 支持的最低版本）为基准链接会报
/// `undefined symbol: getrandom`。
///
/// 这里提供与 bionic 语义一致的实现：直发 `SYS_getrandom` 系统调用
/// （内核 3.17+ 即有，Android 全版本可用）。符号定义在可执行文件内，
/// 链接期静态解析——API 28+ 设备上也不会与 libc.so 的同名符号冲突
/// （可执行文件定义优先，且两者行为相同）。
///
/// 只加在独立二进制里，不污染 `libmoxshcore.so`（App 侧 minSdk 28，无需此垫片）。
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn getrandom(
    buf: *mut libc::c_void,
    buflen: usize,
    flags: libc::c_uint,
) -> libc::ssize_t {
    // 安全：buf/buflen 由调用方（std 的 RandomState）保证是合法可写缓冲；
    // flags 原样透传（std 传 0）。SYS_getrandom 在全部 Android 内核可用。
    unsafe { libc::syscall(libc::SYS_getrandom, buf, buflen, flags) as libc::ssize_t }
}

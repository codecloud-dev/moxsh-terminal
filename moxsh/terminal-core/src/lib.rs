//! moxsh 终端内核（Rust 全包，clean-room）。
//!
//! 本 crate 是 C-ABI 边界：对外暴露 `moxsh_*` 函数供 C++ JNI 桥接层与
//! 其他 Rust 模块调用。所有内存安全敏感逻辑均在此用 Rust 实现，热点路径
//! 调用 NEON 汇编（见 [`arch`]）。C/C++ 只保留最小 JNI 桥接。

#![allow(clippy::missing_safety_doc)]
// unsafe 审计强化：unsafe fn 内的每一个 unsafe 操作必须显式包裹 unsafe 块，
// 使"哪些行在做不安全操作"一目了然（FFI 边界可读性/可审计性）。
#![deny(unsafe_op_in_unsafe_fn)]

pub mod arch;
pub mod compat;
pub mod crypto;
pub mod ipc;
pub mod moxpkg;
pub mod parser;
pub mod pkgmgr;
pub mod proot;
pub mod pty;
pub mod renderer;
pub mod ring;
pub mod screen;
pub mod session;

use std::os::raw::{c_char, c_int};

use session::TerminalSession;

// ============ M4：PRoot 容器引擎 C-ABI（D12） ============
//
// 错误码统一约定（ProotError::to_code 的 C 边界）：
//   0 成功 | -1 参数非法 | -2 rootfs 不存在 | -3 发行版未知 | -4 已安装
//   -5 未安装 | -6 需要下载缓存 | -7 SHA-256 校验失败 | -8 解压失败
//   -9 IO 错误 | -10 缓冲区不足 | -11 ptrace 失败
//
// 字符串返回统一采用"调用方缓冲 + 容量"模式（与 moxsh_copy_cells 一致）：
// 返回正数=写入长度（不含 NUL）；0=缓冲区不足（Kotlin 侧应扩容重试）；
// 负数=错误码。避免堆指针跨界释放，Kotlin 无需配对 free。
// 注：安装为同步阻塞调用（下载已在 Kotlin 层完成、解压阶段不可中断），
// 进度条按阶段估算；M6 真机联调如需细粒度回调，经 bridge.cpp 传函数指针。

/// FFI panic 屏障（P3）：`extern "C"` 里 unwind 属未定义行为（Rust 1.81+ 会 abort，
/// 表现为 Android 端整个 App 进程闪退）。所有重逻辑入口统一经 [ffi_guard] 执行：
/// 内部 panic 被捕获并折叠为约定错误码 `-99`（MOXSH_ERR_PANIC），App 存活、
/// Kotlin 侧可提示重试。只吞 panic 不吞错误：正常错误仍走各函数原有错误码。
///
/// 错误码约定补遗（与模块头注释的错误码表并列）：`-99 = 内核内部 panic（不应出现，
/// 出现请附 logcat 上报）`。
pub const MOXSH_ERR_PANIC: c_int = -99;

fn ffi_guard<F: FnOnce() -> c_int + std::panic::UnwindSafe>(f: F) -> c_int {
    match std::panic::catch_unwind(f) {
        Ok(code) => code,
        Err(_) => MOXSH_ERR_PANIC,
    }
}

/// 读取 C 字符串为 Rust &str（非法 UTF-8 / null 返回 None）。
///
/// # Safety
/// `p` 必须指向合法以 NUL 结尾的字符串，或为 null。
unsafe fn read_opt_cstr(p: *const c_char) -> Option<String> {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if p.is_null() {
        return None;
    }
    std::ffi::CStr::from_ptr(p)
        .to_str()
        .ok()
        .map(|s| s.to_string())
}
}

/// 把字符串写入调用方缓冲（截断防护）。
///
/// # Safety
/// `out` 指向至少 `cap` 字节可写内存（可 null，此时仅探测所需长度）。
unsafe fn write_cstr_buf(out: *mut c_char, cap: usize, s: &str) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let need = s.len() + 1; // 含 NUL
    if out.is_null() || cap < need {
        return 0; // 0 = 缓冲不足，Kotlin 扩容重试
    }
    let slice = std::slice::from_raw_parts_mut(out as *mut u8, cap);
    slice[..s.len()].copy_from_slice(s.as_bytes());
    slice[s.len()] = 0;
    need as c_int
}
}

/// 安装发行版：`id` 为预置 id（ubuntu/debian/kali/alpine）或自定义容器名。
/// `cache_tar` 为已下载归档路径（可 null：内部 curl 兜底，Android 建议先下载）。
/// 需要外部下载时返回 -6（ProotError::DownloadRequired）。
///
/// # Safety
/// `id`/`moxsh_root` 必须指向合法 NUL 结尾 UTF-8 字符串；`cache_tar` 可为 null。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_install(
    id: *const c_char,
    moxsh_root: *const c_char,
    cache_tar: *const c_char,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    // P3 panic 屏障：安装链路（下载/解压/落盘）体量大，内部异常折叠为 -99。
    ffi_guard(move ||
    unsafe {
    let (Some(id), Some(root)) = (read_opt_cstr(id), read_opt_cstr(moxsh_root)) else {
        return -1;
    };
    let mgr = proot::distro::DistroManager::new(std::path::Path::new(&root));
    let mut progress: Box<dyn FnMut(&str, u64, u64)> = Box::new(|_, _, _| {});
    let r = match proot::distro::DistroManager::find_spec(&id) {
        Ok(spec) => {
            // 先把 String 绑定到局部，再取引用（避免临时值悬垂）。
            let cache_str = read_opt_cstr(cache_tar);
            let cache = cache_str.as_deref().map(|t| std::path::Path::new(t));
            mgr.install(&spec, cache, &mut progress)
        }
        // 非预置 id：按自定义 rootfs 处理（cache_tar 必填）。
        Err(_) => match read_opt_cstr(cache_tar) {
            Some(tar) => mgr.install_custom(&id, std::path::Path::new(&tar), None, &mut progress),
            None => return proot::ProotError::DownloadRequired(id).to_code(),
        },
    };
    r.map(|_| 0).unwrap_or_else(|e| e.to_code())
    })
}
}

/// 删除发行版。
///
/// # Safety
/// `id`/`moxsh_root` 必须指向合法 NUL 结尾 UTF-8 字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_remove(id: *const c_char, moxsh_root: *const c_char) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let (Some(id), Some(root)) = (read_opt_cstr(id), read_opt_cstr(moxsh_root)) else {
        return -1;
    };
    proot::distro::DistroManager::new(std::path::Path::new(&root))
        .remove(&id)
        .map(|_| 0)
        .unwrap_or_else(|e| e.to_code())
}
}

/// 生成发行版登录命令行（proot 完整调用）写入 `out_buf`（容量 `cap`）。
/// 返回所需长度（含 NUL，>0 成功）/ 0 缓冲不足 / 负错误码。
///
/// # Safety
/// `id`/`moxsh_root` 指向合法字符串；`out_buf` 指向至少 `cap` 字节可写内存。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_login(
    id: *const c_char,
    moxsh_root: *const c_char,
    out_buf: *mut c_char,
    cap: usize,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let (Some(id), Some(root)) = (read_opt_cstr(id), read_opt_cstr(moxsh_root)) else {
        return -1;
    };
    match proot::distro::DistroManager::new(std::path::Path::new(&root)).login_cmd(&id) {
        Ok(cmd) => write_cstr_buf(out_buf, cap, &cmd),
        Err(e) => e.to_code(),
    }
}
}

/// 列出已安装发行版，写入 `out_buf`（行分隔协议：每行 `id|initialized|rootfs`）。
///
/// # Safety
/// `moxsh_root` 指向合法字符串；`out_buf` 指向至少 `cap` 字节可写内存。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_list_installed(
    moxsh_root: *const c_char,
    out_buf: *mut c_char,
    cap: usize,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let Some(root) = read_opt_cstr(moxsh_root) else {
        return -1;
    };
    let list = match proot::distro::DistroManager::new(std::path::Path::new(&root)).list_installed()
    {
        Ok(l) => l,
        Err(e) => return e.to_code(),
    };
    let text: String = list
        .iter()
        .map(|d| format!("{}|{}|{}\n", d.id, d.initialized as u8, d.rootfs.display()))
        .collect();
    write_cstr_buf(out_buf, cap, text.trim_end_matches('\n'))
}
}

/// 备份发行版 rootfs 到 `out_tar`（tar.gz）。
///
/// # Safety
/// `id`/`moxsh_root`/`out_tar` 必须指向合法 NUL 结尾 UTF-8 字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_backup(
    id: *const c_char,
    moxsh_root: *const c_char,
    out_tar: *const c_char,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let (Some(id), Some(root), Some(out)) = (
        read_opt_cstr(id),
        read_opt_cstr(moxsh_root),
        read_opt_cstr(out_tar),
    ) else {
        return -1;
    };
    proot::distro::DistroManager::new(std::path::Path::new(&root))
        .backup(&id, std::path::Path::new(&out))
        .map(|_| 0)
        .unwrap_or_else(|e| e.to_code())
}
}

/// 从备份 tar.gz 恢复发行版（覆盖已有 rootfs）。
///
/// # Safety
/// `id`/`moxsh_root`/`tar_path` 必须指向合法 NUL 结尾 UTF-8 字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_proot_restore(
    id: *const c_char,
    moxsh_root: *const c_char,
    tar_path: *const c_char,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    // P3 panic 屏障：rootfs 恢复 = 大 tar 解压 + 覆盖写，异常折叠为 -99。
    ffi_guard(move ||
    unsafe {
    let (Some(id), Some(root), Some(tar)) = (
        read_opt_cstr(id),
        read_opt_cstr(moxsh_root),
        read_opt_cstr(tar_path),
    ) else {
        return -1;
    };
    proot::distro::DistroManager::new(std::path::Path::new(&root))
        .restore(&id, std::path::Path::new(&tar))
        .map(|_| 0)
        .unwrap_or_else(|e| e.to_code())
    })
}
}

/// 路径翻译自检：运行内置用例，返回通过数。
/// 等于 `proot::TRANSLATE_SELFTEST_TOTAL`（14）即全部通过，供 Kotlin 启动自检。
#[no_mangle]
pub extern "C" fn moxsh_proot_translate_selftest() -> c_int {
    proot::translate_selftest() as c_int
}

/// 打开一个 PTY 会话并执行 `cmd`（UTF-8，以 NUL 结尾）。
///
/// 返回堆分配的 [`TerminalSession`] 裸指针；调用方负责用 [`moxsh_close`] 释放。
/// 失败返回 null。
///
/// # Safety
/// `cmd` 必须指向合法以 NUL 结尾的 UTF-8 字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_open_pty(
    cmd: *const c_char,
    cols: c_int,
    rows: c_int,
) -> *mut TerminalSession {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    // P1 修复：cmd 判空（null 直接 CStr::from_ptr 是 UB）；尺寸钳制——
    // cols/rows 为 0 会引发 Screen 内部 rows-1 下溢/除零，负数 as u32 会爆分配。
    if cmd.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(cmd) = std::ffi::CStr::from_ptr(cmd).to_str() else {
        return std::ptr::null_mut();
    };
    let cols = cols.clamp(2, 500) as u32;
    let rows = rows.clamp(1, 200) as u32;
    match TerminalSession::spawn(cmd, cols, rows) {
        Ok(s) => Box::into_raw(Box::new(s)),
        Err(_) => std::ptr::null_mut(),
    }
}
}

/// 从 PTY 读取至多 `len` 字节到 `buf`，并喂给 VT 解析器更新屏幕状态。
/// 返回读取字节数（0=EOF，-1=错误）。
///
/// # Safety
/// `sess` 必须来自 [`moxsh_open_pty`] 且未被释放；`buf` 指向至少 `len` 字节。
#[no_mangle]
pub unsafe extern "C" fn moxsh_pump(
    sess: *mut TerminalSession,
    buf: *mut u8,
    len: usize,
) -> isize {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() || buf.is_null() {
        return -1;
    }
    let slice = std::slice::from_raw_parts_mut(buf, len);
    match (*sess).pump_into(slice) {
        Ok(n) => n as isize,
        Err(_) => -1,
    }
}
}

/// 向 PTY 写入 `len` 字节（通常是从键盘/IME 来的输入或控制序列）。
///
/// # Safety
/// `sess` 必须有效；`buf` 指向至少 `len` 字节。
#[no_mangle]
pub unsafe extern "C" fn moxsh_write(
    sess: *mut TerminalSession,
    buf: *const u8,
    len: usize,
) -> isize {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() || buf.is_null() {
        return -1;
    }
    let slice = std::slice::from_raw_parts(buf, len);
    match (*sess).write(slice) {
        Ok(n) => n as isize,
        Err(_) => -1,
    }
}
}

/// 更新窗口尺寸（SIGWINCH 由内部处理）。
///
/// # Safety
/// `sess` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn moxsh_resize(
    sess: *mut TerminalSession,
    cols: c_int,
    rows: c_int,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() {
        return -1;
    }
    match (*sess).resize(cols as u32, rows as u32) {
        Ok(()) => 0,
        Err(_) => -1,
    }
}
}

/// 释放会话（关闭 PTY、回收子进程、释放缓冲）。
///
/// # Safety
/// `sess` 必须来自 [`moxsh_open_pty`] 且未被释放；调用后指针失效。
#[no_mangle]
pub unsafe extern "C" fn moxsh_close(sess: *mut TerminalSession) {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if !sess.is_null() {
        drop(Box::from_raw(sess));
    }
}
}

/// 返回屏幕可见行数。
///
/// # Safety
/// `sess` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn moxsh_screen_rows(sess: *mut TerminalSession) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() {
        return 0;
    }
    (*sess).rows() as c_int
}
}

/// 返回屏幕列数。
///
/// # Safety
/// `sess` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn moxsh_screen_cols(sess: *mut TerminalSession) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() {
        return 0;
    }
    (*sess).cols() as c_int
}
}

/// 返回总行数（可见 + 历史回滚）。
///
/// # Safety
/// `sess` 必须有效。
#[no_mangle]
pub unsafe extern "C" fn moxsh_total_rows(sess: *mut TerminalSession) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() {
        return 0;
    }
    (*sess).total_rows() as c_int
}
}

/// 把绝对行 [start_row, start_row+count) 的单元格复制到 `buf`
/// （每行 cols*16 字节，小端 Cell 布局）。返回复制的行数，失败返回 -1。
///
/// # Safety
/// `sess` 必须有效；`buf` 指向至少 `buflen` 字节。
#[no_mangle]
pub unsafe extern "C" fn moxsh_copy_cells(
    sess: *mut TerminalSession,
    start_row: c_int,
    count: c_int,
    buf: *mut u8,
    buflen: usize,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if sess.is_null() || buf.is_null() {
        return -1;
    }
    let slice = std::slice::from_raw_parts_mut(buf, buflen);
    (*sess).copy_cells(start_row as usize, count as usize, slice);
    // 返回实际能覆盖的行数（用于 Kotlin 端边界判断）
    let stride = (*sess).cols() * 16;
    ((buflen / stride) as c_int).min(count)
}
}

// ============ M4：.mox 包 C-ABI（D15，见 moxpkg.rs 模块头规范） ============
//
// 错误码（moxpkg::MoxError::to_code）：
//   0 成功 | -1 参数非法 | -2 包不存在 | -3 manifest 坏 | -4 NotAMox
//   -5 验签失败 | -8 解压失败 | -9 IO 错误
//
// 句柄模式（照 moxsh_open_pty 的 Box::into_raw 约定）：
//   moxsh_mox_open 产出 opaque *mut MoxPackage，后续 verify/extract/list/manifest
//   复用该句柄（open 时已缓存条目表与 manifest 字节，重复调用零重扫），
//   用完必须 moxsh_mox_close 释放，否则泄漏。句柄无效一律返回 -1 / 忽略。

/// 打开 .mox 包并把句柄写入 `out_handle`。成功返回 0，失败返回负错误码。
///
/// # Safety
/// `path` 必须指向合法 NUL 结尾 UTF-8 字符串；`out_handle` 指向可写指针槽位。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_open(
    path: *const c_char,
    out_handle: *mut *mut moxpkg::MoxPackage,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    let Some(path) = read_opt_cstr(path) else {
        return -1;
    };
    if out_handle.is_null() {
        return -1;
    }
    match moxpkg::MoxPackage::open(std::path::Path::new(&path)) {
        Ok(pkg) => {
            *out_handle = Box::into_raw(Box::new(pkg));
            0
        }
        Err(e) => e.to_code(),
    }
}
}

/// 验签（HMAC-SHA256(secret, manifest 原始字节)，复用 crypto.rs）。
/// 返回 0=通过 / -5=签名不符 / 负错误码（-4 非 .mox 等）。
///
/// # Safety
/// `handle` 必须来自 [`moxsh_mox_open`] 且未释放；`secret` 指向合法字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_verify(
    handle: *mut moxpkg::MoxPackage,
    secret: *const c_char,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if handle.is_null() {
        return -1;
    }
    let Some(secret) = read_opt_cstr(secret) else {
        return -1;
    };
    match (*handle).verify(secret.as_bytes()) {
        Ok(true) => 0,
        Ok(false) => -5,
        Err(e) => e.to_code(),
    }
}
}

/// 解包到 `out_dir`（manifest.json / signature / payload/**，白名单外条目跳过）。
///
/// # Safety
/// `handle` 有效；`out_dir` 指向合法 NUL 结尾 UTF-8 字符串。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_extract(
    handle: *mut moxpkg::MoxPackage,
    out_dir: *const c_char,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    // P3 panic 屏障：解包 = 不可信第三方 tar 解压，异常折叠为 -99。
    if handle.is_null() {
        return -1;
    }
    let handle_ref = &*handle;
    let Some(out_dir) = read_opt_cstr(out_dir) else {
        return -1;
    };
    ffi_guard(move ||
        handle_ref
            .extract_to(std::path::Path::new(&out_dir))
            .map(|_| 0)
            .unwrap_or_else(|e| e.to_code()))
}
}

/// 列出包内条目到 `out_buf`（行分隔协议：每行 `name|size_bytes`）。
/// 返回所需长度（含 NUL）/ 0 缓冲不足 / 负错误码（照 moxsh_proot_list_installed 模式）。
///
/// # Safety
/// `handle` 有效；`out_buf` 指向至少 `cap` 字节可写内存（可 null 探测长度）。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_list(
    handle: *mut moxpkg::MoxPackage,
    out_buf: *mut c_char,
    cap: usize,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if handle.is_null() {
        return -1;
    }
    let text: String = (*handle)
        .list_entries()
        .iter()
        .map(|e| format!("{}|{}\n", e.name, e.size))
        .collect();
    write_cstr_buf(out_buf, cap, text.trim_end_matches('\n'))
}
}

/// 把 manifest.json 原文写入 `out_buf`（Kotlin 侧自行 JSON 解析，平台自带 org.json）。
///
/// # Safety
/// `handle` 有效；`out_buf` 指向至少 `cap` 字节可写内存（可 null 探测长度）。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_manifest(
    handle: *mut moxpkg::MoxPackage,
    out_buf: *mut c_char,
    cap: usize,
) -> c_int {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if handle.is_null() {
        return -1;
    }
    // open 时已保证 manifest 存在（缺失在 open 即报 NotAMox），此处 unwrap 安全；
    // 防御起见仍走 map_or 兜底空串。
    let json = (*handle)
        .manifest_json_bytes()
        .map_or_else(String::new, |b| String::from_utf8_lossy(&b).into_owned());
    write_cstr_buf(out_buf, cap, &json)
}
}

/// 释放句柄（照 moxsh_close 模式；调用后指针失效，重复释放为未定义行为）。
///
/// # Safety
/// `handle` 必须来自 [`moxsh_mox_open`] 且未被释放过。
#[no_mangle]
pub unsafe extern "C" fn moxsh_mox_close(handle: *mut moxpkg::MoxPackage) {
    // SAFETY：FFI thin wrapper——契约见函数 # Safety 文档；整体即不安全上下文。
    unsafe {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}
}

#[cfg(test)]
mod tests {
    use super::proot;
    use std::os::raw::c_int;

    /// M5：translate 自检——纯路径逻辑（不触碰文件系统/网络），随测试跑全量，
    /// 断言通过数等于内置总数（与 so 内 `moxsh_proot_translate_selftest` 同源）。
    #[test]
    fn translate_selftest_all_pass() {
        assert_eq!(proot::translate_selftest(), proot::TRANSLATE_SELFTEST_TOTAL);
    }

    /// M5：C-ABI 自检入口返回值断言（14 以内 usize→c_int 无损）。
    /// 注：无 `mox_selftest` 对应函数，跳过该半边。
    #[test]
    fn cabi_translate_selftest_code_matches() {
        let code = super::moxsh_proot_translate_selftest();
        assert_eq!(code, proot::TRANSLATE_SELFTEST_TOTAL as c_int);
    }
}

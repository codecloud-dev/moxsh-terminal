//! PTY 管理（Rust 自研，clean-room）。
//!
//! 使用 `posix_openpt` + `grantpt`/`unlockpt` + `ptsname` 打开伪终端主从对；
//! `fork` 后子进程 `setsid` + `TIOCSCTTY` + `dup2` 重定向 0/1/2，再 `execve`
//! 目标程序。父进程持有 master fd 与子进程 pid，所有资源在 [`Drop`] 回收。

use std::ffi::{CStr, CString};
use std::io;
use std::os::unix::io::RawFd;

use libc::{c_char, c_int, c_void, pid_t, winsize, TIOCSWINSZ};

pub struct Pty {
    pub master_fd: RawFd,
    pub pid: pid_t,
}

extern "C" {
    static mut environ: *mut *mut c_char;
}

impl Pty {
    /// 打开 PTY 并以 `cols x rows` 窗口执行命令 `cmd`。
    ///
    /// `cmd` 按空白拆分为 argv（兼容 `sh -c "..."` 与 `-l` 等）。
    pub fn open(cmd: &str, cols: u32, rows: u32) -> io::Result<Pty> {
        let master = unsafe { libc::posix_openpt(libc::O_RDWR | libc::O_NOCTTY) };
        if master < 0 {
            return Err(io::Error::last_os_error());
        }
        if unsafe { libc::grantpt(master) } < 0 || unsafe { libc::unlockpt(master) } < 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master) };
            return Err(e);
        }
        let slave_path = unsafe { libc::ptsname(master) };
        if slave_path.is_null() {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master) };
            return Err(e);
        }
        let slave_cstr = unsafe { CStr::from_ptr(slave_path) };

        let pid = unsafe { libc::fork() };
        if pid < 0 {
            let e = io::Error::last_os_error();
            unsafe { libc::close(master) };
            return Err(e);
        }

        if pid == 0 {
            // ===== 子进程 =====
            unsafe {
                libc::setsid();
                let slave_fd = libc::open(slave_cstr.as_ptr(), libc::O_RDWR);
                if slave_fd < 0 {
                    libc::_exit(1);
                }
                // 把从设备设为控制终端（TIOCSCTTY 在不同平台上是 c_int 或 c_ulong，用 as _ 按目标平台推断）
                if libc::ioctl(slave_fd, libc::TIOCSCTTY as _, 0) < 0 {
                    libc::_exit(1);
                }
                // 重定向标准流
                for i in 0..3 {
                    libc::dup2(slave_fd, i);
                }
                if slave_fd > 2 {
                    libc::close(slave_fd);
                }
                // 设置窗口尺寸
                let ws = winsize {
                    ws_row: rows as u16,
                    ws_col: cols as u16,
                    ws_xpixel: 0,
                    ws_ypixel: 0,
                };
                libc::ioctl(libc::STDIN_FILENO, TIOCSWINSZ, &ws as *const winsize);

                // 构造 argv
                let argv: Vec<CString> = cmd
                    .split_whitespace()
                    .filter_map(|s| CString::new(s).ok())
                    .collect();
                if argv.is_empty() {
                    libc::_exit(127);
                }
                let mut arg_ptrs: Vec<*const c_char> =
                    argv.iter().map(|s| s.as_ptr() as *const c_char).collect();
                arg_ptrs.push(std::ptr::null());

                // 设置 TERM
                let term = CString::new("TERM=xterm-256color").unwrap();
                // 追加到 environ（复制到新的 null 结尾数组）
                let env = build_environ(&term);
                libc::execve(
                    arg_ptrs[0],
                    arg_ptrs.as_ptr(),
                    env.as_ptr() as *const *const c_char,
                );
                libc::_exit(127);
            }
        }

        // ===== 父进程 =====
        Ok(Pty {
            master_fd: master,
            pid,
        })
    }

    pub fn resize(&self, cols: u32, rows: u32) -> io::Result<()> {
        let ws = winsize {
            ws_row: rows as u16,
            ws_col: cols as u16,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let rc = unsafe { libc::ioctl(self.master_fd, TIOCSWINSZ, &ws as *const winsize) };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        // 通知子进程 SIGWINCH
        unsafe {
            libc::kill(self.pid, libc::SIGWINCH);
        }
        Ok(())
    }

    pub fn read(&self, buf: &mut [u8]) -> io::Result<usize> {
        let n = unsafe {
            libc::read(
                self.master_fd,
                buf.as_mut_ptr() as *mut c_void,
                buf.len(),
            )
        };
        if n < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(n as usize)
    }

    pub fn write(&self, buf: &[u8]) -> io::Result<usize> {
        let mut written = 0;
        while written < buf.len() {
            let n = unsafe {
                libc::write(
                    self.master_fd,
                    buf[written..].as_ptr() as *const c_void,
                    buf.len() - written,
                )
            };
            if n < 0 {
                let e = io::Error::last_os_error();
                if e.kind() == io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(e);
            }
            written += n as usize;
        }
        Ok(written)
    }
}

/// 在继承的 environ 基础上追加 `extra`（形如 `KEY=VALUE`），返回 null 结尾数组。
unsafe fn build_environ(extra: &CString) -> Vec<*const c_char> {
    // SAFETY：遍历 C 运行时的全局 environ 表（只读），直到 NUL 终止项；
    // 调用方契约见函数 # Safety 说明（进程环境在单线程启动期访问）。
    unsafe {
        let mut vec: Vec<*const c_char> = Vec::new();
        let mut p = environ;
        while !(*p).is_null() {
            vec.push(*p);
            p = p.add(1);
        }
        vec.push(extra.as_ptr());
        vec.push(std::ptr::null());
        vec
    }
}

impl Drop for Pty {
    fn drop(&mut self) {
        unsafe {
            if self.master_fd >= 0 {
                libc::close(self.master_fd);
            }
            if self.pid > 0 {
                libc::kill(self.pid, libc::SIGTERM);
                // P1 修复：绝不在 Drop 里无限阻塞。
                // waitpid(..., 0) 在子进程忽略 SIGTERM（或卡在不可中断态）时会永久挂起，
                // 导致会话销毁卡死（UI ANR 风险）。改为 WNOHANG 轮询 + 超时升级 SIGKILL。
                let mut status: c_int = 0;
                let deadline = std::time::Instant::now() + std::time::Duration::from_millis(500);
                loop {
                    // 0 = 仍在运行；pid = 已收割；-1 = 错误（如 ECHILD 已被收割）——均不等待
                    let r = libc::waitpid(self.pid, &mut status as *mut c_int, libc::WNOHANG);
                    if r != 0 {
                        break;
                    }
                    if std::time::Instant::now() >= deadline {
                        // 优雅期结束，升级强杀。SIGKILL 后用 WNOHANG 短轮询收割；
                        // 极端不可中断态下放弃收割（僵尸由内核在进程退出时回收，优于挂死 UI）。
                        libc::kill(self.pid, libc::SIGKILL);
                        let hard_deadline =
                            std::time::Instant::now() + std::time::Duration::from_millis(100);
                        while std::time::Instant::now() < hard_deadline {
                            if libc::waitpid(self.pid, &mut status as *mut c_int, libc::WNOHANG) != 0 {
                                break;
                            }
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        break;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(20));
                }
            }
        }
    }
}

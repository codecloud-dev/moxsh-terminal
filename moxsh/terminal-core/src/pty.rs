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
        // P1 修复（配套 destroySession UAF）：master 设非阻塞。
        // 阻塞读会让 Kotlin 泵协程永久卡在 native pump（cancel 无法中断），
        // close 后即 use-after-free。非阻塞后 pump 以 WouldBlock 即时返回，
        // 泵循环每轮都有挂起点，cancel 可及时生效。
        // 仅影响 master 的 open file description；子进程的 slave 不受影响。
        {
            let flags = unsafe { libc::fcntl(master, libc::F_GETFL) };
            if flags < 0 || unsafe { libc::fcntl(master, libc::F_SETFL, flags | libc::O_NONBLOCK) } < 0 {
                let e = io::Error::last_os_error();
                unsafe { libc::close(master) };
                return Err(e);
            }
        }
        // glibc/bionic 的 ptsname() 返回静态缓冲区指针，**非线程安全**：
        // 并发 open 多个会话时（App 的 IO 协程完全可能），两个线程会拿到互相
        // 踩踏的 slave 路径，fork 出的子进程打开/抢占对方的从设备后 _exit(1)。
        // 改用可重入的 ptsname_r，从根上消除竞态。
        let mut slave_buf = [0u8; 128];
        let prc = unsafe {
            libc::ptsname_r(master, slave_buf.as_mut_ptr() as *mut c_char, slave_buf.len())
        };
        if prc != 0 {
            let e = io::Error::from_raw_os_error(prc);
            unsafe { libc::close(master) };
            return Err(e);
        }
        let slave_cstr = unsafe { CStr::from_ptr(slave_buf.as_ptr() as *const c_char) };

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
                    env.as_ptr(),
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
            let e = io::Error::last_os_error();
            // Linux PTY 语义：子进程退出（slave 全部关闭）后读 master 返回 EIO，
            // 而不是 0 字节 EOF。在 read 这一层把它折叠为 Ok(0)（干净 EOF），
            // pump/pump_into/C-ABI 三层因此统一"会话正常结束"语义，
            // 上层用 poll_exit / exitStatus 区分退出码即可。
            if e.raw_os_error() == Some(libc::EIO) {
                return Ok(0);
            }
            return Err(e);
        }
        Ok(n as usize)
    }

    /// 全量写入 `buf`。master 是 O_NONBLOCK（见 [`Pty::open`] 的 P1 注释），
    /// 当 PTY 输入缓冲写满（子进程未及时消费，典型场景：粘贴大段文本、vim 流控）
    /// 会返回 EAGAIN——这里用 poll(POLLOUT) 等待可写后重试，总超时 3s，
    /// 超时后报错（防泵线程永久卡死），不再像旧实现那样把 EAGAIN 直接当错误丢弃。
    pub fn write(&self, buf: &[u8]) -> io::Result<usize> {
        const WRITE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);
        let deadline = std::time::Instant::now() + WRITE_TIMEOUT;
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
                match e.kind() {
                    io::ErrorKind::Interrupted => continue,
                    io::ErrorKind::WouldBlock => {
                        // 输入缓冲已满：等待 POLLOUT（最多到 deadline），再重试写入
                        if std::time::Instant::now() >= deadline {
                            return Err(e);
                        }
                        let mut pfd = libc::pollfd {
                            fd: self.master_fd,
                            events: libc::POLLOUT,
                            revents: 0,
                        };
                        let remain =
                            deadline.saturating_duration_since(std::time::Instant::now());
                        let rc = unsafe {
                            libc::poll(
                                &mut pfd as *mut libc::pollfd,
                                1,
                                remain.as_millis() as c_int,
                            )
                        };
                        if rc < 0 {
                            let pe = io::Error::last_os_error();
                            if pe.kind() == io::ErrorKind::Interrupted {
                                continue; // poll 被 signal 打断：回 write 重试
                            }
                            return Err(pe);
                        }
                        // rc == 0 超时：回 write 再探一次，撞 deadline 收敛退出
                        continue;
                    }
                    _ => return Err(e),
                }
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

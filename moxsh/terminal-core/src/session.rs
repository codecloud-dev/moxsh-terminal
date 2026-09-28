//! 终端会话（Rust 自研）：聚合 PTY、屏幕与解析器。
//!
//! [`TerminalSession`] 持有 [`crate::pty::Pty`]（I/O 通道）、[`crate::screen::Screen`]
//! （字符网格 + 历史）与 [`crate::parser::Parser`]（转义序列状态机）。所有对外操作
//! 通过 `crate::lib` 的 C-ABI 暴露给 Kotlin/JNI。

use std::io;

use crate::parser::Parser;
use crate::pty::Pty;
use crate::screen::Screen;

/// 默认回滚行数（设置中可上调，架构文档设定的上限为 100k）。
pub const DEFAULT_HISTORY: usize = 10_000;

pub struct TerminalSession {
    pty: Pty,
    pub screen: Screen,
    parser: Parser,
    read_buf: [u8; 65536],
    /// 已收割的子进程退出码（None = 仍在运行或尚未收割）。
    /// 缓存后 [`TerminalSession::poll_exit`] 幂等返回，避免重复 waitpid（ECHILD）。
    reaped_exit: Option<i32>,
}

impl TerminalSession {
    /// 以 `cols x rows` 窗口启动 `cmd` 对应的程序。
    pub fn spawn(cmd: &str, cols: u32, rows: u32) -> io::Result<TerminalSession> {
        let pty = Pty::open(cmd, cols, rows)?;
        let screen = Screen::new(cols as usize, rows as usize, DEFAULT_HISTORY);
        Ok(TerminalSession {
            pty,
            screen,
            parser: Parser::new(),
            read_buf: [0u8; 65536],
            reaped_exit: None,
        })
    }

    /// 从 PTY 读取一次并喂给解析器，返回读取字节数（0=EOF/-1=错误已转换为 Err）。
    pub fn pump(&mut self) -> io::Result<usize> {
        let n = self.pty.read(&mut self.read_buf)?;
        if n == 0 {
            return Ok(0);
        }
        // 字段级借用互不相交，可同时使用 read_buf 与 screen/parser。
        self.parser.feed(&mut self.screen, &self.read_buf[..n]);
        Ok(n)
    }

    /// 把 PTY 输出读入 `buf` 并喂给解析器（供 C-ABI 复用调用方的缓冲区）。
    pub fn pump_into(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let n = self.pty.read(buf)?;
        if n == 0 {
            return Ok(0);
        }
        self.parser.feed(&mut self.screen, &buf[..n]);
        Ok(n)
    }

    pub fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.pty.write(buf)
    }

    pub fn resize(&mut self, cols: u32, rows: u32) -> io::Result<()> {
        self.pty.resize(cols, rows)?;
        self.screen.resize(cols as usize, rows as usize);
        Ok(())
    }

    pub fn rows(&self) -> usize {
        self.screen.rows
    }

    pub fn cols(&self) -> usize {
        self.screen.cols
    }

    pub fn total_rows(&self) -> usize {
        self.screen.total_rows()
    }

    /// 把绝对行 [start_row, start_row+count) 的单元格复制到 `out`（每行 cols*16 字节）。
    pub fn copy_cells(&self, start_row: usize, count: usize, out: &mut [u8]) {
        self.screen.copy_cells(start_row, count, out)
    }

    pub fn clear_dirty(&mut self) {
        self.screen.clear_dirty();
    }

    pub fn cursor(&self) -> (usize, usize) {
        (self.screen.cursor_x, self.screen.cursor_y)
    }

    pub fn title(&self) -> &str {
        &self.screen.title
    }

    /// 非阻塞轮询子进程退出状态（WNOHANG 收割，绝不挂起调用方）。
    ///
    /// 返回 `Some(code)` = 已退出：正常退出为退出码，信号退出为 `128+sig`
    /// （对齐 shell 惯例，`$?` 同语义）；`None` = 仍在运行。
    /// 一旦收割到即缓存，后续调用幂等返回同一值。
    ///
    /// 背景缺口：此前内核没有任何 waitpid/退出状态 API——用户敲 `exit` 后
    /// 子进程变僵尸（要到 destroySession 才被 Drop 收割），Kotlin 也拿不到
    /// 退出码，UI 无法提示"会话已结束"。
    pub fn poll_exit(&mut self) -> Option<i32> {
        if let Some(code) = self.reaped_exit {
            return Some(code);
        }
        let mut status: libc::c_int = 0;
        // SAFETY：waitpid 仅传本会话子进程 pid 与栈上 status，WNOHANG 不阻塞。
        let r = unsafe { libc::waitpid(self.pty.pid, &mut status, libc::WNOHANG) };
        if r != self.pty.pid {
            // 0=仍在运行；-1=错误（如已被 Drop 收割 ECHILD）——均视为"未收割"
            return None;
        }
        // WIFEXITED/WEXITSTATUS/WTERMSIG 在 libc crate 中是 safe fn，无需 unsafe 包裹
        let code = if libc::WIFEXITED(status) {
            libc::WEXITSTATUS(status)
        } else if libc::WIFSIGNALED(status) {
            128 + libc::WTERMSIG(status)
        } else {
            // WIFSTOPPED 等：子进程还在（被停/继续），不算退出
            return None;
        };
        self.reaped_exit = Some(code);
        Some(code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 轮询直到拿到退出码（最多 5s）。
    fn wait_exit(s: &mut TerminalSession) -> Option<i32> {
        for _ in 0..500 {
            if let Some(code) = s.poll_exit() {
                return Some(code);
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        None
    }

    #[test]
    fn exit_status_zero_on_clean_exit() {
        // Pty::open 用 execve（不走 PATH 解析），必须绝对路径
        let mut s = TerminalSession::spawn("/usr/bin/true", 80, 24).expect("spawn true");
        assert_eq!(wait_exit(&mut s), Some(0));
        // 幂等：收割后再次轮询返回同一退出码
        assert_eq!(s.poll_exit(), Some(0));
    }

    #[test]
    fn exit_status_reports_nonzero_code() {
        let mut s = TerminalSession::spawn("/usr/bin/false", 80, 24).expect("spawn false");
        assert_eq!(wait_exit(&mut s), Some(1));
    }

    #[test]
    fn poll_exit_returns_none_while_running() {
        let mut s =
            TerminalSession::spawn("/usr/bin/sleep 0.3", 80, 24).expect("spawn sleep");
        // 立即轮询：子进程大概率仍在运行（允许极快机器上已退出的抖动，故只断言类型）
        let _ = s.poll_exit();
        // 等它退出后必须能拿到 0
        assert_eq!(wait_exit(&mut s), Some(0));
    }

    #[test]
    fn pump_after_child_exit_is_clean_eof() {
        // Linux 语义：子进程退出（slave 全关）后读 master 得 EIO 而非 EOF。
        // 契约：pump 必须把 EIO 折叠为 Ok(0)（干净 EOF），不得当错误抛出。
        let mut s = TerminalSession::spawn("/usr/bin/true", 80, 24).expect("spawn true");
        assert_eq!(wait_exit(&mut s), Some(0));
        let mut buf = [0u8; 4096];
        for _ in 0..500 {
            match s.pump_into(&mut buf) {
                Ok(0) => return, // 干净 EOF，契约成立
                Ok(_) => std::thread::sleep(std::time::Duration::from_millis(10)),
                Err(e) => panic!("pump 应折叠 EIO 为干净 EOF，实际错误: {e}"),
            }
        }
        panic!("5s 内未观察到干净 EOF");
    }
}

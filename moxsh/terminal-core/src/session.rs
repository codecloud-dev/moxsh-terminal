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
}

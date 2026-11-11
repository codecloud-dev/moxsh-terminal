//! VT100/ANSI/xterm 转义序列解析器（Rust 自研，clean-room）。
//!
//! 一个紧凑但覆盖日常使用的状态机：处理可打印字符、C0 控制符、ESC 序列、
//! CSI（光标/擦除/SGR/滚动）、OSC（标题）。解析结果直接作用于 [`crate::screen::Screen`]。

use crate::screen::{Screen, ATTR_INVERSE};

#[derive(Debug, Clone, Copy, PartialEq)]
enum State {
    Ground,
    Escape,
    CsiEntry,
    CsiParam,
    OscString,
    /// OSC 以 ST（ESC \\）结束时，等待第二字节 `\`。
    OscEscape,
    Charset,
}

/// 终端私有模式状态（DECSET / DECRST）。
///
/// 2026-11-11 审计修复：旧解析器遇到 `?2004` / `?1006` / `?1000` 一律静默丢弃，
/// 上层输入层根本拿不到「程序要求括号粘贴 / SGR 鼠标上报」的信号。这里把状态
/// 显式记录并暴露；渲染/输入层按需消费（当前 App 输入层尚未全部接入，属已知差距）。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Modes {
    /// ?1000 / ?1002 / ?1003：鼠标事件上报。
    pub mouse: bool,
    /// ?1006：SGR 扩展鼠标坐标编码。
    pub sgr_mouse: bool,
    /// ?1015：urxvt 鼠标编码。
    pub urxvt_mouse: bool,
    /// ?2004：括号粘贴（paste 应用 \x1b[200~ / \x1b[201~ 包裹）。
    pub bracketed_paste: bool,
    /// ?1049 / ?47 / ?1047：备用屏。
    pub alt_screen: bool,
}

pub struct Parser {
    state: State,
    params: Vec<u32>,
    cur_param: u32,
    osc_buf: String,
    /// 是否处于 G0 图形字符集（line-drawing）。
    g1_graphic: bool,
    /// UTF-8 多字节累积缓冲（用于把跨字节序列合成一个码点）。
    utf8: Vec<u8>,
    /// 当前 CSI 是否为私有模式（`?` 前缀）。
    private: bool,
    /// 已解析的私有模式状态，供输入/渲染层消费。
    pub modes: Modes,
    /// OSC 52 请求写入剪贴板的 base64 原文（不含 `c;` 选择子）。
    /// 核心库不碰系统剪贴板（无平台权限、也不该有），由上层解码后决定如何处理。
    pub clipboard: Option<String>,
}

impl Default for Parser {
    fn default() -> Self {
        Self::new()
    }
}

impl Parser {
    pub fn new() -> Parser {
        Parser {
            state: State::Ground,
            params: Vec::new(),
            cur_param: 0,
            osc_buf: String::new(),
            g1_graphic: false,
            utf8: Vec::new(),
            private: false,
            modes: Modes::default(),
            clipboard: None,
        }
    }

    pub fn feed(&mut self, screen: &mut Screen, bytes: &[u8]) {
        for &b in bytes {
            self.step(screen, b);
        }
    }

    fn push_param(&mut self) {
        // P1 修复：参数个数上限 32（VT 兼容上限），超出丢弃——防 `ESC[;;;;...` 把
        // params Vec 撑到无界（每字节 4B 内存）。
        if self.params.len() < 32 {
            self.params.push(self.cur_param);
        }
        self.cur_param = 0;
    }

    fn step(&mut self, screen: &mut Screen, b: u8) {
        match self.state {
            State::Ground => self.ground(screen, b),
            State::Escape => self.escape(screen, b),
            State::CsiEntry => self.csi_entry(screen, b),
            State::CsiParam => self.csi_param(screen, b),
            State::OscString => self.osc_string(screen, b),
            State::OscEscape => {
                // ST 的第二字节 `\`：吞掉，绝不把它当可打印字符写进屏幕。
                // 容错：即便下一字节不是 `\` 也只丢弃这一个字节，不污染主屏。
                self.state = State::Ground;
            }
            State::Charset => {
                // ESC ( B / ESC ) 0 等之后的单字节
                if b == b'0' {
                    self.g1_graphic = true;
                } else if b == b'B' {
                    self.g1_graphic = false;
                }
                self.state = State::Ground;
            }
        }
    }

    fn ground(&mut self, screen: &mut Screen, b: u8) {
        if b == 0x7f {
            return; // P3：DEL 不写入格子（0x7f 是删除而非可见字符）
        }
        match b {
            0x1b => self.state = State::Escape,
            0x0d => {
                screen.carriage_return();
            }
            0x0a..=0x0c => {
                screen.line_feed();
            }
            0x09 => {
                // tab 前进到下一个 8 列制表位
                let next = (screen.cursor_x / 8 + 1) * 8;
                screen.cursor_x = next.min(screen.cols - 1);
            }
            0x08 => {
                screen.cursor_x = screen.cursor_x.saturating_sub(1);
            }
            0x07 => { /* BEL 忽略 */ }
            _ => {
                if b < 0x20 {
                    return; // 其余 C0 控制符忽略
                }
                if b < 0x80 {
                    // ASCII 可打印字符（可能需图形字符集映射）
                    let code = if self.g1_graphic && (0x61..=0x7a).contains(&b) {
                        GRAPHIC_MAP
                            .iter()
                            .find(|(k, _)| *k == b)
                            .map(|(_, v)| *v)
                            .unwrap_or(b as u32)
                    } else {
                        b as u32
                    };
                    screen.put_char(code);
                } else {
                    // 多字节 UTF-8：累积到完整序列再解码。
                    // 【bug 修复】期望长度 need 只应由"首字节"（utf8[0]）判定：
                    // 原实现对每个字节都用当前字节判定，续字节（0x80..=0xBF）
                    // 右移后落入 else 分支得 need=1，被误判为非法首字节——缓冲
                    // 被清空并输出 U+FFFD，导致所有多字节字符（中文/emoji）损坏。
                    // 改为按缓冲首字节判定后，续字节正常累积到完整序列。
                    self.utf8.push(b);
                    let head = self.utf8[0];
                    let need = if head >> 5 == 0b110 {
                        2
                    } else if head >> 4 == 0b1110 {
                        3
                    } else if head >> 3 == 0b11110 {
                        4
                    } else {
                        1 // 非法首字节
                    };
                    if need == 1 {
                        self.utf8.clear();
                        screen.put_char(0xfffd);
                    } else if self.utf8.len() >= need {
                        if let Some(cp) = crate::arch::utf8_complete(&self.utf8) {
                            screen.put_char(cp);
                        }
                        self.utf8.clear();
                    }
                }
            }
        }
    }

    fn escape(&mut self, screen: &mut Screen, b: u8) {
        match b {
            b'[' => {
                self.state = State::CsiEntry;
                self.params.clear();
                self.cur_param = 0;
                self.private = false;
            }
            b']' => {
                self.state = State::OscString;
                self.osc_buf.clear();
            }
            b'(' | b')' => {
                self.state = State::Charset;
            }
            b'c' => {
                // RIS：重置屏幕
                *screen = Screen::new(screen.cols, screen.rows, screen.max_history);
                self.state = State::Ground;
            }
            b'M' => {
                screen.reverse_line_feed();
                self.state = State::Ground;
            }
            b'E' => {
                screen.carriage_return();
                screen.line_feed();
                self.state = State::Ground;
            }
            b'D' => {
                screen.line_feed();
                self.state = State::Ground;
            }
            b'7' => {
                screen.save_cursor();
                self.state = State::Ground;
            }
            b'8' => {
                screen.restore_cursor();
                self.state = State::Ground;
            }
            _ => self.state = State::Ground,
        }
    }

    fn csi_entry(&mut self, screen: &mut Screen, b: u8) {
        match b {
            b'0'..=b'9' => {
                self.cur_param = (b - b'0') as u32;
                self.state = State::CsiParam;
            }
            b';' => {
                self.push_param();
                self.state = State::CsiParam;
            }
            b'?' => { self.private = true; }
            _ => {
                self.dispatch_csi(screen, b);
                self.state = State::Ground;
            }
        }
    }

    fn csi_param(&mut self, screen: &mut Screen, b: u8) {
        match b {
            b'0'..=b'9' => {
                // P1 修复：saturating + 上限钳制，防 u32 回绕与天文数字参数
                self.cur_param = self
                    .cur_param
                    .saturating_mul(10)
                    .saturating_add((b - b'0') as u32)
                    .min(65_535);
            }
            b';' => {
                self.push_param();
            }
            _ => {
                self.push_param();
                self.dispatch_csi(screen, b);
                self.state = State::Ground;
            }
        }
    }

    fn dispatch_csi(&mut self, screen: &mut Screen, final_byte: u8) {
        let p = &self.params;
        let n0 = p.first().copied().unwrap_or(0);
        let n1 = p.get(1).copied().unwrap_or(0);
        match final_byte {
            b'A' => screen.move_cursor(0, -(n0.max(1) as i32)),
            b'B' => screen.move_cursor(0, n0.max(1) as i32),
            b'C' => screen.move_cursor(n0.max(1) as i32, 0),
            b'D' => screen.move_cursor(-(n0.max(1) as i32), 0),
            b'E' => {
                screen.carriage_return();
                screen.move_cursor(0, n0.max(1) as i32);
            }
            b'F' => {
                screen.carriage_return();
                screen.move_cursor(0, -(n0.max(1) as i32));
            }
            b'G' => screen.set_cursor((n0.max(1) - 1) as usize, screen.cursor_y),
            b'H' | b'f' => {
                screen.set_cursor((n1.max(1) - 1) as usize, (n0.max(1) - 1) as usize)
            }
            b'd' => screen.set_cursor(screen.cursor_x, (n0.max(1) - 1) as usize),
            b'J' => screen.erase_in_display(n0),
            b'K' => screen.erase_in_line(n0),
            b'L' => {
                // P1 修复：滚动次数钳制到行数（等价语义：一次滚 N 行 == 多次滚 1 行直到 N 行），
                // 防恶意大参数触发天文数字次全网格拷贝。
                let n = n0.max(1).min(screen.rows as u32);
                for _ in 0..n {
                    screen.scroll_down(1);
                }
            }
            b'M' => {
                // P1 修复：滚动次数钳制到行数（等价语义：一次滚 N 行 == 多次滚 1 行直到 N 行），
                // 防恶意大参数触发天文数字次全网格拷贝。
                let n = n0.max(1).min(screen.rows as u32);
                for _ in 0..n {
                    screen.scroll_up(1);
                }
            }
            b'P' => {
                let y = screen.cursor_y;
                let x = screen.cursor_x;
                let n = n0.max(1) as usize;
                for c in x..screen.cols.saturating_sub(n) {
                    screen.cells[y * screen.cols + c] = screen.cells[y * screen.cols + c + n];
                }
                for c in screen.cols.saturating_sub(n)..screen.cols {
                    screen.cells[y * screen.cols + c] = crate::screen::Cell::default();
                }
                screen.mark(y);
            }
            b'@' => {
                let y = screen.cursor_y;
                let x = screen.cursor_x;
                let n = n0.max(1) as usize;
                for c in (x + n..screen.cols).rev() {
                    screen.cells[y * screen.cols + c] = screen.cells[y * screen.cols + c - n];
                }
                for c in x..(x + n).min(screen.cols) {
                    screen.cells[y * screen.cols + c] = crate::screen::Cell::default();
                }
                screen.mark(y);
            }
            b'S' => {
                // P1 修复：滚动次数钳制到行数（等价语义：一次滚 N 行 == 多次滚 1 行直到 N 行），
                // 防恶意大参数触发天文数字次全网格拷贝。
                let n = n0.max(1).min(screen.rows as u32);
                for _ in 0..n {
                    screen.scroll_up(1);
                }
            }
            b'T' => {
                // P1 修复：滚动次数钳制到行数（等价语义：一次滚 N 行 == 多次滚 1 行直到 N 行），
                // 防恶意大参数触发天文数字次全网格拷贝。
                let n = n0.max(1).min(screen.rows as u32);
                for _ in 0..n {
                    screen.scroll_down(1);
                }
            }
            b'm' => screen.set_sgr(p),
            b'r' => {
                let top = (n0.max(1) - 1) as usize;
                let bottom = (n1.max(1) - 1) as usize;
                screen.set_scroll_region(top, bottom);
            }
            b's' => screen.save_cursor(),
            b'u' => screen.restore_cursor(),
            b'h' | b'l' if self.private => {
                // 2026-11-11 审计修复：私有模式不再只处理 ?25 / ?1049 且不再静默清屏。
                // 支持一次多个模式（CSI ?1049;1006;2004h），并同步记录到 self.modes。
                let set = final_byte == b'h';
                let ms: Vec<u32> = p.to_vec();
                for m in ms {
                    match m {
                        25 => screen.cursor_visible = set,
                        47 | 1047 | 1049 => {
                            if m == 1049 && set {
                                screen.save_cursor();
                            }
                            if set {
                                screen.enter_alt_screen();
                            } else {
                                screen.exit_alt_screen();
                                if m == 1049 {
                                    screen.restore_cursor();
                                }
                            }
                        }
                        1000 | 1002 | 1003 => self.modes.mouse = set,
                        1006 => self.modes.sgr_mouse = set,
                        1015 => self.modes.urxvt_mouse = set,
                        2004 => self.modes.bracketed_paste = set,
                        _ => {}
                    }
                }
                self.modes.alt_screen = screen.in_alt_screen();
            }
            b'h' | b'l' => {
                // 非私有 CSI h/l（如插入模式）当前渲染层不支持，明确忽略，
                // 不再被误当成 DECSET 触发备用屏。
            }
            _ => {}
        }
    }

    fn osc_string(&mut self, screen: &mut Screen, b: u8) {
        if b == 0x07 {
            // BEL 终止：单字节，直接收尾。
            self.finish_osc(screen);
            self.state = State::Ground;
        } else if b == 0x1b {
            // ST 终止（ESC \）：先收尾，再吞掉后续 `\`。旧实现在这里直接回
            // Ground，导致 `\` 被当成普通字符打印到屏幕上（标题栏之后多出反斜杠）。
            self.finish_osc(screen);
            self.state = State::OscEscape;
        } else if b >= 0x20 {
            // P2 修复：OSC 缓冲上限 4KiB，超出进入静默丢弃态（仍消费字节直到终止符），
            // 防无终止符的无限 OSC 流把 String 撑到 OOM。
            if self.osc_buf.len() < 4096 {
                self.osc_buf.push(b as char);
            }
        }
    }

    fn finish_osc(&mut self, screen: &mut Screen) {
        if let Some(idx) = self.osc_buf.find(';') {
            let command = &self.osc_buf[..idx];
            let value = &self.osc_buf[idx + 1..];
            match command {
                "0" | "2" => screen.title = value.to_string(),
                // OSC 52：程序请求写系统剪贴板，格式 `52;c;<base64>`。
                // 只记录原始 base64，不在此处写剪贴板（核心库无平台权限）；
                // `52;c;?` 是查询剪贴板，需要宿主回写 PTY，当前不支持，忽略。
                "52" => {
                    if let Some(b64) = value.strip_prefix("c;") {
                        if b64 != "?" {
                            self.clipboard = Some(b64.to_string());
                        }
                    }
                }
                _ => {}
            }
        }
        self.osc_buf.clear();
    }

}

/// 图形字符集近似映射（DEC Special Graphics → Unicode）。
const GRAPHIC_MAP: &[(u8, u32)] = &[
    (b'q', 0x2500), // ─
    (b'w', 0x2501), // ━
    (b'e', 0x2502), // │
    (b't', 0x2514), // └
    (b'u', 0x2518), // ┘
    (b'k', 0x2510), // ┐
    (b'l', 0x250c), // ┌
    (b'm', 0x2534), // ┴
    (b'j', 0x252c), // ┬
    (b'n', 0x253c), // ┼
    (b'x', 0x2715), // ✕
    (b'o', 0x2022), // •
];

impl Parser {
    pub fn reset(&mut self, screen: &mut Screen) {
        *screen = Screen::new(screen.cols, screen.rows, screen.max_history);
        self.state = State::Ground;
        self.params.clear();
        self.cur_param = 0;
        self.osc_buf.clear();
        self.utf8.clear();
    }

    /// 标记屏幕为反显（测试/调试用）。
    #[allow(dead_code)]
    pub fn debug_inverse(screen: &mut Screen) {
        screen.cur_attrs |= ATTR_INVERSE;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::screen::{ATTR_BOLD, Screen};

    /// M5-1：普通字符打印——逐格落位、光标推进。
    #[test]
    fn plain_chars_print_and_advance() {
        let mut s = Screen::new(10, 4, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"Hi");
        assert_eq!(s.cell_at(0, 0).code, 'H' as u32);
        assert_eq!(s.cell_at(0, 1).code, 'i' as u32);
        assert_eq!(s.cursor_x, 2);
        assert_eq!(s.cursor_y, 0);
    }

    /// M5-2：C0 控制符——\r 归零、\n 换行、\b 退格、\t 到 8 列制表位。
    #[test]
    fn c0_controls() {
        let mut s = Screen::new(40, 4, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"AB");
        assert_eq!(s.cursor_x, 2);
        p.feed(&mut s, b"\r");
        assert_eq!(s.cursor_x, 0);
        p.feed(&mut s, b"\n");
        assert_eq!(s.cursor_y, 1);
        p.feed(&mut s, b"C");
        assert_eq!(s.cursor_x, 1);
        p.feed(&mut s, b"\x08"); // BS：退回 C 之前
        assert_eq!(s.cursor_x, 0);
        p.feed(&mut s, b"\t"); // HT：0 → 下一个 8 列制表位
        assert_eq!(s.cursor_x, 8);
    }

    /// M5-3：CSI 光标移动——CUP 绝对定位、CUU 上移、CUD 下移。
    #[test]
    fn csi_cursor_moves() {
        let mut s = Screen::new(20, 10, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b[3;5H"); // 行 3 列 5（1-based）→ (x=4, y=2)
        assert_eq!((s.cursor_x, s.cursor_y), (4, 2));
        p.feed(&mut s, b"\x1b[2A"); // CUU 上移 2 → y=0
        assert_eq!(s.cursor_y, 0);
        p.feed(&mut s, b"\x1b[2B"); // CUD 下移 2 → y=2
        assert_eq!(s.cursor_y, 2);
        p.feed(&mut s, b"\x1b[3D"); // CUB 左移 3 → x=1
        assert_eq!(s.cursor_x, 1);
    }

    /// M5-4：SGR——前景/背景/粗体，0 复位全部样式。
    #[test]
    fn sgr_colors() {
        let mut s = Screen::new(20, 4, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b[1;31mX"); // 粗体 + 前景红
        let c = s.cell_at(0, 0);
        assert_eq!(c.code, 'X' as u32);
        assert_eq!(c.attrs & ATTR_BOLD, ATTR_BOLD);
        assert_eq!(c.fg, 0xcd3131);
        p.feed(&mut s, b"\x1b[41mY"); // 背景红（前景保持）
        let c = s.cell_at(0, 1);
        assert_eq!(c.bg, 0xcd3131);
        assert_eq!(c.fg, 0xcd3131);
        p.feed(&mut s, b"\x1b[0mZ"); // 全复位
        let c = s.cell_at(0, 2);
        assert_eq!(c.attrs, 0);
        assert_eq!(c.fg, 0);
        assert_eq!(c.bg, 0);
    }

    /// M5-5：OSC 标题设置——只写 title，不污染屏幕网格与光标。
    #[test]
    fn osc_title_no_screen_pollution() {
        let mut s = Screen::new(20, 4, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b]0;moxsh-term\x07");
        assert_eq!(s.title, "moxsh-term");
        assert_eq!(s.cell_at(0, 0).code, 0);
        assert_eq!((s.cursor_x, s.cursor_y), (0, 0));
        // OSC 以 ST（BEL 同效）结束后解析器回到 Ground 态，继续正常收字符。
        p.feed(&mut s, b"\x1b]2;sh\x07ok");
        assert_eq!(s.title, "sh");
        assert_eq!(s.cell_at(0, 0).code, 'o' as u32);
        assert_eq!(s.cursor_x, 2);
    }

    /// M5-6：ICH/DCH——插入/删除字符，右侧内容相应平移、尾部补空。
    #[test]
    fn ich_and_dch() {
        let mut s = Screen::new(10, 3, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"ABCDEF");
        p.feed(&mut s, b"\x1b[1;1H"); // 回 (0,0)
        p.feed(&mut s, b"\x1b[2P"); // DCH：删 2 字符 → "CDEF" 左移
        assert_eq!(s.cell_at(0, 0).code, 'C' as u32);
        assert_eq!(s.cell_at(0, 1).code, 'D' as u32);
        assert_eq!(s.cell_at(0, 4).code, 0); // 尾部补空
        p.feed(&mut s, b"\x1b[1;1H");
        p.feed(&mut s, b"\x1b[2@"); // ICH：插 2 空位 → 内容右移
        assert_eq!(s.cell_at(0, 0).code, 0);
        assert_eq!(s.cell_at(0, 2).code, 'C' as u32);
    }

    /// M5-7：自动换行回绕——写满一行后回绕到下一行行首。
    #[test]
    fn auto_wrap_to_next_line() {
        let mut s = Screen::new(5, 3, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"ABCDEFG"); // 5 列屏，7 字符
        assert_eq!(s.cell_at(0, 4).code, 'E' as u32); // 行尾
        assert_eq!(s.cell_at(1, 0).code, 'F' as u32); // 回绕
        assert_eq!(s.cell_at(1, 1).code, 'G' as u32);
        assert_eq!(s.cursor_y, 1);
        assert_eq!(s.cursor_x, 2);
    }

    /// M5-8：滚动区域——区域内的 LF 触发区域内上滚，区域外行不动。
    #[test]
    fn scroll_region_confined() {
        let mut s = Screen::new(8, 5, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b[1;3r"); // 区域 = 行 1..3（0-indexed 0..=2）
        assert_eq!(s.scroll_top, 0);
        assert_eq!(s.scroll_bottom, 2);
        p.feed(&mut s, b"\x1b[1;1HR1"); // row0
        p.feed(&mut s, b"\x1b[2;1HR2"); // row1
        p.feed(&mut s, b"\x1b[4;1HKEEP"); // row3：区域外
        p.feed(&mut s, b"\x1b[3;1H"); // 光标到 scroll_bottom 行
        p.feed(&mut s, b"\n"); // bottom 行 LF → 区域内上滚
        assert_eq!(s.cell_at(0, 0).code, 'R' as u32); // row0 ← "R2"
        assert_eq!(s.cell_at(0, 1).code, '2' as u32);
        assert_eq!(s.cell_at(3, 0).code, 'K' as u32); // 区域外 KEEP 不动
    }

    /// M5-9：UTF-8 中文与 emoji——解码为正确码点，宽字符按 2 列落位推进
    /// （主格写码点，占用格写 0，即 screen 侧的 wide 占位标记）。
    #[test]
    fn utf8_wide_chars_two_columns() {
        let mut s = Screen::new(20, 3, 8);
        let mut p = Parser::new();
        p.feed(&mut s, "中".as_bytes()); // E4 B8 AD
        assert_eq!(s.cell_at(0, 0).code, 0x4e2d);
        assert_eq!(s.cell_at(0, 1).code, 0); // 宽字符占用格
        assert_eq!(s.cursor_x, 2); // 按 2 列推进
        p.feed(&mut s, "\u{1F600}".as_bytes()); // 😀 F0 9F 98 80（4 字节）
        assert_eq!(s.cell_at(0, 2).code, 0x1f600);
        assert_eq!(s.cell_at(0, 3).code, 0);
        assert_eq!(s.cursor_x, 4);
    }

    // ===== 2026-11-11 全仓审计回归：终端备用屏 / 私有模式 / OSC52 =====

    /// ?1049 进出备用屏必须还原主屏内容与光标（旧实现退出时只留花屏）。
    #[test]
    fn audit_alt_screen_1049_saves_and_restores() {
        let mut s = Screen::new(10, 3, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"MAIN");
        s.set_cursor(4, 1);
        p.feed(&mut s, b"\x1b[?1049h");
        assert!(s.in_alt_screen(), "应进入备用屏");
        assert!(p.modes.alt_screen);
        assert_eq!(s.cell_at(0, 0).code, 0, "进入备用屏应清屏");
        p.feed(&mut s, b"VIM");
        assert_eq!(s.cell_at(0, 0).code, 'V' as u32);
        p.feed(&mut s, b"\x1b[?1049l");
        assert!(!s.in_alt_screen(), "应退回主屏");
        assert!(!p.modes.alt_screen);
        assert_eq!(s.cell_at(0, 0).code, 'M' as u32, "主屏内容必须还原");
        assert_eq!(s.cell_at(0, 3).code, 'N' as u32);
        assert_eq!((s.cursor_x, s.cursor_y), (4, 1), "1049 应同时恢复光标");
    }

    /// 重复 ?1049h 不能覆盖快照（嵌套进入不得丢主屏）。
    #[test]
    fn audit_alt_screen_double_enter_is_idempotent() {
        let mut s = Screen::new(8, 2, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"AB");
        p.feed(&mut s, b"\x1b[?1049h\x1b[?1049h");
        p.feed(&mut s, b"\x1b[?1049l");
        assert_eq!(s.cell_at(0, 0).code, 'A' as u32);
        assert_eq!(s.cell_at(0, 1).code, 'B' as u32);
    }

    /// ?47 / ?1047 只切屏不负责保存光标。
    #[test]
    fn audit_alt_screen_47_toggles() {
        let mut s = Screen::new(8, 2, 8);
        let mut p = Parser::new();
        p.feed(&mut s, b"XY\x1b[?47h");
        assert!(s.in_alt_screen());
        p.feed(&mut s, b"\x1b[?47l");
        assert!(!s.in_alt_screen());
        assert_eq!(s.cell_at(0, 0).code, 'X' as u32);
    }

    /// 一次多个私有模式 + 状态真实可查，不再静默丢弃。
    #[test]
    fn audit_private_modes_are_recorded() {
        let mut s = Screen::new(10, 3, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b[?2004h\x1b[?1006h\x1b[?1000h\x1b[?1015h");
        assert!(p.modes.bracketed_paste, "?2004");
        assert!(p.modes.sgr_mouse, "?1006");
        assert!(p.modes.mouse, "?1000");
        assert!(p.modes.urxvt_mouse, "?1015");
        p.feed(&mut s, b"\x1b[?2004;1006l");
        assert!(!p.modes.bracketed_paste, "分号批量 DECRST");
        assert!(!p.modes.sgr_mouse, "分号批量 DECRST");
    }

    /// 非私有 CSI h 不得被误判成 DECSET。
    #[test]
    fn audit_non_private_csi_h_is_not_decset() {
        let mut s = Screen::new(10, 3, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b[4h");
        assert!(!s.in_alt_screen());
    }

    /// OSC 52 剪贴板内容被捕获；ST（ESC \）不再漏出反斜杠字符。
    #[test]
    fn audit_osc52_and_st_terminator() {
        let mut s = Screen::new(10, 3, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b]52;c;aGVsbG8=\x07");
        assert_eq!(p.clipboard.as_deref(), Some("aGVsbG8="));
        p.feed(&mut s, b"\x1b]0;title\x1b\\OK");
        assert_eq!(s.title, "title");
        assert_eq!(s.cell_at(0, 0).code, 'O' as u32, "ST 的 \\ 不能被打印");
        assert_eq!(s.cell_at(0, 1).code, 'K' as u32);
    }

    /// OSC 52 查询（`?`）不应被当成剪贴板写入。
    #[test]
    fn audit_osc52_query_ignored() {
        let mut s = Screen::new(10, 3, 16);
        let mut p = Parser::new();
        p.feed(&mut s, b"\x1b]52;c;?\x07");
        assert_eq!(p.clipboard, None);
    }

}

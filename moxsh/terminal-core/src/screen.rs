//! 屏幕网格与光标/样式状态机（Rust 自研，clean-room）。
//!
//! [`Screen`] 维护一个 `rows × cols` 的 [`Cell`] 网格、光标位置、当前 SGR 样式，
//! 以及历史回滚缓冲（[`crate::ring::RingBuffer`]）。渲染层（Kotlin/Compose）通过
//! [`Screen::copy_cells`] 把可见/历史区域以紧凑二进制复制到直接缓冲区后绘制。

/// 单个字符格子在跨 FFI 传输时的字节布局（16 字节，小端）。
/// Kotlin 侧用 `ByteBuffer.order(LITTLE_ENDIAN)` 按此结构读。
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct Cell {
    /// Unicode 码点；0 表示空格/空。
    pub code: u32,
    /// 前景色 0xRRGGBB；0 表示使用默认前景。
    pub fg: u32,
    /// 背景色 0xRRGGBB；0 表示使用默认背景。
    pub bg: u32,
    /// 属性位掩码（见 [`ATTR_*`]）。
    pub attrs: u16,
}

pub const ATTR_BOLD: u16 = 1 << 0;
pub const ATTR_ITALIC: u16 = 1 << 1;
pub const ATTR_UNDERLINE: u16 = 1 << 2;
pub const ATTR_BLINK: u16 = 1 << 3;
pub const ATTR_INVERSE: u16 = 1 << 4;
pub const ATTR_DIM: u16 = 1 << 5;

impl Cell {
    pub fn blank() -> Cell {
        Cell::default()
    }
}

/// 近似 wcwidth：返回字符占据的列宽（0/1/2）。
///
/// P2 修复：补齐零宽/组合字符（返回 0，中文乱位主因之一）、
/// Unicode 13+ 常用 emoji 宽字符（EAW=W）、CJK 扩展 B~F、肤色修饰符。
/// 范围表依据 Unicode EastAsianWidth（W/F 集合的常用子集），按 lo 升序排列。
fn in_ranges(c: u32, ranges: &[(u32, u32)]) -> bool {
    ranges
        .binary_search_by(|&(lo, hi)| {
            if c < lo {
                std::cmp::Ordering::Greater
            } else if c > hi {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Equal
            }
        })
        .is_ok()
}

/// 零宽集合：组合附加符号、变体选择符（emoji 序列必需）、零宽控制符、肤色修饰符。
const ZERO_WIDTH_RANGES: &[(u32, u32)] = &[
    (0x0300, 0x036f),
    (0x0483, 0x0489),
    (0x0591, 0x05bd),
    (0x0610, 0x061a),
    (0x064b, 0x065f),
    (0x0670, 0x0670),
    (0x06d6, 0x06dc),
    (0x0900, 0x0902),
    (0x093a, 0x093a),
    (0x093c, 0x093c),
    (0x0941, 0x0948),
    (0x094d, 0x094d),
    (0x0e31, 0x0e31),
    (0x0e34, 0x0e3a),
    (0x0e47, 0x0e4e),
    (0x1ab0, 0x1aff),
    (0x1dc0, 0x1dff),
    (0x200b, 0x200f),
    (0x202a, 0x202e),
    (0x2060, 0x2064),
    (0x20d0, 0x20f0),
    (0xfe00, 0xfe0f),
    (0xfe20, 0xfe2f),
    (0xfeff, 0xfeff),
    (0x1f3fb, 0x1f3ff),
    (0xe0100, 0xe01ef),
];

/// 宽字符补充集合：常用 emoji（EAW=W）、CJK 扩展、西夏文、注音变体等。
const WIDE_EXTRA_RANGES: &[(u32, u32)] = &[
    (0x16fe0, 0x16fe4),
    (0x17000, 0x187f7),
    (0x18800, 0x18cd5),
    (0x1b000, 0x1b2fb),
    (0x231a, 0x231b),
    (0x2329, 0x232a),
    (0x23e9, 0x23ec),
    (0x23f0, 0x23f0),
    (0x23f3, 0x23f3),
    (0x25fd, 0x25fe),
    (0x2614, 0x2615),
    (0x2648, 0x2653),
    (0x267f, 0x267f),
    (0x2693, 0x2693),
    (0x26a1, 0x26a1),
    (0x26aa, 0x26ab),
    (0x26bd, 0x26be),
    (0x26c4, 0x26c5),
    (0x26ce, 0x26ce),
    (0x26d4, 0x26d4),
    (0x26ea, 0x26ea),
    (0x26f2, 0x26f3),
    (0x26f5, 0x26f5),
    (0x26fa, 0x26fd),
    (0x2705, 0x2705),
    (0x270a, 0x270b),
    (0x2728, 0x2728),
    (0x274c, 0x274c),
    (0x274e, 0x274e),
    (0x2753, 0x2755),
    (0x2757, 0x2757),
    (0x2795, 0x2797),
    (0x27b0, 0x27b0),
    (0x27bf, 0x27bf),
    (0x2b1b, 0x2b1c),
    (0x2b50, 0x2b50),
    (0x2b55, 0x2b55),
    (0x2ebf0, 0x2ee5d),
    (0x1f004, 0x1f004),
    (0x1f0cf, 0x1f0cf),
    (0x1f18e, 0x1f18e),
    (0x1f191, 0x1f19a),
    (0x1f200, 0x1f2ff),
];

pub fn wcwidth(c: u32) -> u8 {
    if c == 0 {
        return 0;
    }
    if c < 0x20 || (0x7f <= c && c < 0xa0) {
        return 0; // 控制字符
    }
    if in_ranges(c, ZERO_WIDTH_RANGES) {
        return 0; // 组合符/变体选择符/零宽控制：不占列
    }
    if (0x20..0x7f).contains(&c) || (0xa0..0x1100).contains(&c) {
        return 1;
    }
    // 宽字符范围（东亚宽/全角 + 常见 emoji）
    if (0x1100..=0x115f).contains(&c)
        || (0x2e80..=0x303e).contains(&c)
        || (0x3041..=0x33ff).contains(&c)
        || (0x3400..=0x4dbf).contains(&c)
        || (0x4e00..=0x9fff).contains(&c)
        || (0xa000..=0xa4cf).contains(&c)
        || (0xac00..=0xd7a3).contains(&c)
        || (0xf900..=0xfaff).contains(&c)
        || (0xfe30..=0xfe4f).contains(&c)
        || (0xff00..=0xff60).contains(&c)
        || (0xffe0..=0xffe6).contains(&c)
        || (0x1f300..=0x1faff).contains(&c)
        || (0x20000..=0x3fffd).contains(&c)
        || in_ranges(c, WIDE_EXTRA_RANGES)
    {
        return 2;
    }
    1
}

/// 一行历史文本（含样式）用于回滚缓冲。
#[derive(Clone)]
pub struct Line {
    pub cells: Vec<Cell>,
}

impl Line {
    pub fn blank(cols: usize) -> Line {
        Line {
            cells: vec![Cell::default(); cols],
        }
    }
}

pub struct Screen {
    pub cols: usize,
    pub rows: usize,
    // pub(crate)：Parser 的 CSI 处理（DCH/ICH 等行列操作）需直接读写网格。
    pub(crate) cells: Vec<Cell>,
    pub cursor_x: usize,
    pub cursor_y: usize,
    pub cursor_visible: bool,
    saved_x: usize,
    saved_y: usize,
    cur_fg: u32,
    cur_bg: u32,
    pub(crate) cur_attrs: u16,
    pub scroll_top: usize,
    pub scroll_bottom: usize,
    pub auto_wrap: bool,
    pending_wrap: bool,
    /// 历史回滚（最早在前）。
    pub history: crate::ring::RingBuffer<Line>,
    pub title: String,
    /// 自上次绘制以来的脏行集合（绝对行号 = history.len() + 可见行号）。
    pub dirty: std::collections::HashSet<usize>,
    pub(crate) max_history: usize,
}

impl Screen {
    pub fn new(cols: usize, rows: usize, max_history: usize) -> Screen {
        Screen {
            cols,
            rows,
            cells: vec![Cell::default(); cols * rows],
            cursor_x: 0,
            cursor_y: 0,
            cursor_visible: true,
            saved_x: 0,
            saved_y: 0,
            cur_fg: 0,
            cur_bg: 0,
            cur_attrs: 0,
            scroll_top: 0,
            scroll_bottom: rows - 1,
            auto_wrap: true,
            pending_wrap: false,
            history: crate::ring::RingBuffer::new(max_history),
            title: String::new(),
            dirty: std::collections::HashSet::new(),
            max_history,
        }
    }

    pub fn cell_at(&self, r: usize, c: usize) -> &Cell {
        &self.cells[r * self.cols + c]
    }

    fn cell_at_mut(&mut self, r: usize, c: usize) -> &mut Cell {
        &mut self.cells[r * self.cols + c]
    }

    pub(crate) fn mark(&mut self, r: usize) {
        self.dirty.insert(self.history.len() + r);
    }

    /// 写入一个码点（处理自动换行与宽字符占用）。
    pub fn put_char(&mut self, code: u32) {
        let w = wcwidth(code) as usize;
        let w = w.max(1);
        if self.pending_wrap && self.auto_wrap {
            self.carriage_return();
            self.line_feed();
            self.pending_wrap = false;
        }
        if self.cursor_x + w > self.cols {
            if self.auto_wrap {
                self.carriage_return();
                self.line_feed();
            } else {
                self.cursor_x = self.cols.saturating_sub(w);
            }
        }
        let x = self.cursor_x;
        let y = self.cursor_y;
        if y < self.rows {
            // 先快照当前样式：cell_at_mut 借用整个 self，无法同时读 cur_*。
            let (fg, bg, attrs) = (self.cur_fg, self.cur_bg, self.cur_attrs);
            for i in 0..w {
                let c = if i == 0 { code } else { 0 };
                let cell = self.cell_at_mut(y, x + i);
                cell.code = c;
                cell.fg = fg;
                cell.bg = bg;
                cell.attrs = attrs;
            }
            self.mark(y);
            self.cursor_x = x + w;
            if self.cursor_x >= self.cols && self.auto_wrap {
                self.pending_wrap = true;
                // P0 修复：pending_wrap 态光标停在最后一列（而非 cols）。
                // 推到 cols 会让 EL1/ED1/DCH 等"以 cursor_x 为端点"的序列越界 panic
                // （panic 穿越 extern "C" 直接 abort App）；换行动作由 pending_wrap 分支处理。
                self.cursor_x = self.cols.saturating_sub(1);
            }
        }
    }

    pub fn carriage_return(&mut self) {
        self.cursor_x = 0;
        self.pending_wrap = false;
    }

    pub fn line_feed(&mut self) {
        if self.cursor_y == self.scroll_bottom {
            self.scroll_up(1);
        } else if self.cursor_y < self.rows - 1 {
            self.cursor_y += 1;
        }
        self.pending_wrap = false;
    }

    pub fn reverse_line_feed(&mut self) {
        if self.cursor_y == self.scroll_top {
            self.scroll_down(1);
        } else if self.cursor_y > 0 {
            self.cursor_y -= 1;
        }
    }

    pub fn scroll_up(&mut self, n: usize) {
        for _ in 0..n {
            if self.scroll_top == 0 && self.scroll_bottom == self.rows - 1 {
                // 整屏滚动：把第一行推进历史
                let first = Line {
                    cells: self.cells[0..self.cols].to_vec(),
                };
                self.history.push(first);
            }
            for r in self.scroll_top..self.scroll_bottom {
                let src = (r + 1) * self.cols;
                let dst = r * self.cols;
                self.cells.copy_within(src..src + self.cols, dst);
                self.mark(r);
            }
            let last = self.scroll_bottom * self.cols;
            for c in 0..self.cols {
                self.cells[last + c] = Cell::default();
            }
            self.mark(self.scroll_bottom);
        }
    }

    pub fn scroll_down(&mut self, n: usize) {
        for _ in 0..n {
            for r in (self.scroll_top + 1..=self.scroll_bottom).rev() {
                let src = (r - 1) * self.cols;
                let dst = r * self.cols;
                self.cells.copy_within(src..src + self.cols, dst);
                self.mark(r);
            }
            let top = self.scroll_top * self.cols;
            for c in 0..self.cols {
                self.cells[top + c] = Cell::default();
            }
            self.mark(self.scroll_top);
        }
    }

    pub fn erase_in_display(&mut self, mode: u32) {
        match mode {
            0 => {
                for r in 0..self.rows {
                    for c in (if r == self.cursor_y { self.cursor_x } else { 0 })..self.cols {
                        *self.cell_at_mut(r, c) = Cell::default();
                    }
                    self.mark(r);
                }
            }
            1 => {
                for r in 0..self.rows {
                    // 防御：终点钳制到 cols，防 cursor_x 越界写穿行尾
                    let end = if r == self.cursor_y {
                        (self.cursor_x + 1).min(self.cols)
                    } else {
                        self.cols
                    };
                    for c in 0..end {
                        *self.cell_at_mut(r, c) = Cell::default();
                    }
                    self.mark(r);
                }
            }
            2 | 3 => {
                for r in 0..self.rows {
                    for c in 0..self.cols {
                        *self.cell_at_mut(r, c) = Cell::default();
                    }
                    self.mark(r);
                }
            }
            _ => {}
        }
    }

    pub fn erase_in_line(&mut self, mode: u32) {
        let y = self.cursor_y;
        match mode {
            0 => {
                for c in self.cursor_x..self.cols {
                    *self.cell_at_mut(y, c) = Cell::default();
                }
            }
            1 => {
                // 防御：cursor_x 理论上不应 >= cols（P0 已修），此处钳制兜底
                let end = self.cursor_x.min(self.cols.saturating_sub(1));
                for c in 0..=end {
                    *self.cell_at_mut(y, c) = Cell::default();
                }
            }
            2 => {
                for c in 0..self.cols {
                    *self.cell_at_mut(y, c) = Cell::default();
                }
            }
            _ => {}
        }
        self.mark(y);
    }

    pub fn set_cursor(&mut self, x: usize, y: usize) {
        self.cursor_x = x.min(self.cols.saturating_sub(1));
        self.cursor_y = y.min(self.rows - 1);
        self.pending_wrap = false;
    }

    pub fn move_cursor(&mut self, dx: i32, dy: i32) {
        let nx = self.cursor_x as i32 + dx;
        let ny = self.cursor_y as i32 + dy;
        self.cursor_x = nx.clamp(0, self.cols as i32 - 1) as usize;
        self.cursor_y = ny.clamp(0, self.rows as i32 - 1) as usize;
        self.pending_wrap = false;
    }

    pub fn save_cursor(&mut self) {
        self.saved_x = self.cursor_x;
        self.saved_y = self.cursor_y;
    }

    pub fn restore_cursor(&mut self) {
        // 双保险：恢复时再钳制一次（防外部直接改 saved_* 或历史状态残留）
        self.cursor_x = self.saved_x.min(self.cols.saturating_sub(1));
        self.cursor_y = self.saved_y.min(self.rows.saturating_sub(1));
        self.pending_wrap = false;
    }

    pub fn set_scroll_region(&mut self, top: usize, bottom: usize) {
        if top < bottom && bottom < self.rows {
            self.scroll_top = top;
            self.scroll_bottom = bottom;
            self.cursor_x = 0;
            self.cursor_y = top;
        }
    }

    /// 解析并应用 SGR（Select Graphic Rendition）参数。
    pub fn set_sgr(&mut self, params: &[u32]) {
        let mut i = 0;
        if params.is_empty() {
            self.cur_fg = 0;
            self.cur_bg = 0;
            self.cur_attrs = 0;
            return;
        }
        while i < params.len() {
            match params[i] {
                0 => {
                    self.cur_fg = 0;
                    self.cur_bg = 0;
                    self.cur_attrs = 0;
                }
                1 => self.cur_attrs |= ATTR_BOLD,
                2 => self.cur_attrs |= ATTR_DIM,
                3 => self.cur_attrs |= ATTR_ITALIC,
                4 => self.cur_attrs |= ATTR_UNDERLINE,
                5 => self.cur_attrs |= ATTR_BLINK,
                7 => self.cur_attrs |= ATTR_INVERSE,
                22 => self.cur_attrs &= !(ATTR_BOLD | ATTR_DIM),
                23 => self.cur_attrs &= !ATTR_ITALIC,
                24 => self.cur_attrs &= !ATTR_UNDERLINE,
                25 => self.cur_attrs &= !ATTR_BLINK,
                27 => self.cur_attrs &= !ATTR_INVERSE,
                30..=37 => self.cur_fg = ansi_8color(params[i] - 30),
                38 => {
                    if let Some((c, ni)) = parse_ext_color(params, i) {
                        self.cur_fg = c;
                        i = ni;
                    }
                }
                39 => self.cur_fg = 0,
                40..=47 => self.cur_bg = ansi_8color(params[i] - 40),
                48 => {
                    if let Some((c, ni)) = parse_ext_color(params, i) {
                        self.cur_bg = c;
                        i = ni;
                    }
                }
                49 => self.cur_bg = 0,
                90..=97 => self.cur_fg = ansi_8color_bright(params[i] - 90),
                100..=107 => self.cur_bg = ansi_8color_bright(params[i] - 100),
                _ => {}
            }
            i += 1;
        }
    }

    pub fn resize(&mut self, cols: usize, rows: usize) {
        let mut new_cells = vec![Cell::default(); cols * rows];
        let copy_r = self.rows.min(rows);
        let copy_c = self.cols.min(cols);
        for r in 0..copy_r {
            for c in 0..copy_c {
                new_cells[r * cols + c] = self.cells[r * self.cols + c];
            }
        }
        self.cells = new_cells;
        self.cols = cols;
        self.rows = rows;
        self.scroll_top = 0;
        self.scroll_bottom = rows - 1;
        self.cursor_x = self.cursor_x.min(cols.saturating_sub(1));
        self.cursor_y = self.cursor_y.min(rows.saturating_sub(1));
        // P1 修复：save/restore 的光标同样要随 resize 钳制，
        // 否则 ESC7 → 缩小 → ESC8 恢复出越界光标，后续写格 panic。
        self.saved_x = self.saved_x.min(cols.saturating_sub(1));
        self.saved_y = self.saved_y.min(rows.saturating_sub(1));
        self.pending_wrap = false;
        self.dirty.clear();
    }

    /// 把绝对行 [start_row, start_row+count) 的单元格复制到 `out`（每行 cols*16 字节）。
    /// 绝对行 0..history.len() 为历史，其后为可见屏幕。
    pub fn copy_cells(&self, start_row: usize, count: usize, out: &mut [u8]) {
        let total = self.history.len() + self.rows;
        let stride = self.cols * 16;
        for k in 0..count {
            let abs = start_row + k;
            if abs >= total {
                break;
            }
            let line: &[Cell] = if abs < self.history.len() {
                &self.history.get(abs).cells
            } else {
                let r = abs - self.history.len();
                &self.cells[r * self.cols..(r + 1) * self.cols]
            };
            let base = k * stride;
            if base + stride > out.len() {
                break;
            }
            for (c, cell) in line.iter().enumerate().take(self.cols) {
                let o = base + c * 16;
                out[o..o + 4].copy_from_slice(&cell.code.to_le_bytes());
                out[o + 4..o + 8].copy_from_slice(&cell.fg.to_le_bytes());
                out[o + 8..o + 12].copy_from_slice(&cell.bg.to_le_bytes());
                out[o + 12..o + 14].copy_from_slice(&cell.attrs.to_le_bytes());
            }
        }
    }

    pub fn total_rows(&self) -> usize {
        self.history.len() + self.rows
    }

    pub fn clear_dirty(&mut self) {
        self.dirty.clear();
    }
}

fn ansi_8color(idx: u32) -> u32 {
    let palette = [
        0x000000, 0xcd3131, 0x0dbc4c, 0xe5e510, 0x2472c8, 0xbc3fbc, 0x11a8cd, 0xe5e5e5,
    ];
    palette.get(idx as usize).copied().unwrap_or(0)
}

fn ansi_8color_bright(idx: u32) -> u32 {
    let palette = [
        0x666666, 0xf14c4c, 0x23d18b, 0xf5f543, 0x3b8eea, 0xd670d6, 0x29b8db, 0xffffff,
    ];
    palette.get(idx as usize).copied().unwrap_or(0)
}

/// 解析 38/48 后的扩展颜色（truecolor 或 256 色）。返回 (color, next_index)。
fn parse_ext_color(params: &[u32], i: usize) -> Option<(u32, usize)> {
    match params.get(i + 1).copied() {
        Some(2) => {
            // truecolor: 38;2;r;g;b
            let r = *params.get(i + 2)?;
            let g = *params.get(i + 3)?;
            let b = *params.get(i + 4)?;
            Some((((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff), i + 4))
        }
        Some(5) => {
            // 256-color palette index
            let idx = *params.get(i + 2)? as usize;
            Some((xterm_256(idx), i + 2))
        }
        _ => None,
    }
}

fn xterm_256(idx: usize) -> u32 {
    if idx < 16 {
        let p = if idx < 8 {
            [
                0x00, 0xcd, 0x0d, 0xe5, 0x24, 0xbc, 0x11, 0xe5,
            ]
        } else {
            [
                0x66, 0xf1, 0x23, 0xf5, 0x3b, 0xd6, 0x29, 0xff,
            ]
        };
        // 简化：8 色板重复亮/暗
        let base = idx % 8;
        (p[base] as u32) << 16 | ((p[base] as u32) << 8) | (p[base] as u32)
    } else if idx < 232 {
        let i = idx - 16;
        let r = ((i / 36) % 6) as u32;
        let g = ((i / 6) % 6) as u32;
        let b = (i % 6) as u32;
        let conv = |v: u32| if v == 0 { 0 } else { 55 + v * 40 };
        (conv(r) << 16) | (conv(g) << 8) | conv(b)
    } else {
        let v = (8 + (idx - 232) * 10) as u32;
        (v << 16) | (v << 8) | v
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// M5-1：初始化尺寸——网格、光标、滚动区域、历史均为初始态。
    #[test]
    fn init_dimensions() {
        let s = Screen::new(80, 24, 100);
        assert_eq!(s.cols, 80);
        assert_eq!(s.rows, 24);
        assert_eq!((s.cursor_x, s.cursor_y), (0, 0));
        assert_eq!(s.scroll_top, 0);
        assert_eq!(s.scroll_bottom, 23);
        assert_eq!(s.total_rows(), 24);
        assert!(s.history.is_empty());
        assert!(s.dirty.is_empty());
        // 顺带锚定 wcwidth：CJK 全角 2 列、ASCII 1 列
        assert_eq!(wcwidth(0x4e2d), 2);
        assert_eq!(wcwidth('A' as u32), 1);
    }

    /// M5-2：put_char 推进光标；写满一行后回绕到下一行行首；
    /// auto_wrap 关闭时钳制在行尾覆盖写入。
    #[test]
    fn put_char_advance_and_wrap() {
        let mut s = Screen::new(4, 3, 8);
        for c in ['A', 'B', 'C', 'D'] {
            s.put_char(c as u32);
        }
        // P0 修复后语义：行满光标停在最后一列 + pending_wrap=true（不再推到 cols 越界）
        assert_eq!(s.cursor_x, 3);
        assert!(s.pending_wrap);
        s.put_char('E' as u32); // 回绕
        assert_eq!(s.cell_at(0, 0).code, 'A' as u32);
        assert_eq!(s.cell_at(1, 0).code, 'E' as u32);
        assert_eq!(s.cursor_y, 1);
        // auto_wrap = false：不换行，光标钳制在最后一列
        let mut s2 = Screen::new(4, 3, 8);
        s2.auto_wrap = false;
        for c in ['A', 'B', 'C', 'D'] {
            s2.put_char(c as u32);
        }
        s2.put_char('E' as u32); // 超界 → 钳到 cols-w 覆盖 'D'
        assert_eq!(s2.cursor_y, 0);
        assert_eq!(s2.cell_at(0, 3).code, 'E' as u32);
    }

    /// M5-3：擦除——行擦（EL 0/1）与全屏擦（ED 2），并标脏行。
    #[test]
    fn erase_display_and_line() {
        let mut s = Screen::new(6, 3, 8);
        for _ in 0..18 {
            s.put_char('X' as u32);
        }
        s.set_cursor(1, 1);
        s.erase_in_line(0); // 光标 → 行尾
        assert_eq!(s.cell_at(1, 0).code, 'X' as u32);
        assert_eq!(s.cell_at(1, 1).code, 0);
        assert_eq!(s.cell_at(1, 5).code, 0); // EL0 已清掉右侧
        // 重填后验证 EL1：行首 → 光标（含）清空，光标右侧保留。
        s.set_cursor(0, 1);
        for _ in 0..6 {
            s.put_char('X' as u32);
        }
        s.set_cursor(1, 1);
        s.erase_in_line(1); // 行首 → 光标（含）
        assert_eq!(s.cell_at(1, 0).code, 0);
        assert_eq!(s.cell_at(1, 5).code, 'X' as u32); // 光标右侧保留
        s.set_cursor(0, 0);
        s.erase_in_display(2); // 全屏
        for r in 0..3 {
            for c in 0..6 {
                assert_eq!(s.cell_at(r, c).code, 0);
            }
        }
        assert!(!s.dirty.is_empty()); // 擦除过的行已标脏
    }

    /// M5-4：滚动上移——整屏滚动时首行进历史，可见内容整体上移一行。
    #[test]
    fn scroll_up_pushes_history() {
        let mut s = Screen::new(6, 3, 8);
        for c in "TOP".chars() {
            s.put_char(c as u32);
        }
        // 标准终端下 LF 不归列（LF≠CRLF），换行写新行需先 CR。
        s.carriage_return();
        s.line_feed();
        for c in "MID".chars() {
            s.put_char(c as u32);
        }
        assert!(s.history.is_empty());
        s.scroll_up(1);
        assert_eq!(s.history.len(), 1); // "TOP" 推进历史
        assert_eq!(s.history.get(0).cells[0].code, 'T' as u32);
        assert_eq!(s.cell_at(0, 0).code, 'M' as u32); // "MID" 上移到首行
        assert_eq!(s.cell_at(1, 0).code, 0); // 底部空行
    }

    /// M5-5：属性位保持——SGR 设置后写入的格子带 attrs/fg/bg；
    /// 复位后写入的格子不受先前样式影响。
    #[test]
    fn attrs_preserved_on_cells() {
        let mut s = Screen::new(10, 3, 8);
        s.set_sgr(&[1, 31]); // 粗体 + 前景红
        s.put_char('A' as u32);
        let c = s.cell_at(0, 0);
        assert_eq!(c.attrs & ATTR_BOLD, ATTR_BOLD);
        assert_eq!(c.fg, 0xcd3131);
        s.set_sgr(&[0, 44]); // 复位 + 背景蓝
        s.put_char('B' as u32);
        let c = s.cell_at(0, 1);
        assert_eq!(c.attrs, 0);
        assert_eq!(c.fg, 0);
        assert_eq!(c.bg, ansi_8color(4)); // palette[4] = 0x2472c8
    }

    /// M5-6：resize 保内容——左上角交集区域内容保留、新增区域空白、
    /// 光标钳制在界内、脏标记清空（实现即此行为：不重排，只保交集）。
    #[test]
    fn resize_keeps_content() {
        let mut s = Screen::new(10, 4, 8);
        for _ in 0..4 {
            s.put_char('H' as u32);
        }
        assert_eq!((s.cursor_x, s.cursor_y), (4, 0));
        s.resize(6, 5);
        assert_eq!(s.cols, 6);
        assert_eq!(s.rows, 5);
        assert_eq!(s.cell_at(0, 0).code, 'H' as u32);
        assert_eq!(s.cell_at(0, 3).code, 'H' as u32);
        assert_eq!(s.cell_at(0, 4).code, 0); // 新增列空白
        assert_eq!((s.cursor_x, s.cursor_y), (4, 0)); // 原光标仍在界内
        assert!(s.dirty.is_empty()); // resize 清脏标记（下一帧全量重绘）
    }
}

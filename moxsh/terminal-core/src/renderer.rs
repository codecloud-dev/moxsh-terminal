//! 渲染辅助（颜色/度量换算 + 脏区跟踪）。
//!
//! 屏幕的"像素绘制"在 Kotlin/Compose 层完成；本模块提供跨语言一致的颜色与
//! 度量换算，以及脏区域判定，避免 Kotlin 侧重复实现样式逻辑。
//!
//! ## 脏区跟踪（M7 提前项：[`DirtyTracker`]）
//!
//! Kotlin `TerminalView` 每帧（60/120Hz）调 `copyCells` 前先用它过滤，只对
//! 脏区间拷贝/重绘，静态行复用上一帧位图——滚动不刷全屏、空闲界面零拷贝，
//! 显著降低高刷屏的拷贝与绘制负载。标准用法（三步）：
//!
//! 1. **标脏**：上层（parser/session）对内容变化的行调 [`DirtyTracker::mark_row`]；
//!    全量变化（resize / alternate screen / RIS）调 [`DirtyTracker::mark_all`]。
//! 2. **每帧求脏区间**：
//!    `let ranges = tracker.compute_dirty_ranges(|r| fnv1a_row(r));`
//!    闭包返回第 r 行的 64 位指纹（推荐 FNV-1a，或 NEON 校验和——Android 真机
//!    可替换指纹实现而不改接口）。指纹与上一帧相同的行即使忘了 mark 也会被
//!    hash-diff 兜底跳过；显式 mark 与 hash-diff 取并集。
//! 3. **局部重绘**：只对返回的区间（升序、互不重叠的闭区间 `(start, end)`，
//!    含端点）逐行 `Screen::copy_cells`，并令 Kotlin 侧对应行位图失效重绘。
//!
//! `compute_dirty_ranges` 是消费式调用：返回区间的同时更新指纹基线并清空
//! 显式标脏，实例绑定一块 `Screen`（行数固定；resize 后重建实例）。
//! 零依赖（无 crate、无 unsafe）。

use crate::screen::Cell;

/// 默认前景/背景（与 Kotlin 侧主题一致）。
pub const DEFAULT_FG: u32 = 0xe6e6e6;
pub const DEFAULT_BG: u32 = 0x000000;

/// 把 Cell 的前景色转为 ARGB（含 inverse 处理）。
pub fn fg_argb(cell: &Cell) -> u32 {
    let raw = if cell.fg == 0 { DEFAULT_FG } else { cell.fg };
    if cell.attrs & crate::screen::ATTR_INVERSE != 0 {
        let bg = if cell.bg == 0 { DEFAULT_BG } else { cell.bg };
        return bg | 0xff000000;
    }
    raw | 0xff000000
}

/// 把 Cell 的背景色转为 ARGB。
pub fn bg_argb(cell: &Cell) -> u32 {
    let raw = if cell.bg == 0 { DEFAULT_BG } else { cell.bg };
    if cell.attrs & crate::screen::ATTR_INVERSE != 0 {
        let fg = if cell.fg == 0 { DEFAULT_FG } else { cell.fg };
        return fg | 0xff000000;
    }
    raw | 0xff000000
}

/// 是否加粗（bold/dim 组合近似）。
pub fn is_bold(cell: &Cell) -> bool {
    cell.attrs & crate::screen::ATTR_BOLD != 0
}

/// 把 RGB 0xRRGGBB 转为 ARGB 0xAARRGGBB（带 alpha）。
pub fn rgb_to_argb(rgb: u32, alpha: u8) -> u32 {
    (rgb & 0x00ffffff) | ((alpha as u32) << 24)
}

/// 脏区跟踪器：显式标脏 ∪ 逐行指纹 diff，输出合并后的脏行区间。
///
/// 字段说明：
/// - `rows`：显式标脏的行（parser 标记路径，行号 = 可见屏行号）。
/// - `all`：全脏标志（resize / alternate screen 等全量变化）。
/// - `last_frame_hash`：上一帧各行 64 位指纹基线；首帧为空 → 视为全脏。
pub struct DirtyTracker {
    rows: Vec<bool>,
    all: bool,
    last_frame_hash: Vec<u64>,
}

impl DirtyTracker {
    /// 新建跟踪器（`row_count` = Screen.rows；resize 后请重建）。
    pub fn new(row_count: usize) -> DirtyTracker {
        DirtyTracker {
            rows: vec![false; row_count],
            all: false,
            last_frame_hash: Vec::new(),
        }
    }

    /// 标记第 r 行脏（越界忽略——上层行号先于 tracker 重建到达时静默丢弃）。
    pub fn mark_row(&mut self, r: usize) {
        if r < self.rows.len() {
            self.rows[r] = true;
        }
    }

    /// 标记全屏脏（resize / alternate screen 切换 / RIS 等全量变化）。
    pub fn mark_all(&mut self) {
        self.all = true;
    }

    /// 清除所有显式脏标记。指纹基线保留——hash-diff 兜底不受影响。
    pub fn clear(&mut self) {
        for v in self.rows.iter_mut() {
            *v = false;
        }
        self.all = false;
    }

    /// 计算本轮脏行区间并推进帧基线（消费式：标脏与指纹基线一并更新）。
    ///
    /// `hash_row(r)` 由调用方提供第 r 行的 64 位指纹（FNV-1a / NEON 校验和）。
    /// 判脏规则：显式 mark ∪ 指纹与上一帧不同；首帧（无有效基线）全脏。
    /// 返回升序、互不重叠的闭区间 `(start, end)`（含端点）。
    pub fn compute_dirty_ranges<F>(&mut self, mut hash_row: F) -> Vec<(usize, usize)>
    where
        F: FnMut(usize) -> u64,
    {
        let n = self.rows.len();
        if n == 0 {
            return Vec::new();
        }
        // 首帧或行数变化：无有效基线 → 全脏，并重建占位基线（保证下标安全）。
        if self.last_frame_hash.len() != n {
            self.last_frame_hash = vec![0; n];
            self.all = true;
        }
        let mut dirty = vec![false; n];
        let mut new_hash = Vec::with_capacity(n);
        for (r, d) in dirty.iter_mut().enumerate() {
            let h = hash_row(r);
            new_hash.push(h);
            // 全脏短路；否则显式标脏与 hash-diff 取并集。
            *d = self.all || self.rows[r] || self.last_frame_hash[r] != h;
        }
        self.last_frame_hash = new_hash;
        self.rows = vec![false; n];
        self.all = false;

        // 合并连续脏行为区间。
        let mut out = Vec::new();
        let mut r = 0;
        while r < n {
            if dirty[r] {
                let start = r;
                while r + 1 < n && dirty[r + 1] {
                    r += 1;
                }
                out.push((start, r));
            }
            r += 1;
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// FNV-1a 64 位指纹（真实调用方的参考实现：对一行的 copyCells 字节算）。
    fn fnv1a(bytes: &[u8]) -> u64 {
        let mut h: u64 = 0xcbf29ce484222325;
        for &b in bytes {
            h ^= b as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        h
    }

    /// 全脏：首帧无基线视为全脏；mark_all 同样整体脏——均输出单一覆盖区间。
    #[test]
    fn all_dirty_single_range() {
        let mut t = DirtyTracker::new(4);
        let r = t.compute_dirty_ranges(|r| fnv1a(&[r as u8]));
        assert_eq!(r, vec![(0, 3)]); // 首帧全脏
        t.mark_all();
        let r = t.compute_dirty_ranges(|r| fnv1a(&[r as u8]));
        assert_eq!(r, vec![(0, 3)]); // mark_all 全脏
    }

    /// 无脏：内容与基线一致且无显式标脏 → 空区间（Kotlin 侧整帧零拷贝）。
    #[test]
    fn no_dirty_when_unchanged() {
        let mut t = DirtyTracker::new(4);
        t.compute_dirty_ranges(|r| fnv1a(&[r as u8])); // 建立基线
        let r = t.compute_dirty_ranges(|r| fnv1a(&[r as u8]));
        assert!(r.is_empty());
    }

    /// 区间合并：连续标脏行（1,2,3）合并为一个区间，避免逐行碎片拷贝。
    #[test]
    fn adjacent_marks_merge_into_range() {
        let mut t = DirtyTracker::new(8);
        t.compute_dirty_ranges(|r| fnv1a(&[r as u8])); // 基线
        t.mark_row(1);
        t.mark_row(2);
        t.mark_row(3);
        let r = t.compute_dirty_ranges(|r| fnv1a(&[r as u8]));
        assert_eq!(r, vec![(1, 3)]);
    }

    /// 部分行：mark 与 hash-diff 取并集——第 0 行显式标脏、第 2 行指纹变化，
    /// 其余行（指纹不变、未标脏）被跳过。
    #[test]
    fn partial_rows_mark_plus_hash_diff() {
        let mut t = DirtyTracker::new(4);
        t.compute_dirty_ranges(|_| 0); // 基线：全 0 指纹
        t.mark_row(0);
        let r = t.compute_dirty_ranges(|r| if r == 2 { 0xdead } else { 0 });
        assert_eq!(r, vec![(0, 0), (2, 2)]);
    }
}

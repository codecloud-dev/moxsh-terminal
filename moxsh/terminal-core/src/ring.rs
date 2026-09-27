//! 固定容量环形缓冲（用于屏幕历史回滚）。
//!
//! 仅保留最近 `cap` 项；满后覆盖最老项。逻辑索引 0 始终是最老项，
//! 便于渲染层按绝对行号访问历史。
//!
//! 另含 [`OverflowBuffer`]：内存+落盘二级缓冲（M5 任务：回滚超限 mmap 兜底防 OOM）。
//! 屏幕网格走 [`RingBuffer`]，超大历史的原始字节流走 [`OverflowBuffer`]。

use std::fs::OpenOptions;
use std::io;
use std::os::unix::io::AsRawFd;

pub struct RingBuffer<T> {
    buf: Vec<Option<T>>,
    cap: usize,
    head: usize,
    len: usize,
}

impl<T> RingBuffer<T> {
    pub fn new(cap: usize) -> RingBuffer<T> {
        let mut buf = Vec::with_capacity(cap);
        for _ in 0..cap {
            buf.push(None);
        }
        RingBuffer {
            buf,
            cap,
            head: 0,
            len: 0,
        }
    }

    /// 追加一项；满时覆盖最老项。
    pub fn push(&mut self, item: T) {
        if self.cap == 0 {
            return;
        }
        if self.len < self.cap {
            let i = (self.head + self.len) % self.cap;
            self.buf[i] = Some(item);
            self.len += 1;
        } else {
            self.buf[self.head] = Some(item);
            self.head = (self.head + 1) % self.cap;
        }
    }

    /// 按逻辑索引（0=最老）取项。
    pub fn get(&self, idx: usize) -> &T {
        let i = if self.len < self.cap {
            idx
        } else {
            (self.head + idx) % self.cap
        };
        self.buf[i].as_ref().expect("ring slot populated")
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn push_and_get() {
        let mut r = RingBuffer::new(3);
        r.push(1);
        r.push(2);
        r.push(3);
        assert_eq!(r.len(), 3);
        assert_eq!(*r.get(0), 1);
        assert_eq!(*r.get(2), 3);
        // 覆盖最老
        r.push(4);
        assert_eq!(*r.get(0), 2);
        assert_eq!(*r.get(2), 4);
    }

    #[test]
    fn overflow_buffer_mem_only() {
        let mut b = OverflowBuffer::new(1024, std::env::temp_dir().join("moxsh-ring-test-mem")).unwrap();
        b.push(b"hello world").unwrap();
        let mut out = [0u8; 5];
        assert_eq!(b.read_at(6, &mut out).unwrap(), 5);
        assert_eq!(&out, b"world");
        assert_eq!(b.total_len(), 11);
    }

    #[test]
    fn overflow_buffer_spills_to_mmap() {
        let path = std::env::temp_dir().join("moxsh-ring-test-spill");
        let mut b = OverflowBuffer::new(8, path).unwrap();
        // 超过内存上限 8 字节，触发落盘兜底
        b.push(b"0123456789ABCDEF").unwrap();
        assert!(b.spilled());
        assert_eq!(b.total_len(), 16);
        let mut out = [0u8; 16];
        assert_eq!(b.read_at(0, &mut out).unwrap(), 16);
        assert_eq!(&out, b"0123456789ABCDEF");
        // 继续追加仍正确
        b.push(b"!").unwrap();
        let mut tail = [0u8; 1];
        assert_eq!(b.read_at(16, &mut tail).unwrap(), 1);
        assert_eq!(tail[0], b'!');
    }

    /// M5-补1：mem_only 阶段写入后触发落盘，跨"原内存段"的中部偏移读取仍正确。
    #[test]
    fn overflow_buffer_read_mid_after_spill() {
        let path = std::env::temp_dir().join("moxsh-ring-test-mid");
        let mut b = OverflowBuffer::new(16, path).unwrap();
        b.push(b"0123456789").unwrap(); // 10 字节，仍在内存
        assert!(!b.spilled());
        b.push(b"ABCDEFGHIJKLMNOP").unwrap(); // 10+16 > 16 → 整体搬移落盘
        assert!(b.spilled());
        assert_eq!(b.total_len(), 26);
        // 中部偏移 12..18（原内存段内）内容无损坏
        let mut out = [0u8; 6];
        assert_eq!(b.read_at(12, &mut out).unwrap(), 6);
        assert_eq!(&out, b"CDEFGH");
    }

    /// M5-补2：sync 在未落盘（no-op）与已落盘（msync）两种状态下都不 panic。
    #[test]
    fn overflow_buffer_sync_ok() {
        let path = std::env::temp_dir().join("moxsh-ring-test-sync");
        let mut b = OverflowBuffer::new(4, path).unwrap();
        b.push(b"abc").unwrap(); // 仍在内存
        assert!(b.sync().is_ok()); // 无映射 → no-op Ok
        b.push(b"defghi").unwrap(); // 3+6 > 4 → 触发落盘
        assert!(b.spilled());
        assert!(b.sync().is_ok()); // msync MS_ASYNC 不 panic、返回 Ok
    }

    /// M5-补3：drop 后落盘的 mmap 文件被清理（会话关闭即释放磁盘）。
    #[test]
    fn overflow_buffer_drop_cleans_file() {
        let path = std::env::temp_dir().join("moxsh-ring-test-drop");
        {
            let mut b = OverflowBuffer::new(4, path.clone()).unwrap();
            b.push(b"0123456789").unwrap(); // 触发落盘，创建 mmap 文件
            assert!(b.spilled());
            assert!(path.exists(), "落盘后 mmap 文件应存在");
        } // 此处 drop
        assert!(!path.exists(), "drop 后落盘文件应被清理");
    }
}

/// 内存 + 落盘二级字节缓冲（回滚兜底，M5）。
///
/// 前段保留在内存（`mem_cap` 字节）；超过上限后整段搬移到 mmap 文件，
/// 之后所有追加都写 mmap。收益：超大输出（如 `cat 大文件`、编译日志）不会把
/// 进程 RSS 撑爆——页面由内核按需换入换出，OOM 风险转移为磁盘占用。
///
/// 线程约定：非 Send/Sync（内含裸指针），与 [`crate::session::Session`] 同线程使用
/// （PTY 读取与回滚写入在同一 pump 循环内）。
pub struct OverflowBuffer {
    mem_cap: usize,
    mem: Vec<u8>,
    /// mmap 区域（落盘后有效）；None = 尚未触发落盘。
    map: Option<MmapArea>,
    path: std::path::PathBuf,
    /// 已写入总字节数（= 逻辑长度）。
    written: usize,
}

struct MmapArea {
    file: std::fs::File,
    ptr: *mut u8,
    len: usize,
}

// mmap 区域等价于独占文件映射：单线程持有，允许跨 unsafe 边界移动。
unsafe impl Send for OverflowBuffer {}

impl OverflowBuffer {
    /// 创建缓冲：`mem_cap` 字节内存上限；`path` 为落盘 mmap 文件路径（父目录需存在）。
    pub fn new(mem_cap: usize, path: std::path::PathBuf) -> io::Result<OverflowBuffer> {
        Ok(OverflowBuffer {
            mem_cap: mem_cap.max(1),
            mem: Vec::with_capacity(mem_cap.max(1)),
            map: None,
            path,
            written: 0,
        })
    }

    /// 是否已触发落盘兜底。
    pub fn spilled(&self) -> bool {
        self.map.is_some()
    }

    /// 当前逻辑总长度（字节）。
    pub fn total_len(&self) -> usize {
        self.written
    }

    /// 追加字节；内存满则整体落盘，之后直接写 mmap。
    pub fn push(&mut self, data: &[u8]) -> io::Result<()> {
        // 1) 首次超限：内存段整体升级为 mmap 区域。
        //    先建映射、搬家，再挂到 self.map——避免 &mut self.map 与 create_map(&self) 借用重叠。
        if self.map.is_none() && self.mem.len() + data.len() > self.mem_cap {
            let need = self.mem.len() + data.len();
            let grow = (need * 2).max(1024 * 1024); // 翻倍增长，最少 1 MiB，减少重映射次数
            let mut area = self.create_map(grow)?;
            unsafe {
                std::ptr::copy_nonoverlapping(self.mem.as_ptr(), area.ptr, self.mem.len());
            }
            self.mem = Vec::new(); // 内存段已搬家
            self.map = Some(area);
        }

        if self.map.is_some() {
            let end = self.written + data.len();
            // 2) mmap 空间不足：先建新映射，再换旧映射（同样规避借用冲突）。
            //    Android 无 mremap，用"新建映射 + munmap 旧映射"替代，扩容是低频事件，代价可接受。
            if end > self.map.as_ref().unwrap().len {
                let new_area = self.create_map((end * 2).max(1024 * 1024))?;
                let old = self.map.replace(new_area).unwrap();
                unsafe {
                    libc::munmap(old.ptr as *mut libc::c_void, old.len);
                }
                // 旧 fd（old.file）随 old 离开作用域自动关闭
            }
            let area = self.map.as_ref().unwrap();
            unsafe {
                std::ptr::copy_nonoverlapping(data.as_ptr(), area.ptr.add(self.written), data.len());
            }
            self.written = end;
        } else {
            self.mem.extend_from_slice(data);
            self.written += data.len();
        }
        Ok(())
    }

    /// 从逻辑偏移 `offset` 读取至多 `out.len()` 字节，返回实际读取数。
    pub fn read_at(&self, offset: usize, out: &mut [u8]) -> io::Result<usize> {
        if offset >= self.written {
            return Ok(0);
        }
        let n = out.len().min(self.written - offset);
        if let Some(area) = &self.map {
            unsafe {
                std::ptr::copy_nonoverlapping(area.ptr.add(offset), out.as_mut_ptr(), n);
            }
        } else {
            out[..n].copy_from_slice(&self.mem[offset..offset + n]);
        }
        Ok(n)
    }

    /// 刷盘（低频调用：会话空闲/切后台时由上层触发；内核页缓存本就异步回写）。
    pub fn sync(&self) -> io::Result<()> {
        if let Some(area) = &self.map {
            if unsafe { libc::msync(area.ptr as *mut libc::c_void, area.len, libc::MS_ASYNC) } != 0 {
                return Err(io::Error::last_os_error());
            }
        }
        Ok(())
    }

    /// 创建可写共享匿名外的文件映射：先建文件并 ftruncate 到 `len`。
    fn create_map(&self, len: usize) -> io::Result<MmapArea> {
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&self.path)?;
        file.set_len(len as u64)?;
        let ptr = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                file.as_raw_fd(),
                0,
            )
        };
        if ptr == libc::MAP_FAILED {
            return Err(io::Error::last_os_error());
        }
        Ok(MmapArea {
            file,
            ptr: ptr as *mut u8,
            len,
        })
    }
}

impl Drop for OverflowBuffer {
    fn drop(&mut self) {
        if let Some(area) = self.map.take() {
            unsafe {
                libc::munmap(area.ptr as *mut libc::c_void, area.len);
            }
        }
        // 清理落盘文件（会话关闭即释放磁盘）
        let _ = std::fs::remove_file(&self.path);
    }
}

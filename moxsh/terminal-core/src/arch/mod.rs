//! 热路径汇编封装（UTF-8 解码 / FNV 校验和）。
//!
//! 仅在 `aarch64-linux-android` 目标下编译 `arch/neon.s` 并把 `has_neon_asm` 置位，
//! 此时批量解码走 arm64 汇编；其他目标（含本机 `cargo test`）回退纯 Rust 实现，
//! 保证内核可在任意平台编译与单测。

#[cfg(has_neon_asm)]
extern "C" {
    fn moxsh_neon_utf8_decode(src: *const u8, len: usize, out: *mut u32, max: usize) -> usize;
    fn moxsh_neon_fnv1a(data: *const u8, len: usize) -> u64;
}

/// 批量把 UTF-8 字节流解码为码点序列。返回写入 `out` 的码点数。
pub fn decode_utf8_block(src: &[u8], out: &mut [u32]) -> usize {
    #[cfg(has_neon_asm)]
    unsafe {
        moxsh_neon_utf8_decode(src.as_ptr(), src.len(), out.as_mut_ptr(), out.len())
    }
    #[cfg(not(has_neon_asm))]
    {
        let mut i = 0;
        let mut n = 0;
        while i < src.len() && n < out.len() {
            // next_codepoint 内部推进 i；None 表示缓冲不足/流结束。
            match next_codepoint(src, &mut i) {
                Some(cp) => {
                    out[n] = cp;
                    n += 1;
                }
                None => break,
            }
        }
        n
    }
}

/// FNV-1a 64 位哈希（用于缓冲区/校验和快速指纹）。
pub fn fnv1a(data: &[u8]) -> u64 {
    #[cfg(has_neon_asm)]
    unsafe {
        moxsh_neon_fnv1a(data.as_ptr(), data.len())
    }
    #[cfg(not(has_neon_asm))]
    {
        let mut h: u64 = 0xcbf29ce484222325;
        for &b in data {
            h ^= b as u64;
            h = h.wrapping_mul(0x100000001b3);
        }
        h
    }
}

/// 纯 Rust 增量 UTF-8 解码：从 `buf[pos..]` 读一个码点，推进 `pos`，返回码点。
/// 用于 VT 解析器逐字节消费（[`crate::parser`]）。
pub fn next_codepoint(buf: &[u8], pos: &mut usize) -> Option<u32> {
    if *pos >= buf.len() {
        return None;
    }
    let b0 = buf[*pos];
    let (cp, len) = if b0 < 0x80 {
        (b0 as u32, 1)
    } else if b0 >> 5 == 0b110 {
        (b0 as u32 & 0x1f, 2)
    } else if b0 >> 4 == 0b1110 {
        (b0 as u32 & 0x0f, 3)
    } else if b0 >> 3 == 0b11110 {
        (b0 as u32 & 0x07, 4)
    } else {
        // 非法首字节：跳过
        *pos += 1;
        return Some(0xfffd);
    };
    if *pos + len > buf.len() {
        return None;
    }
    let mut code = cp;
    for k in 1..len {
        let b = buf[*pos + k];
        if b >> 6 != 0b10 {
            *pos += 1;
            return Some(0xfffd);
        }
        code = (code << 6) | (b as u32 & 0x3f);
    }
    *pos += len;
    // P3 修复：拒绝 overlong 编码、UTF-16 代理区、超界码点——
    // 非法序列统一按 1 字节消费并返回 U+FFFD（替换符），防乱码直写 Cell。
    let valid = match len {
        1 => true,
        2 => code >= 0x80,
        3 => code >= 0x800 && !(0xd800..=0xdfff).contains(&code),
        4 => code >= 0x1_0000 && code <= 0x10_ffff,
        _ => false,
    };
    if !valid {
        return Some(0xfffd);
    }
    Some(code)
}

/// 尝试把整段 `bytes` 解码为单个码点（完整序列时返回 Some）。
pub fn utf8_complete(bytes: &[u8]) -> Option<u32> {
    let mut pos = 0;
    let cp = next_codepoint(bytes, &mut pos)?;
    if pos == bytes.len() {
        Some(cp)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_roundtrip() {
        let src = "你好，moxsh 🦀".as_bytes();
        let mut out = vec![0u32; src.len()];
        let n = decode_utf8_block(src, &mut out);
        let s: String = out[..n].iter().map(|&c| char::from_u32(c).unwrap_or('?')).collect();
        assert_eq!(s, "你好，moxsh 🦀");
    }
}

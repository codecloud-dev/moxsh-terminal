//! moxsh-terminal-core 真实微基准（零外部依赖，仅 std::time::Instant）。
//!
//! 背景：本 crate 承载终端最热的三条路径——VT 转义解析（每次 pump）、
//! 回滚环形缓冲（每次滚屏）、SHA-256/HMAC（每次 .mox 验签 / IPC 握手），
//! 但仓库此前**没有任何 benchmark**，README 的「性能打磨」也缺少真实数字支撑。
//! 本程序真跑这些路径并输出可复现的吞吐数据：
//!
//! ```text
//! cargo run --release --example benchmark -- 20
//! ```
//!
//! 数值随机器负载 / 编译器优化等级波动，写进 CHANGELOG 时须标注实测环境，
//! 不得当作固定 SLA。

use std::hint::black_box;
use std::time::Instant;

use moxshcore::crypto::{hmac_sha256, sha256};
use moxshcore::parser::Parser;
use moxshcore::ring::{OverflowBuffer, RingBuffer};
use moxshcore::screen::{wcwidth, Screen};

fn throughput_mb(bytes: usize, d: std::time::Duration) -> f64 {
    (bytes as f64 / (1024.0 * 1024.0)) / d.as_secs_f64()
}

/// 生成约 `target` 字节的混合 VT 流：ASCII / 中文 / emoji / SGR / 光标移动 / EL。
fn mixed_vt_stream(target: usize) -> Vec<u8> {
    let pieces: [&[u8]; 8] = [
        b"the quick brown fox jumps over the lazy dog ",
        "中文测试".as_bytes(),
        "😀🚀".as_bytes(),
        b"\x1b[1;31m",
        b"\x1b[0m",
        b"\x1b[10;20H",
        b"\x1b[K",
        b"\r\n",
    ];
    let mut v = Vec::with_capacity(target + 64);
    while v.len() < target {
        for p in pieces {
            if v.len() >= target {
                break;
            }
            v.extend_from_slice(p);
        }
    }
    v.truncate(target);
    v
}

fn main() {
    let iters: usize = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .filter(|&n| n > 0)
        .unwrap_or(20);
    println!("=== moxsh-terminal-core 真实微基准 (iterations={}) ===", iters);
    println!("（release 构建；数值随机器负载波动，非固定 SLA）\n");

    // 1) SHA-256：包管理 / IPC 验签热路径。
    {
        let data = vec![b'a'; 1024 * 1024];
        let t = Instant::now();
        let mut sink = [0u8; 32];
        for _ in 0..iters {
            sink = sha256(black_box(&data));
        }
        let d = t.elapsed();
        let bytes = data.len() * iters;
        println!(
            "[1] SHA-256      1 MiB x {:<3} = {:>10.3?}  {:>8.2} MB/s   (sink={:02x?})",
            iters,
            d,
            throughput_mb(bytes, d),
            &sink[..4]
        );
    }

    // 2) HMAC-SHA256：.mox 插件验签热路径。
    {
        let key = b"moxsh-benchmark-secret-key-0123456789";
        let data = vec![b'x'; 64 * 1024];
        let t = Instant::now();
        let mut sink = [0u8; 32];
        for _ in 0..iters {
            sink = hmac_sha256(black_box(key), black_box(&data));
        }
        let d = t.elapsed();
        let bytes = data.len() * iters;
        println!(
            "[2] HMAC-SHA256 64 KiB x {:<3} = {:>10.3?}  {:>8.2} MB/s   (sink={:02x?})",
            iters,
            d,
            throughput_mb(bytes, d),
            &sink[..4]
        );
    }

    // 3) VT 解析：pump 热路径（混合 ASCII / UTF-8 / CSI）。
    {
        let target = 2 * 1024 * 1024;
        let stream = mixed_vt_stream(target);
        let mut screen = Screen::new(120, 40, 1_000);
        let mut parser = Parser::new();
        let t = Instant::now();
        for _ in 0..iters {
            parser.feed(&mut screen, black_box(&stream));
        }
        let d = t.elapsed();
        let bytes = stream.len() * iters;
        println!(
            "[3] VT 解析      2 MiB x {:<3} = {:>10.3?}  {:>8.2} MB/s   (rows={})",
            iters,
            d,
            throughput_mb(bytes, d),
            screen.total_rows()
        );
    }

    // 4) wcwidth：中文 / emoji 混排时的逐格宽度查询。
    {
        let cps: Vec<u32> = (0..100_000u32)
            .map(|i| match i % 5 {
                0 => 'A' as u32,
                1 => 0x4e2d,  // 中（东亚宽）
                2 => 0x1f600, // 😀（emoji 宽）
                3 => 0x0301,  // 组合符（零宽）
                _ => 0x25cf,  // ●（窄）
            })
            .collect();
        let t = Instant::now();
        let mut sum = 0u64;
        for _ in 0..iters {
            for &c in &cps {
                sum += wcwidth(black_box(c)) as u64;
            }
        }
        let d = t.elapsed();
        println!(
            "[4] wcwidth     100k x {:<3} = {:>10.3?}  {:>8.0} K ops/s (sum={})",
            iters,
            d,
            (cps.len() * iters) as f64 / d.as_secs_f64() / 1000.0,
            sum
        );
    }

    // 5) RingBuffer：历史行环形缓冲 push + get（每轮覆盖一遍）。
    {
        let mut r = RingBuffer::<usize>::new(10_000);
        let t = Instant::now();
        let mut sum = 0usize;
        for _ in 0..iters {
            for i in 0..10_000 {
                r.push(black_box(i));
            }
            for i in 0..10_000 {
                sum += black_box(*r.get(i));
            }
        }
        let d = t.elapsed();
        println!(
            "[5] RingBuffer  10k x {:<3} = {:>10.3?}  {:>8.0} K ops/s (sum={})",
            iters,
            d,
            (10_000 * iters) as f64 / d.as_secs_f64() / 1000.0,
            sum
        );
    }

    // 6) OverflowBuffer：超大回滚 mmap 落盘 + 尾部回读（触发 spill 与扩容）。
    {
        let path = std::env::temp_dir().join(format!(
            "moxsh-bench-overflow-{}",
            std::process::id()
        ));
        let mut b = OverflowBuffer::new(1024 * 1024, path).unwrap();
        let payload = vec![b'x'; 4096];
        let rounds = iters * 512; // 触发首次 spill 与后续多次 mmap 扩容
        let t = Instant::now();
        for _ in 0..rounds {
            b.push(black_box(&payload)).unwrap();
        }
        let write_d = t.elapsed();
        let mut out = [0u8; 4096];
        let t2 = Instant::now();
        let n = b.read_at(b.total_len() - 4096, &mut out).unwrap();
        let read_d = t2.elapsed();
        let bytes = payload.len() * rounds;
        println!(
            "[6] OverflowBuf {:>4} MiB x {:<3} = write {:>10.3?} ({:>7.2} MB/s), read {}B in {:?}, spilled={}",
            bytes / (1024 * 1024),
            iters,
            write_d,
            throughput_mb(bytes, write_d),
            n,
            read_d,
            b.spilled()
        );
        let _ = b.sync();
    }

    println!("\n=== 基准结束（所有数字均为本机实测，非估算）===");
}

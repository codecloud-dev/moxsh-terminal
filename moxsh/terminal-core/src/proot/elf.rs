//! ELF / shebang 解析与 execve 链解析（guest 解释器问题的基础件）。
//!
//! # 问题背景（为什么需要这个模块）
//!
//! 动态链接 guest 二进制的 `PT_INTERP`（musl 的 `/lib/ld-musl-x86_64.so.1`、
//! glibc 的 `/lib64/ld-linux-x86-64.so.2`）由**内核在 execve 内部**打开——
//! ptrace syscall 拦截发生在内核加载器之前，翻译不到它。宿主（Termux/bionic）
//! 没有对应 loader 时，guest 二进制必然 ENOENT。这是 proot 类工具的核心难题。
//!
//! # 上游方案及其缺陷（读码所得，不抄）
//!
//! 上游 proot 的做法（`src/execve/enter.c` + `src/loader/loader.c`）：
//! 1. 把 execve 改写为执行 **proot 自带的 loader ELF**（运行时从二进制里
//!    extract 到临时文件，或 `$PROOT_LOADER` 指定）；
//! 2. tracer 把一份"load script"（open/mmap/补 auxv/跳转指令流）写进 guest
//!    栈，loader 按脚本手动映射 guest 二进制与解释器的 PT_LOAD 段；
//! 3. loader 的 syscall 全部对 tracer 掩盲（`ignore_loader_syscalls`），
//!    收尾用一次伪造的 `execve` 通知 tracer「加载完成」。
//!
//! 缺陷清单（本模块要规避的）：
//! - **运行时落盘第二个 ELF**：临时文件 + `fchmod 0755` 全局可执行，
//!   安全面扩大；Android 上还多一层 SELinux/W^X 约束；
//! - **每架构一套汇编**（x86/x86_64/arm/arm64 四个 assembly 头 +
//!   arm 的 `MMAP_OFFSET_SHIFT` 怪癖），维护面与出错面成正比；
//! - **load script 协议 + 通知 execve** 是两个独立组件间的隐式契约，
//!   任一侧改动即静默破坏；
//! - **ASLR 是模拟的**（`INTERP_PIC_ADDRESS` 半固定地址），不是真随机；
//! - **loader 手工转录 PT_LOAD 映射**，IFUNC/静态 TLS/RELRO 边界等 ABI
//!   细节靠"抄内核行为"，漏一项就是静默内存损坏。
//!
//! # 本模块的升级方案：显式解释器调用（qemu-user 同款机制）
//!
//! 不造第二个 ELF，而是把 execve 改写为**显式调用 guest 自带的 loader**：
//!
//! ```text
//! execve("/bin/busybox", ["busybox","sh"])
//!   ⇒ execve("<rootfs>/lib/ld-musl-x86_64.so.1",
//!            ["/lib/ld-musl-x86_64.so.1", "/bin/busybox", "busybox","sh"])
//! ```
//!
//! musl/glibc 的 loader 都是 standalone（无 PT_INTERP）静态 ELF，内核可直接
//! 加载；随后 loader 打开 guest 二进制与其依赖库——这些 openat 全部落在
//! 我们的 syscall 翻译链上，天然正确。升级点：
//! - **零第二 ELF / 零临时文件 / 零汇编 / 零协议**：loader 就是发行版自己
//!   那个久经考验的 loader，重定位/IFUNC/TLS 全链路由它亲自完成；
//! - **真 ASLR**：内核映射 loader 用真随机基址，loader 映射程序同样；
//! - **shebang 与 ELF interp 统一**：上游是两套机制（expand_shebang +
//!   loader），这里一次链式走查同时解决（脚本的解释器若也是动态 ELF，
//!   链继续往上走，直到 standalone ELF 为止）。

use std::fs;
use std::path::{Path, PathBuf};

use crate::proot::translate::TranslateRules;
use crate::proot::ProotResult;

/// execve 链走查的深度上限（解释器链 a→b→c…的病态或成环 rootfs 防御）。
const MAX_CHAIN_DEPTH: usize = 8;

/// PT_INTERP 字符串长度上限（内核 `ELF_MIN_INTERP_SIZE` 同数量级，防御野头）。
const MAX_INTERP_LEN: usize = 256;

/// shebang 行读取上限（内核 `BINPRM_BUF_SIZE` = 256）。
const SHEBANG_BUF: usize = 256;

/// ELF 头解析结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ElfInfo {
    /// 文件是否以 ELF 魔数开头（且字节序/类别可解析）。
    pub is_elf: bool,
    /// `PT_INTERP` 字符串（guest 视角路径）；静态 ELF 为 `None`。
    pub interp: Option<String>,
}

impl ElfInfo {
    /// 非 ELF 的便捷构造（脚本/数据文件/读取失败）。
    fn not_elf() -> Self {
        ElfInfo { is_elf: false, interp: None }
    }
}

/// shebang 解析结果（内核语义：`#!interp [arg]`，arg 至多一个）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Shebang {
    /// 解释器路径（guest 视角）。
    pub interp: String,
    /// 可选的单个参数（内核原样传递，不再切分）。
    pub arg: Option<String>,
}

/// execve 处置计划。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ExecPlan {
    /// 原样执行（静态 ELF / 非 ELF / 解析失败）——走既有路径翻译即可。
    Direct,
    /// 改写为显式调用 guest loader：
    /// - `exec_host`：内核真正 exec 的宿主路径（standalone loader）；
    /// - `argv_prefix`：插在原 argv 之前的 guest 视角参数，**不含 loader
    ///   自身**（`ld.so [OPTION]... EXECUTABLE [ARGS...]` 语义：argv[1]
    ///   必须是链上第一个程序）。调用方拼最终 argv：
    ///   `[exec_host] + argv_prefix + 原参数[1..]`。
    ///   ELF interp 场景 = `[prog_guest]`；
    ///   shebang 场景 = `[interp_guest, arg?, script_guest]`。
    Rewrite {
        exec_host: String,
        argv_prefix: Vec<String>,
    },
}

/// 读取并解析文件头：ELF 魔数 + `PT_INTERP`。
///
/// 只读文件头若干字节（phdr 表 + interp 字符串），不映射整文件；
/// 文件不存在/不可读/非 ELF 一律返回 `is_elf: false`（调用方按 Direct 处理，
/// 由内核给出权威错误码——绝不替内核猜错）。
pub fn read_elf_info(path: &Path) -> ProotResult<ElfInfo> {
    use std::io::Read;

    let mut f = match fs::File::open(path) {
        Ok(f) => f,
        Err(_) => return Ok(ElfInfo::not_elf()),
    };
    let mut ehdr = [0u8; 64];
    let n = f.read(&mut ehdr).unwrap_or(0);
    if n < 20 || &ehdr[..4] != b"\x7fELF" {
        return Ok(ElfInfo::not_elf());
    }
    let class = ehdr[4];
    let data = ehdr[5];
    if data != 1 {
        // 只支持小端（aarch64/x86_64 现实世界无大口）；
        // 大端 ELF 视为不可解析，交回 Direct。
        return Ok(ElfInfo::not_elf());
    }
    let interp = match class {
        2 => parse_phdrs64(&mut f, &ehdr)?,
        1 => parse_phdrs32(&mut f, &ehdr)?,
        _ => return Ok(ElfInfo::not_elf()),
    };
    Ok(ElfInfo { is_elf: true, interp })
}

/// 64 位 ELF：定位 phdr 表，找 `PT_INTERP`(3) 并读出字符串。
fn parse_phdrs64(f: &mut fs::File, ehdr: &[u8]) -> ProotResult<Option<String>> {
    use std::io::{Read, Seek, SeekFrom};

    let phoff = u64::from_le_bytes(ehdr[0x20..0x28].try_into().unwrap());
    let phentsize = u16::from_le_bytes(ehdr[0x36..0x38].try_into().unwrap()) as u64;
    let phnum = u16::from_le_bytes(ehdr[0x38..0x3a].try_into().unwrap()) as u64;
    if phnum == 0 || phentsize < 56 || phnum > 4096 {
        return Ok(None);
    }
    let table_len = phentsize * phnum;
    let mut table = vec![0u8; table_len as usize];
    if f.seek(SeekFrom::Start(phoff)).is_err() || f.read(&mut table).unwrap_or(0) < table.len() {
        return Ok(None);
    }
    for i in 0..phnum {
        let ph = &table[(i * phentsize) as usize..(i * phentsize) as usize + 56];
        let p_type = u32::from_le_bytes(ph[0..4].try_into().unwrap());
        if p_type != 3 {
            continue; // PT_INTERP
        }
        let p_offset = u64::from_le_bytes(ph[8..16].try_into().unwrap());
        let p_filesz = u64::from_le_bytes(ph[32..40].try_into().unwrap()) as usize;
        return Ok(read_interp_string(f, p_offset, p_filesz)?);
    }
    Ok(None)
}

/// 32 位 ELF（`x86_64` 宿主跑 i386 guest 等场景）：同上，布局不同。
fn parse_phdrs32(f: &mut fs::File, ehdr: &[u8]) -> ProotResult<Option<String>> {
    use std::io::{Read, Seek, SeekFrom};

    let phoff = u32::from_le_bytes(ehdr[0x1c..0x20].try_into().unwrap()) as u64;
    let phentsize = u16::from_le_bytes(ehdr[0x2a..0x2c].try_into().unwrap()) as u64;
    let phnum = u16::from_le_bytes(ehdr[0x2c..0x2e].try_into().unwrap()) as u64;
    if phnum == 0 || phentsize < 32 || phnum > 4096 {
        return Ok(None);
    }
    let table_len = phentsize * phnum;
    let mut table = vec![0u8; table_len as usize];
    if f.seek(SeekFrom::Start(phoff)).is_err() || f.read(&mut table).unwrap_or(0) < table.len() {
        return Ok(None);
    }
    for i in 0..phnum {
        let ph = &table[(i * phentsize) as usize..(i * phentsize) as usize + 32];
        let p_type = u32::from_le_bytes(ph[0..4].try_into().unwrap());
        if p_type != 3 {
            continue;
        }
        let p_offset = u32::from_le_bytes(ph[4..8].try_into().unwrap()) as u64;
        let p_filesz = u32::from_le_bytes(ph[16..20].try_into().unwrap()) as usize;
        return Ok(read_interp_string(f, p_offset, p_filesz)?);
    }
    Ok(None)
}

/// 从文件 `p_offset` 处读 `p_filesz` 字节的 interp 字符串（截到 NUL/上限）。
fn read_interp_string(f: &mut fs::File, p_offset: u64, p_filesz: usize) -> ProotResult<Option<String>> {
    use std::io::{Read, Seek, SeekFrom};

    if p_filesz == 0 || p_filesz > MAX_INTERP_LEN {
        return Ok(None);
    }
    let mut buf = vec![0u8; p_filesz];
    if f.seek(SeekFrom::Start(p_offset)).is_err() || f.read(&mut buf).unwrap_or(0) < p_filesz {
        return Ok(None);
    }
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    let s = String::from_utf8_lossy(&buf[..end]).into_owned();
    if s.is_empty() {
        Ok(None)
    } else {
        Ok(Some(s))
    }
}

/// 读取并解析 shebang（`#!interp [arg]`）。
///
/// 内核语义对齐：
/// - 首两字节不是 `#!` → `None`（不是脚本）；
/// - `#!` 后无解释器（空行/纯空白）→ `None`（内核按 ELF/ENOEXEC 处理）；
/// - 解释器后至多再取**一个**参数（空格/制表符分隔，吃到行尾）；
/// - 兼容 CRLF：剥掉行尾 `\r`。
pub fn read_shebang(path: &Path) -> ProotResult<Option<Shebang>> {
    use std::io::Read;

    let mut buf = [0u8; SHEBANG_BUF];
    let n = match fs::File::open(path).and_then(|mut f| f.read(&mut buf)) {
        Ok(n) => n,
        Err(_) => return Ok(None),
    };
    if n < 3 || &buf[..2] != b"#!" {
        return Ok(None);
    }
    let line_end = buf[..n].iter().position(|&b| b == b'\n').unwrap_or(n);
    let mut line = &buf[2..line_end];
    if line.last() == Some(&b'\r') {
        line = &line[..line.len() - 1];
    }
    // 解释器：跳过前导空白，吃到空白为止。
    let mut i = 0;
    while i < line.len() && (line[i] == b' ' || line[i] == b'\t') {
        i += 1;
    }
    let interp_start = i;
    while i < line.len() && line[i] != b' ' && line[i] != b'\t' {
        i += 1;
    }
    let interp = String::from_utf8_lossy(&line[interp_start..i]).into_owned();
    if interp.is_empty() {
        return Ok(None);
    }
    // 可选单参数：跳空白，吃到行尾。
    while i < line.len() && (line[i] == b' ' || line[i] == b'\t') {
        i += 1;
    }
    let arg = if i < line.len() {
        Some(String::from_utf8_lossy(&line[i..]).into_owned())
    } else {
        None
    };
    Ok(Some(Shebang { interp, arg }))
}

/// 解析一次 execve 的处置计划（链式走查）。
///
/// `initial_host` 是调用方已解析出的**首个目标的宿主路径**（绝对 guest 路径
/// 走 [`TranslateRules::guest_to_host`]；相对路径须由调用方按 tracee 的
/// `/proc/<pid>/cwd` 拼接——tracer 自己的工作目录与 tracee 无关）。
///
/// 走查规则：
/// 1. 当前目标是脚本（shebang）→ 解释器成为下一跳，脚本（与可选参数）
///    进 argv 前缀；
/// 2. 当前目标是带 `PT_INTERP` 的 ELF → interp 成为下一跳，ELF 进前缀；
/// 3. 当前目标是 standalone ELF（无 interp）/ 非 ELF / 读不到 → 停；
/// 4. 前缀为空 ⇒ [`ExecPlan::Direct`]；非空 ⇒ 最终跳的宿主文件必须存在
///    （否则 Direct，让内核给出权威 ENOENT）。
///
/// 环路/病态链防御：深度上限 [`MAX_CHAIN_DEPTH`] + 已访宿主路径去重。
pub fn resolve_exec_chain(guest_path: &str, initial_host: &Path, rules: &TranslateRules) -> ExecPlan {
    let mut prefix: Vec<String> = Vec::new();
    let mut cur_guest = guest_path.to_string();
    let mut cur_host = initial_host.to_path_buf();
    let mut visited: Vec<PathBuf> = Vec::new();

    for _ in 0..MAX_CHAIN_DEPTH {
        if visited.contains(&cur_host) {
            break; // 环
        }
        visited.push(cur_host.clone());

        // 先 ELF 后 shebang：两者魔数互斥，顺序仅影响可读性。
        match read_elf_info(&cur_host) {
            Ok(info) if info.is_elf => {
                match info.interp {
                    Some(interp) if interp.starts_with('/') => {
                        let next_host = rules.guest_to_host(&interp);
                        prefix.push(std::mem::take(&mut cur_guest));
                        cur_guest = interp;
                        cur_host = next_host;
                        continue;
                    }
                    _ => break, // 静态 ELF 或病态相对 interp：停
                }
            }
            _ => {
                if let Ok(Some(sb)) = read_shebang(&cur_host) {
                    if sb.interp.starts_with('/') {
                        let next_host = rules.guest_to_host(&sb.interp);
                        prefix.push(std::mem::take(&mut cur_guest));
                        if let Some(arg) = sb.arg {
                            prefix.push(arg);
                        }
                        cur_guest = sb.interp;
                        cur_host = next_host;
                        continue;
                    }
                }
                break; // 非 ELF 非脚本：Direct
            }
        }
    }

    if prefix.is_empty() {
        return ExecPlan::Direct;
    }
    if !cur_host.exists() {
        // 链尾 loader 不在 rootfs 里（损坏的 rootfs）：不改写，让内核 ENOENT。
        return ExecPlan::Direct;
    }
    // argv 布局（调用方拼最终 argv）：`[loader 宿主路径] + argv_prefix + 原参数`。
    // argv_prefix **不含 loader 自身**——显式解释器调用语义是
    // `ld.so [OPTION]... EXECUTABLE-FILE [ARGS...]`：argv[1] 必须是链上
    // 第一个程序，loader 把自己的路径塞进去会被当成目标程序（musl 实测：
    // "cannot load /lib/ld-musl-x86_64.so.1"）。
    // 例：动态 ELF ⇒ ["/bin/prog"]；`#!/usr/bin/env python3` ⇒
    // ["/usr/bin/env", "python3", "/bin/pyscript"]。
    ExecPlan::Rewrite {
        exec_host: cur_host.to_string_lossy().into_owned(),
        argv_prefix: prefix.into_iter().rev().collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 手工构造最小 ELF64：一个 PT_INTERP phdr，字符串在文件 offset 0x100。
    fn synth_elf64(interp: Option<&str>) -> Vec<u8> {
        let mut v = vec![0u8; 0x120];
        v[..4].copy_from_slice(b"\x7fELF");
        v[4] = 2; // ELFCLASS64
        v[5] = 1; // ELFDATA2LSB
        // e_phoff = 0x40
        v[0x20..0x28].copy_from_slice(&0x40u64.to_le_bytes());
        v[0x36..0x38].copy_from_slice(&56u16.to_le_bytes()); // e_phentsize
        v[0x38..0x3a].copy_from_slice(&1u16.to_le_bytes()); // e_phnum
        let ph = &mut v[0x40..0x40 + 56];
        match interp {
            Some(s) => {
                ph[0..4].copy_from_slice(&3u32.to_le_bytes()); // PT_INTERP
                ph[8..16].copy_from_slice(&0x100u64.to_le_bytes()); // p_offset
                let len = s.len() + 1;
                ph[32..40].copy_from_slice(&(len as u64).to_le_bytes()); // p_filesz
                v[0x100..0x100 + s.len()].copy_from_slice(s.as_bytes());
            }
            None => {
                ph[0..4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
            }
        }
        v
    }

    /// 手工构造最小 ELF32（类 1，phdr 布局不同）。
    fn synth_elf32(interp: Option<&str>) -> Vec<u8> {
        let mut v = vec![0u8; 0x120];
        v[..4].copy_from_slice(b"\x7fELF");
        v[4] = 1; // ELFCLASS32
        v[5] = 1;
        v[0x1c..0x20].copy_from_slice(&0x40u32.to_le_bytes()); // e_phoff
        v[0x2a..0x2c].copy_from_slice(&32u16.to_le_bytes()); // e_phentsize
        v[0x2c..0x2e].copy_from_slice(&1u16.to_le_bytes()); // e_phnum
        let ph = &mut v[0x40..0x40 + 32];
        match interp {
            Some(s) => {
                ph[0..4].copy_from_slice(&3u32.to_le_bytes());
                ph[4..8].copy_from_slice(&0x100u32.to_le_bytes());
                ph[16..20].copy_from_slice(&((s.len() + 1) as u32).to_le_bytes());
                v[0x100..0x100 + s.len()].copy_from_slice(s.as_bytes());
            }
            None => {
                ph[0..4].copy_from_slice(&1u32.to_le_bytes());
            }
        }
        v
    }

    fn write_tmp(name: &str, bytes: &[u8]) -> PathBuf {
        let p = std::env::temp_dir().join(format!("moxsh-elf-{}-{}", std::process::id(), name));
        fs::write(&p, bytes).unwrap();
        p
    }

    #[test]
    fn elf64_interp_extracted() {
        let p = write_tmp("dyn64", &synth_elf64(Some("/lib/ld-musl-x86_64.so.1")));
        let info = read_elf_info(&p).unwrap();
        assert!(info.is_elf);
        assert_eq!(info.interp.as_deref(), Some("/lib/ld-musl-x86_64.so.1"));
        fs::remove_file(&p).ok();
    }

    #[test]
    fn elf64_static_has_no_interp() {
        let p = write_tmp("static64", &synth_elf64(None));
        let info = read_elf_info(&p).unwrap();
        assert!(info.is_elf);
        assert!(info.interp.is_none());
        fs::remove_file(&p).ok();
    }

    #[test]
    fn elf32_interp_extracted() {
        let p = write_tmp("dyn32", &synth_elf32(Some("/lib/ld-linux.so.2")));
        let info = read_elf_info(&p).unwrap();
        assert!(info.is_elf);
        assert_eq!(info.interp.as_deref(), Some("/lib/ld-linux.so.2"));
        fs::remove_file(&p).ok();
    }

    #[test]
    fn non_elf_is_not_elf() {
        let p = write_tmp("text", b"hello world, not an elf at all");
        let info = read_elf_info(&p).unwrap();
        assert!(!info.is_elf);
        assert!(info.interp.is_none());
        fs::remove_file(&p).ok();
    }

    #[test]
    fn missing_file_is_not_elf() {
        let info = read_elf_info(Path::new("/nonexistent/moxsh-nope")).unwrap();
        assert!(!info.is_elf);
    }

    #[test]
    fn shebang_plain_and_arg() {
        let p = write_tmp("sh1", b"#!/bin/sh\n echo hi\n");
        let sb = read_shebang(&p).unwrap().unwrap();
        assert_eq!(sb.interp, "/bin/sh");
        assert!(sb.arg.is_none());

        let p2 = write_tmp("sh2", b"#!/usr/bin/env python3\n");
        let sb2 = read_shebang(&p2).unwrap().unwrap();
        assert_eq!(sb2.interp, "/usr/bin/env");
        assert_eq!(sb2.arg.as_deref(), Some("python3"));

        let p3 = write_tmp("sh3", b"#!/bin/sh\r\necho crlf\r\n");
        let sb3 = read_shebang(&p3).unwrap().unwrap();
        assert_eq!(sb3.interp, "/bin/sh");

        fs::remove_file(&p).ok();
        fs::remove_file(&p2).ok();
        fs::remove_file(&p3).ok();
    }

    #[test]
    fn shebang_edge_cases() {
        // `#!` + 合法路径（即便不存在）也是 shebang——解析层不查存在性
        let p = write_tmp("nb", b"#!/no\nx");
        let sb = read_shebang(&p).unwrap().unwrap();
        assert_eq!(sb.interp, "/no");
        assert!(sb.arg.is_none());
        // 空解释器
        let p2 = write_tmp("empty", b"#!\n");
        assert!(read_shebang(&p2).unwrap().is_none());
        // 普通文本
        let p3 = write_tmp("plain", b"plain text file\n");
        assert!(read_shebang(&p3).unwrap().is_none());
        // `#!` 后直接换行（无 interp 无 arg）
        let p4 = write_tmp("eol", b"#! \n");
        assert!(read_shebang(&p4).unwrap().is_none());
        fs::remove_file(&p).ok();
        fs::remove_file(&p2).ok();
        fs::remove_file(&p3).ok();
        fs::remove_file(&p4).ok();
    }

    /// 造一个临时 rootfs：动态 ELF（interp=/lib/ldso）+ standalone loader。
    /// 每次调用唯一目录（并行测试互不踩）。
    fn chain_rootfs() -> PathBuf {
        static SEQ: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let n = SEQ.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let base = std::env::temp_dir().join(format!(
            "moxsh-chain-{}-{}",
            std::process::id(),
            n
        ));
        let _ = fs::remove_dir_all(&base);
        fs::create_dir_all(base.join("bin")).unwrap();
        fs::create_dir_all(base.join("lib")).unwrap();
        fs::write(base.join("bin/prog"), synth_elf64(Some("/lib/ldso"))).unwrap();
        fs::write(base.join("lib/ldso"), synth_elf64(None)).unwrap(); // standalone
        base
    }

    #[test]
    fn chain_dynamic_elf_rewrites_to_loader() {
        let rf = chain_rootfs();
        let rules = TranslateRules::new(&rf);
        let plan = resolve_exec_chain("/bin/prog", &rules.guest_to_host("/bin/prog"), &rules);
        match plan {
            ExecPlan::Rewrite { exec_host, argv_prefix } => {
                assert_eq!(exec_host, rf.join("lib/ldso").to_string_lossy());
                assert_eq!(argv_prefix, vec!["/bin/prog".to_string()]);
            }
            other => panic!("期望 Rewrite，得到 {:?}", other),
        }
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_static_elf_is_direct() {
        let rf = chain_rootfs();
        fs::write(rf.join("bin/static"), synth_elf64(None)).unwrap();
        let rules = TranslateRules::new(&rf);
        let h = rules.guest_to_host("/bin/static");
        assert_eq!(resolve_exec_chain("/bin/static", &h, &rules), ExecPlan::Direct);
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_shebang_to_dynamic_interp() {
        let rf = chain_rootfs();
        fs::write(rf.join("bin/script"), b"#!/bin/sh\necho hi\n").unwrap();
        // /bin/sh 是动态 ELF（复用 prog 的内容：interp=/lib/ldso）
        fs::write(rf.join("bin/sh"), synth_elf64(Some("/lib/ldso"))).unwrap();
        let rules = TranslateRules::new(&rf);
        let plan = resolve_exec_chain("/bin/script", &rules.guest_to_host("/bin/script"), &rules);
        match plan {
            ExecPlan::Rewrite { exec_host, argv_prefix } => {
                assert_eq!(exec_host, rf.join("lib/ldso").to_string_lossy());
                // [sh, script]（不含 loader 自身）——最终 argv 由调用方拼
                // [exec_host, sh, script, ...原参数]，与内核 shebang 语义同构
                assert_eq!(
                    argv_prefix,
                    vec!["/bin/sh".to_string(), "/bin/script".to_string()]
                );
            }
            other => panic!("期望 Rewrite，得到 {:?}", other),
        }
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_shebang_with_arg() {
        let rf = chain_rootfs();
        fs::create_dir_all(rf.join("usr/bin")).unwrap();
        fs::write(rf.join("bin/pyscript"), b"#!/usr/bin/env python3\n").unwrap();
        fs::write(rf.join("usr/bin/env"), synth_elf64(Some("/lib/ldso"))).unwrap();
        let rules = TranslateRules::new(&rf);
        let plan = resolve_exec_chain("/bin/pyscript", &rules.guest_to_host("/bin/pyscript"), &rules);
        match plan {
            ExecPlan::Rewrite { argv_prefix, .. } => {
                assert_eq!(
                    argv_prefix,
                    vec![
                        "/usr/bin/env".to_string(),
                        "python3".to_string(),
                        "/bin/pyscript".to_string()
                    ]
                );
            }
            other => panic!("期望 Rewrite，得到 {:?}", other),
        }
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_missing_loader_is_direct() {
        let rf = chain_rootfs();
        fs::write(rf.join("bin/orphan"), synth_elf64(Some("/lib/not-here"))).unwrap();
        let rules = TranslateRules::new(&rf);
        let h = rules.guest_to_host("/bin/orphan");
        // 链尾 loader 不存在 → Direct（内核给 ENOENT，不替内核猜）
        assert_eq!(resolve_exec_chain("/bin/orphan", &h, &rules), ExecPlan::Direct);
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_non_elf_is_direct() {
        let rf = chain_rootfs();
        fs::write(rf.join("bin/data"), b"\x00\x01\x02not-elf").unwrap();
        let rules = TranslateRules::new(&rf);
        let h = rules.guest_to_host("/bin/data");
        assert_eq!(resolve_exec_chain("/bin/data", &h, &rules), ExecPlan::Direct);
        fs::remove_dir_all(&rf).ok();
    }

    #[test]
    fn chain_self_referencing_interp_terminates() {
        // 病态：ELF 的 interp 指向自己 → 环路守卫必须终止（不死循环）。
        let rf = chain_rootfs();
        fs::write(rf.join("bin/loop"), synth_elf64(Some("/bin/loop"))).unwrap();
        let rules = TranslateRules::new(&rf);
        let h = rules.guest_to_host("/bin/loop");
        // 走查在环上终止；最终 host 存在 → 可能给出 Rewrite 或 Direct，
        // 但绝不 panic/死循环。前缀只允许合理的短链。
        let plan = resolve_exec_chain("/bin/loop", &h, &rules);
        if let ExecPlan::Rewrite { argv_prefix, .. } = &plan {
            assert!(argv_prefix.len() <= MAX_CHAIN_DEPTH + 1);
        }
        fs::remove_dir_all(&rf).ok();
    }
}

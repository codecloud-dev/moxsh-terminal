//! 双协议包管理器（Rust 自研）：同时支持
//! 1) 原 termux-packages 的 `.deb` / `Packages` 索引（兼容性，用户零迁移）
//! 2) moxsh 自有 `.mox` 格式（更紧凑的元数据 + 校验）
//!
//! 覆盖：控制字段解析、依赖收集（BFS）、SHA-256 校验。版本约束/替代包等高级
//! dpkg 语义为 M3 范围，此处做基础但正确的实现。

use std::collections::HashMap;

use crate::crypto::sha256;

#[derive(Debug, Clone)]
pub struct Package {
    pub name: String,
    pub version: String,
    pub arch: String,
    pub depends: Vec<String>,
    pub filename: String,
    pub sha256: Option<[u8; 32]>,
}

impl Package {
    pub fn new(name: &str) -> Package {
        Package {
            name: name.to_string(),
            version: String::new(),
            arch: String::new(),
            depends: Vec::new(),
            filename: String::new(),
            sha256: None,
        }
    }
}

/// 解析单个 `control` 段（Deb 控制文件或多段 Packages 索引的一段）。
pub fn parse_control_block(block: &str) -> Option<Package> {
    let mut pkg = Package::new("");
    for line in block.lines() {
        if line.is_empty() {
            continue;
        }
        if let Some((k, v)) = line.split_once(':') {
            let k = k.trim();
            let v = v.trim();
            match k {
                "Package" => pkg.name = v.to_string(),
                "Version" => pkg.version = v.to_string(),
                "Architecture" => pkg.arch = v.to_string(),
                "Filename" => pkg.filename = v.to_string(),
                "Depends" => pkg.depends = split_depends(v),
                "SHA256" => pkg.sha256 = parse_sha256(v),
                _ => {}
            }
        }
    }
    if pkg.name.is_empty() {
        None
    } else {
        Some(pkg)
    }
}

/// 解析 `Packages` 风格索引（多段，以空行分隔）。
pub fn parse_packages_index(index: &str) -> Vec<Package> {
    let mut out = Vec::new();
    for block in index.split("\n\n") {
        if let Some(p) = parse_control_block(block) {
            out.push(p);
        }
    }
    out
}

/// 依赖字段拆分：逗号分隔，竖线表示"或"（取首个候选，M3 完善）。
fn split_depends(s: &str) -> Vec<String> {
    s.split(',')
        .map(|d| d.split('|').next().unwrap_or("").trim().to_string())
        .filter(|d| !d.is_empty())
        .collect()
}

fn parse_sha256(s: &str) -> Option<[u8; 32]> {
    let mut out = [0u8; 32];
    let mut i = 0;
    for byte in s.as_bytes() {
        if i >= 64 {
            break;
        }
        let v = match byte {
            b'0'..=b'9' => byte - b'0',
            b'a'..=b'f' => byte - b'a' + 10,
            b'A'..=b'F' => byte - b'A' + 10,
            _ => continue,
        };
        out[i / 2] = (out[i / 2] << 4) | v;
        i += 1;
    }
    if i == 64 {
        Some(out)
    } else {
        None
    }
}

/// 校验下载数据是否与预期的 SHA-256 一致。
pub fn verify_sha256(data: &[u8], expected_hex: &str) -> bool {
    let digest = sha256(data);
    let hex: String = digest.iter().map(|x| format!("{:02x}", x)).collect();
    hex == expected_hex
}

/// dpkg 风格版本比较（vercmp 简化版）：交替比较数字段（按数值、忽略前导零）
/// 与非数字段（ASCII 字序），如 `1.2 < 1.9 < 1.10`（数字 9 < 10，与纯字符串
/// 序相反）。deb 完整语义还包括 epoch（`2:`）与修订号（`-rN`）特殊处理，
/// 此处统一按整体串应用上述规则，已覆盖 Packages 索引的排序需求。
/// 【新增纯逻辑】测试目标要求"版本比较排序"而既有实现缺失该函数，
/// 按最小语义补齐（零依赖、不改其它逻辑）。
pub fn vercmp(a: &str, b: &str) -> std::cmp::Ordering {
    let (ab, bb) = (a.as_bytes(), b.as_bytes());
    let (mut i, mut j) = (0usize, 0usize);
    while i < ab.len() || j < bb.len() {
        let da = i < ab.len() && ab[i].is_ascii_digit();
        let db = j < bb.len() && bb[j].is_ascii_digit();
        if da || db {
            // 数字段：截取两边连续数字，跳过前导零后先比长度、再逐位比。
            let take_num = |s: &[u8], from: usize| -> (usize, usize) {
                let mut st = from;
                while st < s.len() && s[st] == b'0' {
                    st += 1;
                }
                let mut en = st;
                while en < s.len() && s[en].is_ascii_digit() {
                    en += 1;
                }
                (st, en)
            };
            let (sa, ea) = take_num(ab, i);
            let (sb, eb) = take_num(bb, j);
            let (la, lb) = (ea - sa, eb - sb);
            if la != lb {
                return la.cmp(&lb);
            }
            for k in 0..la {
                if ab[sa + k] != bb[sb + k] {
                    return ab[sa + k].cmp(&bb[sb + k]);
                }
            }
            // 数字段相等（含前导零差异，如 05 == 5）：推进到段尾。
            i = ea;
            j = eb;
        } else {
            // 非数字段：逐字符 ASCII 比较，缺省端视作 0（最小）。
            let ca = if i < ab.len() { ab[i] } else { 0 };
            let cb = if j < bb.len() { bb[j] } else { 0 };
            if ca != cb {
                return ca.cmp(&cb);
            }
            i += 1;
            j += 1;
        }
    }
    std::cmp::Ordering::Equal
}

/// moxsh 自有格式 `.mox`：魔数 + JSON 元数据 + payload。
/// 此处解析头部元数据；payload 由调用方流式处理。
pub const MOX_MAGIC: &[u8; 4] = b"MOX1";

#[derive(Debug, Clone)]
pub struct MoxHeader {
    pub name: String,
    pub version: String,
    pub depends: Vec<String>,
    pub payload_len: u64,
    pub sha256: [u8; 32],
}

/// 从 `.mox` 文件头解析元数据（魔数后接 8 字节 JSON 长度 + JSON）。
pub fn parse_mox_header(data: &[u8]) -> Option<MoxHeader> {
    if data.len() < 4 + 8 || &data[0..4] != MOX_MAGIC {
        return None;
    }
    let json_len =
        u64::from_le_bytes([data[4], data[5], data[6], data[7], data[8], data[9], data[10], data[11]])
            as usize;
    let start = 12;
    // 【bug 修复】原写法 `start + json_len > data.len()` 在恶意超大长度（如
    // 全 0xFF 的 8 字节长度域）下发生 usize 加法溢出，debug 构建直接 panic；
    // 改为反向比较（saturating_sub）杜绝溢出。
    if json_len > data.len().saturating_sub(start) {
        return None;
    }
    let json = &data[start..start + json_len];
    // 极简 JSON 字段提取（避免引入 serde 依赖）
    let name = grab(json, "name");
    let version = grab(json, "version");
    let depends = grab(json, "depends")
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    let payload_len = grab(json, "payload_len")
        .parse::<u64>()
        .unwrap_or(0);
    let sha_hex = grab(json, "sha256");
    let sha = parse_sha256(&sha_hex).unwrap_or([0u8; 32]);
    Some(MoxHeader {
        name,
        version,
        depends,
        payload_len,
        sha256: sha,
    })
}

/// 从扁平 JSON 字节中取字符串/数字字段值（避免 serde 依赖的最小实现）。
///
/// 【bug 修复】原实现统一在 `,`/`}`/`"` 处截断——字符串值的开引号恰好是
/// 第一个匹配字符，end 落在 0，所有带引号的值（name/version/depends/sha256）
/// 都被截成空串。现按值形态分派：带引号 → 取闭引号之间的内容（内部逗号
/// 安全，如 `"ncurses,libc"`）；裸值（数字）→ 到 `,`/`}` 截断。
fn grab(json: &[u8], key: &str) -> String {
    let s = std::str::from_utf8(json).unwrap_or("");
    let pat = format!("\"{}\"", key);
    if let Some(pos) = s.find(&pat) {
        let rest = &s[pos + pat.len()..];
        if let Some(c) = rest.find(':') {
            let val = rest[c + 1..].trim_start();
            // 字符串值：跳过开引号，取到闭引号为止。
            if let Some(inner) = val.strip_prefix('"') {
                if let Some(end) = inner.find('"') {
                    return inner[..end].to_string();
                }
                return String::new();
            }
            // 裸值（数字/布尔）：到 , 或 } 截断。
            if let Some(end) = val.find(|c| c == ',' || c == '}') {
                return val[..end].trim().to_string();
            }
        }
    }
    String::new()
}

/// 本地仓库：名称 → 包。
pub struct Repository {
    pub packages: HashMap<String, Package>,
}

impl Repository {
    pub fn new() -> Repository {
        Repository {
            packages: HashMap::new(),
        }
    }

    /// 从 `Packages` 索引批量加入。
    pub fn ingest_index(&mut self, index: &str) {
        for p in parse_packages_index(index) {
            self.packages.insert(p.name.clone(), p);
        }
    }

    pub fn add(&mut self, p: Package) {
        self.packages.insert(p.name.clone(), p);
    }

    pub fn get(&self, name: &str) -> Option<&Package> {
        self.packages.get(name)
    }

    /// 解析安装 `names` 所需的所有包（含传递依赖），BFS 收集。
    /// 返回拓扑无关的包名列表（已去重）。无法解析的依赖以 Err 返回。
    pub fn resolve(&self, names: &[&str]) -> Result<Vec<String>, String> {
        let mut result: Vec<String> = Vec::new();
        let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
        let mut queue: Vec<String> = names.iter().map(|s| s.to_string()).collect();
        while let Some(name) = queue.pop() {
            if !seen.insert(name.clone()) {
                continue;
            }
            let pkg = match self.packages.get(&name) {
                Some(p) => p,
                None => return Err(format!("unresolved dependency: {}", name)),
            };
            result.push(name.clone());
            for dep in &pkg.depends {
                if !seen.contains(dep) {
                    queue.push(dep.clone());
                }
            }
        }
        Ok(result)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolve_dependencies() {
        let mut repo = Repository::new();
        repo.add(Package {
            name: "app".into(),
            depends: vec!["libc".into(), "zlib".into()],
            ..Package::new("app")
        });
        repo.add(Package {
            name: "zlib".into(),
            depends: vec!["libc".into()],
            ..Package::new("zlib")
        });
        repo.add(Package::new("libc"));
        let r = repo.resolve(&["app"]).unwrap();
        assert_eq!(r.len(), 3);
        assert!(r.contains(&"app".to_string()));
        assert!(r.contains(&"libc".to_string()));
    }

    #[test]
    fn sha256_verify() {
        let data = b"hello";
        let d = sha256(data);
        let hex: String = d.iter().map(|x| format!("{:02x}", x)).collect();
        assert!(verify_sha256(data, &hex));
        assert!(!verify_sha256(data, "deadbeef"));
    }

    /// M5-1：deb control 解析——包名/版本/架构/文件名/依赖/SHA256 各字段。
    #[test]
    fn parse_deb_control_block() {
        let block = "\
Package: vim
Version: 2:9.0.1378-1
Architecture: aarch64
Depends: libc6 (>= 2.31), libtinfo6
Filename: pool/main/v/vim/vim_9.0.1378-1_arm64.deb
SHA256: b1946ac92492d2347c6235b4d2611184e0d0a2b3c4d5e6f708192a3b4c5d6e7f0
";
        let p = parse_control_block(block).unwrap();
        assert_eq!(p.name, "vim");
        assert_eq!(p.version, "2:9.0.1378-1");
        assert_eq!(p.arch, "aarch64");
        assert_eq!(p.filename, "pool/main/v/vim/vim_9.0.1378-1_arm64.deb");
        assert_eq!(p.depends.len(), 2);
        assert_eq!(p.sha256.unwrap()[0], 0xb1);
    }

    /// M5-2：依赖行分割——逗号分隔；`|` 或关系取首个候选（现实现语义，
    /// 完整候选选择为 M3 范围）；版本约束随候选名保留。
    #[test]
    fn depends_split_comma_and_alternatives() {
        let p = parse_control_block(
            "Package: t\nDepends: libc6 (>= 2.31), ncurses-bin | ncurses, busybox\n",
        )
        .unwrap();
        assert_eq!(p.depends, vec!["libc6 (>= 2.31)", "ncurses-bin", "busybox"]);
    }

    /// M5-3：Packages 多段索引解析与仓库摄取。
    #[test]
    fn packages_index_multi_blocks() {
        let idx = "Package: a\nVersion: 1\n\nPackage: b\nVersion: 2\n\n\n";
        let pkgs = parse_packages_index(idx);
        assert_eq!(pkgs.len(), 2);
        assert_eq!(pkgs[0].name, "a");
        assert_eq!(pkgs[1].version, "2");
        let mut repo = Repository::new();
        repo.ingest_index(idx);
        assert_eq!(repo.get("b").unwrap().version, "2");
    }

    /// M5-4：mox 头解析——魔数 + 长度域 + JSON 元数据（现有格式）。
    #[test]
    fn parse_mox_header_roundtrip() {
        let json = br#"{"name":"vim","version":"9.0.1","depends":"ncurses,libc","payload_len":4096,"sha256":"abababababababababababababababababababababababababababababababab"}"#;
        let mut data = MOX_MAGIC.to_vec();
        data.extend_from_slice(&(json.len() as u64).to_le_bytes());
        data.extend_from_slice(json);
        let h = parse_mox_header(&data).unwrap();
        assert_eq!(h.name, "vim");
        assert_eq!(h.version, "9.0.1");
        assert_eq!(h.depends, vec!["ncurses", "libc"]);
        assert_eq!(h.payload_len, 4096);
        assert_eq!(h.sha256[0], 0xab);
        assert_eq!(h.sha256[31], 0xab);
    }

    /// M5-5：vercmp 版本比较排序——数字段按数值（9 < 10）、前导零等价。
    #[test]
    fn vercmp_sorts_numerically() {
        assert_eq!(vercmp("1.9", "1.10"), std::cmp::Ordering::Less);
        assert_eq!(vercmp("1.2", "1.9"), std::cmp::Ordering::Less);
        assert_eq!(vercmp("1.05", "1.5"), std::cmp::Ordering::Equal);
        let mut v = vec!["1.10", "1.2", "1.9", "0.99", "1.0"];
        v.sort_by(|a, b| vercmp(a, b));
        assert_eq!(v, vec!["0.99", "1.0", "1.2", "1.9", "1.10"]);
    }

    /// M5-6：异常输入不 panic——坏 control / 坏 mox 头 / 坏 hex / 未知依赖。
    #[test]
    fn malformed_input_never_panics() {
        // control：空块、无 Package 字段、空包名。
        assert!(parse_control_block("").is_none());
        assert!(parse_control_block("NotAField: x").is_none());
        assert!(parse_control_block("Package:").is_none());
        // mox 头：长度不足、魔数错、JSON 长度域溢出（修复后的反向比较）。
        assert!(parse_mox_header(b"").is_none());
        assert!(parse_mox_header(b"MOX1").is_none());
        assert!(parse_mox_header(b"XXXX\x00\x00\x00\x00\x00\x00\x00\x00{}").is_none());
        assert!(parse_mox_header(b"MOX1\xff\xff\xff\xff\xff\xff\xff\xff{}").is_none());
        // sha256：非法/不足 64 位 hex。
        assert_eq!(parse_sha256("xyz"), None);
        assert_eq!(parse_sha256("ab"), None);
        assert!(!verify_sha256(b"x", "nothex"));
        // 依赖解析：未知包返回 Err 而非 panic。
        let repo = Repository::new();
        assert!(repo.resolve(&["nope"]).is_err());
        // vercmp：空串等退化输入。
        assert_eq!(vercmp("", ""), std::cmp::Ordering::Equal);
        assert_eq!(vercmp("a", ""), std::cmp::Ordering::Greater);
        assert_eq!(vercmp("", "a"), std::cmp::Ordering::Less);
    }
}

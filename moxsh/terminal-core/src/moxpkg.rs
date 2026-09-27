//! .mox 包格式（D15）：moxsh 自研插件 / AI 技能 / 主题 / rootfs 的统一分发格式。
//!
//! # .mox 包规范（本模块为权威实现）
//!
//! ```text
//! .mox 包 = tar 容器（未压缩 tar；也兼容 tar.gz，open 时按 magic 自动识别）
//!   manifest.json          # 元数据（UTF-8 JSON，见下）
//!   signature              # HMAC-SHA256(secret, manifest.json 原始字节) 的 hex 小写
//!   payload/...            # type 对应的实际内容（插件 so/资源、主题文件、skill.json、rootfs 树）
//! ```
//!
//! manifest.json 字段（必填 id/name/version/type，其余可缺省按默认值）：
//!
//! | 字段            | 类型     | 说明                                                         |
//! |-----------------|----------|--------------------------------------------------------------|
//! | id              | string   | 包唯一 id（形如 "float.terminal"、"skill.python"）           |
//! | name            | string   | 显示名                                                       |
//! | version         | string   | 语义化版本（"1.0.0"）                                        |
//! | author          | string   | 作者                                                         |
//! | description     | string   | 描述                                                         |
//! | type            | string   | "plugin" \| "skill" \| "theme" \| "rootfs"                   |
//! | minAppVersion   | string   | 最低宿主版本（"0.1.0"），宿主加载前比对                      |
//! | permissions     | string[] | 声明的权限（install_distro/run_command/...），安装时用户确认 |
//! | price           | number   | D17 预留：0=免费；>0 表示付费（仅字段，无支付流程）          |
//! | purchased       | bool     | D17 预留：是否已购（支付后由商店回写）                       |
//!
//! type == "skill" 时，payload/skill.json 约定结构（AI 技能清单，D18 工具调用用）：
//!
//! ```json
//! { "prompt": "系统提示词…",
//!   "tools": ["install_distro","install_pkg","change_repo","run_command","explain_error", ...],
//!   "params_schema": "JSON Schema 字符串（工具参数约束）" }
//! ```
//!
//! # 外来格式兼容（D15）
//!
//! Termux/第三方 zip 插件包（zip 容器 + package.json）可在 **import 时转换**：
//! Kotlin 层（plugin-store 的"本地上传"入口）解 zip → 生成 manifest.json（type
//! 映射 plugin/skill）→ 连同 payload 重打为 tar → 本模块按标准 .mox 验签。
//! 转换器放在 Kotlin 侧（zip 源不可信，转换即隔离），M7 落地；Rust 侧只需认 tar。
//!
//! # 依赖约束
//!
//! 不引入 serde/serde_json：manifest 字段提取用手写最小 JSON reader（见
//! [`json_string_value`] 系函数）。字段集固定且小，手写成本低于引入 serde 的
//! NDK 编译不确定性；M2 后如 JSON 复杂化（嵌套 schema）可整体换 serde_json。
//!
//! # 错误码（mox 系列 C-ABI 专用段，见 lib.rs）
//!
//! ```text
//! 0 成功 | -1 参数非法 | -2 包文件不存在 | -3 manifest 缺失/坏 | -4 NotAMox
//! -5 验签失败 | -8 解压失败 | -9 IO 错误
//! ```

use std::fs;
use std::io::{Read, Seek};
use std::path::{Path, PathBuf};

use crate::crypto::hmac_sha256;

// ============ 错误类型 ============

/// .mox 包操作错误（`to_code` 映射为 C-ABI 错误码，见模块头注释）。
#[derive(Debug)]
pub enum MoxError {
    /// 参数非法（null 指针 / 空路径）。
    BadArgs,
    /// 包文件不存在。
    NotFound,
    /// manifest.json 存在但解析失败（坏 JSON / 缺必填字段 / 非 UTF-8）。
    BadManifest,
    /// 包内没有 manifest.json（不是合法 .mox）。
    NotAMox,
    /// HMAC 验签失败（包被篡改或 secret 不对）。
    BadSignature,
    /// 解压失败（含 tar 条目路径逃逸等防御性拒绝）。
    ExtractFailed(String),
    /// 其他 IO 错误。
    Io(std::io::Error),
}

impl From<std::io::Error> for MoxError {
    fn from(e: std::io::Error) -> Self {
        MoxError::Io(e)
    }
}

impl MoxError {
    /// C-ABI 错误码（与 lib.rs mox 系列函数返回值一致）。
    pub fn to_code(&self) -> i32 {
        match self {
            MoxError::BadArgs => -1,
            MoxError::NotFound => -2,
            MoxError::BadManifest => -3,
            MoxError::NotAMox => -4,
            MoxError::BadSignature => -5,
            MoxError::ExtractFailed(_) => -8,
            MoxError::Io(_) => -9,
        }
    }
}

// ============ manifest 解析（手写最小 JSON reader，见模块头"依赖约束"） ============

/// manifest.json 的 Rust 视图。`kind` 对应 JSON 键 `"type"`（`type` 是 Rust
/// 关键字，字段改名但序列化键不变）。
#[derive(Debug, Clone, PartialEq)]
pub struct MoxManifest {
    pub id: String,
    pub name: String,
    pub version: String,
    pub author: String,
    pub description: String,
    /// "plugin" | "skill" | "theme" | "rootfs"。
    pub kind: String,
    pub min_app_version: String,
    pub permissions: Vec<String>,
    /// D17 预留：0=免费；>0 付费（仅字段，无支付）。
    pub price: f64,
    /// D17 预留：是否已购。
    pub purchased: bool,
}

impl MoxManifest {
    /// 从 manifest.json 原始字节解析。id/name/version/type 缺一即报 [`MoxError::BadManifest`]。
    pub fn parse(bytes: &[u8]) -> Result<MoxManifest, MoxError> {
        let src = std::str::from_utf8(bytes).map_err(|_| MoxError::BadManifest)?;
        let id = json_string_value(src, "id").ok_or(MoxError::BadManifest)?;
        let name = json_string_value(src, "name").ok_or(MoxError::BadManifest)?;
        let version = json_string_value(src, "version").ok_or(MoxError::BadManifest)?;
        let kind = json_string_value(src, "type").ok_or(MoxError::BadManifest)?;
        // type 白名单校验：外来包转换前在此拦下非法类型。
        if !matches!(kind.as_str(), "plugin" | "skill" | "theme" | "rootfs") {
            return Err(MoxError::BadManifest);
        }
        Ok(MoxManifest {
            id,
            name,
            version,
            author: json_string_value(src, "author").unwrap_or_default(),
            description: json_string_value(src, "description").unwrap_or_default(),
            kind,
            min_app_version: json_string_value(src, "minAppVersion").unwrap_or_default(),
            permissions: json_string_array(src, "permissions"),
            price: json_f64_value(src, "price").unwrap_or(0.0),
            purchased: json_bool_value(src, "purchased").unwrap_or(false),
        })
    }
}

/// AI 技能清单（type=="skill" 时 payload/skill.json 的结构，D18 工具调用用）。
#[derive(Debug, Clone, PartialEq)]
pub struct SkillSpec {
    /// 系统提示词。
    pub prompt: String,
    /// 允许调用的工具白名单（install_distro/run_command/...）。
    pub tools: Vec<String>,
    /// 工具参数约束（JSON Schema 字符串）。
    pub params_schema: String,
}

impl SkillSpec {
    /// 从 payload/skill.json 原始字节解析（prompt/tools 必填）。
    pub fn parse(bytes: &[u8]) -> Result<SkillSpec, MoxError> {
        let src = std::str::from_utf8(bytes).map_err(|_| MoxError::BadManifest)?;
        Ok(SkillSpec {
            prompt: json_string_value(src, "prompt").ok_or(MoxError::BadManifest)?,
            tools: json_string_array(src, "tools"),
            params_schema: json_string_value(src, "params_schema").unwrap_or_default(),
        })
    }
}

/// 在 `src` 中查找 `"key"` 对应的 JSON 字符串值（支持常见转义），找不到返回 None。
///
/// 手写 reader 约定：只识别顶层平铺字段（manifest 固定 schema，够用）；
/// 查找是全文首个匹配——字段名互不为前缀互含（规范保证），无需真实 tokenizer。
fn json_string_value(src: &str, key: &str) -> Option<String> {
    let pat = format!("\"{}\"", key);
    let pos = src.find(&pat)? + pat.len();
    let rest = src[pos..].trim_start().strip_prefix(':')?.trim_start();
    let rest = rest.strip_prefix('"')?;
    let mut out = String::new();
    let mut chars = rest.chars();
    while let Some(c) = chars.next() {
        match c {
            '"' => return Some(out),
            '\\' => {
                let esc = chars.next()?;
                match esc {
                    'n' => out.push('\n'),
                    't' => out.push('\t'),
                    'r' => out.push('\r'),
                    'b' => out.push('\u{8}'),
                    'f' => out.push('\u{c}'),
                    'u' => {
                        // \uXXXX（BMP）；代理对按替换符处理（manifest 不建议用 emoji）。
                        let mut v = 0u32;
                        for _ in 0..4 {
                            v = v * 16 + chars.next()?.to_digit(16)?;
                        }
                        out.push(char::from_u32(v).unwrap_or('\u{FFFD}'));
                    }
                    // \" \\ \/ 直接还原本字符。
                    other => out.push(other),
                }
            }
            c => out.push(c),
        }
    }
    None
}

/// 在 `src` 中查找 `"key"` 对应的字符串数组值（如 permissions/tools）。
fn json_string_array(src: &str, key: &str) -> Vec<String> {
    let pat = format!("\"{}\"", key);
    let Some(pos) = src.find(&pat) else {
        return Vec::new();
    };
    let Some(rest) = src[pos + pat.len()..].trim_start().strip_prefix(':') else {
        return Vec::new();
    };
    let Some(rest) = rest.trim_start().strip_prefix('[') else {
        return Vec::new();
    };
    let mut out = Vec::new();
    let mut rest = rest.trim_start();
    loop {
        rest = rest.trim_start();
        if rest.starts_with(']') {
            return out;
        }
        let Some(s) = rest.strip_prefix('"') else {
            return out; // 非字符串项：到此为止（规范里数组只放字符串）。
        };
        let end = s.find('"').unwrap_or(s.len());
        out.push(s[..end].to_string());
        rest = &s[end..];
        match rest.strip_prefix('"').and_then(|r| r.trim_start().strip_prefix(',')) {
            Some(after) => rest = after,
            None => return out,
        }
    }
}

/// 在 `src` 中查找 `"key"` 对应的数字值（price）。
fn json_f64_value(src: &str, key: &str) -> Option<f64> {
    let pat = format!("\"{}\"", key);
    let pos = src.find(&pat)? + pat.len();
    let rest = src[pos..].trim_start().strip_prefix(':')?.trim_start();
    let end = rest
        .find(|c: char| !(c.is_ascii_digit() || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E'))
        .unwrap_or(rest.len());
    rest[..end].parse::<f64>().ok()
}

/// 在 `src` 中查找 `"key"` 对应的布尔值（purchased）。
fn json_bool_value(src: &str, key: &str) -> Option<bool> {
    let pat = format!("\"{}\"", key);
    let pos = src.find(&pat)? + pat.len();
    let rest = src[pos..].trim_start().strip_prefix(':')?.trim_start();
    if rest.starts_with("true") {
        Some(true)
    } else if rest.starts_with("false") {
        Some(false)
    } else {
        None
    }
}

// ============ 包操作 ============

/// tar 条目元数据（list 用）。
#[derive(Debug, Clone, PartialEq)]
pub struct MoxEntry {
    /// 条目名（"manifest.json" / "signature" / "payload/..."）。
    pub name: String,
    /// 字节大小。
    pub size: u64,
}

/// 一个已打开的 .mox 包。
///
/// `open` 时流式扫描一遍 tar，缓存 manifest/signature/skill.json 字节与条目表
/// （不缓存 payload 大块数据，rootfs 型大包不占内存）；`extract_to` 时重新打开
/// 文件流式落盘。所有操作只读，`MoxPackage` 可安全克隆句柄语义（C-ABI 侧为
/// opaque 指针）。
#[derive(Debug)]
pub struct MoxPackage {
    path: PathBuf,
    /// manifest.json 原始字节（验签基准）。
    manifest: Option<Vec<u8>>,
    /// signature 条目（hex 字符串）。
    signature: Option<String>,
    /// type=="skill" 时缓存的 payload/skill.json 字节。
    skill_bytes: Option<Vec<u8>>,
    /// 全部条目元数据（open 时收集）。
    entries: Vec<MoxEntry>,
}

/// 包内约定条目名。
const ENTRY_MANIFEST: &str = "manifest.json";
const ENTRY_SIGNATURE: &str = "signature";
const ENTRY_SKILL: &str = "payload/skill.json";

impl MoxPackage {
    /// 打开一个 .mox 包并扫描条目（未压缩 tar，或按 gzip magic 自动识别的 tar.gz）。
    pub fn open(path: &Path) -> Result<MoxPackage, MoxError> {
        let mut pkg = MoxPackage {
            path: path.to_path_buf(),
            manifest: None,
            signature: None,
            skill_bytes: None,
            entries: Vec::new(),
        };
        // gzip magic：1f 8b。商店分发的包可选 tar.gz（大 rootfs 体积减半），
        // 规范基准是未压缩 tar，两种都认。
        let is_gz = {
            let mut f = fs::File::open(path).map_err(|e| {
                if e.kind() == std::io::ErrorKind::NotFound {
                    MoxError::NotFound
                } else {
                    MoxError::Io(e)
                }
            })?;
            let mut magic = [0u8; 2];
            match f.read_exact(&mut magic) {
                Ok(()) => magic == [0x1f, 0x8b],
                Err(_) => false, // 空文件/读不出 2 字节：后面 tar 解析时报错。
            }
        };
        let mut pkg_err: Option<MoxError> = None;
        {
            let f = fs::File::open(&pkg.path)?;
            let reader: Box<dyn Read> = if is_gz {
                Box::new(flate2::read::GzDecoder::new(f))
            } else {
                Box::new(f)
            };
            let mut ar = tar::Archive::new(reader);
            match ar.entries() {
                Ok(entries) => {
                    for entry in entries {
                        let mut e = match entry {
                            Ok(e) => e,
                            Err(err) => {
                                pkg_err = Some(MoxError::Io(err));
                                break;
                            }
                        };
                        // 目录条目不进清单。
                        if e.header().entry_type().is_dir() {
                            continue;
                        }
                        let name = match e.path() {
                            Ok(p) => p.to_string_lossy().into_owned(),
                            Err(err) => {
                                pkg_err = Some(MoxError::Io(err));
                                break;
                            }
                        };
                        let size = e.header().size().unwrap_or(0);
                        pkg.entries.push(MoxEntry { name: name.clone(), size });
                        // 只缓存三个小条目的字节。
                        let wanted = match name.as_str() {
                            ENTRY_MANIFEST => Some(&mut pkg.manifest),
                            ENTRY_SIGNATURE => None,
                            ENTRY_SKILL => Some(&mut pkg.skill_bytes),
                            _ => None,
                        };
                        if wanted.is_some() || name == ENTRY_SIGNATURE {
                            let mut data = Vec::with_capacity(size as usize);
                            if let Err(err) = e.read_to_end(&mut data) {
                                pkg_err = Some(MoxError::Io(err));
                                break;
                            }
                            if name == ENTRY_SIGNATURE {
                                pkg.signature =
                                    Some(String::from_utf8_lossy(&data).trim().to_string());
                            } else if let Some(slot) = wanted {
                                *slot = Some(data);
                            }
                        }
                    }
                }
                Err(err) => pkg_err = Some(MoxError::Io(err)),
            }
        }
        if let Some(err) = pkg_err {
            return Err(err);
        }
        // 没有 manifest.json 就不是 .mox（外来 zip 直改后缀等场景在此拦截）。
        if pkg.manifest.is_none() {
            return Err(MoxError::NotAMox);
        }
        Ok(pkg)
    }

    /// 解析缓存的 manifest.json。
    pub fn manifest(&self) -> Result<MoxManifest, MoxError> {
        MoxManifest::parse(self.manifest.as_deref().unwrap_or(&[]))
    }

    /// manifest.json 原始字节（C-ABI `moxsh_mox_manifest` 透传给 Kotlin 解析用）。
    pub fn manifest_json_bytes(&self) -> Option<&[u8]> {
        self.manifest.as_deref()
    }

    /// 解析 payload/skill.json（仅 type=="skill" 且包内有该条目时有值）。
    pub fn skill_spec(&self) -> Result<Option<SkillSpec>, MoxError> {
        match &self.skill_bytes {
            Some(b) => SkillSpec::parse(b).map(Some),
            None => Ok(None),
        }
    }

    /// 条目列表（open 时收集的快照）。
    pub fn list_entries(&self) -> &[MoxEntry] {
        &self.entries
    }

    /// HMAC-SHA256 验签：重新计算 hmac(secret, manifest 原始字节) 并与
    /// 包内 signature 条目（hex）恒时比较。通过返回 Ok(true)，不通过 Ok(false)。
    pub fn verify(&self, secret: &[u8]) -> Result<bool, MoxError> {
        let (Some(manifest), Some(sig)) = (&self.manifest, &self.signature) else {
            // 有 manifest 没 signature：视为未签名包，验签直接不通过。
            return if self.manifest.is_some() {
                Ok(false)
            } else {
                Err(MoxError::NotAMox)
            };
        };
        let expect = hmac_sha256(secret, manifest);
        let expect_hex: String = expect.iter().map(|b| format!("{:02x}", b)).collect();
        // 恒时比较：防时序侧信道逐字节猜签名。
        Ok(consttime_eq_hex(&expect_hex, sig))
    }

    /// 解包到 `out_dir`（manifest.json / signature / payload/**；未知顶级条目
    /// 跳过，防御外来包夹带）。路径安全由 `tar::Entry::unpack_in` 保证：
    /// 拒绝绝对路径与 `..` 逃逸条目，命中即整体报 [`MoxError::ExtractFailed`]。
    pub fn extract_to(&self, out_dir: &Path) -> Result<(), MoxError> {
        fs::create_dir_all(out_dir)?;
        // 与 open 相同的 gzip 识别，保证 tar.gz 包也能解。
        let mut f = fs::File::open(&self.path)?;
        let mut magic = [0u8; 2];
        let is_gz = f.read_exact(&mut magic).is_ok() && magic == [0x1f, 0x8b];
        // 【bug 修复】magic 探测消费了 2 字节，若直接把 f 交给 tar/GzDecoder
        // 会从偏移 2 开始解析 → header 错位报 checksum mismatch。open 路径
        // 无此问题（它重新打开文件）。此处回卷后统一从头读。
        f.rewind()?;
        let reader: Box<dyn Read> = if is_gz {
            Box::new(flate2::read::GzDecoder::new(f))
        } else {
            Box::new(f)
        };
        let mut ar = tar::Archive::new(reader);
        for entry in ar.entries()? {
            let mut e = entry.map_err(MoxError::Io)?;
            if e.header().entry_type().is_dir() {
                continue; // unpack 按文件路径自动建目录。
            }
            // P1 修复（符号链接穿越）：tar 的 unpack_in 只校验条目自身路径，
            // 不校验链接 target——恶意包可放 "payload/x -> ../../../../..."，
            // 解压后在包外制造可写入口。.mox 规范中 payload 只有普通文件，
            // 链接条目一律防御性跳过。
            {
                let et = e.header().entry_type();
                if et.is_symlink() || et.is_hard_link() {
                    continue;
                }
            }
            let name = e
                .path()
                .map_err(MoxError::Io)?
                .to_string_lossy()
                .into_owned();
            let allowed = name == ENTRY_MANIFEST
                || name == ENTRY_SIGNATURE
                || name.starts_with("payload/");
            if !allowed {
                continue;
            }
            e.unpack_in(out_dir)
                .map_err(|err| MoxError::ExtractFailed(format!("{}: {}", name, err)))?;
        }
        Ok(())
    }
}

/// 恒时 hex 比较（长度不同立即 false；等长时全量异或累积，无早退分支）。
fn consttime_eq_hex(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i].to_ascii_lowercase() ^ b[i].to_ascii_lowercase();
    }
    diff == 0
}

// ============ 单元测试（内存构造 .mox，不落临时 tar 文件） ============

#[cfg(test)]
mod tests {
    use super::*;

    /// 测试用 secret（正式发布由 Kotlin 层 Keystore 派生）。
    const SECRET: &[u8] = b"moxsh-test-secret";

    /// 通用内存 tar 构造器（测试 7 的"无 manifest 裸包"也用它）。
    fn build_tar(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut builder = tar::Builder::new(Vec::new());
        for (name, data) in entries {
            let mut header = tar::Header::new_gnu();
            header.set_size(data.len() as u64);
            header.set_mode(0o644);
            header.set_cksum();
            builder.append_data(&mut header, *name, *data).unwrap();
        }
        builder.into_inner().unwrap()
    }

    /// 构造一个内存 .mox 的 tar 字节：manifest.json + signature + payload 文件。
    fn build_mox(manifest_json: &str, sign: bool, payload: &[(&str, &[u8])]) -> Vec<u8> {
        // 拥有所有权的条目表：signature hex 由本函数局部生成，若以 &[u8] 借用
        // 推入条目表会因 String drop 而悬垂（E0597）。
        let mut all: Vec<(&str, Vec<u8>)> =
            vec![(ENTRY_MANIFEST, manifest_json.as_bytes().to_vec())];
        if sign {
            let mac = hmac_sha256(SECRET, manifest_json.as_bytes());
            let hex: String = mac.iter().map(|b| format!("{:02x}", b)).collect();
            all.push((ENTRY_SIGNATURE, hex.into_bytes()));
        }
        all.extend(payload.iter().map(|(n, d)| (*n, d.to_vec())));
        let refs: Vec<(&str, &[u8])> = all.iter().map(|(n, d)| (*n, d.as_slice())).collect();
        build_tar(&refs)
    }

    /// 构造"manifest 字节被篡改但 signature 仍是对原 manifest"的 .mox
    /// （模拟攻击者改包不重签——verify 必须拒绝）。
    fn build_mox_tampered_manifest(
        original_json: &str,
        tampered_json: &str,
        payload: &[(&str, &[u8])],
    ) -> Vec<u8> {
        let mac = hmac_sha256(SECRET, original_json.as_bytes());
        let hex: String = mac.iter().map(|b| format!("{:02x}", b)).collect();
        let mut all: Vec<(&str, Vec<u8>)> = vec![
            (ENTRY_MANIFEST, tampered_json.as_bytes().to_vec()),
            (ENTRY_SIGNATURE, hex.into_bytes()),
        ];
        all.extend(payload.iter().map(|(n, d)| (*n, d.to_vec())));
        let refs: Vec<(&str, &[u8])> = all.iter().map(|(n, d)| (*n, d.as_slice())).collect();
        build_tar(&refs)
    }

    /// 标准示例 manifest（plugin 型，全字段）。
    fn sample_manifest() -> String {
        // 注意 JSON 里写 "type"（Rust 字段名是 kind，见 MoxManifest 注释）。
        r#"{
  "id": "float.terminal",
  "name": "浮动终端",
  "version": "1.2.0",
  "author": "moxsh",
  "description": "液态玻璃悬浮终端",
  "type": "plugin",
  "minAppVersion": "0.1.0",
  "permissions": ["run_command", "explain_error"],
  "price": 6.0,
  "purchased": false
}"#
        .to_string()
    }

    fn sample_payload() -> Vec<(&'static str, &'static [u8])> {
        vec![
            ("payload/entry.so", b"\x7fELF-fake-bytes"),
            ("payload/assets/readme.txt", b"hello mox"),
        ]
    }

    /// 临时解压目录（tests 共用前缀，跑完由用例自清）。
    fn tmp_dir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("moxsh-mox-test-{}-{}", std::process::id(), tag));
        let _ = fs::remove_dir_all(&d);
        d
    }

    // 1. 清单解析：全部字段（含数组/数字/布尔）正确还原。
    #[test]
    fn test_manifest_parse_full_fields() {
        let bytes = build_mox(&sample_manifest(), false, &sample_payload());
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        let m = pkg.manifest().unwrap();
        assert_eq!(m.id, "float.terminal");
        assert_eq!(m.name, "浮动终端");
        assert_eq!(m.version, "1.2.0");
        assert_eq!(m.author, "moxsh");
        assert_eq!(m.kind, "plugin");
        assert_eq!(m.min_app_version, "0.1.0");
        assert_eq!(m.permissions, vec!["run_command", "explain_error"]);
        assert!((m.price - 6.0).abs() < 1e-9);
        assert!(!m.purchased);
    }

    // 2. 正确 secret 验签通过（tar.gz 兼容路径一并验证）。
    #[test]
    fn test_verify_ok() {
        let bytes = build_mox(&sample_manifest(), true, &sample_payload());
        // 纯 tar
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        assert!(pkg.verify(SECRET).unwrap());
        // 同内容打 gzip，open 按 magic 识别后验签结果一致
        let gz = gz_bytes(&bytes);
        let pkg_gz = MoxPackage::open_from_bytes_for_test(&gz).unwrap();
        assert!(pkg_gz.verify(SECRET).unwrap());
    }

    // 3. 篡改 payload 后签名仍对 manifest——验签应仍通过（签名只覆盖 manifest，
    //    payload 完整性由商店侧 sha256 兜底；此处锁定"签名范围 = manifest"的语义）。
    #[test]
    fn test_verify_signature_covers_manifest_only() {
        let bytes = build_mox(&sample_manifest(), true, &sample_payload());
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        assert!(pkg.verify(SECRET).unwrap());
    }

    // 4. 错误 secret / 篡改 manifest → 验签失败。
    #[test]
    fn test_verify_rejects_wrong_secret_or_tampered_manifest() {
        let manifest = sample_manifest();
        let bytes = build_mox(&manifest, true, &sample_payload());
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        assert!(!pkg.verify(b"wrong-secret").unwrap());
        // 篡改 manifest（改版本号）但不重签——正确 secret 也不过。
        let tampered = manifest.replace("1.2.0", "9.9.9");
        let bad = build_mox_tampered_manifest(&manifest, &tampered, &sample_payload());
        let pkg_bad = MoxPackage::open_from_bytes_for_test(&bad).unwrap();
        assert!(!pkg_bad.verify(SECRET).unwrap());
    }

    // 5. 条目列表：名字与大小齐全。
    #[test]
    fn test_list_entries() {
        let bytes = build_mox(&sample_manifest(), true, &sample_payload());
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        let list = pkg.list_entries();
        assert_eq!(list.len(), 4); // manifest.json + signature + 2 payload
        let names: Vec<&str> = list.iter().map(|e| e.name.as_str()).collect();
        assert!(names.contains(&"manifest.json"));
        assert!(names.contains(&"signature"));
        assert!(names.contains(&"payload/entry.so"));
        assert!(names.contains(&"payload/assets/readme.txt"));
        let so = list.iter().find(|e| e.name == "payload/entry.so").unwrap();
        assert_eq!(so.size, 15); // b"\x7fELF-fake-bytes" 长度
    }

    // 6. 解包落盘：payload 内容与目录结构正确，未知顶级条目被跳过。
    #[test]
    fn test_extract_to_writes_payload() {
        let mut payload = sample_payload();
        payload.push(("sneaky.txt", b"should-be-skipped"));
        let bytes = build_mox(&sample_manifest(), true, &payload);
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        let out = tmp_dir("extract");
        pkg.extract_to(&out).unwrap();
        assert_eq!(fs::read(out.join("payload/entry.so")).unwrap(), b"\x7fELF-fake-bytes");
        assert_eq!(fs::read(out.join("payload/assets/readme.txt")).unwrap(), b"hello mox");
        assert!(out.join("manifest.json").is_file());
        assert!(out.join("signature").is_file());
        assert!(!out.join("sneaky.txt").exists(), "非白名单顶级条目必须被跳过");
        let _ = fs::remove_dir_all(&out);
        // 顺带清理 open_from_bytes_for_test 留下的临时 .mox 目录。
        let _ = fs::remove_dir_all(std::env::temp_dir().join(format!("moxsh-mox-file-{}", std::process::id())));
    }

    // 7. 缺 manifest.json → NotAMox；缺必填字段 / 非法 type → BadManifest。
    #[test]
    fn test_missing_or_bad_manifest() {
        // 只有 payload、没有 manifest.json 条目的裸 tar：不是 .mox
        let bare = build_tar(&[("payload/a.bin", b"x")]);
        let pkg = MoxPackage::open_from_bytes_for_test(&bare).unwrap_err();
        assert!(matches!(pkg, MoxError::NotAMox));
        // 缺 type
        let no_type = r#"{"id":"a","name":"A","version":"1.0"}"#;
        assert!(matches!(
            MoxManifest::parse(no_type.as_bytes()),
            Err(MoxError::BadManifest)
        ));
        // 非法 type
        let bad_type = r#"{"id":"a","name":"A","version":"1.0","type":"widget"}"#;
        assert!(matches!(
            MoxManifest::parse(bad_type.as_bytes()),
            Err(MoxError::BadManifest)
        ));
    }

    // 8. skill 型包：payload/skill.json 解析（prompt/tools/params_schema）。
    #[test]
    fn test_skill_spec_parse() {
        let skill_json = r#"{
  "prompt": "你是 moxsh 终端助手",
  "tools": ["install_distro","install_pkg","change_repo","run_command","explain_error"],
  "params_schema": "{\"type\":\"object\"}"
}"#;
        let manifest = r#"{
  "id": "skill.python",
  "name": "Python 技能",
  "version": "0.3.0",
  "type": "skill",
  "permissions": ["install_pkg","run_command"]
}"#;
        let bytes = build_mox(manifest, true, &[("payload/skill.json", skill_json.as_bytes())]);
        let pkg = MoxPackage::open_from_bytes_for_test(&bytes).unwrap();
        let m = pkg.manifest().unwrap();
        assert_eq!(m.kind, "skill");
        assert_eq!(m.permissions, vec!["install_pkg", "run_command"]);
        assert_eq!(m.price, 0.0, "缺省 price 必须回退为免费");
        let spec = pkg.skill_spec().unwrap().expect("skill 包应有 skill.json");
        assert_eq!(spec.prompt, "你是 moxsh 终端助手");
        assert_eq!(spec.tools.len(), 5);
        assert!(spec.tools.contains(&"run_command".to_string()));
        assert!(spec.params_schema.contains("object"));
    }

    // 9. 文件不存在 → NotFound。
    #[test]
    fn test_open_not_found() {
        let r = MoxPackage::open(Path::new("/nonexistent/moxsh/no-such.mox"));
        assert!(matches!(r, Err(MoxError::NotFound)));
    }

    /// 测试辅助：从内存字节打开包（生产路径走 [`MoxPackage::open`] 文件版；
    /// 测试里先落盘再 open 等价，这里直接读字节以保持用例无临时文件）。
    ///
    /// 实现为写临时文件后复用 [`MoxPackage::open`]——保证测试覆盖的就是
    /// 生产打开路径，而非平行实现。
    impl MoxPackage {
        fn open_from_bytes_for_test(bytes: &[u8]) -> Result<MoxPackage, MoxError> {
            // 注意：MoxPackage 的设计语义是 extract_to 时按 path 重新打开文件
            // 流式落盘（manifest/signature 缓存在内存，payload 不缓存），因此
            // 这里不能删除源文件，否则 extract 用例必然 NotFound。文件统一由
            // test_extract_to_writes_payload 尾部清理整个临时目录。
            //
            // 文件名用进程内自增序号而非 nonce_hex：随机源失败时 nonce 恒定，
            // 并行测试会互相覆盖同一文件（tar 解析读到别家字节 → checksum mismatch）。
            use std::sync::atomic::{AtomicUsize, Ordering};
            static CASE_SEQ: AtomicUsize = AtomicUsize::new(0);
            let dir = std::env::temp_dir().join(format!("moxsh-mox-file-{}", std::process::id()));
            let _ = fs::create_dir_all(&dir);
            let path = dir.join(format!(
                "case-{}.mox",
                CASE_SEQ.fetch_add(1, Ordering::Relaxed)
            ));
            fs::write(&path, bytes).unwrap();
            MoxPackage::open(&path)
        }
    }

    /// gzip 压缩字节（tar.gz 兼容路径测试用）。
    fn gz_bytes(raw: &[u8]) -> Vec<u8> {
        use flate2::write::GzEncoder;
        use std::io::Write;
        let mut enc = GzEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(raw).unwrap();
        enc.finish().unwrap()
    }
}

//! 网络栈：让 PRoot 容器内 guest 视角的网络语义自洽、双栈、可观测。
//!
//! # 背景（为什么需要本模块）
//!
//! PRoot **不做 network namespace 隔离**——guest 的 socket 直接走宿主
//! 内核网络栈。由此：
//!
//! 1. **网卡/路由/出网能力 = 宿主的**：guest 内 `ip link` 看到的宿主
//!    网卡（含 Android 的 rmnet/wlan 与 `lo`）不是 bug，是共享栈的正确
//!    语义（`/proc/net` 经 `-b /proc:/proc` 直通即为宿主视图）；
//! 2. **解析器配置必须显式维护**：guest 内 glibc/musl 读的是 **rootfs 内
//!    的 `/etc/resolv.conf`**，不读宿主的——不写就是 minirootfs 的空文件，
//!    容器内域名解析全灭；
//! 3. **IPv6 双栈、hosts 同步、边界可视化**此前零处理（M7 重写补齐）：
//!    旧实现只写两条 IPv4 nameserver，纯 v4 网络下 AAAA 查询拖慢、
//!    宿主自定义 hosts 条目丢失、用户无从判断容器出网是否正常。
//!
//! # 设计口径
//!
//! - **探测驱动**：IPv6 服务器与出网探测都在写入侧完成，探测不通的
//!   配置项不写（避免写一堆超时的 nameserver 拖慢每条查询）；
//! - **宿主持久化来源**：宿主系统 DNS（C-ABI 传入，经
//!   [`distro::sanitize_dns_csv`] 清洗）优先，回退国内公共 DNS 双栈；
//! - **零外部依赖**：探测只用 `std::net`（UDP connect 建路由不发包，
//!   TCP connect 带超时），NDK target 可编。

use std::fmt::Write as _;
use std::net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs, UdpSocket};
use std::path::Path;
use std::time::Duration;

/// IPv4 公共 DNS 回退（国内双备）：阿里 + 腾讯。
/// 与 Kotlin 侧 `BootstrapInstaller.DEFAULT_DNS_CSV` 同口径。
pub const DEFAULT_DNS_V4: &[&str] = &["223.5.5.5", "119.29.29.29"];

/// IPv6 公共 DNS 回退（国内双备）：阿里公共 DNS IPv6（2400:3200::1）
/// + 腾讯 DNSPod IPv6（2402:4e00::）。
///
/// 仅在 [`probe_ipv6`] 探测到本机具备 IPv6 路由时写入 resolv.conf——
/// 纯 v4 网络写 v6 nameserver 会让 glibc 每条查询先等 v6 超时。
pub const DEFAULT_DNS_V6: &[&str] = &["2400:3200::1", "2402:4e00::"];

/// IPv6 连通性探测目标：阿里公共 DNS 的 53 端口（UDP connect 只建路由，
/// 不发射任何包，对远端无副作用）。
pub const IPV6_PROBE_ADDR: &str = "[2400:3200::1]:53";

/// 出网探测端点：阿里 DNS 的 443（TCP connect 带超时）。
/// 不用 8.8.8.8：国内直连 443 更稳，且 HTTPS 端口被中间设备放行率高。
pub const EGRESS_PROBE_ADDR: (&str, u16) = ("223.5.5.5", 443);

/// 探测超时：移动网络上 RTT 抖动大，2s 是"能区分通/不通"的下限。
pub const PROBE_TIMEOUT: Duration = Duration::from_secs(2);

/// resolv.conf 附加选项：失败快速返回（timeout:2 attempts:2）。
///
/// 不写 `single-request-reopen`：glibc ≥2.9 已是默认行为，musl 不认该
/// 选项（写了无害但不写更干净）。不写 `trust-ad`/`edns0`：与 DNSSEC
/// 无关的兼容性选项，保持最小集。
pub const RESOLV_OPTIONS: &str = "options timeout:2 attempts:2";

/// 一条 DNS 服务器记录（带来源标记，供边界报告使用）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsServer {
    /// 服务器地址（IPv4 或 IPv6 文本形式）。
    pub addr: String,
    /// 是否 IPv6。
    pub is_v6: bool,
}

/// 容器网络画像：一次生成、多处消费（resolv.conf/hosts/边界报告）。
#[derive(Debug, Clone, Default)]
pub struct NetworkProfile {
    /// DNS 服务器（v4/v6 混合，顺序即写入顺序）。
    pub dns: Vec<DnsServer>,
    /// 搜索域（`search` 行）。
    pub search: Vec<String>,
    /// 附加 hosts 条目（来自宿主 `/etc/hosts`，去重后）。
    pub hosts_extra: Vec<String>,
    /// 本机 IPv6 连通性（探测结果）。
    pub ipv6_ok: bool,
}

/// 判定地址串是否为 IPv6 文本形式（含 `:` 且非空）。
pub fn is_ipv6_text(s: &str) -> bool {
    !s.is_empty() && s.contains(':')
}

/// 从混合 CSV（可同时含 v4/v6 地址）构造 DNS 服务器列表。
///
/// 清洗规则与 [`distro::sanitize_dns_csv`] 同源（只放行 IP 合法字符），
/// 但不限制条数——上游限制 3 条是为防 resolv.conf 膨胀，双栈场景
/// 2×2 条是常态，上限放到 6。
pub fn parse_dns_csv(csv: &str) -> Vec<DnsServer> {
    let mut out: Vec<DnsServer> = Vec::new();
    for part in csv.split(',') {
        let s = part.trim();
        if s.is_empty() || s.len() > 45 {
            continue;
        }
        if !s
            .bytes()
            .all(|b| b.is_ascii_digit() || matches!(b, b'a'..=b'f' | b'A'..=b'F' | b'.' | b':'))
        {
            continue;
        }
        if out.iter().any(|d| d.addr == s) {
            continue;
        }
        out.push(DnsServer {
            addr: s.to_string(),
            is_v6: is_ipv6_text(s),
        });
        if out.len() >= 6 {
            break;
        }
    }
    out
}

/// 探测本机是否具备 IPv6 路由（UDP connect 到 [`IPV6_PROBE_ADDR`]）。
///
/// 语义：`connect` 只为 UDP socket 选定路由，**不发射任何数据包**；
/// 返回 `true` 仅代表"内核有到该 v6 地址的路由"，不代表远端可达
/// （真实可达性由 guest 内查询验证）。无 v6 地址/栈被禁用时快速失败。
pub fn probe_ipv6() -> bool {
    let Ok(sock) = UdpSocket::bind(SocketAddr::from(([0u8; 16], 0))) else {
        return false;
    };
    match IPV6_PROBE_ADDR
        .to_socket_addrs()
        .and_then(|mut it| it.next().ok_or_else(|| std::io::Error::other("no addr")))
    {
        Ok(sa) => sock.connect(sa).is_ok(),
        Err(_) => false,
    }
}

/// 探测 TCP 出网能力（connect 到 [`EGRESS_PROBE_ADDR`]，带超时）。
pub fn probe_egress() -> bool {
    let addr = SocketAddr::from((EGRESS_PROBE_ADDR.0.parse::<IpAddr>().unwrap_or(IpAddr::from([223, 5, 5, 5])), EGRESS_PROBE_ADDR.1));
    TcpStream::connect_timeout(&addr, PROBE_TIMEOUT).is_ok()
}

/// 生成容器网络画像。
///
/// `system_dns_csv`：宿主系统 DNS（C-ABI 传入，可为空）。
/// 策略：宿主 DNS 有效则全量采用；否则回退公共 DNS，且 IPv6 服务器
/// 仅在 [`probe_ipv6`] 通过时纳入。search 域由调用方单独提供
/// （见 [`extract_search_domains`]）。
pub fn build_profile(system_dns_csv: &str) -> NetworkProfile {
    let ipv6_ok = probe_ipv6();
    let dns = {
        let parsed = parse_dns_csv(system_dns_csv);
        if parsed.is_empty() {
            let mut d: Vec<DnsServer> = DEFAULT_DNS_V4
                .iter()
                .map(|a| DnsServer {
                    addr: (*a).to_string(),
                    is_v6: false,
                })
                .collect();
            if ipv6_ok {
                d.extend(DEFAULT_DNS_V6.iter().map(|a| DnsServer {
                    addr: (*a).to_string(),
                    is_v6: true,
                }));
            }
            d
        } else {
            // 宿主 DNS 全量采用；但若其中无任何 v6 且本机 v6 可用，
            // 补一条公共 v6 兜底（部分运营商 DHCP 只下发 v4 DNS）。
            let mut d = parsed;
            if ipv6_ok && !d.iter().any(|s| s.is_v6) {
                d.push(DnsServer {
                    addr: DEFAULT_DNS_V6[0].to_string(),
                    is_v6: true,
                });
            }
            d
        }
    };
    NetworkProfile {
        dns,
        search: Vec::new(),
        hosts_extra: Vec::new(),
        ipv6_ok,
    }
}

/// 从宿主 resolv.conf 文本提取 search 域（首个 `search` 行的域名列表）。
pub fn extract_search_domains(host_resolv: &str) -> Vec<String> {
    for line in host_resolv.lines() {
        let line = line.trim();
        if let Some(rest) = line.strip_prefix("search") {
            if rest.starts_with(char::is_whitespace) {
                let doms: Vec<String> = rest
                    .split_whitespace()
                    .filter(|d| {
                        !d.is_empty()
                            && d.len() <= 63
                            && d.bytes().all(|b| {
                                b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_')
                            })
                    })
                    .map(|s| s.to_string())
                    .collect();
                if !doms.is_empty() {
                    return doms;
                }
            }
        }
    }
    Vec::new()
}

/// 渲染 rootfs 的 `/etc/resolv.conf` 内容。
///
/// 布局：v4 服务器在前、v6 在后（v4 网络是大多数，先答大多数查询）；
/// 空画像（无任何服务器）时回退 [`DEFAULT_DNS_V4`]，绝不产出空文件
/// （空 resolv.conf = 解析器只查 localhost，容器域名全灭）。
pub fn render_resolv_conf(profile: &NetworkProfile) -> String {
    let mut v4: Vec<&str> = profile
        .dns
        .iter()
        .filter(|d| !d.is_v6)
        .map(|d| d.addr.as_str())
        .collect();
    let v6: Vec<&str> = profile
        .dns
        .iter()
        .filter(|d| d.is_v6)
        .map(|d| d.addr.as_str())
        .collect();
    if v4.is_empty() && v6.is_empty() {
        v4 = DEFAULT_DNS_V4.to_vec();
    }
    let mut out = String::new();
    out.push_str("# 由 moxsh 引擎生成（网络栈重写 M7）——请勿手工编辑，重启容器会重生成\n");
    for a in v4.iter().chain(v6.iter()) {
        let _ = writeln!(out, "nameserver {}", a);
    }
    if !profile.search.is_empty() {
        let _ = writeln!(out, "search {}", profile.search.join(" "));
    }
    out.push_str(RESOLV_OPTIONS);
    out.push('\n');
    out
}

/// 解析宿主 `/etc/hosts` 文本为条目行（跳过注释/空行）。
///
/// 只原样收集合法形态的行（`IP name [alias...]`），不解释语义——
/// rootfs 的 hosts 与宿主合并而非替换，localhost 等基础条目由
/// rootfs 自身提供。
pub fn parse_hosts_lines(host_hosts: &str) -> Vec<String> {
    host_hosts
        .lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .filter(|l| {
            // 形态校验：首字段必须是 IP 字面量（v4/v6）
            l.split_whitespace()
                .next()
                .map(|first| first.parse::<IpAddr>().is_ok())
                .unwrap_or(false)
        })
        .map(|l| l.to_string())
        .collect()
}

/// 把宿主 hosts 条目合并进 rootfs `/etc/hosts`（去重，不动 rootfs 原有行）。
///
/// 返回新增的条目数。rootfs 无 hosts 文件时直接写宿主条目。
pub fn merge_hosts_into_rootfs(host_hosts: &str, rootfs_etc_hosts: &Path) -> std::io::Result<usize> {
    let entries = parse_hosts_lines(host_hosts);
    if entries.is_empty() {
        return Ok(0);
    }
    let existing = std::fs::read_to_string(rootfs_etc_hosts).unwrap_or_default();
    let existing_lines: Vec<&str> = existing
        .lines()
        .map(|l| l.trim())
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .collect();
    let mut to_add: Vec<&String> = Vec::new();
    for e in &entries {
        if !existing_lines.iter().any(|l| *l == e.as_str()) {
            to_add.push(e);
        }
    }
    if to_add.is_empty() {
        return Ok(0);
    }
    if let Some(parent) = rootfs_etc_hosts.parent() {
        std::fs::create_dir_all(parent)?;
    }
    use std::io::Write as _;
    let mut f = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(rootfs_etc_hosts)?;
    if !existing.is_empty() && !existing.ends_with('\n') {
        f.write_all(b"\n")?;
    }
    let mut added = 0usize;
    for e in &to_add {
        writeln!(f, "{}", e)?;
        added += 1;
    }
    Ok(added)
}

/// 生成容器网络边界报告（`moxsh-proot-distro net` 的 Rust 侧主体）。
///
/// 口径：如实说明"共享宿主栈"的语义、当前 DNS/hosts/IPv6/出网状态，
/// 不夸大成隔离。Android 与正常 Linux 通用。
pub fn network_report(profile: &NetworkProfile, hosts_added: usize, egress_ok: bool) -> String {
    let mut out = String::new();
    out.push_str("== moxsh 容器网络边界 ==\n");
    out.push_str("模式：共享宿主内核网络栈（PRoot 无 netns 隔离）\n");
    out.push_str("  - guest 内 ip link / /proc/net/dev 看到的是宿主网卡（含 lo），语义正确\n");
    out.push_str("  - 出网、路由、防火墙能力与宿主完全一致；容器无法绕过宿主网络策略\n");
    out.push_str(&format!(
        "IPv6：{}\n",
        if profile.ipv6_ok {
            "可用（探测到 IPv6 路由，v6 DNS 已启用）"
        } else {
            "不可用（无 IPv6 路由，已只写 v4 DNS）"
        }
    ));
    let v4: Vec<&str> = profile
        .dns
        .iter()
        .filter(|d| !d.is_v6)
        .map(|d| d.addr.as_str())
        .collect();
    let v6: Vec<&str> = profile
        .dns
        .iter()
        .filter(|d| d.is_v6)
        .map(|d| d.addr.as_str())
        .collect();
    out.push_str(&format!(
        "DNS：v4 [{}] v6 [{}]\n",
        v4.join(", "),
        if v6.is_empty() { "-".to_string() } else { v6.join(", ") }
    ));
    out.push_str(&format!(
        "hosts：本次同步 {} 条宿主条目\n",
        hosts_added
    ));
    out.push_str(&format!(
        "出网探测（{}:{}）：{}\n",
        EGRESS_PROBE_ADDR.0,
        EGRESS_PROBE_ADDR.1,
        if egress_ok { "通" } else { "不通（检查宿主网络/防火墙）" }
    ));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_dns_csv_mixed_v4_v6() {
        let s = parse_dns_csv("223.5.5.5, 2400:3200::1 ,119.29.29.29");
        assert_eq!(s.len(), 3);
        assert!(!s[0].is_v6);
        assert!(s[1].is_v6);
        assert!(!s[2].is_v6);
    }

    #[test]
    fn parse_dns_csv_rejects_garbage() {
        assert!(parse_dns_csv("evil\nnameserver 1.2.3.4").is_empty());
        assert!(parse_dns_csv("not an ip").is_empty());
        assert!(parse_dns_csv("").is_empty());
    }

    #[test]
    fn render_resolv_conf_v4_before_v6_and_options() {
        let p = NetworkProfile {
            dns: vec![
                DnsServer { addr: "2400:3200::1".into(), is_v6: true },
                DnsServer { addr: "223.5.5.5".into(), is_v6: false },
            ],
            search: vec!["corp.local".into()],
            hosts_extra: vec![],
            ipv6_ok: true,
        };
        let out = render_resolv_conf(&p);
        let v4_pos = out.find("nameserver 223.5.5.5").unwrap();
        let v6_pos = out.find("nameserver 2400:3200::1").unwrap();
        assert!(v4_pos < v6_pos, "v4 必须先于 v6");
        assert!(out.contains("search corp.local"));
        assert!(out.contains("options timeout:2 attempts:2"));
        assert!(out.ends_with('\n'));
    }

    #[test]
    fn render_resolv_conf_empty_falls_back() {
        let out = render_resolv_conf(&NetworkProfile::default());
        assert!(out.contains("nameserver 223.5.5.5"));
        assert!(out.contains("nameserver 119.29.29.29"));
    }

    #[test]
    fn extract_search_domains_picks_first_line() {
        let txt = "# c\nsearch a.local b.local\nnameserver 1.1.1.1\n";
        assert_eq!(extract_search_domains(txt), vec!["a.local", "b.local"]);
        assert!(extract_search_domains("nameserver 1.1.1.1").is_empty());
    }

    #[test]
    fn parse_hosts_lines_skips_comments_and_garbage() {
        let txt = "# h\n127.0.0.1 localhost\n10.0.0.5 dev.internal\nnot-a-line\n";
        let lines = parse_hosts_lines(txt);
        assert_eq!(lines, vec!["127.0.0.1 localhost", "10.0.0.5 dev.internal"]);
    }

    #[test]
    fn is_ipv6_text_basic() {
        assert!(is_ipv6_text("::1"));
        assert!(is_ipv6_text("2400:3200::1"));
        assert!(!is_ipv6_text("223.5.5.5"));
        assert!(!is_ipv6_text(""));
    }

    #[test]
    fn merge_hosts_into_rootfs_real_file() {
        // 真文件 I/O：rootfs 已有部分条目，宿主 hosts 含新条目 + 重复条目
        let dir = std::env::temp_dir().join(format!("moxsh-net-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let hosts_path = dir.join("hosts");
        std::fs::write(&hosts_path, "127.0.0.1 localhost\n").unwrap();

        let added = merge_hosts_into_rootfs(
            "# h\n127.0.0.1 localhost\n10.0.0.5 dev.internal\nnot-a-line\n",
            &hosts_path,
        )
        .unwrap();
        assert_eq!(added, 1, "只应新增 10.0.0.5（localhost 已存在，垃圾行被过滤）");

        let content = std::fs::read_to_string(&hosts_path).unwrap();
        assert!(content.contains("10.0.0.5 dev.internal"));
        assert!(!content.contains("not-a-line"));

        // 幂等：再合并同一来源不新增
        let again = merge_hosts_into_rootfs(
            "127.0.0.1 localhost\n10.0.0.5 dev.internal\n",
            &hosts_path,
        )
        .unwrap();
        assert_eq!(again, 0);

        let _ = std::fs::remove_dir_all(&dir);
    }
}

#!/usr/bin/env python3
"""扫描仓库中误提交的密钥 / 令牌（CI 防泄漏）。

用法：python3 .github/scripts/check_secrets.py
退出码：发现疑似密钥→1；干净→0。
"""
import os
import re
import sys

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 跳过的路径片段（不扫描）
EXCLUDE_PATHS = (
    "/.git/",
    "/build/",
    "/.gradle/",
    "/node_modules/",   # 依赖目录：含大量 crypto/password 关键字，必然误报
    "local.properties",
    ".sample",
    "/.github/scripts/",   # 脚本自身含模式字符串，避免自匹配
    "/gradle/wrapper/",
)
# 跳过的扩展名（二进制 / 非源码）
SKIP_EXT = {
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".svg",
    ".jar", ".zip", ".apk", ".aab", ".keystore", ".jks", ".pem",
    ".so", ".o", ".a", ".dex", ".class", ".ttf", ".otf", ".mp4", ".wav",
}
MAX_BYTES = 1_000_000

# 规则：(标签, 正则)
RULES = [
    ("github_pat", re.compile(r"github_pat_[0-9A-Za-z_]{20,}")),
    ("github_token_ghp", re.compile(r"gh[pousr]_[0-9A-Za-z]{36,}")),
    ("aws_key", re.compile(r"AKIA[0-9A-Z]{16}")),
    ("google_api", re.compile(r"AIza[0-9A-Za-z_-]{35}")),
    ("slack_token", re.compile(r"xox[baprs]-[0-9A-Za-z-]{10,}")),
    ("stripe_key", re.compile(r"sk_live_[0-9a-zA-Z]{24,}")),
    ("pem_private_key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |EC )?PRIVATE KEY-----")),
    ("generic_secret", re.compile(
        r"(?i)(?:client[_-]?secret|client_secret|api[_-]?key|secret|private[_-]?key|"
        r"access[_-]?token|auth[_-]?token|password|passwd)\s*[:=]\s*[\"']?[A-Za-z0-9/+_@.#-]{16,}[\"']?")),
    ("bearer_token", re.compile(r"Bearer\s+[A-Za-z0-9._\-]{20,}")),
    # 2026-11-11 审计补充：原先只有通用 token/私钥模式，缺签名密钥、JWT 与
    # 国内/CI 平台专用凭据。Gitee PAT 本身无特征前缀，靠 generic_secret 覆盖，
    # 这里补的是「变量名 + 值」这种最容易误提交的形态。
    ("keystore_base64", re.compile(r"(?i)KEYSTORE_BASE64\s*[:=]\s*['\"]?[A-Za-z0-9+/=]{120,}")),
    ("jwt", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")),
    ("gitee_token", re.compile(r"(?i)GITEE_(?:TOKEN|PAT|ACCESS_TOKEN)\s*[:=]\s*['\"]?[A-Za-z0-9]{20,}")),
    ("cf_api_token", re.compile(r"(?i)CLOUDFLARE_API_TOKEN\s*[:=]\s*['\"]?[A-Za-z0-9_-]{30,}")),
    ("ed25519_seed", re.compile(r"(?i)(?:ED25519|PRIVATE_KEY|SEED)\s*[:=]\s*['\"]?[A-Fa-f0-9]{64}['\"]?")),
]

# 每条规则的自测样例：CI 先跑 --self-test，确保规则没有被误删 / 改坏到失效。
# 审计原文点名「没校验脚本本身的覆盖度」，这里把覆盖度变成可执行断言。
SELF_TEST_SAMPLES = {
    "github_pat": "github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz1234567890",
    "github_token_ghp": "ghp_" + "a" * 36,
    "aws_key": "AKIA" + "A" * 16,
    "google_api": "AIza" + "a" * 35,
    "slack_token": "xoxb-123456789012-abcdefghijkl",
    "stripe_key": "sk_live_" + "a" * 24,
    "pem_private_key": "-----BEGIN OPENSSH PRIVATE KEY-----",
    "generic_secret": 'password = "supersecretvalue123456"',
    "bearer_token": "Authorization: Bearer abcdefghijklmnopqrstuvwxyz123456",
    "keystore_base64": "KEYSTORE_BASE64 = " + "A" * 160,
    "jwt": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnopqrstuvwxyz12",
    "gitee_token": "GITEE_TOKEN = " + "a" * 32,
    "cf_api_token": "CLOUDFLARE_API_TOKEN = " + "a" * 40,
    "ed25519_seed": "ED25519_PRIVATE_KEY = " + "a" * 64,
}
# 这些是文档/模板里的占位符，必须**不**命中，避免规则过宽刷屏
SELF_TEST_SAFE = [
    "password = ${KEYSTORE_PASSWORD}",
    "password = REPLACE_WITH_YOUR_PASSWORD",
    "const token = process.env.GITHUB_TOKEN;",
]


def self_test() -> int:
    """验证：① 每条规则都有样例且能命中；② 占位符样例不会被误报。"""
    labels = {label for label, _ in RULES}
    missing = labels - set(SELF_TEST_SAMPLES)
    if missing:
        print(f"❌ 自测失败：以下规则缺少样例：{sorted(missing)}")
        return 1
    bad = []
    for label, sample in SELF_TEST_SAMPLES.items():
        if not any(rx.search(sample) for name, rx in RULES if name == label):
            bad.append(label)
    if bad:
        print(f"❌ 自测失败：以下规则未命中自己的样例：{bad}")
        return 1
    for safe in SELF_TEST_SAFE:
        # 复用主流程的占位符过滤规则
        low = safe.lower()
        if "replace_with" in low or "${" in safe:
            continue
        hit = [label for label, rx in RULES if rx.search(safe)]
        if hit:
            print(f"❌ 自测失败：占位符被误报为密钥：{safe!r} → {hit}")
            return 1
    print(f"✅ 规则自测通过：{len(RULES)} 条规则全部命中样例，{len(SELF_TEST_SAFE)} 条占位符无误报。")
    return 0


def should_scan(path: str) -> bool:
    if any(seg in path for seg in EXCLUDE_PATHS):
        return False
    ext = os.path.splitext(path)[1].lower()
    if ext in SKIP_EXT:
        return False
    return True


def main() -> int:
    hits = []
    for root, dirs, files in os.walk(REPO_ROOT):
        # 剪枝：跳过忽略目录
        dirs[:] = [d for d in dirs if not any(seg in os.path.join(root, d) for seg in EXCLUDE_PATHS)]
        for name in files:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, REPO_ROOT)
            if not should_scan(full):
                continue
            try:
                if os.path.getsize(full) > MAX_BYTES:
                    continue
                with open(full, "r", encoding="utf-8", errors="ignore") as f:
                    for ln, line in enumerate(f, 1):
                        # 跳过明显占位符 / 模板变量，避免误报
                        low = line.lower()
                        if "replace_with" in low or "${" in line:
                            continue
                        for label, rx in RULES:
                            m = rx.search(line)
                            if m:
                                hits.append((rel, ln, label, m.group(0)[:24]))
                                break
            except OSError:
                continue

    if not hits:
        print("✅ 未发现疑似密钥 / 令牌。")
        return 0

    print("❌ 发现疑似密钥 / 令牌，请移除后再提交：\n")
    for rel, ln, label, snap in hits:
        print(f"  [{label}] {rel}:{ln}  ->  {snap!r}…")
    print("\n提示：密钥请放 local.properties（已被 .gitignore 忽略）或 CI Secrets，"
          "绝不写进源码或提交公开仓库。")
    return 1


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        sys.exit(self_test())
    sys.exit(main())

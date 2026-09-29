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
]


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
    sys.exit(main())

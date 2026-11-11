#!/usr/bin/env python3
"""CI 守门：AAR metadata 约束（compileSdk / AGP 最低版本）在 Gradle 启动前校验。

事故背景（2026-11-11）：Dependabot 的 gradle-minor-patch 分组把
androidx.browser 1.8.0 → 1.10.0、androidx.core:core-ktx 1.13.1 → 1.19.1，
版本号看着都是 minor / patch，但 CI 到 `:app:checkReleaseAarMetadata` 才炸：

    Dependency 'androidx.browser:browser:1.10.0' requires compileSdk of at least 36
    ... requires Android Gradle plugin 8.9.1 or higher
    Dependency 'androidx.core:core:1.19.1' requires compileSdk of at least 37
    ... requires Android Gradle plugin 9.1.0 or higher
    （core-ktx 同上，共 6 项）

PR #116 / #118 因此必挂红。这类升级实质是「compileSdk + AGP 大版本迁移」，
不能塞进自动分组 PR。这里把约束变成可执行断言，让机器人 PR 在 5 分钟
Gradle 编译之前就拿到一句人话，而不是一屏 AAR metadata 堆栈。

同时它也是「防回退」：将来真做完迁移（compileSdk 37 / AGP 9.1.0），
本脚本会自动放行，不会把已验证的升级永久锁死。

用法：
  python3 .github/scripts/check_sdk_boundaries.py moxsh
  python3 .github/scripts/check_sdk_boundaries.py moxsh --self-test
"""
import glob
import os
import re
import sys

# 依赖 → (触发约束的最低依赖版本, 要求的 compileSdk, 要求的 AGP 最低版本)
# 只登记本项目在真实 CI 里撞过、有确切报错文本的约束，不臆测其它库。
CONSTRAINTS = {
    ("androidx.browser", "browser"): ("1.10.0", 36, "8.9.1"),
    ("androidx.core", "core"): ("1.19.0", 37, "9.1.0"),
    ("androidx.core", "core-ktx"): ("1.19.0", 37, "9.1.0"),
}

DEP_RX = re.compile(
    r'(?:implementation|api|compileOnly|runtimeOnly|androidTestImplementation|'
    r'testImplementation|debugImplementation|releaseImplementation)\s*\(\s*'
    r'"([^":]+):([^":]+):([^"]+)"'
)
SDK_RX = re.compile(r'\bcompileSdk\s*=\s*(\d+)')
AGP_RX = re.compile(r'com\.android\.(?:application|library)"\)\s*version\s*"([^"]+)"')
AGP_COORD_RX = re.compile(r'com\.android\.tools\.build:gradle:([^"\s]+)')


def vt(text):
    """把 '1.19.1' / '8.9.1' 变成可比较的元组，位数不足补 0。"""
    parts = [int(x) for x in re.findall(r'\d+', text or "")][:3]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def parse(texts, agp_text=None):
    """texts: {相对路径: 文件内容}。返回 (sdk_list, agp, deps)。"""
    sdks, deps = [], []
    for path, txt in texts.items():
        for m in SDK_RX.finditer(txt):
            sdks.append((int(m.group(1)), path))
        for m in DEP_RX.finditer(txt):
            deps.append((m.group(1), m.group(2), m.group(3), path))
        if agp_text is None:
            a = AGP_RX.search(txt) or AGP_COORD_RX.search(txt)
            if a:
                agp_text = a.group(1)
    return sdks, agp_text, deps


def evaluate(texts, agp_text=None):
    """返回 (errors, info)。errors 非空即应让 CI 失败。"""
    sdks, agp, deps = parse(texts, agp_text)
    errors, info = [], []
    if not sdks:
        return [], ["未找到 compileSdk 声明，跳过 AAR 约束校验"]

    min_sdk = min(s for s, _ in sdks)
    distinct = sorted({s for s, _ in sdks})
    info.append(f"compileSdk = {distinct}，按最严格的 {min_sdk} 校验")
    info.append(f"AGP = {agp or '未识别'}")

    seen = set()
    for group, artifact, ver, path in deps:
        rule = CONSTRAINTS.get((group, artifact))
        if not rule or vt(ver) < vt(rule[0]):
            continue
        key = (group, artifact, ver, rule)
        if key in seen:
            continue
        seen.add(key)
        _, need_sdk, need_agp = rule
        problems = []
        if min_sdk < need_sdk:
            problems.append(
                f"compileSdk 至少 {need_sdk}，当前 {min_sdk}"
                f"（{', '.join(sorted({p for s, p in sdks if s < need_sdk})[:3])}）"
            )
        if agp is None or vt(agp) < vt(need_agp):
            problems.append(f"AGP 至少 {need_agp}，当前 {agp or '未识别'}")
        if problems:
            errors.append(
                f"依赖 {group}:{artifact}:{ver}（{path}）触发 AAR metadata 约束："
                + "；".join(problems)
            )

    if errors:
        errors.append(
            "这不是 minor/patch 依赖升级，而是 compileSdk + AGP 大版本迁移："
            "需要统一全部模块的 compileSdk / targetSdk、CI 安装对应 Android platform、"
            "复核 targetSdk 行为变化并跑通真实构建，之后才能合并。"
            "自动分组 PR 请勿直接重试或合并。"
        )
    else:
        info.append("AAR metadata 约束全部满足")
    return errors, info


def self_test(root):
    """正向 / 负向 / 迁移后放行，三组都要真实跑过才算守门有效。"""
    real = load(root)
    ok = True

    errs, _ = evaluate(real)
    ok &= _expect("正向：当前 main 基线", not errs, "应放行", errs)

    bad = dict(real)
    for p, txt in list(bad.items()):
        if "androidx.core:core-ktx:1.13.1" in txt:
            bad[p] = txt.replace("androidx.core:core-ktx:1.13.1",
                                 "androidx.core:core-ktx:1.19.1")
    errs, _ = evaluate(bad)
    hit = any("compileSdk 至少 37" in e for e in errs)
    ok &= _expect("负向：core-ktx 升 1.19.1", bool(errs) and hit,
                  "应拦截并点名 compileSdk 37", errs)

    bad2 = dict(real)
    for p, txt in list(bad2.items()):
        if "androidx.browser:browser:1.8.0" in txt:
            bad2[p] = txt.replace("androidx.browser:browser:1.8.0",
                                  "androidx.browser:browser:1.10.0")
    errs, _ = evaluate(bad2)
    hit2 = any("compileSdk 至少 36" in e for e in errs)
    ok &= _expect("负向：browser 升 1.10.0", bool(errs) and hit2,
                  "应拦截并点名 compileSdk 36", errs)

    migrated = dict(bad)
    migrated = {p: re.sub(r'\bcompileSdk\s*=\s*\d+', 'compileSdk = 37', txt)
                for p, txt in migrated.items()}
    errs, _ = evaluate(migrated, agp_text="9.1.0")
    ok &= _expect("迁移后：compileSdk 37 + AGP 9.1.0", not errs,
                  "应自动放行，不锁死已验证迁移", errs)

    print("=== self-test " + ("PASS ===" if ok else "FAIL ==="))
    return 0 if ok else 1


def _expect(name, cond, want, errs):
    flag = "✓" if cond else "✗"
    print(f"{flag} {name}：{want}")
    if errs:
        for e in errs[:3]:
            print(f"    {e[:150]}")
    return cond


def load(root):
    texts = {}
    for path in glob.glob(os.path.join(root, "**", "*.gradle.kts"), recursive=True):
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            texts[os.path.relpath(path, root)] = f.read()
    return texts


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "moxsh"
    if "--self-test" in sys.argv:
        return self_test(root)
    errors, info = evaluate(load(root))
    for line in info:
        print("  " + line)
    if errors:
        print("❌ AAR metadata 约束不满足：")
        for e in errors:
            print("  - " + e)
        return 1
    print("✓ AAR metadata 约束校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())

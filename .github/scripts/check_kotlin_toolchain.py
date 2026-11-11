#!/usr/bin/env python3
"""CI 守门：依赖的 Kotlin metadata 版本不得超出当前 Kotlin 编译器可读上限。

事故背景（2026-11-11，PR #119 逐层真实 CI 定位）：Dependabot 的
gradle-minor-patch 分组把几个库升到「版本号只是 minor/patch」的新版，
但它们是用 Kotlin 2.x 编译的，metadata 版本超出 Kotlin 1.9.25 编译器
能读的上限（2.0.0），于是 Gradle 跑了 3~4 分钟后才在
:shared / :plugins:plugin-ai:compileReleaseKotlin 爆出几十个
「Unresolved reference: listOf / runCatching / withContext」——看起来像
业务代码全错了，其实是标准库 classpath 被 incompatible classes 污染。

真实证据：
  run 42987971371
    kotlinx-coroutines-android:1.11.0 / kotlin-stdlib:2.2.20
    → binary metadata 2.2.0，expected 1.9.0（编译器可读上限 2.0.0）
  run 42988921137
    androidx.annotation:annotation-jvm:1.11.0 / kotlin-stdlib:2.1.20
    → binary metadata 2.1.0，expected 1.9.0

这类升级的实质是「Kotlin 1.9.x → 2.x 工具链迁移」（换 Compose Compiler
Gradle Plugin、AGP、compileSdk 连锁），不能塞进自动依赖分组 PR。
本脚本在 Gradle 启动前把约束变成一句人话；将来真迁到 Kotlin 2.x 后
会自动放行，不锁死已验证的升级。

用法：
  python3 .github/scripts/check_kotlin_toolchain.py moxsh
  python3 .github/scripts/check_kotlin_toolchain.py moxsh --self-test
"""
import glob
import os
import re
import sys

# (group, artifact) → (触发约束的最低依赖版本, 该版本引入的 metadata, 最低可用 Kotlin)
# 只登记在真实 CI 日志里有确切报错的依赖，不臆测其它库。
KOTLIN2_DEPS = {
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android"): ("1.11.0", "2.2.0", "2.2.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"): ("1.11.0", "2.2.0", "2.2.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"): ("1.11.0", "2.2.0", "2.2.0"),
    ("androidx.annotation", "annotation"): ("1.11.0", "2.1.0", "2.1.0"),
    ("androidx.annotation", "annotation-jvm"): ("1.11.0", "2.1.0", "2.1.0"),
}

# Kotlin 1.9.x 编译器能读的 metadata 上限（CI 日志：can read versions up to 2.0.0）
COMPILER_METADATA_CEILING = {
    1: "2.0.0",
    2: "9.9.0",  # Kotlin 2.x 编译器按各自版本处理，这里不再用旧上限误挡
}

DEP_RX = re.compile(
    r'(?:implementation|api|compileOnly|runtimeOnly|androidTestImplementation|'
    r'testImplementation|debugImplementation|releaseImplementation)\s*\(\s*'
    r'"([^":]+):([^":]+):([^"]+)"'
)
KOTLIN_RX = re.compile(r'org\.jetbrains\.kotlin\.android"\)\s*version\s*"([^"]+)"')


def vt(text):
    parts = [int(x) for x in re.findall(r'\d+', text or "")][:3]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)


def load(root):
    texts = {}
    for path in glob.glob(os.path.join(root, "**", "*.gradle.kts"), recursive=True):
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            texts[os.path.relpath(path, root)] = f.read()
    return texts


def evaluate(texts, kotlin_text=None):
    """返回 (errors, info)。"""
    kotlin, deps = kotlin_text, []
    for path, txt in texts.items():
        if kotlin is None:
            m = KOTLIN_RX.search(txt)
            if m:
                kotlin = m.group(1)
        for m in DEP_RX.finditer(txt):
            deps.append((m.group(1), m.group(2), m.group(3), path))

    info = [f"Kotlin 插件 = {kotlin or '未识别'}"]
    if kotlin is None:
        return [], info + ["未识别 Kotlin 版本，跳过 metadata 校验"]

    major = vt(kotlin)[0]
    ceiling = COMPILER_METADATA_CEILING.get(major, "9.9.0")
    info.append(f"编译器可读 metadata 上限 = {ceiling}")

    errors, seen = [], set()
    for group, artifact, ver, path in deps:
        rule = KOTLIN2_DEPS.get((group, artifact))
        if not rule or vt(ver) < vt(rule[0]):
            continue
        min_dep, metadata, min_kotlin = rule
        if vt(metadata) <= vt(ceiling):
            continue
        key = (group, artifact, ver)
        if key in seen:
            continue
        seen.add(key)
        errors.append(
            f"{group}:{artifact}:{ver}（{path}）引入 Kotlin {metadata} metadata，"
            f"当前 Kotlin {kotlin} 编译器只能读到 {ceiling}；"
            f"需要先把工具链迁到 Kotlin ≥ {min_kotlin}"
        )

    if errors:
        errors.append(
            "这类依赖升级的实质是 Kotlin 1.9.x → 2.x 工具链迁移"
            "（Compose Compiler Gradle Plugin / AGP / compileSdk 会连锁变更），"
            "不是能自动合并的 minor/patch。请把它们从 Dependabot 分组 PR 里排除，"
            "由人工做独立迁移并跑通真实构建。"
        )
    else:
        info.append("Kotlin metadata 兼容性校验通过")
    return errors, info


def _expect(name, cond, want, errs):
    print(("✓" if cond else "✗") + f" {name}：{want}")
    for e in errs[:2]:
        print("    " + e[:160])
    return cond


def self_test(root):
    real = load(root)
    ok = True
    errs, _ = evaluate(real)
    ok &= _expect("正向：当前 main 基线", not errs, "应放行", errs)

    bad = {p: t for p, t in real.items()}
    bad["shared/build.gradle.kts"] = (
        bad.get("shared/build.gradle.kts", "")
        + '\nimplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")\n'
    )
    errs, _ = evaluate(bad)
    hit = any("2.2.0" in e and "coroutines" in e for e in errs)
    ok &= _expect("负向：coroutines 1.11.0", bool(errs) and hit,
                  "应拦截并点名 metadata 2.2.0", errs)

    bad2 = dict(bad)
    bad2["plugins/plugin-ai/build.gradle.kts"] = (
        bad2.get("plugins/plugin-ai/build.gradle.kts", "")
        + '\nimplementation("androidx.annotation:annotation:1.11.0")\n'
    )
    errs, _ = evaluate(bad2)
    hit2 = any("annotation" in e and "2.1.0" in e for e in errs)
    ok &= _expect("负向：annotation 1.11.0", bool(errs) and hit2,
                  "应拦截并点名 metadata 2.1.0", errs)

    errs, _ = evaluate(bad2, kotlin_text="2.2.0")
    ok &= _expect("迁移后：Kotlin 2.2.0", not errs,
                  "应自动放行，不锁死已验证迁移", errs)

    print("=== self-test " + ("PASS ===" if ok else "FAIL ==="))
    return 0 if ok else 1


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    root = args[0] if args else "moxsh"
    if "--self-test" in sys.argv:
        return self_test(root)
    errors, info = evaluate(load(root))
    for line in info:
        print("  " + line)
    if errors:
        print("❌ Kotlin 工具链 / metadata 约束不满足：")
        for e in errors:
            print("  - " + e)
        return 1
    print("✓ Kotlin 工具链 / metadata 约束校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())

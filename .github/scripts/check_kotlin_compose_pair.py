#!/usr/bin/env python3
"""CI 守门：Kotlin 插件版本与各模块 Compose Compiler 版本必须配对。

事故背景（2026-11-11）：Dependabot 的 gradle-minor-patch 分组把 Kotlin
1.9.24 → 1.9.25，却没有同步 11 个模块里的 kotlinCompilerExtensionVersion
（仍是 1.5.14），CI 直接报：

    This version (1.5.14) of the Compose Compiler requires Kotlin version 1.9.24
    but you appear to be using Kotlin version 1.9.25 ...

依赖 PR #116 因此挂红。这里把配对关系变成可执行断言：
  ① 所有模块的 Compose Compiler 版本必须一致；
  ② 已登记的 Kotlin 版本必须搭配对应的 Compose Compiler 版本；
  ③ 未登记的组合只提示、不误挡未来升级（但要求人工确认）。

用法：python3 .github/scripts/check_kotlin_compose_pair.py moxsh
"""
import glob
import os
import re
import sys

# 只登记本项目实际编译验证过的组合。升级 Kotlin / Compose 时请同步登记，
# 或先跑一次真实 CI 确认后再加入，避免这张表本身变成错误来源。
KNOWN_PAIRS = {
    "1.9.24": "1.5.14",
    "1.9.25": "1.5.15",
}


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else "moxsh"
    top = os.path.join(root, "build.gradle.kts")
    if not os.path.isfile(top):
        print(f"⚠️ 找不到 {top}，跳过版本配对校验")
        return 0

    with open(top, "r", encoding="utf-8") as f:
        top_txt = f.read()
    m = re.search(r'org\.jetbrains\.kotlin\.android"\)\s*version\s*"([^"]+)"', top_txt)
    if not m:
        print("⚠️ 根 build.gradle.kts 未找到 Kotlin 插件版本，跳过版本配对校验")
        return 0
    kotlin = m.group(1)

    found = {}
    for path in glob.glob(os.path.join(root, "**", "*.gradle.kts"), recursive=True):
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            txt = f.read()
        for ver in re.findall(r'kotlinCompilerExtensionVersion\s*=\s*"([^"]+)"', txt):
            found.setdefault(ver, []).append(os.path.relpath(path, root))

    if not found:
        print("⚠️ 未找到任何 composeOptions，跳过版本配对校验")
        return 0

    if len(found) > 1:
        print(f"❌ 各模块 Compose Compiler 版本不一致：{sorted(found)}")
        for ver, files in sorted(found.items()):
            print(f"   {ver}: {', '.join(files[:4])}{' …' if len(files) > 4 else ''}")
        print("   请把所有模块的 kotlinCompilerExtensionVersion 统一成同一个值。")
        return 1

    actual = next(iter(found))
    expected = KNOWN_PAIRS.get(kotlin)
    if expected is None:
        print(f"⚠️ Kotlin {kotlin} 未在已知配对表登记（当前 Compose {actual}）。")
        print("   请先用真实 CI 验证兼容性，再把组合加进 KNOWN_PAIRS。本步不阻断构建。")
        return 0

    if actual != expected:
        print(f"❌ 版本不配对：Kotlin {kotlin} 需要 Compose Compiler {expected}，实际是 {actual}")
        print(f"   涉及 {len(found[actual])} 个模块；请把它们的 kotlinCompilerExtensionVersion 统一改为 {expected}。")
        print("   参考：https://developer.android.com/jetpack/androidx/releases/compose-kotlin")
        return 1

    print(f"✓ Kotlin {kotlin} ↔ Compose Compiler {actual} 配对正确（{len(found[actual])} 个模块一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())

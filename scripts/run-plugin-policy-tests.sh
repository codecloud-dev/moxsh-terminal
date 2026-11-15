#!/usr/bin/env bash
# 编译并运行「禁止回滚」生产策略类的 JVM 自测（零测试框架、零 Android SDK）。
#
# 为什么需要它：策略判定是安全硬需求，生产代码在 plugin-store 模块；
# 本脚本用 Gradle 内置的 Kotlin 编译器（K2JVMCompiler embeddable）+ kotlin-stdlib
# 直接把 PluginVersionPolicy.kt 与 PluginVersionPolicyTest.kt 编成 class 并运行，
# 逐行验证 semver 比较 / revoked 拦截 / minVersion 地板 / 版本单调 / 隔离判定，
# 不需要 Android SDK、不需要 gradle assemble、不依赖网络。
#
# 配套：moxsh-plugins 仓库的 scripts/check-plugin-policy.mjs 是校验层参考实现 +
# 三条安全断言（绑定真实 catalog.json），两边向量表逐行对应。
#
# 用法：bash scripts/run-plugin-policy-tests.sh
#   可用环境变量覆盖：KOTLIN_HOME（含 kotlin-compiler-embeddable*.jar 与
#   kotlin-stdlib*.jar 的目录）、JAVA_HOME。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO_ROOT/moxsh/plugins/plugin-store/src"
POLICY="$MODULE/main/java/com/moxsh/plugin/store/PluginVersionPolicy.kt"
TEST="$MODULE/test/java/com/moxsh/plugin/store/PluginVersionPolicyTest.kt"

# 定位 Kotlin 编译器 jar：优先 KOTLIN_HOME，再扫描常见 Gradle 安装目录。
find_jars() {
    local base
    for base in "${KOTLIN_HOME:-}" \
        "$HOME/.sdkman/candidates/gradle/current/lib" \
        /root/.sdkman/candidates/gradle/current/lib \
        "$HOME/.gradle"; do
        [ -n "$base" ] && [ -d "$base" ] || continue
        local compiler stdlib
        compiler=$(ls -1 "$base"/kotlin-compiler-embeddable-*.jar 2>/dev/null | head -1)
        stdlib=$(ls -1 "$base"/kotlin-stdlib-*.jar 2>/dev/null | head -1)
        if [ -z "$compiler" ] || [ -z "$stdlib" ]; then
            compiler=$(find "$base" -name 'kotlin-compiler-embeddable-*.jar' 2>/dev/null | head -1)
            stdlib=$(find "$base" -name 'kotlin-stdlib-*.jar' 2>/dev/null | head -1)
        fi
        if [ -n "$compiler" ] && [ -n "$stdlib" ]; then
            echo "$compiler|$stdlib|$(dirname "$compiler")"
            return 0
        fi
    done
    return 1
}

INFO=$(find_jars) || {
    echo "✗ 找不到 kotlin-compiler-embeddable / kotlin-stdlib jar。" >&2
    echo "  请设置 KOTLIN_HOME 指向含这两个 jar 的目录（如 Gradle 的 lib/）。" >&2
    exit 2
}
COMPILER=${INFO%%|*}; REST=${INFO#*|}; STDLIB=${REST%%|*}; LIBDIR=${REST##*|}
CP=$(ls -1 "$LIBDIR"/*.jar 2>/dev/null | paste -sd: -)
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

echo "=== 编译生产策略类 + 测试（K2JVMCompiler）==="
"$JAVA_BIN" -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$STDLIB" \
    "$POLICY" "$TEST" -d "$OUT" || { echo "✗ 编译失败" >&2; exit 1; }

echo "=== 运行 com.moxsh.plugin.store.PluginVersionPolicyTestKt ==="
"$JAVA_BIN" -cp "$OUT:$STDLIB" com.moxsh.plugin.store.PluginVersionPolicyTestKt

package com.moxsh.plugin.store

import kotlin.system.exitProcess

/**
 * 生产类 [PluginVersionPolicy] 的 JVM 自测（零测试框架，`main` 直接跑）。
 *
 * 这是「真跑生产代码」的入口：用 Gradle 内置 Kotlin 编译器把本文件与
 * `PluginVersionPolicy.kt` 一起编成 class，再 `java` 运行，即可逐行验证
 * 版本比较 / revoked 拦截 / minVersion 地板 / 版本单调 / 隔离判定。
 *
 * 向量表与 `moxsh-plugins/scripts/check-plugin-policy.mjs` 逐行对应；
 * 这里的 MIN/REV/GLASS_* 取自该仓库 catalog.json（repoVersion 1.0.3）的真实策略值。
 * 改动策略或向量时必须同步两处，避免「校验绿、运行时错」的假绿。
 *
 * 用法（见 scripts 说明）：
 *   java -cp <out>:<kotlin-stdlib.jar> com.moxsh.plugin.store.PluginVersionPolicyTestKt
 */
private var pass = 0
private var fail = 0

private fun check(cond: Boolean, label: String, extra: String = "") {
    if (cond) {
        pass++
        println("  ✓ $label${if (extra.isEmpty()) "" else "  $extra"}")
    } else {
        fail++
        System.err.println("  ✗ $label${if (extra.isEmpty()) "" else "  $extra"}")
    }
}

private fun vd(v: PluginVersionPolicy.Verdict) =
    "allowed=${v.allowed} reason=${v.reason}${if (v.detail.isEmpty()) "" else " · ${v.detail}"}"

// catalog.json 的真实策略值（hello-mox / glass-terminal-theme，repoVersion 1.0.3）
private const val MIN = "1.0.1"
private val REV = listOf("1.0.0")
private const val GLASS_MIN = "1.0.1"
private val GLASS_REV = listOf("1.0.0", "0.9.2")

fun main() {
    println("=== semver 比较器单元校验（生产 PluginVersionPolicy.compare） ===")
    val cmpCases = listOf(
        Triple("1.0.0", "1.0.1", -1),
        Triple("1.2.0", "1.1.9", 1),
        Triple("1.0.0", "1.0.0", 0),
        Triple("1.0.0-alpha", "1.0.0", -1),
        Triple("1.0.0-alpha.1", "1.0.0-alpha.beta", -1),
        Triple("1.0.0-beta", "1.0.0-beta.2", -1),
        Triple("1.0.0+build1", "1.0.0+build2", 0),
        Triple("1.0", "1.0.0", 0),
        Triple("1.0.0-rc.1", "1.0.0", -1),
    )
    for ((a, b, want) in cmpCases) {
        val got = PluginVersionPolicy.compare(a, b)
        check(got == want, "compare($a, $b) = $want", "(实际 $got)")
    }
    check(PluginVersionPolicy.compare("bad", "1.0.0") == null, "compare 非法版本返回 null")

    println("\n=== parse 边界校验 ===")
    check(PluginVersionPolicy.compare("01.2.3", "1.2.3") == null, "parse 拒绝前导零核心段")
    check(PluginVersionPolicy.compare("1.2.3-01", "1.2.3") == null, "parse 拒绝前导零预发布数字标识符")
    check(PluginVersionPolicy.compare("1.2.3+", "1.2.3") == null, "parse 拒绝空构建元数据")
    check(PluginVersionPolicy.compare("1.2.3.4", "1.2.3") == null, "parse 拒绝 4 段核心版本")

    println("\n=== 三条安全断言（生产 PluginVersionPolicy.evaluate，真实 catalog 策略） ===")
    // 断言 1：回滚到 revoked 版本被拒
    run {
        val cand = "1.0.0"
        val v = PluginVersionPolicy.evaluate(cand, MIN, REV, "1.0.1")
        check(!v.allowed && v.reason == PluginVersionPolicy.Reason.REVOKED,
            "断言1 回滚到 revoked 版本被拒",
            "[candidate=$cand installed=1.0.1] → ${vd(v)}")
    }
    // 断言 2：回滚到低于 minVersion 被拒（0.9.9 不在撤销名单 → 专门验证地板门）
    run {
        val cand = "0.9.9"
        val v = PluginVersionPolicy.evaluate(cand, MIN, REV, "1.0.1")
        check(!PluginVersionPolicy.isRevoked(cand, REV),
            "断言2 前置：0.9.9 不在撤销名单（确保测的是 minVersion 门而非 revoked 门）")
        check(!v.allowed && v.reason == PluginVersionPolicy.Reason.BELOW_MIN_VERSION,
            "断言2 回滚到低于 minVersion 被拒", "[candidate=$cand minVersion=$MIN] → ${vd(v)}")
    }
    // 断言 3：升级通过
    run {
        val cand = "1.1.0"
        val v = PluginVersionPolicy.evaluate(cand, MIN, REV, "1.0.1")
        check(v.allowed && v.reason == PluginVersionPolicy.Reason.ALLOW,
            "断言3 升级通过", "[candidate=$cand installed=1.0.1] → ${vd(v)}")
    }

    println("\n=== 扩展向量表（semver / 单调 / 绕过 / 边界） ===")
    // 标签, candidate, min, revoked, installed, 期望 allowed, 期望 reason
    data class Vec(
        val label: String, val cand: String, val min: String?, val rev: List<String>,
        val installed: String?, val wantAllowed: Boolean, val wantReason: PluginVersionPolicy.Reason,
    )
    val vectors = listOf(
        Vec("全新安装 revoked 版本也被拒", "1.0.0", "1.0.1", listOf("1.0.0"), null, false, PluginVersionPolicy.Reason.REVOKED),
        Vec("版本单调：低于已装但≥min 也拒(DOWNGRADE)", "1.0.2", "1.0.1", listOf("1.0.0"), "1.2.0", false, PluginVersionPolicy.Reason.DOWNGRADE),
        Vec("同版本重装通过", "1.0.1", "1.0.1", listOf("1.0.0"), "1.0.1", true, PluginVersionPolicy.Reason.ALLOW),
        Vec("全新安装当前版本通过", "1.0.1", "1.0.1", listOf("1.0.0"), null, true, PluginVersionPolicy.Reason.ALLOW),
        Vec("revoked 改写 build 元数据绕过被拒", "1.0.0+build.7", "1.0.1", listOf("1.0.0"), "1.0.1", false, PluginVersionPolicy.Reason.REVOKED),
        Vec("revoked 改写省略 patch 绕过被拒", "1.0", "1.0.1", listOf("1.0.0"), "1.0.1", false, PluginVersionPolicy.Reason.REVOKED),
        Vec("预发布低于正式版被 minVersion 拦截", "1.0.1-rc.1", "1.0.1", listOf("1.0.0"), null, false, PluginVersionPolicy.Reason.BELOW_MIN_VERSION),
        Vec("非法版本被拒", "not.a.version", "1.0.1", listOf("1.0.0"), null, false, PluginVersionPolicy.Reason.INVALID_VERSION),
        Vec("minVersion 非法 → fail-closed 拒绝", "1.2.0", "garbage", listOf("1.0.0"), null, false, PluginVersionPolicy.Reason.INVALID_VERSION),
        Vec("glass 主题 0.9.2(第二个 revoked)被拒", "0.9.2", GLASS_MIN, GLASS_REV, "1.0.1", false, PluginVersionPolicy.Reason.REVOKED),
        Vec("glass 主题 1.0.1→1.2.0 升级通过", "1.2.0", GLASS_MIN, GLASS_REV, "1.0.1", true, PluginVersionPolicy.Reason.ALLOW),
    )
    for (x in vectors) {
        val v = PluginVersionPolicy.evaluate(x.cand, x.min, x.rev, x.installed)
        check(v.allowed == x.wantAllowed && v.reason == x.wantReason, x.label,
            "[candidate=${x.cand}] → 期望 ${x.wantAllowed}/${x.wantReason}，实际 ${vd(v)}")
    }

    println("\n=== 隔离停用判定（生产 PluginVersionPolicy.evaluateInstalled） ===")
    run {
        val r1 = PluginVersionPolicy.evaluateInstalled("1.0.0", MIN, REV)
        check(!r1.allowed && r1.reason == PluginVersionPolicy.Reason.REVOKED,
            "已装 1.0.0(漏洞版) → 判定违规需隔离", vd(r1))
        val r2 = PluginVersionPolicy.evaluateInstalled("0.9.9", MIN, REV)
        check(!r2.allowed && r2.reason == PluginVersionPolicy.Reason.BELOW_MIN_VERSION,
            "已装 0.9.9(低于地板) → 判定违规需隔离", vd(r2))
        val r3 = PluginVersionPolicy.evaluateInstalled("1.0.1", MIN, REV)
        check(r3.allowed, "已装 1.0.1(合规版) → 不需隔离", vd(r3))
        val r4 = PluginVersionPolicy.evaluateInstalled(null, MIN, REV)
        check(!r4.allowed, "已装版本缺失/损坏 → 判定违规需隔离(fail-closed)", vd(r4))
    }

    println("\n=== 目录合并 stricterMin（本地/内置源不得拉低可信地板） ===")
    check(PluginVersionPolicy.stricterMin("", "1.0.1") == "1.0.1", "stricterMin(空, 1.0.1) = 1.0.1")
    check(PluginVersionPolicy.stricterMin("1.0.1", "") == "1.0.1", "stricterMin(1.0.1, 空) = 1.0.1")
    check(PluginVersionPolicy.stricterMin("1.0.1", "1.2.0") == "1.2.0", "stricterMin 取更高地板")
    check(PluginVersionPolicy.stricterMin("1.2.0", "1.0.1") == "1.2.0", "stricterMin 取更高地板(反序)")

    println("\n结果：$pass 通过 / $fail 失败")
    exitProcess(if (fail == 0) 0 else 1)
}

package com.moxsh.plugin.boot

import android.content.Context
import android.content.SharedPreferences

/**
 * 自启命令持久化（重写 Termux:Boot 的 `~/.termux/boot/*.sh` 文件约定，
 * 改为结构化 SharedPreferences 存储，docs/plugins-rewrite.md §0.3 配置方式）。
 *
 * 命令列表以换行分隔落盘（保持用户配置顺序），空行自动过滤。
 * 同时记录最近一次开机执行的摘要，供玻璃面板展示（可观测性，不再黑盒）。
 */
object BootCommandStore {
    private const val NAME = "moxsh.boot.commands"
    private const val K_COMMANDS = "commands"
    private const val K_LAST_RUN_AT = "lastRunAt"
    private const val K_LAST_RUN_COUNT = "lastRunCount"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取自启命令列表（按配置顺序）。 */
    fun load(ctx: Context): List<String> =
        sp(ctx).getString(K_COMMANDS, "")?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 保存自启命令列表（覆盖式，保持传入顺序）。 */
    fun save(ctx: Context, commands: List<String>) {
        sp(ctx).edit().putString(K_COMMANDS, commands.joinToString("\n")).apply()
    }

    /** 记录一次开机执行的摘要（时间 + 条数）。 */
    fun saveLastRun(ctx: Context, count: Int) {
        sp(ctx).edit()
            .putLong(K_LAST_RUN_AT, System.currentTimeMillis())
            .putInt(K_LAST_RUN_COUNT, count)
            .apply()
    }

    /** 最近一次开机执行的摘要文本；从未执行过返回 null。 */
    fun lastRunSummary(ctx: Context): String? {
        val at = sp(ctx).getLong(K_LAST_RUN_AT, 0L)
        if (at == 0L) return null
        val count = sp(ctx).getInt(K_LAST_RUN_COUNT, 0)
        return "最近一次开机执行 $count 条命令"
    }
}

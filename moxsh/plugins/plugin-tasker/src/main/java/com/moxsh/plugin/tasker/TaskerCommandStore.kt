package com.moxsh.plugin.tasker

import android.content.Context
import android.content.SharedPreferences

/**
 * Tasker 命令模板持久化（docs/plugins-rewrite.md §5.3 可视化配置面板）。
 *
 * 用户在玻璃面板里维护常用命令模板，Tasker 意图可直接引用，
 * 也可在面板内试运行查看结果（可观测性，替代「变量返回空」的静默坑）。
 */
object TaskerCommandStore {
    private const val NAME = "moxsh.tasker.commands"
    private const val K_COMMANDS = "commands"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取命令模板列表（按配置顺序）。 */
    fun load(ctx: Context): List<String> =
        sp(ctx).getString(K_COMMANDS, "")?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 保存命令模板列表（覆盖式）。 */
    fun save(ctx: Context, commands: List<String>) {
        sp(ctx).edit().putString(K_COMMANDS, commands.joinToString("\n")).apply()
    }
}

package com.moxsh.plugin.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * 部件绑定持久化：每个部件实例（appWidgetId）× 按钮槽位 → 命令。
 *
 * 替代 Termux:Widget 的 `~/.shortcuts/` 文件约定（及「目录必须 700」的权限坑），
 * 结构化落盘到 SharedPreferences；标题也可按部件自定义。
 */
object WidgetBindingStore {
    /** 按钮槽位数（与 res/layout/glass_widget.xml 的 btn_0..3 对齐）。 */
    const val SLOTS: Int = 4

    private const val NAME = "moxsh.widget.bindings"
    private const val K_TITLE_FMT = "widget_%d_title"
    private const val K_CMD_FMT = "widget_%d_btn_%d"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取部件标题（未设置回退默认）。 */
    fun title(ctx: Context, appWidgetId: Int): String =
        sp(ctx).getString(K_TITLE_FMT.format(appWidgetId), null) ?: "moxsh 玻璃卡片"

    /** 设置部件标题（空串视为恢复默认）。 */
    fun setTitle(ctx: Context, appWidgetId: Int, title: String) {
        sp(ctx).edit()
            .putString(K_TITLE_FMT.format(appWidgetId), title.trim().ifEmpty { null })
            .apply()
    }

    /** 读取某槽位绑定的命令；未绑定返回 null。 */
    fun command(ctx: Context, appWidgetId: Int, slot: Int): String? =
        sp(ctx).getString(K_CMD_FMT.format(appWidgetId, slot), null)
            ?.trim()
            ?.ifEmpty { null }

    /** 绑定某槽位的命令（空串视为解绑）。 */
    fun setCommand(ctx: Context, appWidgetId: Int, slot: Int, command: String) {
        sp(ctx).edit()
            .putString(K_CMD_FMT.format(appWidgetId, slot), command.trim().ifEmpty { null })
            .apply()
    }
}

package com.moxsh.plugin.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.shared.CompatResult
import com.moxsh.shared.CompatShim
import kotlin.concurrent.thread

/**
 * 玻璃卡片桌面部件（重写 Termux:Widget，docs/plugins-rewrite.md §六）。
 *
 *  - 布局：半透明圆角 RemoteViews（@layout/glass_widget，玻璃视觉对齐 GlassSurface）；
 *  - 点按语义：标题打开主 app；4 个按钮槽位执行各自绑定的命令
 *    （[WidgetBindingStore] 里按 appWidgetId × 槽位落盘）；
 *  - 执行链路：CompatShim 分类路由（Tool 本地提示 / Api·Shell 走加固 IPC），
 *    结果回写到部件的「结果徽标」行——失败可见，不再「点击无反应」。
 *
 * PendingIntent 细节：extras 不参与 PendingIntent 的 filterEquals 匹配，
 * 故 requestCode 用 `appWidgetId * [WidgetBindingStore.SLOTS] + slot` 保证唯一，
 * 避免不同部件/槽位互相覆盖。
 */
class GlassWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> pushUpdate(context, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 先让基类处理 APPWIDGET_UPDATE / DELETED 等标准分发
        super.onReceive(context, intent)
        if (intent.action != ACTION_WIDGET_RUN) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val slot = intent.getIntExtra(EXTRA_SLOT, 0)
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return

        // goAsync + 独立线程：命令可能走 IPC，不能在广播主线程里做
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "moxsh-widget-runner") {
            try {
                val stdout = runCommandSafely(command)
                // 全量重建 RemoteViews（保住按钮点击），并覆盖结果徽标
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val manager = AppWidgetManager.getInstance(appContext)
                    pushUpdate(appContext, appWidgetId, resultOverride = "→ ${stdout.take(64)}")
                } else {
                    Log.w(TAG, "部件点击缺少 appWidgetId")
                }
                Log.d(TAG, "部件按钮 [$slot] 执行完成: $command")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GlassWidgetProvider"

        /** 按钮点击的私有 action（显式发往本 provider）。 */
        const val ACTION_WIDGET_RUN: String = "com.moxsh.plugin.widget.ACTION_RUN"

        /** extra：按钮槽位编号。 */
        const val EXTRA_SLOT: String = "slot"

        /** extra：按钮绑定的待执行命令（与 Tasker 通道的 extra 键名保持一致）。 */
        const val EXTRA_COMMAND: String = "command"

        private val ipc = MoxshIpcClient()

        /** 主 app 包名（单 App 内置架构）。 */
        private const val MAIN_PACKAGE = "com.moxsh"

        /**
         * 渲染并推送一个部件实例的 RemoteViews。
         * @param resultOverride 非 null 时覆盖「结果徽标」行（执行完成后回显用）。
         */
        fun pushUpdate(context: Context, appWidgetId: Int, resultOverride: String? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val views = RemoteViews(context.packageName, R.layout.glass_widget)

            // 标题 + 点击打开主 app
            views.setTextViewText(R.id.glass_widget_title, WidgetBindingStore.title(context, appWidgetId))
            openMainApp(context)?.let { pi ->
                views.setOnClickPendingIntent(R.id.glass_widget_title, pi)
            }

            // 4 个按钮槽位：绑定命令则可点击执行，未绑定则置灰提示
            for (slot in 0 until WidgetBindingStore.SLOTS) {
                val buttonId = when (slot) {
                    0 -> R.id.glass_widget_btn_0
                    1 -> R.id.glass_widget_btn_1
                    2 -> R.id.glass_widget_btn_2
                    else -> R.id.glass_widget_btn_3
                }
                val command = WidgetBindingStore.command(context, appWidgetId, slot)
                if (command == null) {
                    views.setTextViewText(buttonId, "未绑定")
                } else {
                    // 按钮文案取命令首词（短），完整命令留在点击意图里
                    views.setTextViewText(buttonId, command.trim().split(Regex("\\s+")).first())
                    views.setOnClickPendingIntent(buttonId, runPendingIntent(context, appWidgetId, slot, command))
                }
            }

            if (resultOverride != null) {
                views.setTextViewText(R.id.glass_widget_result, resultOverride)
            }
            manager.updateAppWidget(appWidgetId, views)
        }

        /** 刷新全部已放置的部件实例（配置面板改绑定后调用）。 */
        fun pushUpdateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, GlassWidgetProvider::class.java))
            ids.forEach { pushUpdate(context, it) }
        }

        /** 构造按钮点击 PendingIntent（requestCode 唯一，见类注释）。 */
        private fun runPendingIntent(
            context: Context,
            appWidgetId: Int,
            slot: Int,
            command: String,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId * WidgetBindingStore.SLOTS + slot,
            Intent(context, GlassWidgetProvider::class.java)
                .setAction(ACTION_WIDGET_RUN)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(EXTRA_COMMAND, command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** 标题点击：打开主 app（单 App 内置，用主包 launcher 意图）。 */
        private fun openMainApp(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(MAIN_PACKAGE) ?: return null
            return PendingIntent.getActivity(
                context,
                appWidgetIdHashCode(),
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /** 标题 PendingIntent 的固定 requestCode（与按钮槽位空间隔离）。 */
        private fun appWidgetIdHashCode(): Int = Int.MAX_VALUE

        /** CompatShim 路由执行（widget 模块独立持有，与其它插件一致）。 */
        private fun runCommandSafely(command: String): String = runCatching {
            val parts = command.trim().split(Regex("\\s+"))
            val name = parts.firstOrNull() ?: return@runCatching "[moxsh-widget] 空命令"
            val args = parts.drop(1)
            when (val result = CompatShim.route(name, args)) {
                is CompatResult.RunNative ->
                    "[moxsh-widget] ${result.command} 由原生逻辑执行：${result.hint}"
                else -> {
                    val echo = ipc.request(command)
                    echo.ifBlank { "[moxsh-widget] 主 app IPC 未就绪，命令待重试：$command" }
                }
            }
        }.onFailure { Log.w(TAG, "部件命令执行失败: $command", it) }
            .getOrDefault("[moxsh-widget] 命令执行异常：$command")
    }
}

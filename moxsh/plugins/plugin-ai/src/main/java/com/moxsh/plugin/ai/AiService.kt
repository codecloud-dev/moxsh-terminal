package com.moxsh.plugin.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * AI Agent 前台服务（D18）。
 *
 * 职责：
 *  - 以 foreground service 的身份持有 [AgentExecutor] 会话（[sharedExecutor] 全局单例）；
 *    用户把 moxsh 切到后台/杀最近任务时，系统不会立刻回收进程，SSE 流式回包不中断——
 *    "杀后台不断流"的核心保障（manifest 已声明 FOREGROUND_SERVICE + dataSync 类型）；
 *  - 提供全局 [start]/[stop] 入口：UI 进入对话页时 start（保活会话），退出可 stop；
 *  - 通知渠道 "ai_agent"：低优先级常驻通知，文案提示 AI 会话进行中。
 *
 * 使用模式（照 plugin-distro/float 的服务模式）：
 * ```
 * AiService.start(context)            // 进入对话页时
 * val executor = AiService.sharedExecutor  // UI/服务复用同一会话
 * AiService.stop(context)             // 不再需要时
 * ```
 *
 * sharedExecutor 的 confirm 回调在服务侧使用默认放行（工具内高危确认由
 * 前台 UI 的玻璃确认卡负责；服务独立跑工具循环时用系统通知兜底，后续版本接）。
 */
class AiService : Service() {

    companion object {
        /** 通知渠道 id（AI 会话常驻通知）。 */
        private const val CHANNEL_ID = "ai_agent"
        /** 通知 id（固定值：更新同一通知，不堆积）。 */
        private const val NOTIFICATION_ID = 18

        /**
         * 全局共享的 AgentExecutor（UI 与服务复用同一份对话历史）。
         * 首次 [start] 或 UI 直接 new 时创建；UI 侧 `AiService.sharedExecutor ?: AgentExecutor(...)`。
         */
        @Volatile
        var sharedExecutor: AgentExecutor? = null

        /** 启动前台服务（幂等：重复调用无副作用）。 */
        fun start(ctx: Context) {
            // 服务未跑时先补一个共享执行器（UI 可直接复用，保证历史不丢）。
            if (sharedExecutor == null) sharedExecutor = ctx.applicationContext.newSharedExecutor()
            val intent = Intent(ctx, AiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /** 停止服务（不销毁 sharedExecutor，对话历史保留，下次进入继续聊）。 */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AiService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 共享执行器兜底：UI 先建过就复用，否则这里建（confirm 默认放行，UI 接管时
        // 以 UI 传入的回调为准——执行器以首次创建者的回调为准，见注释）。
        if (sharedExecutor == null) {
            sharedExecutor = applicationContext.newSharedExecutor()
        }
        // 三参 startForeground 是 API 29+ 引入；API 28 走两参重载（无类型标记）。
        // API 34+ 则必须传类型（与 manifest 的 foregroundServiceType="dataSync" 一致）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：进程被系统回收后服务重建（sharedExecutor 重建为空会话，
        // 极端场景的兜底；常规"切后台"场景进程不会被杀）。
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 不清 sharedExecutor：退出服务只是停止保活，对话历史由 UI/下次会话复用。
        super.onDestroy()
    }

    /** 创建低优先级常驻通知渠道（幂等）。 */
    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AI 会话",
                NotificationManager.IMPORTANCE_LOW, // 低优先级：不出声、不弹横幅
            ).apply { description = "moxsh AI 助手正在进行对话" }
        )
    }

    /** 构建常驻通知（点按打开对话页）。 */
    private fun buildNotification(): Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, AiChatActivity::class.java),
            // API 31+ 必须显式声明可变性（FLAG_IMMUTABLE 最安全）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                android.app.PendingIntent.FLAG_IMMUTABLE
            else 0,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 插件模块无自有资源，用系统图标占位
            .setContentTitle("moxsh AI")
            .setContentText("AI 会话进行中，回答不会中断")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}

/**
 * 为共享执行器准备的工厂扩展：默认配置读 AiConfigStore（apiKey 已加密落盘），
 * 终端会话 id 由调用侧注入（服务侧无法感知 UI 的当前会话，返回 null 走临时会话路径）。
 */
private fun Context.newSharedExecutor(): AgentExecutor = AgentExecutor(
    appContext = applicationContext,
    configProvider = { AiConfigStore.load(this) },
)

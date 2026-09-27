package com.moxsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.moxsh.shared.ExecutionEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话保活服务（foreground service）。
 * M4 落地：承载会话与加固 IPC，对抗 Android 12+ Phantom Process Killer；
 * 同时作为"双插件体系"中 moxsh 原生玻璃插件的宿主进程。
 *
 * 职责：
 *  1. [onCreate] 挂前台通知（IMPORTANCE_LOW 静默，不扰终端使用）。
 *  2. [onStartCommand] 对 UI 未接管的存活会话兜底拉起 [ExecutionEngine.startPump]
 *     泵循环——仅驱动 VT 解析使后台输出不丢；UI 侧泵（带渲染回调）优先。
 *  3. [LocalBinder] 供同进程组件（MainActivity/插件宿主）取会话列表、触发兜底泵。
 */
class MoxshSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "moxsh_session"
        private const val NOTIFY_ID = 0x6D78 // "mx"

        /**
         * UI 正在亲自持有泵的会话集合。
         * Service 兜底泵跳过它们，避免 startPump 的"先停旧泵"语义抢占 UI 泵导致渲染停滞。
         * Activity 新建/关闭会话时维护；Activity 全部退出后集合清空，兜底泵全面接管。
         */
        val uiOwnedSessions: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    }

    /** 本地 binder：同进程组件直接取会话列表与兜底泵入口。 */
    inner class LocalBinder : Binder() {
        /** 服务实例（取 uiOwnedSessions 等状态）。 */
        val service: MoxshSessionService get() = this@MoxshSessionService

        /** 当前存活会话 id 快照。 */
        fun sessionIds(): List<Long> = ExecutionEngine.listSessions()

        /** 为未持有泵的会话补拉兜底泵。 */
        fun ensurePumps() = this@MoxshSessionService.ensurePumps()
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // 通知渠道（API 26+ 必需）。POST_NOTIFICATIONS 未授权时通知不显示，但服务照常前台。
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "终端会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持 moxsh 终端会话在后台存活"
            }
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("moxsh 运行中")
            .setContentText("终端会话保活，输出泵持续驱动")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        // targetSdk 34：specialUse 类型需在 startForeground 时显式指定（manifest 已声明类型）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 持有/拉起 ExecutionEngine 泵循环：对 UI 未接管的会话兜底驱动，后台输出不丢
        ensurePumps()
        // START_STICKY：进程被杀后系统尽量重建服务，配合 ExecutionEngine 会话表自愈
        return START_STICKY
    }

    /** 给所有 UI 未持有的存活会话拉起兜底泵（仅驱动 pump()，无渲染回调）。 */
    fun ensurePumps() {
        ExecutionEngine.listSessions().forEach { id ->
            if (id !in uiOwnedSessions) {
                ExecutionEngine.startPump(id) { /* 后台兜底泵：只驱动 VT 解析，无需重绘回调 */ }
            }
        }
    }
}

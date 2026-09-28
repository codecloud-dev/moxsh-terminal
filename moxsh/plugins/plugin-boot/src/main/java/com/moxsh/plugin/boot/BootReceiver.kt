package com.moxsh.plugin.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * 开机自启接收器（重写 Termux:Boot，docs/plugins-rewrite.md §二）。
 *
 * BOOT_COMPLETED 到达后逐条执行 [BootCommandStore] 里用户配置的命令：
 *  - [goAsync] 拿 PendingResult + 独立线程执行，避免广播 10 秒超时被杀；
 *  - 执行路径见 [BootRouter]（Tool 本地 / Api·Shell 走加固 IPC）；
 *  - 全程 runCatching，单条失败不影响后续，执行完落盘摘要供玻璃面板回看。
 *
 * 厂商自启动拦截（MIUI/EMUI/ColorOS 等）仍需用户手动豁免，
 * 玻璃配置面板 [BootGlassActivity] 提供引导跳转。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val appContext = context.applicationContext
        thread(name = "moxsh-boot-runner") {
            try {
                val commands = BootCommandStore.load(appContext)
                commands.forEach { line -> BootRouter.executeSafely(line) }
                BootCommandStore.saveLastRun(appContext, commands.size)
                Log.i(TAG, "开机自启命令执行完毕，共 ${commands.size} 条")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

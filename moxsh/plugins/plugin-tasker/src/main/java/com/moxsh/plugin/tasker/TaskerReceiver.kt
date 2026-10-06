package com.moxsh.plugin.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Tasker 联动接收器（重写 Termux:Tasker，docs/plugins-rewrite.md §五）。
 *
 * 约定意图（Tasker 侧「Intent Received / Send Intent」事件配置）：
 *  - action：[TaskerPluginContract.ACTION_RUN]
 *  - extra：`command`（String，必填，整行命令，首词为命令名）
 *  - extra：`args`（StringArray，可选，命令参数；不传则按空格切分 command）
 *  - extra：`result_pending_intent`（PendingIntent，可选，结果回传通道）
 *
 * 执行路径见 [TaskerRouter]（CompatShim 分类：Tool 本地 / Api·Shell 走加固 IPC）。
 * 结果回传：
 *  - 带 `result_pending_intent` 时用 [PendingIntent.send] 回传（extra：stdout / exit_code）；
 *  - 否则发 [TaskerPluginContract.ACTION_RESULT] 广播，Tasker 可用
 *    「Intent Received」事件 + 变量 %stdout / %exit_code 消费。
 *
 * 用 goAsync + 独立线程避免广播 10 秒超时；全程 runCatching，绝不静默 hang
 * （对应原 Termux:Tasker「长脚本静默超时」痛点）。
 */
class TaskerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 调用方鉴权由 manifest 的 android:permission="com.termux.permission.RUN_COMMAND"
        // （signature 级）在系统侧强制：仅同签名 App（含本应用自身）可投递此广播，
        // 第三方 App（含外部 Tasker）无法直接触发命令执行。下方仅做语义校验。
        if (intent.action != TaskerPluginContract.ACTION_RUN) return
        val command = intent.getStringExtra(TaskerPluginContract.EXTRA_COMMAND)
        if (command.isNullOrBlank()) {
            Log.w(TAG, "Tasker 意图缺少 command extra，忽略")
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        val args = intent.getStringArrayExtra(TaskerPluginContract.EXTRA_ARGS)?.toList()
        @Suppress("DEPRECATION")
        val resultPi = runCatching {
            intent.getParcelableExtra<PendingIntent>(TaskerPluginContract.EXTRA_RESULT_PENDING_INTENT)
        }.getOrNull()

        thread(name = "moxsh-tasker-runner") {
            try {
                val stdout = TaskerRouter.executeSafely(command, args)
                deliver(appContext, stdout, resultPi)
            } finally {
                pending.finish()
            }
        }
    }

    /** 结果回传：优先 PendingIntent（定向、可控），否则发结果广播。 */
    private fun deliver(ctx: Context, stdout: String, resultPi: PendingIntent?) {
        val result = Intent(TaskerPluginContract.ACTION_RESULT)
            .putExtra(TaskerPluginContract.EXTRA_STDOUT, stdout)
            .putExtra(TaskerPluginContract.EXTRA_EXIT_CODE, 0)
        runCatching {
            if (resultPi != null) {
                resultPi.send(ctx, 0, result)
            } else {
                ctx.sendBroadcast(result)
            }
        }.onFailure { Log.w(TAG, "Tasker 结果回传失败", it) }
    }

    companion object {
        private const val TAG = "TaskerReceiver"
    }
}

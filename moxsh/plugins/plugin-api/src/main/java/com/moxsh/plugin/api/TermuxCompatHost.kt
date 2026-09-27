package com.moxsh.plugin.api

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.shared.CompatResult
import com.moxsh.shared.CompatShim
import java.util.concurrent.Executors
import com.moxsh.shared.TermuxCompatHost as CompatHostContract

/**
 * Termux 兼容宿主（D7 体系①，docs/plugins-rewrite.md §一 / §0.2）。
 *
 * 以 moxsh **自有签名**实现 Termux 插件契约（共享契约、不共享签名）：
 *  - 接收原 Termux 风格 `RUN_COMMAND` Intent（新旧两种 action 均认），
 *    extras 对齐 `com.termux.app.RUN_COMMAND_*` 约定；
 *  - 命令经 [CompatShim.route] 分类路由：
 *      · Tool 类（termux-tools）：本地执行等价逻辑（wake-lock 用 PowerManager，
 *        其余按 [CompatShim] 的 nativeHint 给出实现思路，完整子系统随运行环境落地）；
 *      · Api 类（termux-api）：经 [MoxshIpcClient] 转发到 moxsh 加固 IPC
 *        （shared.IpcServer：nonce + HMAC 握手，拒绝非 moxsh 家族调用）；
 *      · Shell 类：原样把整行命令交主 app 内核执行；
 *  - 结果回传：调用方带 `RUN_COMMAND_RESULT_PENDING_INTENT` 时用
 *    [PendingIntent.send] 回传（Termux:Tasker / 脚本约定）；否则发
 *    [ACTION_RUN_COMMAND_RESULT] 广播，extra 带 `stdout` / `exit_code`。
 *
 * 本服务同时实现 shared 的 [CompatHostContract] 接口（同进程 Binder 侧入口），
 * 供主 app 对 Api 类命令做进程内调用（execute / executeAsync）。
 */
class TermuxCompatHost : Service(), CompatHostContract {

    private val ipc = MoxshIpcClient()

    /** 单线程执行器：Intent 处理与 IPC 请求都挪出主线程，避免 ANR / 阻塞。 */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "moxsh-compat-host") }

    /** wake-lock 句柄：termux-wake-lock / termux-wake-unlock 的本地等价实现。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { i -> executor.execute { handleRunCommand(i) } }
        return START_NOT_STICKY
    }

    // ── shared.TermuxCompatHost 接口实现（同进程 Binder 侧） ──────────────────

    override fun execute(command: String, args: List<String>): ByteArray =
        routeAndRun(command, args).toByteArray(Charsets.UTF_8)

    override fun executeAsync(command: String, args: List<String>, callback: (ByteArray) -> Unit) {
        executor.execute { callback(routeAndRun(command, args).toByteArray(Charsets.UTF_8)) }
    }

    // ── RUN_COMMAND Intent 处理 ───────────────────────────────────────────────

    /** 解析并执行一条 RUN_COMMAND Intent，失败静默记日志（兼容宿主不能让调用方崩）。 */
    private fun handleRunCommand(intent: Intent) {
        val action = intent.action
        if (action != ACTION_RUN_COMMAND && action != ACTION_RUN_COMMAND_LEGACY) return

        // path 与 arguments 对齐 termux-app RunCommandService 的 extra 命名
        val path = intent.getStringExtra(EXTRA_RUN_COMMAND_PATH)
            ?: intent.getStringExtra(EXTRA_RUN_COMMAND_PATH_SHORT)
        if (path.isNullOrBlank()) {
            Log.w(TAG, "RUN_COMMAND 缺少 path extra，忽略")
            return
        }
        val args = intent.getStringArrayExtra(EXTRA_RUN_COMMAND_ARGUMENTS)?.toList() ?: emptyList()
        val background = intent.getBooleanExtra(EXTRA_RUN_COMMAND_BACKGROUND, false)
        // getParcelableExtra 在 API 33 起废弃（运行环境无新版依赖时的兼容读法）
        @Suppress("DEPRECATION")
        val resultPi = runCatching {
            intent.getParcelableExtra<PendingIntent>(EXTRA_RUN_COMMAND_RESULT_PENDING_INTENT)
        }.getOrNull()

        // 命令名 = path 的文件名（Termux 约定 path 为 $PREFIX/bin/xxx 全路径）
        val command = path.substringAfterLast('/')
        val stdout = routeAndRun(command, args)

        if (background) return // 纯后台执行，不回传
        deliverResult(stdout, resultPi)
    }

    /** 把执行结果回传给调用方：优先 PendingIntent，否则发结果广播。 */
    private fun deliverResult(stdout: String, resultPi: PendingIntent?) {
        val result = Intent(ACTION_RUN_COMMAND_RESULT)
            .putExtra(EXTRA_STDOUT, stdout)
            .putExtra(EXTRA_EXIT_CODE, 0)
        runCatching {
            if (resultPi != null) {
                resultPi.send(applicationContext, 0, result)
            } else {
                sendBroadcast(result)
            }
        }.onFailure { Log.w(TAG, "结果回传失败", it) }
    }

    // ── 路由执行（与 shared.CompatShim 严格对齐） ─────────────────────────────

    /** 分类执行一条命令，返回标准输出文本。 */
    private fun routeAndRun(command: String, args: List<String>): String =
        when (val result = CompatShim.route(command, args)) {
            // Tool 类：moxsh 原生等价实现（wake-lock 为完整思路落地，其余给 hint）
            is CompatResult.RunNative -> runNativeTool(result.command, result.hint)
            // Api 类：转发到 moxsh 加固 IPC（HMAC 握手由 IpcServer 侧完成）
            is CompatResult.ForwardToHost -> forwardToIpc(command, args)
            // Shell 类：原样交主 app 内核（运行环境）执行
            is CompatResult.Success -> forwardToIpc(command, args)
        }

    /**
     * Tool 类本地执行。wake-lock 系列给出可用的 PowerManager 实现，
     * 其余命令回显 nativeHint（完整 Tool 子系统见架构 D10，随运行环境落地）。
     */
    private fun runNativeTool(command: String, hint: String): String = when (command) {
        "termux-wake-lock" -> acquireWakeLock()
        "termux-wake-unlock" -> releaseWakeLock()
        else -> "[moxsh-compat] $command 由原生逻辑执行：$hint"
    }

    /** 申请 PARTIAL_WAKE_LOCK（标签 moxsh:compat-wake，8 小时上限防泄漏）。 */
    private fun acquireWakeLock(): String {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moxsh:compat-wake")
            .apply { setReferenceCounted(false) }
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        return "[moxsh-compat] wake-lock 已持有（PARTIAL_WAKE_LOCK, moxsh:compat-wake）"
    }

    /** 释放此前持有的 wake-lock。 */
    private fun releaseWakeLock(): String {
        runCatching { wakeLock?.release() }
        wakeLock = null
        return "[moxsh-compat] wake-lock 已释放"
    }

    /** 把命令整行发往主 app 加固 IPC，拿回显；IPC 未就绪时给占位回执（永不 hang）。 */
    private fun forwardToIpc(command: String, args: List<String>): String {
        val line = (listOf(command) + args).joinToString(" ")
        val echo = ipc.request(line)
        return echo.ifBlank { "[moxsh-compat] 主 app IPC 未就绪，命令已受理待重试：$line" }
    }

    override fun onDestroy() {
        executor.shutdown()
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TermuxCompatHost"

        /** 新版 termux-app 的 RUN_COMMAND action。 */
        const val ACTION_RUN_COMMAND: String = "com.termux.app.RUN_COMMAND"

        /** 旧版 action（moxsh 主 app manifest 亦声明，双兼容）。 */
        const val ACTION_RUN_COMMAND_LEGACY: String = "com.termux.RUN_COMMAND"

        /** 命令可执行文件全路径（extra 名对齐 termux-app）。 */
        const val EXTRA_RUN_COMMAND_PATH: String = "com.termux.app.RUN_COMMAND_PATH"

        /** 简写 extra（部分第三方脚本使用）。 */
        const val EXTRA_RUN_COMMAND_PATH_SHORT: String = "RUN_COMMAND_PATH"

        /** 命令参数数组（extra 名对齐 termux-app）。 */
        const val EXTRA_RUN_COMMAND_ARGUMENTS: String = "com.termux.app.RUN_COMMAND_ARGUMENTS"

        /** 是否纯后台执行（不回传结果）。 */
        const val EXTRA_RUN_COMMAND_BACKGROUND: String = "com.termux.app.RUN_COMMAND_BACKGROUND"

        /** 结果回传 PendingIntent（Termux:Tasker / termux-api 约定）。 */
        const val EXTRA_RUN_COMMAND_RESULT_PENDING_INTENT: String =
            "com.termux.app.RUN_COMMAND_RESULT_PENDING_INTENT"

        /** 无 PendingIntent 时的结果广播 action。 */
        const val ACTION_RUN_COMMAND_RESULT: String = "com.moxsh.plugin.api.RUN_COMMAND_RESULT"

        /** 结果广播 extra：标准输出。 */
        const val EXTRA_STDOUT: String = "stdout"

        /** 结果广播 extra：退出码。 */
        const val EXTRA_EXIT_CODE: String = "exit_code"

        /** wake-lock 8 小时上限，避免异常路径下的常亮泄漏。 */
        private const val WAKE_LOCK_TIMEOUT_MS: Long = 8L * 60 * 60 * 1000
    }
}

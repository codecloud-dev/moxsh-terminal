package com.moxsh.plugin.tasker

import android.util.Log
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.shared.CompatResult
import com.moxsh.shared.CompatShim

/**
 * tasker 插件的命令路由器（本模块独立持有，与 shared.CompatShim 严格对齐）。
 *
 *  - Tool 类：回显 [CompatShim] 的 nativeHint（完整 Tool 子系统随运行环境落地）；
 *  - Api / Shell 类：经 [MoxshIpcClient] 转发到主 app 加固 IPC（HMAC 握手鉴权），
 *    主 app 未就绪时返回占位回执，不阻塞、不静默 hang。
 */
object TaskerRouter {
    private const val TAG = "TaskerRouter"

    private val ipc = MoxshIpcClient()

    /** 执行一条命令：[command] 命令名，[args] 参数（null 则 command 需含参数）。 */
    fun execute(command: String, args: List<String>?): String {
        val explicitArgs = args ?: command.trim().split(Regex("\\s+")).drop(1)
        val name = args?.let { command.trim() } ?: command.trim().split(Regex("\\s+")).first()
        return when (val result = CompatShim.route(name, explicitArgs)) {
            is CompatResult.RunNative ->
                "[moxsh-tasker] ${result.command} 由原生逻辑执行：${result.hint}"
            else -> {
                val line = (listOf(name) + explicitArgs).joinToString(" ")
                val echo = ipc.request(line)
                echo.ifBlank { "[moxsh-tasker] 主 app IPC 未就绪，命令待重试：$line" }
            }
        }
    }

    /** 执行并吞掉异常（广播线程里绝不能崩），失败返回错误说明。 */
    fun executeSafely(command: String, args: List<String>?): String = runCatching {
        execute(command, args)
    }.onFailure { Log.w(TAG, "Tasker 命令执行失败: $command", it) }
        .getOrDefault("[moxsh-tasker] 命令执行异常：$command")
}

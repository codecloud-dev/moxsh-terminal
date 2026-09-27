package com.moxsh.plugin.boot

import android.util.Log
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.shared.CompatResult
import com.moxsh.shared.CompatShim

/**
 * boot 插件的命令路由器（本模块独立持有，与 shared.CompatShim 严格对齐）。
 *
 * 开机阶段的执行路径：
 *  - Tool 类：回显 [CompatShim] 的 nativeHint（完整 Tool 子系统随运行环境落地）；
 *  - Api / Shell 类：经 [MoxshIpcClient] 转发到主 app 加固 IPC 执行；
 *    主 app 未就绪（开机早期常见）时返回占位回执，不阻塞、不崩溃。
 *
 * 对齐 docs/plugins-rewrite.md §2.2「极早期环境未就绪」痛点：boot 侧不做
 * 长阻塞等待，命令由主 app 侧 IPC 服务受理。
 */
object BootRouter {
    private const val TAG = "BootRouter"

    private val ipc = MoxshIpcClient()

    /** 执行一条命令（整行，首词为命令名），返回结果文本。 */
    fun execute(line: String): String {
        val parts = line.trim().split(Regex("\\s+"))
        val command = parts.firstOrNull() ?: return "[moxsh-boot] 空命令"
        val args = parts.drop(1)
        return when (val result = CompatShim.route(command, args)) {
            is CompatResult.RunNative ->
                "[moxsh-boot] ${result.command} 由原生逻辑执行：${result.hint}"
            else -> {
                val echo = ipc.request(line)
                echo.ifBlank { "[moxsh-boot] 主 app IPC 未就绪，命令待重试：$line" }
            }
        }
    }

    /** 执行并吞掉异常（boot 广播里绝不能崩），失败返回错误说明。 */
    fun executeSafely(line: String): String = runCatching { execute(line) }
        .onFailure { Log.w(TAG, "自启命令执行失败: $line", it) }
        .getOrDefault("[moxsh-boot] 命令执行异常：$line")
}

package com.moxsh.plugin.tasker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * plugin-tasker 的原生玻璃插件注册（D5 体系②，重写 Termux:Tasker）。
 *
 * 通过 [PluginContract] 接入原生框架：宿主 [PluginHost] 在玻璃环境里调用
 * [GlassContent] 渲染本插件入口卡片（意图约定说明 + 配置面板入口）。
 * 主 app 只需 `TaskerPluginContract.register(host)` 即可装配本插件。
 */
object TaskerPluginContract : PluginContract {
    override val id: String = "tasker.automation"

    /** Tasker 调用 moxsh 的约定 action。 */
    const val ACTION_RUN: String = "com.moxsh.plugin.tasker.RUN"

    /** 执行结果回传广播 action（无 PendingIntent 时的回传通道）。 */
    const val ACTION_RESULT: String = "com.moxsh.plugin.tasker.RESULT"

    /** extra：整行命令（必填，首词为命令名）。 */
    const val EXTRA_COMMAND: String = "command"

    /** extra：命令参数数组（可选）。 */
    const val EXTRA_ARGS: String = "args"

    /** extra：结果回传 PendingIntent（可选）。 */
    const val EXTRA_RESULT_PENDING_INTENT: String = "result_pending_intent"

    /** 结果 extra：标准输出。 */
    const val EXTRA_STDOUT: String = "stdout"

    /** 结果 extra：退出码。 */
    const val EXTRA_EXIT_CODE: String = "exit_code"

    @Composable
    override fun GlassContent(host: PluginHost) {
        val ctx = LocalContext.current
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("Tasker 联动", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(
                "在 Tasker 发送意图：action=$ACTION_RUN，extra command=命令行；" +
                    "命令经兼容 shim 路由执行，结果以广播 / PendingIntent 回传。",
                color = GlassTokens.onGlassDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                ctx.startActivity(
                    android.content.Intent(ctx, TaskerGlassActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("管理命令模板")
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

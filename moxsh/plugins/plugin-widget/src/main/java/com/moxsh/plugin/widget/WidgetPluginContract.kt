package com.moxsh.plugin.widget

import android.content.Intent
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
 * plugin-widget 的原生玻璃插件注册（D5 体系②，重写 Termux:Widget）。
 *
 * 通过 [PluginContract] 接入原生框架：宿主 [PluginHost] 在玻璃环境里调用
 * [GlassContent] 渲染本插件入口卡片（部件用法说明 + 桌面添加引导）。
 * 主 app 只需 `WidgetPluginContract.register(host)` 即可装配本插件。
 */
object WidgetPluginContract : PluginContract {
    override val id: String = "widget.shortcuts"

    @Composable
    override fun GlassContent(host: PluginHost) {
        val ctx = LocalContext.current
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("玻璃卡片 · 桌面快捷命令", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(
                "把玻璃卡片加到桌面：标题点按打开主 app，4 个按钮槽位可分别绑定命令，" +
                    "点按即经兼容 shim 路由执行，结果回显在卡片徽标行。",
                color = GlassTokens.onGlassDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                // 引导到配置面板（无 appWidgetId 时面板会展示添加引导）
                ctx.startActivity(
                    Intent(ctx, WidgetGlassActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("了解部件用法")
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

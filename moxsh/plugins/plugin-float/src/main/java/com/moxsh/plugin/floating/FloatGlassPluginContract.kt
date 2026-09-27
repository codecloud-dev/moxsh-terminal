package com.moxsh.plugin.floating

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * plugin-float 作为「moxsh 原生玻璃插件」（D5 体系②）的注册示例。
 *
 * 通过 [PluginContract] 接入原生框架：宿主 [PluginHost] 在玻璃环境里调用
 * [GlassContent] 渲染本插件的入口卡片，点击即可拉起悬浮玻璃终端服务。
 * 主 app 只需 `FloatGlassPluginContract.register(host)` 即可装配本插件。
 */
object FloatGlassPluginContract : PluginContract {
    override val id: String = "float.terminal"

    @Composable
    override fun GlassContent(host: PluginHost) {
        val ctx = LocalContext.current
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("悬浮玻璃终端", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(
                "在任意界面之上挂一个可拖动、可缩放的液态玻璃终端窗。",
                color = GlassTokens.onGlassDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                // 经框架宿主的能力拉起服务（也可直接 ctx.startService）
                ctx.startService(Intent(ctx, FloatGlassService::class.java))
            }) {
                Text("启动悬浮终端")
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

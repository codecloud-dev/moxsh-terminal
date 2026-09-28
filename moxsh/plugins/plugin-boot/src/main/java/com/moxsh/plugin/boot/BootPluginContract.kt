package com.moxsh.plugin.boot

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
 * plugin-boot 的原生玻璃插件注册（D5 体系②，重写 Termux:Boot）。
 *
 * 通过 [PluginContract] 接入原生框架：宿主 [PluginHost] 在玻璃环境里调用
 * [GlassContent] 渲染本插件入口卡片（自启命令摘要 + 配置面板入口）。
 * 主 app 只需 `BootPluginContract.register(host)` 即可装配本插件。
 */
object BootPluginContract : PluginContract {
    override val id: String = "boot.autostart"

    @Composable
    override fun GlassContent(host: PluginHost) {
        val ctx = LocalContext.current
        // 与 BootGlassActivity 共用同一 SP 文件（单 App 内置，主 app 环境读取一致）
        val commands = BootCommandStore.load(ctx)
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("开机自启", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(
                if (commands.isEmpty()) "尚未配置自启命令，开机后不会执行任何任务。"
                else "已配置 ${commands.size} 条自启命令，开机后按顺序执行。",
                color = GlassTokens.onGlassDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                ctx.startActivity(
                    Intent(ctx, BootGlassActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("管理自启命令")
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

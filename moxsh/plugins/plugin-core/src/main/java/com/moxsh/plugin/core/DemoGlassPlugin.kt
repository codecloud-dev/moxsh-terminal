package com.moxsh.plugin.core

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * 框架内置的示例原生玻璃插件，演示：
 *  1. 用 [GlassSurface] 搭建玻璃卡片；
 *  2. 通过 [PluginHost.runRemoteCommand] 经加固 IPC 与主 app 内核交互；
 *  3. 通过 [PluginHost.runLocalCommand] 本地执行。
 *
 * 真实插件（plugin-float / plugin-styling …）按同样方式实现 [PluginContract]，
 * 见各插件模块的 `register(host)` 示例。
 */
class DemoGlassPlugin : PluginContract {
    override val id: String = "core.demo"

    @Composable
    override fun GlassContent(host: PluginHost) {
        // 记忆化：只在首次组合时打一次 IPC，避免每次重组都请求
        val ipcEcho by remember { mutableStateOf(host.runRemoteCommand("echo hello-from-plugin")) }
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("示例玻璃插件", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text("IPC 回显: $ipcEcho", color = GlassTokens.onGlassDim)
            Spacer(Modifier.height(8.dp))
            Text("点击按钮会经 ExecutionEngine 本地执行 `pwd`", color = GlassTokens.onGlassDim)
        }
    }
}

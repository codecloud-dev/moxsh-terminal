package com.moxsh.plugin.core

import androidx.compose.runtime.Composable

/**
 * moxsh 原生玻璃插件契约（D5 体系②）。
 *
 * 与 Termux 兼容宿主（体系①）不同，原生插件：
 *  - 使用 moxsh 自有签名，与 Termux 不互通；
 *  - 以液态玻璃 UI 呈现（宿主已提供 GlassSurface / GlassTokens 等环境）；
 *  - 通过 [PluginHost] 访问主 app 的 [com.moxsh.shared.ExecutionEngine] 与加固 IPC。
 *
 * 实现方需在 [GlassContent] 里用 `com.moxsh.ui.component` 的玻璃组件搭建界面，
 * 并通过 `host.runLocalCommand(...)` / `host.runRemoteCommand(...)` 与主 app 交互。
 */
interface PluginContract {
    /** 插件唯一 id（形如 "float.terminal"、"styling.theme"）。 */
    val id: String

    /**
     * 渲染插件的玻璃内容。宿主会把它放进已套好玻璃外壳的环境里，
     * 因此这里直接堆玻璃组件即可，无需再包一层背景。
     */
    @Composable
    fun GlassContent(host: PluginHost)
}

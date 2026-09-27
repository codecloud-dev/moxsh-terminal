package com.moxsh.plugin.core

import androidx.compose.runtime.Composable

/**
 * moxsh 插件基类（v2，推荐所有新插件继承它而不是直接实现 [PluginContract]）。
 *
 * 相比裸实现 [PluginContract]，基类提供：
 *  - [api]：经 [PluginApi] 暴露的完整能力面（会话 / UI / 文件 / 网络 / 存储 / 事件 / AI 技能）；
 *  - [permissions]：以集合形式声明所需权限，宿主据此构造 API 与安装期确认清单；
 *  - 生命周期：[onLoad]（注册进宿主）→ 使用 → [onUnload]（摘除技能、清理句柄）；
 *  - 事件钩子：[onEvent] 收到全部已订阅事件（需 subscribe_events 权限）。
 *
 * 最小示例：
 * ```
 * class ClockPlugin : MoxPlugin() {
 *     override val id = "clock.glass"
 *     override val permissions = setOf(PluginPermissions.POST_NOTIFICATION)
 *     override fun onLoad(api: PluginApi) { /* 初始化 */ }
 *     @Composable override fun GlassContent(host: PluginHost) { /* 玻璃 UI */ }
 * }
 * ```
 */
abstract class MoxPlugin : PluginContract {

    /** 插件唯一 id（形如 "clock.glass"、"float.terminal"）。 */
    abstract override val id: String

    /** 本插件需要的权限集合（对齐 manifest.json 的 permissions）。 */
    open val permissions: Set<String> = emptySet()

    /** 能力门面；宿主 [PluginHost.load] 时注入，此前为 null。 */
    var api: PluginApi? = null
        private set

    /** 注册进宿主时回调（可安全使用 [api]）。 */
    open fun onLoad(api: PluginApi) {}

    /** 从宿主卸载时回调（基类已自动清理 AI 技能注册）。 */
    open fun onUnload() {}

    /** 全局事件回调（需声明 subscribe_events；主线程回调）。 */
    open fun onEvent(event: PluginEvent) {}

    internal fun attachHost(host: PluginHost, appContext: android.content.Context) {
        val a = host.createApi(id, appContext, permissions)
        api = a
        onLoad(a)
    }

    internal fun detachHost() {
        api?.onUnload()
        onUnload()
        api = null
    }

    /** 便捷访问：等价于 `api!!`（仅 onLoad 之后可用）。 */
    protected fun apiOrNull(): PluginApi? = api
}

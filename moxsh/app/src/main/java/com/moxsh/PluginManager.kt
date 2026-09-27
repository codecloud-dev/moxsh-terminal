package com.moxsh

import android.content.Context
import com.moxsh.plugin.boot.BootPluginContract
import com.moxsh.plugin.core.DemoGlassPlugin
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.plugin.core.PluginEvent
import com.moxsh.plugin.core.PluginHost
import com.moxsh.plugin.float.floating.FloatGlassPluginContract
import com.moxsh.plugin.styling.StylePluginContract
import com.moxsh.plugin.widget.WidgetPluginContract
import com.moxsh.shared.BootstrapState
import com.moxsh.shared.ExecutionEngine

/**
 * 主 app 侧插件装配点（D5 体系②落地）。
 *
 * 职责：
 *  1. 持有进程唯一 [PluginHost]（引擎 + 加固 IPC 注入）；
 *  2. 注册全部内置原生玻璃插件（UI 卡片入口，[com.moxsh.plugin.core.PluginContract]）；
 *  3. 挂接会话事件桥（[ExecutionEngine.sessionListener] → v2 事件总线，
 *     插件经 `PluginApi.events` 订阅 SessionOpened/Closed/CommandExecuted 等）。
 *
 * 动态插件（.mox 安装包 / v2 [com.moxsh.plugin.core.MoxPlugin]）由 plugin-store
 * 安装链路调用 `host.load(moxPlugin, appContext)` 装载，与本装配点共用同一宿主。
 */
object PluginManager {

    @Volatile
    private var instance: PluginHost? = null

    /** 宿主单例（[init] 之后可用）。 */
    val host: PluginHost
        get() = instance ?: error("PluginManager 未初始化（应在 Application.onCreate 调用 init）")

    /** 装载全部内置插件并挂接引擎事件桥（幂等，重复调用直接返回）。 */
    fun init(ctx: Context) {
        if (instance != null) return
        val h = PluginHost(ExecutionEngine, MoxshIpcClient())
        h.attachEngineEvents()

        // 内置原生玻璃插件（v1 契约：GlassContent 玻璃卡片入口）
        h.load(DemoGlassPlugin())
        FloatGlassPluginContract.register(h)
        StylePluginContract.register(h)
        BootPluginContract.register(h)
        WidgetPluginContract.register(h)

        // 运行环境就绪 → 发布 BootCompleted（插件经 PluginApi.events 感知）
        BootstrapState.onReady = { h.events.publish(PluginEvent.BootCompleted()) }

        instance = h
    }
}

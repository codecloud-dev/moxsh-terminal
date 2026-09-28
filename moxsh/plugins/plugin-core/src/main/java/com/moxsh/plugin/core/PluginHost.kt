package com.moxsh.plugin.core

import com.moxsh.shared.ExecutionEngine

/**
 * 原生玻璃插件宿主（D5 体系②运行时）。
 *
 * 维护已加载插件列表，并向插件暴露主 app 能力：
 *  - [engine]：同进程终端执行引擎（本地直接喂 PTY）；
 *  - [runRemoteCommand]：经加固 IPC（[MoxshIpcClient] → shared.IpcServer）把命令
 *    转发给内核，拿到结果；
 *  - [runLocalCommand]：本地直接执行（适合无需跨进程的场景）；
 *  - [events]：全局事件总线（v2，插件经 PluginApi.events 订阅）；
 *  - [createApi]：为插件构造带权限校验的 [PluginApi] 门面（v2 主入口）。
 *
 * 由主 app（MoxshSessionService / MainActivity）持有并注入 [engine] 与 [ipc]。
 */
class PluginHost(
    val engine: ExecutionEngine,
    private val ipc: MoxshIpcClient,
) {
    private val plugins = LinkedHashMap<String, PluginContract>()

    /** 进程级事件总线（命令 / 输出 / 会话 / 开机事件经此分发）。 */
    val events: PluginEventBus = PluginEventBus()

    /** 加载（注册）一个原生插件。重复 id 会覆盖。 */
    fun load(plugin: PluginContract) {
        plugins[plugin.id] = plugin
    }

    /**
     * 加载 v2 插件（[MoxPlugin] 子类）：注册进宿主、注入带权限校验的
     * [PluginApi] 门面并回调 [MoxPlugin.onLoad]。
     */
    fun load(plugin: MoxPlugin, appContext: android.content.Context) {
        load(plugin)
        runCatching { plugin.attachHost(this, appContext) }
    }

    /** 按 id 卸载插件：回调 [MoxPlugin.onUnload]，并清理其注册的 AI 技能等运行期资源。 */
    fun unload(id: String) {
        val plugin = plugins.remove(id)
        if (plugin is MoxPlugin) runCatching { plugin.detachHost() }
        runCatching { SkillRegistry.unregisterAllOf(id) }
    }

    /** 列出当前已加载的全部插件。 */
    fun list(): List<PluginContract> = plugins.values.toList()

    /**
     * 会话事件桥（shared.ExecutionEngine → v2 事件总线）：
     * 引擎侧会话开/关在此转为 [PluginEvent.SessionOpened] / [PluginEvent.SessionClosed]。
     * 方向为 plugin 主动注册监听，shared 不反向依赖 plugin 层。
     */
    private val sessionBridge = object : ExecutionEngine.SessionListener {
        override fun onSessionOpened(id: Long, command: String) {
            events.publish(PluginEvent.SessionOpened(id))
        }

        override fun onSessionClosed(id: Long) {
            events.publish(PluginEvent.SessionClosed(id))
        }
    }

    /** 进程级装配（主 app 启动时调用一次）：把会话生命周期桥接到事件总线。 */
    fun attachEngineEvents() {
        engine.sessionListener = sessionBridge
    }

    /** 经加固 IPC 把命令转发给 moxsh 内核，返回结果文本；成功时发布命令执行事件。 */
    fun runRemoteCommand(line: String): String =
        ipc.request(line).also {
            if (it.isNotEmpty()) events.publish(PluginEvent.CommandExecuted(-1L, line))
        }

    /** 本地直接把输入喂给当前活跃会话的 PTY（同进程执行引擎）；写入成功时发布命令执行事件。 */
    fun runLocalCommand(line: String): Boolean {
        val sid = engine.activeSessionId
        if (sid <= 0L) return false // 无活跃会话（0/-1 为哨兵）
        val ok = engine.write(sid, line.toByteArray())
        if (ok) events.publish(PluginEvent.CommandExecuted(sid, line))
        return ok
    }

    /**
     * 为插件构造 [PluginApi] 门面（v2 主入口）。
     * @param permissions 该插件 manifest.json 声明并经用户确认的权限集合。
     */
    fun createApi(
        pluginId: String,
        appContext: android.content.Context,
        permissions: Set<String>,
    ): PluginApi = PluginApi(pluginId, this, appContext, permissions)
}

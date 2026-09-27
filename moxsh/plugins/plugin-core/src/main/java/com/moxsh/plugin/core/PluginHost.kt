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

    /** 按 id 卸载插件，并清理其注册的 AI 技能等运行期资源。 */
    fun unload(id: String) {
        plugins.remove(id)
        runCatching { SkillRegistry.unregisterAllOf(id) }
    }

    /** 列出当前已加载的全部插件。 */
    fun list(): List<PluginContract> = plugins.values.toList()

    /** 经加固 IPC 把命令转发给 moxsh 内核，返回结果文本。 */
    fun runRemoteCommand(line: String): String = ipc.request(line)

    /** 本地直接把输入喂给 PTY（同进程执行引擎）。返回是否写入成功。 */
    fun runLocalCommand(line: String): Boolean = engine.write(0, line.toByteArray())

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

package com.moxsh

import android.content.Context
import com.moxsh.plugin.boot.BootPluginContract
import com.moxsh.plugin.core.DemoGlassPlugin
import com.moxsh.plugin.core.MoxshIpcClient
import com.moxsh.plugin.core.PluginEvent
import com.moxsh.plugin.core.PluginHost
import com.moxsh.plugin.floating.FloatGlassPluginContract
import com.moxsh.plugin.store.StoreInstallManager
import com.moxsh.plugin.store.StoreRepository
import com.moxsh.plugin.styling.StylePluginContract
import com.moxsh.plugin.widget.WidgetPluginContract
import com.moxsh.shared.BootstrapState
import com.moxsh.shared.ExecutionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 主 app 侧插件装配点（D5 体系②落地）。
 *
 * 职责：
 *  1. 持有进程唯一 [PluginHost]（引擎 + 加固 IPC 注入）；
 *  2. 注册全部内置原生玻璃插件（UI 卡片入口，[com.moxsh.plugin.core.PluginContract]）；
 *  3. 挂接会话事件桥（[ExecutionEngine.sessionListener] → v2 事件总线，
 *     插件经 `PluginApi.events` 订阅 SessionOpened/Closed/CommandExecuted 等）；
 *  4. 启动加载期的安全合规核对：用可信目录策略扫描已装的动态插件，
 *     命中撤销名单 / 低于 minVersion / 无法校验的旧漏洞版本会被真实隔离停用
 *     （[StoreInstallManager.reconcileInstalled]），直到合规升级才自动恢复。
 *
 * 动态插件（.mox 安装包 / v2 [com.moxsh.plugin.core.MoxPlugin]）由 plugin-store
 * 安装链路调用 `host.load(moxPlugin, appContext)` 装载，与本装配点共用同一宿主。
 *
 * 版本比较 / revoked 拦截 / minVersion 地板 / 版本单调的**单一真源**是
 * `com.moxsh.plugin.store.PluginVersionPolicy`：安装覆盖由 `StoreInstaller.install`
 * 在解压前调用，加载期隔离由本文件触发的 reconcile 调用；两处共用同一套判定，
 * 不另写比较逻辑（杜绝「安装拦了、启动又放行」的假绿）。
 */
object PluginManager {

    @Volatile
    private var instance: PluginHost? = null

    /** 启动期安全核对的独立作用域（IO；失败互不影响主流程）。 */
    private val securityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // 启动加载期安全核对：loadCatalog 永远含内置目录（离线可用）+ 签名云端目录，
        // 合并时取最严格策略；reconcileInstalled 据此停用本地已装的漏洞/过期版本。
        // 全程 runCatching 兜底，网络/文件异常都不影响宿主初始化，绝不让 CI/冷启动崩。
        val app = ctx.applicationContext
        securityScope.launch {
            runCatching {
                val catalog = StoreRepository.loadCatalog(app.cacheDir)
                StoreInstallManager.reconcileInstalled(app, catalog)
            }
        }
    }
}

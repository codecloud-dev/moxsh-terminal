package com.moxsh.plugin.distro

import com.moxsh.plugin.core.PluginHost

/**
 * plugin-distro（图形化管理全家桶，D13）的总装配器。
 *
 * 宿主（MoxshSessionService / MainActivity / 未来的插件管理页）一行代码装载四件套：
 * ```
 * DistroPluginRegistrar.registerAll(host)
 * ```
 * 之后 host.list() 里将包含 4 个 PluginContract，可在玻璃插件面板渲染入口卡片；
 * MainActivity 顶栏"管理"按钮则直接导航到 [DistroManagerScreen]（快捷路径）。
 */
object DistroPluginRegistrar {

    /** 把发行版管理器 / 图形包管理器 / 资源监控 / 小白向导注册进宿主。 */
    fun registerAll(host: PluginHost) {
        DistroManagerPluginContract.register(host)
        PackageManagerPluginContract.register(host)
        ResourceMonitorPluginContract.register(host)
        OnboardingWizardPluginContract.register(host)
    }
}

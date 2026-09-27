package com.moxsh

import android.app.Application
import android.content.Intent
import com.moxsh.shared.BootstrapState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 职责（已全部实现，M1/M3/M5 缺口收尾）：
 *  1. 启动前台保活服务（M4）：进程常驻承载 ExecutionEngine 泵循环，
 *     对抗 Android 12+ Phantom Process Killer 与后台回收（架构 §5/§7）。
 *  2. 运行环境就绪流水线（M5）：未初始化则自动执行
 *     `BootstrapInstaller.install`（国内 CDN 优先 + SHA-256 校验 + 解压）
 *     → `CompatShim.ensurePrefixLayout`（$PREFIX 目录骨架 + env/ld 配置）。
 *     进度经 [BootstrapState.state]（StateFlow）广播，小白引导向导与首页
 *     玻璃进度卡 collect 渲染，用户全程不需要知道 $PREFIX 的存在。
 *
 * 注意：运行环境 100% 自研，不依赖 Termux 任何代码。
 */
class MoxshApplication : Application() {

    /** 应用级作用域：SupervisorJob 保证子协程失败互不影响；进程存活期间有效。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 前台保活（应用冷启动时进程处于前台，满足 FGS 启动限制；Service.onCreate 内 startForeground）
        startForegroundService(Intent(this, MoxshSessionService::class.java))

        // 插件宿主装配（D5 体系②）：内置原生玻璃插件注册 + 会话事件桥
        PluginManager.init(this)

        // 运行环境就绪流水线：幂等（已就绪直接 Ready；失败进 BootstrapState.Failed，
        // UI 可引导重试或走 BootstrapInstaller.installFromLocal 离线导入）
        appScope.launch { BootstrapState.begin(this@MoxshApplication) }
    }
}

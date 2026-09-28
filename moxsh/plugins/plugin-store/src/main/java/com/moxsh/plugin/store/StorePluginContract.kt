package com.moxsh.plugin.store

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * plugin-store 作为「moxsh 原生玻璃插件」的注册契约（照 plugin-distro 的
 * DistroManagerPluginContract 模式）：宿主玻璃环境里渲染入口卡片，点击展开
 * 玻璃商店。主 app / 插件宿主只需 `StorePluginContract.register(host)` 装配。
 */
object StorePluginContract : PluginContract {
    override val id: String = "store.main"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            // 插件宿主内展开全屏商店（返回由 StoreScreen 顶栏 ← 处理）。
            StoreScreen(onBack = { open = false }, modifier = Modifier.fillMaxSize())
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text("插件商店", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Text(
                    "浏览 / 一键安装插件、AI 技能与主题（.mox 包），支持本地上传与启停管理。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassStoreButton("打开商店", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主（照 plugin-distro 的 register 模式）。 */
    fun register(host: PluginHost) = host.load(this)
}

/**
 * plugin-store 的总装配器（D16）。宿主一行代码装载：
 * ```
 * StorePluginRegistrar.registerAll(host)
 * ```
 */
object StorePluginRegistrar {

    /** 把插件商店注册进宿主（后续技能管理/付费页可在此追加）。 */
    fun registerAll(host: PluginHost) {
        StorePluginContract.register(host)
    }
}

/**
 * 独立入口 Activity（manifest 已声明）：桌面/插件宿主直接进入玻璃商店。
 * 主 app 的常规路径是经 PluginHost 注册的入口卡片（见 StorePluginContract）。
 */
class StoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshGlassTheme {
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = false, modifier = Modifier.fillMaxSize())
                    StoreScreen(onBack = { finish() }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

package com.moxsh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.settings.GlassIconButton

/**
 * 插件面板（D5 体系②入口）：整屏渲染 [PluginManager] 宿主内全部已注册
 * 原生玻璃插件的卡片（[com.moxsh.plugin.core.PluginContract.GlassContent]）。
 *
 * 各插件卡片自带 GlassSurface 外壳（插件直接堆玻璃组件是契约约定），
 * 这里只做纵向滚动容器与顶栏，不再包玻璃背景。
 */
@Composable
fun PluginPanelScreen(onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---- 顶栏：返回 + 标题 + 已注册数量 ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton("←") { onBack() }
            Spacer(Modifier.width(8.dp))
            Text("插件", color = GlassTokens.onGlass)
            Spacer(Modifier.width(8.dp))
            Text(
                "${PluginManager.host.list().size} 个已注册",
                color = GlassTokens.onGlassDim,
            )
        }

        // ---- 插件卡片（GlassContent 自含玻璃外壳） ----
        val plugins = PluginManager.host.list()
        if (plugins.isEmpty()) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text(
                    "暂无已注册插件",
                    color = GlassTokens.onGlassDim,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        plugins.forEach { plugin ->
            plugin.GlassContent(PluginManager.host)
        }
    }
}

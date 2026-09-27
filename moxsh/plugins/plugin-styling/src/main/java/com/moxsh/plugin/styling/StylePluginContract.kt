package com.moxsh.plugin.styling

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * plugin-styling 的原生玻璃插件注册（D5 体系②）。
 *
 * 通过 [PluginContract] 接入原生框架：宿主 [PluginHost] 在玻璃环境里调用
 * [GlassContent] 渲染本插件的入口卡片（当前主题摘要 + 编辑器入口）。
 * 主 app 只需 `StylePluginContract.register(host)` 即可装配本插件。
 *
 * 主题变更对外契约：
 *  - 本插件把选择持久化到 [ThemeStore]（SharedPreferences）后，
 *    发送 [ACTION_THEME_CHANGED] 定向广播；
 *  - 主 app（MoxshApplication / MainActivity）注册对应 BroadcastReceiver，
 *    收到后重新读取主题并热重载玻璃配色与字号，无需重启终端。
 */
object StylePluginContract : PluginContract {
    override val id: String = "styling.theme"

    /** 主题变更广播 action（定向发往主 app 包）。 */
    const val ACTION_THEME_CHANGED: String = "com.moxsh.plugin.styling.THEME_CHANGED"

    /** 广播 extra：配色方案名（[GlassScheme.name]）。 */
    const val EXTRA_SCHEME: String = "scheme"

    /** 广播 extra：终端字体大小（sp）。 */
    const val EXTRA_FONT_SIZE_SP: String = "fontSizeSp"

    /** 广播 extra：是否跟随壁纸动态取色。 */
    const val EXTRA_FROM_WALLPAPER: String = "fromWallpaper"

    @Composable
    override fun GlassContent(host: PluginHost) {
        val ctx = LocalContext.current
        // 与 StyleGlassActivity 共用同一 SP 文件（单 App 内置，主 app 环境读取一致）
        val config = ThemeStore.load(ctx)
        GlassSurface(Modifier.fillMaxWidth()) {
            Text("动态玻璃主题", color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(
                "当前方案：${config.scheme.label} · 字号 ${config.fontSizeSp.toInt()}sp" +
                    if (config.fromWallpaper) " · 跟随壁纸" else "",
                color = GlassTokens.onGlassDim,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                ctx.startActivity(
                    Intent(ctx, StyleGlassActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("打开主题编辑器")
            }
        }
    }

    /**
     * 把主题配置持久化并广播通知主 app 重新套用。
     * 主 app 侧注册一个监听 [ACTION_THEME_CHANGED] 的 receiver 即可热重载主题。
     */
    fun applyTheme(ctx: Context, config: GlassThemeConfig) {
        ThemeStore.save(ctx, config)
        ctx.sendBroadcast(
            Intent(ACTION_THEME_CHANGED)
                // 定向广播：单 App 内置架构下主 app 与插件同包名
                .setPackage(ctx.packageName)
                .putExtra(EXTRA_SCHEME, config.scheme.name)
                .putExtra(EXTRA_FONT_SIZE_SP, config.fontSizeSp)
                .putExtra(EXTRA_FROM_WALLPAPER, config.fromWallpaper),
        )
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

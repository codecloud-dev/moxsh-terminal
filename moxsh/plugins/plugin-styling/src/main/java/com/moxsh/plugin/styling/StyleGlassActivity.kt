package com.moxsh.plugin.styling

import android.app.WallpaperManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.theme.MoxshGlassTheme

/**
 * moxsh 原生玻璃插件：动态玻璃主题引擎（D5 体系②，重写 Termux:Styling）。
 *
 * 能力（docs/plugins-rewrite.md §四）：
 *  - 配色方案：暗 / 亮 / 极光 / 深海 / 暮光，色板点选实时预览；
 *  - 字号调节：Slider 连续调节终端字体大小（Termux:Styling 不支持的增强项）；
 *  - 动态取色：从系统壁纸提取主色（WallpaperColors.primaryColor）作为玻璃基调，
 *    思路上 API 31+ 可平滑升级到 Material You dynamicColorScheme；
 *  - 持久化 + 广播：选择写入 [ThemeStore]，经 [StylePluginContract.applyTheme]
 *    发 [StylePluginContract.ACTION_THEME_CHANGED] 广播让主 app 热重载主题。
 */
class StyleGlassActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首次进入即提取壁纸主色（取一次缓存，避免每次重组都走 WallpaperManager）
        val wallpaperArgb = pickWallpaperColor()
        val initial = ThemeStore.load(this)
        setContent {
            MoxshGlassTheme {
                var scheme by remember { mutableStateOf(initial.scheme) }
                var fontSize by remember { mutableStateOf(initial.fontSizeSp) }
                var fromWallpaper by remember { mutableStateOf(initial.fromWallpaper) }
                // 动态取色基调：跟随壁纸主色，否则用所选方案的渐变首色
                val backdropColors = if (fromWallpaper) {
                    listOf(Color(wallpaperArgb), Color(wallpaperArgb).copy(alpha = 0.6f))
                } else {
                    scheme.gradient.map { Color(it) }
                }

                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    // 玻璃背景：底层动态模糊 + 上层所选方案渐变染色（实时预览）
                    GlassBackdrop(performantBlur = true, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(backdropColors),
                        ),
                    )

                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(
                            "动态玻璃主题",
                            color = GlassTokens.onGlass,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 1：预设色板（暗 / 亮 / 极光 / 深海 / 暮光）──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text("配色方案", color = GlassTokens.onGlass)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GlassScheme.entries.forEach { s ->
                                    val selected = s == scheme
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(s.gradient.first()).copy(alpha = 0.55f))
                                            .border(
                                                1.dp,
                                                if (selected) GlassTokens.onGlass else GlassTokens.stroke,
                                                RoundedCornerShape(12.dp),
                                            )
                                            .clickable {
                                                scheme = s
                                                fromWallpaper = false // 手选方案即退出壁纸跟随
                                            }
                                            .padding(vertical = 14.dp),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(s.label, color = GlassTokens.onGlass) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 2：动态取色（壁纸联动）──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("跟随壁纸取色", color = GlassTokens.onGlass)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "从系统壁纸提取主色作为玻璃基调（WallpaperColors）",
                                        color = GlassTokens.onGlassDim,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(checked = fromWallpaper, onCheckedChange = { fromWallpaper = it })
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ── 卡片 3：字号调节 ──
                        GlassSurface(Modifier.fillMaxWidth()) {
                            Text("终端字号：${fontSize.toInt()}sp", color = GlassTokens.onGlass)
                            Slider(
                                value = fontSize,
                                onValueChange = { fontSize = it },
                                valueRange = 12f..24f,
                                steps = 11, // 1sp 步进
                            )
                        }
                        Spacer(Modifier.height(16.dp))

                        // ── 应用并广播 ──
                        Button(
                            onClick = {
                                StylePluginContract.applyTheme(
                                    this@StyleGlassActivity,
                                    GlassThemeConfig(
                                        scheme = scheme,
                                        fontSizeSp = fontSize,
                                        fromWallpaper = fromWallpaper,
                                        wallpaperArgb = wallpaperArgb,
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保存主题并通知主 app 套用")
                        }
                    }
                }
            }
        }
    }

    /**
     * 从系统壁纸提取主色（ARGB int）。
     * 取不到（无壁纸权限 / 提取失败）时回退默认极光基调，保证功能可用不崩溃。
     */
    private fun pickWallpaperColor(): Int = runCatching {
        val colors = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        val c = colors?.primaryColor
        // android.graphics.Color 分量法转 ARGB（API 26+，minSdk 28 恒可用）
        if (c != null) ((255 shl 24) or (c.red().toInt() shl 16) or (c.green().toInt() shl 8) or c.blue().toInt())
        else 0xFF101A33.toInt()
    }.getOrDefault(0xFF101A33.toInt())
}

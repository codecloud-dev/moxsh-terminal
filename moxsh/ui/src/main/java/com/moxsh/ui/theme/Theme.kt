package com.moxsh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7CF9E5),
    secondary = Color(0xFFB388FF),
    tertiary = Color(0xFFFF8FB1),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00695C),
    secondary = Color(0xFF6200EA),
    tertiary = Color(0xFFC2185B),
)

/**
 * moxsh 全局主题。玻璃材质本身由 Glass.kt 的 GlassSurface 叠加，
 * 这里只提供动态取色的基础色彩方案（Material3 Expressive 风格）。
 *
 * @param accent 主题色（primary）。深色玻璃下 Switch/Slider/选中态等以此着色；
 *               默认极光青，设置页可切换（持久化在 app 侧 SharedPreferences）。
 *               传默认值即保持原配色，插件模块无参调用完全兼容。
 */
@Composable
fun MoxshGlassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = DarkColorScheme.primary,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) {
        // 深色玻璃为默认外观：主题色可由用户设置页注入
        DarkColorScheme.copy(primary = accent)
    } else {
        LightColorScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

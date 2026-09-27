package com.moxsh.plugin.styling

import android.content.Context
import android.content.SharedPreferences

/**
 * 玻璃主题方案与配置模型（docs/plugins-rewrite.md §四）。
 *
 * moxsh 用「结构化主题」取代 Termux:Styling 的文件约定（font.ttf / colors.properties）：
 *  - [GlassScheme]：内置配色方案（暗 / 亮 / 极光 + 深海 / 暮光扩展色板）；
 *  - [GlassThemeConfig]：方案 + 字号 + 动态取色（壁纸联动）。
 *
 * 动态取色思路（M4 落地版）：
 *  - 从系统壁纸提取主色（[WallpaperManager.getWallpaperColors] 的 primaryColor），
 *    把主色映射为玻璃 backdrop 的渐变基调；
 *  - API 31+ 可进一步接入 Material You（dynamicLightColorScheme / dynamicDarkColorScheme）
 *    获得完整动态色板，本阶段先落地 WallpaperColors 主色方案。
 */
enum class GlassScheme(val label: String, val gradient: List<Long>) {
    /** 暗夜：深空黑蓝，AMOLED 友好。 */
    DARK("暗夜", listOf(0xFF0B0F1A, 0xFF141B2E)),

    /** 晨亮：浅色玻璃，日间可读性优先。 */
    LIGHT("晨亮", listOf(0xFFDCE4F2, 0xFFEEDCF2)),

    /** 极光：moxsh 默认配色（与 ui.GlassBackdrop 渐变一致）。 */
    AURORA("极光", listOf(0xFF101A33, 0xFF2A1538)),

    /** 深海：冷青基调。 */
    DEEP_SEA("深海", listOf(0xFF021B2E, 0xFF0B3B3A)),

    /** 暮光：暖紫基调。 */
    TWILIGHT("暮光", listOf(0xFF2E1A2E, 0xFF3A1C2A)),
}

/** 一份结构化玻璃主题配置。 */
data class GlassThemeConfig(
    val scheme: GlassScheme = GlassScheme.AURORA,
    /** 终端字体大小（sp），对应 Termux:Styling 缺失的字号能力。 */
    val fontSizeSp: Float = 14f,
    /** 是否跟随壁纸动态取色（开启后覆盖 scheme 渐变基调）。 */
    val fromWallpaper: Boolean = false,
    /** 从壁纸提取的主色（ARGB），开启动态取色时的渲染基调。 */
    val wallpaperArgb: Int = 0xFF101A33.toInt(),
)

/**
 * 主题持久化：SharedPreferences 落盘，主 app 收到
 * [StylePluginContract.ACTION_THEME_CHANGED] 广播后重新套用主题。
 */
object ThemeStore {
    private const val NAME = "moxsh.styling.theme"
    private const val K_SCHEME = "scheme"
    private const val K_FS = "fontSizeSp"
    private const val K_DYN = "fromWallpaper"
    private const val K_WALL = "wallpaperArgb"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取当前主题；无记录时回退默认（极光 / 14sp / 不跟随壁纸）。 */
    fun load(ctx: Context): GlassThemeConfig {
        val s = sp(ctx)
        val scheme = runCatching { GlassScheme.valueOf(s.getString(K_SCHEME, null) ?: "") }
            .getOrDefault(GlassScheme.AURORA)
        return GlassThemeConfig(
            scheme = scheme,
            fontSizeSp = s.getFloat(K_FS, 14f),
            fromWallpaper = s.getBoolean(K_DYN, false),
            wallpaperArgb = s.getInt(K_WALL, 0xFF101A33.toInt()),
        )
    }

    /** 保存主题配置（保存后由调用方发广播通知主 app 重套）。 */
    fun save(ctx: Context, config: GlassThemeConfig) {
        sp(ctx).edit()
            .putString(K_SCHEME, config.scheme.name)
            .putFloat(K_FS, config.fontSizeSp)
            .putBoolean(K_DYN, config.fromWallpaper)
            .putInt(K_WALL, config.wallpaperArgb)
            .apply()
    }
}

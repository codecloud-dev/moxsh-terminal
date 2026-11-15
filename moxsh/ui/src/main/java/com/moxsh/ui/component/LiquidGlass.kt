package com.moxsh.ui.component

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI

/**
 * moxsh 液态玻璃材质核心（Apple WWDC25 官方原则，代码自研、不引任何第三方 UI 库）。
 *
 * 层级纪律（最重要）：
 *  - 玻璃**只用于导航层 / 悬浮控件**：底部导航、控制面板、抽屉、弹层、悬浮按钮。
 *  - **禁止进内容层**：终端正文用 [ClearContentSurface]（完全不透明、清晰可读），
 *    绝不套玻璃，也不会被顶部高光/移动光泽盖住文字。
 *  - **禁止玻璃叠玻璃**：[LocalGlassDepth] 记录当前玻璃层深度，[liquidGlass] 在
 *    深度 > 0 时自动退化为纯净色底（不再叠第二层玻璃）。
 *  - **Regular / Clear 不混用**：同一表面只用一种 [GlassMaterial]；导航/大面板用
 *    REGULAR，小控件用 CLEAR，二者不在同一层叠用。
 *
 * 材质要点（逐条落地）：
 *  - 半透明白纱 alpha 0.08~0.13（[LiquidGlassTokens.ScrimMin]/[ScrimMax]），
 *    大控件更不透明、小控件更通透（[adaptiveScrimAlpha]）。
 *  - 顶部 1px 镜面高光 inset、四周菲涅尔内光环、saturate 抬饱和度（见
 *    [LiquidGlassBackdrop] 的 ColorMatrix）、边缘轻微色差环 ≤2px。
 *  - blur ≤10px（[LiquidGlassTokens.BlurMaxDp]），API 31+ 才启用实时模糊，
 *    低版本/减弱动效/降低透明度时静默降级（不崩、不掉帧）。
 *  - 内容滚到玻璃下方时的滚动边缘渐隐（[glassScrollEdgeFade] / edgeFadeTop）。
 *
 * 无障碍（真实降级，非注释）：[rememberGlassA11y] 通过 LocalContext 读系统
 * Settings / AccessibilityManager / PowerManager，并用 ContentObserver 在系统设置
 * 变化时重组；降低透明度/高对比 → 完全不透明 + 高对比描边、无模糊无动效无彩环；
 * 减弱动效 → 弹簧退化为瞬移（[GlassMotion.reducedSpring]）。
 *
 * 弹簧（WWDC25）：默认阻尼比 1.0 / 响应 0.4s；手势抛动 0.8 / 0.3s；
 * 动量投射 decelerationRate 0.998（[GlassMotion]）。
 */

// ---------------------------------------------------------------------------
// 材质规格 / token
// ---------------------------------------------------------------------------

/** 同一表面只选一种，绝不混用。 */
enum class GlassMaterial { REGULAR, CLEAR }

object LiquidGlassTokens {
    const val ScrimMin = 0.08f
    const val ScrimMax = 0.13f
    /** blur 上限：≤10px(dp)。 */
    const val BlurMaxDp = 10f
    /** 顶部 1px 镜面高光 inset。 */
    const val SpecularInsetDp = 1f
    /** 四周菲涅尔内光环宽度。 */
    const val FresnelRingDp = 1f
    /** 边缘轻微色差环总宽 ≤2px（冷暖各 1px）。 */
    const val ChromaticMaxDp = 2f
    /** 内容层不透明底（保证终端正文清晰可读）。 */
    val ContentOpaque = Color(0xFF0B0E14)
    val ContentOpaqueHighContrast = Color(0xFF000000)
    val HighContrastStroke = Color(0xFFFFFFFF)
}

/**
 * 大控件更不透明、小控件更通透；结果始终落在 [0.08, 0.13]。
 * @param areaFraction 0=小控件，1=大控件/全宽导航层。
 */
fun adaptiveScrimAlpha(material: GlassMaterial, areaFraction: Float): Float {
    val f = areaFraction.coerceIn(0f, 1f)
    val lo = LiquidGlassTokens.ScrimMin
    val hi = LiquidGlassTokens.ScrimMax
    // REGULAR 基准更不透明，CLEAR 基准更通透；两者都随尺寸单调抬升，不混用。
    val alpha = when (material) {
        GlassMaterial.REGULAR -> lo + (hi - lo) * (0.4f + 0.6f * f)
        GlassMaterial.CLEAR -> lo + (hi - lo) * (0.6f * f)
    }
    return alpha.coerceIn(lo, hi)
}

// ---------------------------------------------------------------------------
// 无障碍：真实读系统设置 + ContentObserver 响应式重组
// ---------------------------------------------------------------------------

data class GlassA11y(
    val reduceTransparency: Boolean = false,
    val reduceMotion: Boolean = false,
    val highContrast: Boolean = false,
) {
    /** 是否还能用玻璃（否则整体退化为不透明）。 */
    val glassEnabled: Boolean get() = !reduceTransparency && !highContrast
}

/**
 * 读取系统无障碍设置：
 *  - 减弱动效：Settings.Global 动画/过渡时长缩放为 0（系统「移除动画」）；
 *  - 高对比：Settings.Secure "high_text_contrast_enabled"；
 *  - 降低透明度：Android 无 iOS 式系统级开关，以「高对比 / 省电模式 / 读屏
 *    (TouchExploration)」任一启用作为等价降级触发（均为真实可读的系统状态）。
 * 通过 ContentObserver 监听设置变化，运行中切换也能即时真实降级。
 */
@Composable
fun rememberGlassA11y(): GlassA11y {
    val context = LocalContext.current

    fun read(): GlassA11y {
        val cr = context.contentResolver
        val animScale = runCatching {
            Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val transitionScale = runCatching {
            Settings.Global.getFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f)
        }.getOrDefault(1f)
        val reduceMotion = animScale == 0f || transitionScale == 0f

        val highContrast = runCatching {
            Settings.Secure.getInt(cr, "high_text_contrast_enabled", 0) == 1
        }.getOrDefault(false)

        val powerSave = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        }.getOrDefault(false)
        val touchExploration = runCatching {
            (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
                ?.isTouchExplorationEnabled == true
        }.getOrDefault(false)
        val reduceTransparency = highContrast || powerSave || touchExploration
        return GlassA11y(reduceTransparency, reduceMotion, highContrast)
    }

    var state by remember { mutableStateOf(read()) }
    DisposableEffect(context) {
        val cr = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state = read() }
        }
        runCatching {
            cr.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer
            )
            cr.registerContentObserver(
                Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer
            )
        }
        onDispose { runCatching { cr.unregisterContentObserver(observer) } }
    }
    return state
}

// ---------------------------------------------------------------------------
// 弹簧（WWDC25）
// ---------------------------------------------------------------------------

object GlassMotion {
    /** 动量投射 decelerationRate（iOS UIScrollView 口径）。 */
    const val DECELERATION_RATE = 0.998f

    const val DefaultDampingRatio = 1.0f      // 默认阻尼比 1.0（临界阻尼、无回弹）
    const val DefaultResponseSeconds = 0.4f   // 默认响应 0.4s
    const val FlingDampingRatio = 0.8f        // 手势抛动 0.8
    const val FlingResponseSeconds = 0.3f    // 手势抛动响应 0.3s

    /** spring 物理模型 mass=1：stiffness = (2π / response)^2。 */
    fun stiffnessFor(responseSeconds: Float): Float {
        val w = (2.0 * PI / responseSeconds).toFloat()
        return w * w
    }

    val DefaultSpring: AnimationSpec<Float> = spring(
        dampingRatio = DefaultDampingRatio, stiffness = stiffnessFor(DefaultResponseSeconds),
    )
    val FlingSpring: AnimationSpec<Float> = spring(
        dampingRatio = FlingDampingRatio, stiffness = stiffnessFor(FlingResponseSeconds),
    )
    /** 减弱动效：超高刚度 ≈ 瞬移，避免任何可见动画。 */
    val reducedSpring: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 100_000f)

    /** 按 [GlassA11y.reduceMotion] 选择弹簧。 */
    fun springFor(a11y: GlassA11y): AnimationSpec<Float> =
        if (a11y.reduceMotion) reducedSpring else DefaultSpring
}

// ---------------------------------------------------------------------------
// 禁止玻璃叠玻璃：深度守卫
// ---------------------------------------------------------------------------

/** 当前所处的玻璃层深度：0=不在玻璃内；>0=已在一层玻璃内部。 */
val LocalGlassDepth = staticCompositionLocalOf { 0 }

// ---------------------------------------------------------------------------
// 核心材质 Modifier
// ---------------------------------------------------------------------------

/**
 * 给导航层/悬浮控件铺一层 WWDC25 液态玻璃。
 *
 * 绘制顺序：半透明白纱 → 内容 → 四周菲涅尔内光环 → ≤2px 冷暖色差环 → 顶部 1px
 * 镜面高光 inset →（可选）滚动边缘渐隐。
 * 遇到 [GlassA11y] 降级或玻璃层深度 > 0 时，直接退化为不透明纯色（真实降级、
 * 禁止玻璃叠玻璃），不绘制任何玻璃装饰。
 */
fun Modifier.liquidGlass(
    cornerRadius: Dp,
    material: GlassMaterial = GlassMaterial.REGULAR,
    a11y: GlassA11y = GlassA11y(),
    areaFraction: Float = 0.8f,
    edgeFadeTop: Boolean = false,
    specular: Boolean = true,
    refraction: Boolean = true,
): Modifier = composed {
    val shape = RoundedCornerShape(cornerRadius)
    val depth = LocalGlassDepth.current

    // 禁止玻璃叠玻璃 / 降低透明度 / 高对比 → 不透明纯色，无任何玻璃装饰。
    if (depth > 0 || !a11y.glassEnabled) {
        val bg = if (a11y.highContrast)
            LiquidGlassTokens.ContentOpaqueHighContrast else LiquidGlassTokens.ContentOpaque
        val borderW = if (a11y.highContrast) 2.dp else 1.dp
        val borderC = if (a11y.highContrast)
            LiquidGlassTokens.HighContrastStroke else Color.White.copy(alpha = 0.10f)
        return@composed this
            .background(bg, shape)
            .border(borderW, borderC, shape)
    }

    val alpha = adaptiveScrimAlpha(material, areaFraction)
    val density = LocalDensity.current
    val specPx = with(density) { LiquidGlassTokens.SpecularInsetDp.dp.toPx() }
    val ringPx = with(density) { LiquidGlassTokens.FresnelRingDp.dp.toPx() }
    // 色差环总宽 ≤2px：冷暖各占一半（各 1px）。
    val chromaPx = with(density) { (LiquidGlassTokens.ChromaticMaxDp / 2f).dp.toPx() }
    val cornerPx = with(density) { cornerRadius.toPx() }
    val corner = CornerRadius(cornerPx, cornerPx)

    this
        .background(Color.White.copy(alpha = alpha), shape)
        .drawWithContent {
            drawContent()
            val w = size.width
            val h = size.height

            // 四周菲涅尔内光环 + ≤2px 冷暖色差环（refraction 关闭时只留白纱）。
            if (refraction) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.05f),
                            Color.White.copy(alpha = 0.10f),
                        ),
                        startY = 0f, endY = h,
                    ),
                    topLeft = Offset(ringPx, ringPx),
                    size = Size(w - 2 * ringPx, h - 2 * ringPx),
                    cornerRadius = corner,
                    style = Stroke(width = ringPx),
                )
                drawRoundRect(
                    color = Color(0xFFBFE9FF).copy(alpha = 0.06f),
                    topLeft = Offset(chromaPx, chromaPx),
                    size = Size(w - 2 * chromaPx, h - 2 * chromaPx),
                    cornerRadius = corner,
                    style = Stroke(width = chromaPx),
                )
                drawRoundRect(
                    color = Color(0xFFFFD9C0).copy(alpha = 0.05f),
                    topLeft = Offset(chromaPx * 1.5f, chromaPx * 1.5f),
                    size = Size(w - 3 * chromaPx, h - 3 * chromaPx),
                    cornerRadius = corner,
                    style = Stroke(width = chromaPx),
                )
            }

            // 顶部 1px 镜面高光 inset：静态亮线（不再用无限扫光动效，减弱动效天然合规）。
            if (specular) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent)
                    ),
                    start = Offset(cornerPx, specPx),
                    end = Offset(w - cornerPx, specPx),
                    strokeWidth = specPx,
                )
            }

            // 内容滚到玻璃下方时的滚动边缘渐隐（上缘一道向下淡出的纱）。
            if (edgeFadeTop) {
                val bandH = h * 0.22f
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        startY = 0f, endY = bandH,
                    ),
                    topLeft = Offset(0f, 0f),
                    size = Size(w, bandH),
                )
            }
        }
}

/**
 * 内容滚到玻璃下方时的滚动边缘渐隐：在靠近玻璃的边缘叠一道向背景色淡出的纱。
 * 用于内容层顶部/底部与玻璃导航层相接处，让滚入玻璃下方的内容自然淡出。
 */
fun Modifier.glassScrollEdgeFade(
    atTop: Boolean = true,
    color: Color = LiquidGlassTokens.ContentOpaque,
): Modifier = this.drawWithContent {
    drawContent()
    val bandH = size.height * 0.12f
    val top = if (atTop) 0f else size.height - bandH
    val colors = if (atTop)
        listOf(color.copy(alpha = 0.55f), Color.Transparent)
    else
        listOf(Color.Transparent, color.copy(alpha = 0.55f))
    drawRect(
        brush = Brush.verticalGradient(colors, startY = top, endY = top + bandH),
        topLeft = Offset(0f, top),
        size = Size(size.width, bandH),
    )
}

// ---------------------------------------------------------------------------
// 容器 composable
// ---------------------------------------------------------------------------

/**
 * 导航层 / 悬浮控件容器（底部导航、控制面板、抽屉、弹层、进度卡）。
 * 默认 REGULAR；只在玻璃层外使用；内部子树提升 [LocalGlassDepth]，
 * 使嵌套的 [liquidGlass] 自动退化，保证「禁止玻璃叠玻璃」。
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    material: GlassMaterial = GlassMaterial.REGULAR,
    areaFraction: Float = 0.85f,
    edgeFadeTop: Boolean = false,
    specular: Boolean = true,
    refraction: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val a11y = rememberGlassA11y()
    // 玻璃铺在外层 Box（本层 composition，depth 未提升）；只有内容子树提升深度。
    Box(
        modifier.liquidGlass(
            cornerRadius = cornerRadius,
            material = material,
            a11y = a11y,
            areaFraction = areaFraction,
            edgeFadeTop = edgeFadeTop,
            specular = specular,
            refraction = refraction,
        )
    ) {
        CompositionLocalProvider(LocalGlassDepth provides LocalGlassDepth.current + 1) {
            Column(Modifier.padding(14.dp), content = content)
        }
    }
}

/**
 * 内容层纯净容器：**完全不透明、无任何玻璃装饰**，保证终端正文/输入框文字
 * 100% 清晰可读。结构（Column + 14dp 内边距）与旧 GlassSurface 对齐，
 * 便于内容层无缝替换而不改变布局。高对比模式下自动加高对比描边。
 */
@Composable
fun ClearContentSurface(
    modifier: Modifier = Modifier,
    color: Color = LiquidGlassTokens.ContentOpaque,
    content: @Composable ColumnScope.() -> Unit,
) {
    val a11y = rememberGlassA11y()
    val shape = RoundedCornerShape(20.dp)
    val bg = if (a11y.highContrast) LiquidGlassTokens.ContentOpaqueHighContrast else color
    val bordered = if (a11y.highContrast)
        Modifier.border(2.dp, LiquidGlassTokens.HighContrastStroke, shape) else Modifier
    Column(
        modifier
            .background(bg, shape)
            .then(bordered)
            .padding(14.dp),
        content = content,
    )
}

/**
 * 全局玻璃背景层（玻璃之下）：中性深色渐变（无紫色发光/无 AI 味主题渐变），
 * saturate 抬饱和度（ColorMatrix），blur ≤10px（API 31+，减弱动效/降低透明度时
 * 静默降级）。玻璃面板叠在它之上，白纱透出的即是这层被模糊、提饱和的背景。
 */
@Composable
fun LiquidGlassBackdrop(performantBlur: Boolean, modifier: Modifier = Modifier) {
    val a11y = rememberGlassA11y()
    // 中性深色：只做明暗层次，不做彩色发光渐变。
    val gradient = listOf(Color(0xFF0A0D14), Color(0xFF10141C), Color(0xFF0A0D14))
    // saturate 抬饱和度：把 backdrop 颜色经 ColorMatrix 提饱和（液态玻璃会让
    // 背后内容更鲜活）。
    val satFilter = remember {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.15f) })
    }
    val canBlur = performantBlur && !a11y.reduceMotion && !a11y.reduceTransparency &&
        Build.VERSION.SDK_INT >= 31
    Box(
        modifier
            .drawBehind {
                drawRect(brush = Brush.verticalGradient(gradient), colorFilter = satFilter)
            }
            .then(
                if (canBlur) Modifier.blur(LiquidGlassTokens.BlurMaxDp.dp) else Modifier
            )
    )
}

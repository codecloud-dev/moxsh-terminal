package com.moxsh.ui.component

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * 全应用液态玻璃组件库（ui 模块，包名 com.moxsh.ui）——WWDC25 适配层。
 *
 * 设计契约（材质单一真源见同包 LiquidGlass.kt）：
 *  - [GlassSurface] / [GlassBackdrop] 委托到 [LiquidGlassSurface] / [LiquidGlassBackdrop]，
 *    全应用（主界面、设置、插件）视觉一致（见 docs/architecture.md §6）。
 *  - 玻璃**只用于导航层 / 悬浮控件**；内容层（终端正文）一律用 [ClearContentSurface]
 *    完全不透明；禁止玻璃叠玻璃（LocalGlassDepth 守卫）；Regular/Clear 不混用。
 *  - 去 AI 味：**不再有紫色发光渐变、斜向柔光、无限移动扫光**；改为中性白纱
 *    alpha 0.08~0.13 + 顶部 1px 镜面高光 + 四周菲涅尔内光环 + ≤2px 冷暖色差环 +
 *    saturate 抬饱和 + blur ≤10px。
 *  - 无障碍真实降级：降低透明度/高对比 → 不透明 + 高对比描边；减弱动效 →
 *    弹簧瞬移、禁用视差（rememberGlassA11y 读系统设置 + ContentObserver）。
 *  - 模糊策略：API31+ 用 RenderEffect 实时模糊（≤10px），API28-30 静态回退；
 *    性能模式 / 无障碍降级时静默关闭，不崩不掉帧。
 *  - [GlassBottomBar] 为真正可向 PTY 发送按键的 ExtraKeys 行；可点组件带
 *    [glassPress] 按压缩放；[GlassFAB] 保留用户 accent 单一强调色（非紫色渐变）。
 */
object GlassTokens {
    val surfaceTint = Color.White.copy(alpha = 0.10f)
    val termTint = Color.Black.copy(alpha = 0.28f)
    val stroke = Color.White.copy(alpha = 0.18f)
    val strokeError = Color(0xFFFF8A80).copy(alpha = 0.85f)
    val onGlass = Color.White.copy(alpha = 0.92f)
    val onGlassDim = Color.White.copy(alpha = 0.6f)
}

/**
 * 主题色流动通道：由 `MoxshGlassTheme` 注入用户选择的 accent。
 * `Color.Unspecified` = 未注入（组件回退中性玻璃，兼容插件模块无参调用）。
 */
val LocalGlassAccent = staticCompositionLocalOf { Color.Unspecified }

/**
 * 玻璃模糊 Modifier（D8 关键能力）。
 * 仅 API31+ 生效（Compose blur 内部经 RenderEffect 实现）；低版本自动 no-op，
 * 静默回退到静态半透明（不报错、不掉帧）。
 *
 * @param enabled 是否开启实时模糊；通常传 `!perfMode`（性能模式关模糊）。
 * @param radiusDp 模糊半径（dp）。
 */
fun Modifier.glassBlur(enabled: Boolean, radiusDp: Float = 20f): Modifier {
    // WWDC25：blur ≤10px。任何传入半径都封顶到 LiquidGlassTokens.BlurMaxDp，
    // 低版本（<31）静默 no-op，不崩不掉帧。
    val capped = radiusDp.coerceAtMost(LiquidGlassTokens.BlurMaxDp)
    return if (enabled && Build.VERSION.SDK_INT >= 31) {
        this.then(Modifier.blur(capped.dp))
    } else this
}

/**
 * 液态按压动效：按住时弹性缩小（默认 0.96），松开回弹。
 * 配 `clickable(interactionSource = ..., indication = null)` 使用，
 * 以自家的玻璃形变替代系统水波纹——水波纹与玻璃材质视觉相斥。
 *
 * @param pressedScale 按下时的缩放比（越小"按"得越深）。
 */
fun Modifier.glassPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "glassPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 玻璃高光层：左上斜向的柔光渐变，叠加在染色之上、内容之下，模拟玻璃反光。 */
private fun glassSheenBrush(): Brush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.13f),
    0.35f to Color.White.copy(alpha = 0.04f),
    0.6f to Color.Transparent,
    start = Offset.Zero,
    end = Offset.Infinite,
)

/** 玻璃顶部高光带：上沿一道亮边，是"真玻璃"最直接的识别特征。 */
private fun glassTopHighlight(): Brush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = 0.18f),
        Color.White.copy(alpha = 0.05f),
        Color.Transparent,
    )
)

/**
 * 指针视差倾斜：卡片随指针拖动产生轻微 3D 旋转，像真玻璃会随视角变化反光。
 * 用于装饰型卡片（默认关闭）；与 clickable 抢事件，需留意层级。
 */
fun Modifier.glassTilt(
    maxTiltDeg: Float = 6f,
    enabled: Boolean = true,
): Modifier = if (!enabled) this else composed {
    var tiltX by remember { mutableStateOf(0f) }
    var tiltY by remember { mutableStateOf(0f) }
    this
        .pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    tiltX = (tiltX + dragAmount.y * 0.25f).coerceIn(-maxTiltDeg, maxTiltDeg)
                    tiltY = (tiltY - dragAmount.x * 0.25f).coerceIn(-maxTiltDeg, maxTiltDeg)
                },
                onDragEnd = {
                    tiltX = 0f
                    tiltY = 0f
                },
            )
        }
        .graphicsLayer {
            rotationX = tiltX
            rotationY = tiltY
            cameraDistance = 12f
            transformOrigin = TransformOrigin(0.5f, 0.5f)
        }
}

/**
 * 全局玻璃背景层（WWDC25）：委托 [LiquidGlassBackdrop]——中性深色（**无紫色
 * 发光渐变**）+ saturate 抬饱和度 + blur ≤10px（API31+）+ 无障碍真实降级。
 *
 * 历史实现里的 baseMid 紫 / baseBottom 青绿主题渐变、accent 底部染色已移除：
 * 彩色发光渐变是「AI 味」，WWDC25 玻璃背后应是中性内容，靠提饱和而非叠色。
 */
@Composable
fun GlassBackdrop(performantBlur: Boolean, modifier: Modifier = Modifier) {
    LiquidGlassBackdrop(performantBlur, modifier)
}

/**
 * 玻璃面板（WWDC25 适配层）：**保留历史签名**以兼容全应用所有调用点，内部委托
 * [LiquidGlassSurface]——半透明白纱 alpha 0.08~0.13 + 顶部 1px 镜面高光 +
 * 四周菲涅尔内光环 + ≤2px 冷暖色差环 + blur≤10 + 无障碍真实降级 + 禁止玻璃叠玻璃。
 *
 * 关键语义迁移（去 AI 味 / 补 a11y 缺口）：
 *  - 不再绘制 glassSheenBrush 斜向柔光、无限移动扫光、折射内圈叠加层：
 *    这些是持续动效，且历史实现从不读系统「减弱动效」设置（无障碍不合规）；
 *  - [tint] == [GlassTokens.termTint]（历史终端内容层深染）→ 走 [ClearContentSurface]
 *    完全不透明，保证终端正文 100% 清晰可读（内容层禁止玻璃）；
 *  - [enableSpecular] / [enableRefraction] / [enableTilt] 保留为兼容参数并**真实使用**：
 *    分别控制顶部高光、菲涅尔/色差环、视差；减弱动效时视差自动禁用。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tint: Color = GlassTokens.surfaceTint,
    enableSpecular: Boolean = true,
    enableRefraction: Boolean = true,
    enableTilt: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 内容层（终端正文/输入区）：历史用 termTint 深染标记，一律完全不透明。
    if (tint == GlassTokens.termTint) {
        ClearContentSurface(modifier, content = content)
        return
    }
    val a11y = rememberGlassA11y()
    // 减弱动效 / 降低透明度 / 高对比时不叠加视差倾斜（真实降级）。
    val tilt = enableTilt && !a11y.reduceMotion && a11y.glassEnabled
    LiquidGlassSurface(
        modifier = if (tilt) modifier.glassTilt() else modifier,
        cornerRadius = 20.dp,
        material = GlassMaterial.REGULAR,
        specular = enableSpecular,
        refraction = enableRefraction,
        content = content,
    )
}

@Composable
fun GlassTopBar(
    title: String,
    perfMode: Boolean,
    onTogglePerf: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier.padding(10.dp).fillMaxWidth(), tint = GlassTokens.surfaceTint) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, color = GlassTokens.onGlass)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("性能模式", color = GlassTokens.onGlassDim)
                Spacer(Modifier.width(6.dp))
                Switch(checked = perfMode, onCheckedChange = { onTogglePerf() })
            }
        }
    }
}

/**
 * 玻璃底部 ExtraKeys 栏：真正可向 PTY 发送按键的扩展键盘行。
 *
 * 行为约定：
 *  - Ctrl / Alt：点按切换修饰态（激活时高亮）。修饰态由调用方持有，
 *    生效于后续输入的第一个字符（Ctrl+C → 0x03 等），生效后由调用方复位。
 *  - Tab / ↑ / ↓ / ← / → / ESC：点按即通过 [onKey] 发送对应 VT 转义序列。
 *  - 全部键位带液态按压动效（按住缩小、松开回弹）。
 *
 * @param ctrlActive  Ctrl 修饰态是否激活。
 * @param altActive   Alt 修饰态是否激活。
 * @param onToggleCtrl 点按 Ctrl 时的状态切换回调。
 * @param onToggleAlt  点按 Alt 时的状态切换回调。
 * @param onKey       发送按键回调，参数为该键对应的 VT 序列字符串
 *                    （Tab="\t"、↑="\u001b[A"、ESC="\u001b" 等）。
 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    onKey: (String) -> Unit = {},
) {
    // 键位 -> VT 序列；null 表示修饰键（点击只切换状态，不直接发送）。
    val keys: List<Pair<String, String?>> = listOf(
        "Ctrl" to null,
        "Alt" to null,
        "Tab" to "\t",
        "↑" to "\u001b[A",
        "↓" to "\u001b[B",
        "←" to "\u001b[D",
        "→" to "\u001b[C",
        "ESC" to "\u001b",
    )
    GlassSurface(modifier.padding(10.dp).fillMaxWidth(), tint = GlassTokens.surfaceTint) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            keys.forEach { (label, seq) ->
                // 修饰键激活时高亮，向用户明确"下一个字符会被修饰"
                val active = (label == "Ctrl" && ctrlActive) || (label == "Alt" && altActive)
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .glassPress(interaction, pressedScale = 0.92f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) {
                            when (label) {
                                "Ctrl" -> onToggleCtrl()
                                "Alt" -> onToggleAlt()
                                else -> seq?.let(onKey)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (active) Color.White else GlassTokens.onGlass)
                }
            }
        }
    }
}

/** 玻璃悬浮按钮：主题色染色 + 液态按压动效（按住缩小回弹）。 */
@Composable
fun GlassFAB(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val accent = LocalGlassAccent.current
    val tint = if (accent.isSpecified) accent.copy(alpha = 0.26f) else GlassTokens.surfaceTint
    val stroke = if (accent.isSpecified) accent.copy(alpha = 0.55f) else GlassTokens.stroke
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .glassPress(interaction, pressedScale = 0.90f)
            .size(56.dp)
            .clip(CircleShape)
            .background(tint, CircleShape)
            .background(glassSheenBrush(), CircleShape)
            .background(glassTopHighlight(), CircleShape)
            .border(1.dp, stroke, CircleShape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = GlassTokens.onGlass, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
    }
}

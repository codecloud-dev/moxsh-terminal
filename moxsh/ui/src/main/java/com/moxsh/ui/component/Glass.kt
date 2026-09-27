package com.moxsh.ui.component

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.dp

/**
 * 全应用液态玻璃组件库（ui 模块，包名 com.moxsh.ui）。
 *
 * 设计契约：
 *  - 所有玻璃组件统一复用 [GlassSurface]（半透明 + 高光描边 + 动态取色），
 *    全应用（主界面、设置、插件）视觉一致（见 docs/architecture.md §6）。
 *  - 模糊策略（D8：按设备性能自动）：
 *      · API31+：用 RenderEffect 实时背景模糊
 *      · API28-30：静态半透明回退（无实时模糊，保帧率）
 *  - [GlassBottomBar] 已被改造为真正可向 PTY 发送按键的 ExtraKeys 行。
 *
 * 生产注意：GlassBackdrop 应在实时壁纸/实况层之上做模糊，这里用渐变示意。
 */
object GlassTokens {
    val surfaceTint = Color.White.copy(alpha = 0.10f)
    val termTint = Color.Black.copy(alpha = 0.28f)
    val stroke = Color.White.copy(alpha = 0.18f)
    val onGlass = Color.White.copy(alpha = 0.92f)
    val onGlassDim = Color.White.copy(alpha = 0.6f)
}

/**
 * 玻璃模糊 Modifier（D8 关键能力）。
 * 仅 API31+ 生效；低版本静默回退到静态半透明（不报错、不掉帧）。
 *
 * @param enabled 是否开启实时模糊；通常传 `!perfMode`（性能模式关模糊）。
 * @param radiusDp 模糊半径（dp）。
 */
fun Modifier.glassBlur(enabled: Boolean, radiusDp: Float = 20f): Modifier =
    if (enabled && Build.VERSION.SDK_INT >= 31) {
        this.then(
            Modifier.graphicsLayer {
                renderEffect = AndroidRenderEffect.createBlurEffect(
                    radiusDp, radiusDp, Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
        )
    } else this

/** 全局玻璃背景层：在内容之下铺一层渐变 +（可选）实时模糊。 */
@Composable
fun GlassBackdrop(performantBlur: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF101A33),
                        Color(0xFF2A1538),
                        Color(0xFF0B2E2C),
                    )
                )
            )
            .then(glassBlur(performantBlur, 30f))
    )
}

/**
 * 玻璃面板：全应用统一的半透明容器（圆角 + 高光描边 + 动态取色调色）。
 * 其他玻璃组件（TopBar/BottomBar/FAB/Dialog）均以其为基础叠加，保证视觉一致。
 *
 * @param tint    玻璃染色；终端面板用 [GlassTokens.termTint]，普通面板用 [GlassTokens.surfaceTint]。
 * @param content 面板内的 Compose 内容（ColumnScope）。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    tint: Color = GlassTokens.surfaceTint,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(tint, RoundedCornerShape(20.dp))
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(20.dp))
            .padding(14.dp),
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
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
                        .clickable {
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

@Composable
fun GlassFAB(modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = GlassTokens.onGlass, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
    }
}

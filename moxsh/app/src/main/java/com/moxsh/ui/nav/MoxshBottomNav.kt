package com.moxsh.ui.nav

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassMaterial
import com.moxsh.ui.component.GlassMotion
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.component.liquidGlass
import com.moxsh.ui.component.rememberGlassA11y

/** 三页导航枚举（终端 / 分类 / 设置），与官网在线体验原型底部标签栏一致。 */
enum class MoxshPage { TERMINAL, CATALOG, SETTINGS }

private val TABS = listOf(
    Triple(MoxshPage.TERMINAL, "终端", "▦"),
    Triple(MoxshPage.CATALOG, "分类", "▣"),
    Triple(MoxshPage.SETTINGS, "设置", "⚙"),
)

/**
 * moxsh 底部三页标签栏（终端 / 分类 / 设置）——WWDC25 液态玻璃导航层。
 *
 * 玻璃只出现在这一悬浮导航层（叠在内容之上）：半透明白纱 + 顶部 1px 镜面高光 +
 * 四周菲涅尔内光环 + ≤2px 冷暖色差环；固定 REGULAR、大控件更不透明
 * （areaFraction=1f → 白纱 alpha 0.13），顶部 edgeFadeTop 让滚入玻璃下方的内容
 * 自然渐隐。终端正文等内容层一律不进玻璃（见 ClearContentSurface）。
 *
 * 无障碍真实降级：用 [rememberGlassA11y] 读系统设置并**传入** [liquidGlass]，
 * 降低透明度/高对比 → 完全不透明 + 高对比描边、无模糊无彩环；减弱动效 →
 * 选中态弹簧退化为瞬移（[GlassMotion.springFor]），且 Settings 变化即时重组。
 *
 * 弹簧：默认阻尼比 1.0 / 响应 0.4s（[GlassMotion.DefaultSpring]）；手势抛动
 * 0.8/0.3s、动量投射 decelerationRate 0.998 由 GlassMotion 统一提供。
 */
@Composable
fun MoxshBottomNav(
    current: MoxshPage,
    onSelect: (MoxshPage) -> Unit,
    showAiBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF7CF9E5)
    val a11y = rememberGlassA11y()
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .liquidGlass(
                cornerRadius = 20.dp,
                material = GlassMaterial.REGULAR,
                a11y = a11y,               // 真实读系统设置，非注释
                areaFraction = 1f,         // 大控件更不透明
                edgeFadeTop = true,        // 内容滚入玻璃下方时上缘渐隐
            )
            .padding(vertical = 10.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TABS.forEach { (page, label, icon) ->
            val selected = page == current
            // 选中态弹性放大：默认 1.0/0.4s 弹簧；减弱动效时 springFor 退化为瞬移。
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = GlassMotion.springFor(a11y),
                label = "navTabScale",
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(page) }
                    .graphicsLayer { scaleX = scale; scaleY = scale },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(icon, color = if (selected) accent else GlassTokens.onGlassDim, fontSize = 22.sp)
                    if (page == MoxshPage.SETTINGS && showAiBadge) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(Color(0xFFFF453A)),
                            contentAlignment = Alignment.Center,
                        ) { Text("1", color = Color.White, fontSize = 10.sp) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    color = if (selected) accent else GlassTokens.onGlassDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

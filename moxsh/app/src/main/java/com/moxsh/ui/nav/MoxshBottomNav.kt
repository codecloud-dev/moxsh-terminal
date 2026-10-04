package com.moxsh.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassTokens

/** 三页导航枚举（终端 / 分类 / 设置），与官网在线体验原型底部标签栏一致。 */
enum class MoxshPage { TERMINAL, CATALOG, SETTINGS }

private val TABS = listOf(
    Triple(MoxshPage.TERMINAL, "终端", "▦"),
    Triple(MoxshPage.CATALOG, "分类", "▣"),
    Triple(MoxshPage.SETTINGS, "设置", "⚙"),
)

/**
 * moxsh 底部三页标签栏（终端 / 分类 / 设置）。
 * 设置页带 AI 角标（首次进入提示）；点击切页，键盘 1/2/3 也切页（由 MoxshRoot 接管）。
 */
@Composable
fun MoxshBottomNav(
    current: MoxshPage,
    onSelect: (MoxshPage) -> Unit,
    showAiBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF7CF9E5)
    Row(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                GlassTokens.surfaceTint,
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .border(
                1.dp,
                GlassTokens.stroke,
                RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            )
            .padding(vertical = 10.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TABS.forEach { (page, label, icon) ->
            val selected = page == current
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelect(page) },
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

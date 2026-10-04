package com.moxsh.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/** 通用玻璃按钮（app 模块内部复用；与 plugin-distro 的同名组件解耦）。 */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    filled -> Color.White.copy(alpha = 0.26f)
                    else -> GlassTokens.surfaceTint
                }
            )
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) GlassTokens.onGlass else GlassTokens.onGlassDim, fontSize = 14.sp)
    }
}

/** 玻璃胶囊筛选标签（分类页 / 包管理器用）。 */
@Composable
fun GlassChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Color.White.copy(alpha = 0.26f) else GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = GlassTokens.onGlass, fontSize = 12.5.sp)
    }
}

/** 区块标题。 */
@Composable
fun GlassSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = GlassTokens.onGlass,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

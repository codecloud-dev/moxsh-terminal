package com.moxsh.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens

/**
 * 液态玻璃控制中心（跟手 + 音量键呼出，与官网在线体验原型一致）。
 *  - 顶部玻璃度环（本地可调，视觉反馈）
 *  - 主题切换（浅 / 深）
 *  - 快捷磁贴（无线 / AirDrop / 热点 / 蓝牙）
 *  - 小组件快捷动作（清屏 / 速连 / 帮助 / 截屏）
 *
 * 真实主题/玻璃度持久化预留：[onToggleTheme] 由调用方接到设置偏好。
 */
@Composable
fun ControlCenter(
    onToggleTheme: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var glassAlpha by remember { mutableFloatStateOf(62f) }
    var wireless by remember { mutableStateOf(true) }
    var airdrop by remember { mutableStateOf(false) }
    var hotspot by remember { mutableStateOf(false) }
    var bt by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
                .clickable { onDismiss() },
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    GlassTokens.surfaceTint,
                    RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                )
                .border(
                    1.dp,
                    GlassTokens.stroke,
                    RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                )
                .padding(top = 54.dp, start = 16.dp, end = 16.dp, bottom = 22.dp)
                .clickable { },
        ) {
            // 拖拽手柄
            Box(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(38.dp).height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(GlassTokens.onGlassDim),
                )
            }

            GlassSurface(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${glassAlpha.toInt()}%",
                            color = GlassTokens.onGlass,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("整体玻璃度", color = GlassTokens.onGlass, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "点击切换主题",
                            color = GlassTokens.onGlassDim,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onToggleTheme() },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CcTile("无线", wireless) { wireless = it }
                    CcTile("AirDrop", airdrop) { airdrop = it }
                    CcTile("热点", hotspot) { hotspot = it }
                    CcTile("蓝牙", bt) { bt = it }
                }
                Spacer(Modifier.height(12.dp))
                GlassSurface(Modifier.fillMaxWidth()) {
                    Text("快捷动作", color = GlassTokens.onGlassDim, fontSize = 12.sp)
                    Spacer(Modifier.height(9.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        WBtn("清屏")
                        WBtn("速连")
                        WBtn("帮助")
                        WBtn("截屏")
                    }
                }
            }
        }
    }
}

@Composable
private fun CcTile(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) Color.White.copy(alpha = 0.20f) else GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(16.dp))
            .clickable { onToggle(!on) }
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = if (on) Color(0xFF7CF9E5) else GlassTokens.onGlass, fontSize = 11.sp)
    }
}

@Composable
private fun WBtn(label: String) {
    Box(
        Modifier
            .weight(1f)
            .clip(RoundedCornerShape(13.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(13.dp))
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = GlassTokens.onGlass, fontSize = 11.sp)
    }
}

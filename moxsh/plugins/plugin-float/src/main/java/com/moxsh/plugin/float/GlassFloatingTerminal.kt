package com.moxsh.plugin.float

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.moxsh.core.TerminalCore
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.component.glassBlur

/**
 * 液态玻璃悬浮终端窗（D5 体系② + D3 玻璃 UI）。
 *
 *  - 玻璃底板：半透明 + 圆角 + 描边，背后内容经 [glassBlur] 实时模糊（API31+）；
 *  - 标题栏即拖拽手柄（[detectDragGestures] 把位移交给宿主更新 WindowManager）；
 *  - 右下角缩放手柄调整窗口尺寸；
 *  - 中部内嵌自绘 [CanvasTerminal]，与主 app 共享同一 TerminalCore 会话。
 *
 * @param session 由 [FloatGlassService] 打开的终端会话
 * @param onClose 关闭（移除悬浮窗）
 * @param onMove 拖拽位移 (dx,dy)，由宿主写回 WindowManager.LayoutParams
 * @param onResize 缩放位移 (dw,dh)，由宿主改窗口宽高
 */
@Composable
fun GlassFloatingTerminal(
    session: TerminalCore.Session,
    onClose: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onResize: (Int, Int) -> Unit,
    perfBlur: Boolean = true,
    fontSizeSp: Float = 14f,
) {
    Box(Modifier.fillMaxSize()) {
        // 玻璃底板（模糊背后内容）
        Spacer(
            Modifier
                .fillMaxSize()
                .glassBlur(perfBlur, 20f)
                .background(GlassTokens.surfaceTint, RoundedCornerShape(18.dp))
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(18.dp)),
        )

        Column(Modifier.fillMaxSize().padding(10.dp)) {
            // 标题栏 = 拖拽手柄
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag -> onMove(drag.x.toInt(), drag.y.toInt()) }
                    }
                    .background(GlassTokens.surfaceTint, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("悬浮终端", color = GlassTokens.onGlass)
                Text("✕", color = GlassTokens.onGlassDim,
                    modifier = Modifier.clickable { onClose() })
            }

            Spacer(Modifier.height(8.dp))

            // 终端画布区域
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(GlassTokens.termTint, RoundedCornerShape(12.dp))
                    .padding(6.dp),
            ) {
                CanvasTerminal(session = session, fontSizeSp = fontSizeSp)
            }
        }

        // 右下角缩放手柄
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(30.dp)
                .background(GlassTokens.surfaceTint, RoundedCornerShape(8.dp))
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(8.dp))
                .pointerInput(Unit) { detectDragGestures { _, drag -> onResize(drag.x.toInt(), drag.y.toInt()) } },
            contentAlignment = Alignment.Center,
        ) {
            Text("⤡", color = GlassTokens.onGlass)
        }
    }
}

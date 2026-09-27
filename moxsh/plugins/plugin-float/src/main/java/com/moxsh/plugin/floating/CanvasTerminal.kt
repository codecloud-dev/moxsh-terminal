package com.moxsh.plugin.floating

import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.moxsh.core.TerminalCore
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 简化版 Canvas 终端渲染（app 当前未提供 TerminalView，故在插件内自绘）。
 *
 * 直接消费 [TerminalCore.Session] 的单元格二进制布局（见 TerminalCore.kt 注释）：
 *   每格 16 字节：code:u32 | fg:u32 | bg:u32 | attrs:u16（小端）。
 * 每 ~60ms 调一次 [TerminalCore.Session.pump] 驱动 PTY 输出并触发重组重绘。
 *
 * 复用思路：与主 app 同一 TerminalCore 内核，能力等价，无 Termux:Float 的「退化副本」问题。
 */
@Composable
fun CanvasTerminal(
    session: TerminalCore.Session,
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 14f,
) {
    // 重组触发器：每帧自增，让 Canvas 重画
    var tick by remember { mutableStateOf(0) }
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
        }
    }

    // 后台泵循环：拉取 PTY 输出
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { session.pump() }
            tick++
            delay(60)
        }
    }

    // tick 变化触发 CanvasTerminal 重组，从而重画 Canvas
    Canvas(modifier) {
        val density = this.density // DrawScope 实现 Density 接口，density 即 Float
        val cellW = fontSizeSp * density * 0.6f
        val cellH = fontSizeSp * density * 1.2f
        paint.textSize = fontSizeSp * density

        val cols = session.cols.coerceAtLeast(1)
        val rows = session.rows.coerceAtLeast(1)
        val buf = ByteArray(cols * rows * TerminalCore.CELL_SIZE)
        val got = runCatching { session.copyCells(0, rows, buf) }.getOrDefault(0)
        if (got <= 0) return@Canvas

        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val base = (r * cols + c) * TerminalCore.CELL_SIZE
                val code = bb.getInt(base).toInt()
                val fg = bb.getInt(base + 4).toLong() and 0xFFFFFFFFL
                if (code == 0) continue // 空单元格
                val ch = code.toChar()
                if (ch.isISOControl() && ch != '\n') continue
                paint.color = if (fg == 0L) Color.WHITE else fg.toInt()
                drawContext.canvas.nativeCanvas.drawText(
                    ch.toString(),
                    c * cellW,
                    (r + 1) * cellH,
                    paint,
                )
            }
        }
    }
}

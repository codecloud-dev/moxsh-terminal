package com.moxsh.ui.terminal

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.core.TerminalCore
import com.moxsh.shared.ExecutionEngine
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ---------------------------------------------------------------------------
// 跨语言单元格契约（对齐 terminal-core TerminalCore.CELL_SIZE = 16，小端）：
//   offset 0 : code  u32 —— Unicode 码点；0 表示空格/宽字符 continuation（不绘制）
//   offset 4 : fg    u32 —— 前景色编码（见 cellColor：0=默认，1..255=色板，其余=ARGB 直读）
//   offset 8 : bg    u32 —— 背景色编码（0=默认透明，透出玻璃底色）
//   offset 12: attrs u16 —— 位标志：bit0 bold / bit1 italic / bit2 underline / bit3 inverse
//   offset 14: （2 字节对齐填充）
// ---------------------------------------------------------------------------

private const val ATTR_BOLD = 0x0001
private const val ATTR_ITALIC = 0x0002
private const val ATTR_UNDERLINE = 0x0004
private const val ATTR_INVERSE = 0x0008

/** 默认前景（近白）；0 号前景色时使用。toInt() 非编译期常量，故 val。 */
private val DEFAULT_FG = 0xFFE8ECEE.toInt()

/** 默认背景（透明）；0 号背景色时不填充矩形，让玻璃底色透出。 */
private const val DEFAULT_BG = 0x00000000

/** 捏合缩放的字号上下限（sp）。 */
private const val FONT_MIN = 8f
private const val FONT_MAX = 32f

/**
 * IME 哨兵字符（零宽空格）：受控输入框永远保持 1 个字符在末尾，
 * 使退格总能"删掉"它并触发 onValueChange（否则空文本时 IME 退格不会回调）。
 */
private const val IME_SENTINEL = "\u200b"

/** ANSI 1..15 基础色板（1=黑；0 保留为"默认色"哨兵，故整体偏移 1 位，共 15 项）。 */
private val ANSI16 = intArrayOf(
    0xFF2E3436.toInt(), // 1  black
    0xFFCC0000.toInt(), // 2  red
    0xFF4E9A06.toInt(), // 3  green
    0xFFC4A000.toInt(), // 4  yellow
    0xFF3465A4.toInt(), // 5  blue
    0xFF75507B.toInt(), // 6  magenta
    0xFF06989A.toInt(), // 7  cyan
    0xFFD3D7CF.toInt(), // 8  white
    0xFF555753.toInt(), // 9  bright black
    0xFFEF2929.toInt(), // 10 bright red
    0xFF8AE234.toInt(), // 11 bright green
    0xFFFCE94F.toInt(), // 12 bright yellow
    0xFF729FCF.toInt(), // 13 bright blue
    0xFFAD7FA8.toInt(), // 14 bright magenta
    0xFF34E2E2.toInt(), // 15 bright cyan
)

/** 6x6x6 色立方的灰阶档位（xterm 标准）。 */
private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

/**
 * 解析内核颜色编码为 ARGB Int。
 * 约定：0 = 默认色（返回 null，由调用方用默认色兜底）；
 *       1..15 = 基础 16 色；16..231 = 6x6x6 色立方；232..255 = 灰阶；
 *       其余（>255）按 0xAARRGGBB/0xRRGGBB 直读。
 */
private fun cellColor(v: Int): Int? = when {
    v <= 0 -> null
    v <= 15 -> ANSI16[v - 1]
    v <= 231 -> {
        val n = v - 16
        val r = CUBE_LEVELS[n / 36]
        val g = CUBE_LEVELS[(n % 36) / 6]
        val b = CUBE_LEVELS[n % 6]
        0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
    v <= 255 -> {
        val g = 8 + (v - 232) * 10
        0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
    }
    else -> v // 内核直接写 ARGB 的高位编码
}

/** 把字符串写入会话 PTY（UTF-8）。空串与无效会话静默忽略。 */
internal fun sendToSession(sessionId: Long, s: String) {
    if (sessionId <= 0L || s.isEmpty()) return
    ExecutionEngine.write(sessionId, s.toByteArray(Charsets.UTF_8))
}

/**
 * 终端渲染屏（app 侧真实终端）。
 *
 * 职责：
 *  1. 渲染：由视图尺寸与字号换算 cols/rows，经 [ExecutionEngine.copyCells] 复制可见区
 *     （含滚动历史），Compose Canvas 逐格绘制（小端 16 字节 Cell 解析）。
 *  2. 滚动：纵向手势映射 startRow = totalRows - visibleRows - scrollOffset；
 *     新输出到来时若 scrollOffset=0 自动贴底跟随，回看历史时视口冻结。
 *  3. 尺寸联动：cols/rows 变化（含捏合字号）自动 [ExecutionEngine.resize]。
 *  4. 输入：隐藏受控输入框捕获软键盘（哨兵机制保证退格可达），
 *     物理键盘走 onPreviewKeyEvent，均转 VT 序列经 [ExecutionEngine.write] 写入。
 *  5. 修饰键：Ctrl/Alt 由底部 ExtraKeys 玻璃栏切换态，作用于下一个字符后回调复位。
 *
 * 会话生命周期：会话创建与 startPump 由上层（MainActivity）持有，
 * 本组件只做 resize 联动与渲染，避免标签切换时重复创建/销毁会话。
 *
 * @param sessionId ExecutionEngine 会话句柄（>0 有效）。
 * @param tick      泵重绘信号：泵循环每读到新输出即自增，驱动本 Canvas 失效重绘。
 * @param fontSizeSp 当前字号（sp），捏合缩放时回调 [onFontSizeChange]。
 * @param ctrlActive/altActive ExtraKeys 修饰态。
 * @param onModifiersConsumed 修饰键被实际消耗（作用于某字符）后回调，参数为 (消耗了Ctrl, 消耗了Alt)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TerminalScreen(
    sessionId: Long,
    tick: Long,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onModifiersConsumed: (usedCtrl: Boolean, usedAlt: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }

    // 等宽 Paint：cell 尺寸由真实字形 advance/fontSpacing 测量，保证 cols/rows 与绘制一致
    val paint = remember(fontSizePx) {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSizePx
            isAntiAlias = true
        }
    }
    val cellW = remember(fontSizePx) { paint.measureText("W") }
    val cellH = remember(fontSizePx) { paint.fontSpacing }

    // 视图像素尺寸 -> 终端网格
    var viewW by remember { mutableStateOf(0) }
    var viewH by remember { mutableStateOf(0) }
    val cols = if (cellW > 0f && viewW > 0) (viewW / cellW).toInt().coerceIn(2, 500) else 0
    val rows = if (cellH > 0f && viewH > 0) (viewH / cellH).toInt().coerceIn(2, 200) else 0

    // 网格变化（布局完成/旋转/捏合字号）同步内核窗口尺寸（触发 SIGWINCH）
    LaunchedEffect(sessionId, cols, rows) {
        if (sessionId > 0L && cols > 0 && rows > 0) {
            ExecutionEngine.resize(sessionId, cols, rows)
        }
    }

    // 回滚偏移：0 = 贴底（跟随最新输出），>0 = 回看历史（历史冻结视口）
    var scrollOffset by remember { mutableStateOf(0) }

    // 可见区单元格缓冲：rows 行 * cols 列 * 16 字节（CELL_SIZE）
    val buf = remember(cols, rows) {
        if (cols > 0 && rows > 0) ByteArray(rows * cols * TerminalCore.CELL_SIZE) else ByteArray(0)
    }

    // ---- 输入管线 ----------------------------------------------------------
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var ime by remember {
        mutableStateOf(TextFieldValue(text = IME_SENTINEL, selection = TextRange(IME_SENTINEL.length)))
    }

    /** 发送一个字符：应用 Ctrl/Alt 修饰态，生效后回调复位。 */
    fun sendChar(ch: Char) {
        when {
            // 回车统一映射 CR（VT 语义）
            ch == '\n' || ch == '\r' -> sendToSession(sessionId, "\r")
            // Ctrl 修饰：字符与 0x1f 位与得控制码（Ctrl+C=0x03 等）
            ctrlActive && ch.code in 0x20..0x7E -> {
                sendToSession(sessionId, (ch.code and 0x1f).toChar().toString())
                onModifiersConsumed(true, false)
            }
            // Alt 修饰：ESC 前缀（Meta 语义）
            altActive -> {
                sendToSession(sessionId, "\u001b" + ch)
                onModifiersConsumed(false, true)
            }
            else -> sendToSession(sessionId, ch.toString())
        }
    }

    // 手势闭包里读到的最新字号（pointerInput 闭包随 fontSizeSp 变化用最新值）
    val currentFontSize by rememberUpdatedState(fontSizeSp)

    GlassSurface(modifier, tint = GlassTokens.termTint) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewW = it.width; viewH = it.height }
                // 双指捏合调字号；单指拖动/双指平移做回滚（pan.y>0 下滑看历史）
                .pointerInput(cellH, fontSizeSp) {
                    // 回调签名：(centroid, pan, zoom, rotation)
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            val next = (currentFontSize * zoom).coerceIn(FONT_MIN, FONT_MAX)
                            if (next != currentFontSize) onFontSizeChange(next)
                        }
                        if (cellH > 0f && pan.y != 0f) {
                            scrollOffset += (pan.y / cellH).toInt()
                        }
                    }
                }
                // 点按：抓焦点并拉起软键盘
                .pointerInput(sessionId) {
                    detectTapGestures {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                }
                // 物理键盘：终端语义拦截（方向键/退格/回车/Tab/ESC 转 VT 序列）
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionUp -> { sendToSession(sessionId, "\u001b[A"); true }
                        Key.DirectionDown -> { sendToSession(sessionId, "\u001b[B"); true }
                        Key.DirectionRight -> { sendToSession(sessionId, "\u001b[C"); true }
                        Key.DirectionLeft -> { sendToSession(sessionId, "\u001b[D"); true }
                        Key.Backspace -> { sendToSession(sessionId, "\u007f"); true }
                        Key.Enter, Key.NumPadEnter -> { sendToSession(sessionId, "\r"); true }
                        Key.Tab -> { sendToSession(sessionId, "\t"); true }
                        Key.Escape -> { sendToSession(sessionId, "\u001b"); true }
                        else -> false // 其余按键交给焦点系统/输入框
                    }
                }
        ) {
            // ---- Canvas 渲染：逐格解析 16 字节 Cell 并绘制 ----
            Canvas(Modifier.matchParentSize()) {
                // 引用 tick：泵每读到新输出 tick 自增 -> 重组 -> 本 draw lambda 更新并失效重绘
                @Suppress("UNUSED_VARIABLE") val frameNo = tick
                if (sessionId <= 0L || cols <= 0 || rows <= 0 || buf.isEmpty()) return@Canvas

                val total = ExecutionEngine.totalRows(sessionId)
                val maxOffset = (total - rows).coerceAtLeast(0)
                val offset = scrollOffset.coerceIn(0, maxOffset)
                // 纵向滚动映射：startRow = totalRows - visibleRows - scrollOffset
                val startRow = (total - rows - offset).coerceAtLeast(0)

                val n = ExecutionEngine.copyCells(sessionId, startRow, rows, buf)
                if (n <= 0) return@Canvas

                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val baseline = -paint.ascent() // 行内基线偏移（ascent 为负）

                for (r in 0 until rows) {
                    val rowTop = r * cellH
                    for (c in 0 until cols) {
                        if (!bb.hasRemaining()) break
                        val code = bb.int
                        val fg = bb.int
                        val bg = bb.int
                        val attrs = bb.short.toInt() and 0xFFFF

                        // 空格 / 宽字符 continuation（内核已把 emoji 等合单格）不重复绘制
                        if (code == 0) continue

                        var fgInt = cellColor(fg) ?: DEFAULT_FG
                        var bgInt = cellColor(bg) ?: DEFAULT_BG
                        if (attrs and ATTR_INVERSE != 0) {
                            val t = fgInt; fgInt = bgInt; bgInt = t
                            if (bgInt == DEFAULT_BG) {
                                // 反显且双方皆默认：底用默认前景、字用深色
                                bgInt = DEFAULT_FG
                                fgInt = 0xFF14161A.toInt()
                            }
                        }

                        // 背景（默认透明不填充，透出玻璃底色）
                        if (bgInt != DEFAULT_BG) {
                            drawRect(
                                color = Color(bgInt),
                                topLeft = Offset(c * cellW, rowTop),
                                size = Size(cellW, cellH),
                            )
                        }

                        // 字形属性（bold/italic/underline）
                        paint.color = fgInt
                        paint.isFakeBoldText = attrs and ATTR_BOLD != 0
                        paint.textSkewX = if (attrs and ATTR_ITALIC != 0) -0.25f else 0f
                        paint.isUnderlineText = attrs and ATTR_UNDERLINE != 0

                        // code 为 Unicode 码点（含增补平面），toChars 处理代理对
                        val text = String(Character.toChars(code))
                        drawIntoCanvas { it.nativeCanvas.drawText(text, c * cellW, rowTop + baseline, paint) }
                    }
                }
            }

            // ---- 隐藏受控输入框：捕获软键盘 ----
            // 哨兵机制：value 恒为 1 个零宽字符且光标钉在末尾，
            // 长度减少 => 退格（发 \x7f）；长度增加 => 追加字符逐个发送。
            BasicTextField(
                value = ime,
                onValueChange = { new ->
                    val oldLen = ime.text.length
                    val newLen = new.text.length
                    when {
                        newLen < oldLen -> repeat(oldLen - newLen) { sendToSession(sessionId, "\u007f") }
                        newLen > oldLen -> new.text.substring(oldLen).forEach(::sendChar)
                    }
                    // 立即重置回哨兵，等待下一次输入
                    ime = TextFieldValue(text = IME_SENTINEL, selection = TextRange(IME_SENTINEL.length))
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { sendToSession(sessionId, "\r") }),
                maxLines = 1,
                textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(focusRequester),
            )
        }
    }
}

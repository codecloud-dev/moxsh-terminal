package com.moxsh.core

/**
 * 终端内核 JNI 桥接（L3 自研 Rust 内核的 Kotlin 入口）。
 * 对应 terminal-core/cpp/bridge.cpp 转发的 Rust C-ABI（moxsh_*）。
 *
 * 加载顺序：先加载 Rust 内核 cdylib `moxshcore`（内含 C++ 桥接与 NEON 汇编），
 * 再由 bridge.cpp 的 JNI 入口进入 Rust 逻辑。
 *
 * Cell 跨语言二进制布局（小端，每格 16 字节）：
 *   code: u32 (4) | fg: u32 (4) | bg: u32 (4) | attrs: u16 (2)
 * Kotlin 侧用 ByteBuffer.order(LITTLE_ENDIAN) 按此结构读取。
 */
class TerminalCore {

    companion object {
        init {
            System.loadLibrary("moxshcore")
        }

        /** Cell 字节布局：code(4) + fg(4) + bg(4) + attrs(2) = 16 字节。 */
        const val CELL_SIZE = 16
    }

    external fun nativeOpenPty(cmd: String, cols: Int, rows: Int): Long
    external fun nativePump(sess: Long, buf: ByteArray): Int
    external fun nativeWrite(sess: Long, buf: ByteArray): Int
    external fun nativeResize(sess: Long, cols: Int, rows: Int): Int
    external fun nativeClose(sess: Long)
    external fun nativeSessionExitStatus(sess: Long): Int
    external fun nativeScreenRows(sess: Long): Int
    external fun nativeScreenCols(sess: Long): Int
    external fun nativeTotalRows(sess: Long): Int
    external fun nativeCopyCells(sess: Long, startRow: Int, count: Int, buf: ByteArray): Int

    /** 打开一个终端会话；失败返回 null。 */
    fun open(command: String, cols: Int, rows: Int): Session? {
        val ptr = nativeOpenPty(command, cols, rows)
        return if (ptr != 0L) Session(this, ptr, command, cols, rows) else null
    }

    /** 单个终端会话的 Kotlin 句柄（多会话由调用方管理列表）。 */
    class Session(
        private val core: TerminalCore,
        val ptr: Long,
        val command: String,
        var cols: Int,
        var rows: Int,
    ) {
        private val pumpBuf = ByteArray(1 shl 16)

        /** 从 PTY 拉取一次输出并驱动 VT 解析；返回读取字节数（0=EOF，-1=错误）。 */
        fun pump(): Int = core.nativePump(ptr, pumpBuf)

        /** 写入键盘/输入字节，返回写入字节数（-1=错误）。 */
        fun write(data: ByteArray): Int = core.nativeWrite(ptr, data)

        /** 更新窗口尺寸（cols/rows 联动更新）。 */
        fun resize(cols: Int, rows: Int) {
            core.nativeResize(ptr, cols, rows)
            this.cols = cols
            this.rows = rows
        }

        fun close() = core.nativeClose(ptr)

        /**
         * 非阻塞轮询子进程退出状态（WNOHANG 收割）。
         * @return `>=0` 已退出（信号退出为 128+sig）；`-1` 仍在运行；`-2` 空指针。
         * 幂等：收割后重复调用返回同一退出码。
         */
        fun exitStatus(): Int = core.nativeSessionExitStatus(ptr)

        /** 总行数 = 可见行 + 历史回滚行，供滚动视图定位。 */
        val totalRows: Int get() = core.nativeTotalRows(ptr)

        /** 把绝对行 [startRow, startRow+count) 的单元格复制到 out（每行 cols*16 字节）。 */
        fun copyCells(startRow: Int, count: Int, out: ByteArray): Int =
            core.nativeCopyCells(ptr, startRow, count, out)
    }
}

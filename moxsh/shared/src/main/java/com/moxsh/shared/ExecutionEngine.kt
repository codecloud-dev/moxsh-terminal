package com.moxsh.shared

import com.moxsh.core.TerminalCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 执行引擎（L4）：管理多个终端会话、PTY、以及输入/输出泵循环。
 *
 * 设计要点：
 *  - 内部用 [TerminalCore] 创建并持有多个 [TerminalCore.Session]，对外暴露自增的 Long 句柄，
 *    与 terminal-core 的裸指针彻底解耦，避免上层直接碰 native 指针。
 *  - 用 [ConcurrentHashMap] 存储会话，保证多线程（尤其 [startPump] 起的协程）安全。
 *  - [startPump] 使用 kotlinx.coroutines 在 [Dispatchers.IO] 上起一个独立的泵循环，
 *    反复调用 [pump] 把 PTY 输出驱动出来；返回字节数 > 0 时回调 [onUpdate]，
 *    EOF（n<=0）或协程被取消（[stopPump]）时自动停止。
 *
 * 该类为单例 [object]，全局唯一，负责整份应用的会话生命周期。
 */
object ExecutionEngine {

    /**
     * 默认 shell（动态判定，非编译期常量）。
     *
     * 运行环境就绪（bootstrap 解压完成）后，$PREFIX/bin/login 为自研入口脚本
     * （BootstrapInstaller.initialize 生成：设置 PREFIX/HOME/PATH 等环境后 exec bash -l），
     * 终端新会话即获得完整 Linux 环境；环境未就绪时回退系统 shell 占位。
     */
    val DEFAULT_SHELL: String
        get() {
            val login = java.io.File("$PREFIX/bin/login")
            return if (login.exists() && login.canExecute()) "$PREFIX/bin/login" else "/system/bin/sh"
        }

    /** 自研运行环境前缀（与 CompatShim.PREFIX 同源，M3 就绪后启用自有 login）。 */
    const val PREFIX: String = "/data/data/com.moxsh/files/usr"

    /** 泵循环每次 pump 后让出 8ms，平衡实时性与 CPU 占用（与架构 §3 滚动/渲染节奏配合）。 */
    private const val PUMP_INTERVAL_MS = 8L

    /** 单一的 Rust 内核桥接实例。 */
    private val core = TerminalCore()

    /** id -> 会话句柄。 */
    private val sessions = ConcurrentHashMap<Long, TerminalCore.Session>()

    /** 自增会话 id 分配器（从 1 开始，0 与 -1 保留作错误哨兵）。 */
    private val nextId = AtomicLong(1)

    /**
     * 当前活跃会话（UI 切换标签时更新；新建会话时自动指向它）。
     * 插件经 PluginHost.runLocalCommand 向此会话写入（0/-1 是哨兵，不可用）。
     */
    @Volatile
    var activeSessionId: Long = -1L

    /** 每个 id 对应的泵协程 Job，用于 [stopPump] 精确取消。 */
    private val pumpJobs = ConcurrentHashMap<Long, Job>()

    /** 泵协程作用域：IO 调度 + 监督 Job，单个会话泵失败不影响其它会话。 */
    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 会话生命周期监听（插件宿主装配时设置；null = 无监听）。
     * 方向设计：shared 不感知 plugin 层，由 plugin-core 注册回调，避免循环依赖。
     */
    interface SessionListener {
        fun onSessionOpened(id: Long, command: String)
        fun onSessionClosed(id: Long)
    }

    /** 进程级会话事件监听（[com.moxsh.plugin.core.PluginHost] 装配时注入）。 */
    @Volatile
    var sessionListener: SessionListener? = null

    /**
     * 创建一个终端会话。
     * @param command 要执行的命令（默认 [DEFAULT_SHELL]）。
     * @param cols 初始列数。
     * @param rows 初始行数。
     * @return 新会话的自增 id；内核打开失败返回 -1L。
     */
    fun createSession(command: String = DEFAULT_SHELL, cols: Int, rows: Int): Long {
        val session = core.open(command, cols, rows) ?: return -1L
        val id = nextId.getAndIncrement()
        sessions[id] = session
        activeSessionId = id
        sessionListener?.onSessionOpened(id, command)
        return id
    }

    /** 会话关闭专用串行执行器：close 与仍在途的最后几次 native 调用天然隔离。 */
    private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "moxsh-session-closer").apply { isDaemon = true }
    }

    /**
     * 销毁一个会话：停泵 → 从表移除 → 延迟一拍关闭 native 会话。
     *
     * P1 修复（use-after-free）：native master 已非阻塞（EAGAIN 即返），
     * stopPump 后泵协程最多再执行一轮即退出；close 再延迟 60ms 落到专用线程，
     * 保证"泵最后一次 native pump"与"close 释放"不重叠。
     */
    fun destroySession(id: Long) {
        stopPump(id)
        val session = sessions.remove(id)
        sessionListener?.onSessionClosed(id)
        if (session != null) {
            closer.execute {
                runCatching { Thread.sleep(60) }
                runCatching { session.close() }
            }
        }
    }

    /** 向会话写入用户输入字节（键盘/粘贴等）。返回是否成功（无会话时 false）。 */
    fun write(id: Long, data: ByteArray): Boolean {
        val s = sessions[id] ?: return false
        s.write(data)
        return true
    }

    /** 更新会话窗口尺寸（cols/rows 联动写入内核）。无会话时静默忽略。 */
    fun resize(id: Long, cols: Int, rows: Int) {
        sessions[id]?.resize(cols, rows)
    }

    /**
     * 拉取一次 PTY 输出并驱动 VT 解析。
     * @return 读取字节数（0=EOF，-1=错误）；无会话返回 -1。
     */
    fun pump(id: Long): Int = sessions[id]?.pump() ?: -1

    /**
     * 非阻塞查询会话子进程退出状态（WNOHANG 收割，绝不挂起）。
     * @return `>=0` 已退出（信号退出为 128+sig）；`-1` 仍在运行或无此会话。
     *
     * 泵循环在 pump 返回 0（干净 EOF——含子进程退出后的 EIO 折叠）时自然停止，
     * 随后可用本 API 取退出码驱动"会话已结束"的 UI 提示。
     */
    fun exitStatus(id: Long): Int = sessions[id]?.exitStatus() ?: -1

    /** 总行数（可见行 + 历史回滚行），供滚动视图定位。 */
    fun totalRows(id: Long): Int = sessions[id]?.totalRows ?: 0

    /** 当前列数。 */
    fun cols(id: Long): Int = sessions[id]?.cols ?: 0

    /** 当前行数。 */
    fun rows(id: Long): Int = sessions[id]?.rows ?: 0

    /**
     * 复制绝对行 [startRow, startRow+count) 的单元格到 [out]。
     * 每行写入 cols * [TerminalCore.CELL_SIZE] 字节。
     * @return 实际复制的字节数；无会话返回 -1。
     */
    fun copyCells(id: Long, startRow: Int, count: Int, out: ByteArray): Int =
        sessions[id]?.copyCells(startRow, count, out) ?: -1

    /**
     * 启动一个持续的泵循环：在 [Dispatchers.IO] 上反复 [pump]，
     * 返回字节数 > 0 时回调 [onUpdate]（传入本次读取字节数），随后让出 [PUMP_INTERVAL_MS]。
     * 当 pump 返回 <= 0（EOF/错误）或协程被取消（[stopPump]）时停止。
     *
     * 若同一 id 已有泵在跑，会先取消旧泵再起新泵，避免重复泵。
     */
    fun startPump(id: Long, onUpdate: (Int) -> Unit) {
        if (!sessions.containsKey(id)) return
        // P2 修复：check-then-act 原子化——UI 泵与 Service 兜底泵并发启动时，
        // 旧实现可能让两个泵同时消费同一 PTY（输出被瓜分）。
        // compute 内完成"停旧 + 登记新"，同 id 泵互斥由 ConcurrentHashMap 锁保证。
        // 泵自然结束（EOF/错误/取消）时的登记清理由下次 startPump 的 compute 覆盖。
        val newJob = pumpScope.launch {
            // isActive: 本协程是否被取消（stopPump 触发）；sessions 含 id: 会话是否仍存活。
            while (isActive && sessions.containsKey(id)) {
                val n = pump(id)
                when {
                    // -2 = 暂无数据（master 非阻塞 EAGAIN）：继续轮询，非 EOF
                    n == -2 -> Unit
                    n <= 0 -> break
                    else -> onUpdate(n)
                }
                delay(PUMP_INTERVAL_MS)
            }
        }
        pumpJobs.compute(id) { _, old -> old?.cancel(); newJob }
    }

    /** 停止指定会话的泵循环（若正在跑）。 */
    fun stopPump(id: Long) {
        pumpJobs.remove(id)?.cancel()
    }

    /** 当前存活会话数。 */
    fun sessionCount(): Int = sessions.size

    /** 列出所有会话 id（快照）。 */
    fun listSessions(): List<Long> = sessions.keys.toList()
}

package com.moxsh.plugin.distro

import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * /proc 采样器（资源监控面板的数据源，Kotlin 侧自研，无需任何 native）。
 *
 * 数据来源：
 *  - CPU：`/proc/stat` 第一行 `cpu user nice system idle iowait irq softirq steal …`，
 *    相邻两次采样做差，busy/(total) 即占用率（首帧无差值记 0）；
 *  - 内存：`/proc/meminfo` 的 MemTotal / MemAvailable；
 *  - 磁盘：`/proc/self/mountinfo` 中找 /data 的挂载设备（展示挂载点用），
 *    容量用 [StatFs]（与 mountinfo 的设备对应，无需 root）；
 *  - 进程：遍历 `/proc/<pid>/`，读 cmdline（名）+ status 的 Uid 行（权限过滤）+
 *    stat 的 utime+stime（做差 / 采样间隔，折算单核百分比）；内存用 statm 首字段
 *    rss 页数 × 4KB。
 *
 * Android 11+ 的 /proc 可见性限制（重要注释）：
 *  - 内核对普通 app 开启 hidepid 语义：/proc/<pid> 只能看到 **同 uid** 进程；
 *  - 这对 moxsh 恰好够用：发行版内的一切（proot 子进程、apt、python…）都是
 *    本 app uid 的后代进程，全部可见、可 kill；
 *  - 其它 app / 系统进程的 pid 目录不存在（open 失败 ENOENT），静默跳过即可，
 *    这不是 bug 而是安全边界；无需请求任何权限，也无法绕过（勿尝试 root 提权）。
 *
 * 采样节奏：1 秒一次（由界面侧协程驱动 [sample]），差值窗口即 1 秒。
 */
internal class ProcSampler {

    /** 单个进程条目（进程列表玻璃行）。 */
    data class ProcInfo(
        val pid: Int,
        val name: String,
        val cpuPercent: Float,
        val memMb: Float,
    )

    /** 一次全量采样结果（三张仪表卡 + 进程列表共用）。 */
    data class Sample(
        val cpuPercent: Float = 0f,
        val memUsedMb: Long = 0L,
        val memTotalMb: Long = 0L,
        val diskUsedGb: Float = 0f,
        val diskTotalGb: Float = 0f,
        val procs: List<ProcInfo> = emptyList(),
    )

    /** 上一次 /proc/stat 各列（差值基数）。 */
    private var prevCpu: LongArray? = null

    /** 上一次每个 pid 的 utime+stime 总和（差值基数）。 */
    private var prevProcTicks: Map<Int, Long> = emptyMap()

    private val pageBytes = 4096L // 内核页大小（Android 全系 4KB；如需精确可用 getconf PAGESIZE）

    /** 采样一次；全部读取失败时返回安全零值（不影响 UI）。 */
    fun sample(): Sample {
        val cpu = sampleCpu()
        val (memUsed, memTotal) = sampleMem()
        val (diskUsed, diskTotal) = sampleDisk()
        val procs = sampleProcs()
        return Sample(cpu, memUsed, memTotal, diskUsed, diskTotal, procs)
    }

    /** CPU 占用率：两次 /proc/stat 差值，busy = total - idle - iowait。 */
    private fun sampleCpu(): Float {
        val line = runCatching {
            File("/proc/stat").bufferedReader().readLine()
        }.getOrNull() ?: return 0f
        val cols = line.removePrefix("cpu").trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (cols.size < 4) return 0f
        val cur = cols.toLongArray()
        val prev = prevCpu
        prevCpu = cur
        if (prev == null || prev.size < cur.size) return 0f
        val total = cur.indices.sumOf { cur[it] - prev[it] }
        if (total <= 0) return 0f
        val idle = (cur.getOrElse(3) { 0 } - prev.getOrElse(3) { 0 }) +
            (cur.getOrElse(4) { 0 } - prev.getOrElse(4) { 0 }) // idle + iowait
        val busy = (total - idle).coerceAtLeast(0)
        return busy * 100f / total
    }

    /** 内存：MemTotal / MemAvailable（kB）。 */
    private fun sampleMem(): Pair<Long, Long> {
        val info = runCatching {
            File("/proc/meminfo").bufferedReader().readLines()
                .take(3) // MemTotal / MemFree / MemAvailable 通常在前 3 行
                .associate {
                    val p = it.split(Regex("\\s+"))
                    p[0].removeSuffix(":") to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
                }
        }.getOrNull() ?: return 0L to 0L
        val total = info["MemTotal"] ?: 0L
        val avail = info["MemAvailable"] ?: info["MemFree"] ?: 0L
        return ((total - avail) / 1024L) to (total / 1024L)
    }

    /**
     * 磁盘：从 /proc/self/mountinfo 找 /data 挂载点（确认分区确实挂载），
     * 容量用 StatFs(/data)。返回 (used GB, total GB)。
     */
    private fun sampleDisk(): Pair<Float, Float> = runCatching {
        // mountinfo 第 5 字段是挂载点；这里只确认 /data 在列（合法性与可读性检查）。
        val mounted = File("/proc/self/mountinfo").bufferedReader().useLines { lines ->
            lines.any { it.split(" ").getOrNull(4) == "/data" }
        }
        if (!mounted) return 0f to 0f
        val fs = StatFs("/data")
        val totalGb = fs.totalBytes / (1024f * 1024 * 1024)
        val usedGb = (fs.totalBytes - fs.availableBytes) / (1024f * 1024 * 1024)
        usedGb to totalGb
    }.getOrDefault(0f to 0f)

    /**
     * 进程：遍历 /proc/<pid>，只保留能读到的（天然 = 同 uid 进程，见类注释）。
     * CPU% = (utime+stime 差值) / (采样间隔 × 每秒 tick 100) × 100，即单核百分比。
     */
    private fun sampleProcs(): List<ProcInfo> {
        val myUid = android.os.Process.myUid()
        val now = HashMap<Int, Long>()
        val result = ArrayList<ProcInfo>()
        val procDir = File("/proc")
        val pidDirs = procDir.listFiles { f -> f.name.all { it.isDigit() } } ?: return emptyList()
        for (dir in pidDirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val name = runCatching {
                File(dir, "cmdline").inputStream().bufferedReader().readLine() // NUL 结尾，读首行
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue // 读不到=非同 uid，静默跳过
            // 权限过滤：status 的 Uid 行必须等于本 app uid（双保险，正常必然成立）
            val uid = runCatching {
                File(dir, "status").bufferedReader().useLines { ls ->
                    ls.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)
                }
            }.getOrNull()?.toIntOrNull() ?: continue
            if (uid != myUid) continue
            // stat：utime(14) stime(15)——comm 可含空格/括号，从最后一个 ')' 之后按空格切
            val stat = runCatching { File(dir, "stat").bufferedReader().readText() }.getOrNull() ?: continue
            val after = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L // after[0] 是 state，故 utime 在 11
            val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L
            val total = utime + stime
            now[pid] = total
            val rssPages = runCatching {
                File(dir, "statm").bufferedReader().useLines { ls -> ls.firstOrNull()?.split(" ")?.getOrNull(1) }
            }.getOrNull()?.toLongOrNull() ?: 0L
            val prev = prevProcTicks[pid] ?: total // 新进程首帧记 0%
            // cpu% = tick 差值 / (USER_HZ=100 ticks·秒⁻¹ × 1 秒窗口) × 100（单核百分比）
            val cpu = (total - prev).coerceAtLeast(0) * 100f / (100f * 1f)
            result.add(ProcInfo(pid, name, cpu, rssPages * pageBytes / (1024f * 1024)))
        }
        prevProcTicks = now
        return result.sortedByDescending { it.cpuPercent }
    }
}

/**
 * 资源监控面板（D13 四件套之三）。
 *
 * 三张玻璃仪表卡（CPU / 内存 / 磁盘）：
 *  - 1 秒采样差值算占用率（[ProcSampler]）；
 *  - Canvas 画迷你曲线（最近 60 点 = 1 分钟窗口）。
 *
 * 进程列表：玻璃列表显示 pid / 名 / CPU% / MEM；点按弹玻璃确认卡后 kill。
 * 权限边界（注释）：android.os.Process.killProcess(pid) 只对本 app uid 的进程
 * 生效（信号权限内核层判定）；要杀发行版内进程无需 root——它们都是本 app 的
 * proot 子进程，同 uid。杀其它应用进程在本平台上不可能也不允许。
 */
@Composable
fun ResourceMonitorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sampler = remember { ProcSampler() }
    // 最近 60 点历史（1 秒一采）
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() } // 0-100（占用百分比）
    val diskHistory = remember { mutableStateListOf<Float>() } // 0-100（占用百分比）
    var cur by remember { mutableStateOf(ProcSampler.Sample()) }
    // 待确认 kill 的进程（非 null 弹玻璃确认卡）
    var pendingKill by remember { mutableStateOf<ProcSampler.ProcInfo?>(null) }

    // 采样循环：1 秒一帧；文件 IO 放 IO 线程，结果回主线程更新快照状态
    LaunchedEffect(Unit) {
        while (isActive) {
            val s = withContext(Dispatchers.IO) { sampler.sample() }
            cur = s
            cpuHistory.add(s.cpuPercent)
            memHistory.add(if (s.memTotalMb > 0) s.memUsedMb * 100f / s.memTotalMb else 0f)
            diskHistory.add(if (s.diskTotalGb > 0) s.diskUsedGb * 100f / s.diskTotalGb else 0f)
            // 只保留最近 60 点
            if (cpuHistory.size > 60) cpuHistory.removeAt(0)
            if (memHistory.size > 60) memHistory.removeAt(0)
            if (diskHistory.size > 60) diskHistory.removeAt(0)
            delay(1000)
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ---- 顶栏 ----
        GlassSurface(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = GlassTokens.onGlass, fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
                Spacer(Modifier.width(10.dp))
                Text("资源监控", color = GlassTokens.onGlass, fontSize = 18.sp)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 三张玻璃仪表卡 ----
            GaugeCard(
                title = "CPU",
                valueText = "%.0f%%".format(cur.cpuPercent),
                subText = "1 秒差值采样 · /proc/stat",
                history = cpuHistory,
            )
            GaugeCard(
                title = "内存",
                valueText = "%d / %d MB".format(cur.memUsedMb, cur.memTotalMb),
                subText = "MemTotal − MemAvailable · /proc/meminfo",
                history = memHistory,
            )
            GaugeCard(
                title = "磁盘（/data 分区）",
                valueText = "%.1f / %.1f GB".format(cur.diskUsedGb, cur.diskTotalGb),
                subText = "StatFs + /proc/self/mountinfo",
                history = diskHistory,
            )

            // ---- 进程列表 ----
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("进程（本应用 uid 可见范围）", color = GlassTokens.onGlass)
                Text(
                    "Android 11+ 内核 hidepid：仅同 uid 进程可见——发行版内的 proot/" +
                        "apt/python 等子进程都在列；其它应用进程不可见也不可杀。",
                    color = GlassTokens.onGlassDim, fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                cur.procs.take(24).forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { pendingKill = p }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            p.name,
                            color = GlassTokens.onGlass, fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text("pid ${p.pid}", color = GlassTokens.onGlassDim, fontSize = 11.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("CPU %.0f%%".format(p.cpuPercent), color = GlassTokens.onGlassDim, fontSize = 11.sp)
                        Spacer(Modifier.width(10.dp))
                        Text("MEM %.0fMB".format(p.memMb), color = GlassTokens.onGlassDim, fontSize = 11.sp)
                    }
                }
                if (cur.procs.isEmpty()) {
                    Text("暂未读到进程（采样中…）", color = GlassTokens.onGlassDim, fontSize = 12.sp)
                }
            }
        }
    }

    // ---- kill 确认（玻璃确认卡） ----
    pendingKill?.let { victim ->
        GlassConfirmDialog(
            title = "结束进程 ${victim.name}？",
            body = "pid ${victim.pid}。仅能结束本应用（含发行版内）的进程；" +
                "若正在运行安装/编译等任务，强制结束可能丢失进度。",
            confirmText = "结束",
            onConfirm = {
                // 权限边界：Process.killProcess 底层 kill(pid, SIGKILL)，内核只允许
                // 对同 uid（或被 zygote 托管）进程发送信号——这里必然同 uid。
                // 备选方案（无 android.os 依赖时）：Runtime.exec("kill -9 $pid")，
                // 由 mksh 同 uid 执行，权限边界相同。
                runCatching { android.os.Process.killProcess(victim.pid) }
                pendingKill = null
            },
            onDismiss = { pendingKill = null },
        )
    }
}

/**
 * 玻璃仪表卡：标题 + 当前值 + 迷你曲线（Canvas，最近 60 点折线）。
 * @param history 0-100 的历史点（调用方已归一化）。
 */
@Composable
private fun GaugeCard(
    title: String,
    valueText: String,
    subText: String,
    history: List<Float>,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, color = GlassTokens.onGlass, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = GlassTokens.onGlass, fontSize = 16.sp)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.18f))
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(10.dp)),
        ) {
            if (history.size < 2) return@Canvas
            val stepX = size.width / 59f
            val path = Path()
            history.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height * (1f - (v / 100f).coerceIn(0f, 1f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path,
                color = Color(0xFF7CF9E5).copy(alpha = 0.9f),
                style = Stroke(width = 3f),
            )
            // 曲线终点小圆点（当前值位置）
            val lastV = history.last().coerceIn(0f, 100f) / 100f
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(
                    (history.size - 1) * stepX,
                    size.height * (1f - lastV),
                ),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(subText, color = GlassTokens.onGlassDim, fontSize = 11.sp)
    }
}

/**
 * plugin-distro 的资源监控注册契约（照 FloatGlassPluginContract 模式）。
 */
object ResourceMonitorPluginContract : PluginContract {
    override val id: String = "distro.monitor"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            ResourceMonitorScreen(onBack = { open = false })
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("资源监控", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                Text(
                    "CPU / 内存 / 磁盘实时曲线与进程列表，一键结束失控进程，零命令行。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("打开监控面板", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

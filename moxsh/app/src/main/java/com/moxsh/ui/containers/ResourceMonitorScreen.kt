package com.moxsh.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlin.random.Random

private data class Proc(val pid: Int, val name: String, val cpu: Int, val memMb: Int)

/**
 * 资源监控（针对小白 · 图形化容器管理四件套之一，与官网在线体验原型一致）。
 *  - CPU / 内存 / 磁盘 三仪表 + 迷你 sparkline
 *  - 进程列表（一键结束）
 *
 * 真实数据来自 /proc/stat、MemAvailable、StatFs 与 hidepid 可见进程；
 * 此处用演示数据保持视觉对齐（sparkline 用伪随机游走）。
 */
@Composable
fun ResourceMonitorScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var cpu by remember { mutableStateOf(23) }
    var mem by remember { mutableStateOf(42) }
    var disk by remember { mutableStateOf(61) }
    val cpuHist = remember { mutableStateListOf(*List(15) { Random.nextInt(15, 32) }.toTypedArray()) }
    val memHist = remember { mutableStateListOf(*List(15) { Random.nextInt(38, 48) }.toTypedArray()) }
    val diskHist = remember { mutableStateListOf(*List(15) { Random.nextInt(59, 63) }.toTypedArray()) }
    val procs = remember {
        mutableStateListOf(
            Proc(1024, "proot", 12, 84),
            Proc(1188, "apt", 4, 32),
            Proc(1310, "python3", 7, 56),
            Proc(1402, "nginx", 1, 6),
            Proc(1503, "node", 3, 48),
        )
    }

    // 轻量演示刷新
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1500)
            cpu = (cpu + Random.nextInt(-4, 5)).coerceIn(5, 60)
            mem = (mem + Random.nextInt(-2, 3)).coerceIn(30, 70)
            cpuHist.add(cpu); if (cpuHist.size > 15) cpuHist.removeAt(0)
            memHist.add(mem); if (memHist.size > 15) memHist.removeAt(0)
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = GlassTokens.onGlass, fontSize = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.width(10.dp))
                Text("资源监控", color = GlassTokens.onGlass, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gauge("CPU", "$cpu%", cpuHist, Modifier.weight(1f))
            Gauge("内存", "$mem%", memHist, Modifier.weight(1f))
            Gauge("磁盘", "$disk%", diskHist, Modifier.weight(1f))
        }

        Text("进程（本应用可见）", color = GlassTokens.onGlass, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Android 11+ hidepid：仅同 uid 进程可见（发行版内 proot/apt 都在列）。", color = GlassTokens.onGlassDim, fontSize = 10.5.sp)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(procs, key = { it.pid }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(GlassTokens.surfaceTint)
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(13.dp))
                        .padding(11.dp, 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.name, color = GlassTokens.onGlass, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(72.dp))
                    Text("pid ${p.pid}", color = GlassTokens.onGlassDim, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                    Text("CPU ${p.cpu}%", color = GlassTokens.onGlassDim, fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Text("MEM ${p.memMb}MB", color = GlassTokens.onGlassDim, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text("结束", color = Color(0xFFFF6A5E), fontSize = 11.5.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFFFF453A).copy(alpha = 0.12f))
                            .clickable { procs.remove(p) }
                            .padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
    }
}

@Composable
private fun Gauge(label: String, value: String, hist: List<Int>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(16.dp))
            .padding(13.dp, 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = GlassTokens.onGlass, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(value, color = Color(0xFF7CF9E5), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val max = (hist.maxOrNull() ?: 1).coerceAtLeast(1)
            hist.takeLast(12).forEach { v ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction = (v.toFloat() / max).coerceIn(0.1f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF7CF9E5).copy(alpha = 0.8f)),
                )
            }
        }
    }
}

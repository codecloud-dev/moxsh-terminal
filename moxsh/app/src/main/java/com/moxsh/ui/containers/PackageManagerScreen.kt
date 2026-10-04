package com.moxsh.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.widgets.GlassButton
import com.moxsh.ui.widgets.GlassChip

private data class Pkg(val name: String, val ver: String, val desc: String, val sizeMb: Int, val cat: Int, var installed: Boolean)
private data class QItem(val name: String, val op: String, var pct: Int = 0, var done: Boolean = false)

private val PKG_CATS = listOf("全部", "开发", "网络", "媒体", "工具")

/**
 * 图形包管理器（针对小白 · 图形化容器管理四件套之一，与官网在线体验原型一致）。
 *  - moxsh 源 / apt 源 切换
 *  - 关键词搜索 + 分类筛选
 *  - 安装 / 更新 / 卸载，安装进入底部队列并显示进度
 *
 * 真实安装走 ProotManager/apt；此处用演示数据保持 UI 闭环与视觉对齐。
 */
@Composable
fun PackageManagerScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var src by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf(0) }
    var kw by remember { mutableStateOf("") }
    val pkgs = remember {
        mutableStateListOf(
            Pkg("python", "3.12.3", "Python 解释器与标准库", 38, 0, true),
            Pkg("nodejs", "20.12.2", "Node.js 运行时与 npm", 42, 0, false),
            Pkg("nginx", "1.24.0", "高性能 Web 服务器", 6, 1, false),
            Pkg("ffmpeg", "6.1.1", "音视频转码与流处理", 96, 2, false),
            Pkg("vim", "9.1", "模态文本编辑器", 9, 3, true),
            Pkg("git", "2.43.0", "分布式版本控制", 15, 3, false),
            Pkg("clang", "18.1.3", "C/C++ 编译器（LLVM）", 210, 0, false),
            Pkg("openssh", "9.6", "SSH 客户端 / 服务端", 12, 1, true),
        )
    }
    val queue = remember { mutableStateListOf<QItem>() }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        // 顶栏
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("←", color = GlassTokens.onGlass, fontSize = 18.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.width(10.dp))
                Text("包管理器", color = GlassTokens.onGlass, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 源切换
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassChip(if (src == 0) "moxsh 源" else "moxsh 源", src == 0) { src = 0 }
            GlassChip("apt 源（发行版内）", src == 1) { src = 1 }
        }

        // 搜索框
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            BasicTextField(
                value = kw,
                onValueChange = { kw = it },
                singleLine = true,
                textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Text(if (kw.isEmpty()) "搜索软件包…" else "", color = GlassTokens.onGlassDim, fontSize = 14.sp)
                    inner()
                },
            )
        }

        // 分类
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PKG_CATS.forEachIndexed { i, label ->
                GlassChip(label, cat == i) { cat = i }
            }
        }

        // 列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pkgs, key = { it.name }) { p ->
                val show = (cat == 0 || p.cat == cat - 1) &&
                    (kw.isBlank() || p.name.contains(kw, true) || p.desc.contains(kw, true))
                if (!show) return@items
                GlassSurface(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${p.name}  ${p.ver}", color = GlassTokens.onGlass, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                            Text("${p.desc} · 约 ${p.sizeMb} MB" + if (p.installed) " · 已安装" else "", color = GlassTokens.onGlassDim, fontSize = 11.5.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (p.installed) {
                            GlassButton("卸载", enabled = true) {
                                p.installed = false
                                queue.add(QItem(p.name, "卸载", 100, true))
                            }
                        } else {
                            GlassButton("安装", filled = true) {
                                queue.add(QItem(p.name, "安装"))
                                simulate(queue.size - 1, queue) { p.installed = true }
                            }
                        }
                    }
                }
            }
        }

        // 安装队列
        if (queue.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("安装队列", color = GlassTokens.onGlassDim, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                queue.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${q.op} ${q.name}" + if (q.done) " ✓" else if (q.pct > 0) " ${q.pct}%" else " · 排队中",
                            color = GlassTokens.onGlass, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                        Text("清除", color = Color(0xFFFF6A5E), fontSize = 11.5.sp,
                            modifier = Modifier.clickable { queue.remove(q) })
                    }
                }
            }
        }
    }
}

private fun simulate(idx: Int, queue: SnapshotStateList<QItem>, onDone: () -> Unit) {
    var p = 0
    // 纯 UI 演示进度；真实路径由 ProotManager/apt 实现替换。
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(object : Runnable {
        override fun run() {
            p += 18
            if (p >= 100) {
                queue[idx] = queue[idx].copy(pct = 100, done = true)
                onDone()
            } else {
                queue[idx] = queue[idx].copy(pct = p)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 220)
            }
        }
    }, 220)
}

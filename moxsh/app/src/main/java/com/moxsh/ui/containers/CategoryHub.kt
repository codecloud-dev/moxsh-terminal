package com.moxsh.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * 分类页（终端 / 分类 / 设置 三页之一，与官网在线体验原型一致）。
 *  - 小白专区：图形化容器管理四件套入口（发行版管理器 / 图形包管理器 / 资源监控 / 新手引导）
 *  - 更多分类网格（插件市场 / 终端主题 / 键盘与手势 / 字体 / 自动化 / 系统 / 外观 / 文件 / 关于）
 *
 * 子页（发行版管理器 / 包管理器 / 资源监控 / 向导）由调用方以导航栈接管，本页只负责 hub 入口。
 */
@Composable
fun CategoryHub(
    onOpenDistro: () -> Unit,
    onOpenPkgs: () -> Unit,
    onOpenMonitor: () -> Unit,
    onOpenWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("分类", color = GlassTokens.onGlass, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("什么都有", color = GlassTokens.onGlassDim, fontSize = 12.sp)
        }

        // ---- 小白专区 ----
        GlassSurface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("针对小白 · 图形化容器管理", color = GlassTokens.onGlass, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFF0A84FF))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                ) { Text("零命令行", color = Color.White, fontSize = 10.sp) }
            }
            Spacer(Modifier.height(11.dp))
            Text(
                "不用敲命令也能装 Linux、管容器、看资源。下面四块帮你从零上手，全程点一点就行。",
                color = GlassTokens.onGlassDim,
                fontSize = 11.5.sp,
            )
            Spacer(Modifier.height(11.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BCard("发行版管理器", "一键装 Ubuntu / Debian / Kali / Alpine") { onOpenDistro() }
                BCard("图形包管理器", "搜索 / 分类，一键装卸软件") { onOpenPkgs() }
                BCard("资源监控", "CPU / 内存 / 磁盘，一键杀进程") { onOpenMonitor() }
                BCard("新手引导", "4 步带你装好一切") { onOpenWizard() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "更多分类",
            color = GlassTokens.onGlassDim,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Spacer(Modifier.height(8.dp))

        val cats = listOf(
            "插件市场", "终端主题", "键盘与手势", "字体", "自动化", "系统", "外观", "文件", "关于",
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            userScrollEnabled = false,
        ) {
            items(cats) { c -> CatCard(c) }
        }
    }
}

@Composable
private fun BCard(title: String, desc: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF0A84FF)),
            contentAlignment = Alignment.Center,
        ) { Text("✦", color = Color.White, fontSize = 18.sp) }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, color = GlassTokens.onGlass, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = GlassTokens.onGlassDim, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun CatCard(name: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(GlassTokens.surfaceTint)
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(17.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTokens.surfaceTint)
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("▪", color = Color(0xFF7CF9E5), fontSize = 18.sp) }
        Spacer(Modifier.width(11.dp))
        Text(name, color = GlassTokens.onGlass, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

package com.moxsh

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moxsh.plugin.distro.DistroManagerScreen
import com.moxsh.shared.ExecutionEngine
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassBottomBar
import com.moxsh.ui.component.GlassFAB
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.component.GlassTopBar
import com.moxsh.ui.settings.GlassIconButton
import com.moxsh.ui.settings.MoxshPrefs
import com.moxsh.ui.settings.SettingsScreen
import com.moxsh.ui.terminal.TerminalScreen
import com.moxsh.ui.terminal.sendToSession
import com.moxsh.ui.theme.MoxshGlassTheme

/** 单个终端标签页：id 为 ExecutionEngine 会话句柄，title 仅用于显示。 */
private data class TermTab(val id: Long, val title: String)

/**
 * 主界面根：全应用液态玻璃外壳 + 真实终端。
 *
 * 结构：
 * ```
 * Box {
 *   GlassBackdrop(...)                 // 玻璃背景层（D8：按性能/设置决定是否实时模糊）
 *   Column {
 *     GlassTopBar(标题, perfMode 切换)
 *     会话标签栏（GlassSurface 卡片，可横向滚动）+ 设置入口
 *     TerminalScreen(weight 1)         // 真实终端渲染/输入
 *     GlassBottomBar(onKey = ...)      // ExtraKeys，真发 VT 序列
 *   }
 *   GlassFAB(新建会话)
 * }
 * ```
 *
 * 会话生命周期归 MainActivity 持有：createSession(80x24) 后由 TerminalScreen
 * 按真实布局 resize；泵由这里启动（frameTick 驱动重绘）。退出 Activity 不销毁
 * 会话——由 MoxshSessionService 前台保活兜底泵续命（后台输出不丢）。
 */
@Composable
fun MoxshRoot() {
    val context = LocalContext.current

    // ---- 持久化偏好（SharedPreferences，设置页与这里共用） ----
    var accent by remember { mutableIntStateOf(MoxshPrefs.accent(context)) }
    var fontSizeSp by remember { mutableFloatStateOf(MoxshPrefs.fontSize(context)) }
    var mirror by remember { mutableStateOf(MoxshPrefs.mirror(context)) }

    // perfMode=true 即性能模式（关实时模糊）。默认按 D8 设备性能自动：
    // API31+ 支持实时模糊则开（perfMode=false），低版本静态回退；用户在设置里选择后落盘覆盖。
    var perfMode by remember {
        val autoBlur = Build.VERSION.SDK_INT >= 31
        val userChoice = MoxshPrefs.realtimeBlur(context)
        mutableStateOf(userChoice?.let { !it } ?: !autoBlur)
    }

    // 状态变化异步写盘（apply()）
    LaunchedEffect(fontSizeSp) { MoxshPrefs.setFontSize(context, fontSizeSp) }
    LaunchedEffect(accent) { MoxshPrefs.setAccent(context, accent) }
    LaunchedEffect(perfMode) { MoxshPrefs.setRealtimeBlur(context, !perfMode) }
    LaunchedEffect(mirror) { MoxshPrefs.setMirror(context, mirror) }

    MoxshGlassTheme(accent = Color(accent)) {
        var showSettings by remember { mutableStateOf(false) }
        // 管理器入口（D13）：控制是否整屏切到发行版管理器。
        // 简单状态切换即可；页面多了可换 Navigation-Compose（导航图见 architecture.md §6）。
        var showManager by remember { mutableStateOf(false) }
        // 插件面板入口（D5 体系②）：整屏渲染已注册原生玻璃插件卡片。
        var showPlugins by remember { mutableStateOf(false) }

        // ---- 多会话标签 ----
        var tabs by remember { mutableStateOf(listOf<TermTab>()) }
        var activeId by remember { mutableLongStateOf(-1L) }
        // 泵重绘信号：泵循环每读到 PTY 输出即自增，驱动 TerminalScreen 的 Canvas 失效
        var frameTick by remember { mutableLongStateOf(0L) }
        // ExtraKeys 修饰态（Ctrl/Alt），作用于下一个输入字符后由终端回调复位
        var ctrlActive by remember { mutableStateOf(false) }
        var altActive by remember { mutableStateOf(false) }

        /** 新建会话：先按 80x24 打开 PTY，TerminalScreen 布局完成后按真实网格 resize。 */
        fun newSession() {
            val id = ExecutionEngine.createSession(cols = 80, rows = 24)
            if (id <= 0L) return
            tabs = tabs + TermTab(id, "shell $id")
            activeId = id
            // 登记 UI 持泵集合，避免 Service 兜底泵抢占（startPump 是"先停旧泵"语义）
            MoxshSessionService.uiOwnedSessions.add(id)
            ExecutionEngine.startPump(id) { frameTick++ }
        }

        /** 关闭会话：停泵 + 销毁 PTY，并切到相邻标签。 */
        fun closeSession(id: Long) {
            ExecutionEngine.stopPump(id)
            ExecutionEngine.destroySession(id)
            MoxshSessionService.uiOwnedSessions.remove(id)
            val idx = tabs.indexOfFirst { it.id == id }
            val next = tabs.filterNot { it.id == id }
            tabs = next
            activeId = when {
                next.isEmpty() -> -1L
                next.any { it.id == activeId } -> activeId
                else -> next[(idx - 1).coerceAtLeast(0).coerceAtMost(next.size - 1)].id
            }
        }

        // 首次进入：自动开一个 shell
        LaunchedEffect(Unit) { if (tabs.isEmpty()) newSession() }

        if (showSettings) {
            SettingsScreen(
                perfMode = perfMode,
                onPerfModeChange = { perfMode = it },
                fontSizeSp = fontSizeSp,
                onFontSizeChange = { fontSizeSp = it },
                accent = accent,
                onAccentChange = { accent = it },
                mirror = mirror,
                onMirrorChange = { mirror = it },
                onBack = { showSettings = false },
            )
        } else if (showManager) {
            // ---- 发行版管理器（D13 四件套入口；插件契约注册见 DistroPluginRegistrar）----
            DistroManagerScreen(
                onBack = { showManager = false },
                onOpenTerminal = { cmd ->
                    // "启动发行版"：拿 loginCommand 生成的 proot 命令行开新会话并切过去，
                    // 用户视角即"切回终端 tab"（标签自动新增 distro N 并激活）。
                    val id = ExecutionEngine.createSession(command = cmd, cols = 80, rows = 24)
                    if (id > 0L) {
                        tabs = tabs + TermTab(id, "distro $id")
                        activeId = id
                        MoxshSessionService.uiOwnedSessions.add(id)
                        ExecutionEngine.startPump(id) { frameTick++ }
                        showManager = false
                    }
                },
                onOpenPackageManager = null, // 四件套互相跳转走插件面板；此处留空即不显示入口
            )
        } else if (showPlugins) {
            // ---- 插件面板（D5 体系②）：内置玻璃插件卡片入口 ----
            PluginPanelScreen(onBack = { showPlugins = false })
        } else {
            // imePadding：输入法弹起自动避让（架构 §6"玻璃层不阻挡触摸与 IME"）
            Box(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            ) {
                GlassBackdrop(performantBlur = !perfMode, modifier = Modifier.fillMaxSize())

                Column(Modifier.fillMaxSize()) {
                    GlassTopBar(
                        title = "moxsh",
                        perfMode = perfMode,
                        onTogglePerf = { perfMode = !perfMode },
                    )

                    // ---- 会话标签栏（GlassSurface 卡片）+ 设置入口 ----
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            tabs.forEach { tab ->
                                val active = tab.id == activeId
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (active) Color.White.copy(alpha = 0.20f)
                                            else GlassTokens.surfaceTint
                                        )
                                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(14.dp))
                                        .clickable { activeId = tab.id }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        tab.title,
                                        color = if (active) Color.White else GlassTokens.onGlassDim,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "×",
                                        color = GlassTokens.onGlassDim,
                                        modifier = Modifier.clickable { closeSession(tab.id) },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        // 插件面板入口（D5 体系②）：玻璃小按钮 → 已注册插件卡片
                        GlassIconButton("✦") { showPlugins = true }
                        Spacer(Modifier.width(6.dp))
                        // 管理器入口（D13）：玻璃小按钮 → 发行版管理器
                        GlassIconButton("☰") { showManager = true }
                        Spacer(Modifier.width(6.dp))
                        // 设置入口（玻璃小按钮）
                        GlassIconButton("⚙") { showSettings = true }
                    }

                    // ---- 终端区 ----
                    if (activeId > 0L) {
                        // key(activeId)：切换标签时按会话隔离滚动/输入框等 remember 状态
                        key(activeId) {
                            TerminalScreen(
                                sessionId = activeId,
                                tick = frameTick,
                                fontSizeSp = fontSizeSp,
                                onFontSizeChange = { fontSizeSp = it },
                                ctrlActive = ctrlActive,
                                altActive = altActive,
                                onModifiersConsumed = { usedCtrl, usedAlt ->
                                    if (usedCtrl) ctrlActive = false
                                    if (usedAlt) altActive = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(10.dp),
                            )
                        }
                    } else {
                        GlassSurface(
                            Modifier
                                .weight(1f)
                                .padding(10.dp)
                                .fillMaxWidth(),
                            tint = GlassTokens.termTint,
                        ) {
                            Text(
                                "没有活动会话，点击右下角 + 新建",
                                color = GlassTokens.onGlassDim,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    // ---- ExtraKeys：真发 VT 序列到当前会话 ----
                    GlassBottomBar(
                        ctrlActive = ctrlActive,
                        altActive = altActive,
                        onToggleCtrl = { ctrlActive = !ctrlActive },
                        onToggleAlt = { altActive = !altActive },
                        onKey = { seq -> sendToSession(activeId, seq) },
                    )
                }

                // 新建会话
                GlassFAB(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) { newSession() }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshRoot()
        }
    }
}

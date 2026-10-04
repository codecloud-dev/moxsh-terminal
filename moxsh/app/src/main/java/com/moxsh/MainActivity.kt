package com.moxsh

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key as composableKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.auth.LoginActivity
import com.moxsh.plugin.distro.DistroManagerScreen
import com.moxsh.shared.BootstrapState
import com.moxsh.shared.ExecutionEngine
import com.moxsh.ui.ai.AiPanel
import com.moxsh.ui.component.GlassBackdrop
import com.moxsh.ui.component.GlassBottomBar
import com.moxsh.ui.component.GlassFAB
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.control.ControlCenter
import com.moxsh.ui.containers.CategoryHub
import com.moxsh.ui.containers.PackageManagerScreen
import com.moxsh.ui.containers.ResourceMonitorScreen
import com.moxsh.ui.nav.MoxshBottomNav
import com.moxsh.ui.nav.MoxshPage
import com.moxsh.ui.onboarding.OnboardingWizard
import com.moxsh.ui.session.MoxSession
import com.moxsh.ui.session.SessionDrawer
import com.moxsh.ui.session.SessionEnv
import com.moxsh.ui.settings.GlassIconButton
import com.moxsh.ui.settings.MoxshPrefs
import com.moxsh.ui.settings.SettingsScreen
import com.moxsh.ui.terminal.TerminalScreen
import com.moxsh.ui.terminal.sendToSession
import com.moxsh.ui.theme.MoxshGlassTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主界面根：全应用液态玻璃外壳 + 真实终端 + 三页导航 + 会话抽屉 + 控制中心 + AI 面板。
 *
 * 结构（与官网在线体验原型 1:1 对齐）：
 * ```
 * Box {
 *   GlassBackdrop(...)                       // 玻璃背景层（按性能/设置决定是否实时模糊）
 *   when(page) {
 *     TERMINAL -> 终端页（顶栏☰抽屉 / 终端 / ExtraKeys / ＋）
 *     CATALOG  -> 分类页（小白专区四件套 + 更多分类 -> 发行版/包管理/资源监控/向导）
 *     SETTINGS -> 设置页
 *   }
 *   会话抽屉（☰）/ 控制中心（V·音量键）/ AI 面板（U）/ 新手引导  —— 覆盖层
 *   MoxshBottomNav(终端 / 分类 / 设置)        // 设置带 AI 角标
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

    var perfMode by remember {
        val autoBlur = Build.VERSION.SDK_INT >= 31
        val userChoice = MoxshPrefs.realtimeBlur(context)
        mutableStateOf(userChoice?.let { !it } ?: !autoBlur)
    }
    var darkTheme by remember { mutableStateOf(true) }

    LaunchedEffect(fontSizeSp) { MoxshPrefs.setFontSize(context, fontSizeSp) }
    LaunchedEffect(accent) { MoxshPrefs.setAccent(context, accent) }
    LaunchedEffect(perfMode) { MoxshPrefs.setRealtimeBlur(context, !perfMode) }
    LaunchedEffect(mirror) { MoxshPrefs.setMirror(context, mirror) }

    MoxshGlassTheme(accent = Color(accent), darkTheme = darkTheme) {
        // ---- 导航与覆盖层状态 ----
        var page by remember { mutableStateOf(MoxshPage.TERMINAL) }
        var showDrawer by remember { mutableStateOf(false) }
        var showControl by remember { mutableStateOf(false) }
        var showAi by remember { mutableStateOf(false) }
        var showWizard by remember { mutableStateOf(false) }
        var aiBadge by remember { mutableStateOf(true) }
        // 分类页子导航栈：hub | distro | pkgs | monitor
        var catalogTop by remember { mutableStateOf("hub") }
        // 插件面板覆盖层开关（终端页顶栏 ✦ 打开，未归入三页导航）
        var showPluginsTemp by remember { mutableStateOf(false) }

        // ---- 多会话（富模型：名称 / 环境 / cwd / 退出码） ----
        var sessions by remember { mutableStateOf(listOf<MoxSession>()) }
        var activeId by remember { mutableLongStateOf(-1L) }
        var frameTick by remember { mutableLongStateOf(0L) }
        var ctrlActive by remember { mutableStateOf(false) }
        var altActive by remember { mutableStateOf(false) }

        /** 新建会话：先按 80x24 打开 PTY，TerminalScreen 布局完成后按真实网格 resize。 */
        fun newSession(
            name: String = "本地终端",
            env: SessionEnv = SessionEnv.NORMAL,
            cwd: String = "~",
            cmd: String? = null,
        ) {
            val id = if (cmd != null) {
                ExecutionEngine.createSession(command = cmd, cols = 80, rows = 24)
            } else {
                ExecutionEngine.createSession(cols = 80, rows = 24)
            }
            if (id <= 0L) return
            sessions = sessions + MoxSession(id, name, env, cwd)
            activeId = id
            MoxshSessionService.uiOwnedSessions.add(id)
            ExecutionEngine.startPump(id) { frameTick++ }
        }

        /** 关闭会话：停泵 + 销毁 PTY，并切到相邻会话。 */
        fun closeSession(id: Long) {
            ExecutionEngine.stopPump(id)
            ExecutionEngine.destroySession(id)
            MoxshSessionService.uiOwnedSessions.remove(id)
            val next = sessions.filterNot { it.id == id }
            sessions = next
            activeId = when {
                next.isEmpty() -> -1L
                next.any { it.id == activeId } -> activeId
                else -> next.first().id
            }
            ExecutionEngine.activeSessionId = activeId
        }

        fun switchSession(id: Long) {
            activeId = id
            ExecutionEngine.activeSessionId = id
        }

        fun renameSession(id: Long, name: String) {
            sessions = sessions.map { if (it.id == id) it.copy(name = name) else it }
        }

        // 首启自动开一个 shell；本地包导入
        val bootState by BootstrapState.state.collectAsState()
        val retryScope = rememberCoroutineScope()
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            retryScope.launch {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val tmp = File(context.cacheDir, "bootstrap.import.zip")
                        File(tmp.parentFile, tmp.name).parentFile?.mkdirs()
                        java.io.FileOutputStream(tmp).use { ins.copyTo(it) }
                        BootstrapState.importFromLocal(context, tmp.absolutePath)
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            BootstrapState.state.collect { s ->
                if (s is BootstrapState.State.Ready && sessions.isEmpty()) {
                    runCatching { newSession() }
                }
            }
        }

        val activeSession = sessions.firstOrNull { it.id == activeId }

        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .onKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown) {
                        when (ev.key) {
                            Key.V -> showControl = true
                            Key.U -> { showAi = true; aiBadge = false }
                            Key.One -> page = MoxshPage.TERMINAL
                            Key.Two -> page = MoxshPage.CATALOG
                            Key.Three -> page = MoxshPage.SETTINGS
                            Key.Escape -> { showControl = false; showAi = false; showDrawer = false }
                        }
                    }
                    false
                },
        ) {
            GlassBackdrop(performantBlur = !perfMode, modifier = Modifier.fillMaxSize())

            // ---- 三个主页面 ----
            when (page) {
                MoxshPage.TERMINAL -> TerminalPage(
                    sessions = sessions,
                    activeSession = activeSession,
                    frameTick = frameTick,
                    fontSizeSp = fontSizeSp,
                    onFontSizeChange = { fontSizeSp = it },
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    onModifiersConsumed = { uc, ua ->
                        if (uc) ctrlActive = false
                        if (ua) altActive = false
                    },
                    onToggleCtrl = { ctrlActive = !ctrlActive },
                    onToggleAlt = { altActive = !altActive },
                    onOpenDrawer = { showDrawer = true },
                    onOpenAi = { showAi = true; aiBadge = false },
                    onOpenControl = { showControl = true },
                    onOpenAccount = { context.startActivity(Intent(context, LoginActivity::class.java)) },
                    onOpenPlugins = { showPluginsTemp = true },
                    onNewSession = { newSession() },
                    bootState = bootState,
                    onRetry = {
                        retryScope.launch { BootstrapState.begin(context) }
                    },
                    onImport = { importLauncher.launch(arrayOf("application/zip")) },
                )
                MoxshPage.CATALOG -> when (catalogTop) {
                    "distro" -> DistroManagerScreen(
                        onBack = { catalogTop = "hub" },
                        onOpenTerminal = { cmd ->
                            newSession("distro", SessionEnv.CONTAINER, "/root", cmd)
                            catalogTop = "hub"
                            page = MoxshPage.TERMINAL
                        },
                        onOpenPackageManager = { catalogTop = "pkgs" },
                    )
                    "pkgs" -> PackageManagerScreen(onBack = { catalogTop = "hub" })
                    "monitor" -> ResourceMonitorScreen(onBack = { catalogTop = "hub" })
                    else -> CategoryHub(
                        onOpenDistro = { catalogTop = "distro" },
                        onOpenPkgs = { catalogTop = "pkgs" },
                        onOpenMonitor = { catalogTop = "monitor" },
                        onOpenWizard = { showWizard = true },
                    )
                }
                MoxshPage.SETTINGS -> SettingsScreen(
                    perfMode = perfMode,
                    onPerfModeChange = { perfMode = it },
                    fontSizeSp = fontSizeSp,
                    onFontSizeChange = { fontSizeSp = it },
                    accent = accent,
                    onAccentChange = { accent = it },
                    mirror = mirror,
                    onMirrorChange = { mirror = it },
                    onBack = { page = MoxshPage.TERMINAL },
                )
            }

            // ---- 插件面板（独立覆盖层，未归入三页） ----
            if (showPluginsTemp) {
                // 直接复用既有插件面板入口（与旧实现一致）
                PluginPanelHolder(onClose = { showPluginsTemp = false })
            }

            // ---- 覆盖层：会话抽屉 / 控制中心 / AI 面板 / 新手引导 ----
            if (showDrawer) {
                SessionDrawer(
                    sessions = sessions,
                    activeId = activeId,
                    onSwitch = { switchSession(it) },
                    onKill = { closeSession(it) },
                    onRename = { id, name -> renameSession(id, name) },
                    onNew = { newSession() },
                    onDismiss = { showDrawer = false },
                )
            }
            if (showControl) {
                ControlCenter(onToggleTheme = { darkTheme = !darkTheme }, onDismiss = { showControl = false })
            }
            if (showAi) {
                AiPanel(onDismiss = { showAi = false })
            }
            if (showWizard) {
                OnboardingWizard(onFinish = { showWizard = false })
            }

            // ---- 底部三页导航 ----
            MoxshBottomNav(
                current = page,
                onSelect = { page = it },
                showAiBadge = aiBadge,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * 终端页（三页之一）：顶栏（☰ 抽屉 / 标题 / 账号 / 插件 / AI / 控制中心）+ 真实终端 + ExtraKeys + ＋。
 * 无活动会话时显示 bootstrap 安装进度卡（失败可重试 / 本地包导入）。
 */
@Composable
private fun TerminalPage(
    sessions: List<MoxSession>,
    activeSession: MoxSession?,
    frameTick: Long,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onModifiersConsumed: (Boolean, Boolean) -> Unit,
    onToggleCtrl: () -> Unit = {},
    onToggleAlt: () -> Unit = {},
    onOpenDrawer: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenControl: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenPlugins: () -> Unit,
    onNewSession: () -> Unit,
    bootState: BootstrapState.State,
    onRetry: () -> Unit,
    onImport: () -> Unit,
) {
    var retrying by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = 84.dp),
        ) {
        // ---- 顶栏 ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton("☰") { onOpenDrawer() }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (activeSession != null) "mox · ${activeSession.name}" else "moxsh",
                    color = GlassTokens.onGlass,
                    fontSize = 16.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                if (sessions.isNotEmpty()) {
                    Text("${sessions.size} 个会话", color = GlassTokens.onGlassDim, fontSize = 11.sp)
                }
            }
            GlassIconButton("👤") { onOpenAccount() }
            Spacer(Modifier.width(6.dp))
            GlassIconButton("✦") { onOpenPlugins() }
            Spacer(Modifier.width(6.dp))
            GlassIconButton("💬") { onOpenAi() }
            Spacer(Modifier.width(6.dp))
            GlassIconButton("☀") { onOpenControl() }
        }

        // ---- 终端区 ----
        if (activeSession != null) {
            composableKey(activeSession.id) {
                TerminalScreen(
                    sessionId = activeSession.id,
                    tick = frameTick,
                    fontSizeSp = fontSizeSp,
                    onFontSizeChange = onFontSizeChange,
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    onModifiersConsumed = onModifiersConsumed,
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
                when (val s = bootState) {
                    is BootstrapState.State.Running -> Column(
                        Modifier.fillMaxWidth().wrapContentSize(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("正在准备运行环境", color = GlassTokens.onGlass)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { s.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text(s.message, color = GlassTokens.onGlassDim)
                    }
                    is BootstrapState.State.Failed -> Column(
                        Modifier.fillMaxWidth().wrapContentSize(Alignment.Center).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("运行环境安装失败", color = GlassTokens.onGlass)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, color = GlassTokens.onGlassDim)
                        Spacer(Modifier.height(14.dp))
                        Button(enabled = !retrying, onClick = { retrying = true; onRetry(); retrying = false }) {
                            Text(if (retrying) "重试中…" else "重试")
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onImport) { Text("从本地包导入") }
                    }
                    else -> Text("没有活动会话，点击右下角 + 新建", color = GlassTokens.onGlassDim, modifier = Modifier.padding(16.dp))
                }
            }
        }

        // ---- ExtraKeys ----
        GlassBottomBar(
            ctrlActive = ctrlActive,
            altActive = altActive,
            onToggleCtrl = onToggleCtrl,
            onToggleAlt = onToggleAlt,
            onKey = { seq -> activeSession?.let { sendToSession(it.id, seq) } },
        )
        }
        // ---- 新建会话（Box 直接子节点，align 在 BoxScope 内生效）----
        GlassFAB(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 96.dp),
        ) { onNewSession() }
    }
}

/**
 * 插件面板占位：复用既有 PluginPanelScreen（与旧实现一致）。
 * 独立抽取便于终端页以回调方式打开，不污染三页导航。
 */
@Composable
private fun PluginPanelHolder(onClose: () -> Unit) {
    PluginPanelScreen(onBack = onClose)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshRoot()
        }
    }
}

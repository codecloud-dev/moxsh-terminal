package com.moxsh.plugin.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.plugin.core.PluginHost
import com.moxsh.plugin.core.PluginContract
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import com.moxsh.ui.theme.MoxshGlassTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AI 对话玻璃侧边栏（D18 对话 UI）+ 插件注册契约。
 *
 * 界面结构（自上而下）：
 *  1. 顶栏：模型选择器（预置服务商 chips + 自定义 baseUrl/Key/模型 玻璃表单）；
 *  2. 消息流：用户右对齐玻璃气泡 / AI 左对齐；工具调用渲染为小玻璃卡
 *     （动作名 + 状态：待确认/执行中/完成/失败）；AI 流式回答带打字光标；
 *  3. 技能 chips 行：内置/商店技能开关；
 *  4. 底部：玻璃输入框 + 发送按钮（GlassFAB 风格）；
 *  5. 高危操作弹 **GlassConfirmCard**（玻璃确认卡）：
 *     小白看到"AI 想删除发行版 Ubuntu，确认？"而不是命令行。
 *
 * 侧边栏可收起：右滑出/收起（slideInHorizontally + fade 动画）。
 * AgentExecutor 会话由 [AiService] 前台服务持有，杀后台不断流。
 */

// ---------------------------------------------------------------------------
// 入口 Activity（manifest 已声明）
// ---------------------------------------------------------------------------

/** AI 对话独立入口（从插件面板/桌面进入；主 app 常规路径是玻璃侧边栏）。 */
class AiChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity-compose 的 setContent（已 import）；宿主统一玻璃主题。
        setContent { MoxshGlassTheme { AiChatScreen(onCollapse = { finish() }) } }
    }
}

// ---------------------------------------------------------------------------
// 玻璃侧边栏主体
// ---------------------------------------------------------------------------

/**
 * AI 对话玻璃侧边栏。
 *
 * @param onCollapse 收起回调（宿主把侧边栏滑出屏幕）。
 * @param perfBlur 是否开实时模糊（性能模式下关，照 Glass.kt 的 D8 策略；
 *                 侧边栏底色为半透明压暗层，宿主已有 GlassBackdrop 时不再叠加模糊）。
 * @param sessionIdProvider 当前终端会话 id（run_command 落点）。
 */
@Composable
fun AiChatScreen(
    onCollapse: () -> Unit,
    perfBlur: Boolean = true,
    sessionIdProvider: () -> Long? = { null },
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 配置（持久化在 AiConfigStore，apiKey 加密落盘）----
    var config by remember { mutableStateOf(AiConfigStore.load(ctx)) }
    var showSettings by remember { mutableStateOf(false) }

    // ---- 技能开关（内置 + 商店扫描）----
    val skills = remember { mutableStateListOf<Skill>() }
    LaunchedEffect(Unit) { skills.addAll(SkillRegistry.scanInstalled(ctx)) }

    // ---- Agent 会话的 UI 侧桥接状态 ----
    // 声明顺序必须在 executor 之前：executor 的回调闭包会捕获这些 delegate。
    // pendingConfirm：高危确认卡挂起门（AgentExecutor.confirm 与 GlassConfirmCard 之间）。
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }
    // progressText：长任务实时进度（install_distro 等）。
    var progressText by remember { mutableStateOf("") }
    // streamingText：流式回答缓冲（onDelta 增量拼接，气泡打字机）。
    var streamingText by remember { mutableStateOf("") }

    // ---- Agent 会话（前台服务存在时复用其执行器；否则本地新建）----
    val executor = remember {
        AiService.sharedExecutor ?: AgentExecutor(
            appContext = ctx.applicationContext,
            configProvider = { config },
            sessionIdProvider = sessionIdProvider,
            // 高危确认：挂起等待 UI 的玻璃确认卡（见 pendingConfirm 状态机）。
            confirm = { title, body ->
                val gate = CompletableDeferred<Boolean>()
                pendingConfirm = PendingConfirm(title, body, gate)
                gate.await()
            },
            onProgress = { progressText = it },
            onDelta = { streamingText = streamingText + it },
        )
    }

    // ---- 消息流渲染数据（从执行器历史投影成 UI 条目）----
    val bubbles = remember { mutableStateListOf<UiBubble>() }
    LaunchedEffect(Unit) { executor.history.forEach { bubbles.add(it.toBubble()) } }

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    // 侧边栏可见态：点"收起"先播滑出动画，动画结束后再回调 onCollapse 通知宿主。
    var sidebarVisible by remember { mutableStateOf(true) }
    fun collapseAnimated() { sidebarVisible = false }
    LaunchedEffect(sidebarVisible) {
        if (!sidebarVisible) {
            delay(320) // 与 exit 动画（slide+fade，默认 300ms tween）对齐
            onCollapse()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 侧边栏容器：右滑出/收起（slideInHorizontally 从右缘滑入 + fade）。
        AnimatedVisibility(
            visible = sidebarVisible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)) // 侧边栏底层压暗，与玻璃面形成层次
                    .padding(12.dp),
            ) {
                AiTopBar(
                    config = config,
                    busy = busy,
                    onOpenSettings = { showSettings = !showSettings },
                    onCollapse = ::collapseAnimated,
                    onNewChat = {
                        executor.reset()
                        bubbles.clear()
                        streamingText = ""
                    },
                )

                // ---- 模型设置表单（玻璃，可展开）----
                AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut()) {
                    ModelSettingsForm(
                        config = config,
                        onChange = {
                            config = it
                            AiConfigStore.save(ctx, it)
                        },
                    )
                }

                // ---- 技能开关 chips ----
                SkillChipsRow(skills) { s, on ->
                    SkillRegistry.setEnabled(ctx, s.manifest.id, on)
                    val i = skills.indexOfFirst { it.manifest.id == s.manifest.id }
                    if (i >= 0) skills[i] = s.copy(enabled = on)
                }

                Spacer(Modifier.height(8.dp))

                // ---- 消息流 ----
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(bubbles) { b ->
                        when (b) {
                            is UiBubble.User -> UserBubble(b)
                            is UiBubble.Assistant -> AssistantBubble(b)
                            is UiBubble.Tool -> ToolCard(b)
                        }
                    }
                    // 流式打字中：AI 气泡 + 闪烁光标（infiniteTransition 呼吸 alpha）。
                    if (busy && streamingText.isNotEmpty()) {
                        item { AssistantBubble(UiBubble.Assistant(streamingText, typing = true)) }
                    }
                    if (progressText.isNotEmpty()) {
                        item { ProgressChip(progressText) }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // ---- 输入区 ----
                InputRow(
                    input = input,
                    enabled = !busy,
                    onInput = { input = it },
                    onSend = {
                        val text = input.text.trim()
                        if (text.isEmpty() || busy) return@InputRow
                        input = TextFieldValue("")
                        busy = true
                        streamingText = ""
                        bubbles += UiBubble.User(text)
                        scope.launch {
                            val enabledSkills = skills.filter { it.enabled }
                            val result = runCatching { executor.send(text, enabledSkills) }
                                .getOrElse { AgentTurnResult("出错了：${it.message}", emptyList()) }
                            streamingText = ""
                            progressText = ""
                            // 工具卡先上（按事件顺序），最后放 AI 最终回答气泡。
                            result.toolEvents.forEach { bubbles += it.toBubble() }
                            bubbles += UiBubble.Assistant(result.text)
                            busy = false
                        }
                    },
                )
            }
        }

        // ---- 玻璃确认卡（高危操作，全屏遮罩 + 居中玻璃卡）----
        pendingConfirm?.let { p ->
            GlassConfirmCard(
                title = p.title,
                body = p.body,
                onConfirm = {
                    pendingConfirm = null
                    p.gate.complete(true)
                },
                onDismiss = {
                    pendingConfirm = null
                    p.gate.complete(false)
                },
            )
        }
    }
}

/** 确认卡挂起状态（AgentExecutor.confirm 与 UI 之间的桥）。 */
private class PendingConfirm(
    val title: String,
    val body: String,
    val gate: CompletableDeferred<Boolean>,
)

// ---------------------------------------------------------------------------
// UI 数据投影（执行器历史 -> 消息流条目）
// ---------------------------------------------------------------------------

/** 消息流条目（密封类：用户气泡 / AI 气泡 / 工具卡）。 */
private sealed class UiBubble {
    /** 用户消息（右对齐玻璃气泡）。 */
    data class User(val text: String) : UiBubble()

    /**
     * AI 消息（左对齐玻璃气泡）。
     * @param typing 流式打字中（尾部渲染闪烁光标）。
     */
    data class Assistant(val text: String, val typing: Boolean = false) : UiBubble()

    /** 工具调用小玻璃卡（图标点 + 动作名 + 状态）。 */
    data class Tool(
        val displayName: String,
        val status: ToolCallStatus,
        val summary: String,
    ) : UiBubble()
}

private fun ChatMessage.toBubble(): UiBubble = when (role) {
    "user" -> UiBubble.User(content)
    else -> UiBubble.Assistant(content)
}

private fun ToolEvent.toBubble(): UiBubble = UiBubble.Tool(displayName, status, summary)

// ---------------------------------------------------------------------------
// 顶栏 + 模型设置表单
// ---------------------------------------------------------------------------

/** 顶栏：标题 + 收起/新会话/设置三个玻璃小按钮。 */
@Composable
private fun AiTopBar(
    config: AiConfig,
    busy: Boolean,
    onOpenSettings: () -> Unit,
    onCollapse: () -> Unit,
    onNewChat: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("moxsh AI", color = GlassTokens.onGlass, fontWeight = FontWeight.Bold)
                Text(
                    "${config.provider.displayName} · ${config.effectiveModel}",
                    color = GlassTokens.onGlassDim,
                    fontSize = 11.sp,
                )
            }
            Row {
                GlassMiniButton("新会话", enabled = !busy, onClick = onNewChat)
                Spacer(Modifier.width(6.dp))
                GlassMiniButton("设置", onClick = onOpenSettings)
                Spacer(Modifier.width(6.dp))
                GlassMiniButton("收起", onClick = onCollapse)
            }
        }
    }
}

/**
 * 模型选择器 + 自定义表单（玻璃）：
 * 服务商 chips 一行（点选即切默认 baseUrl/模型）+ 自定义 Base URL / API Key / 模型名。
 */
@Composable
private fun ModelSettingsForm(config: AiConfig, onChange: (AiConfig) -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("AI 服务商", color = GlassTokens.onGlass, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        // 预置服务商下拉（chips 行：当前选中高亮；横向可滚）。
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AiProvider.values().forEach { p ->
                val selected = p == config.provider
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(10.dp))
                        .clickable {
                            // 切服务商：非自定义项清空自定义 baseUrl/model，走预置默认值。
                            onChange(
                                if (p == AiProvider.OpenAICompatible) {
                                    config.copy(provider = p)
                                } else {
                                    config.copy(provider = p, baseUrl = "", model = "")
                                }
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(p.displayName, color = GlassTokens.onGlass, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = TextFieldValue(config.baseUrl),
            hint = "Base URL（留空用 ${config.provider.defaultBaseUrl.ifBlank { "服务商默认" }}）",
            onValueChange = { onChange(config.copy(baseUrl = it.text)) },
        )
        Spacer(Modifier.height(6.dp))
        GlassTextField(
            value = TextFieldValue(config.apiKey),
            hint = "API Key（加密保存在本机 AndroidKeyStore 内）",
            onValueChange = { onChange(config.copy(apiKey = it.text)) },
        )
        Spacer(Modifier.height(6.dp))
        GlassTextField(
            value = TextFieldValue(config.model),
            hint = "模型名（留空用 ${config.provider.defaultModel.ifBlank { "自定义" }}）",
            onValueChange = { onChange(config.copy(model = it.text)) },
        )
    }
}

/** 横向可滚的 chips 行（服务商/技能较多时不溢出），由调用处直接用
 *  horizontalScroll(rememberScrollState())；保留此注释说明设计意图。 */

// ---------------------------------------------------------------------------
// 技能 chips
// ---------------------------------------------------------------------------

/** 技能开关行：内置 + 商店技能各一枚 chip，点击切换启用（持久化）。 */
@Composable
private fun SkillChipsRow(skills: List<Skill>, onToggle: (Skill, Boolean) -> Unit) {
    if (skills.isEmpty()) return
    GlassSurface(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("技能", color = GlassTokens.onGlass, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            skills.forEach { s ->
                val on = s.enabled
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.08f))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(10.dp))
                        .clickable { onToggle(s, !on) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        s.manifest.name + if (on) " ·开" else " ·关",
                        color = GlassTokens.onGlass,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 消息气泡 / 工具卡 / 进度
// ---------------------------------------------------------------------------

/** 用户气泡：右对齐玻璃胶囊。 */
@Composable
private fun UserBubble(b: UiBubble.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .bubbleWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.22f))
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Text(b.text, color = GlassTokens.onGlass, fontSize = 14.sp)
        }
    }
}

/**
 * AI 气泡：左对齐玻璃胶囊；[typing] 时尾部渲染流式打字光标——
 * "▍" 用 infiniteTransition 做 0.2-1.0 呼吸透明度（纯 Compose，无需额外帧回调）。
 */
@Composable
private fun AssistantBubble(b: UiBubble.Assistant) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            Modifier
                .bubbleWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlassTokens.surfaceTint)
                .border(1.dp, GlassTokens.stroke, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            if (b.typing) {
                // 流式打字中光标动画：光标块透明度呼吸（0.2f-1.0f，500ms 往返）。
                val transition = rememberInfiniteTransition(label = "cursor")
                val alpha by transition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
                    label = "cursorAlpha",
                )
                Text(
                    b.text + "▍",
                    color = GlassTokens.onGlass.copy(alpha = alpha),
                    fontSize = 14.sp,
                )
            } else {
                Text(b.text, color = GlassTokens.onGlass, fontSize = 14.sp)
            }
        }
    }
}

/**
 * 工具调用小玻璃卡：状态色点 + 中文动作名 + 状态文案（结果摘要可展开，
 * 简化为一行摘要截断）。
 */
@Composable
private fun ToolCard(b: UiBubble.Tool) {
    val (dot, label) = when (b.status) {
        ToolCallStatus.PENDING_CONFIRM -> Color(0xFFFFC46B) to "待确认"
        ToolCallStatus.RUNNING -> Color(0xFF7CD4F9) to "执行中"
        ToolCallStatus.DONE -> Color(0xFF7CF9A6) to "完成"
        ToolCallStatus.FAILED -> Color(0xFFFF8FA6) to "失败"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 状态色点（小圆）。
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(b.displayName, color = GlassTokens.onGlass, fontSize = 13.sp)
            Text(
                b.summary.replace('\n', ' ').take(80),
                color = GlassTokens.onGlassDim,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Text(label, color = GlassTokens.onGlassDim, fontSize = 11.sp, textAlign = TextAlign.End)
    }
}

/** 长任务进度条（install_distro 等的实时进度文案）。 */
@Composable
private fun ProgressChip(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = GlassTokens.onGlassDim, fontSize = 12.sp)
    }
}

/**
 * 玻璃确认卡（高危操作）：全屏半透明遮罩 + 居中玻璃卡。
 * 小白看到的确认语是"AI 想要：安装发行版 / 确认执行吗？"，而不是命令行。
 * 点"取消"/遮罩 = 拒绝（结果回传 AI，由 AI 优雅收尾）。
 */
@Composable
private fun GlassConfirmCard(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clickable { }, // 吞掉卡片内点击，避免穿透遮罩
        ) {
            Text(title, color = GlassTokens.onGlass, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(body, color = GlassTokens.onGlassDim, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassMiniButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                GlassMiniButton("确认", filled = true, onClick = onConfirm)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 输入区
// ---------------------------------------------------------------------------

/** 输入行：玻璃输入框 + 发送按钮（GlassFAB 风格的圆形玻璃按钮）。 */
@Composable
private fun InputRow(
    input: TextFieldValue,
    enabled: Boolean,
    onInput: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .imePadding(),
        verticalAlignment = Alignment.Bottom,
    ) {
        GlassTextField(
            value = input,
            hint = "用大白话告诉我想做什么…",
            onValueChange = onInput,
            modifier = Modifier.weight(1f),
            singleLine = false,
        )
        Spacer(Modifier.width(8.dp))
        // 发送按钮：GlassFAB 风格（圆形玻璃，箭头字符居中）。
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (enabled) GlassTokens.surfaceTint else Color.White.copy(alpha = 0.05f))
                .border(1.dp, GlassTokens.stroke, CircleShape)
                .clickable(enabled = enabled, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Text("↑", color = GlassTokens.onGlass, fontSize = 18.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// 模块内玻璃小件（照 plugin-distro 的 internal 风格自持，不动 ui 模块）
// ---------------------------------------------------------------------------

/** 玻璃小按钮（顶栏/确认卡用；filled=true 为高亮主操作）。 */
@Composable
private fun GlassMiniButton(
    text: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    filled -> Color.White.copy(alpha = 0.26f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) GlassTokens.onGlass else GlassTokens.onGlassDim, fontSize = 12.sp)
    }
}

/** 玻璃输入框：BasicTextField 套玻璃底（避免 Material 填充风格破坏玻璃感）。 */
@Composable
private fun GlassTextField(
    value: TextFieldValue,
    hint: String,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 5,
            textStyle = TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.text.isEmpty()) {
                    Text(hint, color = GlassTokens.onGlassDim, fontSize = 14.sp)
                }
                inner()
            },
        )
    }
}

/** 气泡最大宽度（280dp，长文换行不糊边）。 */
private fun Modifier.bubbleWidth(): Modifier = this.then(Modifier.width(280.dp))

// ---------------------------------------------------------------------------
// 插件注册契约（照 plugin-distro 的 DistroManagerPluginContract 模式）
// ---------------------------------------------------------------------------

/**
 * plugin-ai 的注册契约：宿主玻璃环境里渲染入口卡片，点开即对话。
 * 主 app / 插件宿主一行装配：`AiPluginContract.register(host)`。
 */
object AiPluginContract : PluginContract {
    override val id: String = "ai.chat"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            // 插件宿主内直接铺满打开对话侧边栏（Back 由系统处理）。
            AiChatScreen(onCollapse = { open = false })
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("moxsh AI 助手", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                Text(
                    "用大白话指挥手机里的 Linux：装系统、装软件、换源、解释报错，" +
                        "全程图形化，高危操作有确认卡。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassMiniButton("开始对话", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

/** 从宿主进入对话（宿主可startService 保活会话；见 AiService.start）。 */
fun openAiChat(ctx: Context) {
    ctx.startActivity(Intent(ctx, AiChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

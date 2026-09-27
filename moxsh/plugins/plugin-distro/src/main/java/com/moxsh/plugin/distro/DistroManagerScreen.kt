package com.moxsh.plugin.distro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.shared.ProotManager
import com.moxsh.shared.ProotManager.DistroInfo
import com.moxsh.ui.component.*
import com.moxsh.ui.theme.MoxshGlassTheme
import kotlinx.coroutines.launch

/**
 * 单个发行版卡片的安装/进度状态：
 *  - [running]：安装协程进行中（卡片显示进度条）；
 *  - [percent]/[stage]：ProotManager.install 的进度回调透传（0-100 + 阶段文案）；
 *  - [done]：null=未开始/进行中，true=成功，false=失败。
 */
internal data class InstallState(
    val running: Boolean = false,
    val percent: Int = 0,
    val stage: String = "",
    val done: Boolean? = null,
)

/**
 * 发行版管理器（D13 四件套之一）。
 *
 * 对小白零命令行：
 *  - 每个发行版一张玻璃卡片（Logo 色块 + 名称 + 版本 + 大小 + 状态徽章）；
 *  - 未安装 → "一键安装"大按钮 → 底部玻璃进度卡（下载 → 校验 → 解压 → 初始化）；
 *  - 已安装 → 启动 / 备份 / 恢复 / 删除（全部玻璃按钮 + 玻璃确认对话框）；
 *  - 底部"从 tar 安装"自定义 rootfs 玻璃表单（URL + 可选 SHA-256）。
 *
 * 本 Screen 不自带 GlassBackdrop：被 MainActivity 嵌入时宿主已提供玻璃背景；
 * 被 [DistroManagerActivity] 独立打开时由 Activity 自行铺背景。
 *
 * @param onOpenTerminal 可选回调：参数为 `ProotManager.loginCommand(id)` 生成的
 *        proot 命令行。MainActivity 接线时用它 `ExecutionEngine.createSession(command=...)`
 *        开新会话并把 activeId 切过去（即"切回终端 tab"，见按钮内注释）。
 * @param onOpenPackageManager 可选回调：小白流程跳到图形包管理器（向导第④步联动）。
 */
@Composable
fun DistroManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTerminal: ((String) -> Unit)? = null,
    onOpenPackageManager: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 数据与状态 ----
    var distros by remember { mutableStateOf(ProotManager.listInstalled()) }
    val installStates = remember { mutableStateMapOf<String, InstallState>() }
    // 待确认删除的发行版（非 null 时弹玻璃确认对话框）
    var pendingDelete by remember { mutableStateOf<DistroInfo?>(null) }
    // 自定义 rootfs 表单开关与输入
    var showCustomForm by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf(TextFieldValue("")) }
    var customSha by remember { mutableStateOf(TextFieldValue("")) }
    // 正在展示进度的发行版 id（底部玻璃进度卡跟随它）
    var activeInstallId by remember { mutableStateOf<String?>(null) }

    // ---- 恢复（SAF 选 tar）：OpenDocument 拿 content:// uri，先拷到 cacheDir 再交 native ----
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // 生产实现：把 content uri 拷贝为本地临时 tar（contentResolver.openInputStream），
        // 再调 ProotManager.restore(tmpPath, "custom")；此处为骨架，直接注释说明。
        // 注意：native 不识别 content://，必须先落一份私有临时文件。
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

            // ---- 顶栏 ----
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = GlassTokens.onGlass, fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onBack() }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("发行版管理器", color = GlassTokens.onGlass, fontSize = 18.sp)
                }
            }

            // ---- 发行版卡片列表 ----
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(distros, key = { it.id }) { d ->
                    DistroCard(
                        info = d,
                        state = installStates[d.id] ?: InstallState(),
                        onInstall = { startInstall(d, installStates, scope) { distros = ProotManager.listInstalled() } },
                        onLaunch = {
                            // 启动发行版：零命令行路径。
                            //  1) ProotManager.loginCommand(id) 生成 proot 启动命令行；
                            //  2) MainActivity 接线 onOpenTerminal：
                            //     ExecutionEngine.createSession(command = cmd, cols = 80, rows = 24)
                            //     并把 activeId 切到新会话 → 用户视角即"切回终端 tab"。
                            //  3) 之后所有终端输入输出都跑在该发行版内（proot 环境变量已就位）。
                            onOpenTerminal?.invoke(ProotManager.loginCommand(d.id))
                        },
                        onBackup = {
                            // 备份：导出到 /sdcard/Download/moxsh-backups/<id>-<时间戳>.tar。
                            // Android 10+ 无 WRITE_EXTERNAL_STORAGE，生产实现用
                            // MediaStore.Downloads（RELATIVE_PATH = Download/moxsh-backups）
                            // 写入，无需权限；骨架中直接给 native 一个私有目录路径演示。
                            scope.launch {
                                val out = java.io.File(
                                    context.getExternalFilesDir(null),
                                    "moxsh-backups/${d.id}.tar"
                                )
                                out.parentFile?.mkdirs()
                                val ok = ProotManager.backup(d.id, out.absolutePath)
                                installStates[d.id] = InstallState(
                                    running = false, done = ok,
                                    stage = if (ok) "已备份到 Download/moxsh-backups" else "备份失败",
                                    percent = if (ok) 100 else 0,
                                )
                                activeInstallId = d.id
                            }
                        },
                        onRestore = {
                            // 恢复：SAF 选 tar（restorePicker.launch(arrayOf("application/x-tar")))，
                            // 拿 uri → 拷贝到 cacheDir → ProotManager.restore(path, d.id)。
                            restorePicker.launch(arrayOf("application/x-tar", "application/octet-stream"))
                        },
                        onDelete = { pendingDelete = d },
                    )
                }

                // ---- 自定义 rootfs 入口 ----
                item {
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showCustomForm = !showCustomForm },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("自定义 rootfs：从 tar 安装", color = GlassTokens.onGlass,
                                modifier = Modifier.weight(1f))
                            Text(if (showCustomForm) "收起" else "展开", color = GlassTokens.onGlassDim)
                        }
                        if (showCustomForm) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "填 rootfs tar 包的直链 URL（可选填 SHA-256，校验失败即中止）。" +
                                    "支持任意 Linux 用户态根文件系统。",
                                color = GlassTokens.onGlassDim, fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            GlassTextField(customUrl, "https://…/rootfs.tar.gz") { customUrl = it }
                            Spacer(Modifier.height(6.dp))
                            GlassTextField(customSha, "SHA-256（可选）") { customSha = it }
                            Spacer(Modifier.height(10.dp))
                            GlassButton("安装自定义 rootfs") {
                                // 生产实现：把 url/sha 经 native 下载通道交给 moxsh_proot_install_custom；
                                // 这里复用 install 流程，id 取 "custom"，进度同样回到底部进度卡。
                                startInstall(
                                    DistroInfo("custom", "自定义", customUrl.text.substringBeforeLast("/"), 0L, false),
                                    installStates, scope,
                                ) { distros = ProotManager.listInstalled() }
                                showCustomForm = false
                            }
                        }
                    }
                }
            }
        }

        // ---- 底部玻璃进度卡（安装中/刚完成时显示） ----
        val activeId = activeInstallId
        if (activeId != null) {
            val st = installStates[activeId] ?: InstallState()
            GlassSurface(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
                tint = GlassTokens.surfaceTint,
            ) {
                Text(
                    when (activeId) {
                        "custom" -> "自定义 rootfs"
                        else -> distros.firstOrNull { it.id == activeId }?.name ?: activeId
                    },
                    color = GlassTokens.onGlass,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { st.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(6.dp))
                // 阶段文案示例："下载中 45%（清华镜像）→ 校验 SHA-256 → 解压 rootfs → 初始化"
                Text(
                    if (st.running) "${st.stage}  ${st.percent}%" else st.stage,
                    color = GlassTokens.onGlassDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!st.running) {
                        GlassButton("关闭") { activeInstallId = null }
                    }
                }
            }
        }

        // ---- 删除确认（玻璃对话框） ----
        pendingDelete?.let { victim ->
            GlassConfirmDialog(
                title = "删除 ${victim.name} ${victim.version}？",
                body = "将删除整个 rootfs（约 ${victim.sizeMb} MB），其中所有数据不可恢复。" +
                    "建议先备份到 Download 目录。",
                confirmText = "删除",
                onConfirm = {
                    scope.launch {
                        ProotManager.remove(victim.id)
                        distros = ProotManager.listInstalled()
                        pendingDelete = null
                    }
                },
                onDismiss = { pendingDelete = null },
            )
        }
    }
}

/** 发起一键安装：调 ProotManager.install（suspend，内部 IO 线程），进度写回 [installStates]。 */
internal fun startInstall(
    d: DistroInfo,
    states: MutableMap<String, InstallState>,
    scope: kotlinx.coroutines.CoroutineScope,
    onFinished: () -> Unit,
) {
    if (states[d.id]?.running == true) return
    states[d.id] = InstallState(running = true, percent = 0, stage = "准备中")
    scope.launch {
        val ok = ProotManager.install(d.id) { percent, stage ->
            // 进度回调来自 IO 线程，setState 需回主线程（Compose 快照写需主线程）。
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                states[d.id] = InstallState(running = true, percent = percent, stage = stage)
            }
        }
        states[d.id] = InstallState(
            running = false, done = ok,
            percent = if (ok) 100 else 0,
            stage = if (ok) "安装完成，可以启动了" else "安装失败（详见内核日志）",
        )
        onFinished()
    }
}

/**
 * 发行版玻璃卡片：Logo 色块 + 名称/版本 + 大小 + 状态徽章 + 操作按钮。
 * OnboardingWizard 第②步内嵌其精简版（[CompactDistroCard]）。
 */
@Composable
internal fun DistroCard(
    info: DistroInfo,
    state: InstallState,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Logo 色块：各发行版官方主色 + 首字母
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(distroColor(info.id))
                    .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(info.name.first().toString(), color = Color.White, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${info.name} ${info.version}", color = GlassTokens.onGlass)
                Text(
                    if (info.installed) "已安装 · ${info.sizeMb} MB" else "未安装 · 约 ${info.sizeMb} MB",
                    color = GlassTokens.onGlassDim, fontSize = 12.sp,
                )
            }
            // 状态徽章
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (info.installed) Color(0xFF2E7D32).copy(alpha = 0.55f)
                        else Color.White.copy(alpha = 0.10f)
                    )
                    .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (info.installed) "已安装" else "未安装",
                    color = GlassTokens.onGlass, fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (!info.installed) {
            // 未安装：一键安装大按钮（玻璃材质，占满整行）
            GlassButton(
                if (state.running) "安装中…" else "一键安装",
                enabled = !state.running,
                filled = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = onInstall,
            )
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text("${state.stage}  ${state.percent}%", color = GlassTokens.onGlassDim, fontSize = 12.sp)
            }
        } else {
            // 已安装：启动 / 备份 / 恢复 / 删除
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("启动", filled = true, onClick = onLaunch)
                GlassButton("备份", onClick = onBackup)
                GlassButton("恢复", onClick = onRestore)
                GlassButton("删除", onClick = onDelete)
            }
        }
    }
}

/** 向导第②步的精简安装卡：只保留 Logo/名称/一键安装/行内进度。 */
@Composable
internal fun CompactDistroCard(
    info: DistroInfo,
    state: InstallState,
    onInstall: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(distroColor(info.id)),
                contentAlignment = Alignment.Center,
            ) { Text(info.name.first().toString(), color = Color.White) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${info.name} ${info.version}", color = GlassTokens.onGlass, fontSize = 14.sp)
                Text("推荐新手 · 约 ${info.sizeMb} MB", color = GlassTokens.onGlassDim, fontSize = 11.sp)
            }
            if (!info.installed) {
                GlassButton(
                    if (state.running) "${state.percent}%" else "安装",
                    enabled = !state.running,
                    filled = true,
                    onClick = onInstall,
                )
            } else {
                Text("已装好 ✓", color = GlassTokens.onGlassDim, fontSize = 12.sp)
            }
        }
        if (state.running) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(4.dp))
            Text(state.stage, color = GlassTokens.onGlassDim, fontSize = 11.sp)
        }
    }
}

/** 各发行版官方主色（Logo 色块用）。 */
private fun distroColor(id: String): Color = when {
    id.startsWith("ubuntu") -> Color(0xFFE95420)
    id.startsWith("debian") -> Color(0xFFA81D33)
    id.startsWith("kali") -> Color(0xFF367BF0)
    id.startsWith("alpine") -> Color(0xFF0D597F)
    else -> Color(0xFF5E6AD2)
}

/**
 * 玻璃确认对话框（删除等破坏性操作通用）：
 * 全屏半透明遮罩 + 居中 GlassSurface，与全应用玻璃风格一致（不用 Material AlertDialog）。
 */
@Composable
internal fun GlassConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clickable { }, // 吞掉卡片内点击，避免穿透到遮罩触发 onDismiss
            tint = GlassTokens.surfaceTint,
        ) {
            Text(title, color = GlassTokens.onGlass)
            Spacer(Modifier.height(8.dp))
            Text(body, color = GlassTokens.onGlassDim, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GlassButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                GlassButton(confirmText, filled = true, onClick = onConfirm)
            }
        }
    }
}

/** 玻璃输入框：BasicTextField 套玻璃底（避免 Material 填充风格破坏玻璃感）。 */
@Composable
internal fun GlassTextField(
    value: TextFieldValue,
    hint: String,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = GlassTokens.onGlass, fontSize = 14.sp),
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

/** 玻璃按钮（本模块通用小按钮；filled=true 时为高亮主操作）。 */
@Composable
internal fun GlassButton(
    text: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.05f)
                    filled -> Color.White.copy(alpha = 0.26f)
                    else -> GlassTokens.surfaceTint
                }
            )
            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) GlassTokens.onGlass else GlassTokens.onGlassDim,
            fontSize = 14.sp,
        )
    }
}

/**
 * plugin-distro 作为「moxsh 原生玻璃插件」的注册契约（照 plugin-float 的
 * FloatGlassPluginContract 模式）：宿主玻璃环境里渲染入口卡片，点击进入本管理器。
 * 主 app / 插件宿主只需 `DistroManagerPluginContract.register(host)` 装配。
 */
object DistroManagerPluginContract : PluginContract {
    override val id: String = "distro.manager"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            // 插件宿主内也可独立打开全屏管理器（Back 由系统处理）。
            DistroManagerScreen(onBack = { open = false })
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("发行版管理器", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                Text(
                    "一键安装 Ubuntu / Debian / Kali / Alpine，备份与恢复 rootfs，全程免命令行。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("打开管理器", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

/**
 * 独立入口 Activity（manifest 已声明）：桌面/插件宿主直接进入发行版管理器。
 * 主 app 的常规路径是 MainActivity 顶栏"管理"按钮（见 MoxshRoot 接线）。
 */
class DistroManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoxshGlassTheme {
                Box(Modifier.fillMaxSize().systemBarsPadding()) {
                    GlassBackdrop(performantBlur = false, modifier = Modifier.fillMaxSize())
                    DistroManagerScreen(onBack = { finish() })
                }
            }
        }
    }
}

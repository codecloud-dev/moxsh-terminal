package com.moxsh.plugin.store

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.shared.MoxPackage
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个条目的安装状态（四阶段：下载 → 验签 → 解压 → 启用）。
 * 照 plugin-distro 的 InstallState 口径：running/percent/stage/done。
 */
internal data class InstallState(
    val running: Boolean = false,
    val percent: Int = 0,
    val stage: String = "",
    val done: Boolean? = null,
)

/** 商店 Tab：分类过滤（全部/插件/技能/主题）+ 已安装管理页。 */
private enum class StoreTab(val label: String, val typeFilter: String?) {
    All("全部", null),
    Plugin("插件", "plugin"),
    Skill("技能", "skill"),
    Theme("主题", "theme"),
    Installed("已安装", null),
}

/** type -> 徽章色块底色（玻璃卡上可读的柔和色）。 */
private fun typeColor(type: String): Color = when (type) {
    "plugin" -> Color(0xFF5E6AD2)
    "skill" -> Color(0xFF2E7D32)
    "theme" -> Color(0xFFB388FF)
    "rootfs" -> Color(0xFF0D597F)
    else -> Color(0xFF666666)
}

/**
 * 插件商店（D16）：玻璃材质商店全链路。
 *
 *  - 顶部玻璃搜索框 + 分类 Tab（全部/插件/技能/主题）+ 已安装 Tab；
 *  - 玻璃卡片：名称/版本/作者/大小/类型徽章 + "一键安装"大按钮
 *    （进度卡：下载 → 验签 → 解压 → 启用 四阶段，照 plugin-distro 进度卡）；
 *  - price>0 卡片右上角"¥x 预留"徽章 + purchased 标记（D17，仅 UI 展示，无支付）；
 *  - 已安装 Tab：卸载 / 启停玻璃 Switch / 检查更新；
 *  - "本地上传"：SAF 选 .mox → 拷私有区 → 验签 → 安装（外来 zip 格式转换点见安装注释）。
 *
 * 本 Screen 不自带 GlassBackdrop：嵌入宿主时已有玻璃背景；独立打开由
 * [StoreActivity] 自行铺背景。
 */
@Composable
fun StoreScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 目录数据：本地源 + 云源 + 内置目录合并（任一失败静默兜底） ----
    var entries by remember { mutableStateOf(BuiltinCatalog.entries()) }
    LaunchedEffect(Unit) {
        entries = StoreRepository.loadCatalog(context.cacheDir)
    }

    // ---- 界面状态 ----
    var tab by remember { mutableStateOf(StoreTab.All) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val installStates = remember { mutableStateMapOf<String, InstallState>() }
    var activeInstallId by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<String?>(null) }
    // 启停/卸载后 +1 触发已安装列表重组（数据源在 SharedPreferences/文件系统）。
    var installedTick by remember { mutableStateOf(0) }
    val installedIds = remember(installedTick) { StoreInstallManager.installedIds(context) }

    /** 安装完成后的统一收尾：刷新已安装列表。 */
    fun refreshInstalled() { installedTick++ }

    // ---- 本地上传（SAF 选 .mox）：OpenDocument 拿 uri，先拷私有临时文件再交 native ----
    // （native 不识别 content://，必须先落一份私有文件，照 plugin-distro 恢复流程约定。）
    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // ① SAF uri -> cacheDir 私有临时 .mox（IO 线程）
            val tmp = withContext(Dispatchers.IO) {
                val f = File(context.cacheDir, "upload-${System.currentTimeMillis()}.mox")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (f.isFile && f.length() > 0) f else null
            }
            if (tmp == null) {
                installStates["upload"] = InstallState(done = false, stage = "读取所选文件失败")
                activeInstallId = "upload"
                return@launch
            }
            // ② 外来格式转换点（D15/M7）：Termux/zip 插件包在这里先"解 zip → 生成
            //    manifest.json → 重打 tar"再验签；转换器落地前 zip 包 readManifest
            //    即为 null，提示"外来格式待转换"。
            val manifest = MoxPackage.readManifest(tmp.absolutePath)
            if (manifest == null) {
                installStates["upload"] = InstallState(
                    done = false, stage = "不是 .mox 包（外来 zip 转换 M7 支持）",
                )
                activeInstallId = "upload"
                return@launch
            }
            val entry = StoreEntry(
                id = manifest.id, name = manifest.name, version = manifest.version,
                author = manifest.author, description = manifest.description,
                type = manifest.type, minAppVersion = manifest.minAppVersion,
                permissions = manifest.permissions, price = manifest.price,
                purchased = manifest.purchased, sizeBytes = tmp.length(),
            )
            startInstall(entry, singleFileApi(tmp), context, installStates, scope,
                onDone = { ok -> if (ok) refreshInstalled() },
                setActive = { activeInstallId = it })
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

            // ---- 顶栏：返回 / 标题 / 本地上传 ----
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("←", color = GlassTokens.onGlass, fontSize = 18.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onBack() }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("插件商店", color = GlassTokens.onGlass, fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    GlassStoreButton("本地上传") { uploadPicker.launch(arrayOf("*/*")) }
                }
            }

            // ---- 玻璃搜索框 ----
            GlassStoreTextField(query, "搜索插件 / 技能 / 主题…") { query = it }
            Spacer(Modifier.height(8.dp))

            // ---- 分类 Tab（全部/插件/技能/主题/已安装） ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreTab.entries.forEach { t ->
                    val selected = tab == t
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (selected) Color.White.copy(alpha = 0.26f)
                                else Color.White.copy(alpha = 0.08f)
                            )
                            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
                            .clickable { tab = t }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            if (t == StoreTab.Installed) "${t.label} ${installedIds.size}"
                            else t.label,
                            color = if (selected) Color.White else GlassTokens.onGlassDim,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // ---- 列表 ----
            val q = query.text.trim()
            val visible = entries.filter { e ->
                (tab.typeFilter == null || e.type == tab.typeFilter) &&
                    (q.isEmpty() || e.name.contains(q) || e.description.contains(q))
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                if (tab == StoreTab.Installed) {
                    if (installedIds.isEmpty()) {
                        item {
                            GlassSurface(Modifier.fillMaxWidth()) {
                                Text("还没有安装任何包", color = GlassTokens.onGlass)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "到\"全部\"里一键安装，或用右上角\"本地上传\"导入 .mox。",
                                    color = GlassTokens.onGlassDim, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    items(installedIds, key = { it }) { id ->
                        InstalledCard(
                            id = id,
                            catalogEntry = entries.firstOrNull { it.id == id },
                            enabled = StoreInstallManager.isEnabled(context, id),
                            updatable = entries.any {
                                it.id == id && it.version != StoreInstallManager.installedVersion(context, id)
                            },
                            onToggle = { on ->
                                StoreInstallManager.setEnabled(context, id, on)
                                installedTick++
                            },
                            onUpdate = {
                                // 更新 = 重新走四阶段安装（下载覆盖解压）。
                                entries.firstOrNull { it.id == id }?.let { e ->
                                    startInstall(e, defaultApi(), context, installStates, scope,
                                        onDone = { ok -> if (ok) refreshInstalled() },
                                        setActive = { activeInstallId = it })
                                }
                            },
                            onUninstall = { pendingUninstall = id },
                        )
                    }
                } else {
                    items(visible, key = { it.id }) { e ->
                        StoreEntryCard(
                            entry = e,
                            installed = installedIds.contains(e.id),
                            state = installStates[e.id] ?: InstallState(),
                            onInstall = {
                                startInstall(e, defaultApi(), context, installStates, scope,
                                    onDone = { ok -> if (ok) refreshInstalled() },
                                    setActive = { activeInstallId = it })
                            },
                        )
                    }
                }
            }
        }

        // ---- 底部玻璃进度卡（四阶段：下载 → 验签 → 解压 → 启用） ----
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
                        "upload" -> "本地上传安装"
                        else -> entries.firstOrNull { it.id == activeId }?.name ?: activeId
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
                Text(
                    if (st.running) "${st.stage}  ${st.percent}%" else st.stage,
                    color = GlassTokens.onGlassDim, fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!st.running) GlassStoreButton("关闭") { activeInstallId = null }
                }
            }
        }

        // ---- 卸载确认（玻璃对话框，照 plugin-distro 的 GlassConfirmDialog） ----
        pendingUninstall?.let { id ->
            val name = entries.firstOrNull { it.id == id }?.name ?: id
            GlassStoreConfirmDialog(
                title = "卸载 $name？",
                body = "将删除安装目录 /data/data/com.moxsh/store/installed/$id/，其数据不可恢复。",
                confirmText = "卸载",
                onConfirm = {
                    StoreInstallManager.uninstall(context, id)
                    pendingUninstall = null
                    installedTick++
                },
                onDismiss = { pendingUninstall = null },
            )
        }
    }
}

/** 默认安装数据源：本地 Download 目录优先（其 catalog 兜底逻辑在 StoreRepository）。 */
private fun defaultApi(): StoreApi = LocalStoreApi()

/** 上传/已下载文件的直连数据源：download 直接返回给定文件。 */
private fun singleFileApi(file: File): StoreApi = object : StoreApi {
    override suspend fun catalog(): List<StoreEntry> = emptyList()
    override suspend fun download(id: String): File = file
}

/** 发起四阶段安装（照 plugin-distro 的 startInstall：IO 执行 + 主线程写状态）。 */
internal fun startInstall(
    entry: StoreEntry,
    api: StoreApi,
    context: android.content.Context,
    states: MutableMap<String, InstallState>,
    scope: kotlinx.coroutines.CoroutineScope,
    onDone: (Boolean) -> Unit,
    setActive: (String?) -> Unit,
) {
    if (states[entry.id]?.running == true) return
    states[entry.id] = InstallState(running = true, percent = 0, stage = "准备中")
    setActive(entry.id)
    scope.launch {
        val ok = StoreInstaller.install(context = context, entry = entry, api = api) { percent, stage ->
            // 进度回调来自 IO 线程，Compose 快照写需切回主线程（照 plugin-distro）。
            withContext(Dispatchers.Main) {
                states[entry.id] = InstallState(running = true, percent = percent, stage = stage)
            }
        }
        states[entry.id] = InstallState(
            running = false, done = ok,
            percent = if (ok) 100 else 0,
            stage = if (ok) "安装完成" else "安装失败（见进度卡原因）",
        )
        onDone(ok)
    }
}

/**
 * 商店条目玻璃卡片：类型色块 + 名称/版本/作者/大小 + 类型徽章 +
 * "一键安装"大按钮；price>0 右上角"¥x 预留"徽章（D17，仅展示无支付）。
 */
@Composable
internal fun StoreEntryCard(
    entry: StoreEntry,
    installed: Boolean,
    state: InstallState,
    onInstall: () -> Unit,
) {
    // 外层 Box 用来悬浮右上角的价格徽章。
    Box(Modifier.fillMaxWidth()) {
        GlassSurface(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 类型色块：type 徽章底色 + 首字符
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(typeColor(entry.type))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(entry.name.first().toString(), color = Color.White, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${entry.name} ${entry.version}", color = GlassTokens.onGlass)
                    Text(
                        "${entry.author} · 约 ${entry.sizeBytes / 1024 / 1024 + 1} MB",
                        color = GlassTokens.onGlassDim, fontSize = 12.sp,
                    )
                    if (entry.permissions.isNotEmpty()) {
                        Text(
                            "权限：${entry.permissions.joinToString("、")}",
                            color = GlassTokens.onGlassDim, fontSize = 11.sp,
                        )
                    }
                }
                // 类型徽章
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(typeLabel(entry.type), color = GlassTokens.onGlass, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            if (!installed) {
                // 未安装："一键安装"大按钮（玻璃材质占满整行）+ 卡内四阶段进度。
                GlassStoreButton(
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
                    Text("${state.stage}  ${state.percent}%",
                        color = GlassTokens.onGlassDim, fontSize = 12.sp)
                }
            } else {
                Text("已安装 ✓ 可在\"已安装\"页管理",
                    color = GlassTokens.onGlassDim, fontSize = 12.sp)
            }
        }

        // D17 售卖预留：price>0 右上角徽章 + 已购标记（无支付流程）。
        if (entry.price > 0.0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFFF8FB1).copy(alpha = 0.75f))
                    .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                val paid = if (entry.purchased) " · 已购" else ""
                Text("¥${if (entry.price % 1.0 == 0.0) entry.price.toInt() else entry.price} 预留$paid",
                    color = Color.White, fontSize = 10.sp)
            }
        }
    }
}

/**
 * 已安装卡片：名称/版本 + 启停玻璃 Switch + 检查更新 / 卸载。
 * [updatable] 为 true（目录版本 != 商店目录版本）时"检查更新"变高亮"更新 vX.Y.Z"。
 */
@Composable
private fun InstalledCard(
    id: String,
    catalogEntry: StoreEntry?,
    enabled: Boolean,
    updatable: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(typeColor(catalogEntry?.type ?: "")),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (catalogEntry?.name ?: id).first().toString(),
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(catalogEntry?.name ?: id, color = GlassTokens.onGlass, fontSize = 14.sp)
                Text(
                    "v${catalogEntry?.version ?: "?"} · ${typeLabel(catalogEntry?.type ?: "plugin")}",
                    color = GlassTokens.onGlassDim, fontSize = 11.sp,
                )
            }
            // 启停开关（玻璃 Switch：material3 Switch，主题色着色，照 Glass.kt 用法）。
            Text(if (enabled) "启用" else "禁用", color = GlassTokens.onGlassDim, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Switch(checked = enabled, onCheckedChange = { onToggle(it) })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val newVer = catalogEntry?.version
            GlassStoreButton(
                if (updatable && newVer != null) "更新 $newVer" else "检查更新",
                filled = updatable,
                onClick = onUpdate,
            )
            GlassStoreButton("卸载", onClick = onUninstall)
        }
    }
}

/** type 中文标签。 */
private fun typeLabel(type: String): String = when (type) {
    "plugin" -> "插件"
    "skill" -> "技能"
    "theme" -> "主题"
    "rootfs" -> "系统"
    else -> type
}

// ------------------------------------------------------------------
// 本模块玻璃小组件（照 plugin-distro 的 internal 组件风格；internal 声明
// 不可跨模块复用，故在 plugin-store 内保留一份副本，视觉参数与其一致）。
// ------------------------------------------------------------------

/** 玻璃按钮（filled=true 高亮主操作，如"一键安装"）。 */
@Composable
internal fun GlassStoreButton(
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

/** 玻璃搜索框：BasicTextField 套玻璃底（避免 Material 填充风格破坏玻璃感）。 */
@Composable
internal fun GlassStoreTextField(
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
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
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

/** 玻璃确认对话框（卸载等破坏性操作通用，照 plugin-distro 同名实现）。 */
@Composable
internal fun GlassStoreConfirmDialog(
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
                GlassStoreButton("取消", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                GlassStoreButton(confirmText, filled = true, onClick = onConfirm)
            }
        }
    }
}

package com.moxsh.plugin.distro

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moxsh.plugin.core.PluginContract
import com.moxsh.plugin.core.PluginHost
import com.moxsh.ui.component.GlassSurface
import com.moxsh.ui.component.GlassTokens
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 图形包管理器（D13 四件套之二）。
 *
 * 双源设计：
 *  - `moxsh 源`：moxsh 自有包格式（自研 rootfs 的原生包，走本 app 私有通道安装）；
 *  - `apt 源`：发行版内的 apt 仓库——安装动作实际是经 proot 在该发行版的终端
 *    会话里执行 `apt install -y <pkg>` 等命令（见 [PkgRepository] 注释），
 *    本界面把命令行过程图形化：搜索 / 分类浏览 / 一键装卸，输出解析成进度。
 *
 * 安装队列状态机：排队 Queued → 进行中 Running（0-100，apt 输出解析）→
 * 完成 Done / 失败 Failed。队列玻璃卡片常驻底部；apt 有文件锁，故单 worker
 * 顺序消费（Channel 保证 FIFO），绝不并发执行包操作。
 */
@Composable
fun PackageManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var source by remember { mutableIntStateOf(0) }        // 0=moxsh 源, 1=apt 源
    var keyword by remember { mutableStateOf(TextFieldValue("")) }
    var category by remember { mutableIntStateOf(-1) }     // -1=全部
    val all = remember { PkgRepository.all() }
    // 已安装标记（mock 仓库不可变，这里用状态镜像，装卸动作后翻转）
    val installedMap = remember { mutableStateMapOf<String, Boolean>().apply { all.forEach { put(it.name, it.installed) } } }

    // ---- 安装队列：key -> 任务；Channel 单 worker 顺序消费（apt 文件锁决定不能并发） ----
    val jobs = remember { mutableStateMapOf<String, PkgJob>() }
    val queue = remember { Channel<String>(Channel.UNLIMITED) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        for (key in queue) {
            val job = jobs[key] ?: continue
            jobs[key] = job.copy(state = JobState.Running, stage = "开始执行")
            val ok = PkgRepository.runOperation(job.pkg, job.op) { p, s ->
                jobs[key] = jobs[key]?.copy(percent = p, stage = s) ?: return@runOperation
            }
            jobs[key] = jobs[key]?.copy(
                state = if (ok) JobState.Done else JobState.Failed,
                percent = if (ok) 100 else jobs[key]?.percent ?: 0,
                stage = if (ok) "完成" else "失败（E: 详见终端输出）",
            ) ?: continue
            // 成功后同步本地安装标记（mock 仓库元数据不回写）
            if (ok) installedMap[job.pkg.name] = job.op != 1
        }
    }

    /** 提交包操作进队列：避免同包同操作重复排队。 */
    fun submit(pkg: PkgInfo, op: Int) {
        val key = "${pkg.name}#${op}"
        val exist = jobs[key]
        if (exist != null && (exist.state == JobState.Queued || exist.state == JobState.Running)) return
        jobs[key] = PkgJob(pkg, op, JobState.Queued, 0, "排队中…")
        scope.launch { queue.send(key) }
    }

    val pkgs = remember(source, keyword.text, category) {
        all.asSequence()
            .filter { it.source == source }
            .filter { category < 0 || it.category == category }
            .filter {
                val q = keyword.text.trim()
                q.isEmpty() || it.name.contains(q, true) || it.summary.contains(q, true)
            }
            .toList()
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
                Text("包管理器", color = GlassTokens.onGlass, fontSize = 18.sp)
            }
        }

        // ---- 双源分段控件（玻璃） ----
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("moxsh 源", "apt 源").forEachIndexed { i, label ->
                    val selected = source == i
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) Color.White.copy(alpha = 0.24f)
                                else Color.White.copy(alpha = 0.07f)
                            )
                            .border(1.dp, GlassTokens.stroke, RoundedCornerShape(12.dp))
                            .clickable { source = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label + if (i == 1) "（发行版内 apt）" else "",
                            color = if (selected) Color.White else GlassTokens.onGlassDim,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // ---- 搜索框（玻璃） ----
        GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            GlassTextField(keyword, "搜索软件包…", onValueChange = { keyword = it })
        }

        // ---- 分类浏览（开发/网络/多媒体/工具） ----
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(-1 to "全部", 0 to "开发", 1 to "网络", 2 to "多媒体", 3 to "工具").forEach { (id, label) ->
                val selected = category == id
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) Color.White.copy(alpha = 0.24f) else GlassTokens.surfaceTint)
                        .border(1.dp, GlassTokens.stroke, RoundedCornerShape(999.dp))
                        .clickable { category = id }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else GlassTokens.onGlassDim,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // ---- 包列表 ----
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (source == 1) {
                item {
                    GlassSurface(Modifier.fillMaxWidth()) {
                        Text(
                            "apt 源读取自已安装发行版的仓库索引；装卸动作在该发行版的 proot 会话里" +
                                "执行 apt 命令。还没装发行版？先去「发行版管理器」一键安装。",
                            color = GlassTokens.onGlassDim, fontSize = 12.sp,
                        )
                    }
                }
            }
            items(pkgs, key = { "${it.source}:${it.name}" }) { pkg ->
                PkgCard(
                    pkg = pkg.copy(installed = installedMap[pkg.name] ?: false),
                    onInstall = { submit(pkg, 0) },
                    onRemove = { submit(pkg, 1) },
                    onUpdate = { submit(pkg, 2) },
                )
            }
        }

        // ---- 安装队列玻璃卡片（有任务时显示） ----
        if (jobs.isNotEmpty()) {
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("安装队列", color = GlassTokens.onGlass)
                Spacer(Modifier.height(8.dp))
                jobs.entries.toList().forEach { (key, job) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            when (job.op) { 0 -> "安装" 1 -> "卸载" else -> "更新" } + " ${job.pkg.name}",
                            color = GlassTokens.onGlass, fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when (job.state) {
                                JobState.Queued -> "排队中"
                                JobState.Running -> "${job.stage} ${job.percent}%"
                                JobState.Done -> "✓ 完成"
                                JobState.Failed -> "✗ 失败"
                            },
                            color = if (job.state == JobState.Failed) Color(0xFFFF8A80) else GlassTokens.onGlassDim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        // 已结束的任务可从队列清除
                        if (job.state == JobState.Done || job.state == JobState.Failed) {
                            Text(
                                "清除",
                                color = GlassTokens.onGlassDim, fontSize = 12.sp,
                                modifier = Modifier.clickable { jobs.remove(key) },
                            )
                        }
                    }
                    if (job.state == JobState.Running || job.state == JobState.Queued) {
                        LinearProgressIndicator(
                            progress = { job.percent / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 包信息（界面卡片 + 队列共用）。
 * @param source 0=moxsh 源，1=apt 源。
 * @param category 0=开发 1=网络 2=多媒体 3=工具。
 */
data class PkgInfo(
    val name: String,
    val version: String,
    val summary: String,
    val sizeMb: Long,
    val source: Int,
    val category: Int,
    val installed: Boolean = false,
)

/** 队列任务（map 中不可变值替换驱动重组）：Queued → Running → Done/Failed。 */
internal data class PkgJob(
    val pkg: PkgInfo,
    val op: Int,           // 0=安装 1=卸载 2=更新
    val state: JobState,
    val percent: Int,
    val stage: String,
)

internal enum class JobState { Queued, Running, Done, Failed }

/**
 * 包仓库：mock/骨架数据 + 真实实现思路。
 *
 * apt 源刷新思路（native/apt 就绪后实现）：
 *  1) 在目标发行版的 proot 会话执行 `apt update`（命令注入方式：
 *     ExecutionEngine.createSession(command = ProotManager.loginCommand(distroId))，
 *     然后向该会话 write("apt update\n")，从 PTY 输出流收集结果）；
 *  2) 用 `apt-cache search .` / `apt-cache policy <pkg>` 逐包解析
 *     `Package:/Version:/Description:/Installed-Size:/Section:` 段映射为 [PkgInfo]，
 *     Section 字段归入四类（devel→开发，net→网络，sound/video→多媒体，utils→工具）；
 *  3) moxsh 源：自有包索引为签名 JSON，经加固 IPC（PluginHost.runRemoteCommand）拉取。
 *  当前返回 mock 数据，保证 UI / 队列 / 状态机可先行联调。
 */
object PkgRepository {

    /** mock/骨架数据（apt 源代表真实发行版仓库里的常见包）。 */
    fun all(): List<PkgInfo> = listOf(
        PkgInfo("python", "3.12.3", "Python 解释器与标准库", 38, 0, 0, true),
        PkgInfo("clang", "18.1.3", "C/C++ 编译器（LLVM）", 210, 0, 0),
        PkgInfo("rust", "1.79.0", "Rust 工具链（rustc/cargo）", 180, 0, 0),
        PkgInfo("openssh", "9.6", "SSH 客户端/服务端", 12, 0, 1, true),
        PkgInfo("curl", "8.6.0", "HTTP/HTTPS 命令行传输工具", 4, 0, 1),
        PkgInfo("ffmpeg", "6.1.1", "音视频转码与流处理", 96, 0, 2),
        PkgInfo("imagemagick", "6.9.12", "图片查看/转换/处理", 22, 0, 2),
        PkgInfo("vim", "9.1", "模态文本编辑器", 9, 0, 3, true),
        PkgInfo("git", "2.43.0", "分布式版本控制", 15, 0, 3),
        PkgInfo("nodejs", "20.12.2", "Node.js 运行时与 npm", 42, 1, 0),
        PkgInfo("build-essential", "12.10", "GCC/make 编译环境", 160, 1, 0),
        PkgInfo("nginx", "1.24.0", "高性能 Web 服务器", 6, 1, 1),
        PkgInfo("tmux", "3.4", "终端复用器", 2, 1, 3),
    )

    /**
     * 执行包操作（骨架）：生产实现经 ExecutionEngine 在对应发行版会话注入命令——
     *   安装：`apt install -y <pkg>`   卸载：`apt remove -y <pkg>`   更新：`apt upgrade -y <pkg>`
     * 并解析 PTY 输出得到进度：
     *   - "Get:N <url>" 行 → 下载阶段（N/总数 折算 0-60%）；
     *   - "Setting up <pkg>" 行 → 解包配置阶段（60-99%）；
     *   - 退出码 0 → Done；apt 打印 "E:" 行即为失败原因，非 0 → Failed。
     *   会话句柄：`val sid = ExecutionEngine.createSession(command, cols, rows)`；
     *   写命令：`ExecutionEngine.write(sid, "apt install -y pkg\n".toByteArray())`；
     *   输出：ExecutionEngine.startPump(sid) { 解析增量字节 }。
     * 骨架用 delay 模拟下载/解包，驱动队列状态机走完整链路。
     */
    suspend fun runOperation(pkg: PkgInfo, op: Int, onProgress: (Int, String) -> Unit): Boolean {
        val verb = when (op) {
            0 -> "apt install -y ${pkg.name}"
            1 -> "apt remove -y ${pkg.name}"
            else -> "apt upgrade -y ${pkg.name}"
        }
        onProgress(5, "提交：$verb")
        repeat(9) { step ->
            kotlinx.coroutines.delay(250)
            onProgress(10 + step * 9, "下载/解包 ${pkg.name}")
        }
        onProgress(100, "完成")
        return true
    }
}

/**
 * 包卡片：名称/版本/描述/大小 + 操作按钮（按安装态显示 安装 / 卸载+更新）。
 */
@Composable
private fun PkgCard(
    pkg: PkgInfo,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onUpdate: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pkg.name, color = GlassTokens.onGlass)
                    Spacer(Modifier.width(8.dp))
                    Text(pkg.version, color = GlassTokens.onGlassDim, fontSize = 11.sp)
                }
                Text(pkg.summary, color = GlassTokens.onGlassDim, fontSize = 12.sp)
                Text(
                    "约 ${pkg.sizeMb} MB" + if (pkg.installed) " · 已安装" else "",
                    color = GlassTokens.onGlassDim, fontSize = 11.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (pkg.installed) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassButton("更新", onClick = onUpdate)
                    GlassButton("卸载", onClick = onRemove)
                }
            } else {
                GlassButton("安装", filled = true, onClick = onInstall)
            }
        }
    }
}

/**
 * plugin-distro 的包管理器注册契约（照 FloatGlassPluginContract 模式）：
 * 宿主玻璃环境里渲染入口卡片，点击进入图形包管理器。
 */
object PackageManagerPluginContract : PluginContract {
    override val id: String = "distro.packages"

    @Composable
    override fun GlassContent(host: PluginHost) {
        var open by remember { mutableStateOf(false) }
        if (open) {
            PackageManagerScreen(onBack = { open = false })
        } else {
            GlassSurface(Modifier.fillMaxWidth()) {
                Text("图形包管理器", color = GlassTokens.onGlass)
                Spacer(Modifier.height(6.dp))
                Text(
                    "搜索/分类浏览软件包，一键装卸；apt 源经发行版 proot 会话执行，免命令行。",
                    color = GlassTokens.onGlassDim,
                )
                Spacer(Modifier.height(10.dp))
                GlassButton("打开包管理器", filled = true, onClick = { open = true })
            }
        }
    }

    /** 把本原生插件注册进宿主。 */
    fun register(host: PluginHost) = host.load(this)
}

package com.moxsh.plugin.ai

import android.content.Context
import com.moxsh.shared.ExecutionEngine
import com.moxsh.shared.ProotManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

/**
 * AI Agent 工具层（D18 核心）：把大模型的工具调用（tool_calls）映射为
 * moxsh 的图形化操作——装发行版/装包/换源/跑命令/解释报错/开商店/备份。
 *
 * 危险分级（小白保护的核心机制）：
 *  - [DangerLevel.LOW]：直接执行，不打断用户；
 *  - [DangerLevel.HIGH]：删除/换源/备份覆盖/大流量安装等破坏性或重操作，
 *    执行前必须经 [ToolContext.requestConfirm] 挂起，UI 弹 **GlassConfirmCard**
 *    （玻璃确认卡），用户看到的是"AI 想删除发行版 Ubuntu，确认？"而不是命令行，
 *    点"确认"才放行——拒绝则把"用户拒绝执行"回传给 AI，由 AI 优雅收尾。
 *
 * AgentExecutor 的一轮对话流程（ReAct 循环）：
 * ```
 * user 消息 → AI 响应 → 有 tool_calls？
 *   ├─ 是：逐个执行（HIGH 先挂确认卡）→ 结果以 role=tool 回传 → 再次请求 AI（循环）
 *   └─ 否：最终文本返回 UI（循环结束）
 * ```
 */

// ---------------------------------------------------------------------------
// 工具模型
// ---------------------------------------------------------------------------

/** 危险等级。 */
enum class DangerLevel {
    /** 低危：读操作/纯信息/可逆小操作，直接执行。 */
    LOW,

    /**
     * 高危：删除、换源、备份覆盖、大流量长耗时安装。
     * 由 [AgentExecutor] 在执行前统一挂玻璃确认卡，用户点"确认"才放行。
     */
    HIGH,
}

/**
 * 工具执行上下文：由 UI/服务层注入，工具实现只依赖它，不直接持有 Activity。
 *
 * @param ctx        应用上下文（扫描目录、开商店等）。
 * @param sessionId  当前终端会话 id（run_command 走 ExecutionEngine 写 PTY）；
 *                   null/-1 表示无活动会话。
 * @param onProgress 长任务的进度文案回传（install_distro 下载/解压阶段），UI 与 AI 都能看到。
 * @param requestConfirm 高危操作确认挂起点：UI 弹玻璃确认卡并挂起协程，
 *                       用户点"确认"返回 true，点"取消"/点遮罩返回 false。
 */
data class ToolContext(
    val ctx: Context,
    val sessionId: Long? = null,
    val onProgress: (String) -> Unit = {},
    val requestConfirm: suspend (title: String, body: String) -> Boolean = { _, _ -> true },
)

/**
 * 一个 Agent 工具。
 *
 * @param name 工具名（模型可见，snake_case）。
 * @param displayName 中文动作名（玻璃工具卡上展示，如"安装发行版"）。
 * @param description 给模型的用途说明（决定模型何时选它）。
 * @param parametersJson OpenAI function 格式的 JSON Schema 文本（org.json 拼装）。
 * @param danger 危险等级；HIGH 时 [execute] 内部必须先 [ToolContext.requestConfirm]。
 * @param execute 执行体：入参为模型给的 JSON 参数，返回给模型看的结果文本（中文优先）。
 */
data class AgentTool(
    val name: String,
    val displayName: String,
    val description: String,
    val parametersJson: String,
    val danger: DangerLevel,
    val execute: suspend (JSONObject, ToolContext) -> String,
)

// ---------------------------------------------------------------------------
// 工具注册表（7 个）
// ---------------------------------------------------------------------------

/**
 * Agent 工具注册表。每个条目 = 模型可调用的一次图形化操作。
 * [toSpecs] 输出 OpenAI function 清单喂给 transport；[byName] 供执行器查找。
 */
object AgentToolRegistry {

    /** 全部工具（顺序即模型看到的清单顺序，常用在前）。 */
    val tools: List<AgentTool> = listOf(
        installDistro(),
        installPkg(),
        changeRepo(),
        runCommand(),
        explainError(),
        openStore(),
        backupDistro(),
    )

    /** 按名查找工具（模型给了未注册名时返回 null，执行器回传错误文本）。 */
    fun byName(name: String): AgentTool? = tools.firstOrNull { it.name == name }

    /** 导出为 OpenAI tools 数组格式（AgentExecutor → transport）。 */
    fun toSpecs(): List<ToolSpec> = tools.map {
        ToolSpec(name = it.name, description = it.description, parametersJson = it.parametersJson)
    }

    // ---------------- 各工具实现 ----------------

    /**
     * 安装发行版：调 ProotManager.install，下载/校验/解压进度经 onProgress
     * 实时回传（UI 进度显示 + 汇总给 AI）。属于大流量重操作，标 HIGH，
     * AgentExecutor 会在执行前统一挂玻璃确认卡（见 [AgentExecutor.executeTool]）。
     */
    private fun installDistro() = AgentTool(
        name = "install_distro",
        displayName = "安装发行版",
        description = "一键安装一个 Linux 发行版（图形化，无需命令行）。可选：" +
            "ubuntu-24.04 / debian-12 / kali-rolling / alpine-3.20。会下载数百 MB，安装完成前请勿关闭应用。",
        parametersJson = schema {
            prop("id", "string", "发行版 id：ubuntu-24.04 / debian-12 / kali-rolling / alpine-3.20")
            required("id")
        },
        danger = DangerLevel.HIGH,
    ) { args, tc ->
        val id = args.optString("id", "ubuntu-24.04")
        val stages = StringBuilder()
        val ok = ProotManager.install(id) { percent, stage ->
            tc.onProgress("[$id] $percent% $stage")
            stages.appendLine("$percent% $stage")
        }
        if (ok) "发行版 $id 安装成功。可用 backup_distro 提醒用户备份，用 run_command 验证。进度：\n$stages"
        else "发行版 $id 安装失败（内核未就绪或校验未通过）。进度：\n$stages"
    }

    /**
     * 安装软件包：在当前发行版会话里用包管理器装包（apt/apk 由发行版决定）。
     * 常规操作标 LOW——命令会打进用户可见的终端，用户全程看得到。
     */
    private fun installPkg() = AgentTool(
        name = "install_pkg",
        displayName = "安装软件包",
        description = "在当前发行版里安装软件包（自动选择 apt/apk）。适合用户说\"我想用 Python/装个编辑器\"等场景。",
        parametersJson = schema {
            prop("packages", "string", "包名，多个用空格分隔，如 \"python3 git\"")
            prop("distro", "string", "可选：目标发行版 id；缺省用当前已启动的发行版")
            required("packages")
        },
        danger = DangerLevel.LOW,
    ) { args, tc ->
        val pkgs = args.optString("packages").trim()
        if (pkgs.isEmpty()) return@AgentTool "参数 packages 为空，未执行。"
        val distro = args.optString("distro", "").ifBlank { null }
        // 包管理器按发行版家族选择：Alpine 用 apk，其余 Debian 系用 apt。
        val cmd = if (distro?.startsWith("alpine") == true) {
            "apk add $pkgs"
        } else {
            "apt update && apt install -y $pkgs"
        }
        execInSession(tc, cmd, timeoutMs = 180_000)
    }

    /**
     * 换源：重写 sources.list 到国内镜像（清华/中科大/阿里）。改系统配置标 HIGH，
     * AgentExecutor 执行前统一挂玻璃确认卡（与 CompatShim 的 termux-change-repo
     * 原生实现呼应）。
     */
    private fun changeRepo() = AgentTool(
        name = "change_repo",
        displayName = "更换软件源",
        description = "把包管理器的下载源切换到国内镜像（tsinghua/ustc/aliyun），提升下载速度。",
        parametersJson = schema {
            prop("mirror", "string", "镜像名：tsinghua / ustc / aliyun")
            prop("distro", "string", "可选：目标发行版 id；缺省为当前发行版")
            required("mirror")
        },
        danger = DangerLevel.HIGH,
    ) { args, tc ->
        val mirror = args.optString("mirror", "tsinghua").lowercase()
        val domain = when (mirror) {
            "tsinghua" -> "mirrors.tuna.tsinghua.edu.cn"
            "ustc" -> "mirrors.ustc.edu.cn"
            "aliyun" -> "mirrors.aliyun.com"
            else -> return@AgentTool "不支持的镜像：$mirror（可用 tsinghua/ustc/aliyun）"
        }
        execInSession(
            tc,
            "sed -i \"s|deb.debian.org|$domain|g; s|archive.ubuntu.com|$domain|g\" " +
                "${ExecutionEngine.PREFIX}/etc/apt/sources.list 2>/dev/null; apt update",
            timeoutMs = 120_000,
        )
    }

    /**
     * 跑命令：写入当前会话 PTY（ExecutionEngine.write），随后轮询读屏幕缓冲尾部
     * 作为输出回传 AI。命令在用户可见的终端里执行，天然透明，标 LOW。
     */
    private fun runCommand() = AgentTool(
        name = "run_command",
        displayName = "执行命令",
        description = "在当前终端会话里执行一条 shell 命令并返回输出。仅在用户明确要求执行时使用。",
        parametersJson = schema {
            prop("command", "string", "要执行的命令，如 \"uname -a\"")
            required("command")
        },
        danger = DangerLevel.LOW,
    ) { args, tc ->
        val cmd = args.optString("command").trim()
        if (cmd.isEmpty()) return@AgentTool "参数 command 为空，未执行。"
        execInSession(tc, cmd, timeoutMs = 30_000)
    }

    /** 解释报错：纯信息工具（LOW）。报错文本由模型结合上下文自行翻译，工具负责确认收到。 */
    private fun explainError() = AgentTool(
        name = "explain_error",
        displayName = "解释报错",
        description = "用户粘贴了一段终端报错，返回其中可识别的命令名与错误类型摘要（主要翻译工作由你完成）。",
        parametersJson = schema {
            prop("error_text", "string", "报错原文")
            required("error_text")
        },
        danger = DangerLevel.LOW,
    ) { args, _ ->
        val text = args.optString("error_text").take(2000)
        // 摘要规则：第一行通常是核心报错；含 Permission denied / No such file 等常见模式直接点名。
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: "(空)"
        val hints = buildList {
            if (text.contains("Permission denied", true)) add("权限不足：试试在命令前加提权，或检查文件属主")
            if (text.contains("No such file", true)) add("文件/路径不存在：检查拼写与当前目录")
            if (text.contains("command not found", true)) add("命令未安装：可用 install_pkg 安装对应包")
            if (text.contains("E: Unable to locate", true)) add("软件源里找不到该包：先 apt update 或换源")
        }
        "报错首行：$firstLine\n识别提示：${if (hints.isEmpty()) "无常见模式命中，请结合上下文解释" else hints.joinToString("；")}"
    }

    /** 打开商店：纯导航（LOW）。UI 收到该工具结果后跳转商店页。 */
    private fun openStore() = AgentTool(
        name = "open_store",
        displayName = "打开应用商店",
        description = "打开 moxsh 应用商店页（浏览/安装插件与技能包）。",
        parametersJson = schema { /* 无参数 */ },
        danger = DangerLevel.LOW,
    ) { _, _ ->
        // UI 层监听该结果文本，触发导航到 plugin-store 页面。
        "__NAVIGATE__:store"
    }

    /**
     * 备份发行版：ProotManager.backup 打 tar 导出到 Download 目录。
     * 输出文件若同名会被覆盖（覆盖风险），标 HIGH，由 AgentExecutor 挂确认卡。
     */
    private fun backupDistro() = AgentTool(
        name = "backup_distro",
        displayName = "备份发行版",
        description = "把发行版 rootfs 打包为 tar 导出到 Download/moxsh-backups 目录，防止重置丢失环境。",
        parametersJson = schema {
            prop("id", "string", "发行版 id，如 ubuntu-24.04")
            required("id")
        },
        danger = DangerLevel.HIGH,
    ) { args, tc ->
        val id = args.optString("id", "ubuntu-24.04")
        val out = "/sdcard/Download/moxsh-backups/$id-${System.currentTimeMillis()}.tar"
        val ok = ProotManager.backup(id, out)
        if (ok) "备份完成：$out。请把文件位置告诉用户。"
        else "备份失败（内核未就绪或发行版不存在）。"
    }

    // ---------------- 内部辅助 ----------------

    /**
     * 把命令写进当前会话 PTY 并轮询采集输出。
     *
     * 实现说明：ExecutionEngine 是"写输入 + 泵输出"的 PTY 模型，没有阻塞式 exec；
     * 这里 write 命令后按 [timeoutMs] 预算轮询屏幕缓冲（copyCells 读可见区末尾行，
     * 布局与 TerminalCore.kt 的 Cell 定义对齐：code(4)+fg(4)+bg(4)+attrs(2)=16 字节），
     * 输出稳定（连续两次采样一致）即认为命令完成。
     *
     * @return 给模型看的执行结果文本（命令 + 末尾输出）。
     */
    private suspend fun execInSession(tc: ToolContext, command: String, timeoutMs: Long): String {
        // 与 TerminalCore.kt 的 CELL_SIZE 对齐（shared 对 terminal-core 是 implementation 依赖，
        // 插件侧拿不到常量，这里自带并注释来源）。
        val cellSize = 16

        val sid = tc.sessionId ?: -1L
        if (sid <= 0 || ExecutionEngine.cols(sid) <= 0) {
            // 无活动会话：临时开一个 shell 会话执行，用完即销毁（不污染用户终端列表）。
            val tmp = ExecutionEngine.createSession(
                command = ExecutionEngine.DEFAULT_SHELL, cols = 80, rows = 24,
            )
            if (tmp < 0) return "终端引擎未就绪，无法执行命令。请建议用户先从发行版管理器启动一个系统。"
            return try {
                runInSession(tmp, command, timeoutMs, cellSize)
            } finally {
                ExecutionEngine.destroySession(tmp)
            }
        }
        return runInSession(sid, command, timeoutMs, cellSize)
    }

    /** 在指定会话里执行并采集输出（见 [execInSession] 的轮询策略）。 */
    private suspend fun runInSession(sid: Long, command: String, timeoutMs: Long, cellSize: Int): String {
        // 从屏幕缓冲可见区末尾提取纯文本（每格取 codepoint 低 21 位，BMP 直接映射 char；
        // 控制字符丢弃，粗略去 ANSI——够 AI 理解即可，不需要逐字节还原 VT 序列）。
        fun readTail(): String {
            val cols = ExecutionEngine.cols(sid)
            val rows = ExecutionEngine.rows(sid)
            if (cols <= 0 || rows <= 0) return ""
            val count = minOf(rows, 12)
            val start = (ExecutionEngine.totalRows(sid) - count).coerceAtLeast(0)
            val buf = ByteArray(cols * cellSize * count)
            val n = ExecutionEngine.copyCells(sid, start, count, buf)
            if (n <= 0) return ""
            val sb = StringBuilder()
            var cIdx = 0
            while (cIdx + cellSize <= n) {
                val code = (buf[cIdx].toInt() and 0xFF) or
                    ((buf[cIdx + 1].toInt() and 0xFF) shl 8) or
                    ((buf[cIdx + 2].toInt() and 0xFF) shl 16) or
                    ((buf[cIdx + 3].toInt() and 0xFF) shl 24)
                when {
                    code == 0 || code < 32 -> { /* 空格/控制：跳过 */ }
                    code == '\n'.code -> sb.append('\n')
                    else -> sb.appendCodePoint(code)
                }
                cIdx += cellSize
            }
            return sb.toString().trim().take(2000)
        }

        // 回车换行把命令送进 PTY。
        ExecutionEngine.write(sid, (command + "\n").toByteArray(Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = readTail()
        var stable = 0
        while (System.currentTimeMillis() < deadline && stable < 3) {
            delay(300)
            ExecutionEngine.pump(sid) // 主动泵一次驱动 PTY 输出（常规泵循环在 UI/服务侧跑）。
            val cur = readTail()
            stable = if (cur == last) stable + 1 else 0
            last = cur
        }
        return "已执行：$command\n终端输出（末尾）：\n${last.ifBlank { "(无输出)" }}"
    }

    /** JSON Schema 便捷拼装（OpenAI function parameters 格式，纯 org.json）。 */
    private inline fun schema(block: SchemaBuilder.() -> Unit): String {
        val b = SchemaBuilder()
        b.block()
        return b.build().toString()
    }
}

/** JSON Schema 拼装器（object 类型 + properties + required）。 */
private class SchemaBuilder {
    private val props = JSONObject()
    private val req = JSONArray()

    /** 追加一个属性（type: string/number/boolean）。 */
    fun prop(name: String, type: String, desc: String) {
        props.put(name, JSONObject().put("type", type).put("description", desc))
    }

    /** 标记必填属性。 */
    fun required(vararg names: String) {
        names.forEach { req.put(it) }
    }

    fun build(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", props)
        .put("required", req)
}

// ---------------------------------------------------------------------------
// Agent 执行器（一轮对话 = ReAct 工具循环）
// ---------------------------------------------------------------------------

/** 一次工具调用在 UI 上的呈现状态（玻璃工具卡四态；PENDING_CONFIRM 供 UI 在
 *  confirm 挂起期间把工具卡渲染成"待确认"样式）。 */
enum class ToolCallStatus { PENDING_CONFIRM, RUNNING, DONE, FAILED }

/** 一轮对话的产出（UI 渲染用）。 */
data class AgentTurnResult(
    /** 最终文本回答。 */
    val text: String,
    /** 本轮发生的工具调用轨迹（名称 + 中文动作名 + 终态），供消息流渲染玻璃工具卡。 */
    val toolEvents: List<ToolEvent>,
)

/** 一次工具调用的事件记录。 */
data class ToolEvent(
    val callId: String,
    val name: String,
    val displayName: String,
    val status: ToolCallStatus,
    /** 结果/失败原因摘要（工具卡展开显示）。 */
    val summary: String,
)

/**
 * Agent 执行器：持有对话历史与工具循环，是 UI 与前台服务共享的核心。
 *
 * 线程约定：所有公开方法都是 suspend，内部在 IO 上跑 transport；
 * 历史（[history]）只在工作协程里读写，UI 通过 [onHistoryChanged] 拿快照刷新。
 *
 * @param appContext 应用上下文（工具实现要扫目录、拼路径；由 UI/服务创建时绑定）。
 * @param configProvider 每轮开始时取最新配置（用户随时可在顶栏改服务商）。
 * @param sessionIdProvider 取当前终端会话 id（run_command 落点）；返回 null 表示无会话。
 * @param confirm 高危确认挂起点（UI 弹玻璃确认卡）。
 * @param onProgress 长任务进度回传（install_distro 等）。
 * @param onDelta 流式文本增量（打字机）。
 */
class AgentExecutor(
    appContext: Context,
    private val configProvider: () -> AiConfig,
    private val sessionIdProvider: () -> Long? = { null },
    private val confirm: suspend (title: String, body: String) -> Boolean = { _, _ -> true },
    private val onProgress: (String) -> Unit = {},
    private val onDelta: (String) -> Unit = {},
    private val onHistoryChanged: () -> Unit = {},
) {

    companion object {
        /** 单次用户消息允许的最大工具轮数（防模型死循环调用）。 */
        private const val MAX_TOOL_ROUNDS = 8

        /** system 提示词主体：身份 + 小白原则。 */
        private const val SYSTEM_BASE =
            "你是 moxsh 的 AI 助手。moxsh 是一个全面兼容 Termux、界面为液态玻璃风格的 " +
                "Android Linux 终端应用，用户大多是没用过命令行的小白。" +
                "你的原则：全程说中文大白话；能用工具（图形化操作）就不要让用户敲命令；" +
                "涉及删除/换源/备份覆盖等高危操作时先向用户说明后果；一次只推进一小步。"
    }

    /** 应用上下文（工具实现内部使用，如扫描技能目录）。 */
    private val appContext: Context = appContext.applicationContext

    /** 对话历史（含 system/assistant/tool 消息，UI 不直接改）。 */
    val history = mutableListOf<ChatMessage>()

    /**
     * 处理一条用户消息，跑完整工具循环直到拿到最终文本。
     *
     * @param userText 用户输入。
     * @param enabledSkills 启用中的技能（UI 的技能 chips 已勾选项），
     *                      其 prompt 会被拼进本轮 system 提示词。
     * @return 最终回答 + 工具事件轨迹。
     */
    suspend fun send(userText: String, enabledSkills: List<Skill> = emptyList()): AgentTurnResult {
        val cfg = configProvider()
        if (!cfg.isUsable) {
            return AgentTurnResult(
                "还没有配置 AI 服务商：请点右上角设置，选服务商并填入 API Key" +
                    "（智谱的 glm-4-flash 免费哦）。",
                emptyList(),
            )
        }

        // 每轮注入 system：身份 + 启用技能的提示词段。
        val systemMsg = ChatMessage(
            role = "system",
            content = (SYSTEM_BASE + "\n\n" + SkillRegistry.promptsFor(enabledSkills)).trim(),
        )

        history += ChatMessage(role = "user", content = userText)
        onHistoryChanged()

        val transport = OpenAiCompatTransport(cfg, onDelta = onDelta)
        val toolSpecs = AgentToolRegistry.toSpecs()
        val events = mutableListOf<ToolEvent>()

        repeat(MAX_TOOL_ROUNDS) {
            val resp = try {
                transport.chat(listOf(systemMsg) + history.toList(), toolSpecs)
            } catch (e: AiException) {
                val fallback = e.userMessage
                history += ChatMessage(role = "assistant", content = fallback)
                onHistoryChanged()
                return AgentTurnResult(fallback, events)
            }

            if (!resp.wantsTool) {
                history += ChatMessage(role = "assistant", content = resp.content)
                onHistoryChanged()
                return AgentTurnResult(resp.content, events)
            }

            // 模型要调工具：先记 assistant(tool_calls)（协议要求原样回传），再逐个执行。
            history += ChatMessage(role = "assistant", content = resp.content, toolCalls = resp.toolCalls)
            for (call in resp.toolCalls) {
                val tool = AgentToolRegistry.byName(call.name)
                val event = if (tool == null) {
                    ToolEvent(call.id, call.name, call.name, ToolCallStatus.FAILED, "未知工具：${call.name}")
                } else {
                    executeTool(tool, call)
                }
                events += event
                // 结果以 role=tool 回传（失败也要回传，让模型自行调整策略）。
                history += ChatMessage(role = "tool", content = event.summary, toolCallId = call.id)
                onHistoryChanged()
            }
        }

        // 超过轮数上限：兜底收尾，避免模型无限循环。
        val tail = "（工具调用轮数已达上限，先汇报到这里。）"
        history += ChatMessage(role = "assistant", content = tail)
        onHistoryChanged()
        return AgentTurnResult(tail, events)
    }

    /** 执行单个工具（HIGH 危险等级先过玻璃确认卡），产出事件。 */
    private suspend fun executeTool(tool: AgentTool, call: ToolCall): ToolEvent {
        return try {
            val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
                .getOrElse { JSONObject() }
            val tc = ToolContext(
                ctx = appContext,
                sessionId = sessionIdProvider(),
                onProgress = onProgress,
                requestConfirm = confirm,
            )
            // P1 修复（确认绕过）：破坏性分级可被模型用 run_command 绕过——
            // 对 LOW/RISKY 工具额外做命令内容审查，命中破坏性模式时强制走确认卡。
            val argsObj = runCatching {
                org.json.JSONObject(call.argumentsJson.ifBlank { "{}" })
            }.getOrDefault(org.json.JSONObject())
            val cmdText = buildString {
                argsObj.keys().forEach { k -> append(argsObj.optString(k)).append(' ') }
            }
            val destructive = tool.danger != DangerLevel.HIGH && Regex(
                "(?i)(rm\\s+-[rf]|mkfs|dd\\s+if=|chmod\\s+-R\\s+777|sed\\s+-i|mkdir)",
            ).containsMatchIn(cmdText)
            if (tool.danger == DangerLevel.HIGH || destructive) {
                // 玻璃确认卡在这里挂起：UI 层的 confirm 实现渲染 GlassConfirmCard，
                // 用户点"确认"才继续，点"取消"走拒绝分支（小白看到的是确认卡而非命令行）。
                // 函数类型调用不允许命名参数（confirm: suspend (title, body) -> Boolean）
                val approved = confirm(
                    "AI 想要：${tool.displayName}",
                    "${tool.displayName} 属于高危操作（${tool.dangerDetail()}）。\n" +
                        "参数：${summarizeArgs(call)}\n确认执行吗？",
                )
                if (!approved) {
                    return ToolEvent(
                        call.id, call.name, tool.displayName, ToolCallStatus.FAILED,
                        "用户在确认卡上点了取消，未执行。",
                    )
                }
            }
            val result = tool.execute(args, tc)
            ToolEvent(call.id, call.name, tool.displayName, result.finalStatus(), result)
        } catch (e: Exception) {
            ToolEvent(call.id, call.name, tool.displayName, ToolCallStatus.FAILED, "执行出错：${e.message}")
        }
    }

    /** 把模型给的参数压成一句中文摘要（确认卡正文用）。 */
    private fun summarizeArgs(call: ToolCall): String = runCatching {
        val o = JSONObject(call.argumentsJson.ifBlank { "{}" })
        o.keys().asSequence().joinToString("，") { k -> "$k=${o.optString(k)}" }
    }.getOrDefault("").ifBlank { "（无参数）" }

    /** 重置会话（新对话）。 */
    fun reset() {
        history.clear()
        onHistoryChanged()
    }

    /** 工具结果的终态判定：结果文本含失败/取消关键词即 FAILED，否则 DONE。 */
    private fun String.finalStatus(): ToolCallStatus =
        if (contains("失败") || contains("取消") || contains("未执行") || contains("不支持"))
            ToolCallStatus.FAILED else ToolCallStatus.DONE

    /** 工具的危险原因一句话（确认卡正文用）。 */
    private fun AgentTool.dangerDetail(): String = when (name) {
        "install_distro" -> "会下载数百 MB 流量并占用存储"
        "change_repo" -> "会覆盖发行版的软件源配置"
        "backup_distro" -> "会写入与发行版等大的备份文件"
        else -> "影响系统状态"
    }
}

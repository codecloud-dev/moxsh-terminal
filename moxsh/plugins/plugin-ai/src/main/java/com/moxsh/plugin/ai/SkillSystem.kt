package com.moxsh.plugin.ai

import android.content.Context
import com.moxsh.shared.MoxPackage
import org.json.JSONObject
import java.io.File

/**
 * AI 技能系统（D15 配套：技能与插件统一 .mox 格式）。
 *
 * 设计要点：
 *  - 技能 = manifest（.mox 元数据）+ [Skill.prompt]（注入 system prompt 的行为指令）+
 *    [Skill.tools]（该技能允许/强调使用的 Agent 工具名白名单）+ 启用开关；
 *  - 技能源自两条路：
 *      1. 内置技能（[SkillRegistry.builtinSkills]，代码内常量，官方出品开箱即用）；
 *      2. 商店安装的 .mox skill 包（[SkillRegistry.scanInstalled] 扫描安装目录）；
 *  - [SkillRegistry.promptsFor] 把启用技能的 prompt 拼成 system 提示词段落，
 *    AgentExecutor 每轮对话时注入——AI 的"专业能力"即来自技能包。
 */
data class Skill(
    /** 技能元数据（.mox manifest，type 固定为 "skill"）。 */
    val manifest: MoxPackage.MoxManifest,
    /** 注入 system prompt 的行为指令（描述 AI 何时/如何运用该技能）。 */
    val prompt: String,
    /** 该技能关联的 Agent 工具名列表（白名单提示，非硬隔离——硬隔离由权限层后续版本做）。 */
    val tools: List<String>,
    /** 是否启用（持久化在 SharedPreferences）。 */
    val enabled: Boolean,
)

/**
 * 技能注册表：扫描/开关/拼提示词。
 */
object SkillRegistry {

    private const val PREFS = "moxsh_ai_skills"
    private const val KEY_ENABLED_PREFIX = "enabled_"

    /**
     * 商店（plugin-store，D16）安装技能包的落盘约定：
     * `<filesDir>/store/installed/<包id>/`，目录内为 MoxPackage.extract 的产物：
     * ```
     * <id>/manifest.json      <- .mox manifest（MoxPackage.readManifest 读取）
     * <id>/signature          <- 验签文件（安装时已验，这里不再重复验）
     * <id>/payload/skill.json <- 技能体：{"prompt": "...", "tools": ["install_distro", ...]}
     * ```
     */
    private const val STORE_INSTALLED_DIR = "store/installed"
    private const val SKILL_JSON = "payload/skill.json"

    /**
     * 扫描商店已安装目录，收集全部技能（含启用态）。
     * 与 [builtinSkills] 合并去重（同 id 时商店包优先，官方内置让位）。
     * 商店目录不存在 / native 未就绪时返回空表（runCatching 兜底，照 MoxPackage 风格）。
     */
    fun scanInstalled(ctx: Context): List<Skill> {
        val toggles = loadToggles(ctx)
        val storeSkills = runCatching {
            val root = File(ctx.filesDir, STORE_INSTALLED_DIR)
            if (!root.isDirectory) return@runCatching emptyList<Skill>()
            root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
                parseSkillDir(dir, toggles[dir.name] ?: false)
            }
        }.getOrDefault(emptyList())

        // 内置技能：未被商店同 id 包覆盖时生效；启用态默认开，
        // 用户显式关过（prefs 里记录了 false）则保持关。
        val storeIds = storeSkills.map { it.manifest.id }.toSet()
        val builtins = builtinSkills()
            .filter { it.manifest.id !in storeIds }
            .map { it.copy(enabled = toggles[it.manifest.id] ?: true) }
        return builtins + storeSkills
    }

    /** 解析单个商店安装目录为技能；非 skill 类型 / 缺 skill.json / 坏 JSON 返回 null。 */
    private fun parseSkillDir(dir: File, enabled: Boolean): Skill? = runCatching {
        val manifest = MoxPackage.readManifest(File(dir, "manifest.json").absolutePath) ?: return null
        if (manifest.type != "skill") return null
        val body = JSONObject(File(dir, SKILL_JSON).readText())
        val tools = body.optJSONArray("tools")
        Skill(
            manifest = manifest,
            prompt = body.optString("prompt"),
            tools = tools?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            enabled = enabled,
        )
    }.getOrNull()

    /** 设置某技能启用/停用（持久化；被停用的技能不进 system prompt）。 */
    fun setEnabled(ctx: Context, id: String, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED_PREFIX + id, on)
            .apply()
    }

    /** 拼接 system prompt：把启用技能的行为指令组织为"技能清单"段落。 */
    fun promptsFor(enabled: List<Skill>): String {
        if (enabled.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("你已加载以下技能，请在合适场景主动运用：")
        for (s in enabled) {
            sb.appendLine("### ${s.manifest.name}（${s.manifest.id} v${s.manifest.version}）")
            sb.appendLine(s.prompt.trim())
            if (s.tools.isNotEmpty()) {
                sb.appendLine("推荐工具：${s.tools.joinToString("、")}")
            }
            sb.appendLine()
        }
        return sb.toString().trim()
    }

    /**
     * 读取全部开关记录（id -> true/false）。
     * 注意：false 也是有效记录（用户显式关闭过内置技能），与"从未记录"区分。
     */
    private fun loadToggles(ctx: Context): Map<String, Boolean> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.all.keys
            .filter { it.startsWith(KEY_ENABLED_PREFIX) }
            .associate { key ->
                key.removePrefix(KEY_ENABLED_PREFIX) to p.getBoolean(key, false)
            }
    }

    // ------------------------------------------------------------------
    // 内置技能（代码内常量，官方出品）
    // ------------------------------------------------------------------

    /**
     * 内置 3 技能：linux-helper / pkg-recommender / distro-guide。
     * manifest 用 MoxPackage.MoxManifest 构造（type="skill"，author=moxsh 官方），
     * 与商店技能同构——内置只是"不落盘"而已。
     */
    fun builtinSkills(): List<Skill> = listOf(
        Skill(
            manifest = MoxPackage.MoxManifest(
                id = "builtin.linux-helper",
                name = "Linux 助手",
                version = "1.0.0",
                author = "moxsh 官方",
                description = "命令解释与报错翻译：把 Linux 命令/报错讲成人话。",
                type = "skill",
                minAppVersion = "0.1.0",
                permissions = emptyList(),
                price = 0.0,
                purchased = true,
            ),
            prompt = """
                你是 Linux 终端助手。当用户粘贴命令或报错时：
                1. 先用一句大白话说这条命令/报错在干什么；
                2. 再逐段拆解参数（如果命令不简单）；
                3. 报错时先说原因（权限？不存在？拼写？），再给修正后的命令；
                4. 任何专业术语第一次出现都用括号给中文解释。
                不要直接替用户执行命令，除非用户明确说"帮我执行"。
            """.trimIndent(),
            tools = listOf("run_command", "explain_error"),
            enabled = true,
        ),
        Skill(
            manifest = MoxPackage.MoxManifest(
                id = "builtin.pkg-recommender",
                name = "荐包助手",
                version = "1.0.0",
                author = "moxsh 官方",
                description = "按用户需求推荐软件包并引导图形化安装。",
                type = "skill",
                minAppVersion = "0.1.0",
                permissions = emptyList(),
                price = 0.0,
                purchased = true,
            ),
            prompt = """
                你是软件包推荐助手。用户描述需求（"我想写 Python"、"要一个文本编辑器"）时：
                1. 给出 1-2 个最合适的包及理由（优先常见、维护活跃的）；
                2. 说明装完怎么验证（跑什么命令能看到效果）；
                3. 用户同意后调用 install_pkg 工具图形化安装，不要让用户手敲 apt。
            """.trimIndent(),
            tools = listOf("install_pkg", "open_store"),
            enabled = true,
        ),
        Skill(
            manifest = MoxPackage.MoxManifest(
                id = "builtin.distro-guide",
                name = "发行版向导",
                version = "1.0.0",
                author = "moxsh 官方",
                description = "根据用途引导小白选择并安装合适的发行版。",
                type = "skill",
                minAppVersion = "0.1.0",
                permissions = emptyList(),
                price = 0.0,
                purchased = true,
            ),
            prompt = """
                你是发行版选择向导。用户想"装个 Linux"时，按用途引导：
                - 新手/日常折腾：Ubuntu 24.04（生态最全）；
                - 稳定服务器风：Debian 12；
                - 安全测试学习：Kali；
                - 极简/低配机：Alpine（仅 12MB）。
                一次最多问一个澄清问题；用户确定后调用 install_distro 工具安装，
                并把安装进度讲给用户听。换源、删除、备份覆盖属于高危操作，必须先确认。
            """.trimIndent(),
            tools = listOf("install_distro", "change_repo", "backup_distro"),
            enabled = true,
        ),
    )
}

package com.moxsh.plugin.core

import java.util.concurrent.ConcurrentHashMap

/**
 * AI 技能注册表（v2，联动 D18 AI Agent 的工具调用体系）。
 *
 * 插件声明 `register_skill` 权限后，可把自己的能力注册为"AI 技能"：
 * AI 助手在规划时能看到技能的 [SkillSpec.description] 与 [SkillSpec.parametersSchema]，
 * 用户确认后以自然语言参数调用 [SkillSpec.handler]，返回文本结果。
 *
 * 与 .mox skill 包（type=skill）的区别：
 *  - .mox skill 包：静态声明 prompt / 工具清单，安装后生效；
 *  - [SkillRegistry]：运行期动态注册，插件可随时挂载 / 摘除自己的工具。
 */
object SkillRegistry {

    /** 一个可被 AI 调用的技能定义。 */
    data class SkillSpec(
        /** 唯一名（建议 "pluginId.skillName" 形式，重复注册会覆盖）。 */
        val name: String,
        /** 一句话中文描述（AI 选择工具的依据，写清楚"什么时候该用我"）。 */
        val description: String,
        /** JSON Schema 片段：参数定义（如 {"query":"string"}）。可为 "{}" 表示无参。 */
        val parametersSchema: String = "{}",
        /** 调用处理器：入参为 AI 给出的键值对，返回给 AI 的文本结果。任意线程回调。 */
        val handler: (args: Map<String, String>) -> String,
    )

    private val skills = ConcurrentHashMap<String, SkillSpec>()

    /** 注册 / 覆盖一个技能。 */
    fun register(spec: SkillSpec) {
        skills[spec.name] = spec
    }

    /** 按 name 摘除技能。 */
    fun unregister(name: String) {
        skills.remove(name)
    }

    /** 摘除某插件注册的全部技能（按 name 前缀 "pluginId." 匹配）。 */
    fun unregisterAllOf(pluginId: String) {
        val prefix = "$pluginId."
        skills.keys.filter { it.startsWith(prefix) }.forEach { skills.remove(it) }
    }

    /** 全部已注册技能（快照，供 AI 规划层读取）。 */
    fun list(): List<SkillSpec> = skills.values.toList()

    /** 按名调用技能；未注册返回 null。 */
    fun invoke(name: String, args: Map<String, String>): String? =
        skills[name]?.let { runCatching { it.handler(args) }.getOrElse { "技能执行失败：${it.message}" } }
}

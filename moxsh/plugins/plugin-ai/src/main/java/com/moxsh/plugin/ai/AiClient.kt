package com.moxsh.plugin.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 模型接入层（D18）。
 *
 * 职责：
 *  - [AiProvider]：国内主流服务商预置（默认 baseUrl + 推荐模型名）+ OpenAI 兼容自定义 +
 *    Local（llama.cpp 端侧，M7 接入，接口保持一致）；
 *  - [AiTransport]：统一对话接口，[OpenAiCompatTransport] 用平台自带
 *    HttpURLConnection + org.json 实现（不引第三方 HTTP/JSON 库，遵循全自研约束），
 *    并以 SSE（text/event-stream）流式解析 `data:` 行，delta 增量拼接；
 *  - [AiConfigStore]：配置持久化到 SharedPreferences，apiKey 经 AndroidKeyStore
 *    派生的 AES-GCM 密钥加密后落盘（见 [AiConfigStore] 注释）。
 *
 * 全部服务商都走 OpenAI 兼容协议（/chat/completions），因此一个 transport 通吃：
 * DeepSeek / 智谱 / 通义 / Kimi 均官方提供 OpenAI 兼容端点。
 */

// ---------------------------------------------------------------------------
// 配置模型
// ---------------------------------------------------------------------------

/**
 * 服务商枚举。每个国内预置项自带默认 baseUrl 与推荐模型名，
 * 小白零配置即可用（填 Key 就能聊）。
 *
 * @param displayName 界面下拉展示的中文名。
 * @param defaultBaseUrl 默认 API 端点（OpenAI 兼容协议根路径，不含 /chat/completions）。
 * @param defaultModel 推荐模型名（各服务商当前性价比/免费档首选）。
 */
enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    /**
     * OpenAI 兼容自定义：用户自填 baseUrl/Key/模型（可接 one-api 等中转、
     * OpenAI 官方、以及任何兼容网关）。
     */
    OpenAICompatible("OpenAI 兼容（自定义）", "", ""),

    /** DeepSeek：https://api.deepseek.com，推荐 deepseek-chat。 */
    DeepSeek("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),

    /** 智谱 AI（GLM）：https://open.bigmodel.cn/api/paas/v4，推荐免费档 glm-4-flash。 */
    Zhipu("智谱 AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),

    /** 通义千问（阿里云百炼 OpenAI 兼容模式）：推荐 qwen-plus。 */
    Qwen("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),

    /** 月之暗面 Kimi：https://api.moonshot.cn/v1，推荐 moonshot-v1-8k。 */
    Moonshot("Kimi（月之暗面）", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),

    /**
     * 本地端侧模型（预留）：llama.cpp 端侧推理，M7 性能阶段接入。
     * 接口保持 OpenAI 兼容（llama.cpp server 自带 /v1/chat/completions），
     * 因此这里 baseUrl 为本地回环端口，上层代码零改动。
     */
    Local("本地模型（端侧，M7）", "http://127.0.0.1:8080/v1", "local"),
}

/**
 * 一次对话会话的模型配置。
 *
 * @param provider 服务商（决定下拉展示与默认值填充）。
 * @param baseUrl  API 根路径；为空时回退 [AiProvider.defaultBaseUrl]。
 * @param apiKey   明文 Key（仅在内存中流转；落盘前经 [AiConfigStore] 加密）。
 * @param model    模型名；为空时回退 [AiProvider.defaultModel]。
 * @param temperature 采样温度 0.0-2.0（Agent 场景建议偏低 0.3，减少幻觉工具调用）。
 */
data class AiConfig(
    val provider: AiProvider = AiProvider.Zhipu,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.3,
) {
    /** 实际请求 baseUrl（自定义值优先，否则用服务商预置）。 */
    val effectiveBaseUrl: String get() = baseUrl.ifBlank { provider.defaultBaseUrl }

    /** 实际请求模型名（自定义值优先，否则用服务商推荐）。 */
    val effectiveModel: String get() = model.ifBlank { provider.defaultModel }

    /** 配置是否可用于发起请求（有端点且有 Key；Local 端侧暂不要求 Key）。 */
    val isUsable: Boolean
        get() = effectiveBaseUrl.isNotBlank() &&
            (apiKey.isNotBlank() || provider == AiProvider.Local)
}

// ---------------------------------------------------------------------------
// 对话数据模型（OpenAI 兼容协议的 Kotlin 视图）
// ---------------------------------------------------------------------------

/** 一条对话消息。role: "system" / "user" / "assistant" / "tool"。 */
data class ChatMessage(
    val role: String,
    val content: String,
    /** role=tool 时：本次结果对应的工具调用 id（协议要求回传）。 */
    val toolCallId: String? = null,
    /** role=assistant 时：模型发起的工具调用列表（原样回传给模型续轮）。 */
    val toolCalls: List<ToolCall> = emptyList(),
)

/** 一次工具调用（模型侧发起）：name + JSON 字符串参数。 */
data class ToolCall(
    val id: String,
    val name: String,
    /** arguments 为原始 JSON 文本（协议规定增量到达，transport 已拼接完整）。 */
    val argumentsJson: String,
)

/** 工具的 OpenAI function 格式描述（name/description/parameters schema）。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 文本（由 AgentTools 以 org.json 拼好）。 */
    val parametersJson: String,
)

/** 模型一次响应：文本内容 +（可选）工具调用请求。 */
data class AiResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
) {
    /** 本轮是否要求执行工具。 */
    val wantsTool: Boolean get() = toolCalls.isNotEmpty()
}

// ---------------------------------------------------------------------------
// 传输层
// ---------------------------------------------------------------------------

/**
 * AI 请求错误（按 HTTP 语义分类，文案全中文，小白可直接看懂）。
 */
class AiException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 错误分类：文案在 [Kind.userMessage] 里给出中文说明。 */
    enum class Kind(val userMessage: String) {
        /** 401/403：Key 无效或无权限。 */
        AUTH("API Key 无效或没有权限，请在顶部设置里检查 Key 是否填对。"),

        /** 429：触发限流/额度用尽。 */
        RATE_LIMIT("请求太频繁或额度不足，请稍等几秒再试，或到服务商控制台查看额度。"),

        /** 5xx：服务商端故障。 */
        SERVER("服务商服务器开小差了，请稍后重试。"),

        /** 连接失败/DNS/超时等网络问题。 */
        NETWORK("网络连接失败，请检查手机网络后再试。"),

        /** 响应格式不符合预期（服务端改版/网关劫持等）。 */
        PROTOCOL("服务商返回了无法解析的内容，请检查 Base URL 是否正确。"),
    }

    /** 界面直接展示的中文文案。 */
    val userMessage: String get() = "${kind.userMessage}（${message ?: "无详情"}）"
}

/**
 * 统一对话传输接口。
 *
 * 所有服务商（含未来的 llama.cpp 端侧）都实现该接口，AgentExecutor 只面向它编程，
 * 切换服务商零成本（M7 Local 接入时接口保持一致）。
 */
interface AiTransport {
    /**
     * 发起一轮对话。
     *
     * @param messages 完整对话历史（含 system 技能提示词与 tool 结果消息）。
     * @param tools    本轮可用的工具清单（OpenAI function 格式）；空表 = 纯聊天。
     * @return 模型响应（文本与/或工具调用）。
     */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): AiResponse
}

/**
 * OpenAI 兼容传输实现（HttpURLConnection + org.json，平台自带，零三方依赖）。
 *
 * 流式细节（SSE）：
 *  - 请求体 `"stream": true`，响应为 `text/event-stream`；
 *  - 逐行读，`data: {...}` 行为增量块，`data: [DONE]` 为结束哨兵；
 *  - `choices[0].delta.content` 增量拼接为最终文本，经 [onDelta] 实时回传 UI（打字机）；
 *  - `choices[0].delta.tool_calls[]` 按 index 增量到达（id/name 首块给全，
 *    arguments 分片续传），按 index 聚合拼接。
 *
 * @param config 模型配置。
 * @param onDelta 流式文本增量回调（UI 打字机光标用）；可为 null（如后台重试场景）。
 * @param connectTimeoutMs 连接超时。
 * @param readTimeoutMs 读超时（流式响应相邻 chunk 间的最大间隔，模型卡住即断）。
 */
class OpenAiCompatTransport(
    private val config: AiConfig,
    private val onDelta: ((String) -> Unit)? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 120_000,
) : AiTransport {

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): AiResponse =
        withContext(Dispatchers.IO) {
            val url = config.effectiveBaseUrl.trimEnd('/') + "/chat/completions"
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = this@OpenAiCompatTransport.connectTimeoutMs
                    readTimeout = this@OpenAiCompatTransport.readTimeoutMs
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    // 智谱等要求显式 UA；统一带上便于服务商识别兼容客户端。
                    setRequestProperty("User-Agent", "moxsh-ai/1.0")
                    // 部分服务商兼容 Bearer 头；未配置 Key 的 Local 端侧则不带。
                    if (config.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                    doOutput = true
                }

                conn.outputStream.use { out ->
                    out.write(buildRequestBody(messages, tools).toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val code = conn.responseCode
                if (code != 200) {
                    val errBody = runCatching {
                        conn.errorStream?.bufferedReader()?.readText()
                    }.getOrNull().orEmpty().take(500)
                    throw classifyHttpError(code, errBody)
                }

                parseSseStream(conn)
            } catch (e: AiException) {
                throw e
            } catch (e: java.io.IOException) {
                // DNS 失败 / 连接拒绝 / 超时等统一归网络类。
                throw AiException(AiException.Kind.NETWORK, e.message ?: "连接失败", e)
            } finally {
                conn?.disconnect()
            }
        }

    /** 组装 OpenAI 兼容 /chat/completions 请求体（stream=true）。 */
    private fun buildRequestBody(messages: List<ChatMessage>, tools: List<ToolSpec>): String {
        val root = JSONObject()
        root.put("model", config.effectiveModel)
        root.put("temperature", config.temperature)
        root.put("stream", true)
        val arr = JSONArray()
        for (m in messages) {
            val o = JSONObject()
            o.put("role", m.role)
            when {
                // assistant 发起的工具调用需原样回传（协议要求模型能续上自己的调用）。
                m.toolCalls.isNotEmpty() -> {
                    o.put("content", m.content)
                    val calls = JSONArray()
                    for (c in m.toolCalls) {
                        calls.put(
                            JSONObject()
                                .put("id", c.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", c.name).put("arguments", c.argumentsJson),
                                )
                        )
                    }
                    o.put("tool_calls", calls)
                }
                // tool 结果消息必须带 tool_call_id。
                m.role == "tool" -> {
                    o.put("content", m.content)
                    o.put("tool_call_id", m.toolCallId ?: "")
                }
                else -> o.put("content", m.content)
            }
            arr.put(o)
        }
        root.put("messages", arr)
        if (tools.isNotEmpty()) {
            val tarr = JSONArray()
            for (t in tools) {
                tarr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", t.name)
                                .put("description", t.description)
                                .put("parameters", JSONObject(t.parametersJson)),
                        )
                )
            }
            root.put("tools", tarr)
        }
        return root.toString()
    }

    /**
     * 逐行解析 SSE 流：`data: {...}` 增量块 -> delta 拼接；`data: [DONE]` 结束。
     * 同时处理非流式回退：个别网关忽略 stream 参数返回整体 JSON（首行以 "{" 开头），
     * 此时把整包读完交给 [parseNonStreamBody]。
     * 注意：body 流只能消费一次，因此用手动 readLine 而非 useLines，方便两条路径共享。
     */
    private fun parseSseStream(conn: HttpURLConnection): AiResponse {
        val content = StringBuilder()
        // index -> (id, name, argumentsBuf)：tool_calls 按 index 增量聚合。
        val calls = LinkedHashMap<Int, Triple<String?, String?, StringBuilder>>()

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        var firstLine = true

        var line = reader.readLine()
        while (line != null) {
            val l = line.trim()
            if (firstLine && l.startsWith("{")) {
                // 网关忽略 stream=true，返回整体 JSON：读完剩余部分走非流式解析。
                val sb = StringBuilder(l)
                while (reader.readLine()?.let { sb.append(it.trim()); true } == true) { /* 读尽 */ }
                return parseNonStreamBody(sb.toString())
            }
            firstLine = false

            if (l.startsWith("data:")) {
                val payload = l.removePrefix("data:").trim()
                if (payload == "[DONE]") break // 结束哨兵
                val obj = runCatching { JSONObject(payload) }.getOrNull()
                if (obj != null) {
                    val choices = obj.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val delta = choices.getJSONObject(0).optJSONObject("delta")
                        if (delta != null) {
                            // 文本增量 -> 拼接 + 实时回调 UI（打字机效果的数据源）。
                            val piece = delta.optString("content", "")
                            if (piece.isNotEmpty()) {
                                content.append(piece)
                                onDelta?.invoke(piece)
                            }
                            // 工具调用增量：按 index 聚合（首块带 id/name，后续块续 arguments 分片）。
                            val tcarr = delta.optJSONArray("tool_calls")
                            if (tcarr != null) {
                                for (i in 0 until tcarr.length()) {
                                    val tc = tcarr.getJSONObject(i)
                                    val idx = tc.optInt("index", 0)
                                    val slot = calls.getOrPut(idx) {
                                        Triple(null as String?, null as String?, StringBuilder())
                                    }
                                    val id = tc.optString("id", "")
                                    val fn = tc.optJSONObject("function")
                                    val name = fn?.optString("name", "") ?: ""
                                    val args = fn?.optString("arguments", "") ?: ""
                                    val merged = if (id.isNotEmpty() || name.isNotEmpty()) {
                                        Triple(
                                            slot.first ?: id.ifEmpty { null },
                                            slot.second ?: name.ifEmpty { null },
                                            slot.third,
                                        )
                                    } else slot
                                    if (args.isNotEmpty()) merged.third.append(args)
                                    calls[idx] = merged
                                }
                            }
                        }
                    }
                }
            }
            line = reader.readLine()
        }

        return AiResponse(
            content = content.toString(),
            toolCalls = calls.entries.sortedBy { it.key }.mapNotNull { (_, v) ->
                val id = v.first ?: return@mapNotNull null
                val name = v.second ?: return@mapNotNull null
                ToolCall(id, name, v.third.toString().ifBlank { "{}" })
            },
        )
    }

    /** 非流式回退解析：一次性 JSON，choices[0].message。 */
    private fun parseNonStreamBody(body: String): AiResponse {
        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AiException(AiException.Kind.PROTOCOL, "响应不是合法 JSON")
        val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(AiException.Kind.PROTOCOL, "缺少 choices[0].message")
        val calls = msg.optJSONArray("tool_calls")
        return AiResponse(
            content = msg.optString("content", ""),
            toolCalls = calls?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val c = a.getJSONObject(i)
                    val fn = c.optJSONObject("function") ?: return@mapNotNull null
                    ToolCall(c.optString("id"), fn.optString("name"), fn.optString("arguments", "{}"))
                }
            } ?: emptyList(),
        )
    }

    /** HTTP 状态码 -> 分类中文错误。 */
    private fun classifyHttpError(code: Int, body: String): AiException {
        val kind = when {
            code == 401 || code == 403 -> AiException.Kind.AUTH
            code == 429 -> AiException.Kind.RATE_LIMIT
            code in 500..599 -> AiException.Kind.SERVER
            else -> AiException.Kind.PROTOCOL
        }
        return AiException(kind, "HTTP $code ${body.ifBlank { "无响应体" }}")
    }
}

// ---------------------------------------------------------------------------
// 配置持久化（AndroidKeyStore 加密）
// ---------------------------------------------------------------------------

/**
 * AI 配置持久化。
 *
 * 安全方案（AndroidKeyStore 包一层）：
 *  - 在 AndroidKeyStore 里生成/复用名为 [KEY_ALIAS] 的 AES-256-GCM 密钥，
 *    密钥材料不出安全硬件（TEE/StrongBox 视机型），应用卸载即销毁；
 *  - SharedPreferences 明文只存 provider/baseUrl/model/temperature 等非敏感项，
 *    apiKey 经 [encrypt]（GCM 随机 IV + 密文 Base64）后落盘，读取时 [decrypt] 还原；
 *  - 即使 root 后的 /data/data 被整目录拷走，Key 也无法在别的设备还原。
 */
object AiConfigStore {

    private const val PREFS = "moxsh_ai_config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_API_KEY_ENC = "api_key_enc"
    private const val KEY_ALIAS = "moxsh_ai_config_key"

    /** GCM IV 长度（12 字节标准值）与标签长度（128 位）。 */
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    /** 读取配置；从未保存过返回默认（智谱 + glm-4-flash，国内小白开箱即用）。 */
    fun load(ctx: Context): AiConfig {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = runCatching {
            AiProvider.valueOf(p.getString(KEY_PROVIDER, AiProvider.Zhipu.name)!!)
        }.getOrDefault(AiProvider.Zhipu)
        val enc = p.getString(KEY_API_KEY_ENC, null)
        return AiConfig(
            provider = provider,
            baseUrl = p.getString(KEY_BASE_URL, "") ?: "",
            apiKey = if (enc != null) decrypt(enc) else "",
            model = p.getString(KEY_MODEL, "") ?: "",
            temperature = p.getFloat(KEY_TEMPERATURE, 0.3f).toDouble(),
        )
    }

    /** 保存配置（apiKey 加密落盘）。 */
    fun save(ctx: Context, config: AiConfig) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putFloat(KEY_TEMPERATURE, config.temperature.toFloat())
            .putString(KEY_API_KEY_ENC, encrypt(config.apiKey))
            .apply()
    }

    // ---------------- AndroidKeyStore AES-GCM ----------------

    /** 取（必要时生成）AndroidKeyStore 内的 AES 密钥；不可用时返回 null（明文回退由调用方感知为空）。 */
    private fun obtainKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getKey(KEY_ALIAS, null) as? SecretKey)
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        gen.generateKey()
    }.getOrNull()

    /** 明文 -> Base64(IV + GCM 密文)。KeyStore 不可用（极旧机型）返回空串（Key 不落盘）。 */
    private fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        val key = obtainKey() ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(iv + ct)
        }.getOrDefault("")
    }

    /** Base64(IV + GCM 密文) -> 明文；解密失败（换机/损坏）返回空串。 */
    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        val key = obtainKey() ?: return ""
        return runCatching {
            val all = Base64.getDecoder().decode(encoded)
            val iv = all.copyOfRange(0, GCM_IV_LEN)
            val ct = all.copyOfRange(GCM_IV_LEN, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

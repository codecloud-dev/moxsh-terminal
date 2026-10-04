package com.moxsh.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 邮箱注册 / 绑定客户端（验证码流程）。
 *
 * ## 设计前提：为什么客户端不碰授权码
 *
 * 邮箱验证码邮件由后端（mox-id / Cloudflare Workers）用 **SMTP 授权码** 发出，
 * 该授权码只存在于 Worker 的环境变量（`wrangler secret put SMTP_AUTH_CODE`）。
 * 本客户端只做一件事：把「邮箱 + 当前 GitHub 令牌」发给后端，取回校验结果。
 * 授权码绝不进 APK、绝不进公开仓库——本仓库公开且 APK 公开分发，内置即泄漏。
 *
 * ## 为什么必须绑定 GitHub
 *
 * 邮箱本身可被随意填写，不加约束等于开放批量注册小号。因此两个请求都强制携带
 * `Authorization: Bearer <GitHub access token>`：后端以 token 解析出的 GitHub
 * login 作为该邮箱的归属主体，客户端无法为他人邮箱"抢注"，也无法伪造身份刷号。
 *
 * 通道：HttpURLConnection（零新依赖），与 [GitHubLogin] 保持一致的极简风格。
 * 全部方法为挂起函数，内部已切到 [Dispatchers.IO]。
 */
object EmailAuthClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** 验证码长度：6 位数字。 */
    const val CODE_LENGTH = 6

    /** 重新获取验证码的冷却时间（秒），防止刷邮件。 */
    const val RESEND_COOLDOWN_SEC = 60

    sealed interface SendResult {
        /** 已发送。[cooldownSec] 为建议的再次获取冷却秒数。 */
        data class Ok(val cooldownSec: Int) : SendResult
        /** 服务端要求稍后再试（频控）。 */
        data class TooFrequent(val retryAfterSec: Int) : SendResult
        /** 失败。[reason] 已是可直接展示给用户的中文文案。 */
        data class Failed(val reason: String) : SendResult
    }

    sealed interface VerifyResult {
        data class Ok(val email: String) : VerifyResult
        data class Failed(val reason: String) : VerifyResult
    }

    /**
     * 轻量邮箱格式校验（只做基本形状检查，真正的可达性由后端发信验证）。
     * 刻意不用 Android 自带 Patterns（会引入 android.util 依赖不利于复用）。
     */
    fun isEmailLooksValid(email: String): Boolean {
        val e = email.trim()
        if (e.isEmpty() || e.length > 254) return false
        val at = e.indexOf('@')
        if (at <= 0 || at != e.lastIndexOf('@')) return false
        val domain = e.substring(at + 1)
        // 域名需有点分隔、无首尾点、无连续点
        if (domain.length < 3 || !domain.contains('.')) return false
        if (domain.startsWith('.') || domain.endsWith('.') || domain.contains("..")) return false
        if (e.contains(' ')) return false
        return true
    }

    /** 触发后端向 [email] 发送验证码。[token] 为 GitHub access token（必填，构成注册前提）。 */
    suspend fun sendCode(email: String, token: String): SendResult = withContext(Dispatchers.IO) {
        if (!isEmailLooksValid(email)) return@withContext SendResult.Failed("邮箱格式不正确")
        if (token.isBlank()) return@withContext SendResult.Failed("请先登录 GitHub 账号")

        val body = JSONObject().put("email", email.trim()).toString()
        val (code, text) = post(AuthConfig.emailSendCodeUrl(), token, body)
        parseSend(code, text)
    }

    private fun parseSend(code: Int, text: String): SendResult {
        if (code == 429) {
            val retry = runCatching { JSONObject(text).optInt("retry_after", RESEND_COOLDOWN_SEC) }
                .getOrDefault(RESEND_COOLDOWN_SEC)
            return SendResult.TooFrequent(retry.coerceAtLeast(1))
        }
        if (code in 200..299) {
            val cd = runCatching { JSONObject(text).optInt("cooldown", RESEND_COOLDOWN_SEC) }
                .getOrDefault(RESEND_COOLDOWN_SEC)
            return SendResult.Ok(cd.coerceAtLeast(0))
        }
        return SendResult.Failed(readError(code, text, default = "验证码发送失败"))
    }

    /** 校验 [code]，通过即完成该邮箱到当前 GitHub 账号的绑定。 */
    suspend fun verify(email: String, code: String, token: String): VerifyResult = withContext(Dispatchers.IO) {
        if (!isEmailLooksValid(email)) return@withContext VerifyResult.Failed("邮箱格式不正确")
        if (token.isBlank()) return@withContext VerifyResult.Failed("请先登录 GitHub 账号")

        val clean = code.trim()
        if (clean.length != CODE_LENGTH || !clean.all { it.isDigit() }) {
            return@withContext VerifyResult.Failed("请输入 $CODE_LENGTH 位数字验证码")
        }

        val body = JSONObject().put("email", email.trim()).put("code", clean).toString()
        val (code0, text) = post(AuthConfig.emailVerifyUrl(), token, body)
        if (code0 in 200..299) {
            val okEmail = runCatching { JSONObject(text).optString("email", email.trim()) }
                .getOrDefault(email.trim())
            return@withContext VerifyResult.Ok(okEmail)
        }
        VerifyResult.Failed(readError(code0, text, default = "验证码校验失败"))
    }

    /** 统一解析后端 {error/message} 字段，转成可展示文案。 */
    private fun readError(code: Int, text: String, default: String): String {
        val fromBody = runCatching {
            val j = JSONObject(text)
            (j.optString("error").takeIf { it.isNotBlank() }
                ?: j.optString("message").takeIf { it.isNotBlank() })
        }.getOrNull()
        if (fromBody != null) return fromBody
        return "$default（HTTP $code）"
    }

    private fun post(url: String, token: String, jsonBody: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 邮箱注册以 GitHub 账号为前提，故每次调用都必须带 GitHub 令牌
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        return try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.readBytes()?.toString(Charsets.UTF_8) ?: "")
        } finally {
            // HttpURLConnection 无 close()，只能 disconnect() 释放连接
            conn.disconnect()
        }
    }
}
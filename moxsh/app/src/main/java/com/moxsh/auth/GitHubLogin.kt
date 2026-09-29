package com.moxsh.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * GitHub「快捷登录」客户端（设备流 / Device Flow，公开客户端，零 secret）。
 *
 * 为什么用设备流而不是网页流：
 *  - OAuth App 的网页流即使带 PKCE 也强制要求 client_secret，而 secret 绝不能进 APK；
 *  - 设备流（GitHub 给原生 / CLI 应用的无密钥方案，gh CLI 即用此）只需 client_id，
 *    不需要任何 client_secret / private key，完美契合「纯客户端、无后端」的 moxsh。
 *
 * 流程：
 *  1. 本地请求设备码 → 拿到 user_code（展示给用户）+ device_code（轮询用）
 *  2. 用 Custom Tabs 打开 verification_uri，用户在浏览器输入 user_code 并授权
 *  3. 后台轮询 token 端点，直到授权完成 → 拿到 access_token（+ refresh_token）
 *  4. 拉取用户资料，加密存储
 *
 * 安全：app 直连 GitHub，不经过自有后端；不持有、不传输任何 secret。
 */
object GitHubLogin {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int,
    )

    data class Token(
        val accessToken: String,
        val refreshToken: String?,
        val scope: String,
        val expiresIn: Int?,
    )

    data class GitHubUser(
        val login: String,
        val id: Long,
        val name: String?,
        val avatarUrl: String,
        val email: String?,
    )

    sealed interface PollResult {
        data class Success(val token: Token) : PollResult
        object Pending : PollResult
        object SlowDown : PollResult
        data class Failed(val message: String) : PollResult
    }

    // ------------------------------------------------------------------
    // 入口：从设置页 / 首启引导拉起登录页（由 LoginActivity 承载设备流 UI）
    // ------------------------------------------------------------------
    fun startLogin(context: Context) {
        context.startActivity(Intent(context, LoginActivity::class.java))
    }

    // ------------------------------------------------------------------
    // 1) 请求设备码
    // ------------------------------------------------------------------
    suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
        val body = form(
            "client_id" to AuthConfig.CLIENT_ID,
            "scope" to AuthConfig.SCOPES,
        )
        val text = post(AuthConfig.DEVICE_CODE_URL, body)
        val json = JSONObject(text)
        if (!json.has("device_code")) {
            throw IllegalStateException("GitHub 未返回设备码（请检查 client_id 是否正确）")
        }
        DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            interval = json.optInt("interval", 5),
            expiresIn = json.optInt("expires_in", 900),
        )
    }

    // ------------------------------------------------------------------
    // 2) 轮询换 token（单次；由 LoginActivity 循环调用）
    // ------------------------------------------------------------------
    suspend fun pollOnce(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = form(
            "client_id" to AuthConfig.CLIENT_ID,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:device_code",
        )
        val (code, text) = postRaw(AuthConfig.TOKEN_URL, body)
        if (code !in 200..299) {
            val err = runCatching { JSONObject(text).optString("error", "unknown") }.getOrDefault("unknown")
            return@withContext PollResult.Failed(mapError(err))
        }
        val json = JSONObject(text)
        val err = json.optString("error", "")
        if (err.isNotBlank()) return@withContext PollResult.Failed(mapError(err))
        val accessToken = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: return@withContext PollResult.Failed("GitHub 未返回 access_token")
        PollResult.Success(
            Token(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                scope = json.optString("scope"),
                expiresIn = if (json.has("expires_in")) json.getInt("expires_in") else null,
            )
        )
    }

    private fun mapError(err: String): String = when (err) {
        "authorization_pending" -> "正在等待你在浏览器中授权…"
        "slow_down" -> "正在放慢轮询…"
        "expired_token" -> "授权已超时，请重试"
        "access_denied" -> "你已拒绝授权"
        else -> "授权失败：$err"
    }

    // ------------------------------------------------------------------
    // 3) 刷新 token（GitHub App 发放的 user-to-server token 会过期，可静默刷新）
    // ------------------------------------------------------------------
    suspend fun refreshToken(refreshToken: String): Token? = withContext(Dispatchers.IO) {
        val body = form(
            "client_id" to AuthConfig.CLIENT_ID,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token",
        )
        val (code, text) = postRaw(AuthConfig.TOKEN_URL, body)
        if (code !in 200..299) return@withContext null
        val json = JSONObject(text)
        val at = json.optString("access_token").takeIf { it.isNotBlank() } ?: return@withContext null
        Token(
            accessToken = at,
            refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken,
            scope = json.optString("scope"),
            expiresIn = if (json.has("expires_in")) json.getInt("expires_in") else null,
        )
    }

    // ------------------------------------------------------------------
    // 4) 拉取用户资料
    // ------------------------------------------------------------------
    suspend fun fetchUserProfile(token: String): GitHubUser = withContext(Dispatchers.IO) {
        val json = getJson("${AuthConfig.API_BASE}/user", token)
        val login = json.optString("login").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("无法获取 GitHub 用户名")
        val email = runCatching { primaryEmail(token) }.getOrNull()
        GitHubUser(
            login = login,
            id = json.optLong("id"),
            name = json.optString("name").takeIf { it.isNotBlank() },
            avatarUrl = json.optString("avatar_url"),
            email = email,
        )
    }

    private suspend fun primaryEmail(token: String): String? = withContext(Dispatchers.IO) {
        val arr = getJsonArr("${AuthConfig.API_BASE}/user/emails", token)
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optBoolean("primary") && e.optBoolean("verified")) {
                return@withContext e.optString("email").takeIf { it.isNotBlank() }
            }
        }
        null
    }

    // ------------------------------------------------------------------
    // 撤销授权入口（引导用户去 GitHub 设置页）
    // ------------------------------------------------------------------
    fun revokeManagementUrl(): String =
        "https://github.com/settings/connections/applications/${AuthConfig.CLIENT_ID}"

    fun openUrl(context: Context, url: String) {
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
    }

    // ------------------------------------------------------------------
    // 网络工具
    // ------------------------------------------------------------------
    private fun post(url: String, body: String): String = postRaw(url, body).second

    private fun postRaw(url: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            return code to streamText(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(url: String, token: String): JSONObject = JSONObject(apiGet(url, token))

    private fun getJsonArr(url: String, token: String): JSONArray = JSONArray(apiGet(url, token))

    private fun apiGet(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val text = streamText(conn, code)
            if (code !in 200..299) throw IllegalStateException("GitHub API $code: $text")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun streamText(conn: HttpURLConnection, code: Int): String {
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        return input?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
    }

    private fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8.name())

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
}

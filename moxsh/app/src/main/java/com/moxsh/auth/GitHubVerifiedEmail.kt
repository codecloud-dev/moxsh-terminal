package com.moxsh.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * 路径 A：**用 GitHub 已验证邮箱一键绑定**。
 *
 * ## 为什么这是默认路径
 *
 * 用户要的是「邮箱注册，且必须绑定 GitHub」。GitHub 账号本身就带一个
 * **GitHub 已验证（verified）的主邮箱**，可直接取用。于是整条链路变成：
 *
 * ```
 * 登录 GitHub → 读 /user/emails → 取 primary+verified → 绑定完成
 * ```
 *
 * 这个选择解决了三件事：
 *
 * 1. **不发送任何邮件** → 不存在发信限流 / IP 信誉 / 端口限制问题。
 *    （139 邮箱 SMTP 限流、Cloudflare 出口 IP 信誉差等问题在这条路径上完全不存在。）
 * 2. **不需要任何后端** → App 直连 GitHub API，装上即用，不依赖 Worker 部署、
 *    域名、邮件服务配置。真正做到「下载即可用」。
 * 3. **证明强度更高** → 邮箱的真实性由 GitHub 出具证明（verified 标记），
 *    而非由某个自建邮件通道的「验证码存在过」来推断。
 *
 * ## 归属主体不可伪造
 *
 * 邮箱与 GitHub login 一起落库，主体由 `/user` 与 `/user/emails` 两个
 * 接口用**同一个 access token** 取得，客户端无法为他人账号「抢注」邮箱。
 *
 * ## 与其他路径的关系
 *
 * 本对象是**可选**的免密快捷方式；主路径「邮箱 + 密码」[EmailSignUpCard] 不依赖
 * GitHub。当用户已登录 GitHub、且想直接复用其已验证邮箱时，才走这里（免密）。
 * [EmailAuthClient] 的验证码流程是**高级补充**，用于绑定一个与 GitHub 不同的
 * 邮箱，此时才需要 GitHub 令牌作为归属主体。三者最终都写进 [EmailBindingStore]。
 */
object GitHubVerifiedEmail {

    /** 一个 GitHub 账号上的已验证邮箱。 */
    data class VerifiedEmail(
        val email: String,
        val primary: Boolean,
        val verified: Boolean,
    ) {
        /** 展示用：主邮箱打星标。 */
        val label: String get() = if (primary) "$email（主邮箱）" else email
    }

    sealed interface Result {
        /** 取到至少一个已验证邮箱。[preferred] 为推荐直接绑定的那个。 */
        data class Ok(val emails: List<VerifiedEmail>, val preferred: VerifiedEmail) : Result
        /** GitHub 上没有任何已验证邮箱（用户需去设置页添加并验证）。 */
        object None : Result
        /** token 无效 / 网络失败。[reason] 为可展示文案。 */
        data class Failed(val reason: String) : Result
    }

    /**
     * 拉取当前 token 所属账号的全部已验证邮箱。
     *
     * 权限依赖：[AuthConfig.SCOPES] 已申请 `user:email`，
     * 少了这个 scope 该接口会返回 403。
     */
    suspend fun fetch(token: String): Result = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.Failed("请先登录 GitHub 账号")

        val arr = try {
            JSONArray(get("${AuthConfig.API_BASE}/user/emails", token))
        } catch (e: Exception) {
            return@withContext Result.Failed(
                "无法获取 GitHub 邮箱：${e.message ?: "网络错误"}",
            )
        }

        val all = mutableListOf<VerifiedEmail>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mail = o.optString("email").takeIf { it.isNotBlank() } ?: continue
            all += VerifiedEmail(
                email = mail,
                primary = o.optBoolean("primary"),
                verified = o.optBoolean("verified"),
            )
        }

        // 只信 GitHub 标记为 verified 的邮箱
        val verified = all.filter { it.verified }
        if (verified.isEmpty()) return@withContext Result.None

        // 推荐项：主邮箱优先，否则第一个
        val best = verified.firstOrNull { it.primary } ?: verified.first()
        Result.Ok(verified, best)
    }

    /**
     * 一键绑定：拉取并直接保存推荐邮箱。成功时返回 [Result.Ok]，已写入本地存储。
     *
     * 用 `verified` 标记来源，UI 上可区分「GitHub 已验证」与「验证码验证」。
     */
    suspend fun bindBest(ctx: Context, token: String): Result = withContext(Dispatchers.IO) {
        when (val r = fetch(token)) {
            is Result.Ok -> {
                EmailBindingStore.save(ctx, r.preferred.email, EmailBindingStore.Source.GITHUB)
                r
            }
            else -> r
        }
    }

    private fun get(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code == 403) throw IllegalStateException("GitHub 未授予 user:email 权限")
            if (code !in 200..299) throw IllegalStateException("GitHub API $code")
            text
        } finally {
            conn.disconnect()
        }
    }
}

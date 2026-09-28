package com.moxsh.auth

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * GitHub 登录流程的客户端入口。
 *
 * 流程：浏览器打开 [AuthConfig.LOGIN_URL]（经后端跳 GitHub 授权）
 * -> GitHub 回调后端 -> 后端 302 到 [AuthConfig.DEEP_LINK_SCHEME]://[AuthConfig.DEEP_LINK_HOST]?token=<JWT>
 * -> [LoginActivity] 解析并存入 [SessionStore]。
 */
object GitHubLogin {
    /** 在浏览器中打开后端登录入口。 */
    fun start(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AuthConfig.LOGIN_URL)))
    }

    /** 解析深链 URI，返回会话 JWT；非本应用深链返回 null。 */
    fun parseToken(uri: Uri?): String? {
        if (uri == null) return null
        if (uri.scheme == AuthConfig.DEEP_LINK_SCHEME && uri.host == AuthConfig.DEEP_LINK_HOST) {
            return uri.getQueryParameter("token")
        }
        return null
    }
}

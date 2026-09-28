package com.moxsh.auth

/**
 * 登录相关配置。
 *
 * 部署后端（mox-id）后，把 [BACKEND_BASE] 改成你的 PUBLIC_BASE。
 * GitHub 的 OAuth 是服务端 confidential 流程，client_secret 只存在后端，App 不需要持有。
 */
object AuthConfig {
    /** 部署后的 mox-id 后端根地址（结尾不带斜杠）。部署后改这里。 */
    const val BACKEND_BASE: String = "https://mox-id.zyr15555086235.workers.dev"

    /** 后端「开始登录」入口：浏览器打开即进入 GitHub 授权。 */
    val LOGIN_URL: String get() = "$BACKEND_BASE/auth/github/start"

    /** App 深链 scheme/host，必须与后端回调拼接一致（moxsh://auth.callback）。 */
    const val DEEP_LINK_SCHEME: String = "moxsh"
    const val DEEP_LINK_HOST: String = "auth.callback"
}

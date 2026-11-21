package com.moxsh.auth

import com.moxsh.BuildConfig

/**
 * GitHub 设备流（Device Flow）登录配置。仅持有可公开的 client_id，
 * 不内置任何 client_secret 或私钥；敏感配置由构建期注入。
 */
object AuthConfig {
    /** 设备码请求端点。 */
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"

    /** 令牌交换端点（设备流轮询 / 刷新都用它）。 */
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** GitHub REST API 基址。 */
    const val API_BASE = "https://api.github.com"

    /** 由 local.properties 经 buildConfig 注入（公开安全）。 */
    val CLIENT_ID: String get() = BuildConfig.GITHUB_CLIENT_ID

    /** 是否已配置真实 client_id（避免用占位符发起无意义网络请求）。 */
    val IS_CONFIGURED: Boolean get() = CLIENT_ID != "REPLACE_WITH_YOUR_GITHUB_OAUTH_CLIENT_ID"

    /** 申请的最小只读权限。 */
    const val SCOPES = "read:user user:email read:org"

    /** 插件商店索引所在仓库（owner/repo），留空则商店入口不可用。 */
    const val PLUGIN_STORE_REPO = "codecloud-dev/moxsh-plugins"

    /** 云同步保留接口：默认关闭，不发起网络请求。 */
    const val ENABLE_CLOUD_SYNC = false
    const val RESERVED_SYNC_BASE = ""

    // ------------------------------------------------------------------
    // 邮箱注册 / 绑定（邮箱验证码）：客户端只持有公开服务地址，
    // 任何授权码或密钥都不进入客户端与公开仓库。
    // ------------------------------------------------------------------

    /** 邮箱验证服务基址，留空则入口不可用、不发起请求。 */
    val EMAIL_API_BASE: String get() = BuildConfig.EMAIL_API_BASE

    /** 是否已配置邮箱验证服务地址。 */
    val EMAIL_IS_CONFIGURED: Boolean
        get() = EMAIL_API_BASE.isNotBlank() && !EMAIL_API_BASE.startsWith("REPLACE_WITH")

    /** 发码端点（POST，Bearer 鉴权）：{email} → 发送验证码到该邮箱。 */
    fun emailSendCodeUrl(): String = "${EMAIL_API_BASE.trimEnd('/')}/auth/email/send-code"

    /** 校验端点（POST，Bearer 鉴权）：{email, code} → 校验并完成绑定。 */
    fun emailVerifyUrl(): String = "${EMAIL_API_BASE.trimEnd('/')}/auth/email/verify"
}

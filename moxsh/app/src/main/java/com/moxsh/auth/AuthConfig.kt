package com.moxsh.auth

import com.moxsh.BuildConfig

/**
 * GitHub 登录（设备流 / Device Flow，公开客户端）配置。
 *
 * 安全约定（对应你定的规格）：
 *  - [CLIENT_ID] 是唯一会进 APK 的值，且本就可公开；真值在 local.properties 的
 *    `github_client_id`，经 Gradle buildConfig 注入，绝不写死、绝不提交 git。
 *  - 本流程为「设备流」，【不需要也不持有任何 client_secret / private key】，
 *    因此 APK 与公开仓库里都不会出现任何秘密。
 *  - 任何后端地址 / 密钥都只放 mox-id 服务端（暂未部署，云同步仅保留接口）。
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

    /** 申请的权限（最小只读，对应你选的 scope：read:user / user:email / read:org）。 */
    const val SCOPES = "read:user user:email read:org"

    /**
     * 插件商店所在 GitHub 仓库（owner/repo）。已建真实仓库 codecloud-dev/moxsh-plugins，
     * 商店索引从该仓库的 plugins.json 读取；留空则插件商店入口不可用。
     */
    const val PLUGIN_STORE_REPO = "codecloud-dev/moxsh-plugins"

    /**
     * 云同步后端（mox-id）。暂未部署，仅保留接口：
     *  - [ENABLE_CLOUD_SYNC]=false → SyncClient 直接返回「未启用」，不发起任何网络请求
     *  - [RESERVED_SYNC_BASE] 留空，绝不写死任何密钥/地址到公开仓库
     */
    const val ENABLE_CLOUD_SYNC = false
    const val RESERVED_SYNC_BASE = ""

    // ------------------------------------------------------------------
    // 邮箱注册 / 绑定（邮箱验证码）
    //
    // 【安全红线】邮箱授权码（SMTP 授权码 / 客户端专用密码）**绝不进 APK、
    // 绝不进公开仓库**——本仓库是公开仓，且 APK 公开分发可被反编译，
    // 一旦内置，任何人即可冒用该邮箱收发邮件。授权码只允许存在于
    // mox-id（Cloudflare Workers）的环境变量 / wrangler secret 中。
    //
    // 客户端只持有 [EMAIL_API_BASE]（服务地址，属公开信息，与 github_client_id
    // 同级、可公开），由 local.properties 的 `email_api_base` 经 buildConfig 注入。
    //
    // 邮箱注册**不强制** GitHub 登录：主路径「邮箱 + 密码」独立注册，无需 token。
    // 仅当使用「验证码」高级路径（绑定与 GitHub 不同的邮箱）时才需要 GitHub
    // 令牌与后端，此时发码/校验请求带 `Authorization: Bearer <GitHub token>`，
    // 后端以 token 解析出的 GitHub login 作为归属主体，防伪造批量注册。
    // ------------------------------------------------------------------

    /** 邮箱验证服务（mox-id）基址，留空则邮箱注册入口不可用（不会发起任何请求）。 */
    val EMAIL_API_BASE: String get() = BuildConfig.EMAIL_API_BASE

    /** 是否已配置邮箱验证服务地址。 */
    val EMAIL_IS_CONFIGURED: Boolean
        get() = EMAIL_API_BASE.isNotBlank() && !EMAIL_API_BASE.startsWith("REPLACE_WITH")

    /** 发码端点（POST，Bearer 鉴权）：{email} → 发送验证码到该邮箱。 */
    fun emailSendCodeUrl(): String = "${EMAIL_API_BASE.trimEnd('/')}/auth/email/send-code"

    /** 校验端点（POST，Bearer 鉴权）：{email, code} → 校验并完成绑定。 */
    fun emailVerifyUrl(): String = "${EMAIL_API_BASE.trimEnd('/')}/auth/email/verify"
}

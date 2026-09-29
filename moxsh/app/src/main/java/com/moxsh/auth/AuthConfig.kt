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
}

package com.moxsh.auth

import android.content.Context

/**
 * 统一身份门面：把「GitHub 登录」与「邮箱账号登录」归一为一个 app 级的
 * 「已登录」概念。
 *
 * ## 为什么需要它
 *
 * 历史上 [SessionStore] 只认 GitHub token，app 的「已登录」判定（设置页、
 * 云同步捕获等）全部绑定 GitHub。现在邮箱账号也能独立登录设备，需要一个
 * 唯一的真相源，避免各处散落 `SessionStore.isLoggedIn || EmailSessionStore.isActive`
 * 这种易漏的拼装。
 *
 * ## 语义边界
 *
 *  - [type] / [label] / [isLoggedIn]：账号身份的**展示与判定**，GitHub 与邮箱都算。
 *  - **云同步**等需要后端鉴权的能力仍由 [SessionStore] 的 GitHub token 决定
 *    （[SyncClient]、[MoxshApplication] 直接读 GitHub token，不在此处），
 *    因为 mox-id 后端目前以 GitHub login 作归属主体。邮箱账号是本地身份。
 *  - [logout] 同时清除 GitHub 与邮箱会话；[EmailBindingStore] 的注册凭据保留，
 *    退出后仍可凭密码重新登录。
 */
object MoxAccount {
    enum class Type { GITHUB, EMAIL, NONE }

    fun type(ctx: Context): Type =
        if (SessionStore.isLoggedIn(ctx)) Type.GITHUB
        else if (EmailSessionStore.isActive(ctx)) Type.EMAIL
        else Type.NONE

    fun isLoggedIn(ctx: Context): Boolean = type(ctx) != Type.NONE

    fun label(ctx: Context): String? = when (type(ctx)) {
        Type.GITHUB -> UserProfileStore.get(ctx)?.login
        Type.EMAIL -> EmailSessionStore.get(ctx)
        else -> null
    }

    fun logout(ctx: Context) {
        SessionStore.clear(ctx)
        UserProfileStore.clear(ctx)
        EmailSessionStore.clear(ctx)
        // EmailBindingStore 保留：本地注册凭据，退出后仍可凭密码重新登录
    }
}

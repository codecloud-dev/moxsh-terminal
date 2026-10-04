package com.moxsh.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 已绑定邮箱的本地加密存储（与 token 同源的安全存储）。
 *
 * 邮箱是在 GitHub 登录成功【之后】额外绑定的，故读取调用方须先确认
 * [SessionStore.isLoggedIn] 为 true——即邮箱永远是 GitHub 账号的附属信息，
 * 不构成独立的登录凭据。
 */
object EmailBindingStore {
    private const val FILE = "mox_secure_email"
    private const val K_EMAIL = "bound_email"
    private const val K_BOUND_AT = "bound_at"

    private fun prefs(ctx: Context): SharedPreferences {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(ctx: Context, email: String) {
        prefs(ctx).edit().apply {
            putString(K_EMAIL, email)
            putLong(K_BOUND_AT, System.currentTimeMillis())
        }.apply()
    }

    /** 已绑定的邮箱；未绑定或 GitHub 已退出则返回 null。 */
    fun get(ctx: Context): String? {
        // 邮箱依附于 GitHub 会话：未登录时不暴露任何邮箱信息
        if (!SessionStore.isLoggedIn(ctx)) return null
        return prefs(ctx).getString(K_EMAIL, null)?.takeIf { it.isNotBlank() }
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun has(ctx: Context): Boolean = get(ctx) != null
}
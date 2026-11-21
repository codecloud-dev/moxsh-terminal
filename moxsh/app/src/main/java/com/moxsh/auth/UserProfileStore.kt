package com.moxsh.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录用户公开资料的本地加密存储。
 *
 * username / avatar 虽非机密，仍与令牌一起加密保存，避免为少量数据引入 Room/KSP。
 */
object UserProfileStore {
    private const val FILE = "mox_secure_profile"
    private const val K_LOGIN = "login"
    private const val K_ID = "id"
    private const val K_NAME = "name"
    private const val K_AVATAR = "avatar_url"
    private const val K_EMAIL = "email"

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

    fun save(ctx: Context, u: GitHubLogin.GitHubUser) {
        prefs(ctx).edit().apply {
            putString(K_LOGIN, u.login)
            putLong(K_ID, u.id)
            putString(K_NAME, u.name)
            putString(K_AVATAR, u.avatarUrl)
            putString(K_EMAIL, u.email)
        }.apply()
    }

    fun get(ctx: Context): GitHubLogin.GitHubUser? {
        val p = prefs(ctx)
        val login = p.getString(K_LOGIN, null) ?: return null
        return GitHubLogin.GitHubUser(
            login = login,
            id = p.getLong(K_ID, 0),
            name = p.getString(K_NAME, null),
            avatarUrl = p.getString(K_AVATAR, "") ?: "",
            email = p.getString(K_EMAIL, null),
        )
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun has(ctx: Context): Boolean = get(ctx) != null
}

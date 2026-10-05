package com.moxsh.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 邮箱账号的「已登录会话」本地加密存储。
 *
 * 与 [EmailBindingStore] 的区别：
 *  - [EmailBindingStore] 存的是**注册凭据**（邮箱 + 派生密码），是「账号」本身，
 *    退出登录后保留，以便下次凭密码重新登录（相当于记住的账号）。
 *  - 本类存的是**当前登录态**（哪个邮箱正处于登录中），应用重启后据此判断
 *    邮箱账号是否仍在线。退出即清空；注册凭据不清空。
 *
 * 使用 EncryptedSharedPreferences（Android Keystore AES-256），与 token 同源。
 */
object EmailSessionStore {
    private const val FILE = "mox_secure_email_session"
    private const val K_EMAIL = "active_email"
    private const val K_SINCE = "login_at"

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

    fun set(ctx: Context, email: String) {
        prefs(ctx).edit()
            .putString(K_EMAIL, email.lowercase().trim())
            .putLong(K_SINCE, System.currentTimeMillis())
            .apply()
    }

    /** 当前登录中的邮箱；未登录返回 null。 */
    fun get(ctx: Context): String? =
        prefs(ctx).getString(K_EMAIL, null)?.takeIf { it.isNotBlank() }

    fun isActive(ctx: Context): Boolean = get(ctx) != null

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}

package com.moxsh.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录会话的本地安全存储。
 * 使用 EncryptedSharedPreferences（基于 Android Keystore 的 AES-256），
 * 即使设备被 root 也无法直接读取 token / refresh_token。
 */
object SessionStore {
    private const val FILE_NAME = "mox_secure_session"
    private const val KEY_TOKEN = "github_session_token"
    private const val KEY_REFRESH = "github_session_refresh"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(context: Context, accessToken: String, refreshToken: String?) {
        prefs(context).edit().apply {
            putString(KEY_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH, refreshToken)
        }.apply()
    }

    fun get(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)

    fun getRefresh(context: Context): String? = prefs(context).getString(KEY_REFRESH, null)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun isLoggedIn(context: Context): Boolean = !get(context).isNullOrBlank()
}

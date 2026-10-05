package com.moxsh.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 已绑定邮箱的本地加密存储（与 token 同源的安全存储）。
 *
 * ## 不再强制绑定 GitHub
 *
 * 邮箱是用户**独立**的 mox 账号身份，不再依附于 GitHub 会话：
 *  - 未登录 GitHub 也能用「邮箱 + 密码」注册（[Source.PASSWORD]）。
 *  - 已登录 GitHub 时，「用 GitHub 已验证邮箱」只是把已验证邮箱一次性填入并
 *    标记来源，方便但**非必须**。
 *  - 因此 [get] 不再以 [SessionStore.isLoggedIn] 为前置条件；GitHub 退出也
 *    不会清除邮箱（见 [LoginActivity] 登出逻辑）。
 *
 * ## 密码派生
 *
 * 密码以 PBKDF2-HMAC-SHA256（随机盐 + 10k 迭代）派生后存储，**明文绝不落盘**。
 * 本地保存密码是为了让该邮箱账号具备独立凭据（可用于后续云端登录 / 找回），
 * 派生参数与盐随密文一同存入加密偏好，验证时按盐重算比对。
 */
object EmailBindingStore {
    private const val FILE = "mox_secure_email"
    private const val K_EMAIL = "bound_email"
    private const val K_BOUND_AT = "bound_at"
    private const val K_SOURCE = "source" // GITHUB | PASSWORD | CODE
    private const val K_PW_HASH = "pw_hash"
    private const val K_PW_SALT = "pw_salt"

    /** 邮箱来源：决定可信度与是否需密码。 */
    enum class Source { GITHUB, PASSWORD, CODE }

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

    /**
     * 记录已绑定邮箱。
     *
     * @param source 来源；[Source.PASSWORD] 需同时给 [passwordHashB64]/[saltB64]。
     * @param passwordHashB64 PBKDF2 派生结果（Base64）；[source] 非 PASSWORD 时传 null。
     * @param saltB64 与 [passwordHashB64] 配套的随机盐（Base64）。
     */
    fun save(
        ctx: Context,
        email: String,
        source: Source,
        passwordHashB64: String? = null,
        saltB64: String? = null,
    ) {
        prefs(ctx).edit().apply {
            putString(K_EMAIL, email)
            putLong(K_BOUND_AT, System.currentTimeMillis())
            putString(K_SOURCE, source.name)
            if (source == Source.PASSWORD && passwordHashB64 != null && saltB64 != null) {
                putString(K_PW_HASH, passwordHashB64)
                putString(K_PW_SALT, saltB64)
            } else {
                remove(K_PW_HASH)
                remove(K_PW_SALT)
            }
        }.apply()
    }

    /** 已绑定的邮箱；未绑定返回 null。不再依赖 GitHub 登录态。 */
    fun get(ctx: Context): String? =
        prefs(ctx).getString(K_EMAIL, null)?.takeIf { it.isNotBlank() }

    /** 邮箱来源（默认 [Source.PASSWORD]）。 */
    fun source(ctx: Context): Source =
        prefs(ctx).getString(K_SOURCE, null)
            ?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.PASSWORD

    /** 该账号是否以密码注册（可用于本地登录 / 找回）。 */
    fun hasPassword(ctx: Context): Boolean =
        prefs(ctx).getString(K_PW_HASH, null)?.isNotBlank() == true

    /** 该邮箱是否来自 GitHub 已验证（可信度最高，无需密码）。 */
    fun isGithubVerified(ctx: Context): Boolean = source(ctx) == Source.GITHUB

    /** 校验明文密码是否匹配（仅 [Source.PASSWORD] 且有密码时有效）。 */
    fun verifyPassword(ctx: Context, plain: String): Boolean {
        val p = prefs(ctx)
        val hash = p.getString(K_PW_HASH, null) ?: return false
        val salt = p.getString(K_PW_SALT, null) ?: return false
        return runCatching { hashPassword(plain, salt) }.getOrDefault("") == hash
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun has(ctx: Context): Boolean = get(ctx) != null

    // ------------------------------------------------------------------
    // 密码派生（纯函数，便于单元测试与复用）
    // ------------------------------------------------------------------

    /** 用给定盐派生明文密码，返回 Base64。 */
    fun hashPassword(plain: String, saltB64: String): String {
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val spec = PBEKeySpec(plain.toCharArray(), salt, ITERATIONS, KEY_LEN_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return Base64.encodeToString(skf.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    /** 生成新的随机盐（Base64）。 */
    fun newSaltB64(): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    private const val ITERATIONS = 10_000
    private const val KEY_LEN_BITS = 256
    private const val SALT_BYTES = 16
}

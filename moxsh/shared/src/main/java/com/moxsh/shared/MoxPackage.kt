package com.moxsh.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * .mox 包 Kotlin 封装（D15，照 [ProotManager] 的 object 封装模式）。
 *
 * 职责：把 native 层（libmoxshcore.so 的 `moxsh_mox_*` C-ABI，对应
 * `terminal-core/src/moxpkg.rs` + `lib.rs` 导出的 mox 系列函数）包装成
 * Kotlin 友好 API，供 plugin-store 的安装链路（下载 → 验签 → 解压 → 启用）
 * 与"本地上传 .mox"入口调用。
 *
 * native 对应关系（terminal-core/src/lib.rs，Rust）：
 *  - [nativeOpen]     -> `moxsh_mox_open`（打开包并扫描条目，返回句柄）
 *  - [nativeVerify]   -> `moxsh_mox_verify`（HMAC-SHA256(secret, manifest) 验签，复用 crypto.rs）
 *  - [nativeExtract]  -> `moxsh_mox_extract`（解包 manifest/signature/payload 到目录）
 *  - [nativeList]     -> `moxsh_mox_list`（条目行数组 `name|size`）
 *  - [nativeManifest] -> `moxsh_mox_manifest`（manifest.json 原文，Kotlin 用平台 org.json 解析）
 *  - [nativeClose]    -> `moxsh_mox_close`（释放句柄，照 moxsh_close 模式）
 *
 * .mox 包格式（规范权威定义在 moxpkg.rs 模块头注释）：tar 容器 +
 * manifest.json（id/name/version/type: plugin|skill|theme|rootfs/permissions/
 * price/purchased）+ signature（HMAC-SHA256 hex）+ payload/。
 *
 * 注意：native 未就绪时各公开 API 返回 false/null（runCatching 兜底），
 * 界面层展示"内核未就绪"文案即可，无需特殊分支。
 */
object MoxPackage {

    // ------------------------------------------------------------------
    // native 桥（libmoxshcore.so）
    // ------------------------------------------------------------------

    init {
        // moxsh 自研内核库；加载失败由调用方 runCatching 兜底（见各 fun 注释）。
        runCatching { System.loadLibrary("moxshcore") }
    }

    /** 打开 .mox 包，返回 opaque 句柄（Long 承载指针）；失败返回 0。 */
    private external fun nativeOpen(path: String): Long

    /** 释放句柄（与 [nativeOpen] 配对；不释放会泄漏 native 内存）。 */
    private external fun nativeClose(handle: Long)

    /** 验签：0=通过，-5=签名不符，其他负数=错误码（见 moxpkg.rs 错误码表）。 */
    private external fun nativeVerify(handle: Long, secret: String): Int

    /** 解包到 outDir（manifest.json/signature/payload/**），成功返回 true。 */
    private external fun nativeExtract(handle: Long, outDir: String): Boolean

    /** 列出条目，每行格式 `name|size_bytes`。 */
    private external fun nativeList(handle: Long): Array<String>

    /** 返回 manifest.json 原文（UTF-8 JSON）。 */
    private external fun nativeManifest(handle: Long): String

    // ------------------------------------------------------------------
    // Kotlin 公开 API（界面层只用这些）
    // ------------------------------------------------------------------

    /**
     * manifest.json 的 Kotlin 视图，字段与 Rust `MoxManifest`（moxpkg.rs）
     * 一一对应。
     */
    data class MoxManifest(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        /** "plugin" | "skill" | "theme" | "rootfs"。 */
        val type: String,
        val minAppVersion: String,
        val permissions: List<String>,
        /** D17 预留：0=免费；>0 付费（仅字段，无支付）。 */
        val price: Double,
        /** D17 预留：是否已购。 */
        val purchased: Boolean,
    )

    /**
     * 读取并解析包的 manifest。native 未就绪 / 非法 .mox 返回 null。
     * manifest JSON 用平台自带 org.json 解析（不引第三方 JSON 库）。
     * 句柄全程 try/finally 配对关闭，防泄漏。
     */
    fun readManifest(path: String): MoxManifest? = runCatching {
        val handle = nativeOpen(path)
        if (handle == 0L) return null
        try {
            parseManifestJson(nativeManifest(handle))
        } finally {
            nativeClose(handle)
        }
    }.getOrNull()

    /**
     * 验签（HMAC-SHA256(secret, manifest 原始字节)）。
     *
     * @param secret 签名密钥：官方源由 Kotlin 层注入（发布版从 Android
     *               Keystore 派生，硬编码占位见 plugin-store/StoreSecrets）。
     * @return true=验签通过；false=签名不符 / native 未就绪 / 非 .mox。
     */
    fun verify(path: String, secret: String): Boolean = runCatching {
        val handle = nativeOpen(path)
        if (handle == 0L) return false
        try {
            nativeVerify(handle, secret) == 0
        } finally {
            nativeClose(handle)
        }
    }.getOrDefault(false)

    /**
     * 解包到 outDir（IO 线程执行）。目录结构：
     * `<outDir>/manifest.json`、`<outDir>/signature`、`<outDir>/payload/**`。
     */
    suspend fun extract(path: String, outDir: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val handle = nativeOpen(path)
            if (handle == 0L) return@withContext false
            try {
                nativeExtract(handle, outDir)
            } finally {
                nativeClose(handle)
            }
        }.getOrDefault(false)
    }

    /** 列出包内条目（`name|size` 行），native 未就绪返回空表。 */
    fun listEntries(path: String): List<Pair<String, Long>> = runCatching {
        val handle = nativeOpen(path)
        if (handle == 0L) return emptyList()
        try {
            nativeList(handle).mapNotNull { row ->
                val parts = row.split("|")
                if (parts.size < 2) return@mapNotNull null
                parts[0] to (parts[1].toLongOrNull() ?: 0L)
            }
        } finally {
            nativeClose(handle)
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** manifest JSON 文本 -> [MoxManifest]（org.json 解析；坏 JSON 返回 null）。 */
    private fun parseManifestJson(json: String): MoxManifest? = runCatching {
        val o = JSONObject(json)
        val perms = o.optJSONArray("permissions")
        MoxManifest(
            id = o.getString("id"),
            name = o.getString("name"),
            version = o.getString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            type = o.getString("type"),
            minAppVersion = o.optString("minAppVersion"),
            permissions = perms?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            price = o.optDouble("price", 0.0),
            purchased = o.optBoolean("purchased", false),
        )
    }.getOrNull()
}

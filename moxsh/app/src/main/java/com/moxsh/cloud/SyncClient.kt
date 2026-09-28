package com.moxsh.cloud

import android.content.Context
import com.moxsh.auth.AuthConfig
import com.moxsh.auth.SessionStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云同步客户端（对接 mox-id Worker：POST /sync/push、GET /sync/pull）。
 *
 *  - 鉴权：SessionStore 的 JWT（Bearer），与 /me 同一套会话
 *  - 通道：HttpURLConnection（零新依赖），仅 https（BACKEND_BASE 即 workers.dev）
 *  - 冲突：last-write-wins（服务端按 updated_at 裁决），key=捕获时间戳 ms
 *
 * 全部方法为阻塞 IO——调用方必须在协程 Dispatchers.IO / 工作线程调用。
 */
object SyncClient {

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val PULL_SINCE_KEY = "mox_sync_last_pull"

    sealed class SyncResult {
        /** 上传 n 条，拉回 m 条（m=0 也算成功）。 */
        data class Ok(val pushed: Int, val pulled: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    /** 全量同步：先 push 未同步队列，再 pull 增量（合并到本地历史文件）。 */
    fun syncNow(ctx: Context): SyncResult {
        val token = SessionStore.get(ctx)
            ?: return SyncResult.Error("未登录")
        val pushed = runCatching { pushQueue(ctx, token) }.getOrElse { return SyncResult.Error(it.message ?: "push 失败") }
        val pulled = runCatching { pullNew(ctx, token) }.getOrElse { return SyncResult.Error(it.message ?: "pull 失败") }
        return SyncResult.Ok(pushed, pulled)
    }

    // ---------- push ----------

    private fun pushQueue(ctx: Context, token: String): Int {
        val items = CommandHistoryStore.pending(ctx)
        if (items.isEmpty()) return 0
        val arr = JSONArray()
        for ((k, v) in items) {
            arr.put(JSONObject().put("key", k.toString()).put("value", v).put("updated_at", k))
        }
        val body = JSONObject().put("bucket", CommandHistoryStore.BUCKET).put("items", arr)
        val res = http(
            url = "${AuthConfig.BACKEND_BASE}/sync/push",
            method = "POST",
            token = token,
            body = body.toString().toByteArray(Charsets.UTF_8),
        )
        res.use { c ->
            val code = c.responseCode
            val text = c.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code !in 200..299) throw IllegalStateException("push HTTP $code${if (text.isNotBlank()) ": $text" else ""}")
        }
        CommandHistoryStore.dropSynced(ctx, items.size)
        return items.size
    }

    // ---------- pull ----------

    private fun pullNew(ctx: Context, token: String): Int {
        val since = ctx.getSharedPreferences("mox_sync", Context.MODE_PRIVATE)
            .getLong(PULL_SINCE_KEY, 0L)
        val res = http(
            url = "${AuthConfig.BACKEND_BASE}/sync/pull?bucket=${CommandHistoryStore.BUCKET}&since=$since&limit=200",
            method = "GET",
            token = token,
        )
        res.use { c ->
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("pull HTTP $code")
            val json = JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
            if (!json.optBoolean("ok")) throw IllegalStateException("pull 响应异常")
            val items = CommandHistoryStore.fromJson(json.optJSONArray("items") ?: JSONArray())
            // 下次起点：本批最大 updated_at（服务端按升序返回）
            val maxTs = items.maxOfOrNull { it.first } ?: since
            if (maxTs > since) {
                ctx.getSharedPreferences("mox_sync", Context.MODE_PRIVATE)
                    .edit().putLong(PULL_SINCE_KEY, maxTs).apply()
            }
            return items.size
        }
    }

    // ---------- HTTP 基础 ----------

    private fun http(url: String, method: String, token: String, body: ByteArray? = null): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
        }
        if (body != null) conn.outputStream.use { it.write(body) }
        return conn
    }
}

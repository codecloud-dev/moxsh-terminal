package com.moxsh.cloud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 命令历史本地队列（云同步 MVP 的离线缓冲）。
 *
 * 设计：
 *  - JSONL 追加文件（filesDir/cloud_sync_queue.jsonl），一行一条：
 *    `{"k":<捕获时间ms>,"v":"<命令行>"}`，k 同时作为云端 sync_objects 的 key
 *    （时间戳天然唯一且可排序，拉取侧按 k 去重）。
 *  - 只在「已登录 + 用户开启云同步」时由 [ExecutionEngine.inputRecorder] 喂入；
 *    上传成功后整批出队（MVP 语义：队列=未同步，云端=全量）。
 *  - 上限 [MAX_PENDING] 条（防失控增长，丢最旧）；控制序列（ESC）/空行不入队。
 *
 * 隐私边界：命令行按原文捕获（含参数）。敏感场景（输密码）建议用户关闭开关；
 * 云端仅用户本人 JWT 可读写，传输走 https。
 */
object CommandHistoryStore {

    private const val FILE_NAME = "cloud_sync_queue.jsonl"
    private const val MAX_PENDING = 500

    /** 云端 bucket 约定（与 mox-id 的 sync 端点一致）。 */
    const val BUCKET = "termux:history"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /**
     * 从 PTY 原始输入中提取命令行并入队。
     * 规则：按 '\n' 切分，取每个完整行；丢弃空行与含 ESC（\u001b）的控制序列片段
     * （方向键/补全产生的转义串不是"命令"）；单行上限 2000 字符截断。
     */
    fun onInput(ctx: Context, data: ByteArray) {
        val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        var dirty = false
        for (rawLine in text.split('\n')) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            if (line.contains('\u001b')) continue // 控制序列片段（方向键/Tab 补全等）
            if (line.startsWith("exit")) continue // 同步退出指令无回放价值
            append(ctx, now, line.take(2000))
            dirty = true
        }
        if (dirty) trimIfNeeded(ctx)
    }

    /** 追加一条；写入失败静默忽略（同步缓冲不阻塞终端输入路径）。 */
    private fun append(ctx: Context, ts: Long, line: String) {
        runCatching {
            val obj = JSONObject().put("k", ts).put("v", line)
            file(ctx).appendText(obj.toString() + "\n")
        }
    }

    /** 读取全部待同步条目（k 升序）。 */
    fun pending(ctx: Context): List<Pair<Long, String>> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().filter { it.isNotBlank() }.mapNotNull { l ->
                runCatching {
                    val o = JSONObject(l)
                    o.getLong("k") to o.getString("v")
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /** 上传成功后丢弃队首 [count] 条（重写文件）。 */
    fun dropSynced(ctx: Context, count: Int) {
        val f = file(ctx)
        if (!f.exists() || count <= 0) return
        runCatching {
            val rest = f.readLines().filter { it.isNotBlank() }.drop(count)
            f.writeText(rest.joinToString("\n") { it } + if (rest.isEmpty()) "" else "\n")
        }
    }

    /** 超上限丢最旧。 */
    private fun trimIfNeeded(ctx: Context) {
        val f = file(ctx)
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.size > MAX_PENDING) {
                f.writeText(lines.takeLast(MAX_PENDING).joinToString("\n") { it } + "\n")
            }
        }
    }

    /** 从云端拉回的历史（k 升序去重合并用）。 */
    fun fromJson(items: JSONArray): List<Pair<Long, String>> = buildList {
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            add(o.optLong("updated_at") to (o.optString("value") ?: ""))
        }
    }.filter { it.second.isNotBlank() }
}

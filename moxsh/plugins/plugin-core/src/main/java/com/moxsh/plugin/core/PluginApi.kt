package com.moxsh.plugin.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * moxsh 插件 API v2 —— 插件可调用的主 app 能力总门面。
 *
 * 由 [PluginHost.createApi] 构造，每个插件实例持有自己的 [PluginApi]，
 * 构造时传入该插件声明的权限集合，各域入口处统一校验（fail-fast 抛
 * [PluginPermissionDenied]）。
 *
 * 能力域一览（与 docs/plugins.md §插件 API v2 同步）：
 *  - [sessions]：多会话管理（创建/关闭/列表/写入/改尺寸/屏幕快照/输出订阅）
 *  - [ui]：Toast / 通知 / 震动 / 剪贴板
 *  - [fs]：插件私有目录 + 导出到公共 Download（MediaStore，无需存储权限）
 *  - [net]：受控 HTTP（超时 15s，响应上限 5 MB）
 *  - [store]：插件私有 KV 存储
 *  - [events]：全局事件订阅
 *  - [skills]：向 AI 助手注册技能工具
 */
class PluginApi internal constructor(
    val pluginId: String,
    private val host: PluginHost,
    private val context: Context,
    private val permissions: Set<String>,
) {

    private val main = Handler(Looper.getMainLooper())

    /** 权限校验：不满足即抛 [PluginPermissionDenied]。 */
    private fun require(permission: String) {
        if (permission !in permissions) throw PluginPermissionDenied(permission)
    }

    // ── sessions：多会话管理 ────────────────────────────────────────────────

    /** 终端会话能力（权限：manage_sessions；读屏幕另需 read_screen）。 */
    val sessions: SessionsApi = SessionsApi()

    inner class SessionsApi internal constructor() {

        /** 创建新终端会话，返回句柄 id（-1 表示内核打开失败）。 */
        fun create(command: String = host.engine.DEFAULT_SHELL, cols: Int = 80, rows: Int = 24): Long {
            require(PluginPermissions.MANAGE_SESSIONS)
            val id = host.engine.createSession(command, cols, rows)
            if (id > 0) host.events.publish(PluginEvent.SessionOpened(id))
            return id
        }

        /** 关闭会话。 */
        fun close(id: Long) {
            require(PluginPermissions.MANAGE_SESSIONS)
            host.engine.destroySession(id)
            host.events.publish(PluginEvent.SessionClosed(id))
        }

        /** 全部存活会话 id。 */
        fun list(): List<Long> {
            require(PluginPermissions.MANAGE_SESSIONS)
            return host.engine.listSessions()
        }

        /** 向会话写入输入（命令文本或按键字节）。 */
        fun write(id: Long, text: String) {
            require(PluginPermissions.RUN_COMMAND)
            host.engine.write(id, text.toByteArray(Charsets.UTF_8))
        }

        /** 便捷方法：写入一行命令（自动补 \n 回车）。 */
        fun runLine(id: Long, command: String) {
            write(id, "$command\n")
            host.events.publish(PluginEvent.CommandExecuted(id, command))
        }

        /** 更新会话窗口尺寸。 */
        fun resize(id: Long, cols: Int, rows: Int) {
            require(PluginPermissions.MANAGE_SESSIONS)
            host.engine.resize(id, cols, rows)
        }

        /**
         * 当前屏 + 回滚缓冲的纯文本快照（每行一个元素，不含 ANSI 序列）。
         * 需要 read_screen 权限。内核单元布局见 TerminalCore.CELL_SIZE。
         */
        fun screenDump(id: Long, fromRow: Int = 0, count: Int = Int.MAX_VALUE): List<String> {
            require(PluginPermissions.READ_SCREEN)
            val cols = host.engine.cols(id)
            if (cols <= 0) return emptyList()
            val total = host.engine.totalRows(id)
            if (total <= 0) return emptyList()
            val start = fromRow.coerceIn(0, total - 1)
            val rows = count.coerceAtMost(total - start)
            val cellBytes = rows * cols * CELL_SIZE
            val buf = ByteArray(cellBytes)
            val n = host.engine.copyCells(id, start, rows, buf)
            if (n <= 0) return emptyList()
            val out = ArrayList<String>(rows)
            for (r in 0 until rows) {
                val rowBytes = buf.copyOfRange(r * cols * CELL_SIZE, (r + 1) * cols * CELL_SIZE)
                out.add(decodeRow(rowBytes))
            }
            return out
        }

        /**
         * 订阅指定会话（或全部会话，id=-1）的输出事件。
         * 返回取消订阅句柄。需要 subscribe_events 权限。
         */
        fun onOutput(sessionId: Long = -1, listener: (Long) -> Unit): EventListenerToken {
            require(PluginPermissions.SUBSCRIBE_EVENTS)
            return host.events.register(PluginEvent.OutputProduced::class.java) { e ->
                if (sessionId == -1L || e.sessionId == sessionId) listener(e.sessionId)
            }
        }

        /** 把会话的持续输出泵起来（驱动 VT 解析 + 发布 OutputProduced 事件）。 */
        fun startPump(id: Long) {
            require(PluginPermissions.MANAGE_SESSIONS)
            host.engine.startPump(id) { n ->
                host.events.publish(PluginEvent.OutputProduced(id, n))
            }
        }
    }

    // ── ui：用户反馈 ────────────────────────────────────────────────────────

    /** 用户界面反馈能力（Toast / 通知 / 震动 / 剪贴板）。 */
    val ui: UiApi = UiApi()

    inner class UiApi internal constructor() {

        /** 显示玻璃风格 Toast（主线程安全，任意线程可调）。 */
        fun toast(text: String, long: Boolean = false) {
            require(PluginPermissions.POST_NOTIFICATION)
            main.post {
                Toast.makeText(
                    context, text,
                    if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
            }
        }

        /** 发一条系统通知（渠道 "moxsh-plugin"，内容为插件 id + 标题/正文）。 */
        fun notify(title: String, body: String, notificationId: Int = pluginId.hashCode()) {
            require(PluginPermissions.POST_NOTIFICATION)
            main.post {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                    val channel = android.app.NotificationChannel(
                        CHANNEL_ID, "moxsh 插件通知",
                        android.app.NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    nm.createNotificationChannel(channel)
                    val intent = context.packageManager
                        .getLaunchIntentForPackage(context.packageName)
                    val pi = android.app.PendingIntent.getActivity(
                        context, 0, intent,
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                    val n = android.app.Notification.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle("[$pluginId] $title")
                        .setContentText(body)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                    nm.notify(notificationId, n.build())
                }.onFailure {
                    // API 33+ 未授予 POST_NOTIFICATIONS 运行时权限时静默降级为 Toast
                    toast("$title：$body")
                }
            }
        }

        /** 触感反馈。@param ms 震动时长毫秒（上限 1000）。 */
        fun vibrate(ms: Long = 30) {
            require(PluginPermissions.VIBRATE)
            main.post {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms.coerceAtMost(1000), VibrationEffect.DEFAULT_AMPLITUDE))
                }
            }
        }

        /** 写系统剪贴板。 */
        fun copyToClipboard(text: String, label: String = pluginId) {
            require(PluginPermissions.CLIPBOARD)
            main.post {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, text))
            }
        }

        /** 读系统剪贴板（可能为空串）。 */
        fun readClipboard(): String {
            require(PluginPermissions.CLIPBOARD)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        }
    }

    // ── fs：文件 ────────────────────────────────────────────────────────────

    /** 文件能力（权限：storage）。私有目录随意读写；导出 Download 走 MediaStore。 */
    val fs: FsApi = FsApi()

    inner class FsApi internal constructor() {

        /** 插件私有目录（/data/data/com.moxsh/files/plugins/<id>/，随卸载删除）。 */
        fun privateDir(): File {
            require(PluginPermissions.STORAGE)
            return File(context.filesDir, "plugins/$pluginId").apply { mkdirs() }
        }

        /** 写私有文件（相对路径，自动建父目录）。 */
        fun writePrivate(relPath: String, content: ByteArray): File {
            require(PluginPermissions.STORAGE)
            val f = File(privateDir(), relPath)
            f.parentFile?.mkdirs()
            f.writeBytes(content)
            return f
        }

        /** 读私有文件；不存在返回 null。 */
        fun readPrivate(relPath: String): ByteArray? {
            require(PluginPermissions.STORAGE)
            val f = File(privateDir(), relPath)
            return if (f.exists()) f.readBytes() else null
        }

        /** 列私有目录内容。 */
        fun listPrivate(relPath: String = "."): List<File> {
            require(PluginPermissions.STORAGE)
            return File(privateDir(), relPath).listFiles()?.toList().orEmpty()
        }

        /**
         * 把文件导出到公共 Download/moxsh/ 目录（经 MediaStore，**无需存储权限**）。
         * 仅 Android 10+ 支持；9 及以下返回 null 并建议改用私有目录 + 分享。
         */
        fun exportToDownloads(fileName: String, content: ByteArray): Uri? {
            require(PluginPermissions.STORAGE)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return runCatching {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(fileName))
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/moxsh")
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(content) }
                uri
            }.getOrNull()
        }
    }

    // ── net：受控 HTTP ──────────────────────────────────────────────────────

    /** 网络能力（权限：network）。GET/POST 封装，固定 15s 超时、5 MB 响应上限。 */
    val net: NetApi = NetApi()

    /** HTTP 响应（Kotlin 规则：data class 不能嵌套在 inner class 内，故提升到外层）。 */
    data class HttpResponse(val status: Int, val body: ByteArray, val contentType: String?) {
        /** 按 UTF-8 解码响应体。 */
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    inner class NetApi internal constructor() {

        /** 同步 GET（勿在主线程调用；建议配合 events/onOutput 的后台线程）。 */
        fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
            require(PluginPermissions.NETWORK)
            return request("GET", url, null, headers)
        }

        /** 同步 POST（body 原样发送；勿在主线程调用）。 */
        fun post(url: String, body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse {
            require(PluginPermissions.NETWORK)
            return request("POST", url, body, headers)
        }

        private fun request(method: String, url: String, body: ByteArray?, headers: Map<String, String>): HttpResponse {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
            }
            return try {
                conn.connect()
                if (body != null) conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { readLimited(it, MAX_RESPONSE_BYTES + 1) } ?: ByteArray(0)
                require(bytes.size <= MAX_RESPONSE_BYTES) { "响应超过 5 MB 上限" }
                HttpResponse(code, bytes, conn.contentType)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 限量读流：最多 max 字节（防恶意大响应占内存；readBytes(estimated) 已被 Kotlin 弃用为 error）。 */
    private fun readLimited(input: java.io.InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
        val buf = ByteArray(32 * 1024)
        var total = 0
        while (total < max) {
            val n = input.read(buf, 0, minOf(buf.size, max - total))
            if (n == -1) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    // ── store：插件 KV ──────────────────────────────────────────────────────

    /** 插件私有 KV 存储（权限：storage）。落盘 Properties，随插件卸载删除。 */
    val store: StorageApi = StorageApi()

    inner class StorageApi internal constructor() {
        // 直接按插件私有目录构造（复用 FsApi 的目录契约，但不触发 fs 权限——
        // storage 与 fs 是相互独立的权限域）
        private val file: File by lazy {
            File(File(context.filesDir, "plugins/$pluginId").apply { mkdirs() }, "store.properties")
        }
        private val props = java.util.Properties()

        init {
            runCatching {
                if (file.exists()) file.inputStream().use { props.load(it) }
            }
        }

        /** 读字符串值，缺省 [def]。 */
        fun get(key: String, def: String = ""): String = props.getProperty(key, def)

        /** 写字符串值并立即落盘。 */
        fun put(key: String, value: String) {
            require(PluginPermissions.STORAGE)
            props[key] = value
            file.outputStream().use { props.store(it, "moxsh plugin store: $pluginId") }
        }

        /** 删除键。 */
        fun remove(key: String) {
            require(PluginPermissions.STORAGE)
            props.remove(key)
            file.outputStream().use { props.store(it, "moxsh plugin store: $pluginId") }
        }

        /** 全部键。 */
        fun keys(): List<String> = props.keys.map { it.toString() }
    }

    // ── events：全局事件 ────────────────────────────────────────────────────

    /** 事件订阅（权限：subscribe_events）。回调一律主线程。 */
    val events: EventsApi = EventsApi()

    inner class EventsApi internal constructor() {
        private val bus: PluginEventBus get() = host.events

        /** 订阅"命令已执行"。 */
        fun onCommand(listener: (sessionId: Long, command: String) -> Unit): EventListenerToken {
            require(PluginPermissions.SUBSCRIBE_EVENTS)
            return bus.register(PluginEvent.CommandExecuted::class.java) { e ->
                (e as? PluginEvent.CommandExecuted)?.let { listener(it.sessionId, it.command) }
            }
        }

        /** 订阅"会话开启"。 */
        fun onSessionOpen(listener: (sessionId: Long) -> Unit): EventListenerToken {
            require(PluginPermissions.SUBSCRIBE_EVENTS)
            return bus.register(PluginEvent.SessionOpened::class.java) { listener(it.sessionId) }
        }

        /** 订阅"会话关闭"。 */
        fun onSessionClose(listener: (sessionId: Long) -> Unit): EventListenerToken {
            require(PluginPermissions.SUBSCRIBE_EVENTS)
            return bus.register(PluginEvent.SessionClosed::class.java) { listener(it.sessionId) }
        }

        /** 订阅"运行环境就绪（bootstrap 完成）"。 */
        fun onBoot(listener: () -> Unit): EventListenerToken {
            require(PluginPermissions.SUBSCRIBE_EVENTS)
            return bus.register(PluginEvent.BootCompleted::class.java) { listener() }
        }

        /** 取消订阅。 */
        fun off(token: EventListenerToken) = bus.unregister(token)
    }

    // ── skills：AI 技能注册 ─────────────────────────────────────────────────

    /** AI 技能注册（权限：register_skill）。注册的工具出现在 AI 助手的工具列表。 */
    val skills: SkillsApi = SkillsApi()

    inner class SkillsApi internal constructor() {

        /** 注册技能（name 自动加 "pluginId." 前缀防冲突）。 */
        fun register(name: String, description: String, parametersSchema: String = "{}", handler: (Map<String, String>) -> String) {
            require(PluginPermissions.REGISTER_SKILL)
            SkillRegistry.register(
                SkillRegistry.SkillSpec("$pluginId.$name", description, parametersSchema, handler)
            )
        }

        /** 摘除本插件注册的全部技能。 */
        fun unregisterAll() = SkillRegistry.unregisterAllOf(pluginId)
    }

    /** 插件卸载时由宿主调用：摘技能、（事件句柄随宿主清理）。 */
    internal fun onUnload() {
        runCatching { SkillRegistry.unregisterAllOf(pluginId) }
    }

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private fun decodeRow(rowBytes: ByteArray): String {
        // 单元布局与 Rust Cell #[repr(C)] / TerminalCore.CELL_SIZE(=16) 一致：
        // code:u32 @0 | fg:u32 @4 | bg:u32 @8 | attrs:u16 @12 | 2 字节对齐填充。
        val sb = StringBuilder(rowBytes.size / CELL_SIZE)
        var i = 0
        while (i + CELL_SIZE <= rowBytes.size) {
            val cp = (rowBytes[i].toInt() and 0xFF) or
                ((rowBytes[i + 1].toInt() and 0xFF) shl 8) or
                ((rowBytes[i + 2].toInt() and 0xFF) shl 16) or
                ((rowBytes[i + 3].toInt() and 0xFF) shl 24)
            if (cp == 0) sb.append(' ') else sb.appendCodePoint(cp)
            i += CELL_SIZE
        }
        return sb.toString().trimEnd()
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "tar" -> "application/x-tar"
        "mox" -> "application/octet-stream"
        "zip" -> "application/zip"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    companion object {
        /** 与 Rust Cell #[repr(C)] 对齐布局一致（u32+u32+u32+u16+pad = 16 字节），同步 TerminalCore.CELL_SIZE。 */
        const val CELL_SIZE = 16

        /** HTTP 响应上限 5 MB。 */
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024

        private const val CHANNEL_ID = "moxsh-plugin"
    }
}

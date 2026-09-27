package com.moxsh.shared

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Bootstrap 安装器 —— moxsh 首次启动的运行环境落地（任务 M5）。
 *
 * 职责（对应 roadmap 4.5 / D1-D3 决策）：
 *  1. 检测 `$PREFIX`（/data/data/com.moxsh/files/usr）是否已初始化；
 *  2. 下载 moxsh 官方 bootstrap rootfs tar（*国内 CDN 优先，官方源回退*）；
 *  3. SHA-256 校验（防传输损坏 / 篡改）；
 *  4. 解压到 $PREFIX（tar.gz，自实现 tar 头解析，零第三方依赖）；
 *  5. 首次初始化：写 dns（resolv.conf）、apt 风格 sources 模板（清华源默认，D8 国内优化）、
 *     执行一次性脚本标记（scripts.run_once）。
 *
 * ⚠️ bootstrap 产物由 moxsh 云端 CI 构建（见 docs/roadmap.md M6；构建脚本产出
 *    busybox 类工具链 + moxsh 自研包管理 CLI + 目录骨架）。URL 常量在 CI 上线后替换，
 *    当前结构完全按契约写好，CI 一出包即可用。
 *
 * 小白体验：全程玻璃进度卡回调（阶段文案），用户不需要知道 $PREFIX 的存在。
 */
object BootstrapInstaller {

    /** moxsh 运行环境根目录（$PREFIX）。与 Termux 磁盘契约对齐（D3），保证原 .deb 可装。 */
    const val PREFIX_SUBPATH = "files/usr"

    /** 环境就绪标记文件。存在且可读 = 已安装。 */
    private const val READY_MARKER = "files/usr/READY"

    // ── 下载源（CI 产出后替换真实地址；sha256 随版本发布页提供） ──────────────
    // 国内 CDN（优先）：moxsh 官方国内分发节点
    // 官方回退：GitHub Releases（走系统直连，失败则提示用户检查网络）

    /**
     * 下载源定义。
     * @param label   玻璃进度卡上展示的中文源名
     * @param url     bootstrap tar.gz 地址（占位，CI 上线后替换）
     * @param sha256  期望摘要（hex，空串 = 跳过校验——仅本地调试用）
     */
    data class BootstrapSource(val label: String, val url: String, val sha256: String)

    /** 当前版本的 bootstrap 源列表（顺序 = 优先级，第一个为国内 CDN）。 */
    val SOURCES: List<BootstrapSource> = listOf(
        BootstrapSource(
            label = "moxsh 国内 CDN",
            // TODO(CI): 替换为真实 bootstrap 产物地址（M6 云端出包后填入）
            url = "https://cdn.moxsh.example/bootstrap/bootstrap-aarch64.tar.gz",
            sha256 = "",
        ),
        BootstrapSource(
            label = "moxsh 官方源",
            // TODO(CI): GitHub Releases 回退地址
            url = "https://github.com/moxsh/moxsh-bootstrap/releases/latest/download/bootstrap-aarch64.tar.gz",
            sha256 = "",
        ),
    )

    /**
     * 安装进度回调。
     * @param stage    阶段码：0=检测 1=下载 2=校验 3=解压 4=初始化 5=完成 -1=失败
     * @param progress 0-100（仅下载/解压阶段有效）
     * @param message  中文阶段文案（直接展示给小白）
     */
    fun interface ProgressListener {
        fun onProgress(stage: Int, progress: Int, message: String)
    }

    /** 运行环境是否已就绪。 */
    fun isReady(ctx: Context): Boolean =
        File(ctx.filesDir, READY_MARKER).let { it.exists() && it.length() > 0 }

    /**
     * 一键安装运行环境（挂起函数，IO 线程执行）。
     * 依序尝试 [SOURCES]：下载成功且校验通过即停止；全部失败抛 [BootstrapException]。
     *
     * @param listener 玻璃进度卡回调（UI 线程外的普通回调，UI 层自行 post）
     */
    suspend fun install(ctx: Context, listener: ProgressListener): Unit =
        withContext(Dispatchers.IO) {
            val prefix = File(ctx.filesDir, PREFIX_SUBPATH)
            if (isReady(ctx)) {
                listener.onProgress(5, 100, "运行环境已就绪，无需安装")
                return@withContext
            }

            // ── 阶段 1：下载（国内 CDN 优先，逐源回退） ──
            var tarFile: File? = null
            var lastError: Exception? = null
            for ((index, source) in SOURCES.withIndex()) {
                try {
                    listener.onProgress(1, 0, "正在下载运行环境（${source.label}）…")
                    tarFile = download(ctx, source.url) { p ->
                        listener.onProgress(1, p, "下载中 $p%（${source.label}）")
                    }
                    // ── 阶段 2：SHA-256 校验 ──
                    listener.onProgress(2, 0, "校验文件完整性…")
                    if (source.sha256.isNotEmpty()) {
                        val actual = sha256Hex(tarFile)
                        if (!actual.equals(source.sha256, ignoreCase = true)) {
                            tarFile.delete()
                            throw BootstrapException("校验失败（${source.label}）：文件可能损坏，已自动换源重试")
                        }
                    }
                    break // 下载+校验通过
                } catch (e: Exception) {
                    lastError = e
                    tarFile?.delete()
                    tarFile = null
                    if (index < SOURCES.lastIndex) {
                        listener.onProgress(1, 0, "${source.label} 不可用，正在切换下一源…")
                    }
                }
            }
            val tar = tarFile ?: throw BootstrapException(
                "所有下载源均失败：${lastError?.message ?: "未知错误"}。请检查网络后重试。",
            )

            try {
                // ── 阶段 3：解压 tar.gz 到 $PREFIX ──
                listener.onProgress(3, 0, "解压运行环境…")
                prefix.mkdirs()
                extractTarGz(tar, prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }

                // ── 阶段 4：首次初始化（国内优化，D8） ──
                listener.onProgress(4, 0, "初始化配置…")
                initialize(ctx, prefix)
                File(ctx.filesDir, READY_MARKER).writeText("ok")

                listener.onProgress(5, 100, "安装完成，欢迎来到 moxsh！")
            } finally {
                tar.delete() // 下载缓存用完即删，不占空间
            }
        }

    /** 从本地 tar.gz 导入（离线安装兜底：小白用文件管理器把 bootstrap 包放 Download 后一键导入）。 */
    suspend fun installFromLocal(ctx: Context, tarPath: String, listener: ProgressListener): Unit =
        withContext(Dispatchers.IO) {
            val prefix = File(ctx.filesDir, PREFIX_SUBPATH)
            listener.onProgress(3, 0, "从本地包解压…")
            prefix.mkdirs()
            extractTarGz(File(tarPath), prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }
            listener.onProgress(4, 0, "初始化配置…")
            initialize(ctx, prefix)
            File(ctx.filesDir, READY_MARKER).writeText("ok")
            listener.onProgress(5, 100, "导入完成！")
        }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /** 下载远程文件到缓存目录，回调 0-100 进度。 */
    private fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "bootstrap.tar.gz")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "moxsh-bootstrap/1.0")
        if (conn.responseCode !in 200..299) {
            throw BootstrapException("HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(out).use { fos ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var done = 0L
                while (input.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                    done += read
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        return out
    }

    /** 计算文件 SHA-256 的 hex 字符串（流式，不占额外内存）。 */
    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 解压 tar.gz 到目标目录（自实现 ustar 头解析，零依赖）。
     * 安全：拒绝绝对路径与 `..` 逃逸（对齐 .mox 包解压白名单思路）。
     */
    private fun extractTarGz(tar: File, dest: File, onProgress: (Int) -> Unit) {
        GZIPInputStream(FileInputStream(tar), 64 * 1024).use { gz ->
            val header = ByteArray(512)
            var entries = 0
            // 先数一遍条目总数用于进度（tar 需顺序流读，两遍扫描代价可接受）
            // 简化：进度按已读字节/文件大小估算，避免二次 IO。
            val totalBytes = tar.length()
            var consumedApprox = 0L

            while (true) {
                if (!readFully(gz, header)) break
                val name = parseTarName(header, 0, 100).trim('/')
                val sizeField = String(header, 124, 12, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
                if (sizeField.isEmpty()) break
                val size = sizeField.toLong(8)
                val typeFlag = header[156]

                if (name.isNotEmpty() && (typeFlag == '0'.code.toByte() || typeFlag == 0.toByte())) {
                    // 普通文件：安全校验 + 落盘
                    if (!isSafePath(name)) {
                        // 跳过危险条目，不中断整体安装（与 .mox 解包策略一致）
                        skipFully(gz, size)
                        continue
                    }
                    val outFile = File(dest, name)
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = size
                        while (remaining > 0) {
                            val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n == -1) break
                            fos.write(buf, 0, n)
                            remaining -= n
                        }
                    }
                    // 恢复可执行位（bootstrap 内 bin/ 脚本需要；tar mode 字段简化处理）
                    if (name.startsWith("bin/") || name.startsWith("usr/bin/")) {
                        outFile.setExecutable(true, false)
                    }
                    entries++
                } else {
                    skipFully(gz, size)
                }
                // 512 对齐的填充
                val pad = ((size + 511) / 512 * 512 - size).toInt()
                if (pad > 0) skipFully(gz, pad.toLong())
                consumedApprox += 512 + size + pad
                if (totalBytes > 0) {
                    onProgress((consumedApprox * 100 / totalBytes).toInt().coerceIn(0, 99))
                }
            }
        }
    }

    /** 首次初始化：DNS、包源模板（清华默认，D8 国内优化）、一次性脚本占位。 */
    private fun initialize(ctx: Context, prefix: File) {
        // 1. DNS（安卓上 /etc 不可写，用 resolv.conf 常规兜底；proot 发行版内另有处理）
        val etc = File(prefix, "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText(
            "# moxsh 自动生成（国内 DNS，D8）\n" +
                "nameserver 223.5.5.5\n" +
                "nameserver 119.29.29.29\n",
        )

        // 2. 包源模板：moxsh 自研包管理 CLI 读取（mox 格式仓库，国内 CDN 置顶）
        File(etc, "moxsh-sources.list").writeText(
            "# moxsh 包源（自研 mox 格式仓库，按优先级排序）\n" +
                "# 1) moxsh 国内 CDN（默认）\n" +
                "# 2) moxsh 官方源（回退）\n" +
                "# TODO(CI): 填入真实仓库地址（M6）\n",
        )

        // 3. 兼容层提示：apt 风格 sources（发行版在 proot 容器内用容器自身的源，与这里无关）
        File(etc, "apt-sources.readme").writeText(
            "proot 发行版内的 apt 源在发行版内部管理（图形包管理器可一键切换清华/中科大）。\n",
        )

        // 4. 一次性初始化标记目录（首次启动引导向导会消费）
        File(prefix, ".moxsh").apply { mkdirs() }
        File(prefix, ".moxsh/first-boot").writeText("pending")
    }

    // ── tar 解析小工具 ──────────────────────────────────────────────────────

    /** 读满 buf.length 字节，EOF 返回 false。 */
    private fun readFully(gz: java.io.InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = gz.read(buf, off, buf.size - off)
            if (n == -1) return off == buf.size && buf.any { it != 0.toByte() } // 全零块=结尾
            off += n
        }
        // 全零头 = tar 结束标记
        return buf.any { it != 0.toByte() }
    }

    /** ustar 文件名解析（name + 可选 prefix 字段拼接）。 */
    private fun parseTarName(header: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && header[end] != 0.toByte()) end++
        var name = String(header, off, end - off, Charsets.US_ASCII)
        // ustar prefix（155 字节偏移，若 magic 为 "ustar\0"）
        val magicOk = header[257] == 'u'.code.toByte() && header[258] == 's'.code.toByte()
        if (magicOk && header[345] != 0.toByte()) {
            var pEnd = 345
            while (pEnd < 345 + 100 && header[pEnd] != 0.toByte()) pEnd++
            name = String(header, 345, pEnd - 345, Charsets.US_ASCII) + "/" + name
        }
        return name
    }

    /** 跳过 n 字节。 */
    private fun skipFully(gz: java.io.InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n2 = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n2 == -1) break
            remaining -= n2
        }
    }

    /** 路径安全校验：拒绝绝对路径 / `..` 逃逸。 */
    private fun isSafePath(name: String): Boolean =
        !name.startsWith("/") && !name.split("/").any { it == ".." }

    /** bootstrap 安装失败异常（文案已中文化，可直接展示）。 */
    class BootstrapException(message: String) : Exception(message)
}

/**
 * 运行环境就绪状态机（供 UI 层观察，如小白引导向导 / 首页玻璃进度卡）。
 *
 * 流程：`isReady? → BootstrapInstaller.install → CompatShim.ensurePrefixLayout → Ready`。
 * 用 [kotlinx.coroutines.flow.MutableStateFlow] 承载，UI 侧 collect 渲染玻璃进度。
 */
object BootstrapState {

    /** 就绪状态。 */
    sealed class State {
        /** 未开始（冷启动初始）。 */
        data object Idle : State()

        /** 进行中（stage/progress/文案见 [BootstrapInstaller.ProgressListener] 约定）。 */
        data class Running(val stage: Int, val progress: Int, val message: String) : State()

        /** 就绪。 */
        data object Ready : State()

        /** 失败（中文文案可直接展示）。 */
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** 只读状态流（UI collect）。 */
    val state: StateFlow<State> = _state

    /**
     * 一键就绪流水线：已就绪直接 Ready；否则安装 bootstrap → 建立 $PREFIX 布局。
     * 幂等：并发调用时后到者直接等待当前状态（简化处理：Application 只启动一次）。
     */
    suspend fun begin(ctx: Context) {
        if (_state.value is State.Ready) return
        if (BootstrapInstaller.isReady(ctx)) {
            CompatShim.ensurePrefixLayout()
            _state.value = State.Ready
            return
        }
        try {
            BootstrapInstaller.install(ctx) { stage, progress, message ->
                _state.value = State.Running(stage, progress, message)
            }
            CompatShim.ensurePrefixLayout()
            _state.value = State.Ready
        } catch (e: BootstrapInstaller.BootstrapException) {
            _state.value = State.Failed(e.message ?: "安装失败")
        } catch (e: Exception) {
            _state.value = State.Failed("安装失败：${e.message ?: "未知错误"}")
        }
    }
}

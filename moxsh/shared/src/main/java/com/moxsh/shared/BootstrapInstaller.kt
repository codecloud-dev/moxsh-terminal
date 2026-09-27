package com.moxsh.shared

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bootstrap 安装器 —— moxsh 首次启动的运行环境落地（任务 M5）。
 *
 * 职责（对应 roadmap 4.5 / D1-D3 决策）：
 *  1. 检测 `$PREFIX`（/data/data/com.moxsh/files/usr）是否已初始化；
 *  2. 下载 Termux 官方 bootstrap ZIP（按设备 ABI 自动选包；官方直连 + 国内加速双源回退）；
 *  3. SHA-256 校验（官方 Release digest 核实，防传输损坏 / 篡改）；
 *  4. 解压到 $PREFIX（ZIP + SYMLINKS.txt 符号链接重建，格式契约见 [extractZip]；
 *     tar.gz 路径保留给 .mox 生态 rootfs 包，自实现 tar 头解析零依赖）；
 *  5. 首次初始化：写 dns（resolv.conf）、包源模板（D8 国内优化）、一次性脚本标记。
 *
 * 小白体验：全程玻璃进度卡回调（阶段文案），用户不需要知道 $PREFIX 的存在。
 */
object BootstrapInstaller {

    /** moxsh 运行环境根目录（$PREFIX）。与 Termux 磁盘契约对齐（D3），保证原 .deb 可装。 */
    const val PREFIX_SUBPATH = "files/usr"

    /** 环境就绪标记文件。存在且可读 = 已安装。 */
    private const val READY_MARKER = "files/usr/READY"

    // ── 下载源（真实可用链：Termux 官方 bootstrap 产物，与 proot-distro 生态一致） ──
    // 兼容性说明：moxsh 与 Termux 包生态完全兼容（D7），直接采用 termux-packages
    // 社区持续构建的 bootstrap 产物作为运行环境底座（构建脚本 GPL 开源、产物公开
    // 分发，moxsh 代码零复用）。moxsh 自有 CDN 上线后置于列表首位即可。

    /** bootstrap 包格式。 */
    enum class Format { ZIP, TAR_GZ }

    /**
     * 下载源定义。
     * @param label   玻璃进度卡上展示的中文源名
     * @param url     bootstrap 包地址
     * @param sha256  期望摘要（hex，空串 = 跳过校验——仅本地调试用）
     * @param format  包格式（Termux 官方为 ZIP，内含 usr/ 与 SYMLINKS.txt）
     * @param abis    该源适用的设备 ABI（空 = 全部适用）
     */
    data class BootstrapSource(
        val label: String,
        val url: String,
        val sha256: String,
        val format: Format = Format.ZIP,
        val abis: Set<String> = emptySet(),
    )

    /** Termux 官方 bootstrap 版本（升级时同步更新 URL 与 sha256）。 */
    private const val TERMUX_BOOTSTRAP_TAG = "bootstrap-2026.09.20-r1%2Bapt.android-7"

    /**
     * 各 bootstrap 架构包的官方 sha256（GitHub Release 资产 digest 逐个核实，
     * `gh api repos/termux/termux-packages/releases/tags/<tag>` 可复核）。
     */
    private val TERMUX_SHA256 = mapOf(
        "aarch64" to "65ba578133ea2f4e5cc07234568815397cf9e1236b5da8c06ce6753cf036cc69",
        "arm" to "1c953b1d808c45fd578b7a3b4ba4d6b6f54329a7f6db57dceeab55fe997102e8",
        "i686" to "db0c868c88b8d814e71b7e2d60438c836b903585140f40046d885ce103e789fe",
        "x86_64" to "2d23d45c1a9e72dda2172895c218334473a2d1560e724f4b88323e56e80736ff",
    )

    /** 设备 ABI -> Termux bootstrap 架构名映射（Termux 只按这 4 个架构分发）。 */
    private fun bootstrapAbiOf(deviceAbi: String): String = when (deviceAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a", "armv7l", "armv8l" -> "arm"
        "x86" -> "i686"
        "x86_64" -> "x86_64"
        else -> deviceAbi
    }

    /** 当前版本的 bootstrap 源列表（顺序 = 优先级；abis 为空 = 对已实例化的架构全部适用）。 */
    val SOURCES: List<BootstrapSource> = listOf(
        BootstrapSource(
            label = "Termux 官方源（GitHub 直连）",
            url = "https://github.com/termux/termux-packages/releases/download/$TERMUX_BOOTSTRAP_TAG/bootstrap-%ABI%.zip",
            sha256 = "%SHA256%",
            format = Format.ZIP,
            abis = emptySet(),
        ),
        BootstrapSource(
            label = "Termux 官方源（国内加速）",
            url = "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/$TERMUX_BOOTSTRAP_TAG/bootstrap-%ABI%.zip",
            sha256 = "%SHA256%",
            format = Format.ZIP,
            abis = emptySet(),
        ),
    )

    /** 按设备主 ABI 实例化源列表：替换 %ABI% / %SHA256% 占位，过滤不适用架构。 */
    fun sourcesFor(abi: String): List<BootstrapSource> =
        SOURCES.filter { it.abis.isEmpty() || abi in it.abis }.map { s ->
            s.copy(
                url = s.url.replace("%ABI%", abi),
                sha256 = s.sha256.replace("%SHA256%", TERMUX_SHA256[abi] ?: ""),
            )
        }

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
     * 按设备主 ABI 实例化源列表后依序尝试：下载成功且校验通过即停止；
     * 全部失败抛 [BootstrapException]。
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

            // 按设备主 ABI 选包（arm64-v8a -> aarch64 等映射；Termux 官方按架构分发）
            val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifEmpty { "aarch64" }
            val abi = bootstrapAbiOf(deviceAbi)
            val sources = sourcesFor(abi)

            // ── 阶段 1：下载（官方直连 + 国内加速逐源回退） ──
            var pkgFile: File? = null
            var chosen: BootstrapSource? = null
            var lastError: Exception? = null
            for ((index, source) in sources.withIndex()) {
                try {
                    listener.onProgress(1, 0, "正在下载运行环境（${source.label}）…")
                    pkgFile = download(ctx, source) { p ->
                        listener.onProgress(1, p, "下载中 $p%（${source.label}）")
                    }
                    // ── 阶段 2：SHA-256 校验 ──
                    listener.onProgress(2, 0, "校验文件完整性…")
                    if (source.sha256.isNotEmpty()) {
                        val actual = sha256Hex(pkgFile)
                        if (!actual.equals(source.sha256, ignoreCase = true)) {
                            pkgFile.delete()
                            throw BootstrapException("校验失败（${source.label}）：文件可能损坏，已自动换源重试")
                        }
                    }
                    chosen = source
                    break // 下载+校验通过
                } catch (e: Exception) {
                    lastError = e
                    pkgFile?.delete()
                    pkgFile = null
                    if (index < sources.lastIndex) {
                        listener.onProgress(1, 0, "${source.label} 不可用，正在切换下一源…")
                    }
                }
            }
            val pkg = pkgFile ?: throw BootstrapException(
                "所有下载源均失败：${lastError?.message ?: "未知错误"}。请检查网络后重试。",
            )

            try {
                // ── 阶段 3：按包格式解压到 $PREFIX ──
                listener.onProgress(3, 0, "解压运行环境…")
                prefix.mkdirs()
                when (chosen?.format ?: Format.ZIP) {
                    Format.ZIP -> extractZip(pkg, prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }
                    Format.TAR_GZ -> extractTarGz(pkg, prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }
                }

                // ── 阶段 4：首次初始化（国内优化，D8） ──
                listener.onProgress(4, 0, "初始化配置…")
                initialize(ctx, prefix)
                File(ctx.filesDir, READY_MARKER).writeText("ok")

                listener.onProgress(5, 100, "安装完成，欢迎来到 moxsh！")
            } finally {
                pkg.delete() // 下载缓存用完即删，不占空间
            }
        }

    /** 从本地包导入（离线安装兜底：小白用文件管理器把 bootstrap 包放 Download 后一键导入）。 */
    suspend fun installFromLocal(ctx: Context, pkgPath: String, listener: ProgressListener): Unit =
        withContext(Dispatchers.IO) {
            val prefix = File(ctx.filesDir, PREFIX_SUBPATH)
            listener.onProgress(3, 0, "从本地包解压…")
            prefix.mkdirs()
            val f = File(pkgPath)
            // 按扩展名自动识别格式：Termux 官方产物为 zip；.mox 生态 rootfs 包为 tar.gz
            if (pkgPath.endsWith(".zip", ignoreCase = true)) {
                extractZip(f, prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }
            } else {
                extractTarGz(f, prefix) { p -> listener.onProgress(3, p, "解压中 $p%") }
            }
            listener.onProgress(4, 0, "初始化配置…")
            initialize(ctx, prefix)
            File(ctx.filesDir, READY_MARKER).writeText("ok")
            listener.onProgress(5, 100, "导入完成！")
        }

    // ── 内部实现 ────────────────────────────────────────────────────────────

    /** 下载远程 bootstrap 包到缓存目录（按格式命名），回调 0-100 进度。 */
    private fun download(ctx: Context, source: BootstrapSource, onProgress: (Int) -> Unit): File {
        val suffix = if (source.format == Format.ZIP) "zip" else "tar.gz"
        val out = File(ctx.cacheDir, "bootstrap.$suffix")
        val conn = URL(source.url).openConnection() as HttpURLConnection
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

    /**
     * 解压 bootstrap ZIP 到目标目录（Termux 官方 bootstrap 格式）。
     *
     * 格式契约（termux-packages `scripts/generate-bootstraps.sh` 与 termux-app
     * `TermuxInstaller` 双向核对）：
     *  - zip 条目路径相对 $PREFIX 根（`bin/...`、`etc/...`），无 `usr/` 前缀；
     *  - 符号链接不存入 zip（Java zip 流不还原 unix 链接），统一记在 SYMLINKS.txt：
     *    每行 `链接目标←链接位置`，分隔符为 U+2190（←），位置相对 $PREFIX（可能带 ./ 前缀）；
     *    全部条目解压完后统一重建（android.system.Os.symlink，与 Termux 同 API）。
     *  - 安全：拒绝绝对路径与 `..` 逃逸（对齐 .mox 包解压白名单思路）。
     */
    private fun extractZip(pkg: File, dest: File, onProgress: (Int) -> Unit) {
        val symlinks = mutableListOf<Pair<String, String>>() // first=链接目标 second=链接位置
        val totalBytes = pkg.length()
        FileInputStream(pkg).use { fin ->
            val counting = CountingInputStream(fin)
            ZipInputStream(counting, Charsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.trim('/').removePrefix("./")
                    try {
                        if (name == "SYMLINKS.txt") {
                            // 符号链接清单不落盘，读入内存统一重建
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            for (line in text.split('\n')) {
                                if (line.isBlank()) continue
                                val parts = line.split('←')
                                if (parts.size != 2) continue // 容错：坏行跳过
                                val target = parts[0].trim()
                                val linkPath = parts[1].trim().removePrefix("./")
                                if (target.isEmpty() || !isSafePath(linkPath)) continue
                                symlinks.add(target to linkPath)
                            }
                            continue
                        }
                        if (name.isEmpty() || !isSafePath(name)) continue // 拒绝路径逃逸
                        val outFile = File(dest, name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                            continue
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zip.copyTo(fos, 64 * 1024) }
                        // 可执行位（对齐 TermuxInstaller：bin/、libexec、apt 传输工具）
                        if (name.startsWith("bin/") || name.startsWith("libexec") ||
                            name.startsWith("lib/apt/apt-helper") || name.startsWith("lib/apt/methods")
                        ) {
                            outFile.setExecutable(true, false)
                            outFile.setReadable(true, false)
                        }
                    } finally {
                        zip.closeEntry()
                    }
                    if (totalBytes > 0) {
                        onProgress((counting.count * 100 / totalBytes).toInt().coerceIn(0, 99))
                    }
                }
            }
        }
        // ── 重建符号链接（android.system.Os.symlink，API 21+；单条失败不阻断安装） ──
        for ((target, linkPath) in symlinks) {
            try {
                val link = File(dest, linkPath)
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete() // 重装场景防御
                android.system.Os.symlink(target, link.absolutePath)
            } catch (_: Exception) {
                // 个别工具链接缺失不影响 shell 可用性，与 Termux 行为一致
            }
        }
    }

    /** 压缩流字节计数器（ZIP 进度估算：已读压缩字节 / 包总大小）。 */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val n = super.read()
            if (n != -1) count++
            return n
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n != -1) count += n
            return n
        }
    }

    /** 首次初始化：自研 login/profile、DNS、包源模板（D8 国内优化）、shebang 前缀修正。 */
    private fun initialize(ctx: Context, prefix: File) {
        val prefixPath = prefix.absolutePath
        val homePath = File(ctx.filesDir, "files/home").absolutePath

        // 1. 自研 login（覆盖 bootstrap 里按 com.termux 前缀硬编码的官方 ELF——
        //    官方二进制会把 HOME/exec 指向不可写的 com.termux 路径，moxsh 必须接管）。
        //    纯 POSIX 脚本、零编译依赖：设置环境后 exec bash -l（login shell 读 profile）。
        val login = File(prefix, "bin/login")
        login.parentFile?.mkdirs()
        login.writeText(
            "#!/system/bin/sh\n" +
                "# moxsh login —— 运行环境入口（initialize 阶段生成，覆盖 Termux 官方硬编码版）\n" +
                "PREFIX=$prefixPath\n" +
                "HOME=$homePath\n" +
                "TMPDIR=\$PREFIX/tmp\n" +
                "SHELL=\$PREFIX/bin/bash\n" +
                "PATH=\$PREFIX/bin:\$PATH\n" +
                "LANG=C.UTF-8\n" +
                "export PREFIX HOME TMPDIR SHELL PATH LANG\n" +
                "mkdir -p \"\$HOME\" \"\$TMPDIR\" 2>/dev/null\n" +
                "cd \"\$HOME\" 2>/dev/null || cd /\n" +
                "exec \"\$SHELL\" -l\n",
        )
        login.setExecutable(true, false)

        // 2. 自研 /etc/profile（同样覆盖官方版；bash -l 读取，保证 PATH/HOME/提示符正确）
        val etc = File(prefix, "etc").apply { mkdirs() }
        File(etc, "profile").writeText(
            "# moxsh /etc/profile（initialize 阶段生成）\n" +
                "export PREFIX=$prefixPath\n" +
                "export HOME=$homePath\n" +
                "export TMPDIR=\$PREFIX/tmp\n" +
                "export SHELL=\$PREFIX/bin/bash\n" +
                "export PATH=\$PREFIX/bin:\$PATH\n" +
                "export LANG=C.UTF-8\n" +
                "export LD_LIBRARY_PATH=\$PREFIX/lib\n" +
                "PS1='\\[\\e[36m\\]\\u\\[\\e[0m\\]@moxsh:\\w\\$ '\n" +
                "if [ -d \$PREFIX/etc/profile.d ]; then\n" +
                "  for i in \$PREFIX/etc/profile.d/*.sh; do\n" +
                "    [ -r \$i ] && . \$i\n" +
                "  done\n" +
                "  unset i\n" +
                "fi\n",
        )

        // 3. DNS（安卓上 /etc 不可写，用 resolv.conf 常规兜底；proot 发行版内另有处理）
        File(etc, "resolv.conf").writeText(
            "# moxsh 自动生成（国内 DNS，D8）\n" +
                "nameserver 223.5.5.5\n" +
                "nameserver 119.29.29.29\n",
        )

        // 4. 包源模板：moxsh 自研包管理 CLI 读取（mox 格式仓库，国内 CDN 置顶）
        File(etc, "moxsh-sources.list").writeText(
            "# moxsh 包源（自研 mox 格式仓库，按优先级排序）\n" +
                "# 1) moxsh 国内 CDN（默认）\n" +
                "# 2) moxsh 官方源（回退）\n" +
                "# TODO(CI): 填入真实仓库地址（M6）\n",
        )

        // 5. 兼容层提示：apt 风格 sources（发行版在 proot 容器内用容器自身的源，与这里无关）
        File(etc, "apt-sources.readme").writeText(
            "proot 发行版内的 apt 源在发行版内部管理（图形包管理器可一键切换清华/中科大）。\n",
        )

        // 6. shebang 前缀修正：Termux 包的脚本 shebang 在构建期硬编码
        //    /data/data/com.termux/...，moxsh 前缀不同会导致脚本直接失败。
        //    仅处理纯文本文件（无 NUL 字节——排除 ELF，避免破坏二进制内部偏移）。
        fixTextShebangs(File(prefix, "bin"))
        fixTextShebangs(File(prefix, "etc"))

        // 7. 一次性初始化标记目录（首次启动引导向导会消费）
        File(prefix, ".moxsh").apply { mkdirs() }
        File(prefix, ".moxsh/first-boot").writeText("pending")
    }

    /**
     * 递归修正目录下文本文件中的 Termux 硬编码前缀（com.termux → com.moxsh）。
     * 判定规则：文件不含 NUL 字节 = 文本（脚本/配置）；含 NUL = 二进制，跳过。
     * 无该字符串的文件不做重写（避免无谓 IO）。
     */
    private fun fixTextShebangs(dir: File) {
        if (!dir.isDirectory) return
        val needle = "/data/data/com.termux/files/usr"
        val replacement = "/data/data/com.moxsh/files/usr"
        dir.walkTopDown().filter { it.isFile && it.length() in 1..(2 * 1024 * 1024) }.forEach { f ->
            runCatching {
                val bytes = f.readBytes()
                if (bytes.contains(0.toByte())) return@runCatching // 二进制（ELF 等），跳过防偏移破坏
                // 严格 UTF-8 校验：非法序列（非 UTF-8 文本）直接跳过，避免解码破坏
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return@runCatching
                }
                if (!text.contains(needle)) return@runCatching
                f.writeText(text.replace(needle, replacement), Charsets.UTF_8)
            }
        }
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
     * 运行环境就绪钩子（插件宿主等下游在装配时设置）。
     * 方向设计：shared 不感知 plugin 层，由 plugin 侧注册回调，避免循环依赖。
     */
    @Volatile
    var onReady: (() -> Unit)? = null

    /** begin 互斥锁：重试按钮连点 / Application 自启并发时防止双份安装交错写盘。 */
    private val beginMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * 一键就绪流水线：已就绪直接 Ready；否则安装 bootstrap → 建立 $PREFIX 布局。
     * 幂等：并发调用时后到者直接等待当前状态（简化处理：Application 只启动一次）。
     */
    suspend fun begin(ctx: Context) {
        if (_state.value is State.Ready) return
        // P2 修复：互斥——同刻只允许一个安装流水线（重试与自启并发）
        beginMutex.withLock {
            beginLocked(ctx)
        }
    }

    private suspend fun beginLocked(ctx: Context) {
        if (_state.value is State.Ready) return
        if (BootstrapInstaller.isReady(ctx)) {
            CompatShim.ensurePrefixLayout()
            _state.value = State.Ready
            onReady?.invoke()
            return
        }
        try {
            BootstrapInstaller.install(ctx) { stage, progress, message ->
                _state.value = State.Running(stage, progress, message)
            }
            CompatShim.ensurePrefixLayout()
            _state.value = State.Ready
            onReady?.invoke()
        } catch (e: BootstrapInstaller.BootstrapException) {
            _state.value = State.Failed(e.message ?: "安装失败")
        } catch (e: Exception) {
            _state.value = State.Failed("安装失败：${e.message ?: "未知错误"}")
        }
    }
}

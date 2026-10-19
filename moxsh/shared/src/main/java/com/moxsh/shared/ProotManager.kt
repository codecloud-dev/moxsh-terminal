package com.moxsh.shared

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * PRoot 容器管理器（数据层，D12/D13/D14）。
 *
 * 0.4.0 重构：proot 引擎不再打包进 App。运行时按需从官方国内源
 * （Gitee `thousand-code-crafts/moxsh-proot` 的 `proot-latest` 滚动发布）
 * 下载最新版 `moxsh-proot-distro` + `moxsh-proot` 到 `$filesDir/proot/bin/`，
 * 之后所有操作都 shell out 到下载的 CLI（install/login/remove/backup/restore/list），
 * 用户因此始终拿到官方最新版，而非打包进去的旧版。
 *
 * 安装目录约定：`$filesDir/proot/containers/<id>/rootfs`
 * （与 moxsh-proot 引擎 DistroManager 布局一致；经 `MOXSH_ROOT` 环境变量告知引擎）。
 * 使用前必须 [init]（MoxshApplication.onCreate 已调用）。
 */
object ProotManager {

    /** 进度回调接口（UI 层用）。 */
    interface ProgressListener {
        fun onProgress(percent: Int, stage: String)
    }

    // ---- 官方国内源（Gitee，国内直连快、官方、最新）----
    private const val GITEE_OWNER = "thousand-code-crafts"
    private const val GITEE_REPO = "moxsh-proot"
    private const val RELEASE_TAG = "proot-latest"
    private val RELEASE_BASE =
        "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases/download/$RELEASE_TAG"

    private var appCtx: Context? = null

    /** 注入应用上下文（幂等；MoxshApplication.onCreate 调用）。 */
    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * 发行版安装基目录：`$filesDir/proot`。
     * 引擎经 `MOXSH_ROOT` 环境变量读取此目录，rootfs 实际落在
     * `$moxshRoot/containers/<id>/rootfs`。
     */
    fun moxshRoot(): String {
        val ctx = appCtx ?: error("ProotManager.init(context) 未调用")
        return File(ctx.filesDir, "proot").absolutePath
    }

    /** 上下文就绪判断（未 init 时所有操作走兜底，不崩）。 */
    private fun ready(): Boolean = appCtx != null

    private fun engineDir(): File = File(moxshRoot(), "bin")

    /** 当前设备 ABI → 资产后缀（arm64 优先，其次 x86_64）。 */
    private fun abiSuffix(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"
        return when {
            abi.contains("arm64") || abi.contains("aarch64") -> "arm64"
            abi.contains("x86_64") -> "amd64"
            else -> "arm64"
        }
    }

    private fun distroBin(): File = File(engineDir(), "moxsh-proot-distro")
    private fun engineBin(): File = File(engineDir(), "moxsh-proot")

    /**
     * 确保引擎已下载：缺任一二进制即从 Gitee 拉最新版，并标记可执行。
     * @return 两个二进制齐备且可执行返回 true。
     */
    private fun ensureEngine(): Boolean {
        val dir = engineDir().also { it.mkdirs() }
        val distro = distroBin()
        val engine = engineBin()
        val abi = abiSuffix()
        val targets = listOf(
            distro to "$RELEASE_BASE/moxsh-proot-distro-android-$abi",
            engine to "$RELEASE_BASE/moxsh-proot-android-$abi",
        )
        for ((file, url) in targets) {
            if (!file.canExecute() || file.length() == 0L) {
                try {
                    URL(url).openStream().use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                    file.setExecutable(true, false)
                } catch (e: Throwable) {
                    return false
                }
            }
        }
        return distro.canExecute() && engine.canExecute()
    }

    /** 在引擎 bin 目录约束下运行 proot-distro CLI，返回 stdout（trim）；失败返回 null。 */
    private fun runDistro(args: List<String>): String? {
        if (!ensureEngine()) return null
        return try {
            val pb = ProcessBuilder(distroBin().absolutePath, *args.toTypedArray())
            val env = pb.environment()
            val cur = System.getenv("PATH") ?: ""
            env["PATH"] = "${engineDir().absolutePath}:$cur"
            env["MOXSH_ROOT"] = moxshRoot()
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val ok = p.waitFor() == 0
            if (ok) out else null
        } catch (e: Throwable) {
            null
        }
    }

    /** 发行版信息（列表卡片直接渲染用）。 */
    data class DistroInfo(
        val id: String,
        val name: String,
        val version: String,
        val sizeMb: Long,
        val installed: Boolean,
    )

    /** 四大主流发行版预置目录 + 自定义入口。 */
    val AVAILABLE_DISTROS: List<DistroInfo> = listOf(
        DistroInfo("ubuntu-24.04", "Ubuntu", "24.04 LTS", 68L, false),
        DistroInfo("debian-12", "Debian", "12 Bookworm", 55L, false),
        DistroInfo("kali-rolling", "Kali", "rolling", 92L, false),
        DistroInfo("alpine-3.20", "Alpine", "3.20", 12L, false),
        DistroInfo("custom", "自定义 rootfs", "从 tar/URL 安装", 0L, false),
    )

    /** 已安装发行版（仅本地校验 rootfs 目录是否存在，无网络，主线程安全）。 */
    fun listInstalled(): List<DistroInfo> {
        return AVAILABLE_DISTROS.map { base ->
            val rootfs = File(moxshRoot(), "containers/${base.id}/rootfs")
            base.copy(installed = rootfs.isDirectory)
        }
    }

    /**
     * 一键安装（重操作：下载引擎 + 由引擎下载 rootfs，在 IO 线程执行）。
     * @return 成功 true；引擎未就绪/下载失败 false。
     */
    suspend fun install(id: String, onProgress: (Int, String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (!ready()) {
                onProgress(0, "内核未就绪：ProotManager 未初始化")
                return@withContext false
            }
            onProgress(5, "确保 moxsh-proot 引擎已下载")
            if (!ensureEngine()) {
                onProgress(0, "引擎下载失败（检查网络 / Gitee 可达性）")
                return@withContext false
            }
            onProgress(15, "正在安装 $id（下载 rootfs）")
            val ok = runDistro(listOf("install", id)) != null
            onProgress(if (ok) 100 else 0, if (ok) "安装完成，可以启动了" else "安装失败")
            ok
        }

    /** 删除发行版（IO 线程）。 */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        runDistro(listOf("remove", id)) != null
    }

    /**
     * 生成进入发行版的 proot 启动命令行。
     *
     * 优先用已下载引擎的 `login` 子命令（本地进程，无网络，主线程安全）；
     * 引擎未下载时回退 Kotlin 兜底拼接（同样的 `$moxshRoot/containers/<id>/rootfs` 布局）。
     *
     * 注意：返回的命令以裸名 `moxsh-proot` 引用引擎，终端会话需将其 bin 目录
     * （`$filesDir/proot/bin`）纳入 PATH，否则执行时找不到引擎。
     */
    fun loginCommand(id: String): String {
        val distro = distroBin()
        if (distro.canExecute()) {
            try {
                val pb = ProcessBuilder(distro.absolutePath, "login", id)
                val env = pb.environment()
                env["MOXSH_ROOT"] = moxshRoot()
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0 && out.isNotBlank()) return out
            } catch (_: Throwable) {
                // 落到兜底拼接
            }
        }
        val root = if (ready()) {
            "${moxshRoot()}/containers/$id/rootfs"
        } else {
            "<filesDir>/proot/containers/$id/rootfs"
        }
        return "moxsh-proot -r $root -0 -w /root -b /dev -b /proc -b /sys " +
            "/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
            "/bin/bash -l"
    }

    /** 备份发行版 rootfs 为 tar（IO 线程）。 */
    suspend fun backup(id: String, outPath: String): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        runDistro(listOf("backup", id, outPath)) != null
    }

    /** 从 tar 恢复发行版（IO 线程）。 */
    suspend fun restore(tarPath: String, id: String): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        runDistro(listOf("restore", id, tarPath)) != null
    }
}

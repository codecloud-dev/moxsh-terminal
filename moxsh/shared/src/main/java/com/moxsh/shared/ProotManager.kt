package com.moxsh.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PRoot 容器管理器（M4.2 图形化管理全家桶的数据层，D12/D13/D14）。
 *
 * 职责：把 native 层（libmoxshcore.so 的 `moxsh_proot_*` C-ABI，对应
 * `terminal-core/src/lib.rs` 导出的 proot 系列函数）包装成 Kotlin 友好 API，
 * 供 plugin-distro 的四件套界面（发行版管理器 / 图形包管理器 / 资源监控 /
 * 小白向导）调用，全程零命令行。
 *
 * native 对应关系（terminal-core/src/lib.rs，Rust）：
 *  - [nativeListInstalled]  -> `moxsh_proot_list_installed`（返回 `id|name|version|sizeMb|installed` 行数组）
 *  - [nativeInstall]        -> `moxsh_proot_install`（下载 -> SHA-256 校验 -> 解压 rootfs -> 初始化，JNI 内回调进度）
 *  - [nativeRemove]         -> `moxsh_proot_remove`
 *  - [nativeLoginCommand]   -> `moxsh_proot_login_command`（拼 proot 启动命令行）
 *  - [nativeBackup]         -> `moxsh_proot_backup`（rootfs 打 tar 包）
 *  - [nativeRestore]        -> `moxsh_proot_restore`（tar 恢复为 rootfs）
 *
 * 安装目录约定：`$filesDir/proot/<id>/`（应用私有区，无需任何存储权限）。
 *
 * 注意：native 实现由 M4.1 的 Rust PRoot 引擎提供；在引擎落地前调用会返回
 * UnsatisfiedLinkError，界面层已做 runCatching 兜底，展示"内核未就绪"文案。
 */
object ProotManager {

    /** 进度回调接口：JNI 侧（Rust）通过 CallVoidMethod 回调 [onProgress]。 */
    interface ProgressListener {
        /**
         * @param percent 0-100 的整数进度。
         * @param stage   阶段文案，如"下载中 45%（清华镜像）"、"校验 SHA-256"、
         *                "解压 rootfs"、"初始化"。
         */
        fun onProgress(percent: Int, stage: String)
    }

    // ------------------------------------------------------------------
    // native 桥（libmoxshcore.so）
    // ------------------------------------------------------------------

    init {
        // moxsh 自研内核库；加载失败由调用方 runCatching 兜底（见各 fun 注释）。
        runCatching { System.loadLibrary("moxshcore") }
    }

    /** 列出已安装发行版，每行格式 `id|name|version|sizeMb|installed`。 */
    private external fun nativeListInstalled(): Array<String>

    /**
     * 安装发行版（下载/校验/解压/初始化），进度经 [listener] 回调。
     * native 侧在内部线程池执行下载与解压，回调已保证在 moxsh 的 JNI 线程上。
     */
    private external fun nativeInstall(id: String, listener: ProgressListener): Boolean

    /** 删除发行版 rootfs 目录。 */
    private external fun nativeRemove(id: String): Boolean

    /** 返回进入该发行版的 proot 启动命令行（见 [loginCommand]）。 */
    private external fun nativeLoginCommand(id: String): String

    /** 把发行版 rootfs 打包为 tar 到 [outPath]。 */
    private external fun nativeBackup(id: String, outPath: String): Boolean

    /** 从 tar 恢复为发行版 rootfs（id 允许是自定义 id）。 */
    private external fun nativeRestore(tarPath: String, id: String): Boolean

    // ------------------------------------------------------------------
    // Kotlin 公开 API（界面层只用这些）
    // ------------------------------------------------------------------

    /** 发行版信息（列表卡片直接渲染用）。 */
    data class DistroInfo(
        val id: String,
        val name: String,
        val version: String,
        val sizeMb: Long,
        val installed: Boolean,
    )

    /**
     * 四大主流发行版预置目录（D14）+ 自定义入口说明。
     * `installed` 字段为静态目录初始值，运行时以 [listInstalled] 合并为准。
     */
    val AVAILABLE_DISTROS: List<DistroInfo> = listOf(
        DistroInfo("ubuntu-24.04", "Ubuntu", "24.04 LTS", 68L, false),
        DistroInfo("debian-12", "Debian", "12 Bookworm", 55L, false),
        DistroInfo("kali-rolling", "Kali", "rolling", 92L, false),
        DistroInfo("alpine-3.20", "Alpine", "3.20", 12L, false),
        // 自定义入口：不占卡片位，UI 显示"从 tar 安装"表单（URL + 可选 sha256）
        DistroInfo("custom", "自定义 rootfs", "从 tar/URL 安装", 0L, false),
    )

    /**
     * 已安装 + 预置目录合并后的发行版列表（界面卡片数据源）。
     * native 未就绪时回退为静态 [AVAILABLE_DISTROS]（安装态全 false）。
     */
    fun listInstalled(): List<DistroInfo> {
        val rows = runCatching { nativeListInstalled() }.getOrNull() ?: return AVAILABLE_DISTROS
        val installedById = rows.mapNotNull { row ->
            val parts = row.split("|")
            if (parts.size < 5) return@mapNotNull null
            Triple(parts[0], parts[1].toLongOrNull() ?: 0L, parts[4] == "1")
        }.associateBy({ it.first }, { it.second to it.third })
        val versionById = rows.associate { row ->
            val p = row.split("|")
            p[0] to (p.getOrNull(2) ?: "")
        }
        return AVAILABLE_DISTROS.map { base ->
            val (sizeMb, installed) = installedById[base.id] ?: (base.sizeMb to base.installed)
            val version = versionById[base.id]?.takeIf { it.isNotEmpty() } ?: base.version
            base.copy(sizeMb = sizeMb, installed = installed, version = version)
        }
    }

    /**
     * 一键安装（重操作：下载 + 校验 + 解压，在 IO 线程池执行）。
     *
     * @param id         发行版 id（[AVAILABLE_DISTROS] 中的，或自定义 id）。
     * @param onProgress 进度回调（UI 主线程外触发，界面侧记得切回主线程更新状态）。
     * @return 全部阶段成功返回 true；native 未就绪 / 校验失败返回 false。
     */
    suspend fun install(id: String, onProgress: (Int, String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                nativeInstall(id, object : ProgressListener {
                    override fun onProgress(percent: Int, stage: String) = onProgress(percent, stage)
                })
            }.getOrElse {
                onProgress(0, "内核未就绪：${it.message ?: "libmoxshcore 加载失败"}")
                false
            }
        }

    /** 删除发行版（IO 线程执行，删除 rootfs 目录树）。 */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { nativeRemove(id) }.getOrDefault(false)
    }

    /**
     * 生成进入发行版的 proot 启动命令行（纯字符串拼接，同步即可）。
     *
     * 示例（ubuntu-24.04）：
     * ```
     * proot --kill-on-exit -r <filesDir>/proot/ubuntu-24.04 -0 -w /root \
     *   -b /dev -b /proc -b /sys /bin/env -i HOME=/root PATH=... /bin/bash -l
     * ```
     * 返回值直接交给 `ExecutionEngine.createSession(command = loginCommand(id), ...)`
     * 执行即可进入发行版（见 DistroManagerScreen 的"启动"按钮注释）。
     */
    fun loginCommand(id: String): String =
        runCatching { nativeLoginCommand(id) }.getOrElse {
            // 纯 Kotlin 兜底拼接：保证界面在 native 未就绪时也能展示命令行形态。
            val root = "${CompatShim.PREFIX.removeSuffix("/usr")}/proot/$id"
            "proot --kill-on-exit -r $root -0 -w /root -b /dev -b /proc -b /sys " +
                "/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                "/bin/bash -l"
        }

    /**
     * 备份发行版 rootfs 为 tar（IO 线程执行）。
     * @param outPath 输出 tar 绝对路径；界面侧约定导出到
     *                `/sdcard/Download/moxsh-backups/<id>-<时间戳>.tar`
     *                （Android 10+ 实际经 MediaStore.Downloads 写入，见界面注释）。
     */
    suspend fun backup(id: String, outPath: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { nativeBackup(id, outPath) }.getOrDefault(false)
    }

    /** 从 tar 恢复发行版（IO 线程执行；[id] 可为已有发行版或自定义新 id）。 */
    suspend fun restore(tarPath: String, id: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { nativeRestore(tarPath, id) }.getOrDefault(false)
    }
}

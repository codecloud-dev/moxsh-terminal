package com.moxsh.shared

import android.content.Context
import com.moxsh.core.ProotNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PRoot 容器管理器（M4.2 图形化管理全家桶的数据层，D12/D13/D14）。
 *
 * 职责：把 native 层（libmoxshcore.so 的 `moxsh_proot_*` C-ABI，对应
 * `terminal-core/src/lib.rs` 导出的 proot 系列函数）包装成 Kotlin 友好 API，
 * 供 plugin-distro 的四件套界面（发行版管理器 / 图形包管理器 / 资源监控 /
 * 小白向导）与 plugin-ai 的 Agent 工具调用，全程零命令行。
 *
 * **M4.1 修正**：本类早期版本自行声明了 6 个 `external fun`
 * （nativeListInstalled/nativeInstall/...），但 bridge.cpp 只实现了
 * `Java_com_moxsh_core_*` 系列，`Java_com_moxsh_shared_ProotManager_*`
 * 从未存在——运行时全部 UnsatisfiedLinkError。现改为委托
 * [com.moxsh.core.ProotNative]（terminal-core 模块，JNI 实现齐备），
 * 彻底消除死路。
 *
 * native 对应关系（terminal-core/src/lib.rs，Rust）：
 *  - listInstalled  -> `moxsh_proot_list_installed`（行协议解析）
 *  - install        -> `moxsh_proot_install`（下载 -> SHA-256 校验 -> 解压 -> 初始化）
 *  - remove         -> `moxsh_proot_remove`
 *  - loginCommand   -> `moxsh_proot_login`（拼 proot 启动命令行）
 *  - backup         -> `moxsh_proot_backup`（rootfs 打 tar 包）
 *  - restore        -> `moxsh_proot_restore`（tar 恢复为 rootfs）
 *
 * 安装目录约定：`$filesDir/proot/containers/<id>/rootfs`（与 Rust
 * DistroManager 布局一致；应用私有区，无需任何存储权限）。
 * 使用前必须 [init]（MoxshApplication.onCreate 已调用）。
 */
object ProotManager {

    /** 进度回调接口：安装为阻塞式 C-ABI（无 JNI 回调），按阶段粗粒度回调。 */
    interface ProgressListener {
        /**
         * @param percent 0-100 的整数进度（安装阶段粒度：开始 0 / 完成 100）。
         * @param stage   阶段文案，如"安装 ubuntu-24.04"、"安装完成"。
         */
        fun onProgress(percent: Int, stage: String)
    }

    // ------------------------------------------------------------------
    // 生命周期与路径
    // ------------------------------------------------------------------

    private var appCtx: Context? = null

    /** 注入应用上下文（幂等；MoxshApplication.onCreate 调用）。 */
    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * 发行版安装基目录：`$filesDir/proot`。
     * Rust 侧 rootfs 实际落在 `$moxshRoot/containers/<id>/rootfs`。
     */
    fun moxshRoot(): String {
        val ctx = appCtx ?: error("ProotManager.init(context) 未调用")
        return File(ctx.filesDir, "proot").absolutePath
    }

    /** 上下文就绪判断（未 init 时所有操作走兜底，不崩）。 */
    private fun ready(): Boolean = appCtx != null

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
     * native 未就绪 / 未 init 时回退为静态 [AVAILABLE_DISTROS]（安装态全 false）。
     */
    fun listInstalled(): List<DistroInfo> {
        if (!ready()) return AVAILABLE_DISTROS
        val native = ProotNative()
        return when (val r = native.listInstalled(moxshRoot())) {
            is ProotNative.ProotResult.OkList -> {
                val installedIds = r.distros.filter { it.initialized }.map { it.id }.toSet()
                AVAILABLE_DISTROS.map { base ->
                    base.copy(installed = base.id in installedIds)
                }
            }
            else -> AVAILABLE_DISTROS
        }
    }

    /**
     * 一键安装（重操作：下载 + 校验 + 解压，在 IO 线程池执行）。
     *
     * @param id         发行版 id（[AVAILABLE_DISTROS] 中的，或自定义 id）。
     * @param onProgress 进度回调（IO 线程触发，界面侧记得切回主线程更新状态）。
     *                   C-ABI 为阻塞式无 JNI 回调，按开始/完成两点上报。
     * @return 全部阶段成功返回 true；native 未就绪 / 校验失败返回 false。
     */
    suspend fun install(id: String, onProgress: (Int, String) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            if (!ready()) {
                onProgress(0, "内核未就绪：ProotManager 未初始化")
                return@withContext false
            }
            onProgress(0, "安装 $id")
            val ctx = appCtx!!
            // 宿主系统 DNS 优先（PRoot 共享宿主网络栈，但 guest glibc 读
            // rootfs 内 resolv.conf，必须落真实地址；失败回退国内公共 DNS）。
            val dnsCsv = ProotNative().systemDnsCsv(ctx)
            val ok = ProotNative().install(id, moxshRoot(), cacheTar = null, dnsCsv = dnsCsv).isSuccess
            onProgress(if (ok) 100 else 0, if (ok) "安装完成" else "安装失败")
            ok
        }

    /** 删除发行版（IO 线程执行，删除 rootfs 目录树）。 */
    suspend fun remove(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        ProotNative().remove(id, moxshRoot()).isSuccess
    }

    /**
     * 生成进入发行版的 proot 启动命令行（同步即可）。
     *
     * 由 Rust 侧 `moxsh_proot_login` 生成（布局与绑定表同源，避免两套拼法漂移）；
     * native 未就绪时用 Kotlin 兜底拼接（同样的 $moxshRoot/containers/<id>/rootfs
     * 布局，保证界面在降级路径也能展示正确形态）。
     *
     * 返回值直接交给 `ExecutionEngine.createSession(command = loginCommand(id), ...)`
     * 执行即可进入发行版（见 DistroManagerScreen 的"启动"按钮注释）。
     */
    fun loginCommand(id: String): String {
        if (ready()) {
            when (val r = ProotNative().login(id, moxshRoot())) {
                is ProotNative.ProotResult.OkCmd -> return r.command
                else -> Unit
            }
        }
        // 纯 Kotlin 兜底拼接（与 Rust 布局对齐；未 init 时用占位路径展示形态）。
        val root = if (ready()) {
            "${moxshRoot()}/containers/$id/rootfs"
        } else {
            "<filesDir>/proot/containers/$id/rootfs"
        }
        return "proot --kill-on-exit -r $root -0 -w /root -b /dev -b /proc -b /sys " +
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
        if (!ready()) return@withContext false
        ProotNative().backup(id, moxshRoot(), outPath).isSuccess
    }

    /** 从 tar 恢复发行版（IO 线程执行；[id] 可为已有发行版或自定义新 id）。 */
    suspend fun restore(tarPath: String, id: String): Boolean = withContext(Dispatchers.IO) {
        if (!ready()) return@withContext false
        ProotNative().restore(id, moxshRoot(), tarPath).isSuccess
    }
}

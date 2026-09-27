package com.moxsh.core

/**
 * PRoot 容器引擎 JNI 桥接（M4，D12：自研 Rust 引擎 + 对齐上游 proot 6.x 行为）。
 * 对应 terminal-core/cpp/bridge.cpp 转发的 Rust C-ABI（moxsh_proot_*）。
 *
 * 与 [TerminalCore] 共享同一 cdylib（libmoxshcore.so），loadLibrary 幂等。
 *
 * C-ABI 错误码约定（与 lib.rs 注释表一致）：
 *   0 成功 | -1 参数非法 | -2 rootfs 不存在 | -3 发行版未知 | -4 已安装
 *   -5 未安装 | -6 需要下载缓存 | -7 SHA-256 校验失败 | -8 解压失败
 *   -9 IO | -10 缓冲区不足 | -11 ptrace 失败
 *
 * 字符串返回采用"调用方缓冲"模式：native 侧返回所需长度（>0），
 * 0 表示缓冲不足（扩容重试），负数为错误码——无需配对 free。
 */
class ProotNative {

    companion object {
        init {
            System.loadLibrary("moxshcore")
        }

        /** 路径翻译自检用例总数（Rust 侧 moxsh_proot_translate_selftest 满分值）。 */
        const val TRANSLATE_SELFTEST_TOTAL = 14

        /** list_installed 的行分隔协议字段数：id|initialized|rootfs。 */
        private const val LIST_FIELDS = 3

        private const val DEFAULT_BUF = 4096
        private const val MAX_BUF = 1 shl 20
    }

    /** 已安装发行版（list 的行协议解析结果）。 */
    data class ProotDistro(
        val id: String,
        val initialized: Boolean,
        val rootfsPath: String,
    )

    /** 统一结果包装：成功变体按返回内容分型，失败带错误码与人类可读原因。 */
    sealed class ProotResult {
        /** 操作成功（message 为操作说明）。 */
        data class Ok(val message: String) : ProotResult()

        /** 命令生成成功（command 为完整 proot 命令行，交 PTY 层执行）。 */
        data class OkCmd(val command: String) : ProotResult()

        /** 列表成功。 */
        data class OkList(val distros: List<ProotDistro>) : ProotResult()

        /** 失败（code 为 C-ABI 错误码）。 */
        data class Err(val code: Int, val message: String) : ProotResult()

        val isSuccess: Boolean get() = this !is Err
    }

    // ============ external fun（由 bridge.cpp 1:1 转发到 Rust C-ABI） ============

    external fun nativeInstall(id: String, moxshRoot: String, cacheTar: String?): Int
    external fun nativeRemove(id: String, moxshRoot: String): Int
    external fun nativeLogin(id: String, moxshRoot: String, buf: ByteArray): Int
    external fun nativeListInstalled(moxshRoot: String, buf: ByteArray): Int
    external fun nativeBackup(id: String, moxshRoot: String, outTar: String): Int
    external fun nativeRestore(id: String, moxshRoot: String, tarPath: String): Int
    external fun nativeTranslateSelfTest(): Int

    // ============ Kotlin 侧封装（Result 包装 + 缓冲重试） ============

    /**
     * 安装发行版。推荐流程：先由本层（OkHttp/DownloadManager）下载 rootfs
     * 归档到缓存，把本地路径作为 [cacheTar] 传入（Rust 侧依赖极简、不做 HTTPS）。
     * [cacheTar] 传 null 时 Rust 侧用 curl 兜底（开发者模式/命令行场景）。
     * 同步阻塞调用，请在 IO 线程执行；进度条按阶段估算。
     */
    fun install(id: String, moxshRoot: String, cacheTar: String? = null): ProotResult =
        wrap(nativeInstall(id, moxshRoot, cacheTar), "安装 $id")

    /** 删除发行版。 */
    fun remove(id: String, moxshRoot: String): ProotResult =
        wrap(nativeRemove(id, moxshRoot), "删除 $id")

    /**
     * 生成发行版登录命令（完整 proot 调用，对齐 proot-distro login），
     * 交给 PTY 层执行（见 TerminalCore.open）。失败返回 Err。
     */
    fun login(id: String, moxshRoot: String): ProotResult =
        when (val r = stringBuffer { nativeLogin(id, moxshRoot, it) }) {
            is StringResult.Ok -> ProotResult.OkCmd(r.value)
            is StringResult.Err -> ProotResult.Err(r.code, r.message ?: "登录命令生成失败")
        }

    /** 列出已安装发行版（行协议 `id|initialized|rootfs` 逐行解析）。 */
    fun listInstalled(moxshRoot: String): ProotResult {
        return when (val r = stringBuffer { nativeListInstalled(moxshRoot, it) }) {
            is StringResult.Ok -> {
                val distros = r.value.lines()
                    .filter { it.isNotBlank() }
                    .map { line ->
                        val f = line.split("|")
                        check(f.size >= LIST_FIELDS) { "list 协议字段不足: $line" }
                        ProotDistro(f[0], f[1] == "1", f.drop(2).joinToString("|"))
                    }
                ProotResult.OkList(distros)
            }
            is StringResult.Err -> ProotResult.Err(r.code, r.message ?: "列表失败")
        }
    }

    /** 备份发行版到 outTar（tar.gz，建议放 Download 目录供导出）。 */
    fun backup(id: String, moxshRoot: String, outTar: String): ProotResult =
        wrap(nativeBackup(id, moxshRoot, outTar), "备份 $id")

    /** 从 tar.gz 恢复发行版（覆盖已有）。 */
    fun restore(id: String, moxshRoot: String, tarPath: String): ProotResult =
        wrap(nativeRestore(id, moxshRoot, tarPath), "恢复 $id")

    /**
     * 路径翻译引擎自检（首次启动时调用，写入诊断日志）。
     * 返回 true 表示全部通过（通过数 == TRANSLATE_SELFTEST_TOTAL）。
     */
    fun translateSelfTest(): Boolean = nativeTranslateSelfTest() == TRANSLATE_SELFTEST_TOTAL

    // ---- 内部辅助 ----

    private fun wrap(code: Int, action: String): ProotResult =
        if (code == 0) ProotResult.Ok("$action 成功")
        else ProotResult.Err(code, "$action 失败: ${explain(code)}")

    private sealed class StringResult {
        data class Ok(val value: String) : StringResult()
        data class Err(val code: Int, val message: String?) : StringResult()
    }

    /** 缓冲模式字符串调用：0（缓冲不足）自动扩容重试，直至成功/错误/达上限。 */
    private fun stringBuffer(call: (ByteArray) -> Int): StringResult {
        var cap = DEFAULT_BUF
        while (cap <= MAX_BUF) {
            val buf = ByteArray(cap)
            val n = call(buf)
            if (n > 0) {
                // 返回值为所需长度（含 NUL），内容长 n-1。
                return StringResult.Ok(String(buf, 0, n - 1, Charsets.UTF_8))
            }
            if (n < 0) {
                return StringResult.Err(n, explain(n))
            }
            cap *= 2
        }
        return StringResult.Err(-10, explain(-10))
    }

    /** 错误码 → 人类可读说明（与 lib.rs 注释表一一对应）。 */
    private fun explain(code: Int): String = when (code) {
        -1 -> "参数非法"
        -2 -> "rootfs 不存在"
        -3 -> "发行版未知"
        -4 -> "已安装"
        -5 -> "未安装"
        -6 -> "需要先下载归档到缓存"
        -7 -> "SHA-256 校验失败"
        -8 -> "解压失败"
        -9 -> "IO 错误"
        -10 -> "缓冲区不足"
        -11 -> "ptrace 失败"
        else -> "未知错误 $code"
    }
}

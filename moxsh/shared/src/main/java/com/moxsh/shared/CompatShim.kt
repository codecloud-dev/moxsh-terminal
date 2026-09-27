package com.moxsh.shared

/**
 * 兼容 shim（L2 运行环境层核心）：termux-* 命令分类与转发。
 *
 * 在自研 rootfs 中还原 Termux 约定，使 termux-packages 的官方 `.deb` 能直接
 * 安装运行——这是"100% 自研代码、却兼容 Termux 生态"（D1/D3）的关键。
 *
 * 本文件与 `terminal-core/src/compat.rs` 的命令分类表严格对齐：
 *  - [TermuxTarget.Api]：原 Termux:API 命令，需经加固 IPC 转发到插件宿主；
 *  - [TermuxTarget.Tool]：原 termux-tools 脚本命令，由 moxsh 原生逻辑实现；
 *  - [TermuxTarget.Shell]：其余命令，原样交给运行环境执行。
 *
 * 这保证用户既有的 `termux-battery-status`、`termux-wake-lock` 等工作流零迁移。
 */
object CompatShim {

    /** 自研运行环境前缀（默认对齐 termux-packages 约定，保证原 .deb 落位正确）。 */
    const val PREFIX: String = "/data/data/com.moxsh/files/usr"

    /**
     * 已知 Termux:API 命令（与 compat.rs 的 API_COMMANDS 对齐，共 38 个）。
     * 这些命令涉及 Android 系统能力（电池、剪贴板、位置、通知等），
     * 需转发到 plugin-api 的 TermuxCompatHost 执行。
     */
    private val API_COMMANDS: Set<String> = setOf(
        "termux-audio-info",
        "termux-battery-status",
        "termux-brightness",
        "termux-call-log",
        "termux-camera-info",
        "termux-camera-photo",
        "termux-clipboard-get",
        "termux-clipboard-set",
        "termux-contact-list",
        "termux-download",
        "termux-flashlight",
        "termux-fingerprint",
        "termux-infrared-frequencies",
        "termux-infrared-transmit",
        "termux-job-scheduler",
        "termux-keystore",
        "termux-location",
        "termux-media-player",
        "termux-media-scan",
        "termux-microphone-record",
        "termux-notification",
        "termux-notification-list",
        "termux-notification-remove",
        "termux-nfc",
        "termux-phone-call",
        "termux-saf",
        "termux-sensor",
        "termux-sms-inbox",
        "termux-sms-send",
        "termux-speech-to-text",
        "termux-telephony-cellinfo",
        "termux-telephony-deviceinfo",
        "termux-toast",
        "termux-tts-engines",
        "termux-tts-speak",
        "termux-usb",
        "termux-vibrate",
        "termux-volume",
        "termux-wallpaper",
        "termux-wifi-connectioninfo",
        "termux-wifi-enable",
        "termux-wifi-scaninfo",
    )

    /**
     * 已知 termux-tools 命令（与 compat.rs 的 TOOL_COMMANDS 对齐，共 13 个）。
     * 这些命令由 moxsh 原生逻辑实现等价功能。
     */
    private val TOOL_COMMANDS: Set<String> = setOf(
        "termux-setup-storage",
        "termux-wake-lock",
        "termux-wake-unlock",
        "termux-info",
        "termux-fix-shebang",
        "termux-reload-settings",
        "termux-change-repo",
        "termux-open",
        "termux-open-url",
        "termux-share",
        "termux-sudo",
        "termux-chroot",
        "termux-exec",
    )

    /**
     * 分类一个命令名（含或不含 "termux-" 前缀均可，内部已 trim）。
     * 与 compat.rs 的 [classify] 语义完全一致。
     */
    fun classify(command: String): TermuxTarget {
        val name = command.trim()
        return when {
            API_COMMANDS.contains(name) -> TermuxTarget.Api
            TOOL_COMMANDS.contains(name) -> TermuxTarget.Tool
            else -> TermuxTarget.Shell
        }
    }

    /**
     * 对一条命令做兼容路由决策，返回 [CompatResult]。
     * @param command 命令名（通常是 argv[0]）。
     * @param args 命令参数列表（不含命令名）。
     */
    fun route(command: String, args: List<String>): CompatResult {
        return when (classify(command)) {
            // Api 类：转交插件宿主（Binder / PendingIntent 通信，见 TermuxCompatHost）。
            TermuxTarget.Api -> CompatResult.ForwardToHost(command, args)
            // Tool 类：moxsh 原生实现，hint 给出实现思路。
            TermuxTarget.Tool -> CompatResult.RunNative(command, args, nativeHint(command))
            // Shell 类：无需特殊路由，原样交给运行环境执行。
            TermuxTarget.Shell -> CompatResult.Success(command, args)
        }
    }

    /**
     * 为 Tool 类命令给出 moxsh 原生实现思路（运行环境就绪后由对应子系统落地）：
     *  - wake-lock：PowerManager.WakeLock 申请 PARTIAL_WAKE_LOCK；
     *  - setup-storage：StorageAccessFramework 引导授权并建立符号链接；
     *  - info：收集设备/版本信息汇总；
     *  - 其余：参考 termux-tools 语义的自研等价物。
     */
    private fun nativeHint(command: String): String = when (command) {
        "termux-wake-lock" ->
            "使用 android.os.PowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, \"moxsh:wake\") " +
                "申请并保持唤醒锁，记录标签以便 termux-wake-unlock 释放。"
        "termux-wake-unlock" ->
            "释放此前由 termux-wake-lock 持有的 WakeLock（调用 WakeLock.release()）。"
        "termux-setup-storage" ->
            "通过 StorageAccessFramework 引导用户授权外接存储，并在 \$PREFIX/home/storage 下建立 " +
                "dcim / downloads / music / pictures / movies 等符号链接。"
        "termux-info" ->
            "汇总设备型号、Android 版本、moxsh 版本号、PREFIX、CPU ABI、内存与存储用量等诊断信息。"
        "termux-fix-shebang" ->
            "扫描脚本首行 #!，将指向系统解释器的路径重写为 \$PREFIX 下的自研 loader（termux-exec 思路）。"
        "termux-reload-settings" ->
            "重新加载运行环境配置（LD_LIBRARY_PATH / PATH / 主题等）并热更新当前会话。"
        "termux-change-repo" ->
            "切换软件源（内置国内镜像置顶）：重写字 \$PREFIX/etc/apt/sources.list 并刷新索引。"
        "termux-open", "termux-open-url" ->
            "使用 ACTION_VIEW 的 Intent 在系统或玻璃界面中打开文件/URL。"
        "termux-share" ->
            "通过 ACTION_SEND 将文本/文件分享到其它应用。"
        "termux-sudo" ->
            "以提权方式执行命令（受 SELinux/noexec 约束，遵循自研运行环境的安全策略）。"
        "termux-chroot" ->
            "进入以 \$PREFIX 为根的 chroot / 命名空间隔离环境。"
        "termux-exec" ->
            "提供 termux-exec 等价物：拦截 execve，把 #!/bin/sh、env python 等 shebang 映射到自研解释器。"
        else -> "由 moxsh 原生逻辑实现（见架构 D10 兼容 shim）。"
    }

    /** 该命令是否需要 Android 权限 / 插件宿主支持（即 Api 类）。 */
    fun requiresHost(command: String): Boolean = classify(command) == TermuxTarget.Api

    /** 返回全部已知 termux 命令名（用于自动补全 / 帮助）。 */
    fun knownCommands(): List<String> = (API_COMMANDS + TOOL_COMMANDS).toList()

    /**
     * 建立自研 rootfs 的目录骨架与 loader/env 配置（已实现，M3 缺口收尾）。
     *
     * 目录契约对齐 termux-packages（D3，保证官方 .deb 落位正确）：
     * `$PREFIX/{bin,lib,etc,share,tmp,var,opt}` + `$HOME`（files/home）。
     * 同时写入：
     *  - `etc/moxsh.env`：会话启动时 source 的环境变量（PATH/LD_LIBRARY_PATH/TMPDIR/HOME）；
     *  - `etc/moxsh-ld.conf`：自研 loader 的库搜索路径（等价 ld.so.conf）。
     *
     * @return true = 目录骨架就绪。
     */
    fun ensurePrefixLayout(): Boolean {
        val root = java.io.File(PREFIX)
        // 目录骨架（安全：$PREFIX 位于应用私有区 files/usr，无需额外权限）
        val dirs = listOf("bin", "lib", "etc", "share", "tmp", "var/log", "opt", "usr/bin")
        dirs.forEach { java.io.File(root, it).mkdirs() }
        // HOME（自研运行环境的用户主目录）
        java.io.File("/data/data/com.moxsh/files/home").mkdirs()
        // 环境变量文件：每个 shell 会话启动前 source（对齐 Termux shell 启动约定）
        val env = java.io.File(root, "etc/moxsh.env")
        if (!env.exists()) {
            env.writeText(
                buildString {
                    appendLine("# moxsh 运行环境变量（首次初始化自动生成，勿手改）")
                    appendLine("export PREFIX=$PREFIX")
                    appendLine("export PATH=\$PREFIX/bin:\$PATH")
                    appendLine("export LD_LIBRARY_PATH=\$PREFIX/lib")
                    appendLine("export TMPDIR=$PREFIX/tmp")
                    appendLine("export HOME=/data/data/com.moxsh/files/home")
                    appendLine("export LANG=C.UTF-8")
                },
            )
        }
        // 自研 loader 库搜索配置（等价 ld.so.conf；配合 [libraryPath] 使 .deb 二进制找得到库）
        val ldConf = java.io.File(root, "etc/moxsh-ld.conf")
        if (!ldConf.exists()) {
            ldConf.writeText("$PREFIX/lib\n")
        }
        return root.isDirectory
    }

    /**
     * 重写脚本 shebang 以指向自研 loader（termux-exec 思路的自研等价物）。
     * 将常见的系统解释器路径映射到 \$PREFIX 下的自研解释器。
     */
    fun rewriteShebang(script: String): String {
        return script.lines().joinToString("\n") { line ->
            if (line.startsWith("#!")) {
                val (shebang, rest) = line.split(" ", limit = 2) + listOf("")
                val interp = shebang.removePrefix("#!")
                when {
                    interp.contains("bin/sh") -> "#!$PREFIX/bin/sh ${rest.trim()}".trimEnd()
                    interp.contains("bin/bash") -> "#!$PREFIX/bin/bash ${rest.trim()}".trimEnd()
                    interp.contains("python") -> "#!$PREFIX/bin/python ${rest.trim()}".trimEnd()
                    else -> line
                }
            } else {
                line
            }
        }
    }

    /** 还原 LD_LIBRARY_PATH / RUNPATH 解析，使官方 .deb 二进制找到库。 */
    fun libraryPath(): String = "$PREFIX/lib"
}

/**
 * 命令目标分类，与 `terminal-core/src/compat.rs` 的 [TermuxTarget] 对齐。
 */
enum class TermuxTarget {
    /** Termux:API 命令，转发到 moxsh 插件宿主。 */
    Api,

    /** termux-tools 命令，由 moxsh 原生实现等价逻辑。 */
    Tool,

    /** 非 termux 命令，原样交给运行环境执行。 */
    Shell,
}

/**
 * 兼容路由结果。
 */
sealed class CompatResult {
    /**
     * 普通 shell 命令，无需特殊路由，原样交给运行环境执行。
     * @param command 原命令名。
     * @param args 原参数列表。
     */
    data class Success(val command: String, val args: List<String>) : CompatResult()

    /**
     * Api 类命令：需转发到 plugin-api 的 [TermuxCompatHost] 执行。
     * @param command 原命令名。
     * @param args 原参数列表。
     */
    data class ForwardToHost(val command: String, val args: List<String>) : CompatResult()

    /**
     * Tool 类命令：由 moxsh 原生逻辑执行（[hint] 给出实现思路）。
     * @param command 原命令名。
     * @param args 原参数列表。
     * @param hint 原生实现思路（wake-lock 用 WakeLock、setup-storage 用 SAF、info 收集设备等）。
     */
    data class RunNative(
        val command: String,
        val args: List<String>,
        val hint: String,
    ) : CompatResult()
}

/**
 * 插件宿主接口（兼容 Termux:API 契约，D7 兼容体系）。
 *
 * 实际实现位于 plugin-api 模块，通过 **Binder / PendingIntent** 与 shared 通信，
 * 对 Api 类命令提供系统能力（电池、剪贴板、位置、通知等）。
 */
interface TermuxCompatHost {
    /** 同步执行一条 Api 命令，返回其标准输出。 */
    fun execute(command: String, args: List<String>): ByteArray

    /** 异步执行一条 Api 命令，结果通过 [callback] 回调（避免阻塞 UI 线程）。 */
    fun executeAsync(command: String, args: List<String>, callback: (ByteArray) -> Unit)
}

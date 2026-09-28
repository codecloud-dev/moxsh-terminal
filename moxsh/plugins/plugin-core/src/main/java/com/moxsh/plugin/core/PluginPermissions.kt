package com.moxsh.plugin.core

/**
 * moxsh 插件权限清单（v2）。
 *
 * .mox 包 manifest.json 的 `permissions` 字段声明本插件需要的权限，
 * 安装时向用户展示并逐项确认；运行期由 [PluginApi] 在每个能力入口校验，
 * 未声明对应权限的调用会抛 [SecurityException]。
 *
 * 与 docs/plugins.md 的权限表严格同步——新增权限时两处一起改。
 */
object PluginPermissions {
    // ── v1 既有权限 ─────────────────────────────────────────────────────────
    /** 执行命令（本地 PTY / 经 IPC 转发内核）。 */
    const val RUN_COMMAND = "run_command"

    /** 图形化安装软件包（moxpkg / apt）。 */
    const val INSTALL_PKG = "install_pkg"

    /** 安装 / 管理 PRoot 发行版。 */
    const val INSTALL_DISTRO = "install_distro"

    /** 修改软件源配置。 */
    const val CHANGE_REPO = "change_repo"

    /** 调用 AI 解释报错。 */
    const val EXPLAIN_ERROR = "explain_error"

    // ── v2 新增权限 ─────────────────────────────────────────────────────────

    /** 创建 / 关闭 / 切换终端会话（多会话管理）。 */
    const val MANAGE_SESSIONS = "manage_sessions"

    /** 读取终端屏幕内容（回滚缓冲、当前屏单元格）。 */
    const val READ_SCREEN = "read_screen"

    /** 发送系统通知。 */
    const val POST_NOTIFICATION = "post_notification"

    /** 读写剪贴板。 */
    const val CLIPBOARD = "clipboard"

    /** 震动 / 触感反馈。 */
    const val VIBRATE = "vibrate"

    /** 发起网络请求（HTTP GET/POST，受超时与大小限制约束）。 */
    const val NETWORK = "network"

    /** 插件私有文件读写 + 导出文件到公共 Download（经 MediaStore，无需存储权限）。 */
    const val STORAGE = "storage"

    /** 注册 AI 技能（把自己的能力挂进 AI 助手的工具列表）。 */
    const val REGISTER_SKILL = "register_skill"

    /** 订阅全局事件（命令执行 / 终端输出 / 会话开闭）。 */
    const val SUBSCRIBE_EVENTS = "subscribe_events"

    /** 全部权限（工具方法用，不作为 manifest 值）。 */
    val ALL: Set<String> = setOf(
        RUN_COMMAND, INSTALL_PKG, INSTALL_DISTRO, CHANGE_REPO, EXPLAIN_ERROR,
        MANAGE_SESSIONS, READ_SCREEN, POST_NOTIFICATION, CLIPBOARD, VIBRATE,
        NETWORK, STORAGE, REGISTER_SKILL, SUBSCRIBE_EVENTS,
    )
}

/**
 * 权限不足异常。message 会以玻璃确认卡形式展示给用户（与 D18 的高危操作确认体验一致）。
 */
class PluginPermissionDenied(permission: String) :
    SecurityException("插件未声明权限：$permission（请在 manifest.json 的 permissions 中声明并重新打包）")

//! termux-* 命令兼容路由（Rust 自研）。
//!
//! 提供权威的命令分类表，供 Kotlin 层 [`crate::shared`] 的兼容 shim 转发：
//! - `Api`：原 Termux:API 命令（需经加固 IPC 转给插件宿主）
//! - `Tool`：原 termux-tools 脚本命令（moxsh 自行实现等价逻辑）
//! - `Shell`：其余交给运行环境原样执行
//!
//! 这让用户既有的 `termux-battery-status`、`termux-wake-lock` 等工作流零迁移。

#[derive(Debug, Clone, PartialEq)]
pub enum TermuxTarget {
    /// Termux:API 命令，转发到 moxsh 插件宿主。
    Api,
    /// termux-tools 命令，由 moxsh 原生实现。
    Tool,
    /// 非 termux 命令，原样交给运行环境执行。
    Shell,
}

/// 已知 Termux:API 命令（38 个，来自 docs/commands.md）。
const API_COMMANDS: &[&str] = &[
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
];

/// 已知 termux-tools 命令（13 个）。
const TOOL_COMMANDS: &[&str] = &[
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
];

/// D15：`.mox` 包管理 CLI（`moxpkg <list|install|remove|verify> <path|id>`），
/// 对应 [`crate::moxpkg`]。与 termux 命令表互不重叠，照前置透传模式接入
/// 执行引擎路由（有子命令语义，不能落 Shell 分类）。
pub const MOXPKG_COMMANDS: &[&str] = &["moxpkg"];

/// 该命令是否为 .mox 包管理命令（透传给 moxpkg 子命令路由）。
pub fn is_moxpkg_command(command: &str) -> bool {
    MOXPKG_COMMANDS.contains(&command.trim())
}

/// 分类一个命令名（含或不含 "termux-" 前缀均可）。
pub fn classify(command: &str) -> TermuxTarget {
    let name = command.trim();
    if API_COMMANDS.contains(&name) {
        TermuxTarget::Api
    } else if TOOL_COMMANDS.contains(&name) {
        TermuxTarget::Tool
    } else {
        TermuxTarget::Shell
    }
}

/// 该命令是否需要 Android 权限/插件宿主支持。
pub fn requires_host(name: &str) -> bool {
    matches!(classify(name), TermuxTarget::Api)
}

/// 返回全部已知 termux 命令名（用于自动补全/帮助）。
pub fn known_commands() -> Vec<&'static str> {
    let mut v: Vec<&str> = Vec::new();
    v.extend_from_slice(API_COMMANDS);
    v.extend_from_slice(TOOL_COMMANDS);
    v
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn classify_works() {
        assert_eq!(classify("termux-battery-status"), TermuxTarget::Api);
        assert_eq!(classify("termux-wake-lock"), TermuxTarget::Tool);
        assert_eq!(classify("ls"), TermuxTarget::Shell);
    }

    /// moxpkg 命令命中前置路由，termux 分类不受影响（D15）。
    #[test]
    fn moxpkg_command_route() {
        assert!(is_moxpkg_command("moxpkg"));
        assert!(is_moxpkg_command("  moxpkg  "));
        assert!(!is_moxpkg_command("moxpkgx"));
        assert_eq!(classify("moxpkg"), TermuxTarget::Shell);
    }
}

# 贡献指南

感谢关注 moxsh！无论是报 bug、提建议还是交代码，都欢迎。

## 报告问题

提交 Issue 前先搜索一下是否已有同类问题。报告 bug 时请尽量带上：

- 设备型号与 Android 版本
- 复现步骤（越具体越好）
- 期望行为 vs 实际行为
- 相关日志（终端输出或 `adb logcat`）

## 提交代码

1. Fork 本仓库，从 `main` 创建功能分支（如 `feature/glass-search`、`fix/pty-crash`）
2. 完成修改，确保能通过现有测试
3. 提交 Pull Request，说明改了什么、为什么改、怎么验证

### 开发环境

- Android Studio（AGP 对应版本）+ Android SDK 34 + NDK 26.1.10909125
- Rust 工具链（stable）+ `cargo install cargo-ndk`
- 工程入口在 `moxsh/`，克隆后用 Android Studio 直接打开即可

### 提交信息

遵循 Conventional Commits：

```text
feat: 新增悬浮窗拖拽吸附
fix: 修复 PTY 读取在低内存时的崩溃
docs: 更新插件开发指南
```

## 代码约定

- 终端内核、包管理等底层逻辑用 Rust（`terminal-core`），UI 与插件用 Kotlin/Compose
- 遵循各目录现有的代码风格；新增依赖请先在 Issue 里讨论
- 涉及液态玻璃 UI 的改动注意低版本回退（API 31 以下无实时模糊）

## 版权

提交即表示同意代码以 [GPL-3.0](LICENSE) 协议发布。

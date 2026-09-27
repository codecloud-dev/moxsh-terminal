# 更新日志

本项目的所有显著变更都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 性能打磨：120Hz 渲染、glyph atlas 离屏缓存、syscall 热路径优化
- 本地小模型接入（llama.cpp 端侧）
- 插件售卖支付流程（manifest 中 author/price/purchased 字段已预留）

## [0.5.0] - 2026-09-27

首个功能完整版本：从终端内核到图形化管理全家桶全链路可用，开始通过 GitHub Actions 出安装包。

### 新增

- **Rust 终端内核**（terminal-core）：PTY 会话引擎、VT/xterm 转义序列解析、滚动回滚缓冲（超 10000 行自动落盘 mmap 兜底，防 OOM）
- **PRoot 容器引擎**（纯 Rust 自研，融合上游 proot 6.1+ 行为特性）：路径翻译规则引擎、ptrace 系统调用拦截改写（覆盖 open/stat/execve/chdir 等高频集）、`proot` / `proot-distro` 等价 CLI 兼容层
- **发行版管理**：Ubuntu / Debian / Kali / Alpine 一键安装（国内镜像优先 + sha256 校验 + 官方回退），支持自定义 rootfs tar、备份与恢复
- **液态玻璃 UI**：基于 Jetpack Compose 的全套玻璃质感界面，覆盖终端、管理器、商店、AI 对话
- **图形化管理四件套**：发行版管理器、图形包管理器（双源：.mox 自有格式 + apt）、资源监控面板、小白引导向导
- **.mox 插件包格式**：tar 容器 + manifest.json + HMAC-SHA256 签名，统一承载插件 / AI 技能 / 主题 / rootfs 四种类型，含权限声明模型
- **插件商店**：浏览、一键安装、卸载、启停、更新、本地上传 .mox、云端源接口（内置目录兜底）
- **AI 助手**：OpenAI 兼容接口（自定义 base URL）+ 国内预置（DeepSeek / 智谱 / 通义 / Kimi），自然语言直达图形化操作（装发行版 / 装包 / 换源 / 跑命令 / 解释报错），高危操作弹玻璃确认卡
- **Bootstrap 安装流程**：首次启动自动下载 bootstrap rootfs 并初始化 `$PREFIX`
- **持续集成**：GitHub Actions 自动构建 Release APK，以 Artifact 形式发布

### 说明

- 项目为 100% clean-room 实现：包层面兼容 Termux 生态，代码层面零复用
- 与 Termux 的关系详见 [README](README.md#和-termux-是什么关系)

[Unreleased]: https://github.com/zyr15555086235/moxsh-terminal/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/zyr15555086235/moxsh-terminal/releases/tag/v0.5.0

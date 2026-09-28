# moxsh

<p align="center">
  <img src="assets/logo.svg" width="120" alt="moxsh 玻璃标志">
</p>

<p align="center">
  <a href="../../actions"><img src="https://img.shields.io/github/actions/workflow/status/zyr15555086235/moxsh-terminal/build.yml?branch=main&label=CI%20Build" alt="CI Build"></a>
  <a href="../../releases"><img src="https://img.shields.io/github/v/release/zyr15555086235/moxsh-terminal" alt="最新版本"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/zyr15555086235/moxsh-terminal" alt="许可证"></a>
  <a href="../../discussions"><img src="https://img.shields.io/github/discussions/zyr15555086235/moxsh-terminal" alt="社区讨论"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Core-Rust-000?logo=rust&logoColor=white" alt="Rust 内核">
  <img src="https://img.shields.io/badge/UI-Liquid%20Glass-8a7bff" alt="液态玻璃 UI">
</p>

> 跑在 Android 上的**液态玻璃终端**：装上就有完整 Linux 命令行，无需 root、无需配置，全面兼容 Termux 生态。

<details>
<summary>📑 目录</summary>

- [核心特性](#核心特性)
- [它是怎么工作的](#它是怎么工作的)
- [能用它做什么](#能用它做什么)
- [安装](#安装)
- [插件](#插件)
- [和 Termux 是什么关系](#和-termux-是什么关系)
- [参与进来](#参与进来)

</details>

moxsh 是一款 Android 终端应用：装上就有完整的 Linux 命令行环境，不需要 root，不需要任何配置。

它全面兼容 Termux 生态——Termux 官方源的软件包可以直接安装运行。同时 moxsh 是一套 AI 辅助生成的 clean-room 实现——开发过程中研读了 Termux 的公开文档与构建脚本以对齐行为，但代码全部重新编写、未复制 Termux 源码，因此在性能和界面上有更大的发挥空间：整个应用采用液态玻璃（liquid glass）设计，内核与包管理用 Rust 编写，并内置 AI 助手与插件商店。

## 它是怎么工作的

moxsh 不是虚拟机，也不是模拟器。

终端部分由 Rust 内核直接驱动：应用启动命令行程序（`execve`），并把标准输入输出接到屏幕上，这一点和桌面 Linux 上的终端是一样的。由于 Android 不允许应用往 `/bin`、`/usr` 这类系统目录写文件，moxsh 把所有软件安装在自己的私有目录里（称为 *prefix*，对应环境变量 `$PREFIX`），并把路径处理对齐 Termux 的约定——这就是 Termux 软件包能直接运行的原因。

软件包来自 Termux 官方仓库（`apt`/`pkg` 双协议兼容），全部用 Android NDK 交叉编译，原生运行，没有仿真开销。想在隔离环境里玩整个发行版（Ubuntu、Debian、Kali、Alpine），可以用内置的 PRoot 引擎一键安装。

界面采用液态玻璃设计：实时模糊、半透明层级、可拖拽的悬浮窗。高版本安卓使用实时渲染模糊，低版本自动回退到静态效果，不需要手动设置。

## 能用它做什么

- 学习 Linux 命令行和 Shell 脚本
- 用 Python、Node.js、Rust、C/C++ 写程序
- 通过 SSH 连接远程服务器，或把手机当跳板
- 一键安装 Ubuntu / Debian / Kali / Alpine 发行版
- 用 AI 助手解释报错、写脚本、管理环境（支持 DeepSeek、智谱、通义、Kimi 等）
- 安装插件：悬浮窗终端、主题、系统监控等，也可以自己写

## 核心特性

| 维度 | 说明 |
|---|---|
| 🦀 **Rust 内核** | PTY/会话、VT 解析、回滚、渲染调度、包管理验签、IPC 鉴权全部用 Rust，内存安全 |
| 💎 **液态玻璃 UI** | 实时模糊、半透明层级、可拖拽悬浮窗；高版本实时渲染，低版本自动回退 |
| 📦 **Termux 兼容** | 直接吃 termux-packages 官方仓库，`*.deb` 能装能跑，命令用法一致 |
| 🐧 **PRoot 发行版** | Ubuntu / Debian / Kali / Alpine 一键安装，隔离环境随便玩 |
| 🤖 **AI 助手** | 解释报错、写脚本、管环境，支持 DeepSeek / 智谱 / 通义 / Kimi 等 |
| 🧩 **插件体系** | 悬浮玻璃终端、主题、系统监控，`.mox` 一键装，也兼容 Termux 插件 |
| 🔐 **加固 IPC** | 挑战-响应 + HMAC-SHA256 鉴权，跨进程调用不裸奔 |

## 安装

### 系统要求

- Android 9.0 或更高
- arm64-v8a 设备
- 约 300 MB 可用空间（含首装引导包）

### 获取 APK

本仓库通过 GitHub Actions 自动构建：

1. 打开仓库的 [Actions](../../actions) 页面，选择最近一次成功的构建
2. 在页面底部的 Artifacts 里下载 APK 并安装

发布版本会同时放到 [Releases](../../releases) 页面。安装时如系统提示"未知来源"，允许即可。

## 快速上手

第一次启动 moxsh 会自动下载并安装基础系统（引导包），完成后直接进入终端。

更新软件源和包：

```bash
pkg update && pkg upgrade
```

安装软件（和 Termux 用法完全一致）：

```bash
pkg install python
pkg install nodejs
pkg install openssh
```

常用操作：

```bash
# 允许访问手机存储（照片、下载等）
termux-setup-storage

# 连接远程服务器
ssh user@host

# 查看当前 prefix 路径
echo $PREFIX
```

首次安装某个包之前先 `pkg update` 一次，可以避免找不到包的问题。

## 插件

moxsh 的插件、AI 技能、主题、发行版镜像统一使用 `.mox` 包格式，通过内置商店安装，也支持把 `.mox` 文件放到下载目录离线安装。

为 moxsh 编写和发布插件的方法见 [docs/plugins.md](docs/plugins.md)。

同时 moxsh 兼容 Termux 插件宿主（Termux:API、Termux:Widget 等原插件可用）；外来 zip 格式的插件包会在导入时自动转换。

## 和 Termux 是什么关系

- **软件包层面**：直接兼容。Termux 官方源的 `.deb` 包能装能跑，命令用法一致。
- **代码层面**：AI 辅助生成、clean-room 重写。moxsh 的终端内核、包管理器、运行环境、PRoot 引擎全部重新编写（Rust/Kotlin）；开发过程研读 Termux 公开文档/源码以对齐行为与生态契约，但未复制其代码，也不依赖修改 Termux 源码。
- **插件层面**：双体系。Termux 兼容宿主让原有插件继续可用；moxsh 原生插件（`.mox`）独立签名、带玻璃图形界面。

## 文档

- [架构白皮书](docs/architecture.md) —— 设计与实现原理
- [插件开发指南](docs/plugins.md) —— 编写、打包、签名、上架
- [命令体系](docs/commands.md) —— 支持的命令清单
- [开发路线图](docs/roadmap.md) —— 进度与规划

## 参与进来

发现 bug 或想要新功能，欢迎提 [Issue](../../issues)；想贡献代码直接提 Pull Request。

## 许可证

[GPL-3.0](LICENSE)

## AI 辅助声明

本项目（含全部代码、文档与官网页面）由开发者 **Codecloud** 主导设计，
**AI 辅助生成代码**：架构决策、需求定义与验收由人完成，代码实现与文档撰写由 AI 协作完成并经人工审核修订。

<p align="center">  
  <img src="assets/logo.svg" width="128" alt="moxsh 液态玻璃标志">  
</p>

<h1 align="center">💎 moxsh</h1>

<p align="center">  
  <b>跑在 Android 上的液态玻璃终端</b> —— 装上就有完整 Linux 命令行，无需 root、无需配置，全面兼容 Termux 生态。  
</p>

<p align="center">  
  <a href="../../actions"><img src="https://img.shields.io/github/actions/workflow/status/codecloud-dev/moxsh-terminal/build.yml?branch=main\&label=CI%20Build\&color=8a7bff" alt="CI Build"></a>  
  <a href="../../releases"><img src="https://img.shields.io/github/v/release/codecloud-dev/moxsh-terminal?label=Latest\&color=37d5d3" alt="最新版本"></a>  
  <a href="../../discussions"><img src="https://img.shields.io/github/discussions/codecloud-dev/moxsh-terminal?label=Discussions\&color=ff7ac3" alt="社区讨论"></a>  
  <a href="LICENSE"><img src="https://img.shields.io/github/license/codecloud-dev/moxsh-terminal?color=3DDC84" alt="许可证"></a>  
    
  
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android\&logoColor=white" alt="Android 9+">  
  <img src="https://img.shields.io/badge/Core-Rust-000?logo=rust\&logoColor=white" alt="Rust 内核">  
  <img src="https://img.shields.io/badge/UI-Liquid%20Glass-8a7bff" alt="液态玻璃 UI">  
  <img src="https://img.shields.io/badge/Kotlin-UI-7F52FF?logo=kotlin\&logoColor=white" alt="Kotlin">  
  <img src="https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions\&logoColor=white" alt="GitHub Actions">  
</p>

> 装上就有完整的 Linux 命令行；整个应用从内核到界面都为「快」与「美」重新造过一遍——Rust 内核 + 一整套美得像水的液态玻璃界面。

<details>

<summary>📑 目录</summary>

- [🌌 MoX 系列](#-mox-系列)
- [✨ 核心特性](#-核心特性)
- [🛠 它是怎么工作的](#-它是怎么工作的)
- [🚀 快速上手](#-快速上手)
- [🧩 插件体系](#-插件体系)
- [🔗 和 Termux 的关系](#-和-termux-的关系)
- [📚 文档](#-文档)
- [🤝 参与进来](#-参与进来)

</details>

---

## 🌌 MoX 系列

moxsh 只是 **MoX 工具系列**的第一块拼图。一个账号、一套审美，把桌面级工具搬进掌心。

|      产品      |   状态   | 一句话定位                                                            |
| :----------: | :----: | ---------------------------------------------------------------- |
|   **moxsh**  |  ✅ 已上线 | Android 液态玻璃终端，兼容 Termux 生态                                      |
|  **mox-id**  |  ✅ 已上线 | MoX 统一身份后端（GitHub OAuth，可自托管）                                    |
| **mox-site** |  ✅ 已上线 | MoX 系列官方门户（[mox系列官网](https://codecloud-dev.github.io/mox-site/)） |
|  **moxbox**  | 🚧 规划中 | 文件管理与系统套件，同款玻璃界面                                                 |
|  **moxcode** | 🚧 规划中 | 移动端轻量 IDE：终端 + 编辑器 + 预览一体                                        |

---

## ✨ 核心特性

| 维度               | 说明                                             |
| :--------------- | ---------------------------------------------- |
| 🦀 **Rust 内核**   | PTY/会话、VT 解析、回滚、渲染调度、包管理验签、IPC 鉴权全部用 Rust，内存安全 |
| 💎 **液态玻璃 UI**   | 实时模糊、半透明层级、可拖拽悬浮窗；高版本实时渲染，低版本自动回退              |
| 📦 **Termux 兼容** | 直接吃 termux-packages 官方仓库，`*.deb` 能装能跑，命令用法一致   |
| 🐧 **PRoot 发行版** | Ubuntu / Debian / Kali / Alpine 一键安装，隔离环境随便玩   |
| 🤖 **AI 助手**     | 解释报错、写脚本、管环境，支持 DeepSeek / 智谱 / 通义 / Kimi 等    |
| 🧩 **插件体系**      | 悬浮玻璃终端、主题、系统监控，`.mox` 一键装，也兼容 Termux 插件        |
| 🔐 **加固 IPC**    | 挑战-响应 + HMAC-SHA256 鉴权，跨进程调用不裸奔                |
| ☁️ **命令云同步**     | 登录后把 shell 配置、别名、历史同步到云端，换机不丢环境                |

---

## 🛠 它是怎么工作的

moxsh 不是虚拟机，也不是模拟器。

终端部分由 **Rust 内核**直接驱动：应用启动命令行程序（`execve`），把标准输入输出接到屏幕上，和桌面 Linux 终端一致。由于 Android 不允许应用往 `/bin`、`/usr` 写文件，moxsh 把软件装在自己的私有目录（称为 *prefix*，对应 `$PREFIX`），路径处理对齐 Termux 约定——这就是 Termux 软件包能直接运行的原因。

软件包来自 Termux 官方仓库（`apt`/`pkg` 双协议兼容），全部用 Android NDK 交叉编译、原生运行、无仿真开销。想在隔离环境玩整个发行版，用内置 **PRoot 引擎**一键安装。

界面采用**液态玻璃**设计：实时模糊、半透明层级、可拖拽悬浮窗。高版本安卓用实时渲染模糊，低版本自动回退静态效果，无需手动设置。

---

## 🚀 快速上手

第一次启动会自动下载并安装基础系统（引导包），完成后直接进入终端。

更新软件源与包：

```bash
pkg update && pkg upgrade
```

安装软件（和 Termux 用法完全一致）：

```bash
pkg install python nodejs openssh
```

常用操作：

```bash
termux-setup-storage          # 允许访问手机存储（照片、下载等）
ssh user@host                 # 连接远程服务器
echo $PREFIX                  # 查看当前 prefix 路径
```



> 💡 首次装包前先 `pkg update` 一次，可避免找不到包的问题。

### 系统要求

- Android 9.0 或更高
- arm64-v8a 设备
- 约 300 MB 可用空间（含首装引导包）

### 获取 APK

正式签名包在 [Releases](../../releases) 页面；CI 每次成功构建也会产出最新 APK。安装时如系统提示「未知来源」，允许即可。

---

## 🧩 插件体系

moxsh 的插件、AI 技能、主题、发行版镜像统一使用 **`.mox`** 包格式，通过内置商店安装，也支持把 `.mox` 文件放到下载目录离线安装。

为 moxsh 编写和发布插件的方法见 [docs/plugins.md](docs/plugins.md)。同时 moxsh 兼容 Termux 插件宿主（Termux:API、Termux:Widget 等原插件可用）；外来 zip 格式会在导入时自动转换。

---

## 🔗 和 Termux 的关系

- **软件包层面**：直接兼容。Termux 官方源 `.deb` 能装能跑，命令用法一致。
- **代码层面**：AI 辅助生成、clean-room 重写。终端内核、包管理器、运行环境、PRoot 引擎全部重新编写（Rust/Kotlin）；开发过程研读 Termux 公开文档/源码以对齐行为与生态契约，但**未复制其代码**，也不依赖修改 Termux 源码。
- **插件层面**：双体系。Termux 兼容宿主让原插件继续可用；moxsh 原生插件（`.mox`）独立签名、带玻璃图形界面。

---

## 📚 文档

- [架构白皮书](docs/architecture.md) —— 设计与实现原理
- [插件开发指南](docs/plugins.md) —— 编写、打包、签名、上架
- [命令体系](docs/commands.md) —— 支持的命令清单
- [开发路线图](docs/roadmap.md) —— 进度与规划

---

## 🤝 参与进来

发现 bug 或想要新功能，欢迎提 [Issue](../../issues)；想贡献代码直接提 Pull Request。

## 📄 许可证

[GPL-3.0](LICENSE)

## 🤖 AI 辅助声明

本项目（含全部代码、文档与官网页面）由开发者 **Codecloud** 主导设计，**AI 辅助生成代码**：架构决策、需求定义与验收由人完成，代码实现与文档撰写由 AI 协作完成并经人工审核修订。

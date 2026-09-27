# moxsh 架构设计白皮书（v0.4 · 草案）

> **核心定位**：moxsh 的**运行架构 100% 自研、不沿用 Termux 任何代码**（clean-room），但**产物兼容 Termux 生态**——能直接吃 termux-packages 官方仓库、跑现有 `.deb`，同时自带全新格式仓库。全应用液态玻璃、国内优化、云端 CI 出 APK。
> **Clean-room 纪律**：可广泛阅读任何项目的**思路**（libvterm/st/apt/dpkg/termux），但实现代码全部自己写，不复制片段。

---

## 1. 已拍定的 18 个决策

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 兼容层级 | **吃原仓库**：直接消费 termux-packages 官方仓库，现有 `.deb` 能装能跑 |
| D2 | 包管理器 | **双协议自研**：既有我们全新的格式仓库，也兼容 termux 的 apt/deb 协议 |
| D3 | 运行环境 | **100% 自己写、完全自定义**：不抄 Termux 代码；实现对齐 termux-packages 磁盘/ABI 契约 |
| D4 | 终端内核 | **纯自研（clean-room）**：可参考 libvterm/st 等思路，代码全自己写 |
| D5 | minSdk | **28（安卓 9）**：最省事，覆盖绝大多数在用设备 |
| D6 | X11 | **本期不做**，留后续版本 |
| D7 | 插件体系 | **双体系**：① 兼容 Termux 插件契约（原 Termux 插件能跑，通用）；② moxsh 自研插件框架，**独立签名、与 Termux 不互通**，且**带图形化（玻璃）界面** |
| D8 | 玻璃默认 | **按设备性能自动**（API31+ 实时模糊；低版本静态回退）；用户可在设置里手动开/关 |
| D9 | **语言栈** | **多语言混合**：Kotlin/Compose(UI) + **Rust(内核全包)** + **手写 NEON 汇编(热路径)** + **C/C++(仅 Android JNI 桥接，最小存在)** |
| D10 | **Rust 覆盖范围** | 终端内核 + **包管理器解析/验签** + **IPC 加固握手** + **兼容 shim** + **加密安全模块** |
| D11 | **构建编排** | **cargo-ndk 预构建**：Gradle `preBuild` 依赖 `cargo ndk` 产出 `.so` 进 `jniLibs`；CI 装 Rust 工具链 + cargo-ndk |
| D12 | **PRoot 路线** | **自研 Rust 引擎**（ptrace 路径翻译）+ **融合上游 proot 6.x 行为**（syscall 覆盖、`-R`/`-b`/`-0` 语义、`/proc/self/exe`），不抄其代码 |
| D13 | **图形化范围** | 四件套全做：发行版管理器 + 图形包管理器 + 资源监控 + 小白引导向导；小白基本不碰命令行 |
| D14 | **预置发行版** | Ubuntu / Debian / Kali / Alpine + 自定义 rootfs tar（URL + sha256） |
| D15 | **包格式** | 插件与 AI 技能统一 **.mox 格式**（tar + manifest + HMAC-SHA256 签名 + 权限声明），外来格式 import 兼容 |
| D16 | **商店范围** | 全链路：浏览/一键安装/卸载/启停/更新 + 本地上传 + 云端源接口（内置目录兜底） |
| D17 | **售卖预留** | manifest 含 `author/price/purchased` 字段；支付流程与平台抽成后续版本接入（本期仅字段与 UI 徽章） |
| D18 | **AI 接入** | OpenAI 兼容（自定义 base URL + Key）+ 国内预置（DeepSeek/智谱/通义/Kimi）+ 本地小模型接口预留（M7）；高危操作玻璃确认卡 |

---

## 2. 整体分层架构

| 层 | 名称 | 职责 | 主要语言 |
|---|---|---|---|
| L6 | 交付层 | 云端 CI 构建、签名、分发 | YAML / Gradle |
| L5 | 插件层 | 双体系：Termux 兼容插件宿主 + moxsh 玻璃插件框架 | Kotlin/Compose + Rust(逻辑) |
| L4 | 应用框架层 | Activity/Service、会话管理、命令分发、设置/权限 | Kotlin/Compose |
| L3 | 终端内核层 | PTY、终端协议解析、回滚缓冲、渲染后端（**Rust + NEON 汇编**） | **Rust** + **汇编** |
| L2 | 运行环境层（自研） | 自研 bootstrap/loader/exec/shim，契约对齐 termux-packages | Rust + C 桥接 |
| L1 | 玻璃 UI 层 | 全局液态玻璃外壳、动效、手势、IME（Compose） | Kotlin/Compose |

minSdk 28 影响：实时模糊 `RenderEffect` 需 API 31+；**API 28–30 用静态半透明/预模糊回退**，API 31+ 才实时模糊（见 D8）。

---

## 3. 终端内核层（Rust + NEON 汇编，clean-room）★核心

> 原 v0.3 把内核写成"C/C++"，v0.4 起**内核 100% 用 Rust 实现，热点路径用 NEON 汇编**，C/C++ 只保留与 Android 的 JNI 桥接（见 D9）。

- **PTY/会话**：Rust 自研 `session` 模块，`openpty`+`execve` 在独立 native 线程，信号转发（SIGWINCH/SIGCHLD）走 Rust 所有权管理的生命周期守卫。
- **协议解析**：VT100/xterm 转义解析器纯 Rust 从零写，零拷贝写环形缓冲；UTF-8 解码热路径调用 **NEON 汇编** `moxsh_decode_utf8_neon`（每条输入百万次级）。
- **渲染后端**：glyph atlas + `HardwareLayer`；**滚动 diff 与字形 blit 用手写 NEON**（`moxsh_scroll_diff` / `moxsh_blit_glyphs`），120Hz 只重绘脏区域。
- **回滚缓冲**：默认 100k 行，超出 mmap 文件兜底防 OOM（Rust `mmap` 封装 + `Drop` 自动清理）。
- **Unicode 宽度**：自研宽度表（参考 TR11 思路），emoji 序列合单格。
- **C/C++ 桥接**：仅 `cpp/bridge.cpp` 实现 JNI 入口，转发到 Rust 的 `extern "C"` C-ABI；asm 通过 `build.rs` 由 NDK clang 编译进同一 `libmoxshcore.so`。

### 3.1 多语言混合架构与语言选型（联网证据）

| 语言 | 在本项目的角色 | 关键利弊（证据） |
|---|---|---|
| **Kotlin / Compose** | UI（液态玻璃）、应用生命周期、权限、前台服务、JNI 胶水 | 唯一能优雅做 Material3 Expressive + `RenderEffect` 实时模糊的层；原生层只当它"桥"[codemia](https://codemia.io/knowledge-hub/path/android_studio_gradle_and_ndk) |
| **Rust** | **内核全包**：会话/PTY、转义解析、回滚、渲染调度、包管理解析/验签、IPC 鉴权、兼容 shim、加密 | **Google 2025 数据**：Rust 内存安全漏洞密度约 **0.2/百万行 vs C/C++ ~1000/百万行（≈1000× 更低）**，回滚率仅 C++ 的 **1/4**，审查耗时少 **25%**[google blog](https://blog.google/security/rust-in-android-move-fast-fix-things)；[IT之家](https://www.ithome.com/0/897/719.htm) 同口径 |
| **NEON 汇编 (arm64)** | 最热路径：UTF-8 解码、字形 blit、滚动 diff、校验和 | 编译器自动向量化通常够；但"每帧百万次"的字形 blit / 大输出回滚 diff，手写 SIMD 仍有明显收益 |
| **C / C++** | **仅** JNI 桥接（`bridge.cpp`）与极少量 Android NDK 互操作 | NDK 一等公民、`externalNativeBuild` 成熟，但内存安全靠人[positioniseverything](https://www.positioniseverything.net/using-c-and-c-code-in-an-android-app-with-the-ndk/) |
| Go / Zig | （本期不默认）交叉编译辅助二进制 | 增加构建面，M5 再评估 |

**结论**：Rust 是 2025 年安卓原生层的**事实标准安全选择**——Google 自己的 Binder、Keystore、DNS-over-HTTP/3、AVF 都已用 Rust 重写[davthecoder](https://www.davthecoder.com/blog/rust-on-android-how-to-use-it-debug-it-and-why)。把"内存安全 + 高频"的内核放进 Rust、UI 留 Kotlin、极致热点用汇编，是最稳的混合架构。

### 3.2 构建链（cargo-ndk，D11）

```
Kotlin/Compose ──JNI──> bridge.cpp (C++ 仅桥接) ──C-ABI──> Rust cdylib (libmoxshcore.so)
                                                        │
                                              NEON .s (global_asm / build.rs 链入)
```
- `terminal-core/` 是 Android library 模块：`build.gradle.kts` 里 `preBuild.dependsOn(cargoNdkBuild)`，`cargoNdkBuild` 调 `cargo ndk -t arm64-v8a -t x86_64 -o src/main/jniLibs build --release`。
- `build.rs` 用 `cc` crate 把 `cpp/bridge.cpp` 与 `arch/neon.s` 经 NDK clang 编译并链入同一 cdylib。
- `cargo-ndk` v4.1.2 成熟、约 5 万次/周下载[davthecoder](https://www.davthecoder.com/blog/rust-on-android-how-to-use-it-debug-it-and-why)。
- **非 arm64 目标（x86_64 模拟器）**：NEON 路径由 `#[cfg(target_arch="aarch64")]` 守卫，回退纯 Rust 实现，保证 CI 与多 ABI 可编。

---

## 4. 运行环境层（自研 + 契约兼容）★核心

> 全部自研，但目标对齐 termux-packages 的**磁盘/ABI 契约**，使官方 `.deb` 能直接安装运行。

| 能力 | 自研实现（语言） | 兼容契约 |
|---|---|---|
| rootfs | 自研 bootstrap 生成器（Rust） | 提供 termux-packages 期望的前缀路径/目录结构 |
| 包管理 | **双协议自研**（Rust）：① 我们新格式 ② 兼容 apt/deb 索引与 `.deb` | 解析仓库 `Packages`/`Release`、装 `.deb`、维护 `status` 库 |
| shebang/exec | 自研 exec 包装（Rust，termux-exec 思路） | `#!/bin/sh`、`env python` 正确解析 |
| 动态链接 | 自研 loader 策略（Rust，参考 linker 思路） | 满足 `.deb` 二进制的 `RUNPATH`/`NEEDED`，找到 `$PREFIX/lib` |
| 语言运行时 | Python/Node/Go 等由包提供（来自 termux 仓库） | **100% 通用** |

**兼容 shim（Rust 实现，D10）**：在自研 rootfs 中建立与 termux-packages 一致的前缀约定，并提供 shim 还原 `LD_LIBRARY_PATH`、`/proc/self/exe` 行为、shebang 重写等语义——shim 是我们代码，语义对齐，实现"无 Termux 代码、却兼容 Termux 生态"。

---

## 5. 命令分发与 IPC（Rust 加固，D10）

- `RunCommandService` 等价物收 `ACTION_RUN_COMMAND`；自研 `am` 等价本地 socket 服务。
- 插件通信：自研 `LocalServerSocket` + **Rust 加固协议**（`ipc.rs`：nonce+签名指纹鉴权、foreground service 抗 Phantom Process Killer、带 schema 帧、超时背压、断开自愈）。
- **系统性改进**：Termux 原 IPC 无鉴权、同 UID 应用可连、后台被杀后 socket 失效；moxsh 在 Rust 层做握手鉴权与自愈。

---

## 6. 液态玻璃 UI 体系

- API31+：`RenderEffect.createBlurEffect` + `GraphicsLayer.renderEffect` 实时模糊；API28–30：静态半透明/预模糊壁纸回退。
- 统一 `GlassSurface`（半透明+高光描边+动态取色），组件库 `GlassTopBar/BottomBar/Dialog/FAB/Sheet` 全应用复用。
- **默认按设备性能自动**（D8）；设置可强制开/关。
- 玻璃层不阻挡触摸与 IME；输入法弹起自动避让。

---

## 7. 性能优化

冷启动（bootstrap 并行解压+预载 so）/ 渲染（glyph atlas+NEON diff+120Hz）/ 内存（回滚预算+mmap）/ 后台（foreground service 抗杀）/ I/O（native 环形缓冲零拷贝）/ **安全与交付（Rust 低回滚率、少审查轮次）**。

---

## 8. 国内优化

Gradle/Maven 走腾讯/阿里镜像 + CI 同配；NDK/SDK 预缓存或走国内镜像；APT/仓库默认清华/中科大/阿里，`moxsh-change-repo` 国内源置顶 + 我们新格式仓库托管国内 CDN；Rust crates 镜像（`rsproxy.cn` / `sparse` 国内源）；中文文档/社区。

---

## 9. 插件体系（双体系，D7）

| 体系 | 签名 | 界面 | 用途 |
|---|---|---|---|
| **兼容宿主** | 认 Termux 签名契约 | 无（透传） | 让现有 Termux 插件（API/Boot/...）能直接装能用 |
| **moxsh 原生框架** | 自研签名（与 Termux 不互通） | **玻璃图形化** | 我们自己的插件，带 UI、可上架、体验统一 |

原生框架内置重写：API（加固 IPC+玻璃交互）、Boot（foreground 保活）、Float（悬浮玻璃窗+自绘）、Styling（动态玻璃主题）、Tasker（保留 `plugin.xml` 契约+内部加固 socket）、Widget（玻璃卡片）。X11 本期不做（D6）。

---

## 10. 云端 CI / 构建

GitHub Actions `build.yml`：
1. JDK17 → Android SDK+NDK(缓存) → **Rust 工具链 + `cargo-ndk` + `ANDROID_NDK_HOME`**（D11）
2. Gradle 缓存(镜像) → 解码签名(secrets)
3. `gradle assembleRelease`（内部 `preBuild` 已先跑 `cargo ndk` 产出 `libmoxshcore.so`）
4. 上传 APK/AAB

> 沙箱限制：本环境无 Android SDK，**APK 由云端 CI 在 push 后产出**；沙箱交付可编译工程 + CI 配置 + 源码分析。

---

## 11. 风险与踩坑（来自 `build-pitfalls.md` 48 条）

targetSdk/exec、loader/`/proc/self/exe`、Phantom Process Killer、Scoped Storage、SELinux/noexec、渲染掉帧、CI 国内网络超时。**自研运行环境后，前 3/5 条风险由我们自己掌控，但必须自己正确处理。** Rust 把内存安全类风险（历史上占安卓漏洞 76%）降到极低。

---

## 12. 分阶段路线

| 阶段 | 目标 |
|---|---|
| M1 | 工程脚手架 + CI（含 cargo-ndk）+ 玻璃外壳 + 自研运行环境骨架 |
| M2 | **Rust 终端内核 + NEON 热路径** + 渲染优化 |
| M3 | **Rust 双协议包管理器**（新格式 + apt/deb 兼容）+ 验签 |
| M4 | 双插件体系 + **Rust IPC 加固 + 兼容 shim** |
| M5 | 国内优化（含 crates 镜像）+ 性能调优 + 发布 |

---

## 13. 工程脚手架（当前已产出）

位置：`/workspace/moxsh/`，GitHub Actions 在 `/workspace/.github/workflows/build.yml`。

| 路径 | 内容 |
|---|---|
| `settings.gradle.kts` | 多模块注册 |
| `app/` | Compose 玻璃外壳 + 双插件宿主入口 |
| `terminal-core/` | **Rust 内核 + NEON 汇编 + C++ JNI 桥接**（Cargo + cargo-ndk + build.rs） |
| `shared/` | Kotlin 共享库：执行引擎入口/兼容 shim 桩 |
| `plugins/` | 各插件模块（玻璃 UI 骨架） |
| `.github/workflows/build.yml` | 云端构建 APK/AAB（含 Rust 工具链） |

> 说明：脚手架为**阶段一可编译骨架**，模块边界/接口/CI 已就位；Rust 内核各子系统以接口+最小实现落地，标注 TODO 待填充（M2–M4）。

---

## 14. 已交付文档

- `docs/architecture.md`（本文件）
- `docs/commands.md`（Termux 命令体系盘点，来自源码）
- `docs/plugins-rewrite.md`（7 插件重写方案）
- `research/termux-plugins-shortcomings.md`（插件缺点调研，12 条）
- `research/build-pitfalls.md`（构建踩坑，48 条）
- `/workspace/termux-src/`（Termux 全量官方源码，供参考）

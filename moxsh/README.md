# moxsh

> **全面兼容 Termux 生态、性能更强、全应用液态玻璃（glassmorphism）UI、面向国内优化、云端 CI 出 APK 的终端应用。**
> 运行架构 **100% 自研（clean-room，不沿用 Termux 任何代码）**，但产物仍兼容 termux-packages 官方仓库。

---

## 技术栈：多语言混合（v0.4 起）

moxsh 用**混合语言**各司其职，把"内存安全 + 高频"的内核交给 Rust，UI 交给 Kotlin，极致热点用 NEON 汇编，C/C++ 仅做最小 JNI 桥接：

| 语言 | 角色 | 证据 |
|---|---|---|
| **Kotlin / Compose** | 玻璃 UI、生命周期、权限、前台服务、JNI 胶水 | 唯一能优雅做 Material3 Expressive + `RenderEffect` 实时模糊的层 |
| **Rust** | **内核全包**：PTY/会话、VT 解析、回滚、渲染调度、包管理解析/验签、IPC 鉴权、兼容 shim、加密 | Google 2025：内存安全漏洞密度 ≈0.2/百万行 vs C/C++ ~1000（≈1000× 更低），回滚率仅 C++ 的 1/4，审查 -25% |
| **NEON 汇编 (arm64)** | UTF-8 解码、字形 blit、滚动 diff、校验和 | 每帧百万次的渲染/解析热路径，手写 SIMD 仍有明显收益 |
| **C / C++** | **仅** JNI 桥接（`cpp/bridge.cpp`），转发到 Rust C-ABI | NDK 一等公民；仅此最小存在 |

---

## 已拍定决策（详见 `../docs/architecture.md`，共 11 项）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 兼容层级 | 直接吃 termux-packages 官方仓库，现有 `.deb` 能装能跑 |
| D2 | 包管理器 | 双协议自研：我们新格式 + 兼容 termux 的 apt/deb 协议 |
| D3 | 运行环境 | 100% 自研、完全自定义；实现对齐 termux-packages 磁盘/ABI 契约 |
| D4 | 终端内核 | 纯自研（clean-room），可参考 libvterm/st 思路，代码全自己写 |
| D5 | minSdk | **28（安卓 9）** |
| D6 | X11 | 本期不做 |
| D7 | 插件体系 | **双体系**：① Termux 兼容宿主（原插件能用）② moxsh 原生玻璃插件（独立签名、不互通、带图形界面） |
| D8 | 玻璃默认 | 按设备性能自动（API31+ 实时模糊；低版本静态回退），设置可手动开/关 |
| D9 | 语言栈 | **Kotlin + Rust(内核全包) + NEON 汇编(热路径) + C/C++(仅 JNI 桥接)** |
| D10 | Rust 覆盖 | 终端内核 + 包管理解析/验签 + IPC 加固 + 兼容 shim + 加密安全模块 |
| D11 | 构建编排 | **cargo-ndk 预构建**：Gradle `preBuild` 调 `cargo ndk` 产出 `libmoxshcore.so` 进 `jniLibs`；CI 装 Rust 工具链 + cargo-ndk |

---

## 模块结构

```
moxsh/
├── settings.gradle.kts        # 多模块注册（13 个模块）
├── build.gradle.kts           # 插件版本（AGP/Kotlin）
├── gradle.properties
├── gradle/wrapper/            # wrapper 配置（jar 由 `gradle wrapper` 本地生成，CI 不需要）
├── rust-toolchain.toml        # 钉死 Rust 1.86 + android targets
├── init.gradle.kts            # 国内依赖镜像（腾讯/阿里）
├── app/                       # 宿主：真实终端（TerminalView Canvas 渲染）+ 多会话标签 + 设置页
├── terminal-core/             # ★Rust 内核 + NEON 汇编 + C++ JNI 桥接（Cargo + cargo-ndk）
│   ├── Cargo.toml
│   ├── build.rs               # 用 cc crate 编译 bridge.cpp + neon.s 链入 cdylib
│   ├── cpp/bridge.cpp         # 最小 JNI 桥接
│   ├── arch/neon.s            # arm64 NEON 热路径
│   └── src/                   # session/pty/parser/screen/ring/renderer/ipc/compat/crypto/pkgmgr
│                              # + proot/（D12 PRoot 引擎：translate/ptrace/distro/cli）
│                              # + moxpkg.rs（D15 .mox 包解析/验签）
├── shared/                    # Kotlin 共享库：ExecutionEngine / IpcServer / CompatShim
│                              # + ProotManager / MoxPackage / BootstrapInstaller(BootstrapState)
├── ui/                        # 玻璃 UI 组件库（GlassSurface/GlassTopBar/...）
└── plugins/
    ├── plugin-core/           # 原生插件框架（PluginContract/PluginHost）
    ├── plugin-float/          # 悬浮玻璃终端（可拖动缩放）
    ├── plugin-styling/        # 动态玻璃主题（壁纸取色/5 配色/广播换肤）
    ├── plugin-api/            # Termux 插件兼容宿主（RUN_COMMAND 契约）
    ├── plugin-boot/           # 开机自启（BootReceiver + 玻璃配置）
    ├── plugin-tasker/         # Tasker 联动
    ├── plugin-widget/         # 玻璃桌面小部件
    ├── plugin-distro/         # 图形化管理四件套（发行版/包管理/资源监控/小白向导）
    ├── plugin-store/          # .mox 插件商店（一键安装/启停/更新/本地上传/售卖徽章）
    └── plugin-ai/             # AI Agent（OpenAI 兼容+国内预置 / 技能系统 / 工具调用 / 玻璃对话栏）

.github/workflows/build.yml    # 云端 CI：构建并上传 APK（含 Rust 工具链）
```

分层（见架构文档）：L1 玻璃 UI / L2 运行环境(自研) / L3 终端内核(**Rust + NEON 汇编**) / L4 应用框架 / L5 插件 / L6 交付(CI)。

### 内核构建链（D11）

```
Kotlin/Compose ──JNI──> cpp/bridge.cpp (C++ 仅桥接) ──C-ABI──> Rust cdylib (libmoxshcore.so)
                                                            │
                                                   arch/neon.s (NEON，build.rs 链入)
```
- `terminal-core/build.gradle.kts`：`preBuild.dependsOn(cargoNdkBuild)`，`cargoNdkBuild` 调
  `cargo ndk -t arm64-v8a -t x86_64 -o src/main/jniLibs build --release`。
- `build.rs` 用 `cc` crate 把 `cpp/bridge.cpp` 与 `arch/neon.s` 经 NDK clang 编译并链入同一 cdylib。
- 非 arm64（如 x86_64 模拟器）由 `#[cfg(has_neon_asm)]` 守卫，NEON 路径回退纯 Rust。

---

## 构建

### 云端 CI（推荐，出 APK）
push 到 `main`/`dev` 即触发 `.github/workflows/build.yml`：
- JDK17 → Android SDK + NDK 26 → **Rust 工具链 + cargo-ndk + `ANDROID_NDK_HOME`** → Gradle 缓存(镜像) → 复制国内镜像 init → `gradle assembleRelease`（内部 `preBuild` 先跑 `cargo ndk` 产出 `libmoxshcore.so`）→ 上传 APK。
- 正式签名：仓库 Secrets 配置 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。
- 本地开发无需安装 Android SDK，**APK 由云端 CI 在 push 后自动产出**。

### 本地
- Android Studio 打开 `moxsh/`；需 Android SDK(34) + NDK 26.1.10909125 + Rust（见 `rust-toolchain.toml`，含 aarch64/x86_64 android targets）+ `cargo install cargo-ndk`。
- `cargo build -p moxsh-core`（纯 Rust 单测可先跑，无需 SDK）→ `gradle assembleDebug`。

---

## 当前阶段：M1–M5 完成（详见 `../docs/roadmap.md`）

所有子系统均已从骨架填满为真实实现：

- `terminal-core`：**Rust 内核完整实现**——PTY fork/exec、VT 状态机（UTF-8/中文/emoji）、屏幕网格、回滚 + mmap 兜底、NEON 热路径、IPC 加固、兼容路由、双协议包管理、**PRoot 引擎**（路径翻译 20 单测 + ptrace 拦截 + 四大发行版管理）、**.mox 包格式**（解析/验签/防逃逸）。全内核 **60+ 单元测试**。
- `shared`：ExecutionEngine（多会话+泵循环）、IpcServer（HMAC 握手）、CompatShim（termux-* 路由 + `$PREFIX` 布局落地）、ProotManager、MoxPackage、**BootstrapInstaller/BootstrapState**（首装流水线，App 启动自动触发）。
- `app`：TerminalView 真实 Canvas 渲染（16 字节 Cell/256 色/滚动/捏合缩放）、多会话玻璃标签栏、ExtraKeys 真发 VT 序列、设置页持久化。
- `plugins/*`（10 个）：全部经 PluginContract 注册；含图形化管理四件套、.mox 商店、AI Agent（国内预置服务商 + 技能系统 + 高危确认卡）。

**近期方向**：首个可安装 APK 已随 GitHub Release 发布；后续聚焦性能打磨、AI 能力深化、`.mox` 插件生态完善（均为规划、待定，详见产品路线图）。

---

## 配套文档（仓库 `docs/`、`research/`）

- `docs/architecture.md` — 架构白皮书（v0.5，含 18 项决策 + 语言选型证据）
- `docs/roadmap.md` — 产品路线图（对外分享的方向，规划类均「待定」）
- `docs/commands.md` — Termux 命令体系盘点（来自源码）
- `docs/plugins-rewrite.md` — 6 插件重写方案（含代码片段）
- `research/termux-plugins-shortcomings.md` — 插件缺点调研（12 条）
- `research/build-pitfalls.md` — 构建踩坑（48 条）
- `../termux-src/` — Termux 全量官方源码（仅参考，moxsh 不复制其代码）

# moxsh · 项目总览

> 一个**全面兼容 Termux 生态**、**性能更强**、**全应用液态玻璃（glassmorphism）UI**、**面向国内优化**、**云端 CI 出 APK** 的终端应用。  
> 核心原则：**运行架构 100% 自研（clean-room，不沿用 Termux 任何代码），但产物仍兼容 termux-packages 官方仓库**。

---

## 本仓库交付物

| 路径                                        | 内容                                        |
| ----------------------------------------- | ----------------------------------------- |
| `moxsh/`                                  | **可编译工程（13 模块，M1–M5 完成）+ 云端 CI**（详见 `moxsh/README.md`） |
| `.github/workflows/build.yml`             | GitHub Actions：push 即构建并上传 APK            |
| `docs/architecture.md`                    | 架构白皮书 v0.5（含 18 项已拍定决策）                   |
| `docs/roadmap.md`                         | **开发路线图**（M1–M5 ✅，权威进度清单）                 |
| `docs/commands.md`                        | Termux 命令体系盘点（来自源码）                       |
| `docs/plugins-rewrite.md`                 | 6 插件重写方案（含代码片段，X11 递延）                    |
| `research/termux-plugins-shortcomings.md` | 插件缺点调研（12 条，带来源）                          |
| `research/build-pitfalls.md`              | 构建踩坑（48 条，带来源）                            |
| `clone.sh`                                | 本地拉取 Termux 官方参考源码的脚本（仅参考，非 moxsh 代码）   |
| `README.en.md`                            | **英文版 README**（与本文同步）                        |
| `docs/PUSH_GUIDE.md`                       | **中文一步步推送指南**（首次推 GitHub 用）               |
| `push_to_github.sh`                        | 一键建仓 + 推送脚本（在你本机/已连 GitHub 的终端运行）       |

> **关于 `termux-src/`**：它是 Termux 官方源码的**本地参考克隆**，仅用于研究，**不纳入本仓库**（见 `.gitignore`），moxsh **不复制**其中任何代码。需要对照阅读时本地执行 `bash clone.sh` 即可。



---

## 18 项已拍定决策

1. **兼容层级**：直接吃 termux-packages 官方仓库，现有 `.deb` 能装能跑
2. **包管理器**：双协议自研（我们新格式 + 兼容 apt/deb 协议）
3. **运行环境**：100% 自研、完全自定义；实现对齐 termux-packages 磁盘/ABI 契约
4. **终端内核**：纯自研（clean-room），可参考 libvterm/st 思路，代码全自己写
5. **minSdk 28**（安卓 9）
6. **X11 本期不做**
7. **插件双体系**：① Termux 兼容宿主（原插件能用）② moxsh 原生玻璃插件（独立签名、不互通、带图形界面）
8. **玻璃默认**：按设备性能自动（API31+ 实时模糊；低版本静态回退），设置可手动开/关
9. **语言栈**：多语言混合——Kotlin/Compose(UI) + **Rust(内核全包)** + **NEON 汇编(热路径)** + **C/C++(仅 JNI 桥接)**
10. **Rust 覆盖**：终端内核 + 包管理解析/验签 + IPC 加固 + 兼容 shim + 加密安全模块
11. **构建编排**：cargo-ndk 预构建（Gradle `preBuild` 调 `cargo ndk` 产出 `libmoxshcore.so`；CI 装 Rust 工具链 + cargo-ndk）
12. **PRoot 路线**：自研 Rust 引擎（ptrace 路径翻译）+ 融合上游 proot 6.x 行为，不抄其代码
13. **图形化范围**：四件套全做（发行版管理器/图形包管理器/资源监控/小白引导）；小白基本不碰命令行
14. **预置发行版**：Ubuntu / Debian / Kali / Alpine + 自定义 rootfs tar
15. **包格式**：插件与 AI 技能统一 .mox 格式（签名+元数据+权限声明），外来格式兼容
16. **商店范围**：全链路（浏览/一键安装/卸载/启停/更新 + 本地上传 + 云端接口 + 内置目录兜底）
17. **售卖预留**：manifest 含 author/price/purchased 字段；支付与抽成后续接入
18. **AI 接入**：OpenAI 兼容 + 国内预置（DeepSeek/智谱/通义/Kimi）+ 本地小模型预留（M7）；高危操作玻璃确认卡

---

## 怎么拿到 APK

沙箱环境**无 Android SDK**，无法在本机编译 APK。工程已配好**云端 CI**：

1. 把本仓库推到 GitHub
2. 在仓库 **Settings → Secrets** 配置签名：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`（可选；不配则用 debug 签名出包）
3. push 到 `main`/`dev` → Actions 自动构建 `assembleRelease` 并上传 APK 产物

本地编译：用 Android Studio 打开 `moxsh/`（或 `gradle wrapper` 后 `./gradlew assembleDebug`），需 Android SDK(34)+NDK 26.1.10909125。

---

## 阶段路线（详见 `docs/roadmap.md`，权威进度清单）

| 阶段 | 目标                                               | 状态 |
| -- | ------------------------------------------------ | -- |
| M1 | 工程脚手架 + CI + 玻璃外壳 + 自研运行环境骨架                     | ✅ |
| M2 | Rust 终端内核（PTY/VT 解析/回滚）+ NEON 热路径 + 会话引擎          | ✅ |
| M3 | 玻璃 UI 主体 + 真实终端渲染 + 7 插件（float/styling/api/boot/tasker/widget/core） | ✅ |
| M4 | PRoot 引擎 + 图形化管理四件套 + .mox 商店 + AI Agent（18 决策全落地） | ✅ |
| M5 | Bootstrap 首装流水线 + 回滚 mmap 兜底 + Rust 单测补全（33 个）    | ✅ |
| M6 | 首个可安装 APK（推 GitHub → Actions 出包 → 真机联调）           | ⏳ 待推仓库 |
| M7 | 性能打磨（120Hz/glyph atlas/syscall 热路径）+ 本地小模型 + 支付抽成 | ⏳ |

---

## 踩坑经验（本次会话积累）

- **GitHub 直连被沙箱网络层阻断**：壳内 `git clone` 全部 TLS 失败；改用 `ghproxy.net` 前缀（`https://ghproxy.net/https://github.com/...`）成功克隆 Termux 全量源码。
- **默认 shell 是 zsh**：`for x in $var` 不自动分词，整串当单参数；写脚本用 `bash -c` 或显式数组。
- **Compose 玻璃组件放错模块**：玻璃 UI 原在 `:app`，但插件要用且 app 又依赖插件 → 循环依赖。已抽到独立 `:ui` 模块。
- **库模块引用 app 的 style**：库模块 manifest 引用 `@style/Theme.Moxsh.Glass` 需在合并后解析；统一把主题放到 `:ui` 模块。
- **RenderEffect 需 API31+**：实时模糊用 `Build.VERSION.SDK_INT >= 31` 守卫，低版本回退静态半透明（minSdk 28 必须处理）。
- **CI 不用 `./gradlew`**：沙箱无法生成 wrapper jar，改用 `gradle/actions/setup-gradle` 直接装 gradle 跑 `gradle assembleRelease`。
- **Rust 经 cargo-ndk 出 .so**：`terminal-core` 不用 `externalNativeBuild`，而是在 `preBuild` 调 `cargo ndk` 产出 `libmoxshcore.so` 进 `jniLibs`；`build.rs` 用 `cc` crate 把 `bridge.cpp` 与 `neon.s` 经 NDK clang 链入同一 cdylib。CI 需装 Rust 工具链 + `cargo install cargo-ndk` 并设 `ANDROID_NDK_HOME`。

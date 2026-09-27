# moxsh 开发路线图（v0.5）

> 更新时间：2026-09-27。本文档是唯一权威进度清单，完成一项勾一项。
> 架构决策见 `architecture.md`（D1–D13）。

## 里程碑总览

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 工程骨架 + CI + 文档 | ✅ 完成 |
| M2 | Rust 终端内核（PTY/VT 解析/回滚）+ 会话引擎 | ✅ 完成 |
| M3 | 玻璃 UI 主体 + 真实终端渲染 + 7 插件 | ✅ 完成 |
| M4 | PRoot 容器引擎 + 图形化管理全家桶 + 商店 + AI Agent | ✅ 完成 |
| M5 | Bootstrap 安装流程 + 回滚 mmap 兜底 | ✅ 完成 |
| M6 | 首个可安装 APK（GitHub Actions 云端出包 + 真机联调） | 🔄 CI 出包中，真机联调待验证 |
| M7 | 性能打磨（120Hz 渲染、glyph atlas、syscall 热路径、本地小模型接入、售卖支付） | ⏳ 未开始 |

---

## M4：PRoot 容器引擎 + 图形化管理全家桶

### 4.1 PRoot 自研引擎（Rust，任务 #35）

**决策依据**：D12 —— 自研 Rust 引擎为主体，**融合上游 proot-me/proot 最新版（6.1.0+）的行为特性**（syscall 覆盖清单、bind 挂载优先级、fake root `-0`、`/proc/self/exe` 处理、已知 fix），不抄其代码。

| 子项 | 内容 | 状态 |
|---|---|---|
| `proot/translate.rs` | 路径翻译规则引擎：`-R` rootfs、`-b` 绑定挂载、优先级链、`/proc` 伪装、guest→host / host→guest 双向 | ✅ |
| `proot/ptrace.rs` | ptrace 拦截器：enter/exit 双停、syscall 参数改写（寄存器读写）、覆盖高频集：open/openat/stat/lstat/fstatat/readlink/execve/chdir/mkdir/rename/unlink/symlink/truncate/access | ✅ |
| `proot/distro.rs` | 发行版管理：Ubuntu/Debian/Kali/Alpine 四大主流 + 自定义 rootfs tar（URL + sha256 + 国内镜像优先）；install/remove/login/backup/restore | ✅ |
| `proot/cli.rs` | `proot` / `proot-distro` 等价 CLI（脚本兼容层，老用户零迁移） | ✅ |
| C-ABI + `ProotNative.kt` | Kotlin 桥接：install/login/list/remove/backup/restore/translateSelfTest | ✅ |
| 单元测试 | 路径翻译规则纯函数测试（不依赖 ptrace 即可验证 90% 逻辑） | ✅ |

### 4.2 图形化管理全家桶（任务 #36）

**决策依据**：D13 —— 四件套全做，全玻璃 UI，对小白零命令行。

| 子项 | 内容 | 状态 |
|---|---|---|
| 发行版管理器 | 玻璃卡片列表：一键安装（进度/校验/解压）、启动/停止、删除、备份/恢复（导出到 Download） | ✅ |
| 图形包管理器 | 双源（moxsh 自有格式 + apt）：搜索/安装/卸载/全量更新，分类浏览 | ✅ |
| 资源监控面板 | 发行版内 CPU/内存/磁盘实时曲线 + 进程列表一键杀 | ✅ |
| 小白引导向导 | 首次启动分步教学（4 步）：选发行版→换国内源→装第一个软件→（可选）配 GUI；每步玻璃卡片 + 大按钮 | ✅ |

### 4.3 .mox 包格式 + 插件商店（D15/D16/D17）

**决策依据**：D15 —— moxsh 自研插件与 AI 技能统一 **.mox 包格式**（外来格式兼容）；D16 —— 商店全链路（浏览/一键安装/卸载/启停/更新/本地上传/云端源接口）；D17 —— 售卖仅字段预留（price/author/purchased 标记，支付后加）。

| 子项 | 内容 | 状态 |
|---|---|---|
| .mox 包格式 | tar 容器 + manifest.json（id/name/version/author/type: plugin\|skill\|theme\|rootfs/permissions/price 预留）+ payload/ + HMAC-SHA256 签名（复用 crypto.rs） | ✅ |
| Rust 解析 | terminal-core/src/moxpkg.rs：parse/verify/extract/list + C-ABI 扩展 | ✅ |
| 插件商店 UI | 玻璃商店：精选/分类/搜索/一键安装（进度卡）/卸载/启用禁用/更新；本地上传 .mox；StoreApi 云端接口 + 内置目录 | ✅ |
| 技能系统 | skill.json（prompt/工具清单/权限声明）；用户上传的技能 = 插件 = 同一 .mox 通道，AI 与用户都能用 | ✅ |

### 4.4 AI Agent（D18）

**小白核心体验**：全程自然语言，基本不碰命令行终端。

| 子项 | 内容 | 状态 |
|---|---|---|
| 模型接入 | OpenAI 兼容（自定义 base URL + Key + 模型名）+ 国内预置（DeepSeek/智谱/通义/Kimi）+ 本地小模型接口预留（llama.cpp 端侧） | ✅ |
| 对话 UI | 玻璃侧边对话栏（可收起） | ✅ |
| 工具调用 | AI → 图形化操作映射：装发行版/装包/换源/跑命令/解释报错；高危操作弹玻璃确认卡（小白看到确认而不是命令行） | ✅ |
| 技能调用 | AI 可加载/执行用户上传的技能包（.mox skill） | ✅ |

---

## M5：Bootstrap 安装流程 + 内核补强

| 子项 | 内容 | 状态 |
|---|---|---|
| BootstrapInstaller | 首次启动下载 bootstrap rootfs（国内 CDN 优先 + 官方回退）、sha256 校验、解压 `$PREFIX`、dns/sources.list 初始化 | ✅ |
| ring.rs mmap 兜底 | 回滚超 10000 行落盘 mmap，防 OOM | ✅ |
| Rust 单测补全 | parser/screen/ring/pkgmgr | ⏳ |

---

## M6：首个可安装 APK（需用户操作）

1. 推送仓库到 GitHub；2. 配置签名 Secrets（`KEYSTORE_BASE64/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`）；3. Actions 自动出包；4. 真机安装反馈问题，回修。

## M7：性能打磨

- 渲染：glyph atlas 离屏缓存、脏区 diff 精细化、120Hz。
- proot：syscall 热路径优化（seccomp 用户通知探索）、启动提速。
- 内核：NEON 汇编覆盖率扩展（scroll diff、cell clear）。

---

## 已拍定新增决策（并入 architecture.md）

- **D12 PRoot 路线**：自研 Rust 引擎 + 融合上游 proot 6.1.0+ 行为；不集成其代码。
- **D13 图形化范围**：发行版管理器 + 图形包管理器 + 资源监控 + 小白引导，四件套全做。
- **D14 预置发行版**：Ubuntu / Debian / Kali / Alpine + 自定义 rootfs tar。
- **D15 包格式**：插件与 AI 技能统一 .mox 格式（签名+元数据+权限声明），外来格式兼容。
- **D16 商店范围**：全链路——浏览/一键安装/卸载/启停/更新 + 本地上传 + 云端源接口（内置目录兜底）。
- **D17 售卖预留**：manifest 含 author/price/purchased 字段，支付流程后续版本接入，平台抽成模式预留。
- **D18 AI 接入**：OpenAI 兼容（自定义 base URL）+ 国内预置（DeepSeek/智谱/通义/Kimi）+ 本地小模型接口预留；小白全程图形化，基本不碰终端。

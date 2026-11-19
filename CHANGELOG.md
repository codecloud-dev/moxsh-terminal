# 更新日志

<p align="center">
  <img src="https://img.shields.io/github/v/release/codecloud-dev/moxsh-terminal?label=最新版本&color=8a7bff" alt="最新版本">
  <img src="https://img.shields.io/badge/格式-Keep%20a%20Changelog-8a7bff" alt="Keep a Changelog">
  <img src="https://img.shields.io/badge/版本号-语义化-3DDC84" alt="语义化版本">
</p>

本项目的所有显著变更都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.2] - 2026-11-19

Rust 内核可靠性深度加固 + 首次引入可复现的真实基准。不改公开 C-ABI、不破坏现有 API。

### 修复（均由真跑 / 回归测试实证，非纸面推演）

- **P0：`moxsh_resize` 尺寸钳制遗漏可致整个 App SIGABRT**。同文件的 `moxsh_open_pty_impl` 明确做了 `cols.clamp(2,500)/rows.clamp(1,200)` 并注释「rows=0 会引发 Screen 内部 rows-1 下溢/除零、负数 as u32 会爆分配」，但 `moxsh_resize_impl` 直接 `cols as u32 / rows as u32`，漏了同一道钳制。真跑回归：向 `moxsh_resize(sess, 80, 0)` 传入 rows=0 → `Screen::resize` 的 `rows - 1` usize 下溢 → 测试进程 **SIGABRT（signal 6）**，而非常规错误码。
  - 修法：提取 `clamp_dims()` 供 open/resize 两个 C-ABI 入口共用；`Screen::new` / `Screen::resize` 再补 `.max(1)` 纵深防御（pub API 防内部误用）。
  - 回归：`resize_zero_rows_is_clamped`、`resize_negative_dims_are_clamped`。
- **残缺 UTF-8 前缀被控制符 / ESC 打断后污染后续字符**。`Parser::ground` 在 utf8 序列未累积完成时遇到 C0 控制符或 ESC 直接切状态，残留前缀与下一个多字节字符首字节拼接 → `utf8_complete` 失败 → 整段被静默丢弃（屏幕出现空白/U+FFFD）。真跑回归：喂 `[E4,B8]` + LF + 完整「中」，`cell.code=0x0`（期望 `0x4e2d`），FAILED。
  - 修法：遇到非续字节（`!(0x80..0xC0).contains(b)`）时先吐出 U+FFFD 并清空 utf8 缓冲。
  - 回归：`utf8_prefix_broken_by_c0_does_not_poison_next_char`、`utf8_prefix_broken_by_escape_does_not_poison_next_char`。
- **`Pty::read` 未处理 EINTR**。同文件的 `write()` 与 `crypto::random_bytes()` 都对 Interrupted 重试，唯独 `read()` 把 EINTR 当致命 IO 错误抛给上层；`moxsh_resize` 向子进程发 SIGWINCH 时读取线程可能被信号打断，导致泵把 EINTR 误判成会话结束。修法：`read()` 改为循环重试 EINTR，EIO→干净 EOF 语义保持不变。

### 性能与真实基准

- 新增 `moxsh/terminal-core/examples/benchmark.rs`：零外部依赖（仅 `std::time::Instant` + `black_box`），真跑六条热路径，可复现命令：`cargo run --release --example benchmark -- 20`。
- 本机实测（release + LTO；数值随机器负载波动，非固定 SLA；以下为主流程最终复核 `cargo run --release --example benchmark -- 20` 输出）：SHA-256 1 MiB **274.90 MB/s**；HMAC-SHA256 64 KiB **281.16 MB/s**；VT 解析（混合 ASCII/UTF-8/CSI）2 MiB **101.71 MB/s**；wcwidth **245725 K ops/s**；RingBuffer push+get **222157 K ops/s**；OverflowBuffer 写 40 MiB（含首次 spill 与多次 mmap 扩容）**853.85 MB/s**、尾部回读 4096B 用时 20ns。

### 版本对齐

- 本版仓库 tag / Release 统一为 `v1.0.2`：`moxsh/terminal-core/Cargo.toml` 与锁文件从 `1.0.0` 升到 `1.0.2`；Android `versionCode` 64 → 65、`versionName` 1.0.1 → 1.0.2；`SECURITY.md` 当前发布线同步为 1.0.2，避免 tag、Rust crate、APK 三处版本各说各话。

### 测试与验证（退出码均单独取，非管道假绿）

- `cargo test --lib`：**74 passed / 0 failed / EXIT=0**（升级前基线 70 passed，新增 4 条回归测试）。
- `cargo clippy --all-targets -- -D warnings`：**EXIT=0**（含新 example；修复一处 `manual_range_contains` 提示）。
- CI 的 7 个 Python 静态自检（Kotlin 词法/配对/SDK 边界/工具链/清单权限/secret 自测与扫描）：**全部 EXIT=0**。
- `cargo audit`：本机未安装 cargo-audit（AUDIT_EXIT=101，工具缺失，非代码问题）；CI 仍由 `taiki-e/install-action` 独立执行。

## [1.0.1] - 2026-11-14

维护与文档合规更新（全家桶依赖体检 / 文档与路线图复核）。不含功能性变更。

### 维护

- **版本号与文档一致性对齐**：README 版本徽章由遗留的 `1.0.0` 更正为 `1.0.1`，与 `moxsh/app/build.gradle.kts` 中已提交的 `versionName 1.0.1` / `versionCode 64` 及 SECURITY 支持矩阵保持一致（此前徽章滞后于代码）。
- **全家桶依赖体检推进**：MoX 全家桶各仓库开展依赖体检与文档合规核查；破坏性依赖变更已按 2026-11-11 审计要求纳入 ignore 守门。

### 文档

- 更新 [docs/roadmap.md](docs/roadmap.md) 至 2026-11-14，新增「近期生态进展」小节，如实记录：MoX 全家桶 npm 包（`@codecloud-dev/agent-core`、`@codecloud-dev/moxwebgpu`）已发布到 `@codecloud-dev` 组织；官网 mox-site 完成安全加固（全响应统一安全头、Functions 漏头修复并上线）；各仓库依赖体检 / 文档合规推进中。
- README「大版本进度」表更正：moxwebgpu 由「npm 待发布」更新为「已发布（npm）」，moxsh-terminal 标记为已发布。
- 修正 README 中过时的版本徽章与里程碑描述。

## [Unreleased]

### 计划中

- 性能打磨：120Hz 渲染、glyph atlas 离屏缓存、syscall 热路径优化
- 本地小模型接入（llama.cpp 端侧）
- 插件售卖支付流程（manifest 中 author/price/purchased 字段已预留）

## [1.0.0] - 2026-10-07

首个大版本里程碑（MoX 全家桶 1.0.0 同步发布）。本版本以**门面统一与里程碑**为主，不含功能性的「做绝」，为后续迭代留出空间。

### 门面 / 里程碑

- **应用图标统一品牌**：自适应图标前景由旧 `> _` 字形替换为仓库 `assets/logo.svg` 的 moxsh「M」终端字形（前景描边沿用近黑 `#0b0e1a`），青→靛→粉渐变背景层保留，App 与官方门户门面一致
- **版本号升 1.0.0**：`versionName` `0.6.2` → `1.0.0`，`versionCode` `62` → `63`
- **路线图板块文档化**：性能打磨（120Hz 渲染 / glyph atlas 离屏缓存 / NEON 向量化）、AI 端侧（llama.cpp）、`.mox` 生态等方向在 [docs/roadmap.md](docs/roadmap.md) 中显式记为「规划中 / 待定」，不在本次一次性实现

### 文档

- README 新增「大版本进度（MoX 全家桶 1.0.0）」对照表，标注 6 个公开仓库的 1.0.0 状态

[1.0.0]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v1.0.0

## [0.6.2] - 2026-10-06

### 新增

- **邮箱 + 密码独立账号(不再强制 GitHub 登录)**:`LoginActivity` 改为 GitHub / 邮箱双 Tab 登录页;邮箱 + 密码自注册(`EmailSignUpCard`,PBKDF2-HMAC-SHA256 + 随机盐);新增统一账号门面 `MoxAccount` 与登录态持久化 `EmailSessionStore`;GitHub 已验证邮箱可一键绑定,密码为可选项。

### 修复

- **版本号回归**:`versionName` 由遗留的 `0.5.0` 修正为 `0.6.2`(此前 v0.6.0 发布包内 APK 仍显示 0.5.0)。
- **4 处编译错误**:`KeyboardOptions` 导入修正为 `androidx.compose.foundation.text`(`GlassWidgets` / `EmailSignUpCard` / `TerminalView` —— 本项目所用 Compose 版本中 `KeyboardOptions` 位于 `foundation.text`,而非 `ui.text.input`);`SettingsScreen` 补 `stringResource` 与 `com.moxsh.R` 导入;`EmailSignUpCard` 注册成功回调改用局部非空 `val`,规避委托属性(自定义 getter)的智能转型失败。

[0.6.2]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v0.6.2

## [0.6.1] - 2026-09-30

### 修复

打通 release 全链路：main 分支 `Build moxsh APK` 由连续 5 次失败转为成功。

- **Gradle Kotlin DSL 脚本编译失败**：`app/build.gradle.kts` 里裸写 `java.util.Properties` / `java.net.URL`，部分 Gradle 版本的脚本编译环境无法解析根标识符 `java`，报 `Unresolved reference: util / net`；改为显式 `import`
- **自适应图标资源非法**：`ic_launcher_background.xml` 用了 VectorDrawable 不支持的 `<defs>` / `linearGradient android:id` 引用与 `<rect android:rx>`，且 `android:width/height` 缺 dp 单位；重写为合规写法（`<path>` + `<aapt:attr>` + `<gradient>`）
- **Compose 1.6.8 API 不匹配**：`Brush.linearGradient` 没有接收 `List<Pair>` 的重载，改传 vararg `Pair`；补 `Spring` / `animateFloatAsState` 导入；`MoxshTokens.XL2/XL` 修正为 `MoxshTokens.Spacing.XL2/XL`
- **Kotlin 变量先声明后使用**：`BootstrapInstaller.kt` 中 `lastError` 的声明晚于阶段 0 的 `catch`，导致 4 处 `Unresolved reference`
- **缺失导入**：补 `MainActivity.kt` 的 `java.io.File` / `java.io.FileOutputStream`，以及 `LoginActivity.kt` 的 `com.moxsh.R`（R 生成在 `applicationId` 包，子包文件不会自动解析）
- **R8 中止打包**：`androidx.security:security-crypto` 传递依赖 Tink，引用 `com.google.errorprone.annotations.*` 与 `javax.annotation.*` 两组仅编译期可见的注解，release classpath 中没有它们，R8 视为硬错误；按 R8 生成的 `missing_rules.txt` 建议以 `-dontwarn` 精确抑制

### 变更

- `.gitignore` 补充 `node_modules/` 与本地仓库副本目录，避免 `git add -A` 将其以 gitlink(160000) 形式误纳入索引

[0.6.1]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v0.6.1

## [0.6.0] - 2026-09-28

### 新增

- **PTY 内核四项强化**：`Pty::write` 处理 `EAGAIN`（非阻塞 master 写满重试）、`ptsname_r` 可重入（消除并发开会话竞态）、`read` 折叠 `EIO` 为干净 `EOF`、新增 `exitStatus` 收割 API（宿主 4 项测试覆盖退出码/运行中等路径）
- **液态玻璃设计系统增强**：`LocalGlassAccent` CompositionLocal 实现主题色流动、`glassPress` 弹性按压动效、`glassSheen` 高光渐变、`GlassSurface` 渐变描边、`GlassFAB` 主题色染色；修复 `isSpecified` 扩展属性导入
- **命令云同步 MVP**：`CommandHistoryStore` 本地 JSONL 离线队列、`SyncClient` 对接云端同步后端（`POST /sync/push`、`GET /sync/pull`，last-write-wins）、`ExecutionEngine.inputRecorder` 喂入钩子、`SettingsScreen`「账号与云同步」卡片（登录/退出 + 开关 + 立即同步）
- **登录入口接线**：主界面 👤 按钮跳转 `LoginActivity`，深链 `moxsh://auth.callback` 回跳解析 token 存入 `SessionStore`

### 修复

- **CI 持续构建失败的真正根因**：`CommandHistoryStore` 的 KDoc 中写了 `与同步端点 /sync/* 一致`，其中的 `/*` 被 Kotlin 词法器当作（嵌套的）块注释起始符，导致该文件第 28 行起的所有声明被整段吞成注释，表现为 `25:40 Missing '}'` + `103:1 Unclosed comment`，并级联出 5 处 `Unresolved reference`。编辑器里肉眼完全看不出来，只有编译器才会炸
- **CI 防回归**：新增 `.github/scripts/check_kotlin_lexer.py` 源码静态自检（按嵌套块注释语义扫描，另查 BOM / CRLF / 裸控制字符），在编译前执行，早失败早定位

### 变更

- GitHub 用户名更名为 `codecloud-dev`（全量替换 + 官网 base64）
- 全套文档（README/CHANGELOG/CONTRIBUTING/SECURITY/CODE_OF_CONDUCT/SETUP/验收）统一液态玻璃视觉装饰

[0.6.0]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v0.6.0

## [0.5.1] - 2026-09-28

### 新增

- **GitHub 账号登录**：OAuth 全流程（App 深链 `moxsh://auth.callback` 回跳 + 后端换发 JWT），`EncryptedSharedPreferences` 加密存取 token
- **轻量同步后端**：Cloudflare Workers + D1（边缘 SQLite），schema 预留会员 / 云同步 / 付费插件市场
- **官网三板块**：路线图、Mox 系列愿景、社区（Discussions）+ 登录入口

### 修复

- `LoginActivity` 缺失 `setContent` 导入导致 compileReleaseKotlin 失败（登录模块首个真实编译通过版本）
- CI 构建加重试与失败诊断（自动建 issue 暴露尾部日志）

[0.5.1]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v0.5.1

## [0.5.0] - 2026-09-27

首个以 GitHub Actions 流水线构建 APK 的版本：终端内核到图形化管理全家桶全链路落地；真机长测与打磨持续进行中。

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

[Unreleased]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=compare/v1.0.2...HEAD
[1.0.2]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v1.0.2
[1.0.1]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v1.0.1
[0.5.0]: https://mox-site.pages.dev/go?r=moxsh-terminal&p=releases/tag/v0.5.0

# 更新日志

<p align="center">
  <img src="https://img.shields.io/github/v/release/codecloud-dev/moxsh-terminal?label=最新版本&color=8a7bff" alt="最新版本">
  <img src="https://img.shields.io/badge/格式-Keep%20a%20Changelog-8a7bff" alt="Keep a Changelog">
  <img src="https://img.shields.io/badge/版本号-语义化-3DDC84" alt="语义化版本">
</p>

本项目的所有显著变更都记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 性能打磨：120Hz 渲染、glyph atlas 离屏缓存、syscall 热路径优化
- 本地小模型接入（llama.cpp 端侧）
- 插件售卖支付流程（manifest 中 author/price/purchased 字段已预留）

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

[0.6.1]: https://github.com/codecloud-dev/moxsh-terminal/releases/tag/v0.6.0

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

[0.6.0]: https://github.com/codecloud-dev/moxsh-terminal/releases/tag/v0.6.0

## [0.5.1] - 2026-09-28

### 新增

- **GitHub 账号登录**：OAuth 全流程（App 深链 `moxsh://auth.callback` 回跳 + 后端换发 JWT），`EncryptedSharedPreferences` 加密存取 token
- **轻量同步后端**：Cloudflare Workers + D1（边缘 SQLite），schema 预留会员 / 云同步 / 付费插件市场
- **官网三板块**：路线图、Mox 系列愿景、社区（Discussions）+ 登录入口

### 修复

- `LoginActivity` 缺失 `setContent` 导入导致 compileReleaseKotlin 失败（登录模块首个真实编译通过版本）
- CI 构建加重试与失败诊断（自动建 issue 暴露尾部日志）

[0.5.1]: https://github.com/codecloud-dev/moxsh-terminal/releases/tag/v0.5.1

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

[Unreleased]: https://github.com/codecloud-dev/moxsh-terminal/compare/v0.6.0...HEAD
[0.5.0]: https://github.com/codecloud-dev/moxsh-terminal/releases/tag/v0.5.0

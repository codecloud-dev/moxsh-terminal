# Termux 官方插件生态：缺陷、局限与重写可行性调研

> 调研对象：`termux/` 组织下的 7 个官方插件
> 目标产品：**moxsh**（全面兼容 Termux、性能更强、液态玻璃 UI 的新终端应用）
> 调研方式：WebSearch / WebFetch（GitHub Issues、Reddit r/termux、官方 Wiki、技术博客）
> 日期：2026-09-27

---

## 0. 执行摘要（关键发现一览）

| 插件 | 一句话定位 | 最突出痛点 | 重写优先级 |
|---|---|---|---|
| Termux:API | 把 Android 能力暴露成 `termux-*` 命令 | 每次调用冷启动 JVM，延迟 0.6s→3.6s；后台/SSH 下会 hang；PLAY/F-Droid 功能分裂 | **高** |
| Termux:Boot | 开机自启脚本 | Android 10+ 后台限制 + 厂商自启动杀进程，脚本"根本不触发" | 高 |
| Termux:Float | 悬浮窗终端 | 2019 年后停更；无多标签/复制粘贴/配色；平板鼠标不可用 | 中 |
| Termux:Styling | 主题/字体 | 仅 `font.ttf`+`colors.properties`；Android 13 等宽字体碎片化；无字号调节 | 中 |
| Termux:Tasker | 与 Tasker 联动 | 权限链复杂（RUN_COMMAND/WRITE_SECURE_SETTINGS 需 adb）；变量名大小写坑；超时静默失败 | 中 |
| Termux:Widget | 桌面小部件 | 目录权限必须 700 否则不显示；不放实时监听；Android 10+ 需"显示在其它应用上"；动态快捷方式数量受限 | 中 |
| Termux:X11 | 在 Android 跑 Linux GUI | 非合成器、靠 Phantom Process Killer 豁免+省电豁免才稳定；黑屏/色差/冻结/高 DPI 过小；GPU 加速仅限部分机型 | **高** |

**跨插件共性痛点（moxsh 必须解决的底层问题）：**

1. **签名/来源绑定**：主应用与所有插件必须用同一来源（F-Droid / GitHub / Play）签名，混装会"静默断联、无错误提示"。[rigacci Termux Problems](https://rigacci.org/wiki/doku.php/doc/appunti/android/termux_problems?do=export_xhtml)
2. **Android 后台限制**：Doze、厂商杀进程（MIUI/HarmonyOS/ColorOS）、Android 12+ 的 Phantom Process Killer 让后台任务/服务随时被 SIGKILL。[Big Iron 保活指南](http://www.bigiron.cc/guides/termux-services-that-survive-the-phantom-process-killer)
3. **权限模型碎片化**：各插件各自要不同权限（后台弹出、存储、位置、无障碍……），用户手动逐一开启，体验割裂。
4. **IPC 多为 Intent + 前台 Service + Unix Domain Socket**，跨进程开销大，错误难诊断。

---

## 1. Termux:API（`termux-api`）

### 1.1 功能与实现原理
通过独立的 `Termux:API` 安卓应用，把电池、相机、剪贴板、定位、传感器、通知、短信等 Android 系统能力封装成 `termux-*` 命令行工具。命令由 `termux-api` 二进制通过 **匿名命名空间 Unix Domain Socket** 把参数交给 API 应用的 `SocketListener`，再由 `BroadcastReceiver`（`TermuxApiReceiver`）按 `api_method` 分派到具体实现类，结果经 `ResultReturner` 写回 socket 返回 stdout。架构详见 [DeepWiki Command Flow](https://deepwiki.com/termux/termux-api/2.1-command-flow)。

### 1.2 已知缺陷 / 性能问题 / 兼容性坑
- **冷启动延迟严重**：`termux-battery-status` 在 Android 12 上耗时从 Android 10 的 ~0.6s 退化到 **~3.6s**，疑似每次调用都触发 `dalvikvm` 生成 OAT。`termux-api` issue [#552](https://github.com/termux/termux-api/issues/552)。
- **SSH/后台会话下挂死（hang）**：从 ssh 会话调用 API 命令会永久阻塞、无输出，需亮屏/唤醒才恢复。issue [#109](https://github.com/termux/termux-api-package/issues/109)。
- **随机崩溃**：电池电流值为 null 时 `NullPointerException` 崩溃（已在 0.53 修复，但同类边界仍在）。[OpenAPK 更新说明](https://www.openapk.net/zh/termuxapi/com.termux.api/)。
- **后台无法运行**：`The application cannot run in the background` 多次被报告（issue #825）。[Issue 列表](http://github.es/termux/termux-api/issues)。
- **剪贴板仅支持文本**：**不支持图片剪贴板**，`ctrl+v` 粘图无效。[earendil 文档](https://github.com/q-qp-p/earendil-works-pi/blob/0ab2aa86af862ca1cf4b0b86fcbee14d00e1441f/packages/coding-agent/docs/termux.md)。
- **来源分裂（Play vs F-Droid）**：Play 版 2024 重建，仅内置部分能力；`termux-camera-photo`、`termux-location`、WiFi/电信/SMS/NFC 等命令 **仅 F-Droid 版 API 插件提供**。[domski 博客](https://blog.domski.pl/?p=4477/)。
- **Android 7 需"受保护应用"**，否则 API 调用永久 hang（issue 334，Wiki 有记）。[Termux:API Wiki](https://wiki.termux.com/index.php?direction=prev&oldid=4043&title=Termux%3AAPI)。
- **权限提示看不懂**：`termux-location` 在 Android 12+ 后台定位被收紧，须"仅在使用中允许"前台定位。

### 1.3 架构层面可改进点
- **避免每次调用冷启动 JVM**：长驻 `KeepAliveService`（已存在 `KeepAliveService.java`，但默认不常驻）应改为"按需常驻 + 空闲回收"，把 3.6s 降到 <50ms。
- **统一二进制与权限校验**：当前命令二进制与分发逻辑分离，错误难定位；可改为单一 AIDL/IPC 边界 + 集中权限网关。
- **能力分层**：把"无需 Android 权限"的命令（toast、vibrate）与"高敏"命令（SMS、联系人、通话）在架构上显式分离，降低攻击面。

### 1.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能**：用 Kotlin 常驻绑定 Service（`androidx.core.app.NotificationCompat` 前台）承载 API 服务，避免反复 fork JVM；传感器/定位用 `Flow` 流式回传，UI 用 Compose `collectAsState` 实时刷新。
- **权限模型**：首次调用某能力时，用 Compose 弹窗引导 **一次性授权**（`registerForActivityResult`），并给出"为何需要该权限"的玻璃拟态说明卡，替代现在的"静默失败+看文档"。
- **IPC 方式**：用 **AIDL / Messenger / 共享内存 + Binder** 替代 socket+广播，带版本协商与显式错误码（不再 `hang`）；后台调用改 `WorkManager`/`ForegroundService`。
- **UI 体验**：把 `termux-dialog`、`termux-notification`、`termux-toast` 等做成本地 Compose 组件库，通知/对话框用液态玻璃毛玻璃材质，支持图片剪贴板、24h 时钟、无障碍标识等长期 issue 诉求。

---

## 2. Termux:Boot

### 2.1 功能与实现原理
监听 `BOOT_COMPLETED` 广播，在设备开机后自动执行 `~/.termux/boot/*.sh` 脚本，用于拉起 sshd、守护进程等。本质是系统广播 → 启动 Termux 服务 → 执行 boot 目录脚本。

### 2.2 已知缺陷 / 性能问题 / 兼容性坑
- **脚本"根本不触发"是头号吐槽**：Android 10+ 后台启动限制 + MIUI/HarmonyOS/ColorOS 的"自启动管理"默认拦截；**Termux 和 Termux:Boot 需分别开启自启动豁免**，漏掉后者是最常见原因。[Big Iron 保活指南](http://www.bigiron.cc/guides/termux-services-that-survive-the-phantom-process-killer)、[CSDN 排查](https://ask.csdn.net/questions/9448369)。
- **脚本头约束苛刻**：shebang 必须用 `/data/data/com.termux/files/usr/bin/bash`；CRLF 换行、缺 `+x` 都会静默失败。[CSDN 另一篇](https://ask.csdn.net/questions/9788672)。
- **极早期上下文环境未就绪**：开机瞬间存储未挂载、网络未通、依赖服务没起，脚本里 `curl`/`termux-api` 会失败，需手动加重试/超时。
- **不响应"被系统杀掉后自动拉起"**：Termux:Boot 只认显式启动事件，**不监听进程死亡**，进程被杀不会自动复活。
- **`termux-wake-lock` 重启失效**：需每次开机重新获取，必须写进 boot 脚本而非一次性手动执行。

### 2.3 架构层面可改进点
- 当前是"一次性广播触发"，缺乏**持久保活 + 监督（supervision）**机制（如 runsv/原生 supervisor）。
- 没有"开机后延迟、带依赖顺序、带失败重试"的执行框架。
- 缺乏对厂商自启动豁免的**检测与引导**。

### 2.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能/保活**：用 `ForegroundService` + `WorkManager` 的 `onBoot` 约束，结合 `setExactAndAllowWhileIdle` 做延迟启动；内置**监督进程**（崩溃自动重启、指数退避）。
- **权限模型**：首次配置开机任务时，用 Compose 引导卡 **一键跳转到厂商自启动设置**（按 ROM 识别 MIUI/EMUI/ColorOS 深层路径），并检测 `DuraSpeed`/电池优化并提示关闭。
- **IPC/执行**：boot 脚本以"任务"形式建模（依赖、重试、超时、日志落盘），结果在玻璃材质日志面板可视化，而非黑盒。
- **UI 体验**：提供可视化的"开机任务"列表、开关、日志回看；用液态玻璃卡片展示每个任务的运行状态。

---

## 3. Termux:Float

### 3.1 功能与实现原理
提供可在其它应用之上浮动的终端窗口（SYSTEM_ALERT_WINDOW 悬浮窗），通过 pinch 缩放、拖动实现"边用 App 边敲命令"。**自 2019-09 起停止更新**，Play 版已于 2024-05 下架，最后版本 0.13（2019）。

### 3.2 已知缺陷 / 性能问题 / 兼容性坑
- **长期停更、功能残缺**：用户吐槽"相比主应用反而丢失了特性（复制粘贴、配色、多标签）"，还倒贴钱。[AppBrain 页面与评论](http://developers.appbrain.com/app/termux-float/com.termux.window)。
- **复制/粘贴不可用**：窗口内无法直接复制粘贴，除非借助 termux:api，体验割裂。[GitHub issue #40 讨论](https://github.com/termux/termux-float/issues)。
- **平板 + 鼠标完全不能用**：移动/缩放对平板鼠标无响应。[同上评论集](https://www.androidblip.com/android-apps/com.termux.window.html)。
- **主题不继承**：Termux:Styling 的配色不带到 Float 窗口。[评论集](https://www.androidblip.com/android-apps/com.termux.window.html)。
- **无多标签、无记忆窗口尺寸/位置/字号**：issue [#59 Multiple Tabs](https://github.com/termux/termux-float/issues/59)、[#13 记住横竖屏尺寸](https://github.com/termux/termux-float/issues/13)。
- **崩溃**：[Termux:Float crash #43](https://github.com/termux/termux-float/issues/43)。
- **缩放/旋转后内容被裁切或跑到屏幕外**，键盘弹出时未重算可视区域。[issue #37 详细分析](https://github.com/termux/termux-float/issues/37)。

### 3.3 架构层面可改进点
- 悬浮窗是"独立的精简终端"，与主应用终端引擎/配置**未共享**，导致特性退化。
- 未适配指针设备（鼠标/触控板/手写笔）与 Android 大屏（DeX/平板）。
- 无窗口状态持久化、无多实例/多标签。

### 3.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能**：复用同一套终端渲染内核（Compose + 自绘/WebView-less 终端视图），悬浮窗与主应用共享会话，避免"退化副本"。
- **权限模型**：用 `Settings.canDrawOverlays()` 检测并引导授权；把"显示在其它应用上"做成首次使用时的玻璃引导。
- **IPC 方式**：悬浮窗与主进程同进程/同 Binder，会话切换零拷贝，无需跨应用 socket。
- **UI 体验**：液态玻璃悬浮窗（半透明毛玻璃 + 圆角 + 拖拽手柄）；支持多标签、记忆尺寸/位置/字号、鼠标/触控板完整输入、可缩放为"气泡"最小化。

---

## 4. Termux:Styling

### 4.1 功能与实现原理
长按终端 → Style，提供官方配色方案（colors.properties）与 powerline 字体（font.ttf）。文件落在 `~/.termux/`，由终端按属性加载渲染。

### 4.2 已知缺陷 / 性能问题 / 兼容性坑
- **配置格式极简且脆弱**：只能用严格命名为 `font.ttf` 与 `colors.properties` 的文件，后缀/权限/语法错一点就"不生效"，报错信息弱。[Termux Genius 指南](https://www.termuxgenius.com/2026/08/how-to-change-termux-font-and-color.html)。
- **Android 13 等宽字体碎片化**：部分厂商等宽字体实现缺陷，导致字母 `i`/`l` 显示过粗、不对齐，必须手动塞入 `DroidSansMono.ttf`。[GitCode 解析](https://blog.gitcode.com/50594cbe8e0a6e0fb0ab997073c23315.html)、[GitHub 讨论 #3529](https://github.com/termux/termux-app/discussions/3529)。
- **Play 版报错循环**："The latest Termux:Style app version is not installed" 即使全新安装也弹，且无法退款。[Play 商店评论](https://play.google.com/store/apps/details?id=com.termux.styling&hl=en_IE)。
- **不支持字号调节**：用户买 Styling 主要想要字号控制，结果只给字体/配色。[同上 Play 评论]。
- **连字/OTF 支持有限**：依赖 Android 渲染引擎，旧机型图标错位；`.otf` 不被接受（须 .ttf）。[CSDN 工具文](https://blog.csdn.net/weixin_35835018/article/details/154409415)。

### 4.3 架构层面可改进点
- 主题/字体是"文件约定"而非"结构化配置"，难做实时预览、难做市场/共享。
- 没有与终端渲染层的解耦接口，无法动态换肤、无法按场景（日间/夜间/AMOLED）自动切换。

### 4.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能**：字体/配色以结构化 Compose `Theme` 表达，渲染层热重载，避免重启终端。
- **权限模型**：无需系统权限；仅本地文件读，去掉"来源签名绑定"的发布约束。
- **UI 体验**：用液态玻璃主题商店——实时预览、滑动切换、支持字号/行距/字重、连字开关、AMOLED 黑、按时间/电量自动换肤；图标/配色用毛玻璃材质面板。

---

## 5. Termux:Tasker

### 5.1 功能与实现原理
作为 Tasker 的**插件宿主（plugin host）**，让 Tasker 通过 `com.termux.permission.RUN_COMMAND` 在 Termux 环境执行 `~/.termux/tasker/` 下的脚本，并把 stdout/stderr/exit code 回传给 Tasker 变量。

### 5.2 已知缺陷 / 性能问题 / 兼容性坑
- **权限链繁琐**：Tasker 必须先被授予 `com.termux.permission.RUN_COMMAND`（甚至 `WRITE_SECURE_SETTINGS`，需 adb），否则报 `receiver ... requires permission ... which we don't have`。[CSDN 解决文](https://blog.csdn.net/duan_wl/article/details/135844069)、[vivo 之家错误码](https://www.vivozhijia.com/wz/908097.html)。
- **变量命名大小写坑**：Tasker 变量必须全小写无特殊字符，`%Result`/`%MyVar_1` 非法，否则返回空。[CSDN 变量指南](https://blog.csdn.net/gitblog_00163/article/details/151208610)。
- **静默超时失败**：长脚本默认超时，需 `nohup ... &` 后台化，否则插件步骤报 Timeout。[TermuxTools 自动化指南](https://termuxtools.com/termux-tasker-automation-guide-2/)。
- **Android 10+ 后台启动 Activity 限制**：插件要在终端会话里跑，需要 Termux 取得"显示在其它应用上"权限，否则卡在通知栏等待手动点击。[termux-tasker README](https://github.com/weizx208/termux-tasker)。
- **安全性双刃剑**：`allow-external-apps=true` 后，任何拿到 RUN_COMMAND 权限的 App 都能在 Termux/root 上下文后台执行任意命令，官方明确警示风险。[同上 README](https://github.com/weizx208/termux-tasker)。
- **Play 版无 Tasker 插件**：Google Play 版 Termux 不提供 Termux:Tasker。[domski 博客](https://blog.domski.pl/?p=4477/)。

### 5.3 架构层面可改进点
- 权限模型是"全有或全无"的 RUN_COMMAND，缺少**细粒度、按脚本/按调用方授权**。
- 数据回传仅靠 stdout 字符串 + 超时，缺乏结构化结果（JSON、进度、取消）。
- 错误在 Tasker Run Log 里才看得到，对用户不透明。

### 5.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **权限模型**：用 Compose 授权中心做**按调用方 App + 按脚本**的细粒度授权；对高敏脚本强制"用户确认"弹窗（玻璃材质）。
- **IPC 方式**：以 `ContentProvider`/`Bound Service` + 结构化结果（带进度、可取消）替代纯 Intent；长任务走 `WorkManager`。
- **UI 体验**：在 moxsh 内提供"Tasker/自动化联动"可视化配置面板（选择脚本、定义输出变量、设置超时/重试），替代手改 `~/.termux/tasker/` 与 adb 授权。

---

## 6. Termux:Widget

### 6.1 功能与实现原理
桌面小部件，扫描 `~/.shortcuts/`（及 `tasks/` 子目录）下的可执行脚本，点击即运行；`tasks/` 内脚本静默后台执行。通过 `RUN_COMMAND` Intent 让 Termux 真正执行。

### 6.2 已知缺陷 / 性能问题 / 兼容性坑
- **目录权限必须严格 700**：`~/.shortcuts/` 若 world-readable/writable，Widget 直接拒绝列出脚本（安全设计，但用户极易踩坑）。[TermuxTools Widget 指南](http://termuxtools.com/termux-widgets-home-screen/)、[Termux Genius 指南](https://www.termuxgenius.com/2026/08/how-to-install-and-use-termuxwidget.html)。
- **不实时监听目录**：新增/改名脚本后必须手动刷新或重加部件。[同上]。
- **Android 10+ 需"显示在其它应用上"**：否则从后台起不了终端会话，部分 ROM 直接拒授该权限。[rigacci Termux Problems](https://rigacci.org/wiki/doku.php/doc/appunti/android/termux_problems?do=export_xhtml)。
- **动态快捷方式数量受限**：Android 7 起默认 5/10/15 个，需 adb 改 `max_shortcuts`。[hqwc 文](http://www.hqwc.cn/a/116151.html)。
- **只 1x1 部件可用**：装在外部 SD 卡时 2x2 不出现（issue #45）。[rigacci]。
- **突然停止工作**：电池优化（DuraSpeed）杀进程后，reload 无提示、点击无反应。[rigacci 多个 issue]。
- **不能传参**：点击即无参运行，变通靠多写几个脚本或在脚本内 `select` 菜单。[TermuxTools]。

### 6.3 架构层面可改进点
- 小部件与 Termux 间仍是"发 Intent → 主应用执行"的间接链路，状态不可见、失败不可感。
- 目录约定（隐藏目录 + 权限位）对普通用户不友好，无 UI 管理。
- 缺少参数化、分组、图标自定义（依赖启动器）。

### 6.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能/保活**：moxsh 自带 AppWidgetProvider，脚本在 moxsh 自身进程内执行（同进程 Binder，无需跨应用唤醒），降低被后台杀概率；失败在部件上红点提示。
- **权限模型**：内置"快捷指令管理器"UI（Compose），自动把脚本目录权限设为安全值，告别 `chmod 700` 手动排错。
- **UI 体验**：液态玻璃风格桌面部件，支持**点击传参**（弹玻璃输入卡）、分组、自定义图标、实时刷新、执行结果徽标；`tasks/` 后台任务有进度反馈。

---

## 7. Termux:X11

### 7.1 功能与实现原理
自带定制 X server（**Xlorie / Lorie DDX**），通过 JNI 在 Native 层跑 X 服务，Android 侧用 `LorieView`（SurfaceView 子类）+ GLES2 渲染线程把帧绘到 `ANativeWindow`；跨进程用 **共享内存（ashmem/memfd/AHardwareBuffer）+ Unix Socket SCM_RIGHTS 传 FD + Pthread 条件变量** 同步。[X11 Server Architecture](https://deepwiki.com/termux/termux-x11/2.1-x11-server-architecture)、[Shared Memory & IPC](https://deepwiki.com/termux/termux-x11/2.4-shared-memory-and-ipc)。

### 7.2 已知缺陷 / 性能问题 / 兼容性坑
- **稳定性靠"豁免"堆出来**：必须关 Phantom Process Killer（`settings put global settings_enable_monitor_phantom_procs false`，但系统升级会重置）+ 双 App 省电豁免，否则 X11 随机被杀死/无响应。[DeepWiki 排错](https://deepwiki.com/LinuxDroidMaster/Termux-Desktops/9-troubleshooting-and-faqs)、[termux-x11 排错](https://deepwiki.com/termux/termux-x11/6.3-troubleshooting)。
- **黑屏/色差**：部分设备需 `-legacy-drawing`（黑屏只有光标）、`-force-bgra`（红蓝反转）。[termux-x11 排错](https://deepwiki.com/termux/termux-x11/6.3-troubleshooting)。
- **随机冻结**：`termux x11 freezes at any time` #991、`Logitech MX Stylus on Quest crash` #963 等大量未关 issue。[Issue 列表](http://github.es/termux/termux-x11/issues)。
- **高 DPI 文字极小**：默认缩放下几乎不可读，开缩放又发虚；Xfce 自带缩放反而把可用区缩到 1/4。[LWN 评测](https://lwn.net/Articles/936953/)。
- **GPU 加速碎片化**：VIRGL/ZINK/Turnip(Freedreno) 按 GPU 而异，Adreno 6XX/7XX 才最佳，其它机型性能差。[DeepWiki 硬件加速](https://deepwiki.com/LinuxDroidMaster/Termux-Desktops/9-troubleshooting-and-faqs)。
- **输入问题**：游戏 WASD 不识别需"Prefer scancodes"；Alt 键默认卡住（#990）；meta 键解除绑定（#998）；Dex 模式下鼠标唤醒设备（#917）。
- **非合成器**：只是 X server 后端，不是 Wayland 合成器，多窗口/遮挡/动画弱；与 Xwayland 的 ashmem 改动易冲突（issue #45）。[issue #45](https://github.com/termux/termux-x11/issues/45)。

### 7.3 架构层面可改进点
- 渲染与 X 服务主循环**分离但靠进程间共享状态同步**，任一进程死亡即撕裂/冻结；缺少合成器层做窗口管理与帧节奏（frame pacing）。
- 输入子系统对指针设备/外接键鼠/手势覆盖不全。
- 严重依赖用户手动关系统限制，工程上不可靠。

### 7.4 用 Kotlin/Compose + 液态玻璃 UI 重写建议
- **性能/稳定**：引入**合成器层**（或基于 SurfaceFlinger/AGSL 的轻量 compositor）做帧节奏与窗口管理；用 `AHardwareBuffer` 零拷贝 + `Choreographer` 帧同步，减少发虚与撕裂；常驻前台服务降低被杀。
- **权限模型**：首启动用 Compose 引导卡 **一键检测并尝试关闭 Phantom Process Killer / 省电**（含 adb 指引），把"脆弱豁免"做成可观测状态。
- **IPC 方式**：保留共享内存零拷贝优势，但用 Binder + 结构化协议替代裸 socket/FD 传递，便于错误诊断与版本协商。
- **UI 体验**：液态玻璃风格的"桌面控制台"——分辨率/缩放/DPI 实时滑杆、GPU 模式切换、外设映射配置（鼠标速度、键位重映射、手势）、崩溃自动恢复与状态徽标；对高 DPI 做自适应缩放而非简单模糊。

---

## 8. 跨插件统一改进蓝图（moxsh 总纲）

| 维度 | Termux 现状 | moxsh 重写方向 |
|---|---|---|
| **发布/来源** | 主应用与插件签名绑定，混装静默断联 | 单一 App 内置全部能力模块，去除"跨应用签名依赖" |
| **后台保活** | 各插件各自应对 Doze/厂商杀进程 | 统一前台服务 +  supervisor + 引导式豁免检测 |
| **权限模型** | 分散、需 adb、错误不透明 | Compose 引导式一次性授权，按能力/按调用方细粒度 |
| **IPC** | socket + 广播 + 前台 Service，慢且难诊断 | Binder/AIDL/共享内存 + 结构化错误码，带版本协商 |
| **配置方式** | 隐藏目录 + 权限位 + 文件约定，易踩坑 | 结构化设置 + 可视化 Compose 管理面板 |
| **UI** | 原生/Holo 风格，特性碎片化 | 统一液态玻璃（glassmorphism）设计系统，实时预览 |
| **可观测性** | 失败多静默，需 logcat 排错 | 状态徽标、日志面板、错误可点击引导修复 |

**结论**：Termux 插件生态的最大问题不是单一 Bug，而是**"Android 限制 + 多 APK 签名耦合 + 文件约定式配置 + 无统一 UI/权限框架"** 的系统性债务。moxsh 若用 Kotlin/Compose 把能力"内置化 + 服务常驻化 + 权限引导化 + 玻璃 UI 统一化"，可在性能、稳定性与体验上系统性超越 Termux，而无需复刻其历史包袱。

---

## 参考来源汇总
- Termux:API：[DeepWiki Command Flow](https://deepwiki.com/termux/termux-api/2.1-command-flow) · [issue #552 延迟](https://github.com/termux/termux-api/issues/552) · [issue #109 SSH挂死](https://github.com/termux/termux-api-package/issues/109) · [Issue 列表](http://github.es/termux/termux-api/issues) · [Wiki](https://wiki.termux.com/index.php?direction=prev&oldid=4043&title=Termux%3AAPI) · [OpenAPK](https://www.openapk.net/zh/termuxapi/com.termux.api/)
- Termux:Boot：[Big Iron 保活](http://www.bigiron.cc/guides/termux-services-that-survive-the-phantom-process-killer) · [CSDN 排查1](https://ask.csdn.net/questions/9448369) · [CSDN 排查2](https://ask.csdn.net/questions/9788672)
- Termux:Float：[AppBrain](http://developers.apprain.com/app/termux-float/com.termux.window) · [androidblip 评论](https://www.androidblip.com/android-apps/com.termux.window.html) · [Issues](https://github.com/termux/termux-float/issues) · [#37](https://github.com/termux/termux-float/issues/37) · [#43](https://github.com/termux/termux-float/issues/43)
- Termux:Styling：[Termux Genius](https://www.termuxgenius.com/2026/08/how-to-change-termux-font-and-color.html) · [GitCode 字体](https://blog.gitcode.com/50594cbe8e0a6e0fb0ab997073c23315.html) · [Play 评论](https://play.google.com/store/apps/details?id=com.termux.styling&hl=en_IE) · [GitHub #3529](https://github.com/termux/termux-app/discussions/3529)
- Termux:Tasker：[CSDN 解决](https://blog.csdn.net/duan_wl/article/details/135844069) · [变量指南](https://blog.csdn.net/gitblog_00163/article/details/151208610) · [TermuxTools 指南](https://termuxtools.com/termux-tasker-automation-guide-2/) · [README](https://github.com/weizx208/termux-tasker)
- Termux:Widget：[TermuxTools](http://termuxtools.com/termux-widgets-home-screen/) · [Termux Genius](https://www.termuxgenius.com/2026/08/how-to-install-and-use-termuxwidget.html) · [rigacci](https://rigacci.org/wiki/doku.php/doc/appunti/android/termux_problems?do=export_xhtml) · [hqwc](http://www.hqwc.cn/a/116151.html)
- Termux:X11：[Architecture](https://deepwiki.com/termux/termux-x11/2.1-x11-server-architecture) · [IPC](https://deepwiki.com/termux/termux-x11/2.4-shared-memory-and-ipc) · [Troubleshooting](https://deepwiki.com/termux/termux-x11/6.3-troubleshooting) · [DeepWiki FAQs](https://deepwiki.com/LinuxDroidMaster/Termux-Desktops/9-troubleshooting-and-faqs) · [LWN](https://lwn.net/Articles/936953/) · [Issues](http://github.es/termux/termux-x11/issues) · [#45](https://github.com/termux/termux-x11/issues/45)
- RUN_COMMAND Intent：[Wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent) · [DeepWiki](https://deepwiki.com/nix-community/nix-on-droid-app/4.2-run_command-intent-api)
- 综合：[domski 博客（Play vs F-Droid）](https://blog.domski.pl/?p=4477/) · [earendil 文档](https://github.com/q-qp-p/earendil-works-pi/blob/0ab2aa86af862ca1cf4b0b86fcbee14d00e1441f/packages/coding-agent/docs/termux.md)

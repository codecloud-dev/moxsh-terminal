# moxsh 插件重写方案

> 目标产品：**moxsh** —— 全面兼容 Termux、性能更强、采用液态玻璃（glassmorphism）UI 的新终端应用
> 技术栈：**Kotlin / Jetpack Compose**（UI 与业务），性能热点用 **C/C++（NDK）**
> 本期范围：6 个插件 **API / Boot / Float / Styling / Tasker / Widget**
> 输入依据：[`research/termux-plugins-shortcomings.md`](../../research/termux-plugins-shortcomings.md)（12 条发现）、[`docs/commands.md`](./commands.md)（Termux 命令体系盘点）
> 日期：2026-09-27

---

## 〇、架构决策与双插件体系

### 0.1 拍定的双插件体系

moxsh 不沿用 Termux「主应用 + 多个独立签名 APK 插件」的模型，而采用**双体系并存**：

| 体系 | 定位 | 签名 | 与 Termux 关系 | 界面 |
|---|---|---|---|---|
| **① 兼容宿主（TermuxHost）** | 实现 Termux 插件契约的适配层，让原 `termux-*` 插件与第三方调用「直接装能用」 | 独立（moxsh 自有签名） | 共享**契约**，不共享**签名** | 传统/无 |
| **② moxsh 自研框架** | 本期 6 个插件的统一运行时，单 App 内置、能力模块化 | 独立（moxsh 自有签名） | **与 Termux 不互通** | 液态玻璃 |

> **为什么双体系**：Termux 生态最大债务是「多 APK 签名耦合 + 文件约定式配置 + 无统一 UI/权限框架」（见 research §8 跨插件统一蓝图）。体系②把 6 个能力**内置进单一 App**，从根本上消除跨应用签名依赖（research §8 总纲「单一 App 内置全部能力模块」）；体系①只作为兼容桥，让尚未迁移的用户/脚本仍可走原有 `RUN_COMMAND` / `LocalServerSocket` 通道。

### 0.2 体系① 兼容宿主如何落位

- 实现 `RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND` 的 `Intent` 处理器，等价 `termux-app` 的 `RunCommandService`（见 `commands.md` §4.1）。
- 实现 `LocalServerSocket` / `AmSocketServer` 兼容总线（见 `commands.md` §4.2–4.3），保持 `exit_code\0stdout\0stderr\0` 约定，便于原 `termux-api` 二进制零改动接入。
- 声明 `com.termux.permission.RUN_COMMAND` 兼容权限，并对原插件做 **Intent 重定向/包别名（package alias）**：原插件向 `com.termux` 发的调用经系统/宿主 shim 映射到 moxsh。
- **签名 caveat（显式声明）**：原 Termux 插件以 `com.termux` 签名校验 `RUN_COMMAND` 权限，moxsh 用自有签名，故**与原 Termux 插件不能无缝共享签名校验**；体系①通过「契约兼容 + 包别名 + 放开同 UID 校验」达到「装得上、能调用」，但仅限 moxsh 签名侧生态，不反向互通。

### 0.3 体系② 自研框架统一基座（6 插件共用）

| 维度 | Termux 现状（research §0/§8） | moxsh 自研框架 |
|---|---|---|
| 后台保活 | 各插件各自应对 Doze / 厂商杀进程；Phantom Process Killer 致 SIGKILL | 统一前台服务 + supervisor + 引导式豁免检测 |
| 权限模型 | 分散、需 adb、错误不透明 | Compose 引导式一次性授权，**按能力 / 按调用方**细粒度 |
| IPC | socket + 广播 + 前台 Service，慢且难诊断 | **Binder / AIDL / 共享内存 + 结构化错误码 + 版本协商** |
| 配置方式 | 隐藏目录 + 权限位 + 文件约定 | 结构化设置 + 可视化 Compose 管理面板 |
| UI | 原生 / Holo，特性碎片化 | 统一**液态玻璃**设计系统，实时预览 |
| 可观测性 | 失败多静默，需 logcat | 状态徽标、日志面板、错误可点击引导修复 |

### 0.4 X11 递延声明

**Termux:X11 本期不做。** research §7 指出其稳定性依赖「关闭 Phantom Process Killer + 双 App 省电豁免」等脆弱豁免，且需合成器层 / GPU 加速碎片化适配，工程量大、风险高。本期仅保留架构预留位（宿主保留 `SurfaceFlinger`/`AHardwareBuffer` 零拷贝通道的接口抽象），**正式实现递延至后续版本**。

---

## 一、Termux:API → moxsh API 插件

### 1.1 现状与核心痛点

- **冷启动延迟严重**：`termux-battery-status` 在 Android 12 上从 ~0.6s 退化到 **~3.6s**，疑似每次调用都 fork `dalvikvm` 生成 OAT —— [issue #552 延迟](https://github.com/termux/termux-api/issues/552)。
- **SSH / 后台会话下挂死（hang）**：从 ssh 会话调用 API 命令永久阻塞、无输出，需亮屏恢复 —— [issue #109 SSH挂死](https://github.com/termux/termux-api-package/issues/109)。
- **剪贴板仅支持文本**，图片剪贴板无效 —— [earendil 文档](https://github.com/q-qp-p/earendil-works-pi/blob/0ab2aa86af862ca1cf4b0b86fcbee14d00e1441f/packages/coding-agent/docs/termux.md)。
- **来源分裂（Play vs F-Droid）**：`termux-camera-photo`、`termux-location`、WiFi/SMS/NFC 等命令**仅 F-Droid 版提供**，Play 版缺能力 —— [domski 博客（Play vs F-Droid）](https://blog.domski.pl/?p=4477/)。
- **随机崩溃**：边界值为 null 时 `NullPointerException` —— [OpenAPK 更新说明](https://www.openapk.net/zh/termuxapi/com.termux.api/)。
- 命令流水分发链路：`termux-api` 二进制 → 匿名 Unix Domain Socket → `SocketListener` → `TermuxApiReceiver` 按 `api_method` 分派 —— [DeepWiki Command Flow](https://deepwiki.com/termux/termux-api/2.1-command-flow)。

### 1.2 moxsh 重写设计

- **模块边界**：`ApiCore`（能力分派）↔ `ApiGateway`（权限/版本网关）↔ `ApiBinder`（AIDL 服务）↔ `TermuxCompatHost`（兼容 `termux-*` 命令名）。37 个能力按 research §1.3 建议**分层**：无需 Android 权限层（toast、vibrate、battery-status）与高敏层（SMS、联系人、通话）显式隔离，降低攻击面。
- **加固 IPC**：以 **AIDL `IMoxshApi` + Binder** 替代 socket + 广播，绑定前台 `KeepAliveService`（按需常驻、空闲回收），带**版本协商**与**显式错误码**（不再 `hang`）。后台调用改 `WorkManager`/`ForegroundService`。
- **权限模型**：`ApiGateway` 对每次调用按 `callingUid` + `method` 鉴权；高敏方法触发 Compose 一次性授权弹窗。
- **双体系落位**：体系② 用 `IMoxshApi`；体系① `TermuxCompatHost` 暴露同名 `termux-*` 命令与 `LocalServerSocket`，让原 `termux-api` 二进制零改动接入。

### 1.3 液态玻璃 UI 落地

- 把 `termux-dialog` / `termux-notification` / `termux-toast` 做成**本地 Compose 组件库**，通知与对话框用毛玻璃材质（`RenderEffect` 模糊 + 半透明 + 动态取色）。
- 用 `Material3 Expressive` 的形状/动效语言；传感器/定位用 `Flow` 流式回传，`collectAsState` 实时刷新。
- 支持图片剪贴板、24h 时钟、无障碍标识等长期 issue 诉求。

### 1.4 性能 / 体验优化点

| 项 | Termux | moxsh |
|---|---|---|
| 单次调用延迟 | 0.6s→3.6s（冷启动 JVM） | 常驻 Binder，< 50ms |
| 后台/SSH 调用 | 永久 hang | 前台服务 + 结构化错误码，永不静默挂死 |
| 能力完整性 | Play/F-Droid 分裂 | 单一构建全能力内置 |
| 剪贴板 | 文本 | 文本 + 图片 |

### 1.5 关键代码片段

```kotlin
// IMoxshApi.aidl —— 带版本协商与结构化错误码的 Binder 契约
interface IMoxshApi {
    Bundle call(int apiVersion, String method, in Bundle args);
}

// ApiService.kt —— 常驻前台服务 + 集中权限网关
class ApiService : Service() {
    private val gateway = ApiGateway(applicationContext)
    private val binder = object : IMoxshApi.Stub() {
        override fun call(apiVersion: Int, method: String, args: Bundle): Bundle {
            if (apiVersion < MIN_API_VERSION) {
                return Bundle().apply { putInt("error", ERROR_VERSION) }
            }
            return gateway.dispatch(method, args, callingUid) // callingUid 防伪造
        }
    }
    override fun onBind(intent: Intent) = binder
}

// ApiGateway.kt —— 按能力分层鉴权 + 高敏方法触发授权
class ApiGateway(private val ctx: Context) {
    fun dispatch(method: String, args: Bundle, callerUid: Int): Bundle = when (method) {
        "termux-sms-send" ->
            requireConsent(callerUid, "发送短信") { SmsApi.execute(args) } // 高敏层
        "termux-toast" -> ToastApi.execute(args)                          // 无权限层
        else -> Bundle().apply { putInt("error", ERROR_UNKNOWN_METHOD) }
    }
    private fun requireConsent(callerUid: Int, reason: String, block: () -> Bundle): Bundle {
        return if (ConsentStore.isGranted(callerUid, reason))
            block() else Bundle().apply { putInt("error", ERROR_NEED_CONSENT) }
    }
}
```

### 1.6 兼容性说明

- **命令名 1:1 保留**：全部 37 个 `termux-*` 命令（见 `commands.md` §5.1）在 moxsh 上**无需改为 Linux 实现**——moxsh 是 Android 应用，原生 Android API 仍可用，直接「保留/增强」。
- 体系① 兼容宿主对外暴露同名 `termux-api` 二进制接口与 `LocalServerSocket`，第三方脚本、`am` 调用、`termux-*` 子命令**零改动**。
- 体系② 新增图片剪贴板等增强能力，原文本语义向下兼容。

---

## 二、Termux:Boot → moxsh Boot 插件

### 2.1 现状与核心痛点

- **脚本「根本不触发」是头号吐槽**：Android 10+ 后台限制 + MIUI/HarmonyOS/ColorOS 自启动管理默认拦截；Termux 与 Termux:Boot 需**分别**开启自启动豁免，漏掉后者最常见 —— [Big Iron 保活指南](http://www.bigiron.cc/guides/termux-services-that-survive-the-phantom-process-killer)、[CSDN 排查](https://ask.csdn.net/questions/9448369)。
- **脚本头约束苛刻**：shebang 必须 `/data/data/com.termux/files/usr/bin/bash`；CRLF、`-x` 缺失静默失败 —— [CSDN 另一篇](https://ask.csdn.net/questions/9788672)。
- **极早期环境未就绪**：开机瞬间存储/网络/依赖服务未起，`curl`/`termux-api` 失败需手动重试。
- **不监听进程死亡**：只认显式启动事件，被杀不自动复活；`termux-wake-lock` 重启失效需写进 boot 脚本。

### 2.2 moxsh 重写设计

- **模块边界**：`BootReceiver`（BOOT_COMPLETED）→ `BootScheduler`（延迟/依赖/重试）→ `TaskSupervisor`（监督进程）→ `BootLogStore`（日志落盘）。
- **加固 IPC**：boot 脚本以「任务」对象建模（依赖图、超时、指数退避重试），经 `ForegroundService` + `WorkManager` `onBoot` 约束执行，结果回写本地结构化日志，非黑盒。
- **权限模型**：首次配置开机任务，Compose 引导卡**一键跳转厂商自启动设置**（按 ROM 识别 MIUI/EMUI/ColorOS 深层路径），检测 `DuraSpeed`/电池优化并提示关闭。
- **双体系落位**：体系② 内置 supervisor；体系① 兼容宿主仍识别原 `~/.termux/boot/*.sh` 目录约定。

### 2.3 液态玻璃 UI 落地

- 可视化「开机任务」列表：玻璃卡片展示每个任务的运行/失败/重试状态、开关、依赖顺序。
- 玻璃材质日志面板回看历史执行；失败项提供「点击引导修复」按钮（跳设置/重授权）。

### 2.4 性能 / 体验优化点

| 项 | Termux | moxsh |
|---|---|---|
| 触发可靠性 | 依赖手动豁免，常不触发 | 引导式豁免检测 + 监督进程自动复活 |
| 执行模型 | 一次性广播 | 依赖顺序 + 超时 + 指数退避重试 |
| 可观测性 | 黑盒，需 logcat | 玻璃日志面板 + 状态徽标 |
| 环境就绪 | 用户自加重试 | 框架级延迟/就绪探针 |

### 2.5 关键代码片段

```kotlin
// BootTask.kt —— 结构化任务模型
data class BootTask(
    val id: String, val script: File, val dependsOn: List<String> = emptyList(),
    val timeoutMs: Long = 30_000, val maxRetries: Int = 3
)

// TaskSupervisor.kt —— 监督进程：崩溃自启 + 指数退避
class TaskSupervisor(private val ctx: Context) {
    fun run(task: BootTask) = supervisorScope {
        var attempt = 0
        while (attempt <= task.maxRetries) {
            try {
                execScript(task)   // 经 ForegroundService 持久上下文
                BootLogStore.markOk(task)
                return@supervisorScope
            } catch (e: Exception) {
                attempt++
                val backoff = (1 shl attempt) * 500L  // 指数退避
                delay(backoff)
                BootLogStore.markRetry(task, attempt, e)
            }
        }
        notifyBootFailureGlass(task) // 玻璃通知
    }
}

// VendorAutostart.kt —— 引导式厂商豁免检测
fun detectAutostartState(ctx: Context): AutostartState {
    val rom = Build.MANUFACTURER.lowercase()
    val batteryIgnored = PowerManagerCompat.isIgnoringBatteryOptimizations(ctx, ctx.packageName)
    return AutostartState(rom = rom, batteryIgnored = batteryIgnored,
        deepLink = deepLinkForRom(rom)) // MIUI/EMUI/ColorOS 深层设置路径
}
```

### 2.6 兼容性说明

- 完全保留 `~/.termux/boot/*.sh` 目录约定与脚本语义；自动对脚本做 shebang 修正（`termux-fix-shebang` 逻辑内置）与 `chmod +x`、CRLF 归一。
- `termux-wake-lock` 在 boot 阶段自动重建，无需用户手工写进脚本。
- 体系① 兼容宿主可直接接收原 `BOOT_COMPLETED` 插件行为。

---

## 三、Termux:Float → moxsh Float 插件

### 3.1 现状与核心痛点

- **长期停更、功能残缺**：自 2019-09 停止更新，Play 版 2024-05 下架；用户吐槽相比主应用丢失复制粘贴、配色、多标签 —— [AppBrain 页面与评论](http://developers.appbrain.com/app/termux-float/com.termux.window)。
- **复制/粘贴不可用**：窗口内无法直接复制粘贴，除非借助 termux:api —— [GitHub issue #40](https://github.com/termux/termux-float/issues)。
- **平板 + 鼠标完全不能用**：移动/缩放对平板鼠标无响应 —— [androidblip 评论集](https://www.androidblip.com/android-apps/com.termux.window.html)。
- **主题不继承**、**无多标签**、**无记忆窗口尺寸/位置/字号** —— [#59 Multiple Tabs](https://github.com/termux/termux-float/issues/59)、[#13 记住尺寸](https://github.com/termux/termux-float/issues/13)。
- **崩溃**与**缩放/旋转后内容被裁切或跑到屏外**，键盘弹出未重算可视区 —— [#43](https://github.com/termux/termux-float/issues/43)、[#37 详细分析](https://github.com/termux/termux-float/issues/37)。

### 3.2 moxsh 重写设计

- **模块边界**：`FloatWindowManager`（窗口生命周期）↔ `SharedTerminalEngine`（与主应用**共享同一终端渲染内核**，避免「退化副本」）↔ `PointerInputFilter`（指针设备适配）↔ `WindowStateStore`（尺寸/位置/字号持久化）。
- **加固 IPC**：悬浮窗与主进程**同进程 / 同 Binder**，会话切换零拷贝，无需跨应用 socket。
- **权限模型**：`Settings.canDrawOverlays()` 检测并引导授权，「显示在其它应用上」做成首次使用玻璃引导。
- **双体系落位**：体系② 原生浮动窗；体系① 兼容宿主不提供独立浮窗 APK（原 Float 已停更）。

### 3.3 液态玻璃 UI 落地

- 液态玻璃悬浮窗：半透明毛玻璃 + 圆角 + 拖拽手柄（`RenderEffect` 模糊背后内容）。
- 支持多标签、记忆尺寸/位置/字号、鼠标/触控板/手写笔完整输入、可缩放为「气泡」最小化。
- 继承 Styling 玻璃主题，键盘弹出自动 `WindowInsets` 重算可视区域。

### 3.4 性能 / 体验优化点

| 项 | Termux:Float | moxsh Float |
|---|---|---|
| 终端能力 | 退化副本（无复制/配色/多标签） | 与主应用共享内核，能力等价 |
| 指针设备 | 平板鼠标不可用 | 完整鼠标/触控板/手写笔 |
| 窗口状态 | 不记忆 | 持久化尺寸/位置/字号/多实例 |
| 缩放旋转 | 内容裁切/跑屏外 | 实时重算 + 键盘 inset 适配 |

### 3.5 关键代码片段

```kotlin
// GlassFloatingTerminal.kt —— 液态玻璃悬浮窗 + 拖拽 + 共享会话
@Composable
fun GlassFloatingTerminal(
    session: TerminalSession,
    windowState: WindowState,
    onClose: () -> Unit
) {
    var offset by remember { mutableStateOf(windowState.position) }
    val blur = remember {
        RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)
    }
    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(windowState.size)
            .pointerInput(Unit) {
                detectDragGestures { _, drag -> offset += drag; WindowStateStore.save(offset) }
            }
    ) {
        // 毛玻璃底板
        Spacer(Modifier.fillMaxSize().graphicsLayer { renderEffect = blur }
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(20.dp)))
        // 复用主应用同一 TerminalView，零拷贝共享会话
        TerminalView(session = session, modifier = Modifier.fillMaxSize().padding(12.dp))
        DragHandle(Modifier.align(Alignment.TopCenter))
    }
}

// PointerInputFilter.kt —— 鼠标/触控板完整输入
fun Modifier.floatPointerInput(onScroll: (Float) -> Unit) = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val ev = awaitPointerEvent()
            ev.changes.forEach { if (it.scrollDelta != Offset.Zero) onScroll(it.scrollDelta.y) }
        }
    }
}
```

### 3.6 兼容性说明

- 共享主应用终端内核，原 `termux-*` 命令、shell 脚本在浮窗内**完全一致**运行。
- 复制/粘贴、配色、多标签等能力对齐主应用，不出现退化。
- 窗口状态落盘 `~/.moxsh/float/`，与原 Termux:Float 配置（已停更）无冲突。

---

## 四、Termux:Styling → moxsh Styling 插件

### 4.1 现状与核心痛点

- **配置格式极简且脆弱**：只能用严格命名 `font.ttf` 与 `colors.properties`，后缀/权限/语法错一点就「不生效」、报错弱 —— [Termux Genius 指南](https://www.termuxgenius.com/2026/08/how-to-change-termux-font-and-color.html)。
- **Android 13 等宽字体碎片化**：部分厂商等宽字体缺陷，字母 `i`/`l` 过粗、不对齐，需手动塞 `DroidSansMono.ttf` —— [GitCode 解析](https://blog.gitcode.com/50594cbe8e0a6e0fb0ab997073c23315.html)、[GitHub #3529](https://github.com/termux/termux-app/discussions/3529)。
- **Play 版报错循环**：「The latest Termux:Style app version is not installed」即使全新安装也弹 —— [Play 商店评论](https://play.google.com/store/apps/details?id=com.termux.styling&hl=en_IE)。
- **不支持字号调节**、**连字/OTF 支持有限**（仅 `.ttf`） —— [同上 Play 评论]、[CSDN 工具文](https://blog.csdn.net/weixin_35835018/article/details/154409415)。

### 4.2 moxsh 重写设计

- **模块边界**：`ThemeStore`（结构化主题）↔ `FontResolver`（字体/字号/字重/连字）↔ `GlassThemeEngine`（动态玻璃材质）↔ `ThemeMarket`（预览/共享）。
- **加固 IPC**：主题以**结构化 Compose `Theme`** 表达，渲染层热重载，无需重启终端；无跨进程 socket。
- **权限模型**：无需系统权限，仅本地文件读，去掉「来源签名绑定」发布约束（直接消除 Play 报错循环类问题）。
- **双体系落位**：体系② 结构化主题商店；体系① 兼容宿主仍可读取原 `colors.properties` / `font.ttf`，并自动迁移为结构化主题。

### 4.3 液态玻璃 UI 落地

- 液态玻璃主题商店：实时预览、滑动切换、支持**字号/行距/字重/连字开关/AMOLED 黑**。
- 动态玻璃主题：基于壁纸 `WallpaperColors` 或 `dynamicLightColorScheme`/`dynamicDarkColorScheme` **动态取色**，并随场景（日间/夜间/AMOLED/电量）自动换肤。
- 用 `Material3 Expressive` 的形状与色彩角色，玻璃面板控制项。

### 4.4 性能 / 体验优化点

| 项 | Termux:Styling | moxsh Styling |
|---|---|---|
| 配置形态 | 文件约定（font.ttf/colors.properties） | 结构化 Theme，热重载 |
| 字号/行距 | 不支持 | 支持 |
| 字体碎片化 | 需手动塞 DroidSansMono | 内置等宽回退 + OTF 支持 |
| 换肤 | 手动 | 动态取色 + 场景自动切换 |

### 4.5 关键代码片段

```kotlin
// GlassTheme.kt —— 动态取色 + 玻璃材质主题
@Composable
fun MoxshGlassTheme(
    dark: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    // 动态取色：跟随壁纸（Android 12+ dynamic color）
    val dyn = if (dark) dynamicDarkColorScheme(LocalContext.current)
              else dynamicLightColorScheme(LocalContext.current)
    val scheme = dyn.copy(
        background = if (amoled) Color.Black else dyn.background,
        surface = dyn.surface.copy(alpha = 0.72f) // 玻璃半透明
    )
    MaterialTheme(colorScheme = scheme, typography = MoxshTypography, shapes = ExpressiveShapes) {
        // 全局玻璃容器：模糊 + 半透明 + 圆角
        GlassSurface(content = content)
    }
}

// ThemeStore.kt —— 热重载，无需重启终端
object ThemeStore {
    private val _theme = MutableStateFlow(loadDefault())
    val theme = _theme.asStateFlow()
    fun apply(t: ThemeConfig) { _theme.value = t; TerminalEngine.hotReload(t) }
}

// 字号/连字示例
val MoxshTypography = Typography(defaultFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Bold)
)).let { it.copy(
    bodyMedium = it.bodyMedium.copy(fontSize = ThemeStore.theme.value.fontSize.sp,
                                    fontFeatureSettings = "cv01, ss01") // 连字
) }
```

### 4.6 兼容性说明

- 完全兼容原 `colors.properties` 与 `font.ttf`：体系① 兼容宿主解析二者并导入为结构化主题（含自动塞 `DroidSansMono` 回退解决碎片化）。
- 新增字号/行距/连字/动态取色为**增强项**，不破坏原属性语义。
- `.otf` 字体纳入支持，旧机型图标错位问题缓解。

---

## 五、Termux:Tasker → moxsh Tasker 插件

### 5.1 现状与核心痛点

- **权限链繁琐**：Tasker 需先被授予 `com.termux.permission.RUN_COMMAND`（甚至 `WRITE_SECURE_SETTINGS`，需 adb），否则报 `requires permission ... which we don't have` —— [CSDN 解决文](https://blog.csdn.net/duan_wl/article/details/135844069)、[vivo 之家错误码](https://www.vivozhijia.com/zhijia/908097.html)。
- **变量命名大小写坑**：Tasker 变量必须全小写无特殊字符，`%Result`/`%MyVar_1` 非法返回空 —— [CSDN 变量指南](https://blog.csdn.net/gitblog_00163/article/details/151208610)。
- **静默超时失败**：长脚本默认超时，需 `nohup ... &` 后台化，否则步骤 Timeout —— [TermuxTools 自动化指南](https://termuxtools.com/termux-tasker-automation-guide-2/)。
- **Android 10+ 后台启动 Activity 限制**：需 Termux「显示在其它应用上」，否则卡通知栏 —— [termux-tasker README](https://github.com/weizx208/termux-tasker)。
- **安全性双刃剑**：`allow-external-apps=true` 后任何拿到 `RUN_COMMAND` 的 App 可后台执行任意命令 —— [同上 README](https://github.com/weizx208/termux-tasker)。
- Play 版无 Tasker 插件 —— [domski 博客](https://blog.domski.pl/?p=4477/)。

### 5.2 moxsh 重写设计

- **模块边界**：`TaskerPluginHost`（接收 Tasker 调用）↔ `ConsentCenter`（细粒度授权）↔ `ScriptRunner`（结构化执行）↔ `ResultChannel`（进度/取消回传）。
- **加固 IPC**：以 `ContentProvider` / `Bound Service` + **结构化结果（JSON、进度、可取消）** 替代纯 Intent；长任务走 `WorkManager`。
- **权限模型**：**按调用方 App + 按脚本**细粒度授权；高敏脚本强制玻璃材质「用户确认」弹窗；替代「全有或全无」的 `RUN_COMMAND`。
- **双体系落位**：体系② 原生 Tasker 宿主；体系① 兼容宿主仍理解 `RUN_COMMAND` Intent 语义，但统一经 `ConsentCenter` 鉴权。

### 5.3 液态玻璃 UI 落地

- 「Tasker / 自动化联动」可视化配置面板：选脚本、定义输出变量、设超时/重试。
- 变量名校验实时提示（大写/特殊字符标红），替代「返回空」的静默坑。
- 授权确认用玻璃卡，错误可点击引导修复（跳设置/重授权）。

### 5.4 性能 / 体验优化点

| 项 | Termux:Tasker | moxsh Tasker |
|---|---|---|
| 授权粒度 | 全有或全无 RUN_COMMAND | 按调用方 + 按脚本细粒度 |
| 结果回传 | stdout 字符串 + 超时 | 结构化（进度/可取消/JSON） |
| 变量坑 | 大小写静默失败 | 面板实时校验 |
| 安全性 | allow-external-apps 风险 | 默认拒绝 + 显式确认 |

### 5.5 关键代码片段

```kotlin
// ConsentCenter.kt —— 按调用方 + 按脚本的细粒度授权
object ConsentCenter {
    private val grants = mutableMapOf<Pair<Int, String>, Boolean>() // (callerUid, scriptPath)
    fun authorize(callerUid: Int, script: String, sensitive: Boolean): Boolean {
        if (grants[callerUid to script] == true) return true
        if (!sensitive) return true
        // 高敏脚本：弹玻璃确认卡，用户显式授权
        return requestGlassConsent(callerUid, script)
    }
}

// ScriptRunner.kt —— 结构化执行 + 进度/取消回传
class ScriptRunner {
    fun run(script: File, args: List<String>, scope: CoroutineScope): Flow<RunEvent> = flow {
        emit(RunEvent.Started)
        val proc = Runtime.getRuntime().exec(arrayOf(script.path, *args))
        val reader = proc.inputStream.bufferedReader()
        while (scope.isActive) {
            val line = reader.readLine() ?: break
            emit(RunEvent.Progress(line))            // 进度
        }
        emit(RunEvent.Done(proc.waitFor()))           // 退出码
    }.onCompletion { if (it != null) emit(RunEvent.Cancelled) }
}

// TaskerPluginHost.kt —— 替代纯 Intent 的 Bound Service 入口
class TaskerPluginHost : Service() {
    private val binder = object : ITaskerHost.Stub() {
        override fun execute(callerUid: Int, script: String, args: Array<out String>): Bundle {
            val ok = ConsentCenter.authorize(callerUid, script, sensitive = isSensitive(script))
            if (!ok) return Bundle().apply { putInt("error", ERROR_DENIED) }
            return runBlocking { ScriptRunner().run(File(script), args.toList(), this).last() }.toBundle()
        }
    }
    override fun onBind(i: Intent) = binder
}
```

### 5.6 兼容性说明

- 保留 `~/.termux/tasker/` 脚本目录约定与 `RUN_COMMAND` Intent 语义（体系① 兼容宿主转发）。
- 输出变量大小写规范化层：自动把 `%Result` 映射为合法小写，保证旧 Tasker 配置可用。
- 长脚本默认走 `WorkManager`，消除静默超时。

---

## 六、Termux:Widget → moxsh Widget 插件

### 6.1 现状与核心痛点

- **目录权限必须严格 700**：`~/.shortcuts/` 若 world-readable/writable，Widget 直接拒绝列出脚本 —— [TermuxTools Widget 指南](http://termuxtools.com/termux-widgets-home-screen/)、[Termux Genius Widget](https://www.termuxgenius.com/2026/08/how-to-install-and-use-termuxwidget.html)。
- **不实时监听目录**：新增/改名脚本必须手动刷新或重加部件。
- **Android 10+ 需「显示在其它应用上」**，否则后台起不了会话，部分 ROM 直接拒授 —— [rigacci Termux Problems](https://rigacci.org/wiki/doku.php/doc/appunti/android/termux_problems?do=export_xhtml)。
- **动态快捷方式数量受限**：Android 7 起默认 5/10/15 个，需 adb 改 `max_shortcuts` —— [hqwc 文](http://www.hqwc.cn/a/116151.html)。
- **突然停止工作**：电池优化杀进程后 reload 无提示、点击无反应 —— [rigacci](https://rigacci.org/wiki/doku.php/doc/appunti/android/termux_problems?do=export_xhtml)。
- **不能传参**：点击即无参运行，变通靠多写脚本。

### 6.2 moxsh 重写设计

- **模块边界**：`ShortcutManager`（可视化管理，自动设安全权限）↔ `WidgetProvider`（AppWidgetProvider）↔ `InAppExecutor`（同进程 Binder 执行）↔ `WidgetStateStore`（实时刷新/结果徽标）。
- **加固 IPC**：脚本在 **moxsh 自身进程内**执行（同进程 Binder，无需跨应用唤醒），降低被后台杀概率；失败在部件红点提示。
- **权限模型**：内置「快捷指令管理器」UI 自动把脚本目录权限设为安全值，告别 `chmod 700` 手动排错。
- **双体系落位**：体系② 原生部件；体系① 兼容宿主仍读取 `~/.shortcuts/` 与 `tasks/` 约定。

### 6.3 液态玻璃 UI 落地

- 液态玻璃桌面部件：玻璃卡片 + 圆角 + 动态取色。
- 支持**点击传参**（弹玻璃输入卡）、分组、自定义图标、实时刷新、执行结果徽标；`tasks/` 后台任务有进度反馈。

### 6.4 性能 / 体验优化点

| 项 | Termux:Widget | moxsh Widget |
|---|---|---|
| 执行链路 | 发 Intent → 跨应用主程序 | 同进程 Binder，省跨应用唤醒 |
| 目录权限 | 必须手动 700 | 管理器自动设安全值 |
| 实时性 | 不监听目录 | `FileObserver` 实时刷新 |
| 传参/分组 | 不支持 | 玻璃输入卡 + 分组 + 图标 |

### 6.5 关键代码片段

```kotlin
// GlassWidget.kt —— Glance 液态玻璃卡片 + 实时刷新
class MoxshWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive
    @Composable
    override fun Content() {
        val shortcuts by rememberUpdatedState(ShortcutStore.list()) // FileObserver 驱动
        GlanceTheme {
            Box(
                modifier = BoxModifier
                    .background(GlassBrush())           // 半透明毛玻璃
                    .cornerRadius(20.dp)
                    .padding(12.dp)
            ) {
                LazyColumn {
                    items(shortcuts) { s ->
                        Button(text = s.name, onClick = actionRun(s))   // 同进程执行
                    }
                }
            }
        }
    }
    private fun actionRun(s: Shortcut) = if (s.needsArg)
        actionStartActivity<ArgGlassActivity>(s) else actionRunInApp(s)
}

// ShortcutManager.kt —— 自动修正目录权限，免手动 chmod 700
object ShortcutManager {
    fun secure(dir: File) {
        dir.setReadable(false, false); dir.setReadable(true, true)   // 仅 owner 可读
        dir.setWritable(false, false);  dir.setWritable(true, true)
        dir.setExecutable(true, true)
    }
    fun observe() = FileObserver(dir, CREATE or DELETE or MODIFY) { _, _ -> WidgetStateStore.refresh() }
}

// ArgGlassActivity.kt —— 点击传参的玻璃输入卡
@Composable
fun ArgGlassSheet(script: Shortcut, onRun: (List<String>) -> Unit) {
    GlassSurface {
        var arg by remember { mutableStateOf("") }
        OutlinedTextField(arg, { arg = it }, label = { Text("参数") })
        Button(onClick = { onRun(arg.split(" ")) }) { Text("运行") }
    }
}
```

### 6.6 兼容性说明

- 保留 `~/.shortcuts/` 与 `tasks/` 目录约定（体系① 兼容宿主识别），旧部件配置可直接迁移。
- 目录权限由管理器自动维护为安全值，消除「必须手动 700」的踩坑。
- 实时刷新 + 结果徽标替代「点击无反应」的静默失败；点击传参为增强项，旧无参脚本仍按原语义运行。

---

## 七、统一交付清单与风险

- **双体系落位**：体系①（TermuxHost 兼容 `RUN_COMMAND`/`LocalServerSocket`，包别名重定向）覆盖迁移期兼容；体系②（API/Boot/Float/Styling/Tasker/Widget 内置模块）覆盖本期全部 6 个插件，单一签名、液态玻璃、Binder 加固。
- **X11 递延**：本期不做，仅保留零拷贝通道接口抽象（见 §0.4）。
- **共性风险**：厂商自启动/电池优化豁免依赖系统设置，需 Compose 引导式检测与一键跳转兜底（见各章权限模型）。
- **签名不互通**：体系① 与原 Termux 插件不共享签名校验，仅 moxsh 签名侧生态内可用，已显式声明（见 §0.2）。

---

*本报告基于 [`research/termux-plugins-shortcomings.md`](../../research/termux-plugins-shortcomings.md) 的 12 条发现与 [`docs/commands.md`](./commands.md) 的命令体系盘点撰写；代码段为体现设计意图的示意实现，非完整生产代码。*

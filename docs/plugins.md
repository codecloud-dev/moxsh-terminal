# 插件开发指南

moxsh 的插件、AI 技能、主题、发行版镜像统一使用 `.mox` 包格式分发。本文介绍从零写一个插件、打包签名、到上架商店的完整流程。

## 插件体系概览

moxsh 有两套插件体系：

- **moxsh 原生插件**：`.mox` 格式，独立签名，可以带液态玻璃图形界面。本文的主角。
- **Termux 兼容插件**：Termux:API、Termux:Widget 等原有插件继续可用（兼容宿主负责）；外来 zip 格式插件包在导入时自动转换成 `.mox`。

原生插件按 `type` 分四类：

| type | 用途 | payload 内容 |
|---|---|---|
| `plugin` | 功能插件（悬浮窗、监控面板等） | 插件 so / 资源文件 |
| `skill` | AI 技能包（教 AI 做某类事） | `skill.json` + 提示词/脚本 |
| `theme` | 主题包（配色、色板） | 主题文件 |
| `rootfs` | 发行版镜像 | rootfs 文件树 |

## .mox 包格式

`.mox` 本质是一个 **tar 包**（兼容 tar.gz，加载时按文件头自动识别），结构如下：

```text
my-plugin.mox
├── manifest.json      # 元数据（必填）
├── signature          # HMAC-SHA256 签名（hex 小写）
└── payload/           # 实际内容
    └── ...
```

### manifest.json 字段

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 是 | 包唯一 id，形如 `float.terminal`、`skill.python` |
| `name` | string | 是 | 显示名 |
| `version` | string | 是 | 语义化版本，如 `1.0.0` |
| `type` | string | 是 | `plugin` / `skill` / `theme` / `rootfs` |
| `author` | string | 否 | 作者名 |
| `description` | string | 否 | 一句话描述（商店卡片显示） |
| `minAppVersion` | string | 否 | 最低宿主版本，如 `0.1.0` |
| `permissions` | string[] | 否 | 声明的权限，安装时向用户展示并确认 |
| `price` | number | 否 | 0=免费；>0 付费（售卖通道后续开放） |
| `purchased` | bool | 否 | 预留字段，不用管 |

最小示例：

```json
{
  "id": "demo.hello",
  "name": "你好插件",
  "version": "1.0.0",
  "type": "plugin",
  "author": "你的名字",
  "description": "一个最小示例插件",
  "minAppVersion": "0.1.0",
  "permissions": ["run_command"],
  "price": 0,
  "purchased": false
}
```

### 权限列表

权限在安装时向用户明示，只声明真正需要的。运行期每个 API 入口都会校验，
未声明就调用会得到 `PluginPermissionDenied` 异常（fail-fast）：

| 权限 | 含义 | 对应 API |
|---|---|---|
| `run_command` | 在终端环境执行命令 | `sessions.write` / `sessions.runLine` |
| `install_pkg` | 安装软件包 | 图形化包管理动作 |
| `install_distro` | 安装发行版（PRoot） | 发行版管理动作 |
| `change_repo` | 更换软件源 | 源配置动作 |
| `explain_error` | 读取报错信息（AI 技能常用） | AI 报错解释 |
| `manage_sessions` | 创建/关闭/切换终端会话 | `sessions.create/close/list/resize` |
| `read_screen` | 读取屏幕与回滚缓冲文本 | `sessions.screenDump` |
| `post_notification` | 发送系统通知与 Toast | `ui.notify` / `ui.toast` |
| `clipboard` | 读写系统剪贴板 | `ui.copyToClipboard/readClipboard` |
| `vibrate` | 震动/触感反馈 | `ui.vibrate` |
| `network` | 发起 HTTP 请求（15s 超时 / 5MB 上限） | `net.get/post` |
| `storage` | 插件私有目录读写 + 导出到 Download | `fs.*` / `store.*` |
| `register_skill` | 向 AI 助手注册技能工具 | `skills.register` |
| `subscribe_events` | 订阅命令/输出/会话/开机事件 | `events.*` / `sessions.onOutput` |

## 插件 API v2（原生插件）

原生玻璃插件（继承 `MoxPlugin` 基类）通过 `api` 属性调用主 app 的全部能力面。
相比 v1 的"只能发命令"，v2 暴露七个域：

```kotlin
class NetDiagPlugin : MoxPlugin() {
    override val id = "netdiag.glass"
    override val permissions = setOf(
        PluginPermissions.NETWORK,          // 网络请求
        PluginPermissions.POST_NOTIFICATION, // 通知/Toast
        PluginPermissions.REGISTER_SKILL,    // 注册 AI 技能
    )

    override fun onLoad(api: PluginApi) {
        // 把能力挂进 AI 助手：用户对 AI 说"测一下 github 通不通"即可触发
        api.skills.register(
            name = "ping",
            description = "测试一个网站的连通性并返回 HTTP 状态码",
            parametersSchema = """{"url":"string"}""",
        ) { args ->
            val r = api.net.get(args["url"] ?: "https://github.com")
            "HTTP ${r.status}"
        }
    }

    override fun onEvent(event: PluginEvent) { /* 需 subscribe_events 权限 */ }

    @Composable
    override fun GlassContent(host: PluginHost) {
        // 用 com.moxsh.ui.component 的玻璃组件搭界面
    }
}
```

七域能力速查：

| 域 | 能做什么 |
|---|---|
| `api.sessions` | 多会话创建/关闭/列表/写入/改尺寸；`screenDump` 读取屏幕+回滚文本；`startPump` 驱动输出并触发事件 |
| `api.ui` | Toast、系统通知（自动降级）、震动、剪贴板读写（主线程安全） |
| `api.fs` | 插件私有目录（随卸载删除）；`exportToDownloads` 经 MediaStore 导出到 Download/moxsh/（无需存储权限） |
| `api.net` | GET/POST，固定 15s 超时、5MB 响应上限；请勿在主线程调用 |
| `api.store` | 插件私有 KV（Properties 落盘，随插件卸载删除） |
| `api.events` | 订阅命令执行/输出产出/会话开闭/环境就绪事件（回调在主线程） |
| `api.skills` | 把插件能力注册为 AI 可调用的工具（name 自动加插件前缀防冲突） |

宿主侧（主 app）通过 `PluginHost.createApi(id, context, permissions)` 构造 API，
`PluginHost.events.publish(...)` 发布全局事件。

### 装配与事件接线（已落地）

主 app 侧的装配点在 `app/.../PluginManager.kt`（Application.onCreate 调用 `init`）：

1. **内置插件注册**：DemoGlassPlugin + float/styling/boot/widget 五个玻璃卡片入口，
   由主界面标签栏 `✦` 按钮进入插件面板渲染；
2. **v2 动态插件**：plugin-store 安装链路调用 `host.load(moxPlugin, appContext)`
   —— 自动注入 `PluginApi`（按声明权限构造）并回调 `onLoad`；
3. **事件桥**（shared 不感知 plugin，方向由 plugin 侧注册，防循环依赖）：
   - `ExecutionEngine.sessionListener` → 会话开/关转 `SessionOpened`/`SessionClosed`；
   - `BootstrapState.onReady` → 环境就绪转 `BootCompleted`；
   - `PluginHost.runLocalCommand` / `runRemoteCommand` → 成功后发布 `CommandExecuted`。

插件本地执行命令的目标是**当前活跃会话**（`ExecutionEngine.activeSessionId`，
UI 切换标签时自动同步）。

已知边界：Termux 官方 bootstrap 产物按 `com.termux` 前缀硬编码，moxsh 在
`initialize` 阶段写自研 login/覆盖 profile 并批量修正脚本文本前缀；ELF 内的
硬编码前缀（如 dpkg 数据库路径）暂依赖 proot 翻译层处理（后续迭代）。

## 从零打包一个插件

以一个最小 `plugin` 类型为例。准备一个工作目录：

```text
hello/
├── manifest.json
└── payload/
    └── hello.sh          # 你的插件内容，按需组织
```

### ① 写 manifest.json

照上面的字段表填。`id` 全局唯一，建议用 `作者.功能` 的命名习惯。

### ② 签名

`.mox` 使用 HMAC-SHA256 对 `manifest.json` 的原始字节签名，签名结果（hex 小写）存为 `signature` 文件：

```bash
cd hello
openssl dgst -sha256 -hmac "你的签名密钥" manifest.json \
  | awk '{print $2}' > signature
```

> 开发期使用与宿主一致的官方测试密钥即可通过验签；验签密钥**不再硬编码在库源码**里，而是由构建注入：CI 设置环境变量 `MOX_STORE_SECRET`（或本地 `local.properties` 的 `MOX_STORE_SECRET`），经 `BuildConfig.MOX_STORE_SECRET` 暴露给 `StoreRepository.install`（详见 `plugin-store/build.gradle.kts`）。未注入时回退为开发期占位常量。**正式发布必须在 CI 注入真实签名密钥**，切勿把生产密钥提交进仓库；第三方正式分发请使用你自己的密钥并在发布渠道说明，避免被他人冒签。

### ③ 打 tar 包

在 `hello/` 目录内打包（保证 tar 内路径不带 `./` 前缀）：

```bash
tar cf hello-plugin.mox manifest.json signature payload
```

得到 `hello-plugin.mox`，这就是可安装的插件包。

### ④ 本地安装测试

把包放进下载目录的商店文件夹（离线安装源）：

```bash
mkdir -p /sdcard/Download/moxsh-store
cp hello-plugin.mox /sdcard/Download/moxsh-store/
```

打开 moxsh → 插件商店 → 刷新。本地源和云端源合并展示，你的插件会出现在列表里，点"一键安装"走完整链路：**下载 → 验签 → 解压 → 启用**。验签失败会明确提示"包被篡改或来源不可信"。

安装落位在应用私有目录 `<dataDir>/store/installed/<id>/`，在商店的"已安装"里可以停用/卸载。

## 开发 AI 技能包（type=skill）

技能包教 moxsh 的 AI 助手做一类事情。`payload/` 里放一个 `skill.json`：

```json
{
  "prompt": "你是 Python 学习助手……（系统提示词）",
  "tools": ["install_pkg", "run_command", "explain_error"],
  "params_schema": "{}"
}
```

- `prompt`：技能被启用时注入的系统提示词
- `tools`：允许该技能使用的工具白名单（`install_distro` / `install_pkg` / `change_repo` / `run_command` / `explain_error` 等）
- `params_schema`：工具参数的 JSON Schema 约束（可选）

manifest 的 `type` 填 `"skill"`，其余流程与插件完全一致。参考内置条目 `skill.python`。

## 上架到云端商店

商店有三种数据源，合并展示、id 去重（本地 > 云端 > 内置）：

1. **本地源**：`/sdcard/Download/moxsh-store/*.mox`，用户手动放包，离线可用
2. **云端源**：一个静态 HTTP 服务即可，约定两个接口：

```text
GET <base>/catalog.json      # 目录清单
GET <base>/pkg/<id>.mox      # 包文件
```

`catalog.json` 格式（manifest 字段外加 `sizeBytes`）：

```json
{
  "entries": [
    {
      "id": "demo.hello",
      "name": "你好插件",
      "version": "1.0.0",
      "author": "你的名字",
      "description": "一个最小示例插件",
      "type": "plugin",
      "minAppVersion": "0.1.0",
      "permissions": ["run_command"],
      "price": 0,
      "purchased": false,
      "sizeBytes": 20480
    }
  ]
}
```

也就是说：把 `.mox` 文件和一份 `catalog.json` 放到任意静态文件服务器（GitHub Pages、对象存储、自建 nginx 都行），就是一个可用的商店源。

3. **内置目录**：应用内置的兜底条目，云端不可用时商店依然可浏览。

### 版本更新

同一个 `id` 发新版本：改 `manifest.json` 里的 `version`，重打签名和 tar，替换云端 `pkg/<id>.mox` 并更新 `catalog.json`。商店读取已安装落盘的 manifest 比对版本，提示更新。

## Termux 插件兼容

- Termux 官方插件（API/Widget/Boot/Styling/Float/Tasker）经兼容宿主直接使用，不需要转 `.mox`。
- 第三方 zip 格式插件包（zip 容器 + `package.json`）在上传导入时自动转换：解 zip → 生成 manifest → 重打为标准 `.mox` 再走验签。

## 常见问题

**安装提示"验签失败"？**
`signature` 必须是对 `manifest.json` 原始字节（不是重新格式化后的 JSON）做 HMAC-SHA256 的 hex 小写。重新格式化 manifest 会让签名失效，需要重签。

**商店里看不到我的包？**
检查文件是否在 `/sdcard/Download/moxsh-store/` 且扩展名是 `.mox`；manifest 不合法的包会被商店静默跳过。

**tar 里路径带 `./` 前缀有影响吗？**
请在工作目录内部打包（`tar cf ../x.mox manifest.json signature payload`），保持条目名干净。

**相关代码在哪里？**
包格式权威定义：`terminal-core/src/moxpkg.rs`；Kotlin 封装：`shared/src/main/java/com/moxsh/shared/MoxPackage.kt`；商店与安装链路：`plugins/plugin-store/`。

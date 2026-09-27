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

权限在安装时向用户明示，只声明真正需要的：

| 权限 | 含义 |
|---|---|
| `run_command` | 在终端环境执行命令 |
| `install_pkg` | 安装软件包 |
| `install_distro` | 安装发行版（PRoot） |
| `change_repo` | 更换软件源 |
| `explain_error` | 读取报错信息（AI 技能常用） |

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

> 开发期使用与宿主一致的官方测试密钥即可通过验签（见 `plugin-store` 的 `StoreSecrets`）；正式分发请使用你自己的密钥并在发布渠道说明，避免被他人冒签。

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

# 安全政策

<p align="center">
  <img src="https://img.shields.io/badge/响应时效-72%20小时内-8a7bff" alt="72 小时响应">
  <img src="https://img.shields.io/badge/报告渠道-GitHub%20私密漏洞-181717?logo=github&logoColor=white" alt="私密报告">
  <img src="https://img.shields.io/badge/签名-HMAC--SHA256-3DDC84" alt="HMAC-SHA256">
</p>

**目录**

- [支持的版本](#支持的版本)
- [报告漏洞](#报告漏洞)
- [安全模型说明](#安全模型说明)
- [加密通信与签名](#加密通信与签名)

---

## 支持的版本

moxsh 目前处于快速迭代阶段，安全修复仅针对最新发布的版本：

| 版本 | 支持状态 |
| --- | --- |
| 1.0.x（当前发布线，versionName 1.0.1） | 支持 |
| 更早版本 | 不支持，请升级 |

## 报告漏洞

> 请勿通过公开的 GitHub Issue 报告安全漏洞。

请通过 GitHub 私密漏洞报告渠道提交：

**GitHub 私密漏洞报告**：仓库页 → Security → Report a vulnerability（推荐）

请在报告中尽量包含：

- 受影响的组件与版本（或 commit hash）
- 复现步骤或概念验证（PoC）
- 影响评估（能造成什么后果）

我们会在 **72 小时内**确认收到，并在评估后同步修复进展。修复发布前会与报告人协商披露时间。

## 安全模型说明

moxsh 是一个终端与容器环境，以下行为属于**设计预期**，不视为漏洞：

- 终端会话内执行的任意命令具有与 App 相同的权限（Android 应用沙箱之内）
- PRoot 容器内进程获得"伪装 root"视图，但并不突破 Android 沙箱（非真实 root）
- 用户主动安装的 .mox 插件在其声明权限范围内的行为
- 用户主动配置的第三方 AI API Key 的流出风险（Key 由用户自行保管）

以下属于**安全边界**，突破即为漏洞：

- 绕过 Android 应用沙箱读取其他应用私有数据
- .mox 插件未声明对应权限却执行了受保护操作（权限模型被绕过）
- 签名校验可被伪造（.mox 签名机制失效）
- bootstrap / rootfs 下载的 sha256 校验可被绕过

## 加密通信与签名

- 发行版 rootfs 下载强制 sha256 校验，国内镜像与官方源结果必须一致
- **插件签名现状（2026-11-11 复核后如实标注）**：当前实现使用共享密钥 HMAC-SHA256 校验
  （见 `moxsh/plugins/plugin-store/src/main/java/com/moxsh/plugin/store/StoreRepository.kt`）。
  密钥由构建期 `MOX_STORE_SECRET` 注入 `BuildConfig`；`plugin-store/build.gradle.kts` 已做
  **fail-closed**：release 构建若未注入真实密钥会直接构建失败，禁止回退到源码里的开发期占位常量，
  因此「反编译 APK 直接取得生产签名密钥」这条旧描述已不再成立。
- **仍未收口的差距（真实存在）**：`moxsh-suite/spec/plugin-manifest.schema.json` 定义了
  ed25519 / rsa-pss / ecdsa 非对称签名、`keyId` 密钥轮换与 `timestamp` 时间戳；
  `moxsh-plugins/schema/catalog.schema.json` 与 `schema/entry-schema.json` 已于 2026-11-11
  补齐同构的 `integrity` / `signature` / `signingKeyId` 字段（契约先行），但**终端侧验签代码尚未迁移**：
  仍是对称 HMAC、无 `keyId` 轮换、不校验签名过期或撤销。共享密钥一旦从已分发 APK 中被提取，
  伪造官方插件仍是有效攻击面。这是**已知差距**，不是设计选择，欢迎通过私密渠道报告。

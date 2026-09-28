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
| 0.6.x（main 分支最新构建） | 支持 |
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

- .mox 包使用 HMAC-SHA256 签名（见 `docs/plugins.md`）
- 发行版 rootfs 下载强制 sha256 校验，国内镜像与官方源结果必须一致

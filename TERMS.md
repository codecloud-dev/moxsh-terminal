# 用户协议 / 服务条款（Terms of Service）

**适用仓库**：`codecloud-dev/moxsh-terminal`（moxsh —— Android 液态玻璃终端 App）
**生效日期 / Effective date**：2026-11-15
**协议主体**：`codecloud-dev` —— 一个 GitHub 个人账号，由个人开发者运营，**不是公司、不是法人实体**；没有公司名称、注册号、注册地址或客服电话。本文「我们」仅指该账号及其维护的公开仓库。

> 中文在前，英文在后，同一份文件；口径一致，歧义以中文为准。
> 本文件与 `PRIVACY.md` 配套：数据行为以 `PRIVACY.md` 为准，使用条件以本文件为准。
> 本仓库代码按 **GPL-3.0**（见 `LICENSE`）授权。本条款不缩减该许可授予你的任何权利；凡本条款与 `LICENSE` 冲突，以 `LICENSE` 为准。

---

## 一、接受条款

下载、安装、运行本 App，或使用其联网功能（GitHub 登录、AI 助手、插件商店、引擎下载），即表示你已阅读并同意本条款与 `PRIVACY.md`。不同意请停止使用并卸载 App。

当前版本**没有账号注册协议、没有付费流程、没有云同步**（`AuthConfig.ENABLE_CLOUD_SYNC = false`），因此本条款不涉及订阅、自动续费或虚拟货币。

## 二、软件按「现状」提供与免责

- 本软件按 **「现状」（AS IS）** 提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性、不侵权、不中断、无缺陷、兼容任意设备或 Android 版本。
- 本软件是**终端模拟器 + 容器运行环境**，会执行你输入的命令、安装你选择的包、运行你选择的插件。**命令、脚本、插件、发行版、AI 生成内容的行为与后果由你自行承担**；我们不为其正确性、安全性、合法性负责。
- 在适用法律允许的最大范围内，我们不对任何直接、间接、附带、特殊或后果性损失（含数据丢失、设备损坏、业务中断、利润损失）承担责任，即使已被告知可能发生。
- 终端可能在容器内获得对文件系统的广泛访问能力；**请自行备份数据、谨慎执行 `rm`、`dd`、包管理器与提权命令**。
- 当前版本**未实现**：自动崩溃上报、远程擦除、官方技术支持 SLA、数据恢复服务。

## 三、可接受使用（AUP）

你同意仅将本软件用于合法目的，并自行确保：

- 遵守你所在国家/地区的法律，以及 GitHub、Gitee、AI 服务商、发行版源站等第三方各自的条款；
- 不利用本软件入侵他人系统、抓取未授权数据、传播恶意软件、挖矿滥用、发起拒绝服务、规避网络管制或实施诈骗；
- 不把本软件作为绕过其它服务授权、计费、访问控制或安全机制的工具；
- 自己填写的 GitHub token、AI apiKey、邮箱密码由自己保管，不把凭据贴到公开 Issue、日志或截图里；
- 安装第三方 `.mox` 包、AI 技能、发行版脚本前自行判断来源可信度；`MoxPackage.verify()` 的 HMAC-SHA256 验签只保证包未被篡改且来自持有密钥的构建方，**不担保包内代码安全或无害**。

我们保留在明显滥用时拒绝提供联网入口（登录、商店、引擎下载）的权利，但**无义务监控**你的使用，也不对第三方行为背书。

## 四、禁止逆向滥用与绕过授权

- 允许：阅读、修改、再分发本仓库代码 —— 这些是 **GPL-3.0 明确授予的权利**，本条款不限制；为互操作性而进行的必要分析亦不受限。
- 禁止的是**以规避授权与安全机制为目的的滥用**，包括但不限于：
  - 伪造、篡改、重放 GitHub 设备流令牌或会话，冒充他人登录；
  - 绕过 `.mox` 包验签、伪造构建期签名密钥、篡改插件清单以取得未授权权限；
  - 绕过邮箱验证码频控、批量伪造注册、冒用 SMTP 发信通道；
  - 反编译/修改客户端以规避 AI 服务商鉴权、盗用他人 apiKey、伪造额度；
  - 绕过或规避第八节的**插件版本安全策略**；
  - 以任何方式规避本系列（含 moxsh-proot、mox-id、Fenestra）已有的兑换码、设备绑定、商业授权或付费门禁。
- 上述禁止不扩张为「禁止一切逆向」：GPL-3.0 授予的研究、修改、再分发权利完整保留；被禁止的是**用于绕过授权、侵犯他人或规避付费**的具体滥用行为。

## 五、第三方服务（链接指向，不为其背书）

本 App 会连到这些第三方，使用即同时受其条款约束；我们**不代替它们解释、不担保其可用性、不对其行为负责、不为其背书**：

| 服务 | 用途 | 条款 / 隐私政策入口 |
| --- | --- | --- |
| GitHub | 设备流登录、`/user`、`/user/emails`、主仓库与 Issues | `https://docs.github.com/en/site-policy/github-terms/github-terms-of-service` · `https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement` |
| Gitee | 国内只读镜像、PRoot 引擎二进制下载 | `https://gitee.com/`（以其站内公布条款为准） |
| 爱发电 | 赞助 / 兑换相关（见 proot、官网政策） | `https://afdian.com/`（以其站内公布条款为准） |
| Cloudflare | `mox-id` 运行时（邮箱验证码路径，当前未配置） | `https://www.cloudflare.com/privacypolicy/` |
| DeepSeek / 智谱 / 通义千问 / Kimi / 自定义网关 | 你自选的 AI 服务商 | 见各服务商官网公布的条款；你填谁的 Key 受谁约束 |

以各站最新公布条款为准。第三方服务中断、改版、限流、封禁或条款变化，我们不承担责任。

## 六、专项条款 A：插件版本安全策略（用户义务）

> 这一条是**用户义务**，不是功能说明书。该机制由本项目另一条开发线实现；本条款**只规定用户义务，不描述、不承诺其具体实现方式与当前进度，也不虚构任何实现细节**；实现行为以届时发布的客户端与可信目录（`catalog.json` 的 `minVersion` / `revokedVersions`）为准。

1. **禁止回滚到存在已知漏洞的旧版本**：当某个插件版本被标记为存在已知安全漏洞时，你**不得**将已安装插件降级、回滚、侧载或强制安装到该版本（含手动替换 `dataDir/store/installed/<id>/` 下的文件、从旧备份恢复、修改清单里的 `version` 字段伪装成新版本）。
2. **不得绕过该机制**：你不得禁用、篡改、拦截或伪造版本安全校验（包括修改校验结果、注入伪造清单、使用非官方渠道包绕过校验、Hook / 反射改变判定、绕过商店直接从缓存目录启动旧包等）。
3. **理由**：终端及其容器环境常持有敏感数据与凭据，把已知漏洞重新引入会扩大风险面；升级到修复版本是唯一合规路径。
4. **后果**：客户端可拒绝安装、拒绝启用或直接禁用被标记版本；由此导致的插件不可用、功能降级，按第二节「按现状提供」由你自行承担。你始终可以：升级到已修复版本、卸载该插件、从官方源重新安装。
5. 本条与第四节「禁止绕过授权」相互独立、同时适用。

## 七、知识产权与许可

- 本仓库代码按 **GNU GPL v3.0**（`LICENSE`）授权：你可以阅读、修改、再分发；分发派生版本须遵守 GPL-3.0 的同等许可与源码提供义务。**本用户协议不叠加额外限制、不收取费用、不改变 GPL-3.0**。
- 品牌标识、`moxsh` 名称、图标与文档版权归 `codecloud-dev`；GPL-3.0 对代码的授权不等于对商标的授权。
- 插件 `.mox` 包、AI 技能、主题、发行版脚本由各自作者负责，**不由我们担保**，见第二节。
- 你向本仓库提交的 Issue / PR 内容，视为同意按仓库的 `CONTRIBUTING.md` 与适用许可公开处理。

## 八、变更、终止与争议

- 本条款变更直接更新本仓库 `TERMS.md`，留痕于 Git 提交与 CHANGELOG；不收集联系方式，故**不主动推送**，重大变更在仓库公告。继续使用视为接受更新。
- 你可随时终止：卸载 App、退出 GitHub 登录、清除 `mox_secure_session` / `mox_secure_email` / `moxsh_ai_config`、删除插件与本地数据。终止不影响终止前已发生的合法行为，也不自动删除第三方（GitHub / AI 服务商）侧的数据。
- 因不可抗力、第三方服务终止、平台下架、上游源码/镜像失效导致的功能不可用，我们不承担责任。
- 争议应先友好协商；协商不成，提交**版权人所在地有管辖权的法院**处理。本条款以中文书写，英文仅为阅读便利，歧义以中文为准。若某条被认定无效，其余条款继续有效。

## 九、联系方式

- **唯一联系方式**：在对应仓库开 **GitHub Issues**（`https://github.com/codecloud-dev/<repo>/issues`），或联系 GitHub 账号 **`codecloud-dev`**。
- 不提供、不编造客服邮箱、电话、通信地址、公司名、注册号。请勿在公开 Issue 里贴 token、apiKey、密码或凭据。

---
---

# English — Terms of Service

**Repository**: `codecloud-dev/moxsh-terminal` (moxsh — an Android "liquid glass" terminal app)
**Effective date**: 2026-11-15
**Party**: `codecloud-dev` — a personal GitHub account run by an individual developer, **not a company or legal entity**; no company name, registration number, registered address, or support phone. "We" refers only to this account and the public repositories it maintains.

> Chinese first, English after, in one file; the Chinese text controls in case of conflict.
> This document pairs with `PRIVACY.md`: data practices are governed by `PRIVACY.md`, terms of use by this document.
> The code is licensed under **GPL-3.0** (see `LICENSE`). These terms do not reduce any right that licence grants you; where this document conflicts with `LICENSE`, `LICENSE` controls.

## 1. Acceptance

Downloading, installing, running the app, or using its online features (GitHub login, AI assistant, plugin store, engine download) means you have read and agree to these terms and to `PRIVACY.md`. If you do not agree, stop using and uninstall the app.

The current version has **no account registration agreement, no payment flow, and no cloud sync** (`AuthConfig.ENABLE_CLOUD_SYNC = false`), so these terms involve no subscription, auto-renewal, or virtual currency.

## 2. "As is" and disclaimer

- The software is provided **"AS IS"**, without any express or implied warranty, including merchantability, fitness for a particular purpose, non-infringement, uninterrupted operation, defect-free operation, or compatibility with any device or Android version.
- This is a **terminal emulator + container runtime**: it executes commands you type, installs packages you choose, and runs plugins you choose. **You bear the behaviour and consequences of commands, scripts, plugins, distros, and AI-generated content**; we are not responsible for their correctness, safety, or legality.
- To the maximum extent permitted by law, we are not liable for any direct, indirect, incidental, special, or consequential loss (including data loss, device damage, business interruption, lost profit), even if advised of the possibility.
- The terminal may obtain broad filesystem access inside containers; **back up your own data and be careful with `rm`, `dd`, package managers, and privilege-escalation commands**.
- **Not implemented**: automatic crash reporting, remote wipe, an official support SLA, or a data-recovery service.

## 3. Acceptable use (AUP)

You agree to use the software only for lawful purposes and to ensure that you:

- Comply with the law of your country/region and with the terms of GitHub, Gitee, AI providers, distro sources, and other third parties;
- Do not use it to intrude into others' systems, scrape unauthorised data, spread malware, abuse mining, launch denial-of-service, evade network controls, or commit fraud;
- Do not use it as a tool to bypass another service's authorisation, billing, access control, or security mechanism;
- Keep your own GitHub token, AI apiKey, and email password to yourself, and do not paste credentials into public Issues, logs, or screenshots;
- Judge the trustworthiness of third-party `.mox` packages, AI skills, and distro scripts yourself before installing; `MoxPackage.verify()`'s HMAC-SHA256 check only proves a package was not tampered with and came from a builder holding the key — **it does not warrant that the code inside is safe or harmless**.

We reserve the right to deny online entry points (login, store, engine download) in cases of obvious abuse, but have **no duty to monitor** your use and do not endorse third-party conduct.

## 4. No abusive reverse engineering / no bypassing authorisation

- Allowed: reading, modifying and redistributing this repository's code — these are **rights expressly granted by GPL-3.0**, and these terms do not restrict them; analysis necessary for interoperability is likewise unrestricted.
- What is prohibited is **abuse aimed at circumventing authorisation and security mechanisms**, including but not limited to:
  - Forging, tampering with, or replaying GitHub Device Flow tokens or sessions to impersonate someone else;
  - Bypassing `.mox` package signature verification, forging the build-time signing key, or altering plugin manifests to obtain unauthorised permissions;
  - Bypassing email-code rate limits, bulk-forging registrations, or abusing the SMTP sending channel;
  - Decompiling/modifying the client to evade AI-provider authentication, steal another person's apiKey, or fake quotas;
  - Bypassing or evading the **plugin version security policy** in §6;
  - In any way evading existing redeem-code, device-binding, commercial-licensing, or paywall gates of this series (including moxsh-proot, mox-id, Fenestra).
- This prohibition does **not** expand into "all reverse engineering is forbidden": the research, modification and redistribution rights granted by GPL-3.0 remain intact; what is prohibited is the specific abusive conduct of bypassing authorisation, infringing others, or evading payment.

## 5. Third-party services (links only, no endorsement)

The app connects to these third parties; using them also binds you to their terms. We **do not interpret their terms, guarantee their availability, take responsibility for them, or endorse them**:

| Service | Purpose | Terms / privacy policy |
| --- | --- | --- |
| GitHub | Device Flow login, `/user`, `/user/emails`, canonical repo and Issues | `https://docs.github.com/en/site-policy/github-terms/github-terms-of-service` · `https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement` |
| Gitee | Mainland-China read-only mirror, PRoot engine binary download | `https://gitee.com/` (see its published terms) |
| Afdian | Sponsorship / redemption related (see the proot and website policies) | `https://afdian.com/` (see its published terms) |
| Cloudflare | `mox-id` runtime (email-code path, currently unconfigured) | `https://www.cloudflare.com/privacypolicy/` |
| DeepSeek / Zhipu / Qwen / Kimi / custom gateway | The AI provider you choose | See each provider's published terms; whoever's Key you enter, their terms bind you |

The latest published terms govern. We are not responsible for third-party outages, changes, rate limits, bans, or term changes.

## 6. Special clause A: plugin version security policy (user obligations)

> This clause states **user obligations**, not a feature specification. The mechanism is implemented by another workstream of this project; these terms **state only user obligations and do not describe, promise, or invent any implementation detail or current status**. Actual behaviour follows the client released at that time and the trusted catalogue declarations (`minVersion` / `revokedVersions` in `catalog.json`).

1. **No rollback to versions with known vulnerabilities**: when a plugin version is flagged as having a known security vulnerability, you **must not** downgrade, roll back, sideload, or force-install an installed plugin to that version (including manually replacing files under `dataDir/store/installed/<id>/`, restoring from an old backup, or editing the manifest's `version` field to masquerade as a newer version).
2. **No bypassing the mechanism**: you must not disable, tamper with, intercept, or forge the version security check (including altering check results, injecting forged manifests, using non-official packages to bypass it, hooking/reflection to change the verdict, or launching an old package directly from the cache directory to evade the store).
3. **Rationale**: the terminal and its containers often hold sensitive data and credentials; reintroducing a known vulnerability enlarges the attack surface. Upgrading to a fixed version is the only compliant path.
4. **Consequences**: the client may refuse to install, refuse to enable, or disable a flagged version; any resulting plugin unavailability or degraded functionality is borne by you under §2 ("as is"). You may always: upgrade to a fixed version, uninstall the plugin, or reinstall it from the official source.
5. This clause and §4 ("no bypassing authorisation") are independent and apply cumulatively.

## 7. Intellectual property and licence

- Repository code is licensed under **GNU GPL v3.0** (`LICENSE`): you may read, modify and redistribute it; distributing derivative versions must comply with GPL-3.0's same-licence and source-provision duties. **These terms add no extra restrictions, charge no fee, and do not change GPL-3.0**.
- Branding, the `moxsh` name, icons, and documentation are copyright `codecloud-dev`; a GPL-3.0 grant over code is not a trademark grant.
- `.mox` plugin packages, AI skills, themes, and distro scripts are the responsibility of their respective authors and **are not warranted by us** (§2).
- Content you submit as Issues / PRs is treated as agreed to be handled publicly under the repository's `CONTRIBUTING.md` and the applicable licence.

## 8. Changes, termination and disputes

- Changes update this repository's `TERMS.md` directly and appear in Git history / CHANGELOG. We collect no contact details, so **we do not push notifications**; significant changes are announced in the repository. Continued use constitutes acceptance.
- You may terminate at any time: uninstall the app, sign out of GitHub, clear `mox_secure_session` / `mox_secure_email` / `moxsh_ai_config`, and delete plugins and local data. Termination does not affect lawful conduct before termination and does not automatically delete data on the third-party side (GitHub / AI providers).
- We are not liable for unavailability caused by force majeure, third-party service termination, platform takedown, or upstream source/mirror failure.
- Disputes should first be resolved amicably; failing that, they are submitted to the **court of competent jurisdiction at the copyright holder's location**. These terms are written in Chinese; the English text is for convenience only and the Chinese controls in case of conflict. If any clause is held invalid, the remainder stays in effect.

## 9. Contact

- **Only contact channels**: open a **GitHub Issue** at `https://github.com/codecloud-dev/<repo>/issues`, or reach the GitHub account **`codecloud-dev`**.
- No support email, phone, postal address, company name, or registration number is provided or invented here. Please do not post tokens, apiKeys, passwords, or credentials in a public Issue.

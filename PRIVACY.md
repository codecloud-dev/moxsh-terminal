# 隐私政策 / Privacy Policy

**适用仓库**：`codecloud-dev/moxsh-terminal`（moxsh —— Android 液态玻璃终端 App）
**生效日期 / Effective date**：2026-11-15
**责任主体**：`codecloud-dev` —— 一个 GitHub 个人账号，由个人开发者运营，**不是公司、不是法人实体**；没有公司名称、注册号、注册地址或客服电话。

> 中文在前，英文在后，同一份文件；口径一致，歧义以中文为准。
> 本政策按 `moxsh/app/src/main/java/...`、`moxsh/plugins/...` 与 `AuthConfig.kt` 的**真实代码行为**逐条写。凡代码里没实现、没启用的能力，一律写明「当前版本未实现 / 未启用 / 不联网」。
> 撰写口径参考 GDPR 与《个人信息保护法》的最小必要、目的限定、透明原则，不引用、不复制任何条文原文。

---

## 一、会联网的功能，与不会联网的功能

| 功能 | 当前状态 | 联网对象 |
| --- | --- | --- |
| GitHub 登录（设备流 / Device Flow） | ✅ 已实现 | `github.com/login/device/code`、`github.com/login/oauth/access_token`、`api.github.com/user`、`api.github.com/user/emails` |
| 邮箱验证码绑定（高级路径） | ⚠️ **仅当配置了 `EMAIL_API_BASE` 才会发请求**；未配置时入口不可用、零网络请求 | 自建账号服务的 `/auth/email/send-code`、`/auth/email/verify` |
| AI 助手 | ✅ 已实现，**发往你自己在设置里选的服务商** | DeepSeek / 智谱 / 通义千问 / Kimi / OpenAI 兼容自定义 / 本地 `127.0.0.1` |
| 插件商店目录与包 | ⚠️ 云源地址为占位（`https://store.moxsh.local/v1`），**当前未部署**；拉取失败静默回退内置目录 | 可配 base 的 `catalog.json` 与 `pkg/<id>.mox`；主仓 `codecloud-dev/moxsh-plugins`（GitHub / Gitee 镜像） |
| PRoot 引擎二进制下载 | ✅ 已实现 | Gitee `codecloud-dev/moxsh-proot` 的 Releases |
| 命令云同步 | ❌ **当前版本未启用**：`AuthConfig.ENABLE_CLOUD_SYNC = false`，`SyncClient.syncNow()` 直接返回「云同步尚未启用」，**不发起任何网络请求** | 无 |
| 兑换码校验 / Gitee 登录 | ❌ **当前版本 App 内未实现**（这些在 `moxsh-proot` 引擎与账号服务 / 官网里） | 无 |

> 说明：README 把「命令云同步」列为产品方向，但源码里开关是关闭的。本政策以**代码为准**：当前版本命令历史只在本地排队，**不上传**。

---

## 二、逐项数据清单

### 2.1 GitHub 登录（`AuthConfig.kt` / `GitHubLogin.kt`）

- 使用 **OAuth Device Flow**（公开客户端，`CLIENT_ID` 由构建期注入，APK 内**不含 client_secret、不含私钥**）。
- 申请 scope：`read:user user:email read:org`（`AuthConfig.SCOPES`）。
- App 直连 GitHub，**不经过 codecloud-dev 的自有后端**。
- 拉取并展示的用户资料：`login`、数字 `id`、`name`、`avatar_url`、`email`（只取 `primary && verified` 的那一条）。
- **本地存储**：`SessionStore` 用 `EncryptedSharedPreferences`（文件名 `mox_secure_session`，Android Keystore 主密钥 AES-256-GCM，键/值分别 AES256-SIV / AES256-GCM）保存 `github_session_token` 与可选的 `github_session_refresh`。**Token 只落在你的设备上**，不上传给我们。
- 撤销入口：`GitHubLogin.revokeManagementUrl()` → `github.com/settings/connections/applications/<client_id>`。

### 2.2 邮箱注册 / 绑定（`EmailBindingStore.kt` / `docs/email-auth.md`）

三种路径，**主路径完全不联网**：

| 路径 | 是否联网 | 存什么 |
| --- | --- | --- |
| 邮箱 + 密码（主路径） | **否** | 邮箱、来源 `PASSWORD`、绑定时间；密码用 **PBKDF2-HMAC-SHA256（随机 16 字节盐 + 10,000 次迭代 + 256 位）** 派生后存 hash 与盐，**明文绝不落盘** |
| GitHub 已验证邮箱（可选快捷） | 复用 2.1 的 GitHub API | 邮箱、来源 `GITHUB`；免密 |
| 验证码通道（绑定与 GitHub 不同的邮箱） | 仅当 `EMAIL_API_BASE` 已配置 | `POST /auth/email/send-code` `{email}`、`POST /auth/email/verify` `{email, code}`，带 `Authorization: Bearer <GitHub token>`；后端以 token 解析出的 GitHub login 作归属主体防批量伪造 |

**本地存储**：`EmailBindingStore` 用 `mox_secure_email`（同为 EncryptedSharedPreferences）保存 `bound_email`、`bound_at`、`source`（GITHUB/PASSWORD/CODE）、`pw_hash`、`pw_salt`。邮箱账号**独立于 GitHub 会话**，退出 GitHub 登录不会清除邮箱。

> 客户端**绝不**持有邮箱 SMTP 授权码 / 客户端专用密码；该凭据只存在于服务端环境变量中。

### 2.3 AI 助手（`plugins/plugin-ai/.../AiClient.kt`）

- 服务商由**你自己选择**：`OpenAICompatible`（自填 baseUrl/Key/模型）、`DeepSeek`、`Zhipu`（智谱 `glm-4-flash`）、`Qwen`（通义千问）、`Moonshot`（Kimi）、`Local`（`http://127.0.0.1:8080/v1`，M7 规划中，端侧 llama.cpp）。
- 每次对话，App 把**完整对话历史**（`role: system/user/assistant/tool` 的 `content`、工具调用与工具结果）、`model`、`temperature`、`tools` 以 OpenAI 兼容 `POST /chat/completions`（`stream:true`，SSE）**直接发往你选的端点**。
- 请求头：`Authorization: Bearer <你的 apiKey>`、`User-Agent: moxsh-ai/1.0`。**AI 请求不经过 codecloud-dev 的任何服务器**；对话内容由你选择的服务商按其条款处理。
- **本地存储**：`moxsh_ai_config` 的 SharedPreferences 明文只存 `provider` / `base_url` / `model` / `temperature`；`apiKey` 经 **AndroidKeyStore 的 AES-256-GCM 密钥**加密后以 `api_key_enc` 落盘（GCM 随机 IV + 密文 Base64）。密钥材料不出安全硬件，**应用卸载即销毁**，换机/损坏时解密结果为空串。
- **当前版本未实现**：对话内容上传我方服务器、对话历史云端存储、模型使用遥测、用量统计上报。

> 提醒：AI 工具（AgentTools）可能把你终端里的命令、输出或文件内容作为工具结果放进对话，这些内容会随请求发给你选的服务商。请在敏感场景下不要启用 AI 或不要让它读取敏感数据。

### 2.4 插件商店（`plugins/plugin-store/.../StoreRepository.kt`）

- `CloudStoreApi.catalog()`：`GET <base>/catalog.json`，解析 `entries[]`，每条字段为 `id`、`name`、`version`、`author`、`description`、`type`、`minAppVersion`、`permissions`、`price`、`purchased`、`sizeBytes`、`minVersion`、`revokedVersions`。后两项是**插件版本安全策略的可信目录声明**（最低允许版本、已撤销的漏洞版本列表），App 安装时读取并据以拦截回滚（见 `TERMS.md` 第六节）。**这些是插件元数据，不是你的个人数据**。
- `CloudStoreApi.download()`：`GET <base>/pkg/<id>.mox`，流式写入 `cacheDir/store-<id>.mox`。
- 云源地址 `DEFAULT_BASE_URL = "https://store.moxsh.local/v1"` 是**占位域名，当前未部署**；`StoreRepository.loadCatalog()` 对任一源失败静默 `runCatching` 跳过后回退 `BuiltinCatalog`（4 条内置示例）。
- `LocalStoreApi`：扫描 `/sdcard/Download/moxsh-store/` 下的 `.mox` 包（离线安装）。
- 安装落位：`dataDir/store/installed/<id>/`（应用私有区，免存储权限）；启停标记存 SharedPreferences `moxsh_store`；安装前用 `MoxPackage.verify()` 做 **HMAC-SHA256 验签**，验签密钥由构建期 `MOX_STORE_SECRET` 注入（开发期回退占位常量）。
- **当前版本未实现**：账号、购买流程、付费校验、下载行为上报、安装统计遥测（`price` / `purchased` 字段注明「D17 预留，无支付流程」）。

### 2.5 命令历史本地队列（`cloud/CommandHistoryStore.kt`）

- 从 PTY 原始输入按行提取命令行，**按原文捕获（含参数）**，写 `filesDir/cloud_sync_queue.jsonl`，每行 `{"k":<时间戳ms>,"v":"<命令行>"}`；空行、含 ESC 控制序列的片段、`exit` 开头行不入队；单行上限 2000 字符；队列上限 **500 条**（超出丢最旧）。
- 注释明确：只在「已登录 + 用户开启云同步」时由 `ExecutionEngine.inputRecorder` 喂入；但 `ENABLE_CLOUD_SYNC = false`，`SyncClient.syncNow()` 立即返回错误，**当前版本不上传任何命令**。
- 隐私边界（代码注释）：命令行可能含敏感参数（如密码），云端仅用户本人凭据可读、传输走 https —— 这是**为将来启用云同步写的设计口径，当前未生效**。

### 2.6 PRoot 引擎下载（`shared/ProotManager.kt`）

缺二进制时从 Gitee `codecloud-dev/moxsh-proot` 的 `releases/download/proot-latest` 拉取引擎文件并标记可执行。这是**纯文件下载**，不携带账号、设备标识或任何用户数据；请求信息由 Gitee 按其日志政策记录。

### 2.7 权限清单（`AndroidManifest.xml`）

申请：`INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`（会话保活）、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`（悬浮窗）。`android:allowBackup="false"`。

**不申请、不使用**：位置、通讯录、相机、麦克风、短信、通话记录、外部存储读写、电话、广告 ID、生物识别、后台常驻上传。

---

## 三、不收集什么（当前版本）

- **不上传**：命令历史、终端输出、容器内文件内容、剪贴板、联系人、位置、相册、设备指纹、安装列表、使用时长。
- **不收集**：真实姓名、身份证号、手机号、精确地理位置、生物特征、广告标识符。
- **不持有**：邮箱 SMTP 授权码、GitHub client_secret、任何服务端私钥。
- **不植入**：广告 SDK、统计/埋点 SDK、崩溃自动上报、遥测、跨站追踪、自动化决策、用户画像。
- **未实现**：云同步（开关关闭）、Gitee 登录（App 内未实现，仅官网/引擎侧有）、兑换码校验（在 proot 引擎）、账号体系（App 内邮箱账号纯本地）、年龄核验、实名认证。

---

## 四、存多久、存哪、与谁共享

| 数据 | 位置 | 留存 / 删除 |
| --- | --- | --- |
| GitHub token / refresh token | 本机 EncryptedSharedPreferences `mox_secure_session` | 退出登录 `SessionStore.clear()`；卸载 App 即删 |
| 绑定邮箱、密码派生 hash + 盐 | 本机 `mox_secure_email` | `EmailBindingStore.clear()`；退出 GitHub **不清**邮箱；卸载 App 即删 |
| 邮箱验证码 | 仅当配置了 `EMAIL_API_BASE` 才发往账号服务 | 按账号服务政策；当前未配置则不产生 |
| AI apiKey | 本机 `moxsh_ai_config`（AndroidKeyStore 加密） | 在设置里清除；卸载 App 时密钥销毁、密文随之失效 |
| 对话内容 | **只发给你选的 AI 服务商**，本机不落盘对话历史 | 由该服务商按其条款留存；我们无副本、无法代你删除 |
| 插件缓存 / 已安装包 / 启停标记 | `cacheDir`、`dataDir/store/installed/`、`moxsh_store` | 商店内卸载插件即删目录与标记；清 App 数据/卸载即清空 |
| 命令历史队列 | `filesDir/cloud_sync_queue.jsonl`（≤500 条） | 清 App 数据/卸载即删；当前不上传 |
| 引擎二进制 | 本机 | 清 App 数据/卸载即删 |

**共享方**（各自独立按其条款处理，见第八节）：GitHub（登录与资料）、Gitee（引擎下载镜像、国内只读镜像）、你自选的 AI 服务商（DeepSeek / 智谱 / 通义 / Kimi / 自定义网关 / 本地模型）、账号服务（仅邮箱验证码路径，且当前未配置）、Cloudflare（若账号服务部署于其 Workers）。

我们**不出售、不出租、不用于广告、不向数据经纪人提供**任何数据。App 直连第三方，不把数据回传 `codecloud-dev` 的自有服务器。

---

## 五、你能怎么查看、重置、删除

- **退出 GitHub 登录**：设置页退出 → 清 `mox_secure_session`；再到 `github.com/settings/connections/applications/<client_id>` 撤销授权。
- **清除邮箱账号**：清 `mox_secure_email`（或在 App 内解绑）；本地密码 hash 与盐一并删除。
- **清除 AI Key / 改服务商**：AI 设置里清空 Key 或切换 `Local`；卸载 App 时 AndroidKeyStore 密钥销毁，任何残留密文都不可解密。
- **删除命令历史**：清 App 数据或卸载；也可直接删除 `cloud_sync_queue.jsonl`（需能访问应用私有目录）。
- **卸载插件 / 清缓存**：商店「已安装」里卸载；清 App 缓存。
- **彻底删除**：Android「设置 → 应用 → 清除数据」或卸载 App；`allowBackup=false`，本机数据不进云备份。
- **对话内容**：存在你选的 AI 服务商那里，请按其条款申请删除；我们无副本。
- **阻止联网**：App 需要 `INTERNET` 才能登录/下载/用云端 AI；若只想离线用终端，可不登录、不配置 AI Key、用本地插件源。当前版本**未提供细粒度联网开关**。

---

## 六、儿童

不面向未满 14 周岁的儿童；当前版本**未实现年龄核验**，不有意收集儿童信息。监护人发现儿童使用了登录、邮箱或 AI 功能，请通过 GitHub Issues 联系我们；相关数据都在本机或第三方服务商处，我们可指导清除方式。

## 七、跨境

数据主要留在本机。当你主动登录 GitHub、从 Gitee 下载引擎、或使用境外/境内 AI 服务商时，相应数据会发往该服务商，可能跨境，由其按各自条款在其所在地处理。Cloudflare（账号服务部署时）亦可能在全球边缘处理邮箱验证码请求。我们未自建跨境通道，不对第三方留存地作承诺。

## 八、第三方服务条款入口（不为其背书）

| 服务 | 条款 / 隐私政策入口 |
| --- | --- |
| GitHub | `https://docs.github.com/en/site-policy/github-terms/github-terms-of-service` · `https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement` |
| Gitee | `https://gitee.com/`（以其站内公布的条款为准） |
| Cloudflare（账号服务运行时） | `https://www.cloudflare.com/privacypolicy/` |
| DeepSeek / 智谱 / 通义千问 / Kimi / 自定义网关 | 见各服务商官网公布的条款；你填谁的 Key，就受谁的条款约束 |
| 爱发电（若涉及赞助/兑换，见 proot / 官网政策） | `https://afdian.com/` |

以各站最新公布条款为准。我们不代替其解释、不担保可用性、不对其行为负责；使用第三方服务即同时受其约束。

## 九、变更通知与联系方式

- 变更直接更新本仓库 `PRIVACY.md`，留痕于 Git 提交与 CHANGELOG；App 内**不弹窗、不主动推送**（我们无推送通道、不收集联系方式），重大变更在仓库公告。
- **唯一联系方式**：在对应仓库开 **GitHub Issues**（`https://github.com/codecloud-dev/<repo>/issues`），或联系 GitHub 账号 **`codecloud-dev`**。
- 不提供、不编造客服邮箱、电话、通信地址、公司名、注册号。请勿在公开 Issue 里贴 token、apiKey、密码或订单凭据。

---
---

# English

**Repository**: `codecloud-dev/moxsh-terminal` (moxsh — an Android "liquid glass" terminal app)
**Effective date**: 2026-11-15
**Controller**: `codecloud-dev` — a personal GitHub account run by an individual developer, **not a company or legal entity**; no company name, registration number, registered address, or support phone.

> Chinese first, English after, in one file; the Chinese text controls in case of conflict.
> Written against the **actual code** in `moxsh/app/src/main/java/...`, `moxsh/plugins/...` and `AuthConfig.kt`. Anything not implemented or not enabled is explicitly marked "not implemented / not enabled / no network request in the current version".
> Written in the spirit of GDPR / PIPL minimisation, purpose limitation and transparency; no statutory text is quoted or copied.

## 1. Online features and offline features

| Feature | Current status | Network counterpart |
| --- | --- | --- |
| GitHub login (Device Flow) | ✅ Implemented | `github.com/login/device/code`, `github.com/login/oauth/access_token`, `api.github.com/user`, `api.github.com/user/emails` |
| Email-code binding (advanced path) | ⚠️ **Only sends requests if `EMAIL_API_BASE` is configured**; otherwise the entry is unavailable and no request is made | Self-hosted account service `/auth/email/send-code`, `/auth/email/verify` |
| AI assistant | ✅ Implemented, **sent to the provider you choose in settings** | DeepSeek / Zhipu / Qwen / Kimi / OpenAI-compatible custom / local `127.0.0.1` |
| Plugin store catalogue and packages | ⚠️ Cloud base is a placeholder (`https://store.moxsh.local/v1`), **not deployed**; fetch failure silently falls back to the built-in catalogue | Configurable `catalog.json` and `pkg/<id>.mox`; canonical repo `codecloud-dev/moxsh-plugins` (GitHub / Gitee mirror) |
| PRoot engine binary download | ✅ Implemented | Gitee `codecloud-dev/moxsh-proot` Releases |
| Command cloud sync | ❌ **Not enabled in the current version**: `AuthConfig.ENABLE_CLOUD_SYNC = false`; `SyncClient.syncNow()` returns "cloud sync not enabled" immediately and **makes no network request** | None |
| Redeem-code verification / Gitee login | ❌ **Not implemented in the app in the current version** (these live in the moxsh-proot engine and the account service / website) | None |

> The README lists "command cloud sync" as a product direction, but the source switch is off. This policy follows **the code**: in the current version command history is queued locally only and **is not uploaded**.

## 2. Field-by-field data inventory

### 2.1 GitHub login (`AuthConfig.kt` / `GitHubLogin.kt`)

- Uses **OAuth Device Flow** (public client; `CLIENT_ID` injected at build time; the APK contains **no client_secret and no private key**).
- Requested scope: `read:user user:email read:org` (`AuthConfig.SCOPES`).
- The app connects **directly to GitHub**, not through any backend of `codecloud-dev`.
- Profile fetched and displayed: `login`, numeric `id`, `name`, `avatar_url`, `email` (only the `primary && verified` one).
- **Local storage**: `SessionStore` uses `EncryptedSharedPreferences` (file `mox_secure_session`, Android Keystore master key AES-256-GCM, keys/values AES256-SIV / AES256-GCM) for `github_session_token` and an optional `github_session_refresh`. **The token stays on your device** and is not uploaded to us.
- Revocation: `GitHubLogin.revokeManagementUrl()` → `github.com/settings/connections/applications/<client_id>`.

### 2.2 Email sign-up / binding (`EmailBindingStore.kt` / `docs/email-auth.md`)

Three paths; **the main path is fully offline**:

| Path | Online? | Stored |
| --- | --- | --- |
| Email + password (main) | **No** | Email, source `PASSWORD`, bound time; password stored as **PBKDF2-HMAC-SHA256 (random 16-byte salt, 10,000 iterations, 256-bit)** hash + salt — **plaintext never written to disk** |
| GitHub verified email (optional shortcut) | Reuses §2.1 GitHub API | Email, source `GITHUB`; passwordless |
| Code channel (bind an email different from GitHub's) | Only when `EMAIL_API_BASE` is configured | `POST /auth/email/send-code` `{email}`, `POST /auth/email/verify` `{email, code}`, with `Authorization: Bearer <GitHub token>`; the backend uses the token-derived GitHub login as the ownership subject to prevent bulk forgery |

**Local storage**: `EmailBindingStore` uses `mox_secure_email` (also EncryptedSharedPreferences) for `bound_email`, `bound_at`, `source` (GITHUB/PASSWORD/CODE), `pw_hash`, `pw_salt`. The email account is **independent of the GitHub session**; signing out of GitHub does not clear the email.

> The client **never holds** an SMTP authorisation code or client-specific password; that credential exists only in a server-side environment variable.

### 2.3 AI assistant (`plugins/plugin-ai/.../AiClient.kt`)

- **You choose the provider**: `OpenAICompatible` (your own baseUrl/Key/model), `DeepSeek`, `Zhipu` (`glm-4-flash`), `Qwen`, `Moonshot` (Kimi), `Local` (`http://127.0.0.1:8080/v1`, M7 plan, on-device llama.cpp).
- For each conversation the app sends the **full conversation history** (`role: system/user/assistant/tool` `content`, tool calls and tool results), `model`, `temperature`, `tools` as an OpenAI-compatible `POST /chat/completions` (`stream:true`, SSE) **directly to the endpoint you chose**.
- Headers: `Authorization: Bearer <your apiKey>`, `User-Agent: moxsh-ai/1.0`. **AI requests do not pass through any `codecloud-dev` server**; the conversation is handled by your chosen provider under its terms.
- **Local storage**: `moxsh_ai_config` SharedPreferences stores `provider` / `base_url` / `model` / `temperature` in plaintext; `apiKey` is encrypted with an **AndroidKeyStore AES-256-GCM key** into `api_key_enc` (random GCM IV + Base64 ciphertext). Key material never leaves secure hardware and **is destroyed on uninstall**; on device change or corruption decryption yields an empty string.
- **Not implemented**: uploading conversation content to our servers, cloud storage of chat history, model-usage telemetry, usage-statistics reporting.

> Note: AI tools (AgentTools) may put terminal commands, output, or file contents into tool-result messages; those go to the provider you chose. Do not enable AI, or do not let it read sensitive data, in sensitive contexts.

### 2.4 Plugin store (`plugins/plugin-store/.../StoreRepository.kt`)

- `CloudStoreApi.catalog()`: `GET <base>/catalog.json`, parsing `entries[]` with `id`, `name`, `version`, `author`, `description`, `type`, `minAppVersion`, `permissions`, `price`, `purchased`, `sizeBytes`, `minVersion`, `revokedVersions`. The last two are **trusted-catalogue declarations for the plugin version security policy** (minimum allowed version, list of revoked vulnerable versions), read by the App at install time to block rollbacks (see §6 of `TERMS.md`). **These are plugin metadata, not your personal data.**
- `CloudStoreApi.download()`: `GET <base>/pkg/<id>.mox`, streamed into `cacheDir/store-<id>.mox`.
- `DEFAULT_BASE_URL = "https://store.moxsh.local/v1"` is a **placeholder domain, not deployed**; `StoreRepository.loadCatalog()` silently `runCatching`-skips any failing source and falls back to `BuiltinCatalog` (4 built-in examples).
- `LocalStoreApi` scans `/sdcard/Download/moxsh-store/` for `.mox` packages (offline install).
- Installation lands in `dataDir/store/installed/<id>/` (app-private, no storage permission); enable/disable flags live in SharedPreferences `moxsh_store`; before install, `MoxPackage.verify()` performs **HMAC-SHA256 signature verification** with a secret injected at build time (`MOX_STORE_SECRET`; a dev placeholder otherwise).
- **Not implemented**: accounts, purchase flow, payment verification, download reporting, install telemetry (the `price` / `purchased` fields are explicitly "D17 reserved, no payment flow").

### 2.5 Local command-history queue (`cloud/CommandHistoryStore.kt`)

- Extracts command lines from raw PTY input, **captured verbatim (including arguments)**, into `filesDir/cloud_sync_queue.jsonl`, one JSON object per line: `{"k":<timestamp-ms>,"v":"<command line>"}`. Empty lines, ESC control-sequence fragments, and lines starting with `exit` are skipped; single-line cap 2000 chars; queue cap **500 entries** (oldest dropped).
- The comment states it is fed by `ExecutionEngine.inputRecorder` only when "logged in + user enabled cloud sync"; but `ENABLE_CLOUD_SYNC = false` and `SyncClient.syncNow()` returns an error immediately, so **the current version uploads no commands**.
- Privacy note in code: command lines may contain sensitive parameters (e.g. passwords); the cloud would be readable only by the user's own credential over https — this is a **design note for a future enabled sync, not currently in effect**.

### 2.6 PRoot engine download (`shared/ProotManager.kt`)

When a binary is missing, the app pulls it from Gitee `codecloud-dev/moxsh-proot` `releases/download/proot-latest` and marks it executable. This is a **pure file download** carrying no account, device identifier, or user data; request metadata is logged by Gitee under its own policy.

### 2.7 Permissions (`AndroidManifest.xml`)

Requested: `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (session keepalive), `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW` (floating window). `android:allowBackup="false"`.

**Not requested or used**: location, contacts, camera, microphone, SMS, call logs, external storage, phone, advertising ID, biometrics, background upload.

## 3. What we do not collect (current version)

- **Not uploaded**: command history, terminal output, container file contents, clipboard, contacts, location, photo library, device fingerprint, installed-app list, usage duration.
- **Not collected**: real name, government ID, phone number, precise location, biometrics, advertising identifiers.
- **Not held**: SMTP authorisation codes, GitHub client_secret, any server-side private key.
- **Not embedded**: ad SDKs, analytics/tracking SDKs, automatic crash reporting, telemetry, cross-site tracking, automated decision-making, profiling.
- **Not implemented**: cloud sync (switch off), Gitee login (not in the app; only the website/engine have it), redeem-code verification (in the proot engine), an account system (the in-app email account is purely local), age verification, real-name verification.

## 4. Retention, location, and sharing

| Data | Where | Retention / deletion |
| --- | --- | --- |
| GitHub token / refresh token | On-device EncryptedSharedPreferences `mox_secure_session` | Sign out via `SessionStore.clear()`; uninstall removes it |
| Bound email, password hash + salt | On-device `mox_secure_email` | `EmailBindingStore.clear()`; signing out of GitHub does **not** clear the email; uninstall removes it |
| Email verification code | Sent to the account service only when `EMAIL_API_BASE` is configured | Per the account service policy; not produced when unconfigured |
| AI apiKey | On-device `moxsh_ai_config` (AndroidKeyStore-encrypted) | Clear in settings; uninstall destroys the key so any leftover ciphertext is undecryptable |
| Conversation content | **Only sent to your chosen AI provider**; no on-device chat history | Retained by that provider under its terms; we hold no copy and cannot delete it for you |
| Plugin cache / installed packages / flags | `cacheDir`, `dataDir/store/installed/`, `moxsh_store` | Uninstalling a plugin removes its directory and flag; clearing app data / uninstall clears all |
| Command-history queue | `filesDir/cloud_sync_queue.jsonl` (≤500) | Clearing app data / uninstall removes it; not uploaded currently |
| Engine binary | On-device | Clearing app data / uninstall removes it |

**Recipients** (each acts under its own terms; see §8): GitHub (login and profile), Gitee (engine download mirror, mainland-China read-only mirror), the AI provider you selected (DeepSeek / Zhipu / Qwen / Kimi / custom gateway / local model), the account service (email-code path only, currently unconfigured), Cloudflare (where the account service runs on Workers).

We **do not sell, rent, use for advertising, or provide to data brokers** any data. The app talks to third parties directly and sends nothing back to a `codecloud-dev` server.

## 5. How to view, reset and delete

- **Sign out of GitHub**: settings → sign out clears `mox_secure_session`; also revoke the grant at `github.com/settings/connections/applications/<client_id>`.
- **Clear the email account**: clear `mox_secure_email` (or unbind in-app); the local password hash and salt go with it.
- **Clear the AI Key / change provider**: clear the Key in AI settings or switch to `Local`; uninstall destroys the AndroidKeyStore key.
- **Delete command history**: clear app data or uninstall; the `cloud_sync_queue.jsonl` file can also be removed directly if you can access app-private storage.
- **Uninstall plugins / clear cache**: uninstall from the store's "Installed" list; clear app cache.
- **Full deletion**: Android Settings → Apps → Clear data, or uninstall; `allowBackup=false` means local data is not included in cloud backups.
- **Conversation content**: it sits with your chosen AI provider — request deletion under its terms; we hold no copy.
- **Blocking network access**: the app needs `INTERNET` for login, downloads and cloud AI. To use the terminal fully offline, do not sign in, do not configure an AI Key, and use the local plugin source. **No fine-grained network toggle exists in the current version.**

## 6. Children

Not directed at children under 14. The current version **has no age verification** and does not knowingly collect children's data. If a guardian finds a child used login, email, or AI features, contact us via GitHub Issues; the relevant data is on-device or with a third-party provider, and we can advise on clearing it.

## 7. Cross-border transfers

Data stays mostly on-device. When you actively sign in to GitHub, download the engine from Gitee, or use a domestic/foreign AI provider, the corresponding data goes to that provider and may cross borders, handled under its own terms and in its own location. Cloudflare (where the account service runs) may also process email-code requests at its global edge. We have built no bespoke cross-border channel and make no promise about third-party data locations.

## 8. Third-party terms (we do not endorse them)

| Service | Terms / privacy policy |
| --- | --- |
| GitHub | `https://docs.github.com/en/site-policy/github-terms/github-terms-of-service` · `https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement` |
| Gitee | `https://gitee.com/` (see its published terms) |
| Cloudflare (account service runtime) | `https://www.cloudflare.com/privacypolicy/` |
| DeepSeek / Zhipu / Qwen / Kimi / custom gateway | See each provider's published terms; whoever's Key you enter, their terms bind you |
| Afdian (for sponsorship/redemption, see the proot / website policies) | `https://afdian.com/` |

The latest published terms govern. We do not interpret them, guarantee availability, or take responsibility for them; using a third-party service also binds you to its terms.

## 9. Changes and contact

- Changes update this repository's `PRIVACY.md` directly and appear in Git history / CHANGELOG. The app **does not pop up or push notifications** (we have no push channel and collect no contact details); significant changes are announced in the repository.
- **Only contact channels**: open a **GitHub Issue** at `https://github.com/codecloud-dev/<repo>/issues`, or reach the GitHub account **`codecloud-dev`**.
- No support email, phone, postal address, company name, or registration number is provided or invented here. Please do not post tokens, apiKeys, passwords, or order credentials in a public Issue.

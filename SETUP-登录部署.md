# 🔐 mox 登录功能：部署与手动步骤

<p align="center">
  <img src="https://img.shields.io/badge/后端-Cloudflare%20Workers%20%2B%20D1-F38020?logo=cloudflare&logoColor=white" alt="Cloudflare Workers + D1">
  <img src="https://img.shields.io/badge/登录-GitHub%20OAuth-181717?logo=github&logoColor=white" alt="GitHub OAuth">
  <img src="https://img.shields.io/badge/回调-https%20专用-8a7bff" alt="https 回调">
  <img src="https://img.shields.io/badge/存储-EncryptedSharedPreferences-3DDC84?logo=android&logoColor=white" alt="加密存储">
</p>

> GitHub 登录已实现，但有三件**只能你本人操作**的事（命令行 token 权限不足），外加少量配置。按序做完即可上线。

📑 **步骤总览**

| # | 步骤 | 谁来做 | 预计耗时 |
|---|---|---|---|
| 0 | 删掉废弃私有仓库 `ESP32-` | **你**（网页） | 2 分钟 |
| 1 | 部署后端 `mox-id` | **你**（Cloudflare 账号） | 10 分钟 |
| 2 | 创建 GitHub OAuth App | **你**（网页） | 3 分钟 |
| 3 | App 端填后端地址 | 我（命令行可代办） | 1 分钟 |
| 4 | 端到端验证 | 一起 | 5 分钟 |

---

## 0️⃣ 删掉废弃私有仓库 ESP32-（网页操作）

命令行 token 没有 `delete_repo` 权限，删不了。请手动：

1. 打开 https://github.com/codecloud-dev/ESP32-/settings
2. 最底部 Danger Zone → **Delete this repository**
3. 输入 `ESP32-` 确认

## 1️⃣ 部署后端 mox-id（Cloudflare，需你的账号）

代码在独立仓库 `mox-id`，零外部依赖。详细见该仓库 README，摘要：

```bash
cd mox-id
npm install
wrangler login
wrangler d1 create mox-id                 # 把输出的 id 填进 wrangler.toml 的 database_id
wrangler d1 execute mox-id --file=./migrations/0001_init.sql --remote   # 用户/设备/会话表
wrangler d1 execute mox-id --file=./migrations/0002_sync.sql --remote   # 命令云同步表（sync_objects）
wrangler secret put CLIENT_ID             # 步骤 2 建 OAuth App 后拿
wrangler secret put CLIENT_SECRET         # 同上
wrangler secret put JWT_SECRET            # openssl rand -hex 32
wrangler secret put PUBLIC_BASE           # 部署后的根地址，如 https://mox-id.<你>.workers.dev
wrangler deploy
```

> 💡 **记下部署后的根地址**，下面步骤要用（下称 `PUBLIC_BASE`）。

## 2️⃣ 手动创建 GitHub OAuth App（网页操作，命令行无权限）

1. GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App
2. Application name：`moxsh`（随意）
3. Homepage URL：`https://<PUBLIC_BASE>`
4. **Authorization callback URL（必须是 https）：`https://<PUBLIC_BASE>/auth/github/callback`**
5. 创建后得到 **Client ID** 与 **Client Secret**
6. 把这两个值 `wrangler secret put CLIENT_ID` / `wrangler secret put CLIENT_SECRET` 进 mox-id

> ⚠️ **GitHub 不接受 `moxsh://` 自定义 scheme 作为回调**，所以必须经后端转发——这正是轻量后端存在的理由之一。

## 3️⃣ App 端填后端地址

编辑 `app/src/main/java/com/moxsh/auth/AuthConfig.kt`：

```kotlin
const val BACKEND_BASE: String = "https://<你的 PUBLIC_BASE>"
```

改完重新构建 APK。官网「登录」按钮已指向 `<PUBLIC_BASE>/auth/github/start`，部署后即生效。

## 4️⃣ 端到端验证

1. 打开官网点「登录」→ 浏览器跳到 GitHub 授权 → 授权后自动回跳 App（深链 `moxsh://auth.callback?token=...`）
2. App 内 `LoginActivity` 解析 token 存入 EncryptedSharedPreferences
3. 调用 `GET <PUBLIC_BASE>/me`（带 `Authorization: Bearer <token>`）应返回你的 GitHub 用户

---

## 🗂️ 代码位置

| 端 | 文件 |
|---|---|
| 后端 | `mox-id/src/index.ts`、`auth.ts`、`db.ts`、`migrations/0001_init.sql` |
| App 配置 | `app/.../auth/AuthConfig.kt` |
| App 登录逻辑 | `app/.../auth/GitHubLogin.kt`、`SessionStore.kt` |
| App 登录页 | `app/.../auth/LoginActivity.kt`（已注册深链 `moxsh://auth.callback`） |
| 清单 | `app/src/main/AndroidManifest.xml`（新增 LoginActivity + 深链 intent-filter） |
| 依赖 | `app/build.gradle.kts`（新增 `androidx.security:security-crypto:1.1.0`） |

> 🔮 **后续计划**：在 App 设置页加「登录 / 退出」入口，调用 `GitHubLogin.start(context)` 即可（目前通过官网按钮或深链触发）。

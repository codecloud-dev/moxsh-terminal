# mox 登录功能：部署与手动步骤

GitHub 登录已实现，但有三件**只能你本人操作**的事（命令行 token 权限不足），外加少量配置。按序做完即可上线。

## 0. 删掉废弃私有仓库 ESP32-（网页操作）

命令行 token 没有 `delete_repo` 权限，删不了。请手动：

1. 打开 https://github.com/zyr15555086235/ESP32-/settings
2. 最底部 Danger Zone → **Delete this repository**
3. 输入 `ESP32-` 确认

## 1. 部署后端 mox-id（Cloudflare，需你的账号）

代码在独立仓库 `mox-id`，零外部依赖。详细见该仓库 README，摘要：

```bash
cd mox-id
npm install
wrangler login
wrangler d1 create mox-id                 # 把输出的 id 填进 wrangler.toml 的 database_id
wrangler d1 execute mox-id --file=./migrations/0001_init.sql --remote
wrangler secret put CLIENT_ID             # 步骤 2 建 OAuth App 后拿
wrangler secret put CLIENT_SECRET         # 同上
wrangler secret put JWT_SECRET            # openssl rand -hex 32
wrangler secret put PUBLIC_BASE           # 部署后的根地址，如 https://mox-id.<你>.workers.dev
wrangler deploy
```

记下部署后的根地址，下面步骤要用（下称 `PUBLIC_BASE`）。

## 2. 手动创建 GitHub OAuth App（网页操作，命令行无权限）

1. GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App
2. Application name：`moxsh`（随意）
3. Homepage URL：`https://<PUBLIC_BASE>`
4. **Authorization callback URL（必须是 https）：`https://<PUBLIC_BASE>/auth/github/callback`**
   - ⚠️ GitHub 不接受 `moxsh://` 自定义 scheme 作为回调，所以必须经后端转发
5. 创建后得到 **Client ID** 与 **Client Secret**
6. 把这两个值 `wrangler secret put CLIENT_ID` / `wrangler secret put CLIENT_SECRET` 进 mox-id

## 3. App 端填后端地址

编辑 `app/src/main/java/com/moxsh/auth/AuthConfig.kt`：

```kotlin
const val BACKEND_BASE: String = "https://<你的 PUBLIC_BASE>"
```

改完重新构建 APK。官网「登录」按钮已指向 `<PUBLIC_BASE>/auth/github/start`，部署后即生效。

## 4. 端到端验证

1. 打开官网点「登录」→ 浏览器跳到 GitHub 授权 → 授权后自动回跳 App（深链 `moxsh://auth.callback?token=...`）
2. App 内 `LoginActivity` 解析 token 存入 EncryptedSharedPreferences
3. 调用 `GET <PUBLIC_BASE>/me`（带 `Authorization: Bearer <token>`）应返回你的 GitHub 用户

## 代码位置

| 端 | 文件 |
|---|---|
| 后端 | `mox-id/src/index.ts`、`auth.ts`、`db.ts`、`migrations/0001_init.sql` |
| App 配置 | `app/.../auth/AuthConfig.kt` |
| App 登录逻辑 | `app/.../auth/GitHubLogin.kt`、`SessionStore.kt` |
| App 登录页 | `app/.../auth/LoginActivity.kt`（已注册深链 `moxsh://auth.callback`） |
| 清单 | `app/src/main/AndroidManifest.xml`（新增 LoginActivity + 深链 intent-filter） |
| 依赖 | `app/build.gradle.kts`（新增 `androidx.security:security-crypto:1.1.0`） |

> 后续可在 App 设置页加一个「登录 / 退出」入口，调用 `GitHubLogin.start(context)` 即可（目前通过官网按钮或深链触发）。

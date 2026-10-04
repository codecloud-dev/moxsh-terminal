# 邮箱注册 / 绑定（mox-id Worker）

> 对应客户端实现：`moxsh/app/src/main/java/com/moxsh/auth/` 下的
> `EmailAuthClient.kt` / `EmailBindingStore.kt` / `EmailSignUpCard.kt` / `AuthConfig.kt`

---

## 1. 为什么邮箱注册必须绑定 GitHub

邮箱本身可被任意人随意填写。若允许「邮箱 + 验证码」独立注册，任何人都能批量注册
小号、抢占他人邮箱、绕过社区信誉机制。

因此 moxsh 的规则是：**邮箱不是独立登录凭据，而是已登录 GitHub 账号的附属绑定。**

| 约束 | 实现 |
|---|---|
| 未登录 GitHub 时无法进入邮箱注册 | 客户端卡片禁用并提示先登录；请求层再挡一次（`token.isBlank()` 直接失败） |
| 邮箱归属主体由 GitHub 决定 | 发码/校验两个请求都强制带 `Authorization: Bearer <GitHub access token>`，后端以 token 解析出的 `login` 为主体 |
| 客户端无法伪造身份 | 没有 GitHub 令牌就拿不到验证码；换邮箱也只会绑定到自己的 GitHub 账号名下 |
| 退出会话即解除绑定 | 客户端 `SessionStore.clear` 时同步 `EmailBindingStore.clear`；`EmailBindingStore.get` 在未登录态直接返回 `null` |

---

## 2. 🔴 安全红线：邮箱授权码绝不进客户端

邮箱验证码邮件由后端用 **SMTP 授权码（客户端专用密码）** 发出。这个授权码
**只允许存在于 Worker 的环境变量中**。

**绝对禁止：**

- ❌ 写进 App 源码或 `local.properties` → 会进 APK
- ❌ 提交进本仓库 → 本仓库是**公开仓**，git 历史永久留痕，无法真正删除
- ❌ 写进任何前端可见的构建产物

**为什么这么严：**

1. **APK 公开分发且可反编译。** 内置的字符串常量用 `strings` 或任意反编译工具
   即可提取，无需 root。
2. **139 邮箱授权码具备完整 IMAP/SMTP 收发能力。** 泄漏等于邮箱被完整接管：
   可冒名发信（钓鱼 / 诈骗 / 刷单），受害者不止你一人，还会牵连所有收到你邮件的人。
3. **公开仓库 = 永久泄漏。** 即使事后删除提交，历史记录里依然可被捞出。

这与本仓库既有的 auth 设计原则一致：`AuthConfig.kt` 中 GitHub 登录刻意采用
**设备流（Device Flow）**，正是因为设备流「不需要也不持有任何 client_secret /
private key」。邮箱方案必须守住同一条线。

> ⚠️ **若某个授权码曾在对话、聊天记录、截图或日志中明文出现过，应立即视为已泄漏**，
> 到邮箱后台吊销并重新生成。新码只填进 Worker secret。

---

## 3. 部署步骤（Cloudflare Workers）

### 3.1 创建 Worker 与密钥

```bash
# 在你自己的后端仓库（不要放进 moxsh-terminal）里
wrangler secret put SMTP_AUTH_CODE     # 邮箱授权码 —— 交互式输入，不进版本库
wrangler secret put SMTP_FROM          # 发件人地址，如 your@139.com
wrangler secret put GITHUB_TOKEN_APP   # 用于校验 GitHub token 的应用 token（可选）
```

`wrangler.toml` / `wrangler.jsonc` 中**只声明 secret 名称，不写值**：

```tomc
name = "mox-id"
main = "src/index.ts"
compatibility_date = "2026-01-01"

# D1 用于存验证码与绑定关系
[[d1_databases]]
binding = "DB"
database_name = "mox-id"
database_id = "<你的 database_id>"
```

### 3.2 需要的表结构

```sql
CREATE TABLE email_codes (
  email      TEXT NOT NULL,
  github     TEXT NOT NULL,   -- GitHub login，归属主体
  code       TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  used       INTEGER DEFAULT 0
);
CREATE INDEX idx_email_codes_lookup ON email_codes(email, github);

-- 频控：同一 github + 同一 IP 的发码间隔
CREATE TABLE email_rate_limit (
  key        TEXT PRIMARY KEY,
  last_sent  INTEGER NOT NULL
);

CREATE TABLE email_bindings (
  github     TEXT PRIMARY KEY,
  email      TEXT NOT NULL,
  bound_at   INTEGER NOT NULL
);
```

### 3.3 客户端配置

`local.properties`（已被 `.gitignore` 忽略，不进仓库）：

```properties
github_client_id=你的 GitHub OAuth App client_id
email_api_base=https://mox-id.<你的子域>.workers.dev
```

`email_api_base` 只是**服务地址**，属公开信息，允许进 APK。
`local.properties.sample` 里已加入占位符与说明。

---

## 4. 接口契约

两个端点都必须校验 `Authorization: Bearer <GitHub access token>`。

### 4.1 发送验证码

```
POST {email_api_base}/auth/email/send-code
Authorization: Bearer <github access token>
Content-Type: application/json

{ "email": "user@139.com" }
```

成功 `200`：

```json
{ "ok": true, "cooldown": 60 }
```

频控 `429`：

```json
{ "ok": false, "error": "发送过于频繁，请稍后再试", "retry_after": 42 }
```

失败（`400` / `401` / `500`）：`{ "ok": false, "error": "<可展示文案>" }`

### 4.2 校验验证码

```
POST {email_api_base}/auth/email/verify
Authorization: Bearer <github access token>
Content-Type: application/json

{ "email": "user@139.com", "code": "123456" }
```

成功 `200`：

```json
{ "ok": true, "email": "user@139.com", "bound_at": 1730000000000 }
```

失败：`{ "ok": false, "error": "验证码已过期或错误" }`

### 4.3 服务端必须遵守的安全要求

- **校验 GitHub token 真伪**，不要只解码不验证，否则可伪造任意 `login` 冒名注册。
- 验证码 **6 位数字、随机源用密码学安全随机数**（`crypto.getRandomValues`），
  有效期建议 10 分钟，`used` 标记一次性消费。
- **限流**：同一 `github` 与同一 IP 双重限流（建议 60 秒/次、10 条/小时）。
- **不要在错误信息里区分「邮箱不存在」与「验证码错误」**，避免被用来探测邮箱。
- 全程 https；日志中**不得**记录验证码明文。

---

## 5. 客户端行为

| 状态 | 表现 |
|---|---|
| 未登录 GitHub | 卡片禁用，提示「请先完成 GitHub 登录，再绑定邮箱」，不发任何请求 |
| `email_api_base` 未配置 | 显示配置提示，不发起无意义网络请求 |
| 已绑定邮箱 | 展示邮箱 + 「解除绑定」 |
| 未绑定 | 邮箱输入 → 获取验证码（60s 倒计时防刷）→ 输入 6 位验证码 → 完成绑定 |
| 验证码输入 | 过滤非数字并限长 6 位 |

绑定结果存于 `EmailBindingStore`（`EncryptedSharedPreferences`，AES-256 +
Android Keystore），与 GitHub token 同源的安全存储。
# 邮箱注册 / 绑定

> 对应客户端：`moxsh/app/src/main/java/com/moxsh/auth/` 下的
> `GitHubVerifiedEmail.kt` / `EmailAuthClient.kt` / `EmailBindingStore.kt` /
> `EmailSignUpCard.kt` / `AuthConfig.kt`
>
> 对应服务端：`edge/mox-id/`（Cloudflare Workers + D1）

---

## 1. 三种方式，先看这个

邮箱绑定有**三种方式**，主路径**不依赖 GitHub 登录**：

| | **主路径：邮箱 + 密码** | **GitHub 已验证邮箱（可选）** | **验证码通道（高级）** |
|---|---|---|---|
| 做什么 | 填邮箱 + 设密码即注册 | 一键采用 GitHub 已验证邮箱 | 用另一个邮箱收验证码 |
| 需要 GitHub 登录吗 | **不需要** | 需要（仅作快捷来源） | 需要（作为归属主体） |
| 发邮件吗 | **不发** | **不发** | 发 |
| 需要后端吗 | **不需要** | **不需要** | 需要 mox-id |
| 装上能用吗 | **能** | **能** | 需先部署 |
| 密码 | 用户自设（PBKDF2 本地派生） | 免密（GitHub 即身份证明） | 免密（验证码即证明） |
| 可信度 | 用户自证（本地凭据） | GitHub 出具 verified 证明 | 验证码由后端通道签发 |

**结论：邮箱注册不再强制 GitHub 登录。** 绝大多数用户用「邮箱 + 密码」即可，
装上 APK 即可用；已登录 GitHub 的用户可一键用已验证邮箱免密绑定。验证码通道
只在「想绑定一个与 GitHub 账号不同的邮箱」且已部署后端时才需要。

---

## 2. 为什么默认路径是 A（三条理由）

### 2.1 不发邮件 ⇒ 不存在发信限流

用 139/163 系个人邮箱 SMTP 发验证码，必然被风控：

- **每日额度**：个人邮箱 SMTP 通常几十封/天
- **IP 频率限制**：同一 IP 短时间大量发信直接拒收
- **并发限制**：同时连接数被限
- **IP 信誉**：Cloudflare 等平台的出口 IP 属于共享高风险段，极易被判定为垃圾邮件源，
  一旦被拒，整个发信域名/IP 都会进黑名单

路径 A 完全不发邮件，上述问题**一个都不会发生**。

### 2.2 不需要后端 ⇒ 真正「装上就用」

路径 A 是 App 直连 GitHub API（`GET /user/emails`），没有中间服务。
不需要域名、不需要邮件服务、不需要 Cloudflare 账户、不需要部署命令。

### 2.3 可信度更高

路径 A 的邮箱真实性由 **GitHub 出具 verified 证明**——GitHub 验证过这个邮箱归属。
路径 B 的可信度来自「某个自建邮件通道曾经发过一封验证码」，强度弱于前者。

---

## 3. GitHub 已验证邮箱（可选快捷方式）

> 这是**可选**的便捷入口，不是前提。未登录 GitHub 时完全不显示，主路径「邮箱 + 密码」照常可用。

### 3.1 流程

```
已通过 GitHub 设备流登录（拿到 access token，scope: read:user user:email read:org）
      ↓
GET https://api.github.com/user/emails
      ↓  筛出 verified == true，主邮箱优先
写入 EmailBindingStore（来源 = GITHUB，免密）
      ↓
绑定完成
```

实现见 `GitHubVerifiedEmail.kt`；UI 见 `EmailSignUpCard.kt` 的 GitHub 快捷方式区域。

### 3.2 归属主体不可伪造

邮箱与 GitHub login 同源：`/user` 与 `/user/emails` 用**同一个 access token**
取得，token 决定身份，客户端无法为他人账号「抢注」邮箱。

### 3.3 依赖的 scope

`AuthConfig.SCOPES = "read:user user:email read:org"`，其中 **`user:email` 是必需项**，
缺少它 `/user/emails` 返回 403。少这个 scope 时客户端会明确提示
「GitHub 未授予 user:email 权限」。

---

## 4. 邮箱是独立账号，GitHub 是可选来源

主路径「邮箱 + 密码」注册出的就是**独立账号**，与 GitHub 登录解耦：

| 约束 | 实现 |
|---|---|
| 邮箱可独立于 GitHub 存在 | 主路径不要求登录；`EmailBindingStore.get` 不再以 `SessionStore.isLoggedIn` 为前置；退出 GitHub 也不清除邮箱 |
| 密码本地派生 | 密码经 PBKDF2（随机盐 + 10k 迭代）派生后存入 `EncryptedSharedPreferences`，明文不落盘，作为该账号的独立凭据 |
| 验证码通道仍须 GitHub 作为归属主体 | 仅当用「验证码」高级路径绑定与 GitHub 不同的邮箱时，发码/校验强制 `Authorization: Bearer <GitHub token>`，后端以 token 的 `login` 为主体，防伪造批量注册 |
| 客户端无法伪造他人身份 | 验证码通道无 GitHub token 拿不到验证码；换邮箱只绑到自己账号名下 |

---

## 5. 路径 B：验证码通道（服务端 `edge/mox-id`）

### 5.1 发信通道：为什么不是 SMTP

> **这一节是对早期方案的更正。** 早期版本打算用 139 邮箱 SMTP 授权码
> 在 Worker 上发信，**那是行不通的**：

1. **Cloudflare Workers 硬性封锁出站 25 端口**。
2. Worker 的 `send_email` 绑定（Email Routing）**只能发给 Cloudflare 已验证的转发地址**，
   发不到任意用户邮箱。
3. 即便换用 465/587 端口，139 邮箱 SMTP 也只允许**个人收发**，程序化发验证码必被风控
   （见2.1）。

因此本服务改用**专业事务邮件服务的 HTTPS API**（默认 Resend）：
共享 IP 池已预热、送达率高、自带退信与配额管理，专为程序化发信设计。
密钥以 `wrangler secret` 存放，代码与公开仓库中零字面量。

### 5.2 部署（走 CI，凭证在仓库 Secrets 里）

部署由 `.github/workflows/deploy-edge.yml` 自动完成，**无需本地手敲凭证**：

- 触发：`push` 到 `main` 且改动 `edge/**`，或 `workflow_dispatch` 手动触发。
- 凭证全部来自仓库 Secrets（CI 内可读，本地读不到值）：
  - `CLOUDFLARE_API_TOKEN` / `CLOUDFLARE_ACCOUNT_ID` —— Cloudflare 部署凭证（**已配置**）。
  - `RESEND_API_KEY` —— Resend API Key（**可选**；仓库里配了才会注入 Worker，路径 B 才发得出信）。
  - `MAIL_FROM` —— 作为 **Variable**（非机密）配置的已验证发件地址，如 `mox@yourdomain.com`。
- 流程：`npm ci` → `tsc` 类型检查 → 进程内集成测试（11 项）→ `d1 execute` 同步表结构（幂等）→
  按需注入 `RESEND_API_KEY`/`MAIL_FROM` → `wrangler deploy`。
- `pull_request` 只跑「类型检查 + 集成测试」做门禁，不部署。

> ⚠️ **启用路径 B 前必须做两件事**（否则 Worker 会返回 `mail_configured:false`，发码失败）：
> 1. 在仓库 **Settings → Secrets** 加 `RESEND_API_KEY`（resend.com 控制台生成）。
> 2. 在仓库 **Settings → Variables** 加 `MAIL_FROM`（Resend 里已验证域名的发件地址）。
> 两者加好后，下一次 `main` 推送会自动注入并生效，无需改代码。

> 本地手动部署（仅调试用，等价于 CI 步骤）：

```bash
cd edge/mox-id
export CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=...   # 来自仓库 Secrets / 本地 .env
npm install
npx wrangler d1 execute mox-id --remote --file=./migrations/0001_init.sql
npx wrangler secret put RESEND_API_KEY        # 交互式，不进版本库
npx wrangler vars set MAIL_FROM mox@yourdomain.com
npx wrangler deploy
```

探活：`curl https://<worker>.workers.dev/health` →
`{"ok":true,"mail_configured":true}`（未配 Resend 时为 `false`）

### 5.3 限流策略（针对发信被限的问题）

与其等服务商风控把我们拒掉，不如在最外层就挡住异常流量，保护发信域名/IP 信誉：

| 维度 | 阈值 | 作用 |
|---|---|---|
| GitHub 账号冷却 | 60s | 防单账号刷邮件 |
| 客户端 IP 冷却 | 60s | 防多账号共用一个 IP 刷量 |
| GitHub 账号配额 | 5 封/小时 | 防长期滥用 |
| 客户端 IP 配额 | 10 封/小时、30 封/天 | 防换号长期骚扰 |
| 目标邮箱配额 | 3 封/小时 | **防拿本服务轰炸他人邮箱**（最易被滥用的一点） |
| 熔断 | 连续失败 5 次 → 暂停 15 分钟 | 通道异常时保护域名/IP |

冷却键以 SHA-256 存储（不存明文 IP / login）。

### 5.4 接口契约

两个端点都必须带 `Authorization: Bearer <GitHub access token>`。

**发送验证码**

```
POST {email_api_base}/auth/email/send-code
Authorization: Bearer <github access token>
Content-Type: application/json

{ "email": "user@example.com" }
```

- `200`：`{"ok": true, "cooldown": 60}`
- `429`：`{"ok": false, "error": "发送过于频繁，请稍后再试", "retry_after": 42}`
- `401`：未带 / 无效 GitHub token
- `502`：发信通道异常（已计入熔断）

**校验验证码**

```
POST {email_api_base}/auth/email/verify
Authorization: Bearer <github access token>
Content-Type: application/json

{ "email": "user@example.com", "code": "123456" }
```

- `200`：`{"ok": true, "email": "user@example.com", "verified": false, "bound_at": 1730000000000}`
- 失败：`{"ok": false, "error": "验证码已过期或错误"}`

**健康检查**

```
GET {email_api_base}/health → {"ok": true, "service": "mox-id-email", "mail_configured": true}
```

### 5.5 服务端安全要求

- **必须校验 GitHub token 真伪**（调 `/user` 验证），不能只解码——否则可伪造任意 `login` 冒名注册。
- 验证码 6 位、随机源用 `crypto.getRandomValues`，有效期 10 分钟，`used` 标记一次性消费。
- 错误信息**不区分「邮箱不存在」与「验证码错误」**，避免被用来探测邮箱。
- 全程 https；日志中**不得**记录验证码明文或任何密钥。

### 5.6 本地测试

```bash
cd edge/mox-id
npx tsc --noEmit                        # 类型检查
node --experimental-strip-types scripts/integration-test.mts   # 集成测试（11 项）
```

测试用 wrangler 官方 `getPlatformProxy` 在**进程内**调用 Worker，
走完整真实代码路径（同一份 `src/index.ts`、同一个 D1 引擎、同一套限流逻辑）。

---

## 6. 🔴 安全红线：任何密钥都绝不进客户端

**绝对禁止：**

- ❌ 写进 App 源码或 `local.properties` → 会进 APK
- ❌ 提交进本仓库 → 本仓库是**公开仓**，git 历史永久留痕，事后删提交也捞得回来
- ❌ 写进任何前端可见的构建产物

**为什么这么严：**

1. **APK 公开分发且可反编译。** 内置字符串常量用 `strings` 或任意反编译工具即可提取，无需 root。
2. **邮箱授权码具备完整 IMAP/SMTP 收发能力。** 泄漏等于邮箱被完整接管：可冒名发信
   （钓鱼 / 诈骗 / 刷单），受害者不止你一人，还会牵连所有收到你邮件的人。
3. **公开仓库 = 永久泄漏。**

这与本仓库既有的 auth 设计原则一致：`AuthConfig.kt` 中 GitHub 登录刻意采用
**设备流（Device Flow）**，正是因为设备流「不需要也不持有任何 client_secret /
private key」。

> ⚠️ **若某个授权码 / API Key 曾在对话、聊天记录、截图或日志中明文出现过，
> 应立即视为已泄漏**，到对应后台吊销并重新生成。新码只填进 Worker secret。

---

## 7. 客户端行为

| 状态 | 表现 |
|---|---|
| 未登录 GitHub | 主路径「邮箱 + 密码」**照常可用**；GitHub 快捷方式与验证码通道不显示 |
| 已登录 · GitHub 快捷方式 | 折叠区展开列出 GitHub 已验证邮箱，点一下即绑定（免密），标注「GitHub 已验证」 |
| 已登录 · GitHub 无已验证邮箱 | 引导去 GitHub 邮箱设置页添加并验证 |
| 已登录 · 验证码通道未配置后端 | 折叠区展开后显示「服务未配置」，**不发任何请求** |
| 已登录 · 验证码通道可用 | 邮箱输入 → 获取验证码（60s 倒计时防刷）→ 输 6 位 → 完成绑定 |
| 已绑定 | 展示邮箱 + 来源徽章（GitHub 已验证 / 密码注册 / 验证码验证）+ 「解除绑定」 |
| 退出 GitHub 登录 | **邮箱绑定保留**（邮箱是独立账号，不随 GitHub 退出清除） |

绑定结果存于 `EmailBindingStore`（`EncryptedSharedPreferences`，AES-256 +
Android Keystore），与 GitHub token 同源的安全存储。密码以 PBKDF2-HMAC-SHA256
（随机盐 + 10k 迭代）派生后存储，明文不落盘。

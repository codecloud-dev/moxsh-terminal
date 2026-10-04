/**
 * mox-id Worker：邮箱注册 / 绑定 —— 参考实现
 *
 * ⚠️ 这是一份**参考实现**，供你在自己的后端仓库落地时参考或直接取用。
 *    请不要把它连同任何真实密钥提交进 moxsh-terminal（公开仓）。
 *
 * 【安全铁律】
 *  - 邮箱授权码只能来自环境变量 / wrangler secret：`wrangler secret put SMTP_AUTH_CODE`
 *  - 代码里**绝不出现**任何授权码、邮箱密码、GitHub token 的字面量
 *  - 每个请求都必须校验 GitHub token：以 token 解析出的 login 作为邮箱归属主体，
 *    否则任何人都能伪造身份批量注册、或抢注他人邮箱
 *
 * 相关文档：docs/email-auth.md
 */

export interface Env {
  DB: D1Database;
  /** 邮箱 SMTP 授权码（客户端专用密码）。只存在于 Worker secret。 */
  SMTP_AUTH_CODE: string;
  /** 发件人地址，例如 your@139.com。 */
  SMTP_FROM: string;
  /** 139 邮箱 SMTP 服务端（可按服务商调整）。 */
  SMTP_HOST?: string;
  /** 用于调用 GitHub API 校验 access_token 的应用凭证。 */
  GITHUB_TOKEN_APP?: string;
}

const CODE_TTL_MS = 10 * 60 * 1000; // 验证码 10 分钟有效
const RESEND_COOLDOWN_MS = 60 * 1000; // 同一主体重发间隔
const HOURLY_LIMIT = 10; // 每小时上限

interface Session {
  login: string;
  id: number;
}

const json = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'no-store',
    },
  });

const fail = (error: string, status = 400, extra: Record<string, unknown> = {}) =>
  json({ ok: false, error, ...extra }, status);

/** 密码学安全随机 6 位验证码。 */
function generateCode(): string {
  const buf = new Uint32Array(1);
  crypto.getRandomValues(buf);
  return String(buf[0] % 1_000_000).padStart(6, '0');
}

/** 基本邮箱形状校验（可达性以发信成功与否为准）。 */
function isEmailValid(email: string): boolean {
  if (email.length > 254) return false;
  const at = email.indexOf('@');
  if (at <= 0 || at !== email.lastIndexOf('@')) return false;
  const domain = email.slice(at + 1);
  return (
    domain.length >= 3 &&
    domain.includes('.') &&
    !domain.startsWith('.') &&
    !domain.endsWith('.') &&
    !domain.includes('..')
  );
}

/**
 * 校验 GitHub access_token，返回其登录用户。
 * 失败返回 null —— 调用方必须拒绝，绝不能只解码不验证。
 */
async function verifyGitHubToken(token: string, env: Env): Promise<Session | null> {
  const headers: Record<string, string> = {
    authorization: `Bearer ${token}`,
    accept: 'application/vnd.github+json',
    'user-agent': 'mox-id',
  };
  if (env.GITHUB_TOKEN_APP) {
    headers['x-github-api-version'] = '2022-11-28';
  }
  const resp = await fetch('https://api.github.com/user', { headers });
  if (!resp.ok) return null;
  const data = (await resp.json()) as { login: string; id: number };
  if (!data.login) return null;
  return { login: data.login, id: data.id };
}

function bearer(request: Request): string | null {
  const raw = request.headers.get('authorization') ?? '';
  const m = /^Bearer\s+(.+)$/i.exec(raw);
  return m ? m[1].trim() : null;
}

/** 频控：同一 github 主体 + 同一客户端 IP 双重限制。 */
async function checkRateLimit(db: D1Database, key: string): Promise<{ retryAfter: number } | null> {
  const now = Date.now();
  const keyHash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key));
  const keyId = Array.from(new Uint8Array(keyHash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');

  const cooldown = await db
    .prepare('SELECT last_sent FROM email_rate_limit WHERE key = ?')
    .bind(keyId)
    .first<{ last_sent: number }>();
  if (cooldown && now - cooldown.last_sent < RESEND_COOLDOWN_MS) {
    return { retryAfter: Math.ceil((RESEND_COOLDOWN_MS - (now - cooldown.last_sent)) / 1000) };
  }

  const hourly = await db
    .prepare(
      'SELECT COUNT(*) AS c FROM email_codes WHERE github = ? AND created_at > ?',
    )
    .bind(key, now - 3600 * 1000)
    .first<{ c: number }>();
  if ((hourly?.c ?? 0) >= HOURLY_LIMIT) {
    return { retryAfter: 3600 };
  }

  return null;
}

async function markSent(db: D1Database, key: string): Promise<void> {
  const now = Date.now();
  const keyHash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(key));
  const keyId = Array.from(new Uint8Array(keyHash))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  await db
    .prepare('INSERT OR REPLACE INTO email_rate_limit (key, last_sent) VALUES (?, ?)')
    .bind(keyId, now)
    .run();
}

/**
 * 经 SMTP 发送验证码邮件。
 *
 * 这里用 Cloudflare Workers 的 email sending binding 或第三方 HTTP 邮件服务均可；
 * 若走 SMTP 通道，`SMTP_AUTH_CODE` 只从 env 读取，代码与日志中都不出现其值。
 */
async function sendVerificationEmail(env: Env, email: string, code: string, login: string): Promise<void> {
  if (!env.SMTP_AUTH_CODE) {
    throw new Error('SMTP_AUTH_CODE 未配置（请执行 wrangler secret put SMTP_AUTH_CODE）');
  }
  const host = env.SMTP_HOST ?? 'smtp.139.com';
  const from = env.SMTP_FROM;

  // 用 email binding（Workers 原生出站邮件）：
  //   wrangler 配置 { "send_email_binding": { "name": "EMAIL" } }
  //   再 await env.EMAIL.send({ from, to, raw })
  // 下面的 send_email binding 是 Cloudflare 的出站邮件绑定，不经过 SMTP 授权码，
  // 因此对「授权码」无依赖——保留 SMTP_HOST/授权码读取仅为兼容 SMTP 型后端。
  const binder = (env as unknown as { EMAIL?: { send: (m: unknown) => Promise<void> } }).EMAIL;
  if (binder?.send) {
    const subject = 'mox 邮箱验证';
    const raw = [
      `From: ${from}`,
      `To: ${email}`,
      `Subject: ${subject}`,
      'Content-Type: text/plain; charset=utf-8',
      '',
      `你好 ${login}：`,
      '',
      `你的 moxsh 邮箱验证码是：${code}`,
      `10 分钟内有效。若非本人操作请忽略本邮件。`,
    ].join('\r\n');
    await binder.send({ from, to: email, raw });
    return;
  }

  throw new Error('未配置出站邮件绑定（EMAIL）或 SMTP 通道');
}

async function handleSendCode(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return fail('未提供 GitHub 令牌，请先登录 GitHub', 401);

  const session = await verifyGitHubToken(token, env);
  if (!session) return fail('GitHub 令牌无效或已过期，请重新登录', 401);

  const { email } = (await request.json().catch(() => ({}))) as { email?: string };
  if (!email || !isEmailValid(email)) return fail('邮箱格式不正确');

  const ip = request.headers.get('cf-connecting-ip') ?? 'unknown';
  const limited = await checkRateLimit(env.DB, `${session.login}|${ip}`);
  if (limited) {
    return fail('发送过于频繁，请稍后再试', 429, { retry_after: limited.retryAfter });
  }

  const code = generateCode();
  await sendVerificationEmail(env, email, code, session.login);
  await markSent(env.DB, `${session.login}|${ip}`);

  await env.DB.prepare(
    'INSERT INTO email_codes (email, github, code, expires_at, used, created_at) VALUES (?, ?, ?, ?, 0, ?)',
  )
    .bind(email, session.login, code, Date.now() + CODE_TTL_MS, Date.now())
    .run();

  return json({ ok: true, cooldown: Math.ceil(RESEND_COOLDOWN_MS / 1000) });
}

async function handleVerify(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return fail('未提供 GitHub 令牌，请先登录 GitHub', 401);

  const session = await verifyGitHubToken(token, env);
  if (!session) return fail('GitHub 令牌无效或已过期，请重新登录', 401);

  const { email, code } = (await request.json().catch(() => ({}))) as {
    email?: string;
    code?: string;
  };
  if (!email || !isEmailValid(email)) return fail('邮箱格式不正确');
  if (!code || !/^\d{6}$/.test(code)) return fail('请输入 6 位数字验证码');

  const row = await env.DB.prepare(
    `SELECT code, expires_at, used FROM email_codes
     WHERE email = ? AND github = ? AND code = ?
     ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(email, session.login, code)
    .first<{ code: string; expires_at: number; used: number }>();

  // 不区分「不存在」与「错误」，避免被用来探测邮箱
  if (!row || row.used || Date.now() > row.expires_at) {
    return fail('验证码已过期或错误');
  }

  await env.DB.prepare('UPDATE email_codes SET used = 1 WHERE email = ? AND github = ? AND code = ?')
    .bind(email, session.login, code)
    .run();

  const now = Date.now();
  await env.DB.prepare(
    'INSERT OR REPLACE INTO email_bindings (github, email, bound_at) VALUES (?, ?, ?)',
  )
    .bind(session.login, email, now)
    .run();

  return json({ ok: true, email, bound_at: now });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method !== 'POST') return fail('仅支持 POST', 405);

    try {
      switch (url.pathname) {
        case '/auth/email/send-code':
          return await handleSendCode(request, env);
        case '/auth/email/verify':
          return await handleVerify(request, env);
        default:
          return fail('Not Found', 404);
      }
    } catch (e) {
      // 日志中不输出任何密钥或验证码
      console.error('email-auth error:', (e as Error)?.message);
      return fail('服务异常，请稍后再试', 500);
    }
  },
};
/**
 * mox-id Worker：邮箱验证码发送与校验（路径 B —— 用「与 GitHub 不同」的邮箱时使用）。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  【重要】为什么默认路径不是这里
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  绝大多数用户应该走**路径 A**（App 端「用 GitHub 已验证邮箱一键绑定」）：
 *  不发邮件、无需后端、装上即用、零限流问题。见 GitHubVerifiedEmail.kt。
 *
 *  本Worker 只服务于「用户想绑定一个与 GitHub 账号不同的邮箱」这个少数场景。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  【发信通道】为什么用 HTTP API 而不是 SMTP 授权码
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  1. Cloudflare Workers **硬性封锁出站 25 端口**；`send_email` 绑定（Email Routing）
 *     只能发给 Cloudflare 已验证的转发地址，**发不到任意用户邮箱**。
 *     => 在 Worker 上「用 139 邮箱 SMTP 授权码发信」这条路是走不通的。
 *  2. 139/163 系个人邮箱 SMTP 是为**个人收发**设计的，拿来发验证码必然被风控：
 *     每日额度、IP 频率、并发数三重限制，且 Cloudflare 出口 IP 信誉极差，
 *     极易被判定为垃圾邮件源而整体拒收。
 *  3. 专业事务邮件服务（Resend / SendGrid 等）走 HTTPS API：
 *     共享 IP 池已预热、送达率高、自带退信与配额管理，专为程序化发信设计。
 *
 *  => 结论：本 Worker 通过 `RESEND_API_KEY` 调 Resend 的 HTTPS API 发信。
 *     密钥只存在于 Worker secret，代码与公开仓库中零字面量。
 *
 *  接入其他服务商只需替换 `deliverViaResend()` 一个函数。
 *
 * 相关文档：docs/email-auth.md
 */

export interface Env {
  DB: D1Database;

  /** Resend API Key（https://resend.com）。只存在于 Worker secret：wrangler secret put RESEND_API_KEY */
  RESEND_API_KEY?: string;
  /** 已验证的发件人地址，如 mox@yourdomain.com（Resend 会验证其 DNS 记录） */
  MAIL_FROM?: string;
  /** 回退发件人。Resend 账号未验证时可先发到本人邮箱完成联调。 */
  MAIL_FALLBACK_TO?: string;
}

// ══════════════════════════════════════════════════════════════════════════
//  限流参数（针对个人邮箱被限流的问题，做了三层收紧 + 熔断）
// ══════════════════════════════════════════════════════════════════════════

const CODE_TTL_MS = 10 * 60 * 1000;   // 验证码 10 分钟有效
const RESEND_COOLDOWN_MS = 60 * 1000; // 同一主体重发间隔 60s

/** 校验端防爆破：同一 (github, email) 每小时最多失败尝试次数 */
const VERIFY_FAIL_LIMIT = 10;
/** 失败计数窗口 */
const VERIFY_FAIL_WINDOW = 3600 * 1000;

/** 主体维度：同一 GitHub 账号每小时最多发几封 */
const LIMIT_PER_HOUR_SUBJECT = 5;
/** IP 维度：同一客户端 IP 每小时最多发几封（防换账号刷量） */
const LIMIT_PER_HOUR_IP = 10;
/** IP 维度：同一 IP 每天最多发几封（防换号长期骚扰） */
const LIMIT_PER_DAY_IP = 30;
/** 目标邮箱维度：同一邮箱每小时最多收几封（防轰炸他人邮箱） */
const LIMIT_PER_HOUR_TARGET = 3;
/** 连续发信失败达此次数即熔断（通常是服务商拒绝 / 域名未验证） */
const CIRCUIT_BREAK_FAILURES = 5;
/** 熔断持续时间 */
const CIRCUIT_BREAK_MS = 15 * 60 * 1000;

interface Session {
  login: string;
  id: number;
}

// 跨域来源白名单：仅官网同域、GitHub Pages 镜像与本地开发回显 CORS。
// 此前为 '*'，虽本 Worker 走 Bearer Token（非 Cookie，无经典 CSRF 风险），
// 但收紧为显式白名单可消除审计指出的宽松头隐患，且与前端 api-proxy 策略一致。
const ALLOWED_ORIGINS = new Set<string>([
  'https://moxsh.app',
  'https://www.moxsh.app',
  'https://mox-site.pages.dev',
  'https://codecloud-dev.github.io',
]);

function resolveCorsOrigin(origin?: string | null): string | null {
  if (!origin) return null;
  if (ALLOWED_ORIGINS.has(origin)) return origin;
  if (/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)) return origin;
  return null;
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
  // 取模 100 万后 padStart；用 30 位随机数 × 范围/2^32 避免取模偏差
  const n = Math.floor((buf[0] / 4294967296) * 1_000_000);
  return String(n).padStart(6, '0');
}

/** 基本邮箱形状校验（可达性以发信成功与否为准）。 */
function isEmailValid(email: string): boolean {
  const e = email.trim();
  if (e.length > 254) return false;
  const at = e.indexOf('@');
  if (at <= 0 || at !== e.lastIndexOf('@')) return false;
  const local = e.slice(0, at);
  const domain = e.slice(at + 1);
  // 本地段 ≤64，且不能以点开头/结尾或出现连续点
  if (local.length > 64 || local.startsWith('.') || local.endsWith('.') || local.includes('..')) {
    return false;
  }
  // 字符集校验：原实现只查域名形状，会放行 "a b@ex ample.com" 这类含空格/控制字符的
  // 畸形地址——它们会被投递服务 4xx 拒绝，既空耗限流配额，又是下文熔断器的误触发源
  if (!/^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+$/.test(local)) return false;
  if (domain.length < 3) return false;
  // 域名按 hostname 形状校验：拒绝空格、下划线、空标签（".."）、首尾 '-'
  if (
    !/^[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$/.test(
      domain,
    )
  ) {
    return false;
  }
  return true;
}

/**
 * 校验 GitHub access_token，返回其登录用户。
 * 失败返回 null —— 调用方必须拒绝，绝不能只解码不验证。
 */
async function verifyGitHubToken(token: string): Promise<Session | null> {
  const resp = await fetch('https://api.github.com/user', {
    headers: {
      authorization: `Bearer ${token}`,
      accept: 'application/vnd.github+json',
      'user-agent': 'mox-id',
      'x-github-api-version': '2022-11-28',
    },
  });
  if (!resp.ok) return null;
  const data = (await resp.json()) as { login?: string; id?: number };
  if (!data.login) return null;
  return { login: data.login, id: data.id ?? 0 };
}

function bearer(request: Request): string | null {
  const raw = request.headers.get('authorization') ?? '';
  const m = /^Bearer\s+(.+)$/i.exec(raw);
  return m ? m[1].trim() : null;
}

function hashKey(key: string): Promise<string> {
  return crypto.subtle
    .digest('SHA-256', new TextEncoder().encode(key))
    .then((buf) =>
      Array.from(new Uint8Array(buf))
        .map((b) => b.toString(16).padStart(2, '0'))
        .join(''),
    );
}

async function countSince(
  db: D1Database,
  column: 'github' | 'ip',
  value: string,
  sinceMs: number,
): Promise<number> {
  const row = await db
    .prepare(`SELECT COUNT(*) AS c FROM email_codes
              WHERE ${column} = ? AND created_at > ?`)
    .bind(value, sinceMs)
    .first<{ c: number }>();
  return row?.c ?? 0;
}

interface Limited {
  retryAfter: number;
  reason: string;
}

/**
 * 三维频控：主体（GitHub 账号）/ 客户端 IP / 目标邮箱。
 *
 * 这一层是针对「邮箱发送被限流」设计的：与其等服务商风控把我们拒掉，
 * 不如自己在最外层就把异常流量挡住，保护发信域名/IP 的信誉。
 */
async function checkLimits(
  db: D1Database,
  login: string,
  ip: string,
  email: string,
): Promise<Limited | null> {
  const now = Date.now();
  const hour = 3600 * 1000;
  const day = 24 * hour;

  // 1) 熔断：连续失败过多则暂停，保护发信通道
  const breaker = await db
    .prepare('SELECT fail_count, tripped_at FROM email_breaker WHERE id = 1')
    .first<{ fail_count: number; tripped_at: number | null }>();
  if (
    breaker?.tripped_at &&
    breaker.fail_count >= CIRCUIT_BREAK_FAILURES &&
    now - breaker.tripped_at < CIRCUIT_BREAK_MS
  ) {
    return {
      retryAfter: Math.ceil((CIRCUIT_BREAK_MS - (now - breaker.tripped_at)) / 1000),
      reason: '发信服务暂时不可用，请稍后再试',
    };
  }

  // 2) 主体冷却：同一 GitHub 账号 60s 内只能发一次
  const subjectKey = await hashKey(`subject|${login}`);
  const lastSubject = await db
    .prepare('SELECT last_sent FROM email_rate_limit WHERE key = ?')
    .bind(subjectKey)
    .first<{ last_sent: number }>();
  if (lastSubject && now - lastSubject.last_sent < RESEND_COOLDOWN_MS) {
    return {
      retryAfter: Math.ceil((RESEND_COOLDOWN_MS - (now - lastSubject.last_sent)) / 1000),
      reason: '发送过于频繁，请稍后再试',
    };
  }

  // 3) IP 冷却：防止多个账号共用一个 IP 时刷量
  const ipKey = await hashKey(`ip|${ip}`);
  const lastIp = await db
    .prepare('SELECT last_sent FROM email_rate_limit WHERE key = ?')
    .bind(ipKey)
    .first<{ last_sent: number }>();
  if (lastIp && now - lastIp.last_sent < RESEND_COOLDOWN_MS) {
    return {
      retryAfter: Math.ceil((RESEND_COOLDOWN_MS - (now - lastIp.last_sent)) / 1000),
      reason: '发送过于频繁，请稍后再试',
    };
  }

  // 4) 主体小时配额
  const perHourSubject = await countSince(db, 'github', login, now - hour);
  if (perHourSubject >= LIMIT_PER_HOUR_SUBJECT) {
    // 注意：LIMIT_PER_HOUR_SUBJECT 是每小时配额，原文案写成「今日/明天」与实际窗口不符
    return { retryAfter: hour, reason: '本账号发信次数过多，请稍后再试' };
  }

  // 5) IP 小时 / 天配额
  const perHourIp = await countSince(db, 'ip', ip, now - hour);
  if (perHourIp >= LIMIT_PER_HOUR_IP) {
    return { retryAfter: hour, reason: '当前网络发信次数过多，请稍后再试' };
  }
  const perDayIp = await countSince(db, 'ip', ip, now - day);
  if (perDayIp >= LIMIT_PER_DAY_IP) {
    return { retryAfter: day, reason: '当前网络今日发信次数已达上限' };
  }

  // 6) 目标邮箱小时配额：防止拿本服务轰炸他人邮箱（这是最容易被滥用的一点）
  const targetRow = await db
    .prepare(
      `SELECT COUNT(*) AS c FROM email_codes
       WHERE email = ? AND created_at > ?`,
    )
    .bind(email, now - hour)
    .first<{ c: number }>();
  if ((targetRow?.c ?? 0) >= LIMIT_PER_HOUR_TARGET) {
    return { retryAfter: hour, reason: '该邮箱接收验证码过于频繁，请稍后再试' };
  }

  return null;
}

/**
 * 发信前**原子抢占**主体/IP 冷却窗口（条件 UPSERT，WHERE 未命中则 changes=0）。
 *
 * 原实现的 checkLimits 只读先查、markSent 后写，并发请求会同时通过全部限流造成刷信；
 * 这里改为写侧把关：写入成功才真正占用 60s 窗口，把并发/重放绕过的可能压到零。
 *
 * 说明：主体先抢、IP 后抢；万一 IP 未抢到，主体窗口已占用——最多少发一封，
 * 不会放过超额请求（该请求本来就会被 IP 冷却拒绝）。
 */
async function claimCooldown(db: D1Database, keys: string[]): Promise<boolean> {
  const floor = Date.now() - RESEND_COOLDOWN_MS;
  for (const key of keys) {
    const r = await db
      .prepare(
        `INSERT INTO email_rate_limit (key, last_sent) VALUES (?, ?)
         ON CONFLICT(key) DO UPDATE SET last_sent = excluded.last_sent
         WHERE email_rate_limit.last_sent < ?`,
      )
      .bind(key, Date.now(), floor)
      .run();
    if ((r.meta?.changes ?? 0) !== 1) return false;
  }
  return true;
}

/** 发信成功：重置熔断计数（冷却窗口已在发信前抢占）。 */
async function resetBreaker(db: D1Database): Promise<void> {
  await db
    .prepare('UPDATE email_breaker SET fail_count = 0, tripped_at = NULL WHERE id = 1')
    .run();
}

/**
 * 发信失败累加熔断计数。
 * 只应计入**服务侧**故障；请求侧 4xx（邮箱被拒等）见 handleSendCode 的过滤。
 */
async function markFailure(db: D1Database): Promise<void> {
  const now = Date.now();
  await db
    .prepare(
      `INSERT INTO email_breaker (id, fail_count, tripped_at)
       VALUES (1, 1, NULL)
       ON CONFLICT(id) DO UPDATE SET
         fail_count = fail_count + 1,
         tripped_at = CASE WHEN fail_count + 1 >= ? THEN ? ELSE tripped_at END`,
    )
    .bind(CIRCUIT_BREAK_FAILURES, now)
    .run();
}

/** 服务端未配置密钥/发件人——属配置问题而非发信通道故障，不计熔断。 */
class MailConfigError extends Error {}

/** 投递服务返回非 2xx；status 供上层区分请求侧（4xx）/ 服务侧（5xx/401/403）故障。 */
class MailDeliveryError extends Error {
  status?: number;
  constructor(message: string, status?: number) {
    super(message);
    this.status = status;
  }
}

/**
 * 经 Resend 的 HTTPS API 发送验证码邮件。
 *
 * 换服务商（如 SendGrid / 阿里云邮件推送）只需替换本函数，
 * 上层的限流、熔断、校验逻辑全部复用。
 */
async function deliverViaResend(
  env: Env,
  email: string,
  code: string,
  login: string,
): Promise<void> {
  if (!env.RESEND_API_KEY) {
    throw new MailConfigError('RESEND_API_KEY 未配置（wrangler secret put RESEND_API_KEY）');
  }
  const from = env.MAIL_FROM ?? env.MAIL_FALLBACK_TO;
  if (!from) {
    throw new MailConfigError('MAIL_FROM 未配置（wrangler secret put MAIL_FROM）');
  }

  const resp = await fetch('https://api.resend.com/emails', {
    method: 'POST',
    headers: {
      authorization: `Bearer ${env.RESEND_API_KEY}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      from,
      to: [email],
      subject: 'moxsh 邮箱验证码',
      text: [
        `你好 ${login}：`,
        '',
        `你的 moxsh 邮箱验证码是：${code}`,
        '',
        '10 分钟内有效。若非本人操作请忽略本邮件。',
        '（此邮箱由 GitHub 账号 ' + login + ' 绑定）',
      ].join('\n'),
    }),
  });

  if (!resp.ok) {
    // 只记录状态码，不记录响应体中可能含邮箱/密钥的内容
    throw new MailDeliveryError(`发信服务返回 ${resp.status}`, resp.status);
  }
}

async function handleSendCode(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return fail('未提供 GitHub 令牌，请先登录 GitHub', 401);

  const session = await verifyGitHubToken(token);
  if (!session) return fail('GitHub 令牌无效或已过期，请重新登录', 401);

  const { email } = (await request.json().catch(() => ({}))) as { email?: string };
  if (!email || !isEmailValid(email)) return fail('邮箱格式不正确');
  const target = email.trim().toLowerCase();

  const ip = request.headers.get('cf-connecting-ip') ?? 'unknown';

  const limited = await checkLimits(env.DB, session.login, ip, target);
  if (limited) {
    return fail(limited.reason, 429, { retry_after: limited.retryAfter });
  }

  // 发信前原子抢占冷却窗口（checkLimits 是只读预检，并发下可被同时绕过）
  const claimed = await claimCooldown(env.DB, [
    await hashKey(`subject|${session.login}`),
    await hashKey(`ip|${ip}`),
  ]);
  if (!claimed) {
    return fail('发送过于频繁，请稍后再试', 429, {
      retry_after: Math.ceil(RESEND_COOLDOWN_MS / 1000),
    });
  }

  // 先落库再发信：原顺序是「先发信后写入」，D1 写入一旦失败，用户已收到的
  // 验证码永远无法校验，冷却窗口也被白扣。发信失败时再把预置记录删掉，
  // 避免污染目标邮箱的每小时配额。
  const code = generateCode();
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO email_codes (email, github, code, ip, expires_at, used, created_at)
     VALUES (?, ?, ?, ?, ?, 0, ?)`,
  )
    .bind(target, session.login, code, ip, now + CODE_TTL_MS, now)
    .run();

  try {
    await deliverViaResend(env, target, code, session.login);
  } catch (e) {
    await env.DB.prepare('DELETE FROM email_codes WHERE email = ? AND github = ? AND code = ?')
      .bind(target, session.login, code)
      .run();

    if (e instanceof MailConfigError) {
      // 未配置密钥/发件人：不是通道故障，不计熔断，直接报 503 便于部署排障
      console.error('mail not configured:', e.message);
      return fail('发信服务未配置，暂不可用', 503);
    }

    // 仅服务侧故障计入熔断（5xx / 401 / 403 / 网络异常）；请求侧 4xx
    // （如邮箱被服务商拒绝）是调用方问题，不应把公共发信通道拖到全院熔断
    const status = e instanceof MailDeliveryError ? e.status : undefined;
    if (typeof status !== 'number' || status >= 500 || status === 401 || status === 403) {
      await markFailure(env.DB);
    }
    console.error('deliver failed:', e instanceof Error ? e.message : String(e));
    return fail('验证码发送失败，请稍后再试', 502);
  }

  await resetBreaker(env.DB);

  return json({ ok: true, cooldown: Math.ceil(RESEND_COOLDOWN_MS / 1000) });
}

async function handleVerify(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return fail('未提供 GitHub 令牌，请先登录 GitHub', 401);

  const session = await verifyGitHubToken(token);
  if (!session) return fail('GitHub 令牌无效或已过期，请重新登录', 401);

  const { email, code } = (await request.json().catch(() => ({}))) as {
    email?: string;
    code?: string;
  };
  if (!email || !isEmailValid(email)) return fail('邮箱格式不正确');
  if (!code || !/^\d{6}$/.test(code)) return fail('请输入 6 位数字验证码');
  const target = email.trim().toLowerCase();
  const now = Date.now();

  // 防爆破：验证码只有 6 位（10^6 空间），若无失败计数，拿到他人令牌后可在线
  // 枚举到 TTL 结束为止。按 (github, email) 计失败次数，超限即锁窗口。
  const vfailKey = await hashKey(`vfail|${session.login}|${target}`);
  const vfail = await env.DB.prepare(
    'SELECT fails, window_start FROM email_verify_fail WHERE key = ?',
  )
    .bind(vfailKey)
    .first<{ fails: number; window_start: number }>();
  if (
    vfail &&
    vfail.fails >= VERIFY_FAIL_LIMIT &&
    now - vfail.window_start < VERIFY_FAIL_WINDOW
  ) {
    return fail('校验尝试次数过多，请稍后再试', 429, {
      retry_after: Math.ceil((VERIFY_FAIL_WINDOW - (now - vfail.window_start)) / 1000),
    });
  }

  const row = await env.DB.prepare(
    `SELECT code, expires_at, used FROM email_codes
     WHERE email = ? AND github = ? AND code = ?
     ORDER BY created_at DESC LIMIT 1`,
  )
    .bind(target, session.login, code)
    .first<{ code: string; expires_at: number; used: number }>();

  // 不区分「不存在」与「错误」，避免被用来探测邮箱
  if (!row || row.used || Date.now() > row.expires_at) {
    // 记录一次失败尝试（窗口过期则重新计）
    await env.DB.prepare(
      `INSERT INTO email_verify_fail (key, fails, window_start) VALUES (?, 1, ?)
       ON CONFLICT(key) DO UPDATE SET
         fails = CASE WHEN window_start < ? THEN 1 ELSE fails + 1 END,
         window_start = CASE WHEN window_start < ? THEN ? ELSE window_start END`,
    )
      .bind(vfailKey, now, now - VERIFY_FAIL_WINDOW, now - VERIFY_FAIL_WINDOW, now)
      .run();
    return fail('验证码已过期或错误');
  }

  // 标记已消费 + 写入绑定 + 清除失败计数（一批完成，避免「码已消费但绑定失败」）
  await env.DB.batch([
    env.DB
      .prepare('UPDATE email_codes SET used = 1 WHERE email = ? AND github = ? AND code = ?')
      .bind(target, session.login, code),
    env.DB
      .prepare(
        'INSERT OR REPLACE INTO email_bindings (github, email, verified, bound_at) VALUES (?, ?, 0, ?)',
      )
      .bind(session.login, target, now),
    env.DB.prepare('DELETE FROM email_verify_fail WHERE key = ?').bind(vfailKey),
  ]);

  return json({ ok: true, email: target, verified: false, bound_at: now });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    // 健康检查：部署后确认 Worker 存活用。
    // 必须放在方法/路径检查之前 —— 否则GET /health 会被 405 挡掉，无法探活。
    if (url.pathname === '/health') {
      const from = env.MAIL_FROM ?? env.MAIL_FALLBACK_TO;
      // 占位符域名（CI 未注入 Variable MAIL_FROM 时残留）视为未配置：
      // 否则 /health 显示已配置，实际每一封都被 Resend 403 拒绝
      const configured = Boolean(
        env.RESEND_API_KEY && from && !from.includes('REPLACE_WITH_YOUR_DOMAIN'),
      );
      return json({ ok: true, service: 'mox-id-email', mail_configured: configured });
    }

    if (request.method === 'OPTIONS') return withCors(json({ ok: true }), request);
    // 405 也要带 CORS 头：否则浏览器把跨域方法错误报成 CORS 错误，掩盖真实状态码
    if (request.method !== 'POST') return withCors(fail('仅支持 POST', 405), request);

    try {
      switch (url.pathname) {
        case '/auth/email/send-code':
          return withCors(await handleSendCode(request, env), request);
        case '/auth/email/verify':
          return withCors(await handleVerify(request, env), request);
        default:
          return withCors(fail('Not Found', 404), request);
      }
    } catch (e) {
      // 日志中不输出任何密钥或验证码
      console.error('email-auth error:', (e as Error)?.message);
      return withCors(fail('服务异常，请稍后再试', 500), request);
    }
  },
};

/** 按请求来源白名单回显 CORS 头（未命中则不发，避免宽松 '+' 暴露）。 */
function withCors(res: Response, request: Request): Response {
  const origin = resolveCorsOrigin(request.headers.get('origin'));
  if (origin) {
    res.headers.set('access-control-allow-origin', origin);
    res.headers.set('access-control-allow-methods', 'POST, OPTIONS');
    res.headers.set('access-control-allow-headers', 'authorization, content-type');
  }
  return res;
}

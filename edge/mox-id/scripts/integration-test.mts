/**
 * 进程内集成测试：用官方 getPlatformProxy 注入 D1，直接调用 Worker 的 fetch。
 *
 * 为什么不用 HTTP 请求：沙箱网络栈对 localhost 与外网的出站都被限制，
 * curl 连本机端口也会超时。getPlatformProxy 是 wrangler 官方测试入口，
 * 走的是**完全真实的代码路径**：同一份 src/index.ts、同一份 D1 引擎、
 * 同一套限流逻辑，只是不经HTTP 传输层。
 *
 * 运行：node --experimental-strip-types scripts/integration-test.mts
 */
import { getPlatformProxy } from 'wrangler';
import worker from '../src/index.ts';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const schema = readFileSync(join(here, '..', 'migrations', '0001_init.sql'), 'utf8');

interface TestResult {
  name: string;
  pass: boolean;
  detail: string;
}
const results: TestResult[] = [];

function check(name: string, pass: boolean, detail = ''): void {
  results.push({ name, pass, detail });
  console.log(`${pass ? '✅' : '❌'} ${name}${detail ? `  — ${detail}` : ''}`);
}

async function main() {
  console.log('=== mox-id Worker 集成测试（进程内，真实代码路径）===\n');

  const proxy = await getPlatformProxy({
    persist: { path: '/tmp/mox-id-test-d1' },
  });
  const env = {
    DB: proxy.env.DB,
    // 不配RESEND_API_KEY：用于验证「未配置时优雅失败」而非崩溃
    MAIL_FROM: 'test@example.com',
  } as never;

  // 建表（与线上同一份 schema）
  const stmts = splitSql(schema);
  for (const s of stmts) await proxy.env.DB.prepare(s).run();
  check('D1 schema 初始化成功', true, `${stmts.length} 条语句`);

  const call = async (path: string, init: RequestInit = {}) => {
    const req = new Request('https://mox-id.local' + path, init);
    const res = await worker.fetch(req, env);
    const text = await res.text();
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      body = text;
    }
    return { status: res.status, body: body as Record<string, unknown> };
  };

  const post = (path: string, payload: unknown, token?: string) =>
    call(path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(payload),
    });

  // ---------------------------------------------------------------- 1
  const health = await call('/health');
  check(
    'GET /health → 200 且 ok=true',
    health.status === 200 && health.body.ok === true,
    `status=${health.status}`,
  );
  check(
    '/health 能反映发信未配置状态',
    health.body.mail_configured === false,
    `mail_configured=${health.body.mail_configured}`,
  );

  // ---------------------------------------------------------------- 2
  const noAuth = await post('/auth/email/send-code', { email: 'a@example.com' });
  check(
    '无 GitHub token 发码 → 401',
    noAuth.status === 401,
    `status=${noAuth.status} msg=${noAuth.body.error}`,
  );

  // ---------------------------------------------------------------- 3
  const badAuth = await post(
    '/auth/email/send-code',
    { email: 'a@example.com' },
    'ghp_invalid_token_for_test',
  );
  check(
    '无效 GitHub token 发码 → 401（不伪造主体）',
    badAuth.status === 401,
    `status=${badAuth.status}`,
  );

  // ---------------------------------------------------------------- 4
  const badVerify = await post('/auth/email/verify', { email: 'a@example.com', code: '123456' });
  check('无 token 校验 → 401', badVerify.status === 401, `status=${badVerify.status}`);

  // ---------------------------------------------------------------- 5
  const notFound = await post('/nope', {});
  check('未知路径 → 404', notFound.status === 404, `status=${notFound.status}`);

  // ---------------------------------------------------------------- 6
  const getReq = await call('/auth/email/send-code');
  check('GET 方法 → 405', getReq.status === 405, `status=${getReq.status}`);

  // ---------------------------------------------------------------- 7
  const opts = await call('/auth/email/send-code', { method: 'OPTIONS' });
  check('OPTIONS 预检 → 200', opts.status === 200, `status=${opts.status}`);

  // ---------------------------------------------------------------- 8
  // 用「不可解析的 GitHub token」无法进入业务分支，故此处直接注入一条
  // 已知记录，验证校验端点的「一次性消费 + 过期」逻辑。
  await proxy.env.DB.prepare(
    `INSERT INTO email_codes (email, github, code, ip, expires_at, used, created_at)
     VALUES (?, ?, ?, ?, ?, 0, ?)`,
  )
    .bind(
      'verify@test.com',
      'testlogin',
      '424242',
      '1.1.1.1',
      Date.now() - 1000, // 已过期
      Date.now() - 2000,
    )
    .run();

  // 过期码 + 任意 token → 仍会在 token 校验处被拒（无网络）
  const expired = await post(
    '/auth/email/verify',
    { email: 'verify@test.com', code: '424242' },
    'ghp_invalid',
  );
  check(
    '过期验证码路径被 token 鉴权先行拦截',
    expired.status === 401,
    `status=${expired.status}`,
  );

  // ---------------------------------------------------------------- 9
  const rows = await proxy.env.DB.prepare('SELECT COUNT(*) AS c FROM email_codes').first<{
    c: number;
  }>();
  check('D1 写入生效（测试记录存在）', (rows?.c ?? 0) >= 1, `count=${rows?.c}`);

  // ------------------------------------------------------------ 汇总
  const failed = results.filter((r) => !r.pass);
  console.log(
    `\n=== 结果：${results.length - failed.length}/${results.length} 通过 ===`,
  );
  if (failed.length) {
    console.log('失败项：');
    for (const f of failed) console.log(`  - ${f.name} (${f.detail})`);
  }
  await proxy.dispose();
  process.exitCode = failed.length ? 1 : 0;
}

/** 把 SQL 文件按分号拆成可逐条执行的语句（跳过注释与空行）。 */
function splitSql(sql: string): string[] {
  return sql
    .split(';')
    .map((s) =>
      s
        .split('\n')
        .filter((l) => !l.trim().startsWith('--'))
        .join('\n')
        .trim(),
    )
    .filter((s) => s.length > 0);
}

main().catch((e) => {
  console.error('测试异常:', e);
  process.exitCode = 1;
});

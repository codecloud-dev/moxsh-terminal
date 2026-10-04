-- mox-id D1 schema：邮箱验证码（路径 B）
--
-- 用 wrangler d1 execute mox-id --file=./migrations/0001_init.sql 执行。
-- 已存在表时用 --batch（wrangler 3.x）或直接忽略报错重复执行即可（全部IF NOT EXISTS）。

-- 验证码：一次性消费，10 分钟过期
CREATE TABLE IF NOT EXISTS email_codes (
  email      TEXT    NOT NULL,          -- 归一化后的目标邮箱（小写）
  github     TEXT    NOT NULL,          -- GitHub login，归属主体（不可伪造）
  code       TEXT    NOT NULL,          -- 6 位数字验证码
  ip         TEXT    NOT NULL DEFAULT '', -- 请求方 IP，用于 IP 维度限流
  expires_at INTEGER NOT NULL,          -- 过期时间戳（毫秒）
  used       INTEGER NOT NULL DEFAULT 0,-- 1=已消费，防重放
  created_at INTEGER NOT NULL           -- 创建时间戳（毫秒）
);

CREATE INDEX IF NOT EXISTS idx_email_codes_lookup  ON email_codes (email, github);
CREATE INDEX IF NOT EXISTS idx_email_codes_subject ON email_codes (github, created_at);
CREATE INDEX IF NOT EXISTS idx_email_codes_ip      ON email_codes (ip, created_at);

-- 冷却窗口：同一 GitHub 账号 / 同一 IP 的最短重发间隔
-- key 为SHA-256 后的值，不存明文（避免存IP / login）
CREATE TABLE IF NOT EXISTS email_rate_limit (
  key       TEXT    PRIMARY KEY,
  last_sent INTEGER NOT NULL
);

-- 熔断器：连续发信失败达阈值即暂停，保护发信域名 / IP 信誉
CREATE TABLE IF NOT EXISTS email_breaker (
  id         INTEGER PRIMARY KEY CHECK (id = 1),
  fail_count INTEGER NOT NULL DEFAULT 0,
  tripped_at INTEGER                -- 最近一次熔断触发时间
);

INSERT OR IGNORE INTO email_breaker (id, fail_count, tripped_at) VALUES (1, 0, NULL);

-- 已完成绑定：github 为主键，一个 GitHub 账号只绑一个邮箱
CREATE TABLE IF NOT EXISTS email_bindings (
  github   TEXT    PRIMARY KEY,
  email    TEXT    NOT NULL,
  verified INTEGER NOT NULL DEFAULT 0, -- 1=GitHub 已验证（路径 A，本表一般不用）
  bound_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_email_bindings_email ON email_bindings (email);

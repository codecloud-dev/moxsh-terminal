#!/usr/bin/env bash
# ============================================================================
#  moxsh · 一键推送到 GitHub
#  适用：在你【自己有网络 + 能访问 GitHub】的环境里运行
#        （例如你本机终端、或 CodeBuddy 里已连接 GitHub 的终端）
#  本脚本做的事：
#    1) 创建公开仓库 zyr20121219/moxsh-terminal（GPL-3.0，带双语简介）
#    2) 把当前已提交的代码 push 上去
#  所有内容在沙箱里【已经提交好】了，这里只负责"创建仓库 + 推送"。
# ============================================================================
set -euo pipefail

OWNER="zyr20121219"
REPO="moxsh-terminal"
DESC="液态玻璃终端，全面兼容 Termux 生态 —— Rust 内核 / PRoot 容器 / 插件商店 / 内置 AI 助手"
VISIBILITY="public"          # public / private
REMOTE_URL="https://github.com/$OWNER/$REPO.git"

# 当前分支（兼容 main / master）
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"

echo "==> 目标仓库: $OWNER/$REPO  ($VISIBILITY)"
echo "==> 当前分支: $BRANCH"

# ---------------------------------------------------------------------------
# 方式一：GitHub CLI（gh）—— 最省事
# ---------------------------------------------------------------------------
if command -v gh >/dev/null 2>&1; then
  if gh auth status >/dev/null 2>&1; then
    echo "==> 检测到已登录的 gh，直接创建并推送..."
    # 仓库不存在就创建；已存在则忽略错误
    gh repo create "$REPO" --description "$DESC" --license GPL-3.0 --"$VISIBILITY" \
      --source . --remote origin --push 2>/dev/null \
      || git remote set-url origin "$REMOTE_URL" 2>/dev/null \
      || git remote add origin "$REMOTE_URL"
    git push -u origin "$BRANCH" && {
      echo "✅ 完成：https://github.com/$OWNER/$REPO"
      exit 0
    }
  else
    echo "==> gh 已安装但未登录，请先运行: gh auth login"
    echo "    登录后重新执行本脚本即可。"
  fi
fi

# ---------------------------------------------------------------------------
# 方式二：Personal Access Token（没有 gh 时用）
# ---------------------------------------------------------------------------
echo "==> 使用 Personal Access Token 方式"
if [ -z "${GITHUB_TOKEN:-}" ]; then
  read -r -s -p "请粘贴你的 GitHub Personal Access Token（输入时不可见）: " GITHUB_TOKEN
  echo
fi
[ -n "${GITHUB_TOKEN:-}" ] || { echo "❌ 未提供 token，已退出。"; exit 1; }

# 建仓（token 需有 repo 权限）
echo "==> 调用 API 创建仓库..."
curl -s -o /dev/null -w "create http=%{http_code}\n" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$REPO\",\"description\":\"$DESC\",\"license_template\":\"gpl-3.0\",\"private\":false}" \
  https://api.github.com/user/repos || true

# 推送（若直接推送慢/被墙，可改用 ghproxy 前缀，见下方注释）
git remote add origin "$REMOTE_URL" 2>/dev/null || git remote set-url origin "$REMOTE_URL"
# 如遇网络问题，可临时改用：
#   git remote set-url origin "https://ghproxy.net/https://github.com/$OWNER/$REPO.git"
git push -u origin "$BRANCH" && {
  echo "✅ 完成：https://github.com/$OWNER/$REPO"
  exit 0
}

echo "❌ 推送失败，请检查 token 权限 / 网络连接，或参考 docs/PUSH_GUIDE.md"
exit 1

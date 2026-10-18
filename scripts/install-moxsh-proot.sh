#!/usr/bin/env bash
# moxsh-proot 一键安装脚本（原版 Termux 用户专用）。
#
# 把 moxsh 自研 PRoot 引擎（Rust ptrace 路径翻译，非上游 C 版 proot 的封装）
# 装成 Termux 里的 `moxsh-proot` 命令，之后即可：
#
#   moxsh-proot -R ~/ubuntu -0 -- bash
#
# 安装方式（原版 Termux）：
#   curl -fsSL https://raw.githubusercontent.com/codecloud-dev/moxsh-terminal/main/scripts/install-moxsh-proot.sh | bash
#
# 设计约束（Termux 现实）：
#   - 只依赖 Termux 自带工具：curl / coreutils（sha256sum、install、mktemp）；
#   - 二进制从 GitHub Release 下载并做 sha256 校验（放不下就不装，绝不静默）；
#   - 非交互：`curl | bash` 下 stdin 是脚本本身，全程无需任何输入；
#   - PREFIX 未设置时按 Termux 常见路径探测，探测不到则明确报错（不猜 system 路径，
#     Android 10+ 的 W^X 会让 system 分区上的可执行文件根本跑不起来）。

set -euo pipefail

REPO="${MOXSH_PROOT_REPO:-codecloud-dev/moxsh-terminal}"
RELEASE="${MOXSH_PROOT_RELEASE:-proot-latest}"
# 默认走 GitHub Release；国内网络慢时可用 MOXSH_PROOT_BASE_URL 指向镜像
# （镜像需保持相同的 <BASE_URL>/<ASSET> 与 <BASE_URL>/<ASSET>.sha256 布局）。
BASE_URL="${MOXSH_PROOT_BASE_URL:-https://github.com/${REPO}/releases/download/${RELEASE}}"

log()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m警告:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m错误:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 环境探测
# PREFIX：Termux 会话必然设置；裸跑（如 ssh 进 Termux）时按已知路径兜底。
PREFIX="${PREFIX:-}"
if [ -z "$PREFIX" ]; then
  for c in \
    /data/data/com.termux/files/usr \
    /data/data/com.termux.dev/files/usr
  do
    if [ -d "$c" ]; then PREFIX="$c"; break; fi
  done
fi
[ -n "$PREFIX" ] || die "未检测到 Termux 环境（PREFIX 未设置）。
本脚本面向原版 Termux。若你在其他环境，请显式指定安装目录：
  PREFIX=/your/prefix bash install-moxsh-proot.sh"

# 架构：moxsh-proot 提供 aarch64（主流设备）与 x86_64（模拟器/Chromebook）两版。
case "$(uname -m)" in
  aarch64|arm64)  ASSET="moxsh-proot-aarch64-linux-android" ;;
  x86_64|amd64)   ASSET="moxsh-proot-x86_64-linux-android" ;;
  armv7l|armv8l|arm)
    die "检测到 32 位 ARM（$(uname -m)），暂不提供预编译二进制。
请从源码构建：https://github.com/${REPO}#从源码构建" ;;
  *) die "不支持的架构: $(uname -m)" ;;
esac

BINDIR="${PREFIX}/bin"
[ -d "$BINDIR" ] || mkdir -p "$BINDIR"
[ -w "$BINDIR" ] || die "${BINDIR} 不可写（PREFIX 是否正确？）"

command -v curl >/dev/null 2>&1 || die "未找到 curl（pkg install curl）"
command -v sha256sum >/dev/null 2>&1 || die "未找到 sha256sum（pkg install coreutils）"

# ---------------------------------------------------------------- 下载 + 校验
TMP="$(mktemp -d "${TMPDIR:-/tmp}/moxsh-proot.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

log "下载 ${ASSET}（${RELEASE}）…"
# --retry 3：Termux 移动网络抖动比桌面常见；-f 让 404 等也走失败分支。
curl -fSL --retry 3 --connect-timeout 15 \
  -o "${TMP}/${ASSET}" "${BASE_URL}/${ASSET}" \
  || die "下载失败：${BASE_URL}/${ASSET}
可能原因：网络不通 / Release 尚未发布（首次发布由 CI 推送 tag proot-v* 触发）。"

curl -fSL --retry 3 --connect-timeout 15 \
  -o "${TMP}/${ASSET}.sha256" "${BASE_URL}/${ASSET}.sha256" \
  || die "下载校验文件失败：${BASE_URL}/${ASSET}.sha256"

log "校验 sha256…"
# 校验文件内为 "<hash>  <文件名>"（无路径），cd 到 TMP 后按文件名核对。
( cd "$TMP" && sha256sum -c "${ASSET}.sha256" ) \
  || die "sha256 校验失败，二进制可能被截断或篡改，已中止安装。"

# ---------------------------------------------------------------- 安装
log "安装到 ${BINDIR}/moxsh-proot …"
# install -m 0755：原子落盘 + 可执行位，避免 cp 后忘记 chmod。
install -m 0755 "${TMP}/${ASSET}" "${BINDIR}/moxsh-proot"

# ---------------------------------------------------------------- 自检
log "自检 …"
# 1) 是 ELF 可执行（防下载到 HTML 错误页之类）。
head -c 4 "${BINDIR}/moxsh-proot" | grep -q $'\x7fELF' \
  || die "装好的不是 ELF 可执行文件，安装异常。"
# 2) --help 真实输出用法文本。先捕获再判（不能靠退出码管道——
#    本脚本 set -o pipefail，且不同版本 --help 的退出码约定可能变化）。
help_out="$("${BINDIR}/moxsh-proot" --help 2>&1 || true)"
printf '%s\n' "$help_out" | grep -q "moxsh-proot" \
  || die "自检失败：--help 未输出预期用法文本。"

log "安装完成：${BINDIR}/moxsh-proot"
cat <<EOF

用法速查（对齐上游 proot 常用选项）：
  moxsh-proot -R ~/ubuntu -0 -- bash          # 推荐绑定集 + fake root 进发行版
  moxsh-proot -r ~/ubuntu -w /root -- sh      # 最小模式，指定初始目录
  moxsh-proot -S ~/ubuntu -0 -- apt update    # 装包场景（最小绑定集）
  moxsh-proot --help                          # 完整用法

rootfs 未安装？用 proot-distro 装一个（引擎同一套）：
  moxsh-proot-distro install ubuntu
（或直接在 moxsh App 的图形化管理里点安装。）

有问题反馈：https://github.com/${REPO}/issues
EOF

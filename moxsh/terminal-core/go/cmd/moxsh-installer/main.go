// moxsh-installer 是 moxsh PRoot 引擎的 Go 安装器（bash 探测层之后的第二层）。
//
// 职责（用户定口径：bash 探测 + Go 安装器两层）：
//   - install：环境勘察（Termux/Linux、libc、架构、root/非 root）→ 自动选
//     安装路径与下载通道 → 双通道下载 → SHA-256 校验 → 落盘 → PATH 提示；
//   - update：检查远端新版本，向用户征求是否更新（不强制、不静默）；
//   - probe：只打印勘察报告（调试/验证用）。
//
// 与 bash 引导桩（scripts/install-moxsh-proot.sh）的环境变量约定一致：
//   - MOXSH_PROOT_REPO（默认 codecloud-dev/moxsh-terminal）
//   - MOXSH_PROOT_RELEASE（默认 proot-latest）
//   - MOXSH_PROOT_BASE_URL（覆盖主通道，指向国内镜像）
//   - MOXSH_PROOT_VERSION（update 检查用的版本号，默认同 RELEASE）
package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/codecloud-dev/moxsh-terminal/moxsh/terminal-core/go/internal/fetch"
	"github.com/codecloud-dev/moxsh-terminal/moxsh/terminal-core/go/internal/install"
	"github.com/codecloud-dev/moxsh-terminal/moxsh/terminal-core/go/internal/probe"
)

const (
	defaultRepo    = "codecloud-dev/moxsh-terminal"
	defaultRelease = "proot-latest"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "install":
		err = cmdInstall()
	case "update":
		err = cmdUpdate()
	case "probe":
		err = cmdProbe()
	case "version":
		fmt.Println(version)
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "moxsh-installer: %v\n", err)
		os.Exit(1)
	}
}

// version 由构建时 -ldflags "-X main.version=..." 注入；空则为 dev。
var version = "dev"

func usage() {
	fmt.Fprint(os.Stderr, `moxsh-installer — moxsh PRoot 引擎安装器（Go）

用法：
  moxsh-installer install   勘察环境并安装引擎
  moxsh-installer update    检查更新并征求是否升级
  moxsh-installer probe     只打印环境勘察报告

环境变量：
  MOXSH_PROOT_REPO        GitHub 仓库（默认 codecloud-dev/moxsh-terminal）
  MOXSH_PROOT_RELEASE     发布 tag（默认 proot-latest）
  MOXSH_PROOT_BASE_URL    覆盖下载主通道（国内镜像，布局需与 GitHub 一致）
  MOXSH_PROOT_VERSION     update 检查的版本号（默认同 RELEASE）
`)
}

// ===== 双语文案（用户口径：中英双语；LANG 含 zh 用中文） =====

type strings_ struct {
	zh bool
}

func (s strings_) pick(zh, en string) string {
	if s.zh {
		return zh
	}
	return en
}

func langStrings() strings_ {
	return strings_{zh: strings.Contains(os.Getenv("LANG"), "zh")}
}

// channels 下载通道列表（主 → 镜像），顺序即优先级。
func channels() []string {
	var ch []string
	if base := os.Getenv("MOXSH_PROOT_BASE_URL"); base != "" {
		ch = append(ch, base)
	}
	repo := envOr("MOXSH_PROOT_REPO", defaultRepo)
	release := envOr("MOXSH_PROOT_RELEASE", defaultRelease)
	ch = append(ch, fmt.Sprintf("https://github.com/%s/releases/download/%s", repo, release))
	return ch
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// ===== install =====

func cmdInstall() error {
	s := langStrings()
	// ldd 线索由外部探好传入（bash 层或本机 shell 直接跑时探一次）
	libcHint := probeLibcHint()
	rep := probe.Detect(libcHint)

	fmt.Println(s.pick(
		"== moxsh PRoot 引擎安装器 ==",
		"== moxsh PRoot engine installer =="))
	fmt.Printf("  %s %s | %s %s | %s | root=%v\n",
		s.pick("环境:", "env:"), rep.Env,
		s.pick("发行版:", "distro:"), rep.Distro,
		rep.Libc, rep.IsRoot)
	if rep.Env == probe.EnvLinux && rep.Version != "" {
		fmt.Printf("  %s %s\n", s.pick("版本:", "version:"), rep.Version)
	}

	home, _ := os.UserHomeDir()
	installRoot := rep.InstallPath(home)
	if rep.NeedsSudo() {
		fmt.Println(s.pick(
			"提示：系统级安装（/opt/moxsh）需要 root 权限；你也可以设置用户级安装。",
			"Note: system-wide install (/opt/moxsh) needs root; a user-level install is also available."))
	}

	// 资产映射：引擎两个 bin 各自对应本平台资产名
	assets := map[string]string{
		rep.AssetName():      "moxsh-proot",
		distroAssetName(rep): "moxsh-proot-distro",
	}
	staged := filepath.Join(os.TempDir(), "moxsh-installer-staged")
	os.MkdirAll(staged, 0o755)

	fmt.Println(s.pick("下载引擎二进制…", "Downloading engine binaries…"))
	var downloaded []string
	for asset := range assets {
		dest := filepath.Join(staged, asset)
		opts := fetch.Options{
			Channels: channels(),
			Asset:    asset,
			Dest:     dest,
			Timeout:  0,
			OnProgress: func(done, total int64) {
				if total > 0 {
					fmt.Printf("\r  %s: %.1f%%", asset, float64(done)*100/float64(total))
				}
			},
		}
		err := fetch.Download(opts)
		fmt.Println()
		if err != nil {
			if errors.Is(err, fetch.ErrChecksumMismatch) {
				return fmt.Errorf("%s", s.pick(
					"SHA-256 校验失败，已中止（放不下就不装，绝不静默）",
					"SHA-256 mismatch, aborted (never install silently)"))
			}
			// 单资产失败不致命：老版本发布可能只含一个 bin
			fmt.Fprintf(os.Stderr, "  %s %s: %v\n",
				s.pick("跳过", "skip"), asset, err)
			continue
		}
		downloaded = append(downloaded, asset)
	}
	if len(downloaded) == 0 {
		return fmt.Errorf("%s", s.pick(
			"所有下载通道均失败（可设 MOXSH_PROOT_BASE_URL 指向国内镜像）",
			"all download channels failed (set MOXSH_PROOT_BASE_URL for a mirror)"))
	}

	res, err := install.Place(installRoot, staged, assets)
	if err != nil {
		return err
	}
	_ = install.WriteInstalledVersion(installRoot, currentVersion())
	fmt.Println(s.pick(
		fmt.Sprintf("安装完成：%s", installRoot),
		fmt.Sprintf("Installed to: %s", installRoot)))
	for _, p := range res.Installed {
		fmt.Printf("  %s\n", p)
	}
	if rc, err := install.AppendPathToRc(res.BinDir); err == nil && rc != "" {
		fmt.Println(s.pick(
			fmt.Sprintf("已把 PATH 写入 ~/%s", rc),
			fmt.Sprintf("PATH written to ~/%s", rc)))
	}
	if res.PathHint != "" {
		fmt.Println(res.PathHint)
	}
	fmt.Println(s.pick(
		"试试：moxsh-proot-distro install alpine && moxsh-proot-distro login alpine",
		"Try: moxsh-proot-distro install alpine && moxsh-proot-distro login alpine"))
	return nil
}

// distroAssetName proot-distro 的资产名（与 moxsh-proot 同平台后缀）。
func distroAssetName(rep probe.Report) string {
	n := rep.AssetName()
	// moxsh-proot-linux-amd64[-musl] → moxsh-proot-distro-linux-amd64[-musl]
	return strings.Replace(n, "moxsh-proot-", "moxsh-proot-distro-", 1)
}

// probeLibcHint 探 ldd 输出头几行作为 libc 线索（探测失败返回空，由 FS 兜底）。
func probeLibcHint() string {
	// 常见 ldd 路径；Termux 的 ldd 是脚本，输出仍含 musl/glibc 字样
	for _, ldd := range []string{"/usr/bin/ldd", "/bin/ldd", "/data/data/com.termux/files/usr/bin/ldd"} {
		if _, err := os.Stat(ldd); err != nil {
			continue
		}
		out, err := runCapture(ldd, "--version")
		if err == nil && out != "" {
			return out
		}
	}
	return ""
}

// ===== update =====

func cmdUpdate() error {
	s := langStrings()
	home, _ := os.UserHomeDir()
	rep := probe.Detect(probeLibcHint())
	installRoot := rep.InstallPath(home)
	current := install.ReadInstalledVersion(installRoot)
	if current == "" {
		return fmt.Errorf("%s", s.pick(
			"未检测到已安装版本（VERSION 文件缺失），请先运行 install",
			"no installed version found, run install first"))
	}
	latest := currentVersion()
	if !install.NewerVersion(current, latest) {
		fmt.Println(s.pick(
			fmt.Sprintf("已是最新（当前 %s，远端 %s）", current, latest),
			fmt.Sprintf("up to date (local %s, remote %s)", current, latest)))
		return nil
	}
	// 征求是否更新（用户口径：更新征求，不强制不静默）
	fmt.Printf("%s\n", s.pick(
		fmt.Sprintf("发现新版本：%s → %s", current, latest),
		fmt.Sprintf("new version: %s -> %s", current, latest)))
	fmt.Print(s.pick("是否现在更新？[y/N] ", "Update now? [y/N] "))
	var answer string
	fmt.Scanln(&answer)
	if strings.ToLower(strings.TrimSpace(answer)) != "y" {
		fmt.Println(s.pick("已取消更新", "update canceled"))
		return nil
	}
	// 走 install 同链路（覆盖式）
	return cmdInstall()
}

// currentVersion 从环境变量解析远端版本号（CI/发布约定注入）。
func currentVersion() string {
	if v := os.Getenv("MOXSH_PROOT_VERSION"); v != "" {
		return v
	}
	if v := os.Getenv("MOXSH_PROOT_RELEASE"); v != "" && v != defaultRelease {
		return v
	}
	return "latest"
}

// ===== probe =====

func cmdProbe() error {
	rep := probe.Detect(probeLibcHint())
	home, _ := os.UserHomeDir()
	fmt.Printf("env=%s distro=%s(%s) libc=%s arch=%s goos=%s root=%v prefix=%s\n",
		rep.Env, rep.Distro, rep.Version, rep.Libc, rep.Arch, rep.GOOS, rep.IsRoot, rep.Prefix)
	fmt.Printf("install_path=%s needs_sudo=%v asset=%s\n",
		rep.InstallPath(home), rep.NeedsSudo(), rep.AssetName())
	return nil
}

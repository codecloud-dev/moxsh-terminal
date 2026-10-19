// Package probe 实现一键安装的环境勘察层（Termux/Android vs 正常 Linux、
// glibc/musl、CPU 架构、root/非 root），据此自动选择安装路径与下载源。
//
// 定位：bash 管道桩（scripts/install-moxsh-proot.sh）负责最小自举
// （探测 curl/tar、拉下本安装器），之后一切勘察决策都在本包内完成——
// 纯函数化设计，全部逻辑可 go test 覆盖，不依赖真实文件系统。
package probe

import (
	"os"
	"runtime"
	"strings"
)

// EnvKind 环境大类。
type EnvKind int

const (
	// EnvUnknown 无法识别（按正常 Linux 保守处理）。
	EnvUnknown EnvKind = iota
	// EnvTermux Termux / Android（$PREFIX 指向 /data/data/com.termux/files/usr）。
	EnvTermux
	// EnvLinux 正常 Linux 发行版（Debian/Ubuntu/Arch/Fedora…）。
	EnvLinux
	// EnvMacOS macOS（Homebrew 场景，社区请求再加，先识别不安装）。
	EnvMacOS
)

func (k EnvKind) String() string {
	switch k {
	case EnvTermux:
		return "termux"
	case EnvLinux:
		return "linux"
	case EnvMacOS:
		return "macos"
	default:
		return "unknown"
	}
}

// Libc libc 家族（决定下载哪个引擎二进制：glibc 动态 / musl 静态）。
type Libc int

const (
	// LibcUnknown 探测不到（按 glibc 保守处理）。
	LibcUnknown Libc = iota
	// LibcGlibc glibc（Debian/Ubuntu/Arch…）。
	LibcGlibc
	// LibcMusl musl（Alpine/部分 Termux 自定义 rootfs）。
	LibcMusl
)

func (l Libc) String() string {
	switch l {
	case LibcGlibc:
		return "glibc"
	case LibcMusl:
		return "musl"
	default:
		return "unknown"
	}
}

// Distro 正常 Linux 下的发行版（仅信息展示与换源提示用）。
type Distro int

const (
	DistroOther Distro = iota
	DistroDebian
	DistroUbuntu
	DistroArch
	DistroFedora
	DistroAlpine
)

func (d Distro) String() string {
	switch d {
	case DistroDebian:
		return "debian"
	case DistroUbuntu:
		return "ubuntu"
	case DistroArch:
		return "arch"
	case DistroFedora:
		return "fedora"
	case DistroAlpine:
		return "alpine"
	default:
		return "other"
	}
}

// Report 一次环境勘察的完整结果（纯数据，便于测试与序列化）。
type Report struct {
	Env     EnvKind
	Distro  Distro
	Libc    Libc
	Arch    string // runtime.GOARCH 口径：amd64 / arm64 / …
	GOOS    string // runtime.GOOS：linux / android
	IsRoot  bool
	Prefix  string // Termux 的 $PREFIX；非 Termux 为空
	Version string // 发行版版本（os-release VERSION_ID），仅展示
}

// InstallPath 返回引擎安装根目录。
//
// 规则（用户口径：root/非 root 依赖安装路径差异）：
//   - Termux：$PREFIX/opt/moxsh（不碰 HOME，Termux 的 $PREFIX 即用户数据区）；
//   - 正常 Linux + root：/opt/moxsh（系统级，多用户共享）；
//   - 正常 Linux + 非 root：~/.moxsh（用户级，免 sudo）。
func (r Report) InstallPath(home string) string {
	switch {
	case r.Env == EnvTermux && r.Prefix != "":
		return r.Prefix + "/opt/moxsh"
	case r.Env == EnvLinux && r.IsRoot:
		return "/opt/moxsh"
	default:
		// 非 root 的 Linux/macOS/unknown：用户级
		if home == "" {
			home = "/tmp"
		}
		return strings.TrimRight(home, "/") + "/.moxsh"
	}
}

// NeedsSudo 报告是否需要 sudo 提示。
//
// 语义：正常 Linux 的非 root 用户若想装系统级路径（/opt/moxsh）需要
// sudo（也可以改用用户级路径绕过）。不依赖 home——避免 home 兜底值
// 把系统级意图误判成用户级。
func (r Report) NeedsSudo() bool {
	return r.Env == EnvLinux && !r.IsRoot
}

// Detect 执行一次真实环境勘察（读 $PREFIX、os-release、ldd 输出由调用方传入）。
//
// libcHint：调用方探到的 libc 线索（如 ldd --version 输出的头几行），
// 传空则由 DetectLibcFromFS 兜底。
func Detect(libcHint string) Report {
	r := Report{
		GOOS:   runtime.GOOS,
		Arch:   runtime.GOARCH,
		IsRoot: os.Geteuid() == 0,
	}
	// 1) Termux/Android 判定：$PREFIX 指向 Android 数据区即 Termux
	prefix := os.Getenv("PREFIX")
	if prefix != "" && strings.Contains(prefix, "com.termux") {
		r.Env = EnvTermux
		r.Prefix = prefix
	}
	// 2) os-release：发行版识别（Termux 也读宿主 Android 的，无则跳过）
	if data, err := os.ReadFile("/etc/os-release"); err == nil {
		kv := parseOsRelease(string(data))
		id := kv["ID"]
		switch id {
		case "debian":
			r.Distro = DistroDebian
		case "ubuntu":
			r.Distro = DistroUbuntu
		case "arch", "archarm", "manjaro":
			r.Distro = DistroArch
		case "fedora":
			r.Distro = DistroFedora
		case "alpine":
			r.Distro = DistroAlpine
		}
		if r.Version == "" {
			r.Version = kv["VERSION_ID"]
		}
		if r.Env == EnvUnknown {
			// Android 没有 /etc/os-release（Termux 的 proot 里也没有），
			// 走到这里说明是正常 Linux。
			if runtime.GOOS == "linux" {
				r.Env = EnvLinux
			}
		}
	} else if r.Env == EnvUnknown && runtime.GOOS == "darwin" {
		r.Env = EnvMacOS
	}
	// 3) libc：优先调用方线索，其次文件系统兜底
	r.Libc = DetectLibc(libcHint)
	return r
}

// DetectLibcFromFS 文件系统兜底：Alpine 的 /etc/alpine-release、
// musl 的 ld-musl 路径、glibc 的 libc.so.6。
func DetectLibcFromFS() Libc {
	if _, err := os.Stat("/etc/alpine-release"); err == nil {
		return LibcMusl
	}
	if _, err := os.Stat("/lib/ld-musl-x86_64.so.1"); err == nil {
		return LibcMusl
	}
	if _, err := os.Stat("/lib/ld-musl-aarch64.so.1"); err == nil {
		return LibcMusl
	}
	if _, err := os.Stat("/lib/x86_64-linux-gnu/libc.so.6"); err == nil {
		return LibcGlibc
	}
	if _, err := os.Stat("/lib/aarch64-linux-gnu/libc.so.6"); err == nil {
		return LibcGlibc
	}
	return LibcUnknown
}

// DetectLibc 综合 libc 线索与文件系统探测。
//
// libcHint 通常是 `ldd --version` 的头几行文本：musl 输出形如
// "musl libc (x86_64)\nVersion 1.2.5..."，glibc 输出 "ldd (Ubuntu GLIBC ...)"。
func DetectLibc(libcHint string) Libc {
	low := strings.ToLower(libcHint)
	if strings.Contains(low, "musl") {
		return LibcMusl
	}
	if strings.Contains(low, "glibc") || strings.Contains(low, "gnu libc") {
		return LibcGlibc
	}
	return DetectLibcFromFS()
}

// parseOsRelease 解析 os-release 的 KEY=VALUE（去引号、忽略注释）。
func parseOsRelease(s string) map[string]string {
	out := map[string]string{}
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq < 0 {
			continue
		}
		k := strings.TrimSpace(line[:eq])
		v := strings.TrimSpace(line[eq+1:])
		v = strings.Trim(v, `"`)
		v = strings.Trim(v, `'`)
		out[k] = v
	}
	return out
}

// AssetName 引擎二进制的发布资产名（与 Rust CI/发布命名约定一致）。
//
// 约定：moxsh-proot-<goos>-<arch>[-musl]
//   - glibc 动态版：moxsh-proot-linux-amd64（依赖 glibc，体积小）
//   - musl 静态版：moxsh-proot-linux-amd64-musl（零依赖，Termux 首选）
func (r Report) AssetName() string {
	base := "moxsh-proot-" + r.GOOS + "-" + r.Arch
	if r.Libc == LibcMusl {
		base += "-musl"
	}
	return base
}

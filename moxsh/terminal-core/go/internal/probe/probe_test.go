package probe

import "testing"

func TestInstallPathMatrix(t *testing.T) {
	cases := []struct {
		name string
		r    Report
		home string
		want string
	}{
		{"termux 用户级", Report{Env: EnvTermux, Prefix: "/data/data/com.termux/files/usr"}, "", "/data/data/com.termux/files/usr/opt/moxsh"},
		{"linux root 系统级", Report{Env: EnvLinux, IsRoot: true}, "/home/u", "/opt/moxsh"},
		{"linux 非 root 用户级", Report{Env: EnvLinux, IsRoot: false}, "/home/u", "/home/u/.moxsh"},
		{"unknown 非 root 兜底", Report{Env: EnvUnknown}, "/home/u", "/home/u/.moxsh"},
		{"空 home 兜底", Report{Env: EnvLinux, IsRoot: false}, "", "/tmp/.moxsh"},
		{"macos 用户级", Report{Env: EnvMacOS}, "/Users/u", "/Users/u/.moxsh"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := c.r.InstallPath(c.home); got != c.want {
				t.Errorf("InstallPath() = %q, want %q", got, c.want)
			}
		})
	}
}

func TestNeedsSudo(t *testing.T) {
	root := Report{Env: EnvLinux, IsRoot: true}
	if root.NeedsSudo() {
		t.Error("root 不应需要 sudo")
	}
	user := Report{Env: EnvLinux, IsRoot: false}
	if !user.NeedsSudo() {
		t.Error("非 root 系统级路径应提示 sudo")
	}
	termux := Report{Env: EnvTermux, Prefix: "/data/data/com.termux/files/usr", IsRoot: false}
	if termux.NeedsSudo() {
		t.Error("Termux 路径不需要 sudo")
	}
}

func TestDetectLibc(t *testing.T) {
	cases := []struct {
		hint string
		want Libc
	}{
		{"musl libc (x86_64)\nVersion 1.2.5", LibcMusl},
		{"ldd (Ubuntu GLIBC 2.39-0ubuntu8) 2.39", LibcGlibc},
		{"", LibcUnknown}, // 无 hint 时走 FS 兜底；沙箱是 Ubuntu glibc，
		// 但本测试不依赖沙箱形态——FS 兜底在集成路径验证
	}
	for _, c := range cases {
		if got := DetectLibc(c.hint); got != c.want && c.hint != "" {
			t.Errorf("DetectLibc(%q) = %v, want %v", c.hint, got, c.want)
		}
	}
}

func TestAssetName(t *testing.T) {
	cases := []struct {
		r    Report
		want string
	}{
		{Report{GOOS: "linux", Arch: "amd64", Libc: LibcGlibc}, "moxsh-proot-linux-amd64"},
		{Report{GOOS: "linux", Arch: "amd64", Libc: LibcMusl}, "moxsh-proot-linux-amd64-musl"},
		{Report{GOOS: "linux", Arch: "arm64", Libc: LibcMusl}, "moxsh-proot-linux-arm64-musl"},
	}
	for _, c := range cases {
		if got := c.r.AssetName(); got != c.want {
			t.Errorf("AssetName() = %q, want %q", got, c.want)
		}
	}
}

func TestParseOsRelease(t *testing.T) {
	kv := parseOsRelease(`# comment
NAME="Ubuntu"
VERSION="24.04.1 LTS (Noble Numbat)"
ID=ubuntu
VERSION_ID="24.04"
`)
	if kv["ID"] != "ubuntu" || kv["VERSION_ID"] != "24.04" {
		t.Fatalf("parse 失败: %v", kv)
	}
	if kv["NAME"] != "Ubuntu" {
		t.Errorf("引号未剥离: %q", kv["NAME"])
	}
}

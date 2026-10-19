package install

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNewerVersion(t *testing.T) {
	cases := []struct {
		cur, latest string
		want        bool
	}{
		{"1.0.0", "1.0.1", true},
		{"1.0.0", "1.1.0", true},
		{"1.0.0", "2.0.0", true},
		{"1.2.3", "1.2.3", false},
		{"1.2.3", "1.2.2", false},
		{"2.0.0", "1.9.9", false},
		{"v1.0.0", "v1.0.1", true},   // v 前缀
		{"1.0.0", "1.0.1-rc1", true}, // 预发布按序号
		{"1.0", "1.0.1", true},       // 两段式
		{"dev", "0.1.0", true},       // dev 视同 0.0.0
		{"", "0.1.0", true},
		{"1.0.0", "", false},  // 空 latest 不提示
		{"abc", "xyz", false}, // 都解析不出：不提示
	}
	for _, c := range cases {
		if got := NewerVersion(c.cur, c.latest); got != c.want {
			t.Errorf("NewerVersion(%q, %q) = %v, want %v", c.cur, c.latest, got, c.want)
		}
	}
}

func TestVersionRoundTrip(t *testing.T) {
	dir := t.TempDir()
	if err := WriteInstalledVersion(dir, "1.2.3"); err != nil {
		t.Fatal(err)
	}
	if got := ReadInstalledVersion(dir); got != "1.2.3" {
		t.Errorf("读回版本 = %q", got)
	}
	// 空版本写 dev
	if err := WriteInstalledVersion(dir, ""); err != nil {
		t.Fatal(err)
	}
	if got := ReadInstalledVersion(dir); got != "dev" {
		t.Errorf("空版本应写 dev, got %q", got)
	}
}

func TestPlaceAndAppendPath(t *testing.T) {
	root := t.TempDir()
	staged := filepath.Join(root, "staged")
	os.MkdirAll(staged, 0o755)
	os.WriteFile(filepath.Join(staged, "moxsh-proot-linux-amd64"), []byte("fake-bin-1"), 0o644)
	os.WriteFile(filepath.Join(staged, "moxsh-proot-distro-linux-amd64"), []byte("fake-bin-2"), 0o644)

	installRoot := filepath.Join(root, "opt", "moxsh")
	res, err := Place(installRoot, staged, map[string]string{
		"moxsh-proot-linux-amd64":        "moxsh-proot",
		"moxsh-proot-distro-linux-amd64": "moxsh-proot-distro",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Installed) != 2 {
		t.Fatalf("应安装 2 个二进制, got %d", len(res.Installed))
	}
	// 权限 0755
	for _, p := range res.Installed {
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatal(err)
		}
		if fi.Mode().Perm()&0o100 == 0 {
			t.Errorf("%s 无可执行位: %v", p, fi.Mode())
		}
	}
	// 空资产映射 → 报错
	if _, err := Place(installRoot, staged, map[string]string{"nonexistent": "x"}); err == nil {
		t.Error("无可安装资产应报错")
	}
}

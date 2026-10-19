package fetch

import (
	"errors"
	"testing"
)

func TestParseChecksumText(t *testing.T) {
	valid := "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
	cases := []struct {
		in      string
		want    string
		wantErr bool
	}{
		{valid, valid, false},
		{valid + "  moxsh-proot-linux-amd64", valid, false}, // sha256sum 格式
		{"  " + valid + "\n", valid, false},                 // 空白与换行
		{"A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5F6A1B2", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2", false},
		{"", "", true},
		{"short", "", true},
		{valid[:63], "", true},
		{valid[:63] + "g", "", true}, // 非 hex 字符
	}
	for _, c := range cases {
		got, err := ParseChecksumText(c.in)
		if c.wantErr {
			if err == nil {
				t.Errorf("ParseChecksumText(%q) 应报错", c.in)
			}
			continue
		}
		if err != nil {
			t.Errorf("ParseChecksumText(%q) 意外错误: %v", c.in, err)
			continue
		}
		if got != c.want {
			t.Errorf("ParseChecksumText(%q) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestURLBuilders(t *testing.T) {
	if got := AssetURL("https://example.com/base/", "asset"); got != "https://example.com/base/asset" {
		t.Errorf("AssetURL = %q", got)
	}
	if got := ChecksumURL("https://example.com/base", "asset"); got != "https://example.com/base/asset.sha256" {
		t.Errorf("ChecksumURL = %q", got)
	}
}

func TestDownloadValidatesArgs(t *testing.T) {
	if err := Download(Options{}); err == nil {
		t.Error("空选项应报错")
	}
	if err := Download(Options{Channels: []string{"https://x"}}); err == nil {
		t.Error("缺资产名/目标应报错")
	}
}

// TestErrIdentity 错误哨兵可被 errors.Is 识别（调用方据此区分"全失败"与"校验失败"）。
func TestErrIdentity(t *testing.T) {
	if !errors.Is(ErrChecksumMismatch, ErrChecksumMismatch) {
		t.Fatal("ErrChecksumMismatch 不可识别")
	}
	if !errors.Is(ErrAllChannelsFailed, ErrAllChannelsFailed) {
		t.Fatal("ErrAllChannelsFailed 不可识别")
	}
}

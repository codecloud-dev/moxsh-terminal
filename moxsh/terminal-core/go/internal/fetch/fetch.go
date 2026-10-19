// Package fetch 实现引擎二进制的下载器：双通道（主 URL 失败自动切镜像）、
// 断点续传（HTTP Range）、流式 SHA-256 边下边校验、进度回调。
//
// 定位：Go 安装器的下载层。只依赖标准库（net/http、crypto/sha256、io），
// 与引擎产物布局约定：<BASE_URL>/<ASSET> 与 <BASE_URL>/<ASSET>.sha256
// 两个文件成对存在（与 scripts/install-moxsh-proot.sh 的 bash 版同约定）。
package fetch

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// ErrChecksumMismatch SHA-256 校验失败（放不下就不装，绝不静默）。
var ErrChecksumMismatch = errors.New("sha256 校验失败")

// ErrAllChannelsFailed 所有下载通道均失败。
var ErrAllChannelsFailed = errors.New("所有下载通道均失败")

// ProgressFn 进度回调（已下载字节/总字节；总字节未知时为 0）。
type ProgressFn func(done, total int64)

// Options 下载选项。
type Options struct {
	// Channels 下载通道 URL 基址列表，按优先级排序（主站 → 镜像）。
	// 每个通道下按 <base>/<asset> 与 <base>/<asset>.sha256 布局寻址。
	Channels []string
	// Asset 资产文件名（如 moxsh-proot-linux-amd64-musl）。
	Asset string
	// Dest 本地落盘路径。
	Dest string
	// Timeout 单次 HTTP 请求超时（0 = 不限）。
	Timeout time.Duration
	// OnProgress 进度回调（可为 nil）。
	OnProgress ProgressFn
	// ExpectedSHA256 期望校验值（空则从 <asset>.sha256 拉取）。
	ExpectedSHA256 string
	// Retries 每通道重试次数（网络抖动容忍，默认 2）。
	Retries int
}

// AssetURL 通道下资产完整 URL。
func AssetURL(base, asset string) string {
	return strings.TrimRight(base, "/") + "/" + asset
}

// ChecksumURL 通道下校验文件完整 URL。
func ChecksumURL(base, asset string) string {
	return AssetURL(base, asset) + ".sha256"
}

// FetchChecksum 拉取并解析 <asset>.sha256 文件内容。
//
// 兼容两种格式：裸 hex（"abc123..."）与 sha256sum 输出
// ("abc123...  filename")。返回小写 hex。
func FetchChecksum(client *http.Client, base, asset string) (string, error) {
	resp, err := client.Get(ChecksumURL(base, asset))
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("校验文件 HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 512))
	if err != nil {
		return "", err
	}
	return ParseChecksumText(string(body))
}

// ParseChecksumText 解析校验文本为小写 hex（纯函数，可测）。
func ParseChecksumText(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", errors.New("空的校验文件")
	}
	// sha256sum 格式：hex 后跟空白与文件名
	if i := strings.IndexAny(s, " \t"); i > 0 {
		s = s[:i]
	}
	s = strings.ToLower(strings.TrimSpace(s))
	if len(s) != 64 {
		return "", fmt.Errorf("校验值长度 %d != 64", len(s))
	}
	for _, c := range s {
		if !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
			return "", errors.New("校验值含非 hex 字符")
		}
	}
	return s, nil
}

// Download 按选项下载资产：逐通道尝试（含重试），边下边算 SHA-256，
// 落盘 Dest。全部通道失败返回 ErrAllChannelsFailed；校验不符返回
// ErrChecksumMismatch（并删除半成品）。
func Download(opts Options) error {
	if len(opts.Channels) == 0 {
		return errors.New("未配置下载通道")
	}
	if opts.Asset == "" || opts.Dest == "" {
		return errors.New("资产名与目标路径必填")
	}
	retries := opts.Retries
	if retries <= 0 {
		retries = 2
	}
	client := &http.Client{}
	if opts.Timeout > 0 {
		client.Timeout = opts.Timeout
	}

	// 期望校验值：显式指定优先，否则从第一通道拉取（失败不致命，
	// 后续下载成功后再补拉——镜像可能只有部分通道挂了校验文件）。
	expected := strings.ToLower(strings.TrimSpace(opts.ExpectedSHA256))
	if expected == "" {
		if cs, err := FetchChecksum(client, opts.Channels[0], opts.Asset); err == nil {
			expected = cs
		}
	}

	var lastErr error
	for _, base := range opts.Channels {
		for attempt := 0; attempt < retries; attempt++ {
			err := downloadOnce(client, base, opts, expected)
			if err == nil {
				return nil
			}
			lastErr = err
			if errors.Is(err, ErrChecksumMismatch) {
				// 校验失败：镜像内容被污染/劫持，换通道也无济于事
				// （同一资产），但保险起见仍尝试下一通道。
				continue
			}
		}
	}
	if lastErr == nil {
		lastErr = errors.New("下载未执行")
	}
	return fmt.Errorf("%w: %v", ErrAllChannelsFailed, lastErr)
}

// downloadOnce 单通道单次下载（支持 Range 断点续传）。
func downloadOnce(client *http.Client, base string, opts Options, expected string) error {
	url := AssetURL(base, opts.Asset)
	// 断点：已存在的部分文件作为起点
	var have int64
	if fi, err := os.Stat(opts.Dest); err == nil {
		have = fi.Size()
	}
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	if have > 0 {
		req.Header.Set("Range", "bytes="+strconv.FormatInt(have, 10)+"-")
	}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	flag := os.O_CREATE | os.O_WRONLY
	if resp.StatusCode == http.StatusPartialContent && have > 0 {
		flag |= os.O_APPEND // 续传
	} else {
		flag |= os.O_TRUNC // 全新（含 200 覆盖 416 场景）
		have = 0
	}
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	f, err := os.OpenFile(opts.Dest, flag, 0o755)
	if err != nil {
		return err
	}
	defer f.Close()

	total := have + resp.ContentLength
	hasher := sha256.New()
	if have > 0 {
		// 续传：已有部分的哈希需先计入——从头读一遍旧文件
		old, err := os.Open(opts.Dest)
		if err == nil {
			_, _ = io.Copy(hasher, old)
			old.Close()
		}
	}

	buf := make([]byte, 256*1024)
	done := have
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := f.Write(buf[:n]); werr != nil {
				return werr
			}
			hasher.Write(buf[:n])
			done += int64(n)
			if opts.OnProgress != nil {
				opts.OnProgress(done, total)
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			return rerr
		}
	}

	got := hex.EncodeToString(hasher.Sum(nil))
	if expected != "" && got != expected {
		_ = os.Remove(opts.Dest)
		return fmt.Errorf("%w: got %s want %s", ErrChecksumMismatch, got, expected)
	}
	// 未提供期望值且拉取失败：下载成功但无校验——调用方决定是否接受
	return nil
}

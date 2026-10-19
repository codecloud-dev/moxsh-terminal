// Package install 实现引擎二进制的落盘安装：路径决策、权限、PATH 提示、
// shell rc 挂载、更新征求（用户口径：安装脚本带更新策略、可征求是否更新）。
package install

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Result 一次安装的结果。
type Result struct {
	// InstallRoot 安装根目录（如 /opt/moxsh 或 ~/.moxsh）。
	InstallRoot string
	// BinDir 可执行文件目录（InstallRoot/bin）。
	BinDir string
	// Installed 落盘的二进制路径列表。
	Installed []string
	// PathHint 是否需要把 BinDir 加入 PATH（未配置时给出提示文案）。
	PathHint string
}

// BinNames 引擎产出的可执行文件（与 Rust bin 目标对应）。
var BinNames = []string{"moxsh-proot", "moxsh-proot-distro"}

// Place 把已下载的二进制（staged 目录内，文件名即资产名）安装到
// installRoot：staged/<asset> → installRoot/bin/<binname>。
//
// assetToBin 资产名到二进制名的映射（如 moxsh-proot-linux-amd64-musl
// → moxsh-proot）。权限 0755。已存在则覆盖（更新路径）。
func Place(installRoot, stagedDir string, assetToBin map[string]string) (*Result, error) {
	binDir := filepath.Join(installRoot, "bin")
	if err := os.MkdirAll(binDir, 0o755); err != nil {
		return nil, fmt.Errorf("创建 bin 目录失败: %w", err)
	}
	res := &Result{InstallRoot: installRoot, BinDir: binDir}
	for asset, binName := range assetToBin {
		src := filepath.Join(stagedDir, asset)
		if _, err := os.Stat(src); err != nil {
			continue // 该资产未下载（可选资产），跳过
		}
		dst := filepath.Join(binDir, binName)
		data, err := os.ReadFile(src)
		if err != nil {
			return nil, fmt.Errorf("读取 %s 失败: %w", asset, err)
		}
		if err := os.WriteFile(dst, data, 0o755); err != nil {
			return nil, fmt.Errorf("写入 %s 失败: %w", dst, err)
		}
		res.Installed = append(res.Installed, dst)
	}
	if len(res.Installed) == 0 {
		return nil, fmt.Errorf("无可安装的资产（staged=%s）", stagedDir)
	}
	res.PathHint = PathHint(binDir)
	return res, nil
}

// PathHint 生成 PATH 配置提示（检测常见 shell rc 是否已含 binDir）。
func PathHint(binDir string) string {
	home, _ := os.UserHomeDir()
	if home == "" {
		return ""
	}
	for _, rc := range []string{".bashrc", ".zshrc", ".profile"} {
		p := filepath.Join(home, rc)
		data, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		if strings.Contains(string(data), binDir) {
			return "" // 已配置
		}
	}
	return fmt.Sprintf("把 %s 加入 PATH：echo 'export PATH=\"%s:$PATH\"' >> ~/.bashrc", binDir, binDir)
}

// AppendPathToRc 把 binDir 写入用户 shell rc（幂等：已含则跳过）。
// 返回写入了哪个 rc（空 = 无需写/已配置）。
func AppendPathToRc(binDir string) (string, error) {
	home, _ := os.UserHomeDir()
	if home == "" {
		return "", nil
	}
	line := fmt.Sprintf("\n# moxsh engine PATH\nexport PATH=\"%s:$PATH\"\n", binDir)
	for _, rc := range []string{".bashrc", ".zshrc", ".profile"} {
		p := filepath.Join(home, rc)
		data, err := os.ReadFile(p)
		if err != nil {
			continue // rc 不存在则试下一个
		}
		if strings.Contains(string(data), binDir) {
			return "", nil
		}
		if err := os.WriteFile(p, append(data, []byte(line)...), 0o644); err != nil {
			return "", err
		}
		return rc, nil
	}
	return "", nil
}

// VersionFile 安装根下的版本记录文件名。
const VersionFile = "VERSION"

// WriteInstalledVersion 记录已安装版本（更新检查的基准）。
// 传空串写 "dev"（本地构建产物）。
func WriteInstalledVersion(installRoot, version string) error {
	if version == "" {
		version = "dev"
	}
	return os.WriteFile(filepath.Join(installRoot, VersionFile), []byte(version+"\n"), 0o644)
}

// ReadInstalledVersion 读已安装版本（无记录返回空串）。
func ReadInstalledVersion(installRoot string) string {
	data, err := os.ReadFile(filepath.Join(installRoot, VersionFile))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}

// NewerVersion 比较版本号（语义化三段 x.y.z 的数值比较，非 semver 库依赖）。
//
// 返回 true 表示 latest 比 current 新。无法解析时任一段按 0 计；
// 两串相等或无法区分时返回 false（不无脑提示更新）。
func NewerVersion(current, latest string) bool {
	cur := parseTriple(current)
	new := parseTriple(latest)
	if cur == new {
		return false
	}
	return compareTriple(new, cur) > 0
}

// parseTriple "v1.2.3" / "1.2" / "1.2.3-rc1" → [3]int（非数字段记 0）。
func parseTriple(s string) [3]int {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	if i := strings.IndexAny(s, "-+"); i >= 0 {
		s = s[:i] // 去预发布/构建后缀（保守：rc 视同正式版序号）
	}
	parts := strings.Split(s, ".")
	var out [3]int
	for i := 0; i < 3 && i < len(parts); i++ {
		n := 0
		fmt.Sscanf(parts[i], "%d", &n)
		if n < 0 {
			n = 0
		}
		out[i] = n
	}
	return out
}

func compareTriple(a, b [3]int) int {
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			if a[i] < b[i] {
				return -1
			}
			return 1
		}
	}
	return 0
}

package main

import (
	"os/exec"
	"strings"
)

// runCapture 执行命令并捕获合并输出（stdout+stderr），限长 4KB。
// ldd --version 的 musl/glibc 字样在前几行即出现，截断无碍。
func runCapture(name string, args ...string) (string, error) {
	cmd := exec.Command(name, args...)
	out, err := cmd.CombinedOutput()
	if len(out) > 4096 {
		out = out[:4096]
	}
	return strings.TrimSpace(string(out)), err
}

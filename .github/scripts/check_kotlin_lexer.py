#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
moxsh 源码静态自检（防回归）。

起因：Kotlin 的块注释在词法阶段遇到注释文本里的 "/*" 会被当作（嵌套的）注释起始符，
从而把后续真实代码整段吞掉，直到文件结束报 "Unclosed comment" 并连带一堆
"Unresolved reference"。这类错误在编辑器里肉眼看不出来，只在 CI 编译时爆炸，
排查成本极高。本脚本按【嵌套块注释】语义扫描全部 .kt/.kts 源文件，提前拦截。

同时检查：
  - 文件含 BOM（会导致第一个声明解析异常）
  - 行内混有 CRLF（跨平台协作时的换行不一致）
  - 出现裸控制字符（不可见字符，常见于误粘贴转义序列）

用法：python3 check_kotlin_lexer.py <源码根目录> [更多目录...]
退出码：0 = 通过；1 = 发现问题
"""

import os
import sys

# 扫描时跳过的目录
SKIP_DIRS = {'.git', 'build', '.gradle', '.cxx', 'cxx', 'target', 'node_modules'}


def scan_file(path):
    """返回该文件的问题列表（字符串数组）。"""
    problems = []
    with open(path, 'rb') as fh:
        raw = fh.read()

    if raw.startswith(b'\xef\xbb\xbf'):
        problems.append('文件带 UTF-8 BOM，可能导致首个声明解析失败')

    try:
        src = raw.decode('utf-8')
    except UnicodeDecodeError as exc:
        problems.append('不是合法 UTF-8：%s' % exc)
        return problems

    if b'\r\n' in raw:
        problems.append('含 CRLF 换行（仓库统一使用 LF）')

    for lineno, line in enumerate(src.split('\n'), 1):
        # 裸控制字符（允许制表符）
        for ch in line:
            if ord(ch) < 32 and ch != '\t':
                problems.append('第 %d 行含裸控制字符 U+%04X' % (lineno, ord(ch)))
                break

    # ---- 嵌套块注释语义扫描 ----
    length = len(src)
    i = 0
    depth = 0
    in_str = False
    in_char = False
    stack = []

    while i < length:
        c = src[i]
        nxt = src[i + 1] if i + 1 < length else ''

        if depth == 0:
            if not in_str and not in_char:
                if c == '"':
                    in_str = True
                    i += 1
                    continue
                if c == "'":
                    in_char = True
                    i += 1
                    continue
                if c == '/' and nxt == '/':          # 行注释
                    while i < length and src[i] != '\n':
                        i += 1
                    continue
                if c == '/' and nxt == '*':          # 块注释开启
                    depth += 1
                    stack.append(i)
                    i += 2
                    continue
            if in_str and c == '\\':
                i += 2
                continue
            if in_str and c == '"':
                in_str = False
            if in_char and c == '\\':
                i += 2
                continue
            if in_char and c == "'":
                in_char = False
            i += 1
        else:
            if c == '/' and nxt == '*':              # 注释内的 "/*"：Kotlin 会嵌套计数
                depth += 1
                stack.append(i)
                i += 2
                continue
            if c == '*' and nxt == '/':
                depth -= 1
                if stack:
                    stack.pop()
                i += 2
                continue
            i += 1

    if depth != 0:
        start = stack[0] if stack else 0
        lineno = src.count('\n', 0, start) + 1
        bol = src.rfind('\n', 0, start) + 1
        col = start - bol + 1
        snippet = src[start:start + 60].replace('\n', '\\n')
        problems.append(
            '块注释未闭合（残留嵌套层数 %d），起始于 %d:%d —— 注释文本里的 "/*" '
            '会被 Kotlin 当作新的注释起始符并吞掉后续代码。片段：%s'
            % (depth, lineno, col, snippet)
        )

    return problems


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2

    roots = sys.argv[1:]
    total = 0
    fails = 0

    for root in roots:
        if not os.path.isdir(root):
            print('[跳过] 目录不存在：%s' % root)
            continue
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for fn in filenames:
                if not (fn.endswith('.kt') or fn.endswith('.kts')):
                    continue
                path = os.path.join(dirpath, fn)
                total += 1
                problems = scan_file(path)
                if problems:
                    fails += 1
                    print('[FAIL] %s' % path)
                    for p in problems:
                        print('         - %s' % p)

    print('\n扫描 %d 个 Kotlin 源文件，发现 %d 个问题文件。' % (total, fails))
    if fails:
        print('>>> 静态自检未通过')
        return 1
    print('>>> 静态自检通过')
    return 0


if __name__ == '__main__':
    sys.exit(main())

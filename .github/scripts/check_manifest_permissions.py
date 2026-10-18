#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
moxsh AndroidManifest 权限声明自检（防回归）。

起因（真实事故）：plugin-api 曾直接声明
    <permission android:name="com.termux.permission.RUN_COMMAND"
                android:protectionLevel="signature" />
想借此"兼容 Termux 的 RUN_COMMAND"。但 Android 的权限定义是**全局唯一、按名字认主**
的：Termux 官方已声明同名权限（protectionLevel=dangerous），两边签名必然不同，
PackageManager 直接拒绝安装：
    INSTALL_FAILED_DUPLICATE_PERMISSION
用户实测反馈「与 termux 权限冲突无法安装了」。反向同样致命：先装 moxsh 再装 Termux，
Termux 也会装不上。

规则：
  1. 【error】禁止 <permission android:name="..."> 声明不属于本应用命名空间的权限名。
     声明他包权限 = 抢注，必然与对方冲突。要引用别人的权限请用 <uses-permission>。
  2. 【error】同一清单内同名 <permission> 重复声明。
  3. 【warn】组件 android:permission / <uses-permission> 引用了非 android.* 的自定义
     权限，但本仓清单里没有对应声明——该权限若由未安装的第三方提供，组件会静默
     不可访问（不崩、不报错，只是永远调不通），属于难查的哑失效。

用法：python3 check_manifest_permissions.py <源码根目录> [--namespace com.moxsh]
退出码：0 = 通过（允许仅有 warn）；1 = 存在 error
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = 'http://schemas.android.com/apk/res/android'
SKIP_DIRS = {'.git', 'build', '.gradle', '.cxx', 'cxx', 'target', 'node_modules'}

# <permission ...> / <permission-s ...>（不含 uses-permission，正则用负向断言排除）
PERMISSION_RE = re.compile(r'<permission(?![-\w])([^>]*)>', re.DOTALL)
USES_PERMISSION_RE = re.compile(r'<uses-permission(?![-\w])([^>]*)>', re.DOTALL)
NAME_RE = re.compile(r'android:name\s*=\s*"([^"]+)"')


def android_name(attrs):
    m = NAME_RE.search(attrs)
    return m.group(1) if m else None


def line_of(text, pos):
    return text.count('\n', 0, pos) + 1


def collect_manifests(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            if fn == 'AndroidManifest.xml':
                out.append(os.path.join(dirpath, fn))
    return sorted(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    if not args:
        print('用法：python3 check_manifest_permissions.py <源码根目录> [--namespace com.moxsh]')
        return 2

    root = args[0]
    namespace = 'com.moxsh'
    for a in sys.argv[1:]:
        if a.startswith('--namespace'):
            namespace = a.split('=', 1)[1] if '=' in a else namespace
    ns_prefix = namespace.rstrip('.') + '.'

    manifests = collect_manifests(root)
    if not manifests:
        print('未找到任何 AndroidManifest.xml（检查路径：%s）' % root)
        return 2

    errors = []
    warns = []

    # 第一遍：收集本仓声明的自有权限（跨 module 合并后同属一个 APK）
    declared = {}   # perm name -> [(file, line)]
    for path in manifests:
        with open(path, encoding='utf-8') as fh:
            text = fh.read()
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append('%s: XML 不合法：%s' % (path, exc))
            continue
        for m in PERMISSION_RE.finditer(text):
            name = android_name(m.group(1))
            if not name:
                errors.append('%s:%d <permission> 缺少 android:name'
                              % (path, line_of(text, m.start())))
                continue
            declared.setdefault(name, []).append((path, line_of(text, m.start())))

    # 第二遍：规则校验
    for path, entries in declared.items():
        if len(entries) > 1:
            where = ', '.join('%s:%d' % e for e in entries)
            errors.append('权限 %s 被重复声明（%s）—— 合并后会冲突' % (path, where))
    for perm, entries in declared.items():
        if perm.startswith('android.permission.') or perm.startswith(ns_prefix):
            continue
        for file, line in entries:
            errors.append(
                '%s:%d 抢注了外部权限名 %s —— Android 权限定义全局唯一，'
                '与已安装的同名权限（如 Termux 的 com.termux.permission.RUN_COMMAND）'
                '签名不同会直接 INSTALL_FAILED_DUPLICATE_PERMISSION。'
                '请改用自有名 %s 或在 %s 下另起名字，引用他人权限用 <uses-permission>。'
                % (file, line, perm, ns_prefix + perm.rsplit('.', 1)[-1], ns_prefix.rstrip('.'))
            )

    # 规则 3：自定义权限引用但无声明（可能哑失效）
    for path in manifests:
        with open(path, encoding='utf-8') as fh:
            text = fh.read()
        for m in USES_PERMISSION_RE.finditer(text):
            name = android_name(m.group(1))
            if not name or name.startswith('android.permission.'):
                continue
            if name not in declared:
                warns.append('%s:%d 引用未声明的自定义权限 %s（若提供方未安装，'
                             '依赖它的组件会静默不可访问）'
                             % (path, line_of(text, m.start()), name))

    for w in warns:
        print('WARN  %s' % w)
    for e in errors:
        print('ERROR %s' % e)

    if errors:
        print('\n自检失败：%d 个 error，%d 个 warn（检查 %d 个清单）'
              % (len(errors), len(warns), len(manifests)))
        return 1
    print('自检通过：%d 个清单，%d 个 warn' % (len(manifests), len(warns)))
    return 0


if __name__ == '__main__':
    sys.exit(main())

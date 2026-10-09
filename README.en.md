<p align="center">
  <img src="assets/logo.svg" width="128" alt="moxsh liquid-glass logo">
</p>

<h1 align="center">moxsh</h1>

<p align="center">
  <img src="https://img.shields.io/github/actions/workflow/status/codecloud-dev/moxsh-terminal/build.yml?branch=main&label=CI%20Build&color=8a7bff" alt="CI Build">
  <img src="https://img.shields.io/github/v/release/codecloud-dev/moxsh-terminal?label=Latest&color=37d5d3" alt="Latest">
  <img src="https://img.shields.io/github/stars/codecloud-dev/moxsh-terminal?style=social" alt="GitHub Stars">
  <img src="https://img.shields.io/github/discussions/codecloud-dev/moxsh-terminal?label=Discussions&color=ff7ac3" alt="Discussions">
  <img src="https://img.shields.io/badge/version-1.0.0-8a7bff" alt="version">
  <img src="https://img.shields.io/badge/license-GPL--3.0-37d5d3" alt="license">
  <img src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white" alt="Android 9+">
  <img src="https://img.shields.io/badge/Core-Rust-000?logo=rust&logoColor=white" alt="Rust core">
  <img src="https://img.shields.io/badge/UI-Liquid%20Glass-8a7bff" alt="Liquid Glass">
  <img src="https://img.shields.io/badge/Kotlin-UI-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
</p>


> A complete Linux command line out of the box; the whole app — from the core to the UI — was rebuilt for speed and beauty: a Rust core plus a set of water-like liquid-glass interfaces.

<p align="center"><a href="README.md">中文</a> · <a href="README.en.md">English</a></p>

<p align="center"><b>⭐ If moxsh is useful to you, please give it a <a href="https://github.com/codecloud-dev/moxsh-terminal">star</a> — it helps more people get a smooth Linux terminal on their phone!</b></p>

<details>
<summary>📑 目录 · Contents</summary>

- [📑 Contents](#contents)
- [🌟 MoX family](#mox-family)
- [✨ Features](#features)
- [🔧 How it works](#how-it-works)
- [🚀 Getting started](#getting-started)
- [🧩 Plugin system](#plugin-system)
- [🔗 Relationship with Termux](#relationship-with-termux)
- [📖 Docs](#docs)
- [🤝 Get involved](#get-involved)
- [💖 Support us](#support-us)
- [📜 License](#license)
- [🤖 AI assistance statement](#ai-assistance-statement)

</details>

## 📑 Contents

- [MoX family](#mox-family)
- [Features](#features)
- [How it works](#how-it-works)
- [Getting started](#getting-started)
- [Plugin system](#plugin-system)
- [Relationship with Termux](#relationship-with-termux)
- [Docs](#docs)
- [Get involved](#get-involved)
- [Support us](#support-us)
- [License](#license)

---

## 🌟 MoX family

moxsh is just the first piece of the **MoX tool series** — one visual language, bringing desktop-grade tools into your pocket.

| Product | Status | One-liner |
| :---: | :---: | --- |
| **moxsh** | Released | Android liquid-glass terminal, Termux-compatible |
| **mox-site** | Released | The series' official portal ([site](https://codecloud-dev.github.io/mox-site/)) |
| **moxbox** | Planned · tentative | File manager & system suite in the same glass UI; may or may not happen |
| **moxcode** | Planned · tentative | Lightweight mobile IDE: terminal + editor + preview; may or may not happen |

---

## ✨ Features

| Dimension | Notes |
| :--- | --- |
| **Rust core** | PTY/session, VT parsing, scrollback, render scheduling, package signing, IPC auth — all in Rust, memory-safe |
| **Liquid-glass UI** | Real-time blur, translucent layers, draggable floating windows; real-time on high-end, static fallback on low-end |
| **Termux compatible** | Eats the official termux-packages repos; `*.deb` installs and runs, commands behave the same |
| **PRoot distros** | Ubuntu / Debian / Kali / Alpine one-tap, isolated, no system touch |
| **AI assistant** | Explain errors, write scripts, manage environments; DeepSeek / Zhipu / Qwen / Kimi and more |
| **Plugin system** | Floating glass terminal, themes, system monitor; one-tap `.mox`, plus Termux-plugin compatible |
| **Hardened IPC** | Challenge-response + HMAC-SHA256 auth; cross-process calls never run naked |
| **Cloud sync** | After sign-in, sync shell config, aliases and history to the cloud — your environment travels with you |

---

## 🔧 How it works

moxsh is neither a VM nor an emulator.

The terminal is driven directly by the **Rust core**: it launches the command-line program (`execve`) and wires stdout/stdin to the screen, just like a desktop Linux terminal. Because Android won't let apps write to `/bin` or `/usr`, moxsh installs software into its own private directory (called *prefix*, i.e. `$PREFIX`), with path handling aligned to Termux conventions — that's why Termux packages run directly.

Packages come from the official Termux repos (`apt`/`pkg` dual-protocol compatible), all cross-compiled with the Android NDK and run natively with no emulation overhead. Want a whole distro in an isolated environment? Use the built-in **PRoot engine**, one tap.

The UI uses **liquid glass**: real-time blur, translucent layers, draggable floating windows. High-end Android uses real-time render blur; low-end falls back to a static effect automatically — no manual setup.

---

## 🚀 Getting started

On first launch it auto-downloads and installs the base system (bootstrap). Then you're straight into the terminal.

Update sources and packages:

```bash
pkg update && pkg upgrade
```

Install software (identical to Termux):

```bash
pkg install python nodejs openssh
```

Common commands:

```bash
termux-setup-storage          # allow access to phone storage
ssh user@host                 # connect to a remote server
echo $PREFIX                  # print the current prefix path
```

> Run `pkg update` once before your first package install to avoid "package not found".

### 🔹 Requirements

- Android 9.0 or newer
- arm64-v8a device
- ~300 MB free (incl. first-run bootstrap)

### 🔹 Get the APK

Signed release builds are on the [Releases](../../releases) page; CI also produces the latest APK on every successful build. If the system warns "unknown source" on install, just allow it.

---

## 🧩 Plugin system

moxsh's plugins, AI skills, themes and distro images all use the **`.mox`** package format, installed via the built-in store; you can also drop a `.mox` file into Downloads for offline install.

See [docs/plugins.md](docs/plugins.md) to write and publish plugins. moxsh also hosts Termux plugins (Termux:API, Termux:Widget, etc. keep working); foreign zip formats are auto-converted on import.

---

## 🔗 Relationship with Termux

- **Packages**: directly compatible. Official Termux `.deb` installs and runs, commands behave the same.
- **Code**: AI-assisted, clean-room rewrite. The terminal core, package manager, runtime and PRoot engine are all rewritten (Rust/Kotlin). We studied Termux's public docs/source to align behavior and ecosystem contracts, but **did not copy its code** and do not depend on modifying Termux source.
- **Plugins**: dual system. The Termux-compatible host keeps original plugins working; moxsh-native `.mox` plugins are independently signed with a glass GUI.

---

## 📖 Docs

- [Architecture whitepaper](docs/architecture.md) (Chinese)
- [Plugin guide](docs/plugins.md) (Chinese)
- [Command reference](docs/commands.md) (Chinese)
- [Roadmap](docs/roadmap.md) (Chinese)

---

## 🤝 Get involved

Found a bug or want a feature? Open an [Issue](../../issues); to contribute code, just open a Pull Request.

---

## 💖 Support us

If this project is useful to you, **a Star, a follow, or a share** is the biggest encouragement for a tiny indie project — and helps more people find a better terminal.

- Star it on GitHub: hit the Star button at the top-right of the repo
- Follow the repo / join [Discussions](../../discussions) for updates
- Share it with friends, groups, or any developer who's been tortured by terminals

All forms of contribution are welcome: bugs, ideas, code, docs, translations. Indie dev + AI collaboration, but every direction is decided by a human.

---

## 📜 License

[GPL-3.0](LICENSE)

## 🤖 AI assistance statement

This project (all code, docs and site pages) is led by the developer **Codecloud**, with **AI-assisted code generation**: architecture decisions, requirements and acceptance are done by humans; implementation and docs are AI-collaborative and reviewed/edited by humans.

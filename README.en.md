# moxsh · Liquid-Glass Terminal for Android

> A terminal app that is **fully compatible with the Termux ecosystem**, **faster**, ships a **whole-app liquid-glass (glassmorphism) UI**, is **optimized for China**, and builds its APK through **cloud CI**.
> Core principle: the **runtime is 100% self-developed (clean-room — no Termux code is reused)**, yet the artifacts stay compatible with the official `termux-packages` repository.

---

## Why moxsh

Termux is great, but its runtime carries years of assumptions that are hard to evolve. moxsh keeps what works — the package ecosystem — and rebuilds everything underneath:

- **Clean-room runtime** — our own PTY/VT engine, own package resolver, own PRoot. Not a fork; no Termux source is copied.
- **Rust core** — memory safety where it matters most (parsers, crypto, IPC). NEON assembly for the hot paths. Kotlin/Compose for the glass UI.
- **Liquid-glass UI** — the entire app, not just accents. Real-time blur on API 31+, graceful static fallback below.
- **Beginner-friendly** — graphical distro manager, graphical package manager, resource monitor, and a first-run wizard. New users barely touch the terminal.
- **Built-in AI agent** — natural-language control (install distros, swap mirrors, read errors) with risk-confirmation cards for dangerous actions.
- **`.mox` ecosystem** — plugins and AI skills share one signed package format; a store supports one-click install and local uploads, with marketplace/commission fields reserved for later.

---

## Repository layout

| Path | What it is |
| --- | --- |
| `moxsh/` | **Buildable engineering project (13 Gradle modules, milestones M1–M5 done) + cloud CI** — see `moxsh/README.md` |
| `.github/workflows/build.yml` | GitHub Actions: build & upload the APK on every push |
| `docs/architecture.md` | Architecture whitepaper v0.5 (18 ratified decisions, D1–D18) |
| `docs/roadmap.md` | Development roadmap (M1–M5 ✅, authoritative progress list) |
| `docs/commands.md` | Inventory of the Termux command surface (from source study) |
| `docs/plugins-rewrite.md` | Plan for rewriting 6 plugins (with code sketches; X11 deferred) |
| `research/termux-plugins-shortcomings.md` | Plugin shortcoming study (12 findings, with sources) |
| `research/build-pitfalls.md` | Build pitfalls encountered (48 notes, with sources) |
| `clone.sh` | Helper to fetch the Termux reference source into `termux-src/` (local only, **not** part of this repo) |

> **Note on `termux-src/`:** it is a *local reference clone* of the official Termux repositories, used only for study. It is **excluded from this repository** (see `.gitignore`) and moxsh does **not** copy any of its code. Re-fetch it locally with `bash clone.sh` if you want to read along.

---

## 18 ratified decisions

1. **Compatibility layer** — consume the official `termux-packages` repo directly; existing `.deb` packages install and run.
2. **Package manager** — dual protocol (our new format + apt/deb compatibility).
3. **Runtime** — 100% self-built and fully custom; implements the `termux-packages` disk/ABI contract.
4. **Terminal kernel** — pure clean-room (may study libvterm/st ideas, but all code is ours).
5. **minSdk 28** (Android 9).
6. **X11 not in scope** this cycle.
7. **Dual plugin system** — ① Termux-compatible host (old plugins work) ② moxsh native glass plugins (independently signed, not interchanged, with GUI).
8. **Glass by default** — auto by device capability (real-time blur on API 31+, static fallback below); user-toggleable in settings.
9. **Language stack** — Kotlin/Compose (UI) + **Rust (entire kernel)** + **NEON assembly (hot paths)** + **C/C++ (JNI bridge only)**.
10. **Rust coverage** — terminal kernel + package parsing/signing + IPC hardening + compat shim + crypto/security.
11. **Build orchestration** — `cargo-ndk` prebuild (Gradle `preBuild` → `cargo ndk` → `libmoxshcore.so`; CI installs Rust toolchain + cargo-ndk).
12. **PRoot route** — self-built Rust engine (ptrace path translation) that aligns with upstream proot-me 6.x *behavior*; no code copied.
13. **GUI scope** — all four tools (distro manager / graphical package manager / resource monitor / beginner wizard); beginners essentially never use the command line.
14. **Prebuilt distros** — Ubuntu / Debian / Kali / Alpine + custom rootfs tar.
15. **Package format** — plugins and AI skills unified into the `.mox` format (signature + metadata + permission declaration); foreign formats stay compatible.
16. **Store scope** — full chain (browse / one-click install / uninstall / enable-disable / update + local upload + cloud source API + built-in catalog fallback).
17. **Marketplace reservation** — manifest carries `author` / `price` / `purchased` fields; payment & commission wired in a later version.
18. **AI integration** — OpenAI-compatible + domestic presets (DeepSeek / Zhipu / Qwen / Kimi) + local small-model hook (M7); high-risk actions show a glass confirmation card.

---

## How to get the APK

The sandbox has **no Android SDK**, so the APK cannot be compiled here. The project is wired for **cloud CI**:

1. Push this repository to GitHub.
2. In **Settings → Secrets**, configure signing (optional; falls back to debug signing): `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. Push to `main`/`dev` → Actions automatically runs `assembleRelease` and uploads the APK artifact.

Local build: open `moxsh/` in Android Studio (or run `gradle wrapper` then `./gradlew assembleDebug`). Requires Android SDK (34)+ and NDK `26.1.10909125`.

> **Chinese speakers / first-time GitHub users:** a step-by-step guide and a one-command script are provided in [`docs/PUSH_GUIDE.md`](docs/PUSH_GUIDE.md) (中文). Everything is already committed locally; pushing is a single command.

---

## Milestones (authoritative progress — see `docs/roadmap.md`)

| Milestone | Goal | Status |
| --- | --- | --- |
| M1 | Scaffold + CI + glass shell + self-built runtime skeleton | ✅ |
| M2 | Rust terminal kernel (PTY / VT parsing / scrollback) + NEON hot paths + session engine | ✅ |
| M3 | Glass UI core + real terminal rendering + 7 plugins (float/styling/api/boot/tasker/widget/core) | ✅ |
| M4 | PRoot engine + graphical management suite + `.mox` store + AI agent (all 18 decisions landed) | ✅ |
| M5 | Bootstrap first-run pipeline + mmap scrollback fallback + Rust unit tests | ✅ |
| M6 | First installable APK (push → Actions build → on-device testing) | ⏳ needs repo push |
| M7 | Performance polish (120 Hz / glyph atlas / syscall hot paths) + local small model + payment/commission | ⏳ |

---

## Quick start (developer)

```bash
# 1. Fetch the Termux reference source (optional, local only)
bash clone.sh

# 2. Build the Rust core for Android (needs cargo-ndk + NDK)
cd moxsh/terminal-core
cargo ndk -t arm64-v8a -t x86_64 build --release

# 3. Build the APK (needs Android SDK + NDK)
cd moxsh
gradle assembleDebug        # or: ./gradlew assembleDebug
```

Run the Rust unit tests (host target, no Android needed):

```bash
cd moxsh/terminal-core
cargo test
```

---

## Tech stack at a glance

| Layer | Tech | Responsibility |
| --- | --- | --- |
| UI | Kotlin / Jetpack Compose (Material3 Expressive) | Glass UI, lifecycle, permissions, foreground service, JNI glue |
| Kernel | **Rust** | PTY/session, VT parsing, scrollback, render scheduling, package parse/sign, IPC auth, compat shim, crypto |
| Hot paths | **ARM64 NEON assembly** | Scroll diff, cell clear, glyph blits |
| Bridge | **C/C++ (minimal)** | `bridge.cpp` + `neon.s` linked into one `cdylib` via `cc` in `build.rs` |
| Container | **Rust PRoot engine** | ptrace path translation, fake root, bind mounts, distro management |
| Packaging | **`.mox`** | tar + `manifest.json` + HMAC-SHA256 signature + payload (plugin/skill/theme/rootfs) |
| AI | OpenAI-compatible + domestic presets | Natural-language control with risk-confirmation cards |
| CI | GitHub Actions + cargo-ndk | Build & sign APK on push |

---

## License

**GPL-3.0** — see [LICENSE](LICENSE).

This license was chosen deliberately: it keeps the project (and any future marketplace built on it) free software, and its copyleft protects the commission/marketplace model from closed forks. If you plan to build a commercial closed-source derivative, please contact the author to discuss terms.

---

## Contact / contribution

- Issues & PRs: once the repo is pushed, use GitHub Issues.
- Discussion: see `docs/` for architecture and roadmap rationale.
- Author: [@zyr20121219](https://github.com/zyr20121219)

> moxsh is an independent, clean-room project. It is compatible with the Termux ecosystem by contract, not by copying code.

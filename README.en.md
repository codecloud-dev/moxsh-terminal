# moxsh

moxsh is a terminal application for Android: it gives you a full Linux command-line environment out of the box — no root required, no setup, just install and run.

It is fully compatible with the Termux ecosystem: packages from the official Termux repositories install and run directly. At the same time, moxsh is an AI-assisted, clean-room implementation: Termux's public documentation and build scripts were studied to align behavior and ecosystem contracts, but no code was copied, which leaves room for better performance and a UI built entirely around liquid glass design. The kernel and package management are written in Rust, and the app ships with an AI assistant and a plugin store.

## How it works

moxsh is neither a virtual machine nor an emulator.

The terminal is driven by a Rust kernel: the app launches command-line programs (`execve`) and wires their standard input/output to the screen — exactly like a terminal on desktop Linux. Since Android does not allow apps to write into system directories such as `/bin` or `/usr`, moxsh installs all software into its private app directory (called the *prefix*, exposed as `$PREFIX`) and aligns path handling with Termux conventions — that is why Termux packages run unmodified.

Packages come from the official Termux repositories (with both `apt` and `pkg` protocol support), cross-compiled with the Android NDK. They run natively — no emulation overhead. To run an entire distribution (Ubuntu, Debian, Kali, Alpine) in an isolated environment, the built-in PRoot engine installs one with a single tap.

The UI follows a liquid glass design: real-time blur, translucent layers, draggable floating windows. Newer Android versions get live blur; older ones fall back to static translucency automatically.

## What can I do with moxsh?

- Learn the Linux command line and shell scripting
- Program in Python, Node.js, Rust, C/C++
- Use SSH to reach remote servers, or use the phone as a jump host
- Install Ubuntu / Debian / Kali / Alpine with one tap
- Ask the built-in AI assistant to explain errors, write scripts, and manage environments (DeepSeek, Zhipu, Qwen, Kimi and more)
- Install plugins: floating terminal, themes, system monitor — or write your own

## Installation

### Requirements

- Android 9.0 or higher
- arm64-v8a device
- About 300 MB of free space (including the bootstrap)

### Getting the APK

This repository builds automatically with GitHub Actions:

1. Open the [Actions](../../actions) page and pick the latest successful run
2. Download the APK from the Artifacts section and install it

Release builds are published on the [Releases](../../releases) page. If Android warns about "unknown sources", allow it.

## Quick start

The first launch downloads and installs the base system (bootstrap), then drops you into a terminal.

Update package lists and upgrade:

```bash
pkg update && pkg upgrade
```

Install software (identical to Termux usage):

```bash
pkg install python
pkg install nodejs
pkg install openssh
```

Common tasks:

```bash
# Grant access to shared storage (photos, downloads, ...)
termux-setup-storage

# Connect to a remote server
ssh user@host

# Show the prefix path
echo $PREFIX
```

Run `pkg update` once before installing anything for the first time.

## Plugins

Plugins, AI skills, themes and distribution images all ship as `.mox` packages, installed through the built-in store or offline from the download folder.

See [docs/plugins.md](docs/plugins.md) for writing and publishing your own plugins.

moxsh also hosts the Termux add-ons (Termux:API, Termux:Widget, etc.) through a compatibility layer; third-party zip plugin packages are converted automatically on import.

## Relationship with Termux

- **Packages**: fully compatible. `.deb` packages from the official Termux repository install and run; commands behave the same.
- **Code**: AI-assisted, clean-room rewrite. The terminal kernel, package manager, runtime environment and PRoot engine are all written from scratch (Rust/Kotlin); Termux sources were studied for behavior alignment but no code was copied and no patched Termux sources are used.
- **Plugins**: dual system. The compatibility host keeps existing Termux add-ons working; native moxsh plugins (`.mox`) are independently signed with glass UI.

## Documentation

- [Architecture whitepaper](docs/architecture.md) (Chinese)
- [Plugin development guide](docs/plugins.md) (Chinese)
- [Command reference](docs/commands.md) (Chinese)
- [Roadmap](docs/roadmap.md) (Chinese)

## Contributing

Found a bug or want a feature? Open an [Issue](../../issues). Pull requests are welcome.

## License

[GPL-3.0](LICENSE)

## AI Assistance Notice

This project (all code, docs and the website) is designed and owned by **Codecloud**,
with **AI-assisted code generation**: architecture decisions, requirements and acceptance are human-driven;
implementation and documentation are AI-collaborated and human-reviewed.

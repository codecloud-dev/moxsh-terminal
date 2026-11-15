// build.rs —— 把 C++ JNI 桥接与 NEON 汇编经 NDK clang 编译，链入 libmoxshcore.so
// cargo-ndk 已设置好 CC_aarch64_linux_android / AR / 链接标志等环境变量，
// cc crate 会自动采用对应工具链，无需手写路径。
fn main() {
    // 告知 rustc 检查自定义 cfg，消除 unexpected_cfgs 警告
    println!("cargo:rustc-check-cfg=cfg(has_neon_asm)");

    let target = std::env::var("TARGET").unwrap_or_default();
    let is_android = target.contains("android");

    if is_android {
        // ── 16KB page size 兼容（Google Play 自 2025-11-01 对 targetSdk 35+ 强制）──
        // 让 ELF LOAD 段按 16KB 对齐，保证 libmoxshcore.so 在 16KB 页设备上可加载。
        // 该 cdylib 的最终链接由 rustc 完成（cc crate 只产出 .o/.a 静态库），
        // 故用 cargo:rustc-cdylib-link-arg 精确作用于 cdylib，再补一条 rustc-link-arg
        // 覆盖所有链接目标做双保险。重复的 -z 参数对链接器无害。
        // 仅 Android 目标生效：本机 `cargo test / cargo build`（TARGET 为宿主）不加这些
        // NDK 链接参数，避免污染非 Android 构建。
        println!("cargo:rustc-cdylib-link-arg=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-cdylib-link-arg=-Wl,-z,common-page-size=16384");
        println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
        println!("cargo:rustc-link-arg=-Wl,-z,common-page-size=16384");

        // C++ JNI 桥接：仅做 Android 端到 Rust C-ABI 的转发（D9：C++ 最小存在）
        // 仅在 Android 目标编译——本机 `cargo test`（无 NDK/ jni.h）仍可纯 Rust 通过。
        cc::Build::new()
            .cpp(true)
            .file("cpp/bridge.cpp")
            .flag("-std=c++17")
            .flag("-fvisibility=hidden")
            // 汇编/链接阶段 cc-rs 仍会附带 -D/-f* 等编译期 flag，clang 对
            // "argument unused" 告警——显式关掉，保持 CI 日志干净。
            .flag("-Wno-unused-command-line-argument")
            .compile("moxsh_bridge");

        // NEON 汇编热路径：仅在 arm64-android 编译；其余架构由 Rust 回退实现。
        if target == "aarch64-linux-android" {
            cc::Build::new()
                .file("arch/neon.s")
                .flag("-target")
                .flag("aarch64-linux-android")
                .flag("-Wno-unused-command-line-argument")
                .compile("moxsh_neon");
            println!("cargo:rustc-cfg=has_neon_asm");
        }

        println!("cargo:rerun-if-changed=cpp/bridge.cpp");
        println!("cargo:rerun-if-changed=arch/neon.s");
    }
}

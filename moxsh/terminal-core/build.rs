// build.rs —— 把 C++ JNI 桥接与 NEON 汇编经 NDK clang 编译，链入 libmoxshcore.so
// cargo-ndk 已设置好 CC_aarch64_linux_android / AR / 链接标志等环境变量，
// cc crate 会自动采用对应工具链，无需手写路径。
fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let is_android = target.contains("android");

    if is_android {
        // C++ JNI 桥接：仅做 Android 端到 Rust C-ABI 的转发（D9：C++ 最小存在）
        // 仅在 Android 目标编译——本机 `cargo test`（无 NDK/ jni.h）仍可纯 Rust 通过。
        cc::Build::new()
            .cpp(true)
            .file("cpp/bridge.cpp")
            .flag("-std=c++17")
            .flag("-fvisibility=hidden")
            .compile("moxsh_bridge");

        // NEON 汇编热路径：仅在 arm64-android 编译；其余架构由 Rust 回退实现。
        if target == "aarch64-linux-android" {
            cc::Build::new()
                .file("arch/neon.s")
                .flag("-target")
                .flag("aarch64-linux-android")
                .compile("moxsh_neon");
            println!("cargo:rustc-cfg=has_neon_asm");
        }

        println!("cargo:rerun-if-changed=cpp/bridge.cpp");
        println!("cargo:rerun-if-changed=arch/neon.s");
    }
}

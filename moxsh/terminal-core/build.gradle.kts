plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// ── Rust + NEON 内核构建（D9/D11）──────────────────────────────
// cargo-ndk 预构建产出 .so 进 src/main/jniLibs，Gradle 打包时自动纳入 APK。
val cargoNdkBuild by tasks.registering(Exec::class) {
    workingDir = projectDir
    // ANDROID_NDK_HOME 由 CI/本地环境提供；cargo-ndk 自动探测
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "x86_64",            // 模拟器调试用
        "-o", "src/main/jniLibs",
        "build", "--release"
    )
    // 仅当 Rust 源码或 .s 变更才重编（简单基于文件存在性判断）
    outputs.dir(layout.projectDirectory.dir("src/main/jniLibs"))
    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.files("Cargo.toml", "build.rs")
}

// 让 Kotlin/资源编译在 Rust .so 产出之后进行
tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

android {
    namespace = "com.moxsh.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 注意：本模块不启用 externalNativeBuild——native 由 cargo-ndk 产出。
    // C++ JNI 桥接(bridge.cpp)与 NEON 汇编(neon.s)在 Rust crate 的 build.rs 内
    // 经 NDK clang 编译并链入同一个 libmoxshcore.so。
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":shared"))
}

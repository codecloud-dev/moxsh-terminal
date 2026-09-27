plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.plugin.api"
    compileSdk = 34

    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 复用主 app 共享层（CompatShim 分类路由 / TermuxCompatHost 接口契约）
    implementation(project(":shared"))
    // 经 plugin-core 的 MoxshIpcClient 转发 Api 类命令到加固 IPC
    implementation(project(":plugins:plugin-core"))
}

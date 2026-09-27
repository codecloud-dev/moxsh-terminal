plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.plugin.float"
    compileSdk = 34

    defaultConfig { minSdk = 28 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    // 复用主 app 共享层（执行引擎 / 兼容 shim 约定接口）
    implementation(project(":shared"))
    // 复用终端内核（TerminalCore）做悬浮窗内嵌会话
    implementation(project(":terminal-core"))
    // 复用全局液态玻璃组件库
    implementation(project(":ui"))
    // 通过原生插件框架注册（D5 体系②）
    implementation(project(":plugins:plugin-core"))
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
}

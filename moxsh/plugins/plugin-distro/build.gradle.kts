plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.plugin.distro"
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
    // 复用主 app 共享层（ExecutionEngine / ProotManager：发行版安装与命令执行）
    implementation(project(":shared"))
    // 复用全局液态玻璃组件库（GlassSurface/GlassTopBar/GlassFAB/...）
    implementation(project(":ui"))
    // 通过原生插件框架注册（D5 体系②，照 plugin-float 模式）
    implementation(project(":plugins:plugin-core"))
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 协程（ProotManager.install 的 suspend 调用与界面 scope）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

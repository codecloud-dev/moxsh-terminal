plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.plugin.ai"
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
    // 复用主 app 共享层（ProotManager / MoxPackage / ExecutionEngine：AI 工具调用的落地通道）
    implementation(project(":shared"))
    // 复用全局液态玻璃组件库（GlassSurface/GlassTokens/GlassFAB/...）
    implementation(project(":ui"))
    // 通过原生插件框架注册（D5 体系②，照 plugin-distro 模式）
    implementation(project(":plugins:plugin-core"))
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 协程（AgentExecutor 工具循环、SSE 流式读取、前台服务 scope）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // AndroidX 注解（ForegroundServiceType 声明用）
    implementation("androidx.annotation:annotation:1.11.0")
    // NotificationCompat（AiService 常驻通知；core 是 ui 的传递依赖，这里显式声明）
    implementation("androidx.core:core-ktx:1.13.1")
}

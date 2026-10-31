plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 与 app/build.gradle.kts 同一坑（曾由 3fd6d46 修复）：Gradle Kotlin DSL 脚本里写
// 全限定名 java.util.Properties 会因根标识符 java 解析失败报 "Unresolved reference: util"
// （连带 load 也 unresolved，见 plugin-store 历史 CI 事故 #63~#83）。必须显式 import。
import java.util.Properties

android {
    namespace = "com.moxsh.plugin.store"
    compileSdk = 34

    // 商店验签密钥：生产值经 CI 注入（环境变量 MOX_STORE_SECRET 或本地
    // local.properties 的 MOX_STORE_SECRET），源码不保留明文密钥；
    // 未注入时回退为开发期本地包验签占位常量。
    val localProps =
        Properties().apply {
            val f = project.rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
    // 原始密钥值（CI 环境变量 > 本地 local.properties）；为空表示未配置。
    val rawSecret: String? =
        System.getenv("MOX_STORE_SECRET")
            ?: localProps.getProperty("MOX_STORE_SECRET")
    // 开发期占位常量：仅 debug 构建允许回退，release 严禁（见下方 buildTypes）。
    val DEV_FALLBACK = "moxsh-official-store-v1"

    defaultConfig {
        minSdk = 28
        // debug / 未配置时回退开发占位；release 会在 buildTypes 里直接构建失败。
        val storeSecret = rawSecret ?: DEV_FALLBACK
        buildConfigField("String", "MOX_STORE_SECRET", "\"$storeSecret\"")
    }

    buildTypes {
        // P0 修复：release 构建若未注入真实密钥，严禁回退到公开 DEV_FALLBACK
        // （那等于把密钥就写在 APK 里）。CI 必须配置 MOX_STORE_SECRET，否则构建即失败。
        release {
            if (rawSecret.isNullOrBlank()) {
                throw GradleException(
                    "MOX_STORE_SECRET 未配置：release 构建禁止回退到公开 DEV_FALLBACK 密钥 " +
                        "（moxsh-official-store-v1）。请在 CI 环境变量或 local.properties 设置 " +
                        "MOX_STORE_SECRET 后再打 release 包。",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        // MOX_STORE_SECRET 走 buildConfigField 注入，必须显式开特性
        // （AGP 8 默认关闭，漏开报 "defaultConfig contains custom BuildConfig
        // fields, but the feature is disabled"）
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    // 复用主 app 共享层（MoxPackage：.mox 验签/解压 native 封装）
    implementation(project(":shared"))
    // 复用全局液态玻璃组件库（GlassSurface/GlassTokens/...）
    implementation(project(":ui"))
    // 通过原生插件框架注册（D5 体系②，照 plugin-distro 模式）
    implementation(project(":plugins:plugin-core"))
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    // 协程（StoreApi 的 suspend 调用与界面 scope）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}

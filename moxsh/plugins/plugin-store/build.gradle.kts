plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 与 app/build.gradle.kts 同一坑（曾由 3fd6d46 修复）：Gradle Kotlin DSL 脚本里写
// 全限定名 java.util.Properties 会因根标识符 java 解析失败报 "Unresolved reference: util"
// （连带 load 也 unresolved，见 plugin-store 历史 CI 事故 #63~#83）。必须显式 import。
import java.util.Properties

// 商店验签密钥：生产值经 CI 注入（环境变量 MOX_STORE_SECRET 或本地
// local.properties 的 MOX_STORE_SECRET），源码不保留明文密钥；
// 未注入时 debug / 本地编译验证回退为开发期占位常量。
val localProps =
    Properties().apply {
        val f = project.rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
// 原始密钥值（CI 环境变量 > 本地 local.properties）；为空表示未配置。
val rawSecret: String? =
    System.getenv("MOX_STORE_SECRET")
        ?: localProps.getProperty("MOX_STORE_SECRET")
// 开发期占位常量：仅 debug / 编译验证允许回退，release 产物严禁（见文件末尾守卫）。
val DEV_FALLBACK = "moxsh-official-store-v1"

android {
    namespace = "com.moxsh.plugin.store"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        // debug / 未配置时回退开发占位；release 产物会在末尾守卫处直接构建失败。
        val storeSecret = rawSecret ?: DEV_FALLBACK
        buildConfigField("String", "MOX_STORE_SECRET", "\"$storeSecret\"")
    }

    buildTypes {
        release {
            // 这里不再做密钥检查。Gradle 配置阶段会评估所有 buildType，
            // 旧实现在此处 throw，会让 assembleDebug / fork PR 的编译验证也失败
            // （2026-11-11 全仓审计发现的假阴性：5 个 Dependabot PR 全挂在这）。
            // P0 守卫移到文件末尾的 task 执行期，任何真正产出 release 产物的
            // task 都绕不过，安全性不削弱。
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
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}

// P0 fail-closed 守卫（2026-11-11 二次修复）：
// 未注入 MOX_STORE_SECRET 时，禁止产出任何 release 变体产物，防止公开占位密钥
// 被打进对外分发的包。检查放在 task 执行期（doFirst）而非配置期：
//   · gradle clean assembleDebug                  → 不触发，编译验证可正常跑
//   · gradle assembleRelease / bundleRelease / build → 触发，立即失败
// 这样既不削弱安全，也不再让 Dependabot / fork PR 的 debug 编译假失败。
if (rawSecret.isNullOrBlank()) {
    tasks.configureEach {
        if (name.startsWith("assembleRelease") ||
            name.startsWith("bundleRelease") ||
            name.startsWith("publishRelease")
        ) {
            doFirst {
                throw GradleException(
                    "MOX_STORE_SECRET 未配置：release 产物禁止回退到公开 DEV_FALLBACK 密钥 " +
                        "（moxsh-official-store-v1）。请在 CI 环境变量或 local.properties 设置 " +
                        "MOX_STORE_SECRET 后再打 release 包。",
                )
            }
        }
    }
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

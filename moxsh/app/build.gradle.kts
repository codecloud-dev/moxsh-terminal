plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Gradle Kotlin DSL 脚本里直接写 `java.util.Properties` / `java.net.URL` 这类限定名，
// 在部分 Gradle 版本的脚本编译环境下会因根标识符 `java` 解析失败而报
// "Unresolved reference: util / net"（连带类型推断失败导致 `load` 也 unresolved）。
// 改用显式 import：由编译器从 classpath 直接解析类型，规避该问题。
import java.util.Properties
import java.net.HttpURLConnection
import java.net.URL

// 读取 local.properties（已被 .gitignore 忽略，不进 git）注入 GitHub client_id
// 设备流（Device Flow）只需 client_id，无需任何 client_secret / private key
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.moxsh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.moxsh"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.0"

        // GitHub 登录 client_id（设备流）：来自 local.properties，公开安全，
        // 不写死、不提交 git。仅此一个值进 APK，且本就可公开。
        val clientId = localProps.getProperty("github_client_id")
            ?: "REPLACE_WITH_YOUR_GITHUB_OAUTH_CLIENT_ID"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$clientId\"")
    }

    signingConfigs {
        create("release") {
            // CI 注入（workflow 的 KEYSTORE_* 环境变量 + Secrets）；本地不存在时保持空，
            // release 回退 debug 签名保证任何人 clone 后都能直接出可安装的包。
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式 keystore（CI）用 release 签名；否则回退 debug 签名
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging { jniLibs.useLegacyPackaging = true }
}

// ── 运行环境 bootstrap 包：构建时尽力拉取进 assets（实现"装上即用"，避免首次启动联网下载）──
// 仅当 assets 下缺失对应架构包时才下载；网络失败仅告警、不阻断构建（运行时再走网络兜底）。
val bootstrapTag = "bootstrap-2026.09.20-r1+apt.android-7"
val bootstrapAbis = listOf("aarch64", "arm", "i686", "x86_64")
val bootstrapBase = "https://github.com/termux/termux-packages/releases/download/$bootstrapTag/bootstrap-"
val bootstrapAssetsDir = file("src/main/assets")

tasks.register("fetchBootstrap") {
    group = "moxsh"
    description = "下载 Termux bootstrap 包到 app/src/main/assets（装机即用）"
    doLast {
        bootstrapAssetsDir.mkdirs()
        for (abi in bootstrapAbis) {
            val target = File(bootstrapAssetsDir, "bootstrap-$abi.zip")
            if (target.exists() && target.length() > 0) {
                logger.lifecycle("bootstrap-$abi.zip 已存在，跳过")
                continue
            }
            val url = "$bootstrapBase$abi.zip"
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 180_000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { ins -> target.outputStream().use { ins.copyTo(it) } }
                logger.lifecycle("已下载 bootstrap-$abi.zip (${target.length()} bytes)")
            }.onFailure { e ->
                logger.warn("下载 bootstrap-$abi.zip 失败（运行时将走网络兜底）：${e.message}")
                if (target.exists()) target.delete()
            }
        }
    }
}

// 每次构建先确保 bootstrap 资源就位（缺则拉取，已有则跳过）
tasks.named("preBuild") { dependsOn("fetchBootstrap") }

dependencies {
    implementation(project(":terminal-core"))
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(project(":plugins:plugin-core"))
    implementation(project(":plugins:plugin-float"))
    implementation(project(":plugins:plugin-distro"))
    implementation(project(":plugins:plugin-styling"))
    implementation(project(":plugins:plugin-boot"))
    implementation(project(":plugins:plugin-widget"))
    implementation(project(":plugins:plugin-api"))
    // D16 插件商店 + D18 AI Agent（plugin-ai 目录由另一 agent 实现，此处仅依赖占位）
    implementation(project(":plugins:plugin-store"))
    implementation(project(":plugins:plugin-ai"))

    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    // GitHub 登录会话的安全本地存储（基于 Android Keystore 的 AES-256）
    implementation("androidx.security:security-crypto:1.1.0")
    // Custom Tabs：用系统浏览器内核打开设备流的验证页（最安全、可见网址）
    implementation("androidx.browser:browser:1.8.0")
}

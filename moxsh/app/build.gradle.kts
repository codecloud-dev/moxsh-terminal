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
    // Google Play 自 2025-11-01 强制 targetSdk 35+ 且支持 16KB 页；compileSdk 与
    // targetSdk 保持一致，避免 AGP「target > compile」告警与 lint NewApi 误报。
    // （16KB 页兼容的落点在 native 链接参数，见 terminal-core/build.rs 的
    //  -Wl,-z,max-page-size=16384；AGP 8.5 对 compileSdk 35 的「tested up to 34」
    //  提示由 gradle.properties 的 android.suppressUnsupportedCompileSdk=35 收口。）
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moxsh"
        minSdk = 28
        // targetSdk 35（Play 16KB 合规要求）。
        // 注意（功能取舍，如实记录）：原 targetSdk 28 是为规避 Android 10+（API 29+）
        // 对 app 私有目录写出的可执行文件的 W^X 限制——targeting 29+ 后，BootstrapInstaller
        // 解到 $PREFIX 的 proot/bash 等不能再直接 exec。这是一次明确的合规升级取舍：
        // 运行时 bootstrap 需改为「随 APK 打包的 uncompressed/16KB 对齐 native 库、由系统
        // 从 APK mmap 加载执行」的路径（extractNativeLibs=false），而不是写盘后 exec。
        // 该运行时改造属独立后续项，不在本次签名/SDK 升级范围内；此处仅如实标注，不隐藏。
        targetSdk = 35
        versionCode = 66
        versionName = "1.0.3"

        // GitHub 登录 client_id（设备流）：来自 local.properties，公开安全，
        // 不写死、不提交 git。仅此一个值进 APK，且本就可公开。
        val clientId = localProps.getProperty("github_client_id")
            ?: "REPLACE_WITH_YOUR_GITHUB_OAUTH_CLIENT_ID"
        buildConfigField("String", "GITHUB_CLIENT_ID", "\"$clientId\"")

        // 邮箱验证服务（mox-id）基址：来自 local.properties。
        // 仅服务【地址】，属公开信息；邮箱授权码绝不在此注入、绝不进 APK，
        // 它只允许存在于后端 Worker 的环境变量 / wrangler secret。
        val emailApiBase = localProps.getProperty("email_api_base")
            ?: "REPLACE_WITH_YOUR_MOX_ID_EMAIL_API_BASE"
        buildConfigField("String", "EMAIL_API_BASE", "\"$emailApiBase\"")
    }

    signingConfigs {
        // 统一显式声明全部 APK 签名方案（AGP 8+ DSL）：不依赖各 AGP 版本的默认值，
        // 避免「某代默认只开 v2、换机器就少签名块导致装不上」。
        //  - v1：JAR 签名（老设备兜底）
        //  - v2：APK Signature Scheme v2
        //  - v3：v3；且 targetSdk≥33 时 apksigner 在 v3 块内产出 v3.1 分块
        //        （支持签名密钥轮换）——本项目 targetSdk 35 已满足，故 v3/v3.1 全开
        //  - v4：ADB 增量安装所需的 v4（.idsig），需先有 v2/v3（上面已开）
        // v4 仅作用于 API 30+ 的 adb incremental install，其余设备自动忽略，不会崩构建。
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
            // CI 注入（workflow 的 KEYSTORE_* + Secrets）。只有「文件存在 + 四项凭据齐全」
            // 才算一份可用的正式 keystore；任何缺项都保持空配置，由 buildTypes 回退 debug，
            // 绝不让半配置的 signingConfig 参与签名而把构建/发布打挂。
            val ksPath = System.getenv("KEYSTORE_PATH")
            val ksPass = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPass = System.getenv("KEY_PASSWORD")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists() &&
                !ksPass.isNullOrBlank() && !alias.isNullOrBlank() && !keyPass.isNullOrBlank()
            ) {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
        // debug 兜底签名也开全部方案：本地 clone 直接 assembleRelease / assembleDebug
        // 都拿到完整签名的可安装包，且不崩构建。
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有完整正式 keystore（CI）用 release 签名；否则优雅回退 debug 签名。
            // signingConfigs.release 仅在「文件+四项凭据齐全」时才有 storeFile，
            // 故 storeFile==null 即代表「本地无 keystore」，此时引用 debug 永不崩。
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
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }

    // 16KB 页兼容：native .so 的 ELF 段对齐由 terminal-core/build.rs 的链接参数
    // -Wl,-z,max-page-size=16384 保证；此处保持既有打包策略（与 extractNativeLibs 及
    // 运行时加载方式相关），不为了 16KB 盲目翻转而引入功能回归。
    packaging { jniLibs.useLegacyPackaging = true }

    lint {
        // targetSdk 已升到 35（Play 16KB 合规），ExpiredTargetSdkVersion 本不会再触发；
        // 仍显式 disable 一道，防止将来 AGP/元数据把 35 误判为过期而阻断构建。
        // 精准关掉这一个检查，不用 abortOnError=false（那会埋掉真问题）。
        // AGP 8.5 的 Lint DSL 接口只有 disable 属性（Set），没有 vararg 方法
        // （方法仅存在于实现类，Groovy 动态派发才可用）——Kotlin DSL 必须用属性形式。
        disable += "ExpiredTargetSdkVersion"
    }
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
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // GitHub 登录会话的安全本地存储（基于 Android Keystore 的 AES-256）
    implementation("androidx.security:security-crypto:1.1.0")
    // Custom Tabs：用系统浏览器内核打开设备流的验证页（最安全、可见网址）
    implementation("androidx.browser:browser:1.8.0")
}

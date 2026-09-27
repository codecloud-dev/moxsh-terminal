plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
            isMinifyEnabled = false
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

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":terminal-core"))
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(project(":plugins:plugin-float"))
    implementation(project(":plugins:plugin-distro"))
    implementation(project(":plugins:plugin-styling"))
    implementation(project(":plugins:plugin-api"))
    // D16 插件商店 + D18 AI Agent（plugin-ai 目录由另一 agent 实现，此处仅依赖占位）
    implementation(project(":plugins:plugin-store"))
    implementation(project(":plugins:plugin-ai"))

    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
}

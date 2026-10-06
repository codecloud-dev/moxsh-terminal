plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.plugin.store"
    compileSdk = 34

    // 商店验签密钥：生产值经 CI 注入（环境变量 MOX_STORE_SECRET 或本地
    // local.properties 的 MOX_STORE_SECRET），源码不保留明文密钥；
    // 未注入时回退为开发期本地包验签占位常量。
    val localProps =
        java.util.Properties().apply {
            val f = project.rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
    val storeSecret: String =
        System.getenv("MOX_STORE_SECRET")
            ?: localProps.getProperty("MOX_STORE_SECRET")
            ?: "moxsh-official-store-v1"

    defaultConfig {
        minSdk = 28
        buildConfigField("String", "MOX_STORE_SECRET", "\"$storeSecret\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

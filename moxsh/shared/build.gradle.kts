plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moxsh.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":terminal-core"))
    implementation("androidx.core:core-ktx:1.13.1")
    // 执行引擎的泵循环（startPump）使用 kotlinx.coroutines 在 Dispatchers.IO 上调度。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}

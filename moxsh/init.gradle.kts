// 国内依赖镜像（moxsh 国内优化 / D8 之外的工程化落地）
// CI 会把它复制为 ~/.gradle/init.gradle.kts；无网络时也兼容 google()/mavenCentral()
allprojects {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

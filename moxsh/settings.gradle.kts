pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "moxsh"
include(":app")
include(":terminal-core")
include(":shared")
include(":ui")
include(":plugins:plugin-core")
include(":plugins:plugin-float")
include(":plugins:plugin-distro")
include(":plugins:plugin-styling")
include(":plugins:plugin-api")
include(":plugins:plugin-boot")
include(":plugins:plugin-tasker")
include(":plugins:plugin-widget")
// D16 插件商店（.mox 包全链路：浏览/安装/卸载/启停/上传/云源接口）
include(":plugins:plugin-store")
// D18 AI Agent（另一 agent 实现，先 include 占位；目录由其自建）
include(":plugins:plugin-ai")

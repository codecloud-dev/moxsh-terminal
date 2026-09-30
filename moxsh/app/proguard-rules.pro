# moxsh release R8 规则（保守，优先保证正确性）
# 组件保留由 AAPT 自动生成，这里兜底
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Application

# 登录 / 账号相关：保留签名，避免内联/重命名导致运行时反射或序列化问题
-keep class com.moxsh.auth.** { *; }

# EncryptedSharedPreferences / security-crypto 依赖反射
-keep class androidx.security.crypto.** { *; }

# ── Tink（由 androidx.security:security-crypto 传递依赖引入）──
# tink-android 的字节码里引用了一批「仅编译期可见」的注解，它们在 release
# 运行期并不需要、也不在 classpath 里，R8 会报
#   "Missing classes detected ... Missing class com.google.errorprone.annotations.*"
# 并以 Compilation failed 中止打包（即使这些类从不被加载到）。
# 这里显式抑制这两组注解缺失告警——缺它们不影响任何运行路径。
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Kotlin 协程 / 序列化基础
-keepattributes *Annotation*, Signature
-keepclassmembers class kotlin.Metadata { *; }

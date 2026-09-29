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

# Kotlin 协程 / 序列化基础
-keepattributes *Annotation*, Signature
-keepclassmembers class kotlin.Metadata { *; }

# dsh-remote-control (composeApp) R8 keep 规则
# 原则：从社区标准规则起手，宁可多 keep 也不误删；每处注明保护对象与原因。
# 若 R8 误删导致缺失类/崩溃，在此追加规则并记录原因（见 commit 记录）。

# ── Kotlin 元数据与注解（序列化/反射/协程依赖）────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, Exceptions
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── kotlinx.serialization：协议 @Serializable 类的序列化器 ────
# 序列化器由插件生成（XX$$serializer + Companion.serializer()），被优化掉会报
# 「Serializer for class ... is not found」。协议类集中在 com.daniel.dshremote.protocol。
-keep,includedescriptorclasses class com.daniel.dshremote.**$$serializer { *; }
-keepclassmembers class com.daniel.dshremote.** {
    *** Companion;
}
-keepclasseswithmembers class com.daniel.dshremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ── 应用入口（manifest 反射实例化 Activity）────────────────────
-keep class com.daniel.dshremote.MainActivity { *; }

# ── zxing 扫码：解码器/相机视图（体量小 ~325K，整体保留最稳）──
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# ── mikepenz Markdown 渲染器：组件表存在反射查找，整体保留 ────
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.**
# 解析后端 org.intellij.markdown 一并整体保留：无法 100% 排除其被反射/字符串名查找，
# 宁可多 keep 也不误删（代价 ~100KB，APK 仍远低于 6.3MB 目标）。
-keep class org.intellij.markdown.** { *; }
-dontwarn org.intellij.**

# ── 网络栈：ktor/okhttp/okio/slf4j 以库自带 consumer rules 为主，此处兜底告警 ──
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**

# ── Compose：依赖 androidx 官方 consumer rules，此处只兜底告警 ──
-dontwarn androidx.compose.**

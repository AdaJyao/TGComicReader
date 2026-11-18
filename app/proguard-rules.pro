# 默认不混淆；保留 Compose / Coil 反射入口
-keep class androidx.compose.** { *; }
-keep class coil.** { *; }
-dontwarn org.jetbrains.annotations.**

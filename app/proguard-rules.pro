# 自己的类名在 manifest / Intent 里按字符串引用，直接保留，避免 R8 改名后找不到
-keep class com.lcs.tgcomic.** { *; }

# WebView 里的 JS 接口（目前没注入，留个保险）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

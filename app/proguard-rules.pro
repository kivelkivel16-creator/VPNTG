# Add project specific ProGuard rules here.

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep data classes used with Gson (DTOs)
-keep class com.v2ray.ang.dto.** { *; }
-keepclassmembers class com.v2ray.ang.dto.** { *; }

# Keep enums
-keep enum com.v2ray.ang.enums.** { *; }

# Keep Firebase models
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep native V2Ray library interfaces
-keep class go.** { *; }
-keep class libv2ray.** { *; }

# Keep MMKV
-keep class com.tencent.mmkv.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Remove debug/verbose logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
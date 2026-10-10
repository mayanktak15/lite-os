# ProGuard rules for Lite TV

# Keep Media3 ExoPlayer classes
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep our SDUI data classes
-keep class com.litetv.app.sdui.SduiConfig** { *; }
-keep class com.litetv.app.iptv.Channel { *; }

# Keep Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# Remove debug logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

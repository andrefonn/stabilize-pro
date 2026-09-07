# Keep OpenCV classes and native JNI methods
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**
-keepclassmembers class * {
    native <methods>;
}

# Media3 ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

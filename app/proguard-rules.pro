# PlayDepth Standard Release R8 Rules

# Preserve source file names and line numbers for readable crash stack traces
-keepattributes SourceFile,LineNumberTable,Exceptions,InnerClasses,Signature,RuntimeVisibleAnnotations

# Keep JNI USB DAC native driver bridge class & all native method signatures intact
-keep class com.quandot.musicplayer.audio.UsbDacNative {
    *;
}
-keep class com.quandot.musicplayer.audio.UsbDacNative$* {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Keep AndroidManifest component entrypoints intact while allowing R8 to obfuscate internal members
-keep class com.quandot.musicplayer.service.PlaybackService
-keep class com.quandot.musicplayer.MainActivity
-keep class com.quandot.musicplayer.MusicApplication

# Preserve Parcelable creators
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Preserve enum values() / valueOf()
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Media3 FFmpeg audio renderer & JNI symbols loaded reflectively by DefaultRenderersFactory
-keep class androidx.media3.decoder.ffmpeg.** {
    *;
}

# Suppress harmless warnings for optional platform classes referenced by libraries
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

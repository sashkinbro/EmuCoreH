# EmuCoreH R8 rules.
#
# This app is a Compose/Kotlin frontend around the bundled Flycast libretro
# core and its JNI bridges (libemucoreh_jni.so and the optional
# libemucoreh_discord.so). Runtime name lookups need explicit keeps:
# JNI entry points, the classes and members the native code resolves by name,
# the Discord partner SDK models instantiated from libdiscord_partner_sdk.so,
# WorkManager workers restored from the database by class name, and
# kotlinx.serialization serializers used for persisted JSON and typed
# Navigation Compose routes. Everything else is shrunk normally.

# Keep readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Signatures/annotations are read at runtime by Room, kotlinx.serialization
# and the Discord SDK.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# --- JNI entry points -------------------------------------------------------
# The native libraries resolve these methods by their mangled names
# (Java_com_sbro_emucoreh_core_NativeCoreBridge_*,
#  Java_com_sbro_emucoreh_discord_DiscordNative_*), so the class and method
# names must stay stable in release builds.
-keep class com.sbro.emucoreh.core.NativeCoreBridge { *; }
-keep class com.sbro.emucoreh.discord.DiscordNative { *; }

# --- Native callbacks into Kotlin -------------------------------------------
# achievements_bridge.cpp resolves the HTTP transport, its static request
# method and the response fields by name from the native HTTP worker thread.
-keep class com.sbro.emucoreh.core.AchievementsHttp { *; }
-keep class com.sbro.emucoreh.core.AchievementsHttpResponse { *; }

# storage_vfs.cpp resolves the SAF bridge and its static open/stat/list
# methods by name.
-keep class com.sbro.emucoreh.core.SafStorageBridge { *; }

# --- Discord Social SDK -----------------------------------------------------
# libdiscord_partner_sdk.so instantiates its Java models and activity classes
# by name, and DiscordAuthActivity looks the engine entry point up through
# reflection.
-keep class com.discord.socialsdk.** { *; }

# --- WorkManager ------------------------------------------------------------
# Enqueued work is restored from the database by worker class name.
-keepnames class * extends androidx.work.ListenableWorker

# --- Firebase / Play services ----------------------------------------------
# Firebase, Play Billing and AndroidX Credentials ship their own consumer
# rules; these dontwarn entries keep the release shrink focused when optional
# integrations are absent from a variant.
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# --- Persisted enums --------------------------------------------------------
# Enum constant names are written to DataStore/JSON (drawer items, game menu
# tabs/sections, texture download status) and matched by name on the next
# launch, so they must not be renamed by R8.
-keepclassmembers enum com.sbro.emucoreh.** {
    *;
}

# --- kotlinx.serialization --------------------------------------------------
# Persisted JSON models and typed Navigation routes decode through generated
# serializers/companions that R8 cannot see statically.
-keepclassmembers class com.sbro.emucoreh.** {
    *** Companion;
}
-keepclasseswithmembers class com.sbro.emucoreh.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sbro.emucoreh.**$$serializer { *; }
-keepclassmembers class **$$serializer {
    public static ** INSTANCE;
}

# The release APK does not emit application logcat diagnostics. R8 also drops
# message construction for these calls when its result has no other consumer.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}

# EmuCoreA R8 rules.
#
# This app is a Compose/Kotlin frontend around the bundled PPSSPP libretro
# core and its JNI bridge (libemucorea_jni.so). Runtime name lookups need explicit keeps:
# JNI entry points, classes resolved by the Discord partner SDK,
# and kotlinx.serialization serializers used for persisted JSON and
# typed Navigation Compose routes. Everything else is shrunk normally.

# Keep readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Signatures/annotations are read at runtime by Room and the Discord SDK.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# --- JNI --------------------------------------------------------------------
# Native entry points are resolved by their mangled names
# (Java_com_sbro_emucorea_core_NativeCoreBridge_*,
#  Java_com_sbro_emucorea_discord_DiscordNative_*), so class and method names
# must stay stable in release builds.
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucorea.core.NativeCoreBridge {
    native <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class com.sbro.emucorea.discord.DiscordNative {
    native <methods>;
}

# --- RetroAchievements ------------------------------------------------------
# achievements_bridge.cpp resolves the HTTP bridge object, its static request
# method and the response fields by name from the native HTTP worker thread.
-keep class com.sbro.emucorea.core.AchievementsHttp { *; }
-keep class com.sbro.emucorea.core.AchievementsHttpResponse { *; }

# --- Discord Social SDK -----------------------------------------------------
# libdiscord_partner_sdk.so instantiates its Java models and activity classes
# by name.
-keep class com.discord.socialsdk.** { *; }

# --- Persisted enums --------------------------------------------------------
# Enum constant names are written to DataStore/JSON (drawer items, game menu
# tabs/sections, texture download status) and matched by name on the next
# launch, so they must not be renamed by R8.
-keepclassmembers enum com.sbro.emucorea.** {
    *;
}

# --- kotlinx.serialization --------------------------------------------------
# Persisted JSON models and typed Navigation routes decode through generated
# serializers/companions that R8 cannot see statically.
-keepclassmembers class com.sbro.emucorea.** {
    *** Companion;
}
-keepclasseswithmembers class com.sbro.emucorea.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sbro.emucorea.**$$serializer { *; }
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

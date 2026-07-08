# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep SSH library classes
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# ----------------------------------------------------------------------------
# BouncyCastle JCA provider (H3)
#
# BouncyCastleProvider (TabSSHApplication.kt / OciKeyMaterial.kt) registers its
# algorithm implementations by fully-qualified string name in the provider
# constructor. R8 full mode has no static reference to those classes, so it
# strips them; the provider then fails to instantiate the algorithm and PEM /
# PKCS8 / OpenSSL key parsing (OciKeyMaterial, KeyStorage) throws on release
# builds only. Keep the whole provider tree and silence the optional-dep warns.
# ----------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ----------------------------------------------------------------------------
# Reflection into SSHConnection.session (H4)
#
# HistoryFetcher, MoshHandoff, PortForwardingManager and SCPClient all reach the
# underlying JSch Session via `sshConnection.javaClass.getDeclaredField("session")`.
# R8 renames/strips that private field in release builds, so the lookup throws
# NoSuchFieldException and port forwarding / SCP / Mosh handoff / history fetch
# break. Keep the field under its source name. (The reflected JSch setEnv method
# in SSHConnection is already covered by the com.jcraft.jsch keep rule above.)
# ----------------------------------------------------------------------------
-keepclassmembers class io.github.tabssh.ssh.connection.SSHConnection {
    private com.jcraft.jsch.Session session;
}

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# JSR-305 annotations — OkHttp references javax.annotation.* at compile time;
# keep them and suppress R8 warnings. (androidx.security-crypto / Tink rules
# were removed with that unused dependency.)
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-keep class javax.annotation.** { *; }
-keep class javax.annotation.concurrent.** { *; }

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# kotlinx.serialization
#
# kotlinx-serialization generates a synthetic `$$serializer` class plus a
# `Companion.serializer()` (or `INSTANCE.serializer()` on @Serializable
# objects) for every `@Serializable` class. Both are looked up reflectively
# at runtime by `serializer<T>()` / `Json.encodeToString(payload)`. R8 in the
# release build strips them as unused, which manifests as:
#
#     kotlinx.serialization.SerializationException:
#       Serializer for class 'SyncDataPackage' is not found.
#
# These rules are the official set from
# https://github.com/Kotlin/kotlinx.serialization/blob/master/rules/common.pro
# Without them, sync upload/download fails on every release build.
# ----------------------------------------------------------------------------

# Annotations are read by the runtime reflection lookups.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep `Companion` object fields of @Serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default + named).
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of @Serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# kotlinx-serialization-json declares its own runtime reflection lookups.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces: every TabSSH @Serializable model lives under one of these
# packages. Even with the rules above, having explicit class keeps documents
# the dependency and survives any kotlinx-serialization rule changes.
-keep,includedescriptorclasses class io.github.tabssh.sync.models.** { *; }
-keep,includedescriptorclasses class io.github.tabssh.themes.definitions.** { *; }
-keep,includedescriptorclasses class io.github.tabssh.pairing.** { *; }
# Note: previous rules referenced `com.tabssh.*` which was the wrong package
# (actual package: `io.github.tabssh.*`). Those rules have always been
# matching nothing — leaving them out rather than fixing them in place
# because the kotlinx.serialization rules above are what actually fix sync.
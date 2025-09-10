# F-Droid specific ProGuard rules for reproducible builds

# Additional optimizations for F-Droid builds
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# Ensure reproducible builds by removing debug info
-keepattributes !SourceFile,!LineNumberTable

# Keep version information
-keep class com.tabssh.BuildConfig { *; }

# F-Droid specific keeps
-keep class com.tabssh.TabSSHApplication { *; }

# Ensure consistent naming for reproducible builds
-useuniqueclassmembernames
-keeppackagenames doNotKeepAThing

# Additional ProGuard rules for deterministic output
-printseeds seeds.txt
-printusage usage.txt
-printmapping mapping.txt
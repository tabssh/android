# F-Droid specific ProGuard rules for reproducible builds

# Additional optimizations for F-Droid builds
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification

# Ensure reproducible builds by removing debug info
-keepattributes !SourceFile,!LineNumberTable

# Keep version information
-keep class io.github.tabssh.BuildConfig { *; }

# F-Droid specific keeps
-keep class io.github.tabssh.TabSSHApplication { *; }

# Ensure consistent naming for reproducible builds
-useuniqueclassmembernames
-keeppackagenames doNotKeepAThing

# Additional ProGuard rules for deterministic output.
# Paths are relative to the app module dir, so bare filenames dropped
# seeds.txt/usage.txt/mapping.txt next to app/build.gradle (the CI workspace),
# where they risked being committed by accident. Point them into the module
# build output tree instead, alongside R8's own mapping.
-printseeds build/outputs/mapping/fdroidRelease/seeds.txt
-printusage build/outputs/mapping/fdroidRelease/usage.txt
-printmapping build/outputs/mapping/fdroidRelease/mapping.txt
# Termux terminal emulator module

This module is vendored from `termux/termux-app` tag `v0.118.1`, commit
`43317b78c920a48254f8846f5e14b5f873faa271`, under the module's Apache 2.0
license. It provides the Java VT emulator and the `libtermux.so` PTY JNI
library used by TabSSH.

TabSSH builds the source locally with Android NDK `27.3.13750724`. The only
native build change adds 16 KB ELF maximum and common page-size linker flags
in `src/main/jni/Android.mk`; this keeps the JNI ABI and Java API at the
pinned upstream revision while making the shared library loadable on 16 KB
page-size devices.

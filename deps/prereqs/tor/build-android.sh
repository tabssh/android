#!/usr/bin/env bash
##@Version 202608310000-git
# deps/prereqs/tor/build-android.sh — Cross-compile the tor client for one Android ABI.
#
# Runs INSIDE the Docker image built from deps/prereqs/tor/Dockerfile.
#
# Usage (from the host):
#   docker build -t tabssh/tor-build deps/prereqs/tor/
#   docker run --rm -v $(pwd)/deps/prereqs/tor:/work tabssh/tor-build \
#     /work/build-android.sh <abi>
#
#   abi ∈ {arm64-v8a, x86_64, armeabi-v7a, x86}
#
# Output: /work/out/<abi>/tor (statically linked).
# To bundle into the APK the host moves it to:
#   app/src/main/jniLibs/<abi>/libtor.so
# (the `lib*.so` filename is required for Android's APK installer to copy
#  the file to nativeLibraryDir; the file is not actually a shared object.)

set -euo pipefail

VERSION="202608310000-git"

ABI="${1:-arm64-v8a}"
# TabSSH has minSdk 24; tor cross-compiles cleanly against API 24 and the
# built-in Tor route is runtime-gated by TorNativeClient.isAvailable().
API_LEVEL="${API_LEVEL:-24}"

case "$ABI" in
  arm64-v8a)    TRIPLE="aarch64-linux-android";    BIN_PREFIX="aarch64-linux-android" ;;
  armeabi-v7a)  TRIPLE="armv7a-linux-androideabi"; BIN_PREFIX="arm-linux-androideabi" ;;
  x86_64)       TRIPLE="x86_64-linux-android";     BIN_PREFIX="x86_64-linux-android" ;;
  x86)          TRIPLE="i686-linux-android";       BIN_PREFIX="i686-linux-android" ;;
  *) echo "Unknown ABI $ABI"; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-/opt/android-ndk}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

NDK_AR="$TOOLCHAIN/bin/llvm-ar"
NDK_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
NDK_STRIP="$TOOLCHAIN/bin/llvm-strip"
NDK_NM="$TOOLCHAIN/bin/llvm-nm"
NDK_CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
NDK_CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"

__use_ndk_toolchain() {
    export AR="$NDK_AR" RANLIB="$NDK_RANLIB" STRIP="$NDK_STRIP" NM="$NDK_NM"
    export CC="$NDK_CC" CXX="$NDK_CXX"
    export CFLAGS="-fPIC -O2" CXXFLAGS="-fPIC -O2" LDFLAGS="-static-libstdc++"
}

# for the final strip step at the end
export STRIP="$NDK_STRIP"
export PATH="$TOOLCHAIN/bin:$PATH"

# tor's configure prints a summary banner whose width probe runs
# `test "$(tput cols)" -ge 80`. In CI $TERM is unset, so `tput cols` emits
# nothing and the probe becomes `test "" -ge 80` → "unary operator expected",
# returning non-zero. config.status has already written the Makefile by then, so
# this is purely cosmetic — but `set -e` treats configure's non-zero exit as
# fatal and aborts before `make`. TERM=dumb gives tput a terminfo entry
# (cols#80) so the probe evaluates cleanly and configure exits 0.
export TERM=dumb

PREFIX="/tmp/build-${ABI}/prefix"
SRC_CACHE="/opt/sources"
BUILD="/tmp/build-${ABI}"
OUT="/work/out/${ABI}"

rm -rf "$BUILD"
mkdir -p "$BUILD" "$PREFIX/lib" "$PREFIX/include" "$OUT"
cd "$BUILD"

echo "═════════════════════════════════════════════════════════════════"
echo "Cross-compiling tor for $ABI (API $API_LEVEL)"
echo "  NDK_CC=$NDK_CC"
echo "  PREFIX=$PREFIX"
echo "═════════════════════════════════════════════════════════════════"

# ── 1. zlib ────────────────────────────────────────────────────────────────
echo "──── zlib 1.3.1 ────"
__use_ndk_toolchain
tar xzf "$SRC_CACHE/zlib-1.3.1.tar.gz"
cd zlib-1.3.1
# zlib's configure has no --host; it honours CC/CFLAGS from the environment.
./configure --prefix="$PREFIX" --static
make -j"$(nproc)" >&2
make install >&2
cd ..

# ── 2. openssl ─────────────────────────────────────────────────────────────
echo "──── openssl 3.0.13 ────"
__use_ndk_toolchain
tar xzf "$SRC_CACHE/openssl-3.0.13.tar.gz"
cd openssl-3.0.13
case "$ABI" in
  arm64-v8a)   OSSL_TARGET="android-arm64" ;;
  armeabi-v7a) OSSL_TARGET="android-arm"   ;;
  x86_64)      OSSL_TARGET="android-x86_64" ;;
  x86)         OSSL_TARGET="android-x86"   ;;
esac
ANDROID_NDK_ROOT="$NDK" \
./Configure "$OSSL_TARGET" \
    -D__ANDROID_API__="$API_LEVEL" \
    --prefix="$PREFIX" \
    --openssldir="$PREFIX/ssl" \
    no-shared no-tests no-asm
make -j"$(nproc)" build_libs >&2
make install_dev >&2
cd ..

# ── 3. libevent ────────────────────────────────────────────────────────────
echo "──── libevent 2.1.13 ────"
__use_ndk_toolchain
tar xzf "$SRC_CACHE/libevent-2.1.13-stable.tar.gz"
cd libevent-2.1.13-stable
# tor uses its own TLS, so libevent's OpenSSL bufferevents aren't needed.
./configure \
    --host="$BIN_PREFIX" \
    --prefix="$PREFIX" \
    --disable-shared --enable-static \
    --disable-openssl --disable-samples \
    --disable-libevent-regress --disable-debug-mode
make -j"$(nproc)" >&2
make install >&2
cd ..

# ── 4. tor ─────────────────────────────────────────────────────────────────
echo "──── tor 0.4.9.11 ────"
__use_ndk_toolchain
tar xzf "$SRC_CACHE/tor-0.4.9.11.tar.gz"
cd tor-0.4.9.11
# --enable-android sets the Android-specific configure defaults so the many
# run-time feature probes don't have to execute on the cross host.
# --enable-static-* + --with-*-dir link our just-built deps statically.
# -ldl: openssl 3.x's DSO layer (crypto/dso/dso_dlfcn.c) references
# dlopen/dlsym/dlclose/dlerror even in a no-shared static build. On Android
# these live in libc, but the static link still needs -ldl to resolve them
# or ld.lld fails with "undefined symbol: dlopen".
LDFLAGS="-static-libstdc++ -L$PREFIX/lib -ldl" \
CPPFLAGS="-I$PREFIX/include" \
./configure \
    --host="$BIN_PREFIX" \
    --prefix="$PREFIX" \
    --enable-android \
    --enable-static-tor \
    --enable-static-libevent --with-libevent-dir="$PREFIX" \
    --enable-static-openssl --with-openssl-dir="$PREFIX" \
    --enable-static-zlib --with-zlib-dir="$PREFIX" \
    --disable-asciidoc \
    --disable-manpage --disable-html-manual \
    --disable-system-torrc \
    --disable-tool-name-check \
    --disable-unittests \
    --disable-lzma --disable-zstd \
    --disable-seccomp \
    --disable-module-relay \
    --disable-module-dirauth \
    ac_cv_func_getentropy=no \
    >&2
# tor 0.4.9.x has no `tor` convenience target — `make tor` fails with
# "No rule to make target 'tor'". Build the binary by its real file target,
# which pulls in only its library prerequisites (no manpages, no unit tests).
make -j"$(nproc)" src/app/tor >&2
# Modern tor emits the client binary at src/app/tor.
cp src/app/tor "$OUT/tor"
"$STRIP" "$OUT/tor" 2>&2 || true
cd ..

echo "═════════════════════════════════════════════════════════════════"
echo "✅ Built $OUT/tor"
file "$OUT/tor" 2>&2 || true
ls -la "$OUT"

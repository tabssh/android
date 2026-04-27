#!/usr/bin/env bash
# mosh/build-android.sh — Cross-compile mosh-client for one Android ABI.
#
# Wave 9.2 — runs INSIDE the Docker image built from mosh/Dockerfile.
#
# Usage (from the host):
#   docker build -t tabssh/mosh-build mosh/
#   docker run --rm -v $(pwd)/mosh:/work tabssh/mosh-build \
#     /work/build-android.sh <abi>
#
#   abi ∈ {arm64-v8a, x86_64, armeabi-v7a, x86}
#
# Output: /work/out/<abi>/mosh-client (statically linked).
# To bundle into the APK the host moves it to:
#   app/src/main/jniLibs/<abi>/libmosh-client.so
# (the `lib*.so` filename is required for Android's APK installer to copy
#  the file to nativeLibraryDir; the file is not actually a shared object.)

set -euo pipefail

ABI="${1:-arm64-v8a}"
# Mosh needs API 26+ — `nl_langinfo` was only added to Bionic in API 26.
# TabSSH itself has minSdk 24, but Mosh tab will be runtime-gated on
# devices below API 26 (the binary won't run there; SSH still works).
API_LEVEL="${API_LEVEL:-26}"

case "$ABI" in
  arm64-v8a)    TRIPLE="aarch64-linux-android";    BIN_PREFIX="aarch64-linux-android" ;;
  armeabi-v7a)  TRIPLE="armv7a-linux-androideabi"; BIN_PREFIX="arm-linux-androideabi" ;;
  x86_64)       TRIPLE="x86_64-linux-android";     BIN_PREFIX="x86_64-linux-android" ;;
  x86)          TRIPLE="i686-linux-android";       BIN_PREFIX="i686-linux-android" ;;
  *) echo "Unknown ABI $ABI"; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-/opt/android-ndk}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"

# NDK toolchain — applied per-step so host builds (abseil/protoc Pass 1)
# don't accidentally cross-compile and pull in AndroidLogSink.
NDK_AR="$TOOLCHAIN/bin/llvm-ar"
NDK_RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
NDK_STRIP="$TOOLCHAIN/bin/llvm-strip"
NDK_NM="$TOOLCHAIN/bin/llvm-nm"
NDK_CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
NDK_CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
NDK_CFLAGS="-fPIC -O2"
NDK_CXXFLAGS="-fPIC -O2"
NDK_LDFLAGS="-static-libstdc++"

use_ndk_toolchain() {
    export AR="$NDK_AR" RANLIB="$NDK_RANLIB" STRIP="$NDK_STRIP" NM="$NDK_NM"
    export CC="$NDK_CC" CXX="$NDK_CXX"
    export CFLAGS="$NDK_CFLAGS" CXXFLAGS="$NDK_CXXFLAGS" LDFLAGS="$NDK_LDFLAGS"
}

use_host_toolchain() {
    unset AR RANLIB STRIP NM CC CXX CFLAGS CXXFLAGS LDFLAGS
}

export STRIP="$NDK_STRIP"  # for the final strip step at the end

PREFIX="/tmp/build-${ABI}/prefix"
SRC_CACHE="/opt/sources"
BUILD="/tmp/build-${ABI}"
OUT="/work/out/${ABI}"

rm -rf "$BUILD"
mkdir -p "$BUILD" "$PREFIX/lib" "$PREFIX/include" "$OUT"
cd "$BUILD"

echo "═════════════════════════════════════════════════════════════════"
echo "Cross-compiling mosh-client for $ABI (API $API_LEVEL)"
echo "  NDK_CC=$NDK_CC"
echo "  PREFIX=$PREFIX"
echo "═════════════════════════════════════════════════════════════════"

# ── 1. ncurses ────────────────────────────────────────────────────────────
echo "──── ncurses 6.4 ────"
use_ndk_toolchain
tar xzf "$SRC_CACHE/ncurses-6.4.tar.gz"
cd ncurses-6.4
./configure \
    --host="$BIN_PREFIX" \
    --prefix="$PREFIX" \
    --without-shared --with-normal --without-debug \
    --without-progs --without-tests --without-manpages \
    --disable-db-install --without-cxx-binding \
    --disable-stripping
make -j"$(nproc)" libs >&2
make install.libs install.includes >&2
cd ..

# ── 2. openssl ────────────────────────────────────────────────────────────
echo "──── openssl 3.0.13 ────"
use_ndk_toolchain
tar xzf "$SRC_CACHE/openssl-3.0.13.tar.gz"
cd openssl-3.0.13
case "$ABI" in
  arm64-v8a)   OSSL_TARGET="android-arm64" ;;
  armeabi-v7a) OSSL_TARGET="android-arm"   ;;
  x86_64)      OSSL_TARGET="android-x86_64" ;;
  x86)         OSSL_TARGET="android-x86"   ;;
esac
PATH="$TOOLCHAIN/bin:$PATH" \
ANDROID_NDK_ROOT="$NDK" \
./Configure "$OSSL_TARGET" \
    -D__ANDROID_API__="$API_LEVEL" \
    --prefix="$PREFIX" \
    --openssldir="$PREFIX/ssl" \
    no-shared no-tests no-asm
PATH="$TOOLCHAIN/bin:$PATH" make -j"$(nproc)" build_libs >&2
PATH="$TOOLCHAIN/bin:$PATH" make install_dev >&2
cd ..

# ── 3. protobuf 21.12 (cross + host protoc) ─────────────────────────────
# Sticking to 21.12 — the last protobuf without abseil-cpp dependency.
# 22+ requires building abseil for both host and target separately and
# the find_package(absl) dance fights the NDK toolchain. mosh-client
# doesn't care about protobuf version (it just needs *some* protoc).
echo "──── protobuf 21.12 (cross + host protoc) ────"
tar xzf "$SRC_CACHE/protobuf-cpp-3.21.12.tar.gz"
cd protobuf-3.21.12

# Pass 1 — host build of protoc (needed to generate sources during cross-build)
use_host_toolchain
mkdir build-host && cd build-host
cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_EXAMPLES=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    -Dprotobuf_INSTALL=OFF \
    >&2
cmake --build . --target protoc -j"$(nproc)" >&2
HOST_PROTOC="$(pwd)/protoc"
cd ..

# Pass 2 — cross-compile static libraries for Android.
use_ndk_toolchain
mkdir build-android && cd build-android
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17 \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_EXAMPLES=OFF \
    -Dprotobuf_BUILD_PROTOC_BINARIES=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF \
    >&2
cmake --build . -j"$(nproc)" >&2
cmake --install . >&2
cd ../..

# ── 4. mosh ───────────────────────────────────────────────────────────────
echo "──── mosh 1.4.0 ────"
use_ndk_toolchain
tar xzf "$SRC_CACHE/mosh-1.4.0.tar.gz"
cd mosh-mosh-1.4.0
./autogen.sh >&2
PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig" \
PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" \
PROTOC="$HOST_PROTOC" \
LDFLAGS="-static-libstdc++ -L$PREFIX/lib" \
CPPFLAGS="-I$PREFIX/include" \
LIBS="-lcrypto -lssl -lncurses -lprotobuf" \
./configure \
    --host="$BIN_PREFIX" \
    --prefix="$PREFIX" \
    --enable-client \
    --disable-server \
    --disable-completion \
    --disable-hardening \
    >&2
# Build only the client; server isn't needed (it runs on the remote, the
# user already has mosh-server there).
make -C src/protobufs -j"$(nproc)" >&2
make -C src/network -j"$(nproc)" >&2
make -C src/util -j"$(nproc)" >&2
make -C src/crypto -j"$(nproc)" >&2
make -C src/statesync -j"$(nproc)" >&2
make -C src/terminal -j"$(nproc)" >&2
make -C src/frontend mosh-client >&2
cp src/frontend/mosh-client "$OUT/mosh-client"
"$STRIP" "$OUT/mosh-client" 2>&2 || true
cd ..

echo "═════════════════════════════════════════════════════════════════"
echo "✅ Built $OUT/mosh-client"
file "$OUT/mosh-client" 2>&2 || true
ls -la "$OUT"

#!/usr/bin/env bash
# spice/build-android.sh — Cross-compile the SPICE client stack and link the
# TabSSH JNI bridge into libtabssh_native.so for one Android ABI.
#
# Runs INSIDE the Docker image built from spice/Dockerfile.
#
# Usage (from the host):
#   docker build -t tabssh/spice-build spice/
#   docker run --rm -v $(pwd)/spice:/work tabssh/spice-build \
#     /work/build-android.sh <abi>
#
#   abi in {arm64-v8a, armeabi-v7a, x86_64, x86}
#
# Output: /work/out/<abi>/libtabssh_native.so — a single self-contained
# shared object (all of glib/spice/pixman/openssl/opus static-linked in).
# The host / fetch script drops it at app/src/main/jniLibs/<abi>/.
#
# API level 26: glib and spice-gtk both call nl_langinfo, added to Bionic
# in API 26. TabSSH minSdk is 24, so the SPICE tab is runtime-gated by
# SpiceLoader — devices below 26 simply fall back to VNC, exactly like mosh.

set -euo pipefail

ABI="${1:-arm64-v8a}"
API_LEVEL="${API_LEVEL:-26}"

case "$ABI" in
  arm64-v8a)
    TRIPLE="aarch64-linux-android";    BIN_PREFIX="aarch64-linux-android"
    MESON_CPU_FAMILY="aarch64";         MESON_CPU="aarch64";  OSSL_TARGET="android-arm64" ;;
  armeabi-v7a)
    TRIPLE="armv7a-linux-androideabi"; BIN_PREFIX="arm-linux-androideabi"
    MESON_CPU_FAMILY="arm";             MESON_CPU="armv7";    OSSL_TARGET="android-arm" ;;
  x86_64)
    TRIPLE="x86_64-linux-android";     BIN_PREFIX="x86_64-linux-android"
    MESON_CPU_FAMILY="x86_64";          MESON_CPU="x86_64";   OSSL_TARGET="android-x86_64" ;;
  x86)
    TRIPLE="i686-linux-android";       BIN_PREFIX="i686-linux-android"
    MESON_CPU_FAMILY="x86";             MESON_CPU="i686";     OSSL_TARGET="android-x86" ;;
  *) echo "Unknown ABI $ABI"; exit 1 ;;
esac

NDK="${ANDROID_NDK_HOME:-/opt/android-ndk}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$TOOLCHAIN/bin:$PATH"

CC="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/${TRIPLE}${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
NM="$TOOLCHAIN/bin/llvm-nm"

PREFIX="/tmp/build-${ABI}/prefix"
SRC_CACHE="/opt/sources"
BUILD="/tmp/build-${ABI}"
OUT="/work/out/${ABI}"
JOBS="$(nproc)"

rm -rf "$BUILD"
mkdir -p "$BUILD" "$PREFIX/lib/pkgconfig" "$PREFIX/include" "$OUT"

export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"

echo "═════════════════════════════════════════════════════════════════"
echo "Cross-compiling SPICE stack + JNI bridge for $ABI (API $API_LEVEL)"
echo "  CC=$CC"
echo "  PREFIX=$PREFIX"
echo "═════════════════════════════════════════════════════════════════"

# Meson cross file shared by every meson-based dependency.
CROSS_FILE="$BUILD/android-${ABI}.cross"
cat > "$CROSS_FILE" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = '$MESON_CPU_FAMILY'
cpu = '$MESON_CPU'
endian = 'little'

[properties]
pkg_config_libdir = '$PREFIX/lib/pkgconfig'
sys_root = '$TOOLCHAIN/sysroot'

[built-in options]
prefix = '$PREFIX'
libdir = 'lib'
default_library = 'static'
c_args = ['-fPIC', '-O2', '-D__ANDROID_API__=$API_LEVEL']
cpp_args = ['-fPIC', '-O2', '-D__ANDROID_API__=$API_LEVEL']
EOF

__autotools_env() {
    export CC CXX AR RANLIB STRIP NM
    export CFLAGS="-fPIC -O2 -D__ANDROID_API__=$API_LEVEL"
    export CXXFLAGS="$CFLAGS"
    export CPPFLAGS="-I$PREFIX/include"
    export LDFLAGS="-L$PREFIX/lib"
}

cd "$BUILD"

# ── 1. zlib ────────────────────────────────────────────────────────────────
echo "──── zlib 1.3.1 ────"
__autotools_env
tar xzf "$SRC_CACHE/zlib-1.3.1.tar.gz"
cd zlib-1.3.1
CHOST="$BIN_PREFIX" ./configure --prefix="$PREFIX" --static
make -j"$JOBS" >&2
make install >&2
cd ..

# ── 2. libffi ──────────────────────────────────────────────────────────────
echo "──── libffi 3.4.6 ────"
__autotools_env
tar xzf "$SRC_CACHE/libffi-3.4.6.tar.gz"
cd libffi-3.4.6
./configure --host="$BIN_PREFIX" --prefix="$PREFIX" \
    --enable-static --disable-shared --disable-docs
make -j"$JOBS" >&2
make install >&2
cd ..

# ── 3. pcre2 ───────────────────────────────────────────────────────────────
echo "──── pcre2 10.44 ────"
__autotools_env
tar xzf "$SRC_CACHE/pcre2-10.44.tar.gz"
cd pcre2-10.44
./configure --host="$BIN_PREFIX" --prefix="$PREFIX" \
    --enable-static --disable-shared \
    --enable-pcre2-8 --disable-pcre2-16 --disable-pcre2-32
make -j"$JOBS" >&2
make install >&2
cd ..

# ── 4. openssl ─────────────────────────────────────────────────────────────
echo "──── openssl 3.0.13 ────"
tar xzf "$SRC_CACHE/openssl-3.0.13.tar.gz"
cd openssl-3.0.13
ANDROID_NDK_ROOT="$NDK" \
./Configure "$OSSL_TARGET" -D__ANDROID_API__="$API_LEVEL" \
    --prefix="$PREFIX" --openssldir="$PREFIX/ssl" \
    no-shared no-tests no-asm
make -j"$JOBS" build_libs >&2
make install_dev >&2
cd ..

# ── 5. opus ────────────────────────────────────────────────────────────────
echo "──── opus 1.5.2 ────"
__autotools_env
tar xzf "$SRC_CACHE/opus-1.5.2.tar.gz"
cd opus-1.5.2
./configure --host="$BIN_PREFIX" --prefix="$PREFIX" \
    --enable-static --disable-shared --disable-doc --disable-extra-programs
make -j"$JOBS" >&2
make install >&2
cd ..

# ── 6. pixman ──────────────────────────────────────────────────────────────
echo "──── pixman 0.43.4 ────"
tar xzf "$SRC_CACHE/pixman-0.43.4.tar.gz"
cd pixman-0.43.4
meson setup _build --cross-file "$CROSS_FILE" \
    -Dtests=disabled -Ddemos=disabled -Dgtk=disabled
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 7. glib ────────────────────────────────────────────────────────────────
echo "──── glib 2.80.4 ────"
tar xf "$SRC_CACHE/glib-2.80.4.tar.xz"
cd glib-2.80.4
meson setup _build --cross-file "$CROSS_FILE" \
    -Dtests=false -Dnls=disabled -Dselinux=disabled -Dxattr=false \
    -Dlibmount=disabled -Dman-pages=disabled -Dsysprof=disabled \
    -Dintrospection=disabled -Dglib_debug=disabled
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 8. json-glib ───────────────────────────────────────────────────────────
echo "──── json-glib 1.8.0 ────"
tar xf "$SRC_CACHE/json-glib-1.8.0.tar.xz"
cd json-glib-1.8.0
meson setup _build --cross-file "$CROSS_FILE" \
    -Dtests=false -Dintrospection=disabled -Ddocs=disabled -Dnls=disabled
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 9. spice-protocol (headers only) ───────────────────────────────────────
echo "──── spice-protocol 0.14.4 ────"
tar xf "$SRC_CACHE/spice-protocol-0.14.4.tar.xz"
cd spice-protocol-0.14.4
meson setup _build --cross-file "$CROSS_FILE"
meson install -C _build >&2
cd ..

# ── 10. spice-gtk (spice-client-glib only, no GTK) ─────────────────────────
echo "──── spice-gtk 0.42 ────"
tar xf "$SRC_CACHE/spice-gtk-0.42.tar.xz"
cd spice-gtk-0.42
meson setup _build --cross-file "$CROSS_FILE" \
    -Dgtk=disabled -Dvapi=disabled -Dintrospection=disabled \
    -Dusbredir=disabled -Dsmartcard=disabled -Dpolkit=disabled \
    -Dsasl=disabled -Dgstreamer=no -Dwebdav=disabled -Dlz4=false \
    -Dcelt051=disabled -Dtests=false -Dcoroutine=gthread
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 11. JNI bridge → libtabssh_native.so ───────────────────────────────────
# Compile the thunk layer + the real glib bridge, then link everything
# static into one shared object. TABSSH_SPICE_AVAILABLE=1 selects the real
# implementation path in spice_client.c.
echo "──── libtabssh_native.so (JNI bridge) ────"
GLIB_CFLAGS="$(pkg-config --cflags spice-client-glib-2.0)"
STATIC_LIBS="$(pkg-config --static --libs spice-client-glib-2.0)"

# GLIB_CFLAGS and STATIC_LIBS are multi-token compiler/linker flag lists
# from pkg-config; they MUST word-split into separate argv entries, so the
# unquoted expansion is intentional and correct here.
# shellcheck disable=SC2086
"$CC" -fPIC -O2 -shared -D__ANDROID_API__="$API_LEVEL" \
    -DTABSSH_SPICE_AVAILABLE=1 \
    $GLIB_CFLAGS \
    /work/cpp/spice_client.c /work/cpp/spice_client_glib.c \
    -o "$OUT/libtabssh_native.so" \
    -Wl,--start-group \
    $STATIC_LIBS \
    -Wl,--end-group \
    -llog -landroid
"$STRIP" "$OUT/libtabssh_native.so"

echo "═════════════════════════════════════════════════════════════════"
echo "✅ Built $OUT/libtabssh_native.so"
file "$OUT/libtabssh_native.so" || true
ls -la "$OUT"

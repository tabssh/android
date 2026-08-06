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
mkdir -p "$BUILD" "$PREFIX/lib/pkgconfig" "$PREFIX/share/pkgconfig" "$PREFIX/include" "$OUT"

# spice-protocol (and some others) install their .pc under share/pkgconfig,
# not lib/pkgconfig. PKG_CONFIG_LIBDIR replaces the default search path
# entirely, so both dirs must be listed or spice-gtk's spice-common
# subproject fails with "Dependency spice-protocol not found".
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"

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
pkg_config_libdir = '$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig'

# No sys_root here: meson feeds this property to pkg-config as
# PKG_CONFIG_SYSROOT_DIR, which prefixes every absolute -I/-L path
# pkg-config emits with this sysroot. Our own .pc files (glib, json-glib,
# spice-protocol, ...) already carry absolute paths under $PREFIX, not
# NDK-sysroot-relative ones, so that prefixing corrupts them (e.g.
# glib-object.h "not found" because -I becomes
# \$sysroot/tmp/build-*/prefix/include instead of /tmp/build-*/prefix/include).
# The versioned clang wrapper (\${TRIPLE}\${API_LEVEL}-clang) already bakes
# in the correct NDK sysroot for system headers/libs on its own, so meson
# doesn't need to know about it separately.

[built-in options]
prefix = '$PREFIX'
libdir = 'lib'
default_library = 'static'
# No -D__ANDROID_API__ here: the versioned clang wrapper
# (\${TRIPLE}\${API_LEVEL}-clang) already predefines it internally, and
# redefining it triggers -Wmacro-redefined, which glib's meson size_t
# probe treats as fatal because it builds its test with -Werror.
#
# c_link_args/-I: glib's meson.build looks up iconv via
# dependency('iconv'), which resolves through cc.find_library() +
# cc.has_header(), NOT pkg-config. Our static libiconv lives in
# \$PREFIX, which isn't on the NDK sysroot's default search path, so it
# must be added explicitly here for that probe to find it.
c_args = ['-fPIC', '-O2', '-I$PREFIX/include']
cpp_args = ['-fPIC', '-O2', '-I$PREFIX/include']
c_link_args = ['-L$PREFIX/lib']
cpp_link_args = ['-L$PREFIX/lib']
EOF

# No -D__ANDROID_API__ here either, for the same reason as the cross file
# above — the versioned clang wrapper already defines it.
__autotools_env() {
    export CC CXX AR RANLIB STRIP NM
    export CFLAGS="-fPIC -O2"
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

# ── 6. libiconv ────────────────────────────────────────────────────────────
# Bionic has no iconv() at API 26 (added at API 28). glib's meson.build
# requires dependency('iconv') unconditionally on non-Windows, so build a
# static libiconv here; the cross file above points meson's find_library
# probe at $PREFIX for it.
echo "──── libiconv 1.18 ────"
__autotools_env
tar xzf "$SRC_CACHE/libiconv-1.18.tar.gz"
cd libiconv-1.18
./configure --host="$BIN_PREFIX" --prefix="$PREFIX" \
    --enable-static --disable-shared
make -j"$JOBS" >&2
make install >&2
cd ..

# ── 7. pixman ──────────────────────────────────────────────────────────────
echo "──── pixman 0.43.4 ────"
tar xzf "$SRC_CACHE/pixman-0.43.4.tar.gz"
cd pixman-0.43.4
meson setup _build --cross-file "$CROSS_FILE" \
    -Dtests=disabled -Ddemos=disabled -Dgtk=disabled
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 8. glib ────────────────────────────────────────────────────────────────
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

# ── 9. libjpeg-turbo ───────────────────────────────────────────────────────
# spice-gtk requires libjpeg (pkg-config: libjpeg). Cross-build static via
# the NDK's own CMake toolchain file. SIMD is disabled so the image needs no
# nasm/yasm cross assembler; decode is a touch slower but correct on all ABIs.
echo "──── libjpeg-turbo 3.0.4 ────"
tar xzf "$SRC_CACHE/libjpeg-turbo-3.0.4.tar.gz"
cd libjpeg-turbo-3.0.4
cmake -G Ninja -B _build \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API_LEVEL" \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DENABLE_SHARED=FALSE -DENABLE_STATIC=TRUE \
    -DWITH_SIMD=FALSE -DWITH_TURBOJPEG=FALSE >&2
cmake --build _build -j"$JOBS" >&2
cmake --install _build >&2
cd ..

# ── 10. json-glib ──────────────────────────────────────────────────────────
echo "──── json-glib 1.8.0 ────"
tar xf "$SRC_CACHE/json-glib-1.8.0.tar.xz"
cd json-glib-1.8.0
meson setup _build --cross-file "$CROSS_FILE" \
    -Dtests=false -Dintrospection=disabled -Dgtk_doc=disabled -Dnls=disabled
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 11. spice-protocol (headers only) ───────────────────────────────────────
echo "──── spice-protocol 0.14.4 ────"
tar xf "$SRC_CACHE/spice-protocol-0.14.4.tar.xz"
cd spice-protocol-0.14.4
meson setup _build --cross-file "$CROSS_FILE"
meson install -C _build >&2
cd ..

# ── 12. spice-gtk (spice-client-glib only, no GTK) ─────────────────────────
echo "──── spice-gtk 0.42 ────"
tar xf "$SRC_CACHE/spice-gtk-0.42.tar.xz"
cd spice-gtk-0.42
# spice-gtk 0.42 has no option to disable GStreamer: meson.build requires
# the full gstreamer-1.0 stack unconditionally, and channel-display-gst.c +
# spice-gstaudio.c are always compiled. Cross-building all of GStreamer
# static for Android would defeat the single self-contained .so model, so
# we patch it out instead:
#   1. blank the gstreamer dependency list (foreach over [] is a no-op);
#   2. drop the two gst source files from the build.
# The two symbols they defined (create_gstreamer_decoder, spice_gstaudio_new)
# are provided by /work/cpp/spice_gst_stubs.c at the final link — they stay
# unresolved in the static lib until then. MJPEG video still decodes via the
# builtin libjpeg path (-Dbuiltin-mjpeg=true); see spice_gst_stubs.c.
sed -i "s/^deps = \['gstreamer-1.0'.*/deps = []/" meson.build
sed -i "/'channel-display-gst.c',/d; /'spice-gstaudio.c',/d; /'spice-gstaudio.h',/d" src/meson.build
# channel-display.c stays in the build and still pulls in a little GStreamer:
# the priv header includes <gst/gst.h>, and the file names GstPipeline /
# GST_TYPE_PIPELINE in one signal + the (now-uncalled) hand_pipeline_to_widget.
# Drop the header and swap those two type references for generic pointer types
# so the file compiles with no GStreamer headers. gst_opts[] is plain data and
# gstvideo_has_codec() is provided by spice_gst_stubs.c.
sed -i "/#include <gst\/gst.h>/d" src/channel-display-priv.h
sed -i "s/GstPipeline \*pipeline/void *pipeline/g" \
    src/channel-display-priv.h src/channel-display.c
sed -i "s/GST_TYPE_PIPELINE/G_TYPE_POINTER/g" src/channel-display.c
# Don't build spice-gtk's own tools/tests: they are executables linked with
# -Wl,--no-undefined, so the three GStreamer stub symbols (resolved only at
# our final libtabssh_native.so link) leave them unresolved. We only need the
# static library + headers + .pc, so skip those subdirs entirely.
sed -i "/^subdir('tools')/d; /^subdir('tests')/d" meson.build
# gstreamer/celt051/tests are not real spice-gtk 0.42 meson options (that
# option list only has: gtk, wayland-protocols, webdav, builtin-mjpeg,
# usbredir, libcap-ng, polkit, pie, usb-acl-helper-dir, usb-ids-path,
# coroutine, introspection, vapi, alignment-checks, lz4, sasl, opus,
# smartcard, egl, gtk_doc, recorder, valgrind) — passing them is a hard
# "Unknown option" error, not a silent no-op. lz4 is a feature, not a
# boolean, so it takes disabled/enabled/auto, not true/false.
meson setup _build --cross-file "$CROSS_FILE" \
    -Dgtk=disabled -Dvapi=disabled -Dintrospection=disabled \
    -Dusbredir=disabled -Dsmartcard=disabled -Dpolkit=disabled \
    -Dsasl=disabled -Dwebdav=disabled -Dlz4=disabled \
    -Dgtk_doc=disabled -Degl=disabled -Dcoroutine=gthread \
    -Dbuiltin-mjpeg=true \
    -Dspice-common:tests=false
meson compile -C _build -j"$JOBS" >&2
meson install -C _build >&2
cd ..

# ── 13. JNI bridge → libtabssh_native.so ───────────────────────────────────
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
# No -D__ANDROID_API__ here either — see the cross-file comment above.
"$CC" -fPIC -O2 -shared \
    -DTABSSH_SPICE_AVAILABLE=1 \
    $GLIB_CFLAGS \
    /work/cpp/spice_client.c /work/cpp/spice_client_glib.c \
    /work/cpp/spice_gst_stubs.c \
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

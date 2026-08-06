# spice/Dockerfile — Reproducible NDK cross-compile environment for the
# SPICE client stack and the TabSSH JNI bridge (libtabssh_native.so).
#
# Mirrors mosh/Dockerfile: we build the whole libspice-client-glib
# dependency chain ourselves rather than relying on distro prebuilts,
# because none ship for Android and the versions must match what the JNI
# bridge (spice/cpp/spice_client_glib.c) links against. Everything is
# static-linked into a single self-contained libtabssh_native.so per ABI
# so the APK needs no additional runtime .so and runs no native toolchain
# at package time.
#
# Dependency chain (build order): zlib → libffi → pcre2 → glib →
# pixman → openssl → opus → json-glib → spice-protocol → spice-gtk
# (spice-client-glib-2.0) → link libtabssh_native.so.
#
# Total disk: ~6 GB during build. Time: ~40-60 min per ABI on a modern
# x86 box (glib + spice-gtk dominate).
#
# Usage:
#   docker build -t tabssh/spice-build spice/
#   docker run --rm -v $(pwd)/spice:/work tabssh/spice-build \
#     /work/build-android.sh arm64-v8a
#
# Output: /work/out/<abi>/libtabssh_native.so
#
# NOTE: cross-compiling glib + spice-gtk for Android via meson is
# genuinely fiddly (meson cross-file properties, disabled i18n/introspection,
# gthread coroutines). This recipe is the starting point; the spice-libs.yml
# workflow is where it gets shaken out end-to-end on real CI runners.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    autoconf \
    automake \
    libtool \
    pkg-config \
    cmake \
    ninja-build \
    python3 \
    python3-pip \
    python3-setuptools \
    git \
    perl \
    wget \
    unzip \
    ca-certificates \
    file \
    xz-utils \
    gettext \
    && rm -rf /var/lib/apt/lists/*

# meson from pip — Debian bookworm's meson is too old for recent glib.
RUN pip3 install --break-system-packages --no-cache-dir "meson>=1.3.0"

# Android NDK r26d (matches mosh/Dockerfile and the app's toolchain).
ENV NDK_VERSION=r26d
ENV ANDROID_NDK_HOME=/opt/android-ndk
RUN wget -q https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip -O /tmp/ndk.zip && \
    unzip -q /tmp/ndk.zip -d /opt && \
    mv /opt/android-ndk-${NDK_VERSION} ${ANDROID_NDK_HOME} && \
    rm /tmp/ndk.zip

# Pre-cache source tarballs so repeat builds don't re-download.
WORKDIR /opt/sources
RUN wget -q https://zlib.net/zlib-1.3.1.tar.gz && \
    wget -q https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz && \
    wget -q https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.44/pcre2-10.44.tar.gz && \
    wget -q https://download.gnome.org/sources/glib/2.80/glib-2.80.4.tar.xz && \
    wget -q https://www.cairographics.org/releases/pixman-0.43.4.tar.gz && \
    wget -q https://www.openssl.org/source/openssl-3.0.13.tar.gz && \
    wget -q https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz && \
    wget -q https://download.gnome.org/sources/json-glib/1.8/json-glib-1.8.0.tar.xz && \
    wget -q https://www.spice-space.org/download/releases/spice-protocol-0.14.4.tar.xz && \
    wget -q https://www.spice-space.org/download/gtk/spice-gtk-0.42.tar.xz

WORKDIR /work
ENTRYPOINT ["/bin/bash"]

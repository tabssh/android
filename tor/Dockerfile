# tor/Dockerfile — Reproducible NDK cross-compile environment for tor.
#
# Produces a statically-linked `tor` client binary per Android ABI, bundled
# into the APK as app/src/main/jniLibs/<abi>/libtor.so (the `lib*.so` naming
# trick makes Android's installer unpack it to nativeLibraryDir and mark it
# executable — it is a real ELF executable, not a shared object, exactly like
# the mosh-client binary).
#
# We build the whole dependency chain ourselves (zlib, openssl, libevent) and
# link tor statically so there is no runtime ld_library_path dance on Android.
#
# Total disk: ~5 GB during build (NDK + sources + intermediate objects).
# Total build time: ~30 min per ABI on a modern x86 box.
#
# Usage:
#   docker build -t tabssh/tor-build tor/
#   docker run --rm -v $(pwd)/tor:/work tabssh/tor-build \
#     /work/build-android.sh x86_64
#
# Output ends up at /work/out/<abi>/tor.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    autoconf \
    automake \
    libtool \
    pkg-config \
    git \
    perl \
    wget \
    unzip \
    ca-certificates \
    python3 \
    file \
    xz-utils \
    && rm -rf /var/lib/apt/lists/*

# Android NDK r26d (October 2024 LTS — supports API 21+ / 16KB pages on r26+).
ENV NDK_VERSION=r26d
ENV ANDROID_NDK_HOME=/opt/android-ndk
RUN wget -q https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip -O /tmp/ndk.zip && \
    unzip -q /tmp/ndk.zip -d /opt && \
    mv /opt/android-ndk-${NDK_VERSION} ${ANDROID_NDK_HOME} && \
    rm /tmp/ndk.zip

# Pre-cache source tarballs so repeat builds don't re-download.
# openssl 3.0.13 matches the version the mosh build pins, keeping one audited
# crypto source across both native binaries.
WORKDIR /opt/sources
RUN wget -q https://zlib.net/zlib-1.3.1.tar.gz && \
    wget -q https://www.openssl.org/source/openssl-3.0.13.tar.gz && \
    wget -q https://github.com/libevent/libevent/releases/download/release-2.1.13-stable/libevent-2.1.13-stable.tar.gz && \
    wget -q https://dist.torproject.org/tor-0.4.9.11.tar.gz

WORKDIR /work
ENTRYPOINT ["/bin/bash"]

# mosh/Dockerfile — Reproducible NDK cross-compile environment for mosh-client.
#
# Wave 9.2 — produces a statically-linked mosh-client binary per Android ABI.
# We build deps ourselves rather than relying on prebuilts because:
#   - mosh needs specific protobuf-Lite + abseil-cpp + ncurses + openssl
#     versions that must match what mosh-client links against
#   - Static linking removes the runtime ld_library_path dance that caused
#     so much trouble in Termux + standalone modes
#
# Total disk: ~5 GB during build (NDK + sources + intermediate objects).
# Total build time: ~45 min per ABI on a modern x86 box.
#
# Usage:
#   docker build -t tabssh/mosh-build mosh/
#   docker run --rm -v $(pwd)/mosh:/work tabssh/mosh-build \
#     /work/build-android.sh x86_64
#
# Output ends up at /work/out/<abi>/mosh-client.

FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    autoconf \
    automake \
    libtool \
    pkg-config \
    cmake \
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
WORKDIR /opt/sources
RUN wget -q https://invisible-mirror.net/archives/ncurses/ncurses-6.4.tar.gz && \
    wget -q https://www.openssl.org/source/openssl-3.0.13.tar.gz && \
    wget -q https://github.com/protocolbuffers/protobuf/releases/download/v25.3/protobuf-25.3.tar.gz && \
    wget -q https://github.com/abseil/abseil-cpp/releases/download/20240116.2/abseil-cpp-20240116.2.tar.gz && \
    wget -q https://github.com/mobile-shell/mosh/archive/refs/tags/mosh-1.4.0.tar.gz

WORKDIR /work
ENTRYPOINT ["/bin/bash"]

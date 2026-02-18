# TabSSH Android Build Image
# Pre-built with all tools for fast CI/CD builds
# Usage: docker pull ghcr.io/tabssh/android:build

FROM eclipse-temurin:17-jdk

LABEL org.opencontainers.image.source=https://github.com/tabssh/android
LABEL org.opencontainers.image.description="TabSSH Android build environment"

# Install required packages
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget \
    unzip \
    git \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Download and install Android SDK command line tools
# The zip extracts to cmdline-tools/, we need it at cmdline-tools/latest/
RUN mkdir -p $ANDROID_SDK_ROOT && \
    cd $ANDROID_SDK_ROOT && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip && \
    unzip -q cmdline-tools.zip -d cmdline-tools-tmp && \
    mkdir -p cmdline-tools && \
    mv cmdline-tools-tmp/cmdline-tools cmdline-tools/latest && \
    rm -rf cmdline-tools.zip cmdline-tools-tmp

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true
RUN sdkmanager --install \
    "platform-tools" \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    > /dev/null 2>&1

# Pre-download Gradle 8.11.1 to speed up builds
ENV GRADLE_VERSION=8.11.1
ENV GRADLE_HOME=/opt/gradle
ENV PATH=$PATH:$GRADLE_HOME/bin
RUN mkdir -p $GRADLE_HOME && \
    wget -q https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -O /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    mv /opt/gradle-${GRADLE_VERSION}/* $GRADLE_HOME && \
    rm -rf /opt/gradle-${GRADLE_VERSION} /tmp/gradle.zip

# Create gradle user home for caching
ENV GRADLE_USER_HOME=/root/.gradle
RUN mkdir -p $GRADLE_USER_HOME

# Set working directory
WORKDIR /workspace

# Default command
CMD ["gradle", "--version"]

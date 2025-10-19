FROM openjdk:17-jdk-slim

# Install required packages for Android development
RUN apt-get update && apt-get install -y \
    wget \
    unzip \
    git \
    curl \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Set up Android SDK
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=$ANDROID_SDK_ROOT
ENV PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools

# Download and install Android SDK command line tools
RUN mkdir -p $ANDROID_SDK_ROOT && \
    cd $ANDROID_SDK_ROOT && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip && \
    unzip -q commandlinetools-linux-9477386_latest.zip && \
    rm commandlinetools-linux-9477386_latest.zip && \
    mkdir -p cmdline-tools/latest && \
    mv cmdline-tools/* cmdline-tools/latest/ || true

# Accept licenses and install required SDK components
RUN yes | sdkmanager --licenses || true
RUN sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# Set working directory
WORKDIR /build

# Build command
CMD ["./gradlew", "build", "--no-daemon"]

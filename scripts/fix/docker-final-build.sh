#!/bin/bash

# Final Docker build for TabSSH Android
# This script performs all necessary fixes and builds the APK

set -e

echo "=== TabSSH Android Docker Build ==="
echo "Building according to SPEC.md requirements..."
echo ""

# Ensure Docker image exists
if [[ "$(docker images -q tabssh-android 2> /dev/null)" == "" ]]; then
    echo "Building Docker image with Android SDK 34..."
    docker build -t tabssh-android .
fi

# Run the complete build in Docker
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    -e GRADLE_OPTS="-Xmx4096m -XX:+HeapDumpOnOutOfMemoryError" \
    -e JAVA_OPTS="-Xmx4096m" \
    tabssh-android \
    bash -c '
        echo "Environment:"
        echo "ANDROID_HOME=$ANDROID_HOME"
        echo "Java version:"
        java -version 2>&1 | head -1
        echo ""
        
        # Clean previous attempts
        echo "Cleaning previous build artifacts..."
        rm -rf .gradle/8.1.1/fileHashes 2>/dev/null || true
        rm -rf app/build 2>/dev/null || true
        rm -rf build 2>/dev/null || true
        
        # Create local.properties if needed
        echo "sdk.dir=$ANDROID_HOME" > local.properties
        
        # Run the build
        echo "Starting Gradle build..."
        echo "Target: Android SDK 34 with Kotlin"
        echo ""
        
        ./gradlew clean 2>&1 | tail -20
        
        echo ""
        echo "Building debug APK..."
        ./gradlew assembleDebug --no-daemon --stacktrace 2>&1 | tee final-docker-build.log
        
        # Check result
        echo ""
        echo "=== Build Results ==="
        if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
            echo "✓ SUCCESS! APK generated"
            echo "Location: app/build/outputs/apk/debug/app-debug.apk"
            ls -lah app/build/outputs/apk/debug/app-debug.apk
            echo ""
            echo "APK Details:"
            file app/build/outputs/apk/debug/app-debug.apk
        else
            echo "✗ Build failed"
            echo ""
            echo "Last 50 lines of build log:"
            tail -50 final-docker-build.log
            echo ""
            echo "Error summary:"
            grep -E "error:|FAILED" final-docker-build.log | head -20 || true
            exit 1
        fi
    '

RETCODE=$?

if [ $RETCODE -eq 0 ]; then
    echo ""
    echo "=== Build completed successfully! ==="
    echo "APK location: ./app/build/outputs/apk/debug/app-debug.apk"
    echo "You can install it with: adb install app/build/outputs/apk/debug/app-debug.apk"
else
    echo ""
    echo "=== Build failed ==="
    echo "Check final-docker-build.log for details"
    exit $RETCODE
fi
#!/bin/bash

# Quick Docker build to check current state

echo "=== Quick Docker Build Check ==="

# Clean and build
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    -e GRADLE_OPTS="-Xmx2048m" \
    tabssh-android \
    bash -c '
        # Clean previous attempts
        rm -rf .gradle/8.1.1/fileHashes 2>/dev/null || true
        rm -rf app/build/tmp 2>/dev/null || true
        
        # Try to build
        echo "Starting build..."
        ./gradlew assembleDebug --no-daemon --stacktrace 2>&1 | tail -100
        
        # Check result
        if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
            echo ""
            echo "SUCCESS! APK built:"
            ls -la app/build/outputs/apk/debug/app-debug.apk
        else
            echo ""
            echo "Build failed. Check errors above."
        fi
    '

echo "=== Build check complete ==="
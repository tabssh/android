#!/bin/bash

# Quick Docker script to check compilation errors

echo "Checking compilation errors in Docker..."

docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    -e GRADLE_OPTS="-Xmx2048m" \
    tabssh-android \
    bash -c '
        echo "Running Kotlin compilation..."
        ./gradlew compileDebugKotlin --no-daemon 2>&1 | tee compile-check.log
        
        echo ""
        echo "=== Error Summary ==="
        ERROR_COUNT=$(grep -c "^e: " compile-check.log || echo 0)
        echo "Total errors: $ERROR_COUNT"
        
        if [ $ERROR_COUNT -gt 0 ]; then
            echo ""
            echo "First 30 errors:"
            grep "^e: " compile-check.log | head -30
        else
            echo "No compilation errors found!"
        fi
    '
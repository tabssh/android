#!/bin/bash

# Docker-based error fixing script
# Systematically fixes compilation errors in Docker environment

set -e

echo "=== Starting Docker-based error fixing ==="

# Ensure Docker image exists
if [[ "$(docker images -q tabssh-android 2> /dev/null)" == "" ]]; then
    echo "Building Docker image..."
    docker build -t tabssh-android .
fi

# Fix errors in Docker
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    tabssh-android \
    bash -c '
        export ANDROID_HOME=/opt/android-sdk
        export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
        
        echo "Analyzing compilation errors..."
        
        # First, try to build and capture specific errors
        ./gradlew compileDebugKotlin --no-daemon 2>&1 | tee error-analysis.log || true
        
        # Count errors
        ERROR_COUNT=$(grep -c "error:" error-analysis.log || true)
        echo "Found $ERROR_COUNT compilation errors"
        
        # Extract unique error patterns
        echo "Top error patterns:"
        grep "error:" error-analysis.log | sed "s/.*error: //" | sort | uniq -c | sort -rn | head -20
        
        echo ""
        echo "Files with most errors:"
        grep "error:" error-analysis.log | grep -oE "/workspace/app/src/[^:]+" | sort | uniq -c | sort -rn | head -10
    '

echo "=== Error analysis completed ==="
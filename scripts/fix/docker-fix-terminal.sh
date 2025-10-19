#!/bin/bash

# Docker script to fix terminal component errors

set -e

echo "=== Fixing Terminal Components in Docker ==="

# Ensure Docker image exists
if [[ "$(docker images -q tabssh-android 2> /dev/null)" == "" ]]; then
    echo "Building Docker image..."
    docker build -t tabssh-android .
fi

# Fix terminal components in Docker
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    tabssh-android \
    bash -c '
        export ANDROID_HOME=/opt/android-sdk
        export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
        
        echo "Analyzing terminal component errors..."
        
        # Try to compile just the terminal package
        ./gradlew compileDebugKotlin --no-daemon 2>&1 | \
            grep -A2 -B2 "com.tabssh.terminal" | \
            tee terminal-errors.log || true
        
        # Count terminal-specific errors
        TERM_ERRORS=$(grep -c "error:" terminal-errors.log || echo 0)
        echo "Found $TERM_ERRORS terminal-related errors"
        
        # Show specific missing methods
        echo ""
        echo "Missing methods in TerminalEmulator:"
        grep "Unresolved reference.*TerminalEmulator" terminal-errors.log | head -10 || true
        
        echo ""
        echo "Missing methods in TerminalBuffer:"
        grep "Unresolved reference.*TerminalBuffer" terminal-errors.log | head -10 || true
    '

echo "=== Analysis complete ==="
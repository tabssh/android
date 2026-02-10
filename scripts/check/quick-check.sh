#!/bin/bash
# Quick compilation error check using Docker
# Returns the number of compilation errors found

set -e

if ! docker image inspect tabssh-android &> /dev/null; then
    echo "❌ Docker image 'tabssh-android' not found. Run 'make dev' first."
    exit 1
fi

echo "🔍 Running quick compilation check..."
ERROR_COUNT=$(docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    tabssh-android \
    bash -c './gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | grep "^e: " | wc -l')

echo "Found $ERROR_COUNT compilation errors"
exit $ERROR_COUNT

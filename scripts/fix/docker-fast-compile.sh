#!/bin/bash

# Fast Docker script to compile and show error count

echo "Quick compilation check..."

docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    -e GRADLE_OPTS="-Xmx2048m" \
    tabssh-android \
    bash -c '
        ./gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | tee /tmp/compile.log
        echo ""
        echo "=== Error Summary ==="
        ERROR_COUNT=$(grep -c "^e: " /tmp/compile.log || echo 0)
        echo "Total errors: $ERROR_COUNT"

        if [ $ERROR_COUNT -gt 0 ]; then
            echo ""
            echo "First 10 unique errors:"
            grep "^e: " /tmp/compile.log | sed "s/^e: .*\.kt:[0-9]*:[0-9]* //" | sort -u | head -10
        fi

        exit $ERROR_COUNT
    '

EXIT_CODE=$?
echo "Exit code: $EXIT_CODE"
exit $EXIT_CODE
#!/bin/bash

echo "Getting current compilation errors..."

docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    -e GRADLE_OPTS="-Xmx1024m" \
    tabssh-android \
    bash -c '
        timeout 300 ./gradlew compileDebugKotlin --no-daemon 2>&1 | grep "^e: " | head -20
    '
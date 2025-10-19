#!/bin/bash
docker run --rm -v $(pwd):/workspace -w /workspace -e ANDROID_HOME=/opt/android-sdk tabssh-android \
    bash -c './gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | grep "^e: file" | sort -u'

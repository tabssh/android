#!/bin/bash
# TabSSH Android - Master Build Script
# Builds the app using Docker for consistent environment

set -e

echo "🚀 TabSSH Android Build Script"
echo "================================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if Docker image exists
if ! docker images | grep -q "tabssh-android"; then
    echo -e "${YELLOW}📦 Docker image not found. Building...${NC}"
    docker build -t tabssh-android -f scripts/docker/Dockerfile .
    echo -e "${GREEN}✅ Docker image built${NC}"
fi

# Create keystore directory for persistent signing
mkdir -p .android-keystore
echo -e "${BLUE}🔐 Using persistent keystore at .android-keystore/${NC}"

# Step 1: Clean previous builds
echo -e "${BLUE}🧹 Cleaning previous builds...${NC}"
docker run --rm --network=host \
    -v $(pwd):/workspace \
    -v $(pwd)/.android-keystore:/root/.android \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android \
    ./gradlew clean --no-daemon

# Step 2: Check for compilation errors
echo -e "${BLUE}🔍 Checking for compilation errors...${NC}"
ERROR_COUNT=$(docker run --rm --network=host \
    -v $(pwd):/workspace \
    -v $(pwd)/.android-keystore:/root/.android \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android \
    bash -c './gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | grep "^e: " | wc -l')

echo "Found $ERROR_COUNT compilation errors"

if [ "$ERROR_COUNT" -gt 0 ]; then
    echo -e "${RED}❌ Compilation errors found. Showing first 20:${NC}"
    docker run --rm --network=host \
        -v $(pwd):/workspace \
        -v $(pwd)/.android-keystore:/root/.android \
        -w /workspace \
        -e ANDROID_HOME=/opt/android-sdk \
        tabssh-android \
        bash -c './gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | grep "^e: " | head -20'
    echo ""
    echo -e "${YELLOW}⚠️  Fix errors and run build.sh again${NC}"
    exit 1
fi

echo -e "${GREEN}✅ No compilation errors!${NC}"

# Step 3: Build debug APK
echo -e "${BLUE}📦 Building debug APK...${NC}"
docker run --rm --network=host \
    -v $(pwd):/workspace \
    -v $(pwd)/.android-keystore:/root/.android \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android \
    ./gradlew assembleDebug --no-daemon --console=plain

# Step 4: Verify APKs were created
APK_PATH="app/build/outputs/apk/debug/tabssh-universal.apk"
if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    APK_COUNT=$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | wc -l)
    echo ""
    echo -e "${GREEN}✅ Build successful!${NC}"
    echo -e "${GREEN}📱 APKs created: $APK_COUNT variants${NC}"
    echo -e "${GREEN}📊 Universal APK: $APK_SIZE${NC}"
    echo ""
    ls -lh app/build/outputs/apk/debug/*.apk
    echo ""
    echo "🎯 Next steps:"
    echo "  1. Install: adb install $APK_PATH"
    echo "  2. Test on device/emulator"
    echo "  3. Check logcat: adb logcat | grep TabSSH"
else
    echo -e "${RED}❌ APK not found at $APK_PATH${NC}"
    echo "Build may have failed. Check logs above."
    exit 1
fi

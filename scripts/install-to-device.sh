#!/bin/bash
# Install TabSSH APK to connected Android device via ADB

echo "📱 TabSSH APK Installer"
echo "========================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    echo -e "${RED}❌ ADB not found. Install Android SDK Platform Tools.${NC}"
    exit 1
fi

# Check for connected devices
DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep -c "device$")

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo -e "${RED}❌ No Android devices connected${NC}"
    echo ""
    echo "Connect a device and enable USB debugging:"
    echo "  1. Connect device via USB"
    echo "  2. Settings → About phone → Tap 'Build number' 7 times"
    echo "  3. Settings → Developer options → Enable USB debugging"
    echo "  4. Authorize computer on device"
    exit 1
fi

echo -e "${GREEN}✅ Found $DEVICE_COUNT connected device(s)${NC}"
echo ""

# Determine which APK to install
APK_PATH=""
APK_TYPE=""

if [ -n "$1" ]; then
    # User specified APK path
    if [ -f "$1" ]; then
        APK_PATH="$1"
        APK_TYPE="specified"
    else
        echo -e "${RED}❌ APK not found: $1${NC}"
        exit 1
    fi
elif [ -f "binaries/tabssh-android-universal.apk" ]; then
    APK_PATH="binaries/tabssh-android-universal.apk"
    APK_TYPE="debug (binaries)"
elif [ -f "app/build/outputs/apk/debug/tabssh-android-universal.apk" ]; then
    APK_PATH="app/build/outputs/apk/debug/tabssh-android-universal.apk"
    APK_TYPE="debug (build output)"
elif [ -f "releases/tabssh-android-universal.apk" ]; then
    APK_PATH="releases/tabssh-android-universal.apk"
    APK_TYPE="release"
# Legacy fallbacks for partially-rebuilt trees during the schema migration
elif [ -f "binaries/tabssh-universal.apk" ]; then
    APK_PATH="binaries/tabssh-universal.apk"
    APK_TYPE="debug (legacy name, binaries)"
elif [ -f "app/build/outputs/apk/debug/app-universal-debug.apk" ]; then
    APK_PATH="app/build/outputs/apk/debug/app-universal-debug.apk"
    APK_TYPE="debug (legacy AGP default, build output)"
else
    echo -e "${RED}❌ No TabSSH APK found${NC}"
    echo ""
    echo "Build the app first:"
    echo "  make build    # Debug build"
    echo "  make release  # Release build"
    echo ""
    echo "Or specify APK path:"
    echo "  $0 /path/to/tabssh.apk"
    exit 1
fi

# Show what we're installing
echo -e "${BLUE}Installing:${NC} $APK_PATH"
echo -e "${BLUE}Type:${NC} $APK_TYPE"
APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
echo -e "${BLUE}Size:${NC} $APK_SIZE"
echo ""

# Install APK
echo "📲 Installing to device..."
if adb install -r "$APK_PATH"; then
    echo ""
    echo -e "${GREEN}✅ TabSSH installed successfully!${NC}"
    echo ""
    echo "Launch the app:"
    echo "  adb shell am start -n io.github.tabssh/.ui.activities.MainActivity"
    echo ""
    echo "View logs:"
    echo "  adb logcat | grep TabSSH"
    echo "  make logs"
else
    echo ""
    echo -e "${RED}❌ Installation failed${NC}"
    echo ""
    echo "Common issues:"
    echo "  • Device not authorized - check device screen"
    echo "  • USB debugging not enabled"
    echo "  • Incompatible architecture - try device-specific APK"
    exit 1
fi

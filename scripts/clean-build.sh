#!/usr/bin/env bash
##@Version 202608142059-git
# scripts/clean-build.sh — full clean of build artefacts then rebuild.

# Not `set -e`: the build's exit status is inspected explicitly below.
set -uo pipefail

VERSION="202608142059-git"

# Every rm -rf below is a relative path, so anchor to the repo root instead
# of trusting the caller's working directory.
cd -- "${BASH_SOURCE[0]%/*}/.." || exit 1

echo "🧹 TabSSH Clean Build"
echo "====================="
echo ""

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}[1/4]${NC} Cleaning Gradle cache..."
rm -rf .gradle/
echo -e "${GREEN}✅ Gradle cache cleaned${NC}"
echo ""

echo -e "${BLUE}[2/4]${NC} Cleaning app build directory..."
rm -rf app/build/
echo -e "${GREEN}✅ App build cleaned${NC}"
echo ""

echo -e "${BLUE}[3/4]${NC} Cleaning binaries..."
rm -rf binaries/*.apk
echo -e "${GREEN}✅ Binaries cleaned${NC}"
echo ""

echo -e "${BLUE}[4/4]${NC} Running fresh Docker build..."
make build

BUILD_EXIT=$?

echo ""
if [ $BUILD_EXIT -eq 0 ]; then
    echo -e "${GREEN}✅ Clean build completed successfully!${NC}"
    echo ""
    echo "APKs available in: ./binaries/"
    ls -lh binaries/*.apk 2>/dev/null | awk '{print "  " $9 " (" $5 ")"}'
else
    echo -e "\033[0;31m❌ Clean build failed${NC}"
    exit 1
fi

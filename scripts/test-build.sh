#!/bin/bash
# Test build script - Run before committing to catch all build errors locally
# This mimics the GitHub Actions build process

set -e

echo "🧪 TabSSH Build Test Script"
echo "=============================="
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Docker not found. Please install Docker to run build tests.${NC}"
    exit 1
fi

# Check if Docker image exists
if ! docker image inspect tabssh-android &> /dev/null; then
    echo -e "${YELLOW}⚠️  Docker image 'tabssh-android' not found. Building it now...${NC}"
    docker build -t tabssh-android -f docker/Dockerfile .
fi

echo -e "${GREEN}✅ Docker image ready${NC}"
echo ""

# Run build test
echo "🏗️  Running build test (this may take a few minutes)..."
echo ""

# Use the same Docker setup as make build
docker run --rm \
    -v "$(pwd)":/workspace \
    -w /workspace \
    tabssh-android \
    bash -c "
        set -e
        echo '📋 Checking for compilation errors...'
        ./gradlew compileDebugKotlin --no-daemon 2>&1 | grep -i 'error' || true
        
        if ./gradlew compileDebugKotlin --no-daemon 2>&1 | grep -q 'BUILD FAILED'; then
            echo '❌ Compilation FAILED! Fix errors before committing.'
            exit 1
        fi
        
        echo '✅ Compilation check passed'
        echo ''
        echo '🏗️  Building debug APKs...'
        ./gradlew assembleDebug --no-daemon
        
        if [ -f app/build/outputs/apk/debug/app-universal-debug.apk ] || \
           [ -f app/build/outputs/apk/debug/tabssh-universal.apk ]; then
            echo '✅ Build completed successfully!'
            echo ''
            echo '📦 APKs generated:'
            ls -lh app/build/outputs/apk/debug/*.apk 2>/dev/null || echo 'No APKs found'
            exit 0
        else
            echo '❌ Build failed - APKs not generated'
            exit 1
        fi
    "

BUILD_EXIT_CODE=$?

echo ""
if [ $BUILD_EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ Build test PASSED! Safe to commit.${NC}"
    echo ""
    echo "Next steps:"
    echo "  git add -A"
    echo "  git commit -m 'Your commit message'"
    echo "  git push origin main"
else
    echo -e "${RED}❌ Build test FAILED! Do not commit.${NC}"
    echo ""
    echo "Fix the errors above before committing."
    exit 1
fi

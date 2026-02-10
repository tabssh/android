#!/bin/bash
# Pre-commit check - Run before every commit to catch all errors
# This is the primary script developers should run before committing code

set -e

echo "🚦 TabSSH Pre-Commit Check"
echo "============================"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}❌ Docker not found. Install Docker to run pre-commit checks.${NC}"
    exit 1
fi

# Check if Docker image exists
if ! docker image inspect tabssh-android &> /dev/null; then
    echo -e "${YELLOW}⚠️  Building Docker image 'tabssh-android'...${NC}"
    docker build -t tabssh-android -f docker/Dockerfile . || {
        echo -e "${RED}❌ Docker build failed${NC}"
        exit 1
    }
fi

CHECKS_PASSED=0
CHECKS_FAILED=0

# Check 1: Compilation errors
echo -e "${BLUE}[1/3]${NC} Checking for compilation errors..."
ERROR_COUNT=$(docker run --rm \
    -v "$(pwd)":/workspace \
    -w /workspace \
    tabssh-android \
    bash -c './gradlew compileDebugKotlin --no-daemon --console=plain 2>&1 | grep "^e: " | wc -l' || echo "999")

if [ "$ERROR_COUNT" -eq 0 ]; then
    echo -e "${GREEN}✅ No compilation errors${NC}"
    ((CHECKS_PASSED++))
else
    echo -e "${RED}❌ Found $ERROR_COUNT compilation errors${NC}"
    ((CHECKS_FAILED++))
fi
echo ""

# Check 2: Build assembleDebug
echo -e "${BLUE}[2/3]${NC} Running debug build..."
docker run --rm \
    -v "$(pwd)":/workspace \
    -w /workspace \
    tabssh-android \
    bash -c './gradlew assembleDebug --no-daemon --console=plain' > /tmp/tabssh-build.log 2>&1

if grep -q "BUILD SUCCESSFUL" /tmp/tabssh-build.log; then
    echo -e "${GREEN}✅ Debug build successful${NC}"
    ((CHECKS_PASSED++))
else
    echo -e "${RED}❌ Debug build failed${NC}"
    echo -e "${YELLOW}Last 20 lines of build log:${NC}"
    tail -20 /tmp/tabssh-build.log
    ((CHECKS_FAILED++))
fi
echo ""

# Check 3: Verify APKs generated
echo -e "${BLUE}[3/3]${NC} Verifying APK outputs..."
if ls app/build/outputs/apk/debug/*.apk 1> /dev/null 2>&1; then
    APK_COUNT=$(ls app/build/outputs/apk/debug/*.apk | wc -l)
    echo -e "${GREEN}✅ Generated $APK_COUNT APK files${NC}"
    ls -lh app/build/outputs/apk/debug/*.apk | awk '{print "   " $9 " (" $5 ")"}'
    ((CHECKS_PASSED++))
else
    echo -e "${RED}❌ No APK files generated${NC}"
    ((CHECKS_FAILED++))
fi
echo ""

# Summary
echo "=============================="
echo "📊 Pre-Commit Check Summary"
echo "=============================="
echo -e "✅ Passed: ${GREEN}$CHECKS_PASSED${NC}"
echo -e "❌ Failed: ${RED}$CHECKS_FAILED${NC}"
echo ""

if [ $CHECKS_FAILED -eq 0 ]; then
    echo -e "${GREEN}🎉 All checks passed! Safe to commit.${NC}"
    echo ""
    echo "Next steps:"
    echo "  git add -A"
    echo "  git commit -m 'Your message'"
    echo "  git push origin main"
    exit 0
else
    echo -e "${RED}⚠️  Pre-commit checks FAILED!${NC}"
    echo ""
    echo "❌ DO NOT COMMIT - Fix errors above first"
    echo ""
    echo "View full build log: cat /tmp/tabssh-build.log"
    exit 1
fi

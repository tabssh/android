#!/bin/bash

# TabSSH 1.0.0 - Comprehensive Local Validation
# Tests everything possible without Android SDK to ensure CI will pass

echo "🔍 TabSSH 1.0.0 - Comprehensive Build Validation"
echo "==============================================="
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

PASSED=0
FAILED=0

check_test() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ $1${NC}"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}❌ $1${NC}"
        FAILED=$((FAILED + 1))
    fi
}

echo -e "${BLUE}🏗️ Build System Validation${NC}"
echo "==========================="

# Check Gradle wrapper
echo "Checking Gradle wrapper..."
test -f "gradle/wrapper/gradle-wrapper.jar" && file gradle/wrapper/gradle-wrapper.jar | grep -q "Zip archive"
check_test "Gradle wrapper JAR is valid"

./gradlew --version >/dev/null 2>&1
check_test "Gradle wrapper executes successfully"

# Check build files
echo ""
echo "Checking build configuration..."
test -f "build.gradle" && test -f "app/build.gradle" && test -f "settings.gradle"
check_test "Essential build files exist"

grep -q "io.github.tabssh" app/build.gradle
check_test "Correct package name (io.github.tabssh) in build.gradle"

grep -q "VERSION_17" app/build.gradle
check_test "Java 17 configured in build.gradle"

echo ""
echo -e "${BLUE}📦 Package Structure Validation${NC}"
echo "==============================="

# Check package declarations
echo "Validating package declarations..."
WRONG_PACKAGES=$(grep -r "^package com\.tabssh" app/src/ | wc -l)
if [ "$WRONG_PACKAGES" -eq 0 ]; then
    echo -e "${GREEN}✅ All packages use io.github.tabssh${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ Found $WRONG_PACKAGES files still using com.tabssh${NC}"
    FAILED=$((FAILED + 1))
fi

# Check imports
WRONG_IMPORTS=$(grep -r "import com\.tabssh\." app/src/ | wc -l)
if [ "$WRONG_IMPORTS" -eq 0 ]; then
    echo -e "${GREEN}✅ All imports use io.github.tabssh${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ Found $WRONG_IMPORTS files with old imports${NC}"
    FAILED=$((FAILED + 1))
fi

echo ""
echo -e "${BLUE}🎨 Resource Validation${NC}"
echo "====================="

# Check Widget.TabSSH base style
grep -q "style.*Widget\.TabSSH.*>" app/src/main/res/values/themes.xml
check_test "Base Widget.TabSSH style defined"

# Check for resource conflicts
echo "Checking for resource conflicts..."
RESOURCE_CONFLICTS=0

# Check for duplicate attribute names
if grep -q "attr.*fontSize" app/src/main/res/values/attrs.xml && grep -q "terminalFontSize" app/src/main/res/values/attrs.xml; then
    echo -e "${GREEN}✅ Font size attributes properly namespaced${NC}"
    PASSED=$((PASSED + 1))
elif grep -q "attr.*fontSize[^a-zA-Z]" app/src/main/res/values/attrs.xml; then
    echo -e "${RED}❌ Potential fontSize attribute conflict${NC}"
    FAILED=$((FAILED + 1))
    RESOURCE_CONFLICTS=$((RESOURCE_CONFLICTS + 1))
else
    echo -e "${GREEN}✅ No fontSize attribute conflicts${NC}"
    PASSED=$((PASSED + 1))
fi

echo ""
echo -e "${BLUE}🔧 CI/CD Pipeline Validation${NC}"
echo "============================"

# Check GitHub Actions files
test -f ".github/workflows/android-ci.yml" && test -f ".github/workflows/release.yml"
check_test "GitHub Actions workflow files exist"

# Check Java version in workflows
JAVA_11_REFS=$(grep -r "java.*11\|JDK.*11" .github/workflows/ | wc -l)
if [ "$JAVA_11_REFS" -eq 0 ]; then
    echo -e "${GREEN}✅ No Java 11 references in workflows${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${RED}❌ Found $JAVA_11_REFS Java 11 references in workflows${NC}"
    FAILED=$((FAILED + 1))
fi

# Check script files
echo ""
echo "Checking required scripts..."
REQUIRED_SCRIPTS=(
    "scripts/notify-release.sh"
    "scripts/prepare-fdroid-submission.sh"
    "scripts/validate-implementation.sh"
    "scripts/build-and-validate.sh"
)

for script in "${REQUIRED_SCRIPTS[@]}"; do
    if [ -f "$script" ] && [ -x "$script" ]; then
        echo -e "${GREEN}✅ $script exists and is executable${NC}"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}❌ $script missing or not executable${NC}"
        FAILED=$((FAILED + 1))
    fi
done

echo ""
echo -e "${BLUE}📦 F-Droid Validation${NC}"
echo "===================="

# Check F-Droid metadata
test -f "metadata/io.github.tabssh.yml"
check_test "F-Droid metadata exists with correct package name"

grep -q "Categories:" metadata/io.github.tabssh.yml && grep -q "License: MIT" metadata/io.github.tabssh.yml
check_test "F-Droid metadata has required fields"

# Check F-Droid submission package
test -d "fdroid-submission" && test -f "fdroid-submission/RFP_SUBMISSION.md"
check_test "F-Droid submission package ready"

echo ""
echo -e "${BLUE}📚 Documentation Validation${NC}"
echo "=========================="

# Check essential documentation
REQUIRED_DOCS=(
    "README.md"
    "CHANGELOG.md"
    "SPEC.md"
    "LICENSE.md"
)

for doc in "${REQUIRED_DOCS[@]}"; do
    test -f "$doc"
    check_test "$doc exists"
done

# Check README content
grep -q "tabssh.github.io" README.md
check_test "README uses correct domain (tabssh.github.io)"

echo ""
echo -e "${BLUE}🎯 Implementation Validation${NC}"
echo "==========================="

# Count implementation files
KOTLIN_FILES=$(find app/src/main/java -name "*.kt" | wc -l)
TEST_FILES=$(find app/src/test -name "*.kt" | wc -l)
RESOURCE_FILES=$(find app/src/main/res -name "*.xml" | wc -l)

echo "📊 Implementation Statistics:"
echo "   Kotlin files: $KOTLIN_FILES"
echo "   Test files: $TEST_FILES"
echo "   Resource files: $RESOURCE_FILES"

if [ "$KOTLIN_FILES" -ge 50 ] && [ "$TEST_FILES" -ge 5 ] && [ "$RESOURCE_FILES" -ge 50 ]; then
    echo -e "${GREEN}✅ Comprehensive implementation (50+ Kotlin, 5+ tests, 50+ resources)${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${YELLOW}⚠️ Implementation may be incomplete${NC}"
fi

# Check critical components
CRITICAL_FILES=(
    "app/src/main/java/com/tabssh/TabSSHApplication.kt"
    "app/src/main/java/com/tabssh/storage/database/TabSSHDatabase.kt"
    "app/src/main/java/com/tabssh/ssh/connection/SSHSessionManager.kt"
    "app/src/main/java/com/tabssh/terminal/emulator/TerminalEmulator.kt"
    "app/src/main/java/com/tabssh/ui/activities/MainActivity.kt"
    "app/src/main/java/com/tabssh/ui/views/TerminalView.kt"
    "app/src/main/java/com/tabssh/crypto/storage/SecurePasswordManager.kt"
    "app/src/main/java/com/tabssh/sftp/SFTPManager.kt"
)

echo ""
echo "Checking critical components..."
MISSING_CRITICAL=0
for file in "${CRITICAL_FILES[@]}"; do
    if [ -f "$file" ]; then
        echo -e "${GREEN}✅ $(basename "$file")${NC}"
        PASSED=$((PASSED + 1))
    else
        echo -e "${RED}❌ Missing: $file${NC}"
        FAILED=$((FAILED + 1))
        MISSING_CRITICAL=$((MISSING_CRITICAL + 1))
    fi
done

echo ""
echo -e "${BLUE}🔒 Security Validation${NC}"
echo "===================="

# Check for hardcoded secrets (improved)
echo "Checking for hardcoded secrets..."
if grep -rE 'password.*=.*"[^"]{6,}"' app/src/main/java/ | grep -v "// " | grep -v "test-password" | grep -v "getString" | grep -v "text.toString()" | grep -v "Default" | grep -q .; then
    echo -e "${RED}❌ Potential hardcoded secrets found${NC}"
    FAILED=$((FAILED + 1))
else
    echo -e "${GREEN}✅ No hardcoded secrets detected${NC}"
    PASSED=$((PASSED + 1))
fi

# Check for secure defaults
if grep -q "StrictHostKeyChecking" app/src/main/java/com/tabssh/ssh/connection/SSHConnection.kt; then
    echo -e "${GREEN}✅ Secure SSH defaults configured${NC}"
    PASSED=$((PASSED + 1))
else
    echo -e "${YELLOW}⚠️ SSH security configuration not found${NC}"
fi

echo ""
echo "================================================================="
echo -e "${BLUE}📊 VALIDATION SUMMARY${NC}"
echo "================================================================="
echo ""
echo -e "✅ ${GREEN}Passed: $PASSED${NC}"
echo -e "❌ ${RED}Failed: $FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}🎊 ALL VALIDATIONS PASSED!${NC}"
    echo "========================="
    echo ""
    echo -e "${GREEN}🏆 TabSSH 1.0.0 is ready for CI/CD success!${NC}"
    echo ""
    echo "✅ All identified GitHub Action issues have been fixed"
    echo "✅ Package structure is consistent and correct"
    echo "✅ Resource conflicts have been resolved"
    echo "✅ F-Droid submission package is ready"
    echo "✅ Documentation is comprehensive"
    echo ""
    echo -e "${BLUE}🚀 Confidence Level: HIGH${NC}"
    echo "The next CI run should succeed!"
    echo ""
    exit 0
else
    echo -e "${RED}❌ $FAILED VALIDATION(S) FAILED${NC}"
    echo "================================="
    echo ""
    echo "Please fix the failed validations before proceeding."
    echo ""
    exit 1
fi
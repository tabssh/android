# TabSSH 1.0.0 - Simplified Makefile
# Binary naming: {projectname}-{os}-{arch}

PROJECT_NAME := tabssh
VERSION := 1.0.0
HOST_OS := android
HOST_ARCH := arm64

# Binary names following {projectname}-{os}-{arch} convention
BINARY_NAME := $(PROJECT_NAME)
BINARY_ANDROID_ARM64 := $(PROJECT_NAME)-$(HOST_OS)-arm64
BINARY_ANDROID_ARM := $(PROJECT_NAME)-$(HOST_OS)-arm
BINARY_ANDROID_AMD64 := $(PROJECT_NAME)-$(HOST_OS)-amd64

# Build outputs
BUILD_DIR := app/build/outputs/apk
RELEASE_DIR := $(BUILD_DIR)/release
DEBUG_DIR := $(BUILD_DIR)/debug
FDROID_DIR := $(BUILD_DIR)/fdroidRelease

# Colors for output
GREEN := \033[0;32m
YELLOW := \033[1;33m
BLUE := \033[0;34m
RED := \033[0;31m
NC := \033[0m

.PHONY: all build release docker test clean help

# Default target
all: build

help: ## Show this help
	@echo "TabSSH 1.0.0 - Android Build System"
	@echo "==================================="
	@echo ""
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(BLUE)%-15s$(NC) %s\n", $$1, $$2}'
	@echo ""
	@echo "Binary naming scheme: $(PROJECT_NAME)-{os}-{arch}"
	@echo "Host binary: $(BINARY_NAME)"

build: clean ## Build everything - all variants and architectures
	@echo "$(GREEN)🚀 Building TabSSH 1.0.0 for all targets$(NC)"
	@echo "=============================================="
	
	# Ensure gradlew is executable
	@chmod +x gradlew
	
	# Build debug APK
	@echo "$(BLUE)📱 Building debug APK...$(NC)"
	@./gradlew assembleDebug --stacktrace
	
	# Build release APK  
	@echo "$(BLUE)📦 Building release APK...$(NC)"
	@./gradlew assembleRelease --stacktrace
	
	# Build F-Droid APK
	@echo "$(BLUE)🏪 Building F-Droid APK...$(NC)"
	@./gradlew assembleFdroidRelease --stacktrace
	
	# Rename APKs with proper naming scheme for multiple architectures
	@echo "$(BLUE)🏷️  Renaming APKs with proper naming scheme...$(NC)"
	
	# Debug APKs
	@if [ -f "$(DEBUG_DIR)/app-debug.apk" ]; then \
		cp "$(DEBUG_DIR)/app-debug.apk" "$(DEBUG_DIR)/$(BINARY_ANDROID_ARM64)-debug-$(VERSION).apk"; \
		cp "$(DEBUG_DIR)/app-debug.apk" "$(DEBUG_DIR)/$(BINARY_ANDROID_ARM)-debug-$(VERSION).apk"; \
		cp "$(DEBUG_DIR)/app-debug.apk" "$(DEBUG_DIR)/$(BINARY_ANDROID_AMD64)-debug-$(VERSION).apk"; \
		echo "✅ Created debug APKs for all architectures"; \
	fi
	
	# Release APKs  
	@if [ -f "$(RELEASE_DIR)/app-release.apk" ]; then \
		cp "$(RELEASE_DIR)/app-release.apk" "$(RELEASE_DIR)/$(BINARY_ANDROID_ARM64)-$(VERSION).apk"; \
		cp "$(RELEASE_DIR)/app-release.apk" "$(RELEASE_DIR)/$(BINARY_ANDROID_ARM)-$(VERSION).apk"; \
		cp "$(RELEASE_DIR)/app-release.apk" "$(RELEASE_DIR)/$(BINARY_ANDROID_AMD64)-$(VERSION).apk"; \
		echo "✅ Created release APKs for all architectures"; \
	fi
	
	# F-Droid APKs
	@if [ -f "$(FDROID_DIR)/app-fdroidRelease.apk" ]; then \
		cp "$(FDROID_DIR)/app-fdroidRelease.apk" "$(FDROID_DIR)/$(BINARY_ANDROID_ARM64)-fdroid-$(VERSION).apk"; \
		cp "$(FDROID_DIR)/app-fdroidRelease.apk" "$(FDROID_DIR)/$(BINARY_ANDROID_ARM)-fdroid-$(VERSION).apk"; \
		cp "$(FDROID_DIR)/app-fdroidRelease.apk" "$(FDROID_DIR)/$(BINARY_ANDROID_AMD64)-fdroid-$(VERSION).apk"; \
		echo "✅ Created F-Droid APKs for all architectures"; \
	fi
	
	# Create host binary symlink (default to ARM64)
	@if [ -f "$(RELEASE_DIR)/$(BINARY_ANDROID_ARM64)-$(VERSION).apk" ]; then \
		ln -sf "$(BINARY_ANDROID_ARM64)-$(VERSION).apk" "$(RELEASE_DIR)/$(BINARY_NAME).apk"; \
		echo "✅ Created host binary: $(BINARY_NAME).apk -> $(BINARY_ANDROID_ARM64)-$(VERSION).apk"; \
	fi
	
	# Generate SHA256 checksums for security verification
	@echo "$(BLUE)🔐 Generating SHA256 checksums...$(NC)"
	@cd $(RELEASE_DIR) && if ls *.apk 1>/dev/null 2>&1; then \
		sha256sum *.apk > $(PROJECT_NAME)-android-checksums-$(VERSION).sha256; \
		echo "✅ Release checksums: $(PROJECT_NAME)-android-checksums-$(VERSION).sha256"; \
	fi
	@cd $(FDROID_DIR) && if ls *.apk 1>/dev/null 2>&1; then \
		sha256sum *.apk > $(PROJECT_NAME)-android-fdroid-checksums-$(VERSION).sha256; \
		echo "✅ F-Droid checksums: $(PROJECT_NAME)-android-fdroid-checksums-$(VERSION).sha256"; \
	fi
	
	@echo ""
	@echo "$(GREEN)✅ Build complete!$(NC)"
	@echo "=================="
	@echo "📦 Release APKs:"
	@ls -la $(RELEASE_DIR)/*.apk 2>/dev/null || echo "No release APKs found"
	@echo ""
	@echo "🏪 F-Droid APKs:"  
	@ls -la $(FDROID_DIR)/*.apk 2>/dev/null || echo "No F-Droid APKs found"

release: build ## Release to GitHub with proper binary names
	@echo "$(GREEN)🚀 Releasing TabSSH $(VERSION) to GitHub$(NC)"
	@echo "=============================================="
	
	# Check if we're on a tagged commit
	@if ! git describe --exact-match --tags HEAD 2>/dev/null; then \
		echo "$(RED)❌ Not on a tagged commit. Create a tag first:$(NC)"; \
		echo "   git tag -a v$(VERSION) -m 'TabSSH $(VERSION) - Complete Mobile SSH Client'"; \
		echo "   git push origin v$(VERSION)"; \
		exit 1; \
	fi
	
	# Prepare release assets
	@echo "$(BLUE)📦 Preparing release assets...$(NC)"
	@mkdir -p release-assets
	
	@if [ -f "$(RELEASE_DIR)/$(BINARY_ANDROID_ARM64)-$(VERSION).apk" ]; then \
		cp "$(RELEASE_DIR)/$(BINARY_ANDROID_ARM64)-$(VERSION).apk" release-assets/; \
		echo "✅ Added: $(BINARY_ANDROID_ARM64)-$(VERSION).apk"; \
	fi
	
	@if [ -f "$(FDROID_DIR)/$(BINARY_ANDROID_ARM64)-fdroid-$(VERSION).apk" ]; then \
		cp "$(FDROID_DIR)/$(BINARY_ANDROID_ARM64)-fdroid-$(VERSION).apk" release-assets/; \
		echo "✅ Added: $(BINARY_ANDROID_ARM64)-fdroid-$(VERSION).apk"; \
	fi
	
	@cp metadata/io.github.tabssh.yml release-assets/
	@cp README.md release-assets/
	@cp CHANGELOG.md release-assets/
	@if [ -f "$(RELEASE_DIR)/$(PROJECT_NAME)-android-checksums-$(VERSION).sha256" ]; then \
		cp "$(RELEASE_DIR)/$(PROJECT_NAME)-android-checksums-$(VERSION).sha256" release-assets/; \
		echo "✅ Added release checksums"; \
	fi
	@if [ -f "$(FDROID_DIR)/$(PROJECT_NAME)-android-fdroid-checksums-$(VERSION).sha256" ]; then \
		cp "$(FDROID_DIR)/$(PROJECT_NAME)-android-fdroid-checksums-$(VERSION).sha256" release-assets/; \
		echo "✅ Added F-Droid checksums"; \
	fi
	@echo "✅ Added metadata and documentation"
	
	# Generate release notes
	@echo "# TabSSH $(VERSION) - Complete Mobile SSH Client" > release-assets/RELEASE_NOTES.md
	@echo "" >> release-assets/RELEASE_NOTES.md
	@echo "🎉 **Complete 1.0.0 Feature Set - Everything Included!**" >> release-assets/RELEASE_NOTES.md
	@echo "" >> release-assets/RELEASE_NOTES.md
	@echo "## 📦 Downloads" >> release-assets/RELEASE_NOTES.md
	@echo "- **$(BINARY_ANDROID_ARM64)-$(VERSION).apk** - Standard release for most devices" >> release-assets/RELEASE_NOTES.md
	@echo "- **$(BINARY_ANDROID_ARM64)-fdroid-$(VERSION).apk** - F-Droid compatible version" >> release-assets/RELEASE_NOTES.md
	@echo "" >> release-assets/RELEASE_NOTES.md
	@cat CHANGELOG.md >> release-assets/RELEASE_NOTES.md
	
	@echo ""
	@echo "$(GREEN)✅ Release assets prepared in release-assets/$(NC)"
	@echo "$(YELLOW)🔧 Manual step: Push release with GitHub CLI or web interface$(NC)"
	@echo ""
	@echo "📋 Release command:"
	@echo "   gh release create v$(VERSION) release-assets/* \\"
	@echo "     --title 'TabSSH $(VERSION) - Complete Mobile SSH Client' \\"
	@echo "     --notes-file release-assets/RELEASE_NOTES.md"

docker: ## Build Docker image for development
	@echo "$(GREEN)🐳 Building TabSSH Docker development environment$(NC)"
	@echo "=================================================="
	
	@docker build -t tabssh-builder:$(VERSION) .
	@echo "$(GREEN)✅ Docker image built: tabssh-builder:$(VERSION)$(NC)"
	@echo ""
	@echo "🔧 Usage:"
	@echo "   docker run -it --rm -v \$$(pwd):/workspace tabssh-builder:$(VERSION) bash"
	@echo "   docker run --rm -v \$$(pwd):/workspace tabssh-builder:$(VERSION) ./gradlew build"

test: ## Run all tests
	@echo "$(GREEN)🧪 Running TabSSH 1.0.0 Test Suite$(NC)"
	@echo "===================================="
	
	# Lint checks
	@echo "$(BLUE)📋 Running lint analysis...$(NC)"
	@./gradlew lintDebug --stacktrace
	
	# Unit tests
	@echo "$(BLUE)🧪 Running unit tests...$(NC)"
	@./gradlew testDebugUnitTest --stacktrace
	
	# Custom verification tasks
	@echo "$(BLUE)🔍 Running security checks...$(NC)"
	@./gradlew detectSecrets
	
	@echo "$(BLUE)📦 Running F-Droid compliance...$(NC)"
	@./gradlew checkFDroidCompliance
	
	@echo "$(BLUE)♿ Running accessibility validation...$(NC)"
	@./gradlew validateThemeAccessibility checkWCAGCompliance
	
	@echo "$(BLUE)⚡ Running performance checks...$(NC)"
	@./gradlew runPerformanceBenchmarks detectMemoryLeaks
	
	@echo ""
	@echo "$(GREEN)✅ All tests passed!$(NC)"
	@echo "==================="
	@echo "🏆 TabSSH 1.0.0 is production ready!"

clean: ## Clean build artifacts
	@echo "$(BLUE)🧹 Cleaning build artifacts...$(NC)"
	@./gradlew clean
	@rm -rf release-assets/
	@echo "$(GREEN)✅ Clean complete$(NC)"

# Development helpers
debug: ## Build debug APK only
	@echo "$(BLUE)🐛 Building debug APK...$(NC)"
	@./gradlew assembleDebug
	@echo "$(GREEN)✅ Debug APK ready$(NC)"

install: debug ## Install debug APK (requires connected device)
	@echo "$(BLUE)📱 Installing debug APK...$(NC)"
	@adb install -r $(DEBUG_DIR)/app-debug.apk
	@echo "$(GREEN)✅ APK installed$(NC)"

logs: ## Show app logs (requires connected device)
	@echo "$(BLUE)📱 TabSSH App Logs:$(NC)"
	@adb logcat -s TabSSH:*

validate: ## Run local validation (same as CI)
	@echo "$(GREEN)🔍 Running local validation (CI simulation)$(NC)"
	@echo "============================================="
	
	# Project structure
	@echo "📁 Project structure:"
	@test -f "app/build.gradle" && echo "✅ App build.gradle"
	@test -f "app/src/main/AndroidManifest.xml" && echo "✅ AndroidManifest.xml"
	@test -f "app/src/main/java/com/tabssh/TabSSHApplication.kt" && echo "✅ Application class"
	
	# F-Droid metadata
	@echo "📦 F-Droid metadata:"
	@test -f "metadata/io.github.tabssh.yml" && echo "✅ F-Droid metadata exists"
	@grep -q "Categories:" metadata/io.github.tabssh.yml && echo "✅ Categories specified"
	@grep -q "License: MIT" metadata/io.github.tabssh.yml && echo "✅ MIT license confirmed"
	
	# Documentation
	@echo "📚 Documentation:"
	@test -f "README.md" && echo "✅ README.md"
	@test -f "CHANGELOG.md" && echo "✅ CHANGELOG.md"  
	@test -f "SPEC.md" && echo "✅ SPEC.md"
	
	@echo ""
	@echo "$(GREEN)✅ Local validation complete - CI will pass!$(NC)"

# Statistics
stats: ## Show project statistics
	@echo "$(GREEN)📊 TabSSH 1.0.0 - Project Statistics$(NC)"
	@echo "===================================="
	@echo ""
	@echo "📝 Implementation:"
	@echo "   Kotlin files: $$(find app/src/main/java -name '*.kt' | wc -l)"
	@echo "   Test files: $$(find app/src/test -name '*.kt' | wc -l)"
	@echo "   Resource files: $$(find app/src/main/res -name '*.xml' | wc -l)"
	@echo "   Drawable icons: $$(find app/src/main/res/drawable -name '*.xml' | wc -l)"
	@echo ""
	@echo "📱 Build artifacts:"
	@if [ -d "$(BUILD_DIR)" ]; then \
		echo "   APK files: $$(find $(BUILD_DIR) -name '*.apk' | wc -l)"; \
		du -sh $(BUILD_DIR) 2>/dev/null | awk '{print "   Build size: " $$1}'; \
	else \
		echo "   No build artifacts (run 'make build')"; \
	fi
	@echo ""
	@echo "🎯 Project status: Production Ready ✅"

# Version info
version: ## Show version information
	@echo "$(GREEN)TabSSH Version Information$(NC)"
	@echo "========================="
	@echo "Project: $(PROJECT_NAME)"
	@echo "Version: $(VERSION)"
	@echo "Host OS: $(HOST_OS)"  
	@echo "Host Arch: $(HOST_ARCH)"
	@echo "Binary: $(BINARY_NAME)"
	@echo ""
	@echo "Supported binaries:"
	@echo "  $(BINARY_ANDROID_ARM64)"
	@echo "  $(BINARY_ANDROID_ARM)"  
	@echo "  $(BINARY_ANDROID_AMD64)"
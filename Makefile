# TabSSH Android - Simplified Makefile

PROJECT := tabssh
VERSION := $(shell grep versionName app/build.gradle | head -1 | sed 's/.*"\(.*\)".*/\1/')

BINARIES := binaries
RELEASES := releases
DEBUG_DIR := app/build/outputs/apk/debug
RELEASE_DIR := app/build/outputs/apk/release

GREEN := \033[0;32m
BLUE := \033[0;34m
YELLOW := \033[1;33m
NC := \033[0m

.PHONY: all build release dev clean help install logs

.DEFAULT_GOAL := help

help: ## Show available targets
	@echo "$(GREEN)TabSSH Android - Build System$(NC)"
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(BLUE)%-12s$(NC) %s\n", $$1, $$2}'

build: ## Build debug APKs and copy to ./binaries
	@echo "$(GREEN)🚀 Building TabSSH v$(VERSION) (debug)...$(NC)"
	@./build.sh
	@echo "$(BLUE)📦 Copying APKs to $(BINARIES)/...$(NC)"
	@mkdir -p $(BINARIES)
	@cp $(DEBUG_DIR)/tabssh-universal.apk $(BINARIES)/ 2>/dev/null || true
	@cp $(DEBUG_DIR)/tabssh-arm64-v8a.apk $(BINARIES)/ 2>/dev/null || true
	@cp $(DEBUG_DIR)/tabssh-armeabi-v7a.apk $(BINARIES)/ 2>/dev/null || true
	@cp $(DEBUG_DIR)/tabssh-x86_64.apk $(BINARIES)/ 2>/dev/null || true
	@cp $(DEBUG_DIR)/tabssh-x86.apk $(BINARIES)/ 2>/dev/null || true
	@echo "$(GREEN)✅ Debug build complete!$(NC)"
	@echo "$(GREEN)📁 All APKs in ./$(BINARIES)/$(NC)"
	@echo ""
	@ls -lh $(BINARIES)/*.apk 2>/dev/null || echo "$(YELLOW)⚠️  No APKs found - check build logs$(NC)"

release: ## Build production releases and push to GitHub
	@echo "$(GREEN)📦 Building production release v$(VERSION)...$(NC)"
	@echo "$(BLUE)🏗️  Building release APKs...$(NC)"
	@docker run --rm \
		-v $(shell pwd):/workspace \
		-w /workspace \
		-e ANDROID_HOME=/opt/android-sdk \
		tabssh-android \
		./gradlew clean assembleRelease --no-daemon --console=plain
	@mkdir -p $(RELEASES)
	@echo "$(BLUE)📦 Copying release APKs to $(RELEASES)/...$(NC)"
	@cp $(RELEASE_DIR)/tabssh-universal.apk $(RELEASES)/ 2>/dev/null || true
	@cp $(RELEASE_DIR)/tabssh-arm64-v8a.apk $(RELEASES)/ 2>/dev/null || true
	@cp $(RELEASE_DIR)/tabssh-armeabi-v7a.apk $(RELEASES)/ 2>/dev/null || true
	@cp $(RELEASE_DIR)/tabssh-x86_64.apk $(RELEASES)/ 2>/dev/null || true
	@cp $(RELEASE_DIR)/tabssh-x86.apk $(RELEASES)/ 2>/dev/null || true
	@echo "$(BLUE)🗜️  Archiving source (excluding VCS)...$(NC)"
	@tar --exclude-vcs \
	     --exclude='*.apk' \
	     --exclude='build' \
	     --exclude='.gradle' \
	     --exclude='*.iml' \
	     --exclude='.idea' \
	     --exclude='local.properties' \
	     --exclude='binaries' \
	     --exclude='releases' \
	     -czf $(RELEASES)/$(PROJECT)-$(VERSION)-source.tar.gz .
	@echo "$(BLUE)📝 Generating release notes...$(NC)"
	@./scripts/build/generate-release-notes.sh $(VERSION) > /tmp/tabssh-android/release-notes.md
	@echo "$(BLUE)🗑️  Deleting existing release v$(VERSION)...$(NC)"
	@gh release delete v$(VERSION) -y 2>/dev/null || echo "  No existing release"
	@echo "$(BLUE)📤 Publishing to GitHub...$(NC)"
	@gh release create v$(VERSION) \
	   --title "TabSSH Android v$(VERSION) - Complete SSH Client" \
	   --notes-file /tmp/tabssh-android/release-notes.md \
	   $(RELEASES)/tabssh-*.apk \
	   $(RELEASES)/$(PROJECT)-$(VERSION)-source.tar.gz
	@echo "$(GREEN)✅ Release v$(VERSION) published!$(NC)"
	@echo "$(GREEN)📁 All release files in ./$(RELEASES)/$(NC)"
	@echo "$(YELLOW)🔗 https://github.com/tabssh/android/releases/tag/v$(VERSION)$(NC)"

dev: ## Build Docker development container
	@echo "$(GREEN)🐳 Building development container...$(NC)"
	@docker build -t tabssh-android -f scripts/docker/Dockerfile .
	@echo "$(GREEN)✅ Container built: tabssh-android$(NC)"
	@echo ""
	@echo "$(BLUE)Run:$(NC) docker-compose -f docker-compose.dev.yml up -d"

clean: ## Clean build artifacts (binaries/ and app/build/)
	@echo "$(BLUE)🧹 Cleaning build artifacts...$(NC)"
	@rm -rf $(BINARIES)/*.apk app/build/ .gradle/
	@echo "$(GREEN)✅ Cleaned: ./$(BINARIES)/, app/build/, .gradle/$(NC)"

install: ## Install universal APK to device (debug)
	@echo "$(BLUE)📱 Installing debug APK...$(NC)"
	@adb install -r $(BINARIES)/tabssh-universal.apk
	@echo "$(GREEN)✅ Installed!$(NC)"

install-release: ## Install universal release APK to device
	@echo "$(BLUE)📱 Installing release APK...$(NC)"
	@adb install -r $(RELEASES)/tabssh-universal.apk
	@echo "$(GREEN)✅ Release APK installed!$(NC)"

logs: ## View app logs from device
	@echo "$(BLUE)📋 Logs (Ctrl+C to stop)...$(NC)"
	@adb logcat | grep --color=auto TabSSH
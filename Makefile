# TabSSH Android - Local Development
# All builds run in Docker for consistency

# === Configuration ===
PROJECT := tabssh
VERSION := $(shell grep versionName app/build.gradle | head -1 | sed 's/.*"\(.*\)".*/\1/')
BUILD_IMAGE := ghcr.io/tabssh/android:build

# Directories
BINARIES := binaries
DEBUG_DIR := app/build/outputs/apk/debug

# Docker run command
DOCKER_RUN := docker run --rm --network=host \
	-v $(shell pwd):/workspace \
	-v $(shell pwd)/.android-keystore:/root/.android \
	-w /workspace \
	-e ANDROID_HOME=/opt/android-sdk \
	-e GRADLE_USER_HOME=/workspace/.gradle

# Colors
GREEN := \033[0;32m
BLUE := \033[0;34m
YELLOW := \033[1;33m
NC := \033[0m

.PHONY: build check clean install logs image help

.DEFAULT_GOAL := help

help: ## Show available targets
	@echo -e "$(GREEN)TabSSH Android v$(VERSION)$(NC)"
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(BLUE)%-10s$(NC) %s\n", $$1, $$2}'

build: _ensure-image ## Build debug APKs
	@echo -e "$(GREEN)🚀 Building TabSSH v$(VERSION)...$(NC)"
	@$(DOCKER_RUN) $(BUILD_IMAGE) ./gradlew clean assembleDebug --no-daemon -q
	@mkdir -p $(BINARIES)
	@cp $(DEBUG_DIR)/*.apk $(BINARIES)/ 2>/dev/null || true
	@echo -e "$(GREEN)✅ Done$(NC)"
	@ls -lh $(BINARIES)/*.apk 2>/dev/null

check: _ensure-image ## Check for errors
	@$(DOCKER_RUN) $(BUILD_IMAGE) ./gradlew compileDebugKotlin --no-daemon 2>&1 | grep "^e:" || echo -e "$(GREEN)✅ No errors$(NC)"

clean: ## Clean build artifacts
	@rm -rf $(BINARIES)/*.apk app/build/ .gradle/
	@echo -e "$(GREEN)✅ Cleaned$(NC)"

install: ## Install APK to device
	@adb install -r $(BINARIES)/tabssh-universal.apk

logs: ## View device logs
	@adb logcat | grep -E "TabSSH|tabssh"

image: ## Build Docker image
	@echo -e "$(BLUE)🐳 Building image...$(NC)"
	@docker build -t $(BUILD_IMAGE) -f docker/Dockerfile .
	@echo -e "$(GREEN)✅ Built: $(BUILD_IMAGE)$(NC)"

_ensure-image:
	@docker image inspect $(BUILD_IMAGE) > /dev/null 2>&1 || $(MAKE) image
	@mkdir -p .android-keystore

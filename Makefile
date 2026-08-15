# TabSSH Android - Local Development
# All builds run in Docker for consistency (AI.md PART 4)

# === Configuration ===
PROJECT := tabssh
# Anchored to the declaration, not the bare word: an unanchored match hit the
# explanatory comment above defaultConfig first, and the resulting string
# carried backticks that every recipe then ran as command substitution.
VERSION := $(shell grep -m1 -E -- '^[[:space:]]*versionName[[:space:]]+"' app/build.gradle | sed 's/.*"\(.*\)".*/\1/')
# Toolchain image declared in IDEA.md ## Toolchain (build_image)
BUILD_IMAGE ?= ghcr.io/tabssh/android:build
# 4g proved too small: the Gradle daemon (Xmx2048m) plus Kotlin/KSP daemons
# plus lintDebug got OOM-killed mid-check under a 4g cgroup limit
DOCKER_MEM ?= 6g
DOCKER_CPUS ?= 2

# Directories
BINARIES := binaries
RELEASES := releases
DEBUG_DIR := app/build/outputs/apk/debug
RELEASE_DIR := app/build/outputs/apk/release

# Docker run command (AI.md PART 4 run pattern)
# ANDROID_HOME is preset in the image; never mount over /opt/android-sdk.
# Gradle cache is project-scoped via GRADLE_USER_HOME=/workspace/.gradle so
# concurrent projects never share caches; the wrapper dist is seeded from the
# image's pre-warmed /root/.gradle so ./gradlew never re-downloads Gradle.
# Recursively expanded (=) so the random container name regenerates on every
# use; each container is --rm and named $(PROJECT)-XXXXXXXX per the naming rule.
DOCKER_RUN = docker run --rm \
	--name $(PROJECT)-$(shell tr -dc 'a-z0-9' </dev/urandom | head -c8) \
	--memory=$(DOCKER_MEM) --cpus=$(DOCKER_CPUS) \
	-v $(PWD):/workspace \
	-w /workspace \
	-e GRADLE_USER_HOME=/workspace/.gradle

# Seed the Gradle wrapper distribution from the image's pre-warmed cache
GRADLE_SEED = [ -d /workspace/.gradle/wrapper ] || { mkdir -p /workspace/.gradle && cp -a /root/.gradle/wrapper /workspace/.gradle/ 2>/dev/null || true; }

# Colors
GREEN := \033[0;32m
BLUE := \033[0;34m
YELLOW := \033[1;33m
NC := \033[0m

.PHONY: help check build release test test-install install clean image fetch-mosh fetch-spice fetch-fonts adb-reconnect logs _ensure-image

.DEFAULT_GOAL := help

help: ## Show available targets
	@echo -e "$(GREEN)TabSSH Android v$(VERSION)$(NC)"
	@echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
	@grep -E -- '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(BLUE)%-12s$(NC) %s\n", $$1, $$2}'

check: _ensure-image ## Compile + lint + JVM unit tests in Docker (pre-commit gate)
	@$(DOCKER_RUN) $(BUILD_IMAGE) sh -c "$(GRADLE_SEED); ./gradlew kspDebugKotlin compileDebugKotlin lintDebug testDebugUnitTest --no-daemon --build-cache" \
		&& echo -e "$(GREEN)✅ No errors$(NC)" \
		|| { echo -e "$(YELLOW)❌ Errors found$(NC)"; exit 1; }

build: _ensure-image fetch-mosh fetch-spice fetch-fonts ## Build debug APKs -> ./binaries/
	@echo -e "$(GREEN)🚀 Building TabSSH v$(VERSION)...$(NC)"
	@$(DOCKER_RUN) $(BUILD_IMAGE) sh -c "$(GRADLE_SEED); ./gradlew clean assembleDebug --no-daemon --build-cache"
	@mkdir -p $(BINARIES)
	@cp $(DEBUG_DIR)/*.apk $(BINARIES)/ 2>/dev/null || true
	@echo -e "$(GREEN)✅ Done$(NC)"
	@ls -lh $(BINARIES)/*.apk 2>/dev/null

release: _ensure-image fetch-mosh fetch-spice fetch-fonts ## Build release APKs -> ./releases/ (local verification only)
	@echo -e "$(GREEN)🚀 Building TabSSH v$(VERSION) (release)...$(NC)"
	@$(DOCKER_RUN) $(BUILD_IMAGE) sh -c "$(GRADLE_SEED); ./gradlew clean assembleRelease --no-daemon --build-cache"
	@mkdir -p $(RELEASES)
	@cp $(RELEASE_DIR)/*.apk $(RELEASES)/ 2>/dev/null || true
	@echo -e "$(GREEN)✅ Done$(NC)"
	@ls -lh $(RELEASES)/*.apk 2>/dev/null

test: check ## Everything in check, plus UI tests when a device is reachable
	@SERIAL=$$($(ADB) devices 2>/dev/null | awk '/\tdevice$$/{print $$1; exit}'); \
	if [ -n "$$SERIAL" ]; then \
		scripts/ui-test.sh --serial "$$SERIAL" $(if $(TEST),$(TEST),all); \
	else \
		echo -e "$(YELLOW)No device/emulator reachable — instrumented tests skipped$(NC)"; \
	fi

fetch-mosh: ## Fetch mosh-client binaries from latest GH release
	@scripts/fetch-mosh-binaries.sh

fetch-spice: ## Fetch SPICE native libs (libtabssh_native.so) from latest GH release
	@scripts/fetch-spice-libs.sh

fetch-fonts: ## Fetch Nerd Fonts (skip-if-present, --force to refresh)
	@scripts/fetch-fonts.sh

clean: ## Clean build artifacts
	@rm -rf $(BINARIES)/*.apk $(RELEASES)/*.apk app/build/ .gradle/
	@echo -e "$(GREEN)✅ Cleaned$(NC)"

ADB := $(shell command -v adb 2>/dev/null || find /opt/android /opt/android-sdk -name adb -type f 2>/dev/null | head -1)

adb-reconnect: ## Reconnect to phone over WireGuard (use after phone reboot)
	@PORT=$$(ssh phone 'getprop service.adb.tls.port 2>/dev/null'); \
	if [ -n "$$PORT" ] && [ "$$PORT" != "0" ]; then \
		echo "Connecting to phone on wireless-debug port $$PORT..."; \
		$(ADB) connect 10.200.0.2:$$PORT; \
	else \
		echo "Wireless debugging not active. Enable it in Developer Options, then retry."; \
		exit 1; \
	fi

install: ## Install universal APK to device
	@$(ADB) install -r $(BINARIES)/tabssh-android-universal.apk

logs: ## View device logs
	@$(ADB) logcat | grep -E -- "TabSSH|tabssh"

test-install: ## Build, install, then run UI tests
	$(MAKE) build install test

image: ## Build Docker image
	@echo -e "$(BLUE)🐳 Building image...$(NC)"
	@docker build -t $(BUILD_IMAGE) -f docker/Dockerfile.build .
	@echo -e "$(GREEN)✅ Built: $(BUILD_IMAGE)$(NC)"

_ensure-image:
	@docker image inspect $(BUILD_IMAGE) > /dev/null 2>&1 || $(MAKE) image

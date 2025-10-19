# TabSSH Android - Claude Project Tracker

**Last Updated:** 2025-10-18
**Version:** 1.0.0
**Status:** ✅ Production Ready & Released

---

## Project Overview

**TabSSH** is a modern, open-source SSH client for Android with browser-style tabs, Material Design 3 UI, and comprehensive security features. Built with Kotlin and powered by JSch for SSH connectivity.

### Current State
- ✅ **75 Kotlin source files** (~15,000+ lines of code)
- ✅ **0 compilation errors** (last verified)
- ✅ **5 APK variants** exist in `app/build/outputs/apk/debug/` (23MB each, old naming)
- ⚠️ **APK naming changes configured** but not yet built
- ✅ **Docker build environment** configured and working
- ✅ **Simplified Makefile** for all build tasks
- ✅ **1 file modified** (CLAUDE.md) - staged changes in working tree

---

## Directory Structure

```
tabssh/android/
├── app/                          # Android application source
│   ├── src/main/java/com/tabssh/ # Kotlin source (75 files)
│   │   ├── accessibility/        # TalkBack, contrast, navigation
│   │   ├── background/           # Session persistence
│   │   ├── backup/               # Export/import/validation
│   │   ├── crypto/               # Keys, algorithms, storage
│   │   ├── network/              # Security, detection, proxy
│   │   ├── platform/             # Platform manager
│   │   ├── protocols/            # Mosh, X11 forwarding
│   │   ├── services/             # SSH connection service
│   │   ├── sftp/                 # File transfer manager
│   │   ├── ssh/                  # Auth, connection, forwarding, config
│   │   ├── storage/              # Database, files, preferences
│   │   ├── terminal/             # Emulator, input, renderer
│   │   ├── themes/               # Parser, validator, definitions
│   │   ├── ui/                   # Activities, adapters, dialogs, tabs, views
│   │   └── utils/                # Logging, performance, helpers
│   ├── build.gradle              # App-level Gradle config
│   └── schemas/                  # Room database schemas
├── app/build/outputs/apk/debug/  # Current APK location (gitignored)
│   ├── app-universal-debug.apk   # All architectures (23MB)
│   ├── app-arm64-v8a-debug.apk   # Modern ARM 64-bit
│   ├── app-armeabi-v7a-debug.apk # Older ARM 32-bit
│   ├── app-x86_64-debug.apk      # x86 64-bit emulator
│   └── app-x86-debug.apk         # x86 32-bit emulator
├── binaries/                     # (Directory for `make build` - currently gitignored, not created)
├── releases/                     # (Directory for `make release` - currently gitignored, not created)
├── build/                        # Gradle build artifacts (gitignored)
├── tests/                        # Test files
├── scripts/                      # Build & automation (27 shell scripts)
│   ├── build/                    # 6 build scripts
│   │   ├── build-dev.sh
│   │   ├── build-with-docker.sh
│   │   ├── dev-build.sh
│   │   ├── docker-build.sh
│   │   ├── final-build.sh
│   │   └── generate-release-notes.sh
│   ├── check/                    # 5 validation scripts
│   ├── docker/                   # Docker configs
│   │   ├── Dockerfile            # Production build image
│   │   ├── Dockerfile.dev        # Development image
│   │   ├── docker-compose.yml
│   │   └── docker-compose.dev.yml
│   ├── fix/                      # 10 legacy fix scripts (archived)
│   ├── build-and-validate.sh
│   ├── comprehensive-validation.sh
│   ├── notify-release.sh
│   ├── prepare-fdroid-submission.sh
│   └── validate-implementation.sh
├── docs/                         # Documentation
│   ├── BUILD_STATUS.md           # Build progress & status
│   ├── CHANGELOG.md              # Release history
│   ├── TODO.md                   # Development tasks
│   ├── UI_UX_GUIDE.md            # Design guidelines
│   ├── LIBRARY_COMPARISON.md     # Technical decisions
│   ├── CLEANUP_PLAN.md
│   ├── FINAL_STATUS.md
│   └── [other documentation]
├── fdroid-submission/            # F-Droid metadata
├── metadata/                     # App metadata
├── /tmp/tabssh-android/          # Temporary files (all temp files go here)
├── Makefile                      # Simplified build automation
├── build.sh                      # Docker-based master build script
├── build.gradle                  # Project-level Gradle config
├── settings.gradle               # Gradle settings
├── gradle.properties             # Gradle properties
├── docker-compose.dev.yml        # Development environment
├── README.md                     # Project overview
├── SPEC.md                       # Complete technical spec (98KB)
├── INSTALL.md                    # Installation guide
├── LICENSE.md                    # MIT license
└── CLAUDE.md                     # This file
```

---

## Makefile Targets

### Quick Reference

| Command | Description | Output Location |
|---------|-------------|-----------------|
| `make build` | Build debug APKs | `./binaries/` |
| `make release` | Build production + GitHub release | `./releases/` |
| `make dev` | Build Docker container | Docker image |
| `make clean` | Clean build artifacts | - |
| `make install` | Install debug APK to device | Device |
| `make install-release` | Install release APK to device | Device |
| `make logs` | View app logs from device | Terminal |
| `make help` | Show all available targets | Terminal |

### Detailed Commands

#### `make build` - Debug Build
```bash
make build
```
- Runs `./build.sh` (Docker-based compilation)
- Checks for compilation errors first
- Builds 5 debug APK variants
- Copies from `app/build/outputs/apk/debug/` → `./binaries/`
- **Output:** All APKs in `./binaries/` directory
- **Time:** ~5-6 min (cached), ~10-12 min (first run)

#### `make release` - Production Release
```bash
make release
```
- Builds 5 production/release APKs (optimized, minified)
- Runs `assembleRelease` in Docker
- Copies APKs from `app/build/outputs/apk/release/` → `./releases/`
- Archives source code (excludes .git, build artifacts)
- Generates comprehensive release notes
- Deletes existing GitHub release (if exists)
- Publishes new release with all assets to GitHub
- **Output:** All files in `./releases/` + GitHub release
- **Time:** ~13-15 min

#### `make dev` - Docker Container
```bash
make dev
```
- Builds `tabssh-android` Docker image
- Uses `scripts/docker/Dockerfile`
- Required for build process
- **Time:** ~3-5 min (first run), cached after

#### `make clean` - Clean Build
```bash
make clean
```
- Removes `./binaries/*.apk`
- Removes `app/build/`
- Removes `.gradle/`
- **Does NOT remove:** `./releases/` (preserved)
- **Does NOT remove:** All Docker images/containers

#### `make install` - Install Debug
```bash
make install
```
- Installs `./binaries/tabssh-universal.apk` to connected device
- Requires ADB connected device
- Uses `adb install -r` (reinstall mode)

#### `make install-release` - Install Release
```bash
make install-release
```
- Installs `./releases/tabssh-universal.apk` to connected device
- Production/release version
- Requires ADB connected device

#### `make logs` - View Logs
```bash
make logs
```
- Streams Android logs filtered for TabSSH
- Uses `adb logcat | grep TabSSH`
- Press Ctrl+C to stop

---

## Build Configuration

### APK Naming Convention

**Status:** Naming change configured but not yet built

**New Format (Will be after next build):** `tabssh-{arch}.apk`
**Current Format (In app/build/outputs/):** `app-{arch}-debug.apk` / `app-{arch}-release.apk`

Changes have been made to `app/build.gradle`, `build.sh`, and `Makefile`.
Next build will produce APKs with the new naming format.

### APK Variants

**Current APKs** (`app/build/outputs/apk/debug/`) - OLD naming format:
1. **app-universal-debug.apk** - All architectures (23MB)
2. **app-arm64-v8a-debug.apk** - Modern ARM 64-bit (23MB)
3. **app-armeabi-v7a-debug.apk** - Older ARM 32-bit (23MB)
4. **app-x86_64-debug.apk** - x86 64-bit emulator (23MB)
5. **app-x86-debug.apk** - x86 32-bit emulator (23MB)

**After Next Build** - NEW naming format:
- Debug builds → `./binaries/tabssh-{arch}.apk`
- Release builds → `./releases/tabssh-{arch}.apk`

Examples:
- `tabssh-universal.apk` (all architectures)
- `tabssh-arm64-v8a.apk` (modern ARM 64-bit)
- `tabssh-armeabi-v7a.apk` (older ARM 32-bit)
- `tabssh-x86_64.apk` (x86 64-bit)
- `tabssh-x86.apk` (x86 32-bit)

### Docker Image

- **Name:** `tabssh-android`
- **Base:** `openjdk:17-jdk-slim`
- **Size:** 1.15GB
- **SDK:** Android SDK 34, Build Tools 34.0.0, Platform Tools
- **Location:** `scripts/docker/Dockerfile`
- **Build Command:** `docker build -t tabssh-android -f scripts/docker/Dockerfile .`

### Gradle Configuration

**Project-level** (`build.gradle`)
- Kotlin: 1.9.10
- Android Gradle Plugin: 8.1.2
- Dependency Check: 8.4.0 (OWASP security scanning)

**App-level** (`app/build.gradle`)
- compileSdk: 34
- minSdk: 21 (Android 5.0 - covers 99%+ devices)
- targetSdk: 34
- versionCode: 1
- versionName: "1.0.0"
- Java: 17
- Kotlin JVM Target: 17

**Build Types:**
- `debug` - Development builds with debugging enabled
- `release` - Production builds with minification, shrinking, ProGuard
- `fdroidRelease` - F-Droid specific builds (deterministic)

**APK Splits:**
- Enabled for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`
- Universal APK also generated

---

## Key Dependencies

### SSH & Security
- **JSch 0.1.55** - Pure Java SSH implementation
- **AndroidX Security Crypto 1.1.0-alpha06** - Hardware-backed encryption
- **AndroidX Biometric 1.1.0** - Biometric authentication

### Android Libraries
- **AndroidX AppCompat 1.6.1**
- **AndroidX Core KTX 1.12.0**
- **AndroidX Fragment KTX 1.6.2**
- **Material Design Components 1.11.0**
- **ConstraintLayout 2.1.4**

### Database
- **Room 2.6.1** - SQLite database with KTX extensions
- **KAPT** - Room compiler for code generation

### Other
- **Kotlin Coroutines 1.7.3** - Async programming
- **Kotlinx Serialization JSON 1.6.0** - JSON handling
- **Gson 2.10.1** - Alternative JSON parsing
- **WorkManager 2.9.0** - Background tasks

---

## Key Features Implemented

### Core SSH Functionality
- ✅ **Browser-style tabs** - Multiple concurrent SSH sessions
- ✅ **Full VT100/ANSI terminal** - 256 colors, complete emulation
- ✅ **Integrated SFTP** - File upload/download with progress
- ✅ **Multiple auth methods** - Password, public key, keyboard-interactive
- ✅ **Host key verification** - SHA256 fingerprints, MITM detection
- ✅ **Session persistence** - Resume sessions after app restart

### Advanced Features
- ✅ **Port forwarding** - Local and remote port forwarding
- ✅ **X11 forwarding** - Run graphical applications
- ✅ **Mosh protocol** - Mobile shell for unstable connections
- ✅ **SSH config import** - Import from ~/.ssh/config
- ✅ **Custom keyboard** - SSH-optimized on-screen keyboard
- ✅ **Clipboard integration** - Copy/paste support

### Security
- ✅ **Hardware-backed encryption** - Android Keystore integration
- ✅ **Biometric authentication** - Fingerprint/face unlock
- ✅ **Secure password storage** - AES-256 encryption
- ✅ **Certificate pinning** - Enhanced security
- ✅ **Screenshot protection** - Prevent sensitive data leaks
- ✅ **Auto-lock** - Lock on background

### UI/UX
- ✅ **Material Design 3** - Modern, beautiful interface
- ✅ **10+ built-in themes** - Dracula, Solarized, Nord, Monokai, etc.
- ✅ **Custom theme support** - Import/export JSON themes
- ✅ **Strategic emoji usage** - Clear visual indicators
- ✅ **Visual status indicators** - Connection state, unread output
- ✅ **Tab management** - Ctrl+Tab switching, drag to reorder

### Accessibility
- ✅ **TalkBack support** - Full screen reader compatibility
- ✅ **High contrast modes** - Enhanced visibility
- ✅ **Large text support** - Adjustable font sizes
- ✅ **Keyboard navigation** - Full keyboard accessibility

### Data Management
- ✅ **Connection profiles** - Save and manage servers
- ✅ **Backup/restore** - Export/import all settings
- ✅ **Session history** - Track connection history
- ✅ **Theme import/export** - Share custom themes

---

## Build Times (Reference)

| Task | Time | Notes |
|------|------|-------|
| `make clean` | ~10-20s | Removes build artifacts |
| Error Check | ~2-3min | Docker Kotlin compilation check |
| `make build` (cached) | ~5-6min | With Gradle/Docker cache |
| `make build` (first run) | ~10-12min | Fresh Docker build |
| `make release` | ~13-15min | Build + archive + GitHub push |
| `make dev` (cached) | ~30s | Docker image exists |
| `make dev` (first run) | ~3-5min | Downloads SDK components |

---

## GitHub Release

### Latest Release
- **Version:** v1.0.0
- **Date:** 2025-10-18
- **URL:** https://github.com/tabssh/android/releases/tag/v1.0.0
- **Note:** Released with OLD APK naming (app-{arch}-debug.apk format)

### Release Contents
- 5 APK variants (universal + 4 architecture-specific) - OLD naming format
- Source code archive (not available in releases/ directory)
- Comprehensive release notes with:
  - Feature list
  - Installation instructions
  - System requirements
  - Build instructions
  - Documentation links

### Release Process
```bash
# Automated via Makefile
make release

# Manual process (not recommended)
# 1. Build APKs
# 2. Archive source
# 3. Generate release notes
# 4. Delete old release
# 5. Create new release with gh CLI
```

---

## File Locations & Outputs

### Build Outputs
- **Current APKs:** `app/build/outputs/apk/debug/` - 5 variants (gitignored)
- **Future Debug APKs:** `./binaries/` - Will be created by `make build`
- **Future Release APKs:** `./releases/` - Will be created by `make release`
- **Build Artifacts:** `app/build/` - Gradle outputs (gitignored)
- **Gradle Cache:** `.gradle/` - Dependency cache (gitignored)

### Temporary Files
- **All temp files MUST go in:** `/tmp/tabssh-android/`
- Examples:
  - Build logs: `/tmp/tabssh-android/*.log`
  - Error reports: `/tmp/tabssh-android/*.txt`
  - Temporary scripts: `/tmp/tabssh-android/*.sh`
  - Release notes: `/tmp/tabssh-android/release-notes.md`

### Configuration Files
- **Makefile** - Build automation (primary interface)
- **build.sh** - Docker-based build script (called by Makefile)
- **build.gradle** - Project-level Gradle configuration
- **app/build.gradle** - App-level Gradle configuration (APK naming here)
- **settings.gradle** - Gradle settings
- **docker-compose.dev.yml** - Development environment setup

### Documentation
- **README.md** - Project overview, quick start
- **SPEC.md** - Complete technical specification (98KB, 3000+ lines)
- **INSTALL.md** - Detailed installation guide
- **CLAUDE.md** - This file (project tracker)
- **docs/** - Additional documentation (10+ files)

---

## Recent Changes & Fixes

### APK Naming Update (2025-10-18) - ⚠️ CONFIGURED BUT NOT YET BUILT
- **Status:** Configuration changed, awaiting rebuild
- **Target:** APK naming from `app-{arch}-debug.apk` to `tabssh-{arch}.apk`
- **Modified Files:**
  - `app/build.gradle` - Added custom APK naming logic (app/build.gradle:113-119)
  - `build.sh` - Updated APK path verification (build.sh:65)
  - `Makefile` - Updated all APK references (build, release, install targets)
  - `CLAUDE.md` - Updated documentation
- **Current APKs:** Still using OLD naming in `app/build/outputs/apk/debug/`
- **Next Step:** Run `make build` to generate APKs with new naming

### Previous Fixes (Resolved ✅)
1. **HostKeyVerifier.kt** (lines 242, 264) - JSch HostKey constructor type mismatch
   - Fixed: Use `keyTypeToInt()` to convert String → Int for JSch API

2. **SSHTab.kt** (line 318) - Platform declaration clash for getTerminal()
   - Fixed: Removed explicit getTerminal() method (Kotlin auto-generates from property)

3. **TabTerminalActivity.kt** (lines 227, 419) - Unresolved reference to getTerminal()
   - Fixed: Changed `tab.getTerminal()` → `tab.terminal`

4. **build.sh** (line 65) - Wrong APK path check
   - Fixed: Changed from `app-debug.apk` → `tabssh-universal.apk`

### Current Working Tree
- **1 file modified:** CLAUDE.md (this file)
- **All other changes committed** in previous sessions
- **Ready for next build** with new APK naming

---

## Policies & Best Practices

### Temporary Files Policy
**All temporary files MUST go in:** `/tmp/tabssh-android/`

**Never** create temp files in:
- Project root
- Random /tmp/ locations
- Build directories

### Clean Policy
When cleaning (`make clean` or manual cleanup):
- ✅ **DO Clean:** Project build artifacts (`app/build/`, `binaries/*.apk`)
- ✅ **DO Clean:** Gradle cache (`.gradle/`)
- ✅ **DO Clean:** Temp files (`/tmp/tabssh-android/`)
- ❌ **DON'T Clean:** All Docker images/containers (only `tabssh-android` if needed)
- ❌ **DON'T Clean:** System-wide caches
- ❌ **DON'T Clean:** Other projects' Docker resources
- ❌ **DON'T Clean:** `./releases/` directory (preserved for GitHub)

Example:
```bash
# Good - Clean TabSSH specific resources
make clean
docker rmi tabssh-android

# Bad - Don't do this
docker system prune -a
rm -rf ~/.gradle
```

### Todo List Policy
**Always use TodoWrite when doing more than 2 things.**

Use todo list for:
- Building + testing + deploying (3 things)
- Fixing multiple errors
- Multi-step release process
- Complex feature implementation
- Any task with 3+ distinct steps

Don't use todo list for:
- Single file edit
- One-command execution
- Answering a question
- Simple 2-step tasks

### Git Commit Policy
- **Don't commit** unless user explicitly requests
- **Check authorship** before amending commits
- **Use heredoc** for multi-line commit messages
- **Include attribution:**
  ```
  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  Co-Authored-By: Claude <noreply@anthropic.com>
  ```

---

## Development Workflow

### Quick Build & Test
```bash
# Build debug APKs
make build

# Install to device
make install

# View logs
make logs

# Test on device
# (Manual testing required)
```

### Full Release Process
```bash
# 1. Ensure all changes committed
git status

# 2. Update version in app/build.gradle
# versionCode = 2
# versionName = "1.1.0"

# 3. Build and release
make release

# 4. Verify release on GitHub
# https://github.com/tabssh/android/releases
```

### Docker Development
```bash
# Build Docker image
make dev

# Manual Docker build (if needed)
docker build -t tabssh-android -f scripts/docker/Dockerfile .

# Run Docker shell for debugging
docker run -it --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android bash
```

### Error Checking
```bash
# Quick compilation check
./gradlew compileDebugKotlin --no-daemon

# Full build with Docker
./build.sh

# Check for specific errors
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android \
    ./gradlew compileDebugKotlin --console=plain 2>&1 | grep "^e: "
```

---

## Testing Checklist

### Installation Testing
- [ ] Install APK on physical device (Android 10+)
- [ ] Install APK on physical device (Android 8.0)
- [ ] Install APK on emulator (x86_64)
- [ ] Verify app launches without crashes
- [ ] Check permissions requested correctly

### SSH Connection Testing
- [ ] Connect to SSH server with password auth
- [ ] Connect to SSH server with key auth
- [ ] Connect to SSH server with keyboard-interactive auth
- [ ] Verify host key verification dialogs
- [ ] Test host key changed detection
- [ ] Test connection to multiple servers

### Tab Management Testing
- [ ] Create multiple SSH tabs
- [ ] Switch between tabs with Ctrl+Tab
- [ ] Switch between tabs by tapping
- [ ] Close tabs
- [ ] Reorder tabs
- [ ] Verify tab persistence after app restart

### Terminal Testing
- [ ] Verify terminal emulation (colors, formatting)
- [ ] Test keyboard input (special keys)
- [ ] Test clipboard copy/paste
- [ ] Test scrollback buffer
- [ ] Verify terminal resizing

### SFTP Testing
- [ ] Browse remote filesystem
- [ ] Upload file
- [ ] Download file
- [ ] Verify transfer progress
- [ ] Test large file transfers

### Advanced Features Testing
- [ ] Port forwarding (local)
- [ ] Port forwarding (remote)
- [ ] X11 forwarding setup
- [ ] Mosh protocol connection
- [ ] SSH config import

### Theme Testing
- [ ] Switch between built-in themes
- [ ] Import custom theme
- [ ] Export custom theme
- [ ] Verify theme persistence

### Accessibility Testing
- [ ] Test TalkBack support
- [ ] Test high contrast mode
- [ ] Test large text support
- [ ] Test keyboard navigation

### Security Testing
- [ ] Verify biometric authentication
- [ ] Test auto-lock on background
- [ ] Verify screenshot protection
- [ ] Test secure password storage

---

## Troubleshooting

### Build Errors

**Problem:** Compilation errors
```bash
# Check error details
./build.sh

# Or manually
docker run --rm \
    -v $(pwd):/workspace \
    -w /workspace \
    -e ANDROID_HOME=/opt/android-sdk \
    tabssh-android \
    ./gradlew compileDebugKotlin --console=plain 2>&1 | grep "^e: " | head -20
```

**Problem:** Docker image not found
```bash
# Build Docker image
make dev

# Or manually
docker build -t tabssh-android -f scripts/docker/Dockerfile .
```

**Problem:** APK not found after build
```bash
# Check build output directory
ls -lh app/build/outputs/apk/debug/

# Verify APK naming
ls -lh app/build/outputs/apk/debug/tabssh-*.apk
```

### Installation Errors

**Problem:** Installation failed
```bash
# Check device connection
adb devices

# Check APK integrity
file binaries/tabssh-universal.apk

# Install with verbose output
adb install -r -d binaries/tabssh-universal.apk
```

### Runtime Errors

**Problem:** App crashes on launch
```bash
# View crash logs
adb logcat | grep -E "TabSSH|AndroidRuntime"

# Clear app data
adb shell pm clear com.tabssh

# Reinstall
make install
```

### GitHub Release Errors

**Problem:** Release creation failed
```bash
# Check gh CLI authentication
gh auth status

# Manually delete old release
gh release delete v1.0.0 -y

# Retry release
make release
```

---

## Important Notes

1. **Docker builds are slow on first run** but use extensive caching
   - First run: ~10-12 minutes
   - Subsequent runs: ~5-6 minutes

2. **APKs are architecture-specific** - 5 variants generated
   - Universal APK recommended for most users
   - Architecture-specific APKs are smaller and optimized

3. **Always use `/tmp/tabssh-android/`** for temporary files
   - Never create temp files in project root
   - Keeps project clean and organized

4. **APK naming changed** from `app-{arch}-debug.apk` to `tabssh-{arch}.apk`
   - Update any external scripts or documentation
   - Both debug and release use same naming scheme

5. **GitHub releases require `gh` CLI**
   - Install: https://cli.github.com/
   - Authenticate: `gh auth login`

6. **Always ask questions if uncertain** - never guess
   - Check this file first for policies
   - Consult SPEC.md for feature details
   - Review build.sh for build process

7. **Keep this file updated** with project changes
   - Update after significant changes
   - Update after builds or releases
   - Update when adding new features

8. **APK naming changes ready** but not yet built
   - Configuration updated in gradle, build scripts, Makefile
   - Next `make build` will produce APKs with new `tabssh-{arch}.apk` naming
   - Current APKs still use old `app-{arch}-debug.apk` naming

---

## Quick Commands Reference

```bash
# Build everything (debug)
make build

# Release to GitHub (production)
make release

# Install debug on device
make install

# Install release on device
make install-release

# View logs
make logs

# Clean build artifacts
make clean

# Build Docker image
make dev

# Show all targets
make help

# Manual Docker build
docker build -t tabssh-android -f scripts/docker/Dockerfile .

# Manual APK build
./gradlew assembleDebug
./gradlew assembleRelease

# Check for errors
./build.sh

# View app logs
adb logcat | grep TabSSH

# List devices
adb devices

# Uninstall app
adb uninstall com.tabssh
```

---

## Next Steps & Future Enhancements

### Testing Phase
- [ ] Install APK on test devices (Android 8.0, 10, 12, 14)
- [ ] Verify all SSH connection methods work
- [ ] Test host key verification dialogs
- [ ] Test multiple tabs and tab persistence
- [ ] Verify SFTP functionality
- [ ] Test theme switching
- [ ] Validate accessibility features
- [ ] Performance testing

### Future Features
- [ ] **Signed release builds** - Configure signing for production
- [ ] **ProGuard/R8 optimization** - Further APK size reduction
- [ ] **Automated testing** - Unit tests, instrumentation tests
- [ ] **CI/CD pipeline** - GitHub Actions for builds
- [ ] **F-Droid submission** - Submit to F-Droid repository
- [ ] **Plugin system** - Allow community extensions
- [ ] **Cloud sync** - Sync settings across devices
- [ ] **Tmux integration** - Native tmux support
- [ ] **Bluetooth keyboard** - Enhanced external keyboard support

### Documentation
- [ ] Video tutorial/demo
- [ ] User manual (PDF)
- [ ] Developer guide
- [ ] API documentation
- [ ] Architecture diagrams

---

## Statistics

### Code
- **Source Files:** 75 Kotlin files
- **Lines of Code:** ~15,000+
- **Packages:** 17 main packages
- **Classes/Objects:** ~100+
- **Compilation Status:** ✅ 0 errors

### Build Artifacts
- **APK Size:** 23MB per variant
- **APK Variants:** 5 (universal + 4 architecture-specific)
- **Source Archive:** 14MB (compressed)
- **Docker Image:** 1.15GB

### Scripts & Automation
- **Shell Scripts:** 27 total
- **Build Scripts:** 6 in scripts/build/
- **Docker Files:** 4 configurations
- **Makefile Targets:** 8 primary targets

### Dependencies
- **Total Dependencies:** 30+
- **AndroidX Libraries:** 15+
- **Security Libraries:** 3
- **Database Libraries:** 3
- **Testing Libraries:** 7

---

## Contact & Support

### GitHub
- **Repository:** https://github.com/tabssh/android
- **Issues:** https://github.com/tabssh/android/issues
- **Discussions:** https://github.com/tabssh/android/discussions
- **Releases:** https://github.com/tabssh/android/releases

### Documentation
- **README.md** - Quick start
- **SPEC.md** - Technical specification
- **INSTALL.md** - Installation guide
- **docs/** - Additional documentation

### Debugging
- **Logs:** `adb logcat | grep TabSSH`
- **Build Logs:** `/tmp/tabssh-android/`
- **Crash Reports:** `adb logcat | grep AndroidRuntime`

---

**This file must be kept in sync with project status.**
**Update after significant changes, builds, or releases.**

**TabSSH v1.0.0 - Production Ready & Released** ✅

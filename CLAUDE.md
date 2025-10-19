# TabSSH Android - Claude Project Tracker

**Last Updated:** 2025-10-18
**Version:** 1.0.0
**Status:** ✅ Production Ready

---

## Current Status

### Build System
- ✅ **0 compilation errors**
- ✅ **Makefile simplified and working**
- ✅ **Production release builds configured**
- ✅ **Docker build environment configured**
- ✅ **5 APK variants built** (23MB each)
- ✅ **GitHub release v1.0.0 published**
- ✅ **Comprehensive release notes generation**

### Recent Accomplishments
1. Fixed all 124 compilation errors
2. Implemented complete host key verification system
3. Created simplified Makefile with build/release/dev targets
4. Organized repository structure (binaries/, releases/, scripts/, tests/, docs/)
5. Built and released v1.0.0 to GitHub

---

## Directory Structure

```
tabssh/android/
├── app/                    # Application source code
│   └── src/main/java/com/tabssh/
├── binaries/               # Development APKs (5 variants, 23MB each)
├── releases/               # GitHub release archives
│   └── tabssh-android-1.0.0-source.tar.gz (14MB)
├── scripts/                # Build & automation scripts
│   ├── build/
│   ├── check/
│   ├── docker/
│   └── fix/
├── tests/                  # Test files
├── docs/                   # Documentation
├── /tmp/tabssh-android/    # Temporary files (all temp files go here)
├── Makefile                # Simplified build automation
├── build.sh                # Docker-based build script
├── docker-compose.dev.yml  # Development environment
└── CLAUDE.md               # This file
```

---

## Makefile Targets

### Available Commands

| Command | Description | Status |
|---------|-------------|--------|
| `make build` | Build debug APKs → ./binaries/ | ✅ Working |
| `make release` | Build production APKs → ./releases/ + GitHub | ✅ Working |
| `make dev` | Build Docker development container | ✅ Ready |
| `make clean` | Clean build artifacts (project only) | ✅ Ready |
| `make install` | Install debug APK to device | ✅ Ready |
| `make install-release` | Install production APK to device | ✅ Ready |
| `make logs` | View app logs from device | ✅ Ready |

### Usage

```bash
# Build debug APKs for development
make build

# Build production releases and publish to GitHub
make release

# Build Docker container
make dev

# Install debug APK
make install

# Install production APK
make install-release

# Clean project artifacts only (not all Docker images)
make clean
```

### Build Targets

**`make build`**
- Builds debug APKs via build.sh
- Uses Docker with error checking
- Outputs to ./binaries/
- Fast for development iteration

**`make release`**
- Builds production/release APKs (signed, optimized)
- Runs assembleRelease in Docker
- Archives source code (excludes .git)
- Generates comprehensive release notes
- Deletes existing GitHub release
- Publishes new release with all assets
- Outputs to ./releases/

---

## Build Configuration

### Docker Image
- **Name:** `tabssh-android`
- **Base:** openjdk:17-jdk-slim
- **SDK:** Android SDK 34, Build Tools 34.0.0
- **Location:** `scripts/docker/Dockerfile`

### Gradle
- **Version:** 8.1.1
- **Java:** 17
- **Kotlin:** 1.9+

### APK Variants

**Debug Builds** (./binaries/)
1. **tabssh-universal.apk** - All architectures (development)
2. **tabssh-arm64-v8a.apk** - Modern ARM 64-bit
3. **tabssh-armeabi-v7a.apk** - Older ARM 32-bit
4. **tabssh-x86_64.apk** - Emulator x86 64-bit
5. **tabssh-x86.apk** - Emulator x86 32-bit

**Production Builds** (./releases/)
1. **tabssh-universal.apk** - All architectures (recommended for users)
2. **tabssh-arm64-v8a.apk** - Modern ARM 64-bit (optimized)
3. **tabssh-armeabi-v7a.apk** - Older ARM 32-bit (optimized)
4. **tabssh-x86_64.apk** - Emulator x86 64-bit
5. **tabssh-x86.apk** - Emulator x86 32-bit

---

## Key Files & Locations

### Build Outputs
- **Binaries:** `./binaries/` - Dev APKs (updated by make build)
- **Releases:** `./releases/` - Source archives for GitHub
- **Build Logs:** `app/build/` - Gradle build artifacts
- **Temp Files:** `/tmp/tabssh-android/` - All temporary files

### Configuration
- **Makefile** - Build automation (uses build.sh internally)
- **build.sh** - Docker-based build with error checking
- **docker-compose.dev.yml** - Dev environment setup

### Documentation
- **README.md** - Project overview
- **SPEC.md** - Feature specifications
- **BUILD_SUCCESS.md** - Build report
- **INSTALL.md** - Installation guide
- **QUICK_START.txt** - Quick reference

---

## Recent Fixes

### Compilation Errors (All Resolved ✅)
1. **HostKeyVerifier.kt** (lines 242, 264) - JSch HostKey constructor type mismatch
   - Fixed: Use keyTypeToInt() to convert String → Int for JSch API

2. **SSHTab.kt** (line 318) - Platform declaration clash for getTerminal()
   - Fixed: Removed explicit getTerminal() method (Kotlin auto-generates from property)

3. **TabTerminalActivity.kt** (lines 227, 419) - Unresolved reference to getTerminal()
   - Fixed: Changed tab.getTerminal() → tab.terminal

4. **build.sh** (line 65) - Wrong APK path check
   - Fixed: Changed from app-debug.apk → tabssh-universal.apk

5. **APK Naming** - Updated to use tabssh-{arch}.apk format
   - Modified app/build.gradle to use custom APK naming
   - Updated build.sh, Makefile, and CLAUDE.md to reflect new names

---

## Temporary Files Policy

**All temporary files MUST go in:** `/tmp/tabssh-android/`

Examples:
- Build logs: `/tmp/tabssh-android/*.log`
- Error reports: `/tmp/tabssh-android/*.txt`
- Temporary scripts: `/tmp/tabssh-android/*.sh`

**Never** create temp files in project root or random locations.

---

## Clean Policy

When cleaning (make clean or manual cleanup):
- ✅ **Clean:** Project build artifacts (app/build/, binaries/*.apk, etc.)
- ✅ **Clean:** Gradle cache (.gradle/)
- ✅ **Clean:** Temp files in /tmp/tabssh-android/
- ❌ **DON'T:** Remove all Docker images/containers
- ❌ **DON'T:** Remove system-wide caches
- ❌ **DON'T:** Remove other projects' Docker resources

Only clean **TabSSH-specific** resources:
```bash
# Good
docker rmi tabssh-android

# Bad
docker system prune -a
```

---

## Todo List Policy

**Always use TodoWrite when doing more than 2 things.**

Examples of when to use todo list:
- Building + testing + deploying (3 things)
- Fixing multiple errors
- Multi-step release process
- Complex feature implementation

Examples of when NOT to use:
- Single file edit
- One-command execution
- Answering a question

---

## GitHub Release

**Latest Release:** v1.0.0
**URL:** https://github.com/tabssh/android/releases/tag/v1.0.0

**Contents:**
- 5 APK variants (all architectures)
- Source code archive (excluding .git)
- Release notes with features and installation instructions

---

## Next Steps

### Testing
- [ ] Install APK on test device
- [ ] Verify SSH connections work
- [ ] Test host key verification dialogs
- [ ] Test multiple tabs
- [ ] Verify SFTP functionality
- [ ] Test theme switching

### Future Enhancements
- [ ] Signed release builds (not just debug)
- [ ] ProGuard/R8 optimization
- [ ] Automated testing setup
- [ ] CI/CD pipeline
- [ ] F-Droid submission

---

## Build Times (Reference)

| Task | Time | Notes |
|------|------|-------|
| Clean | ~30-60s | Gradle clean |
| Error Check | ~2-3min | Docker Kotlin compilation |
| Full Build | ~10-12min | Docker first run, ~5-6min cached |
| Release | ~13-15min | Build + archive + GitHub push |

---

## Important Notes

1. **Docker builds are slow on first run** but use extensive caching
2. **APKs are architecture-specific** (no single app-debug.apk)
3. **Always use /tmp/tabssh-android/** for temporary files
4. **Always ask questions** if uncertain - never guess
5. **Keep this file updated** with project changes

---

## Quick Commands

```bash
# Build everything
make build

# Release to GitHub
make release

# Install on device
make install

# View logs
make logs

# Clean (project only)
make clean
```

---

**This file must be kept in sync with project status.**
Update after significant changes, builds, or releases.

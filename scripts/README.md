# TabSSH Scripts Directory

Essential scripts for building, testing, and releasing TabSSH Android.

## 📋 Quick Reference

### Before Every Commit
```bash
./scripts/pre-commit-check.sh
```
**Always run this before committing!** Catches compilation errors and build failures.

### Build & Install
```bash
# Full clean and rebuild
./scripts/clean-build.sh

# Install to connected device
./scripts/install-to-device.sh
```

### Development
```bash
# Quick error count (fast)
./scripts/check/quick-check.sh

# Enter Docker dev shell
./scripts/check/dev-shell.sh
```

### Release Management
```bash
# Prepare F-Droid submission
./scripts/prepare-fdroid-submission.sh

# Send release notifications
./scripts/notify-release.sh v1.0.0
```

---

## 📖 Detailed Documentation

### pre-commit-check.sh
**Purpose:** Comprehensive pre-commit validation  
**Run Time:** ~6-8 minutes  
**When to use:** Before every `git commit`

Performs 3 checks:
1. ✅ Compilation errors (must be 0)
2. ✅ Debug build (must succeed)
3. ✅ APK generation (must create 5 APKs)

**Exit codes:**
- `0` - All checks passed, safe to commit
- `1` - Checks failed, DO NOT commit

**Example:**
```bash
./scripts/pre-commit-check.sh
# If passed:
git add -A
git commit -m "Your message"
git push origin main
```

---

### test-build.sh
**Purpose:** Fast build test using Docker  
**Run Time:** ~5-6 minutes  
**When to use:** Quick validation without full checks

Simpler alternative to `pre-commit-check.sh` - just builds without extra validation.

---

### clean-build.sh
**Purpose:** Full clean and rebuild from scratch  
**Run Time:** ~10-12 minutes  
**When to use:** 
- Build acting strange
- After major changes
- Before important releases

Removes all build artifacts and Gradle cache, then runs fresh build.

---

### install-to-device.sh
**Purpose:** Install TabSSH APK to connected Android device via ADB  
**Run Time:** ~10-30 seconds  
**When to use:** Testing on real device

**Auto-detects APKs in this order:**
1. Specified path: `./scripts/install-to-device.sh /path/to/apk`
2. `binaries/tabssh-universal.apk` (debug build)
3. `app/build/outputs/apk/debug/tabssh-universal.apk`
4. `releases/tabssh-universal.apk` (release build)

**Prerequisites:**
- ADB installed (Android SDK Platform Tools)
- Device connected via USB
- USB debugging enabled
- Device authorized

**Example:**
```bash
# Install debug build
make build
./scripts/install-to-device.sh

# Install release build
make release
./scripts/install-to-device.sh releases/tabssh-universal.apk

# View logs after install
make logs
```

---

### check/quick-check.sh
**Purpose:** Fast compilation error count  
**Run Time:** ~2-3 minutes  
**When to use:** Quick validation during development

Returns error count as exit code. Useful for CI/scripts.

**Example:**
```bash
./scripts/check/quick-check.sh
# Exit code = number of errors
# 0 = no errors ✅
# >0 = errors found ❌
```

---

### check/dev-shell.sh
**Purpose:** Enter TabSSH Android development container shell  
**Run Time:** ~5 seconds  
**When to use:** 
- Manual Gradle commands
- Debugging build issues
- Exploring build environment

**Example:**
```bash
./scripts/check/dev-shell.sh
# Now in container:
./gradlew tasks
./gradlew assembleDebug
exit
```

---

### prepare-fdroid-submission.sh
**Purpose:** Generate F-Droid submission package  
**Run Time:** ~5 seconds  
**When to use:** Preparing F-Droid app submission

Creates `fdroid-submission/` directory with:
- `RFP_SUBMISSION.md` - Request for Packaging text
- `COMPLIANCE_CHECKLIST.md` - F-Droid compliance verification
- Metadata files for F-Droid

**Example:**
```bash
./scripts/prepare-fdroid-submission.sh
# Upload fdroid-submission/ contents to:
# https://gitlab.com/fdroid/rfp/-/issues/new
```

---

### notify-release.sh
**Purpose:** Send release notifications to community channels  
**Run Time:** ~10 seconds  
**When to use:** After publishing new release

Sends notifications to:
- Matrix (if `MATRIX_TOKEN` set)
- Mastodon (if `MASTODON_TOKEN` set)
- Discord (if `DISCORD_WEBHOOK` set)
- Generates `release-message.txt` for manual posting

**Example:**
```bash
# After `make release`:
export MATRIX_TOKEN="your_token"
export MASTODON_TOKEN="your_token"
./scripts/notify-release.sh v1.0.0
```

---

## 🔧 Integration with Makefile

These scripts integrate with Makefile commands:

| Makefile Command | Script(s) Used |
|------------------|----------------|
| `make build` | Uses `build.sh` (root) with Docker |
| `make release` | Uses `build.sh` + GitHub CLI |
| `make clean` | Direct Gradle/filesystem commands |
| `make install` | Similar to `install-to-device.sh` |
| `make logs` | Direct ADB command |

**Pre-commit workflow:**
```bash
# 1. Make changes
vim app/src/main/java/...

# 2. Test locally
./scripts/pre-commit-check.sh

# 3. If passed, commit
git add -A
git commit -m "Fix: description"
git push origin main
```

---

## 🚫 Removed Scripts

The following outdated scripts were removed (as of 2026-02-10):

### Removed: fix/ directory (10 scripts)
- All error-fixing scripts removed (errors already fixed)
- Legacy scripts from initial development phase
- No longer needed with 0 compilation errors

### Removed: Outdated check scripts
- `check/docker-check-errors.sh` - Replaced by `pre-commit-check.sh`
- `check/get-errors.sh` - Legacy error extraction
- `check/get-unique-errors.sh` - Legacy error extraction

### Removed: Outdated build scripts
- `build-and-validate.sh` - Replaced by `pre-commit-check.sh`
- `comprehensive-validation.sh` - Incomplete/outdated
- `validate-implementation.sh` - Incomplete/outdated

**If you need these scripts, check git history:**
```bash
git log --all --full-history -- scripts/fix/
git checkout <commit> -- scripts/fix/script-name.sh
```

---

## 📊 Script Maintenance

### Adding New Scripts

1. Create script in appropriate directory:
   - `scripts/` - Main scripts (build, release, etc.)
   - `scripts/check/` - Validation/checking scripts
   - `scripts/tools/` - Utility scripts (if needed)

2. Make executable:
   ```bash
   chmod +x scripts/your-script.sh
   ```

3. Add documentation to this README

4. Test before committing:
   ```bash
   ./scripts/your-script.sh
   ./scripts/pre-commit-check.sh
   ```

### Script Standards

All scripts should:
- ✅ Use `#!/bin/bash` shebang
- ✅ Include descriptive header comment
- ✅ Use `set -e` for error handling
- ✅ Provide colored output (GREEN, RED, YELLOW, BLUE)
- ✅ Include usage examples in comments
- ✅ Exit with proper exit codes (0=success, 1=failure)
- ✅ Be idempotent (safe to run multiple times)

---

## 🆘 Troubleshooting

### "Docker not found"
```bash
# Install Docker:
# - Ubuntu: sudo apt install docker.io
# - macOS: brew install docker
# - Arch: sudo pacman -S docker
```

### "Docker image not found"
```bash
make dev  # Builds tabssh-android image
```

### "ADB not found"
```bash
# Install Android SDK Platform Tools
# Ubuntu: sudo apt install android-tools-adb
# macOS: brew install android-platform-tools
# Manual: https://developer.android.com/tools/releases/platform-tools
```

### "No devices connected"
```bash
# Enable USB debugging:
# 1. Settings → About phone → Tap "Build number" 7 times
# 2. Settings → Developer options → USB debugging ON
# 3. Connect device, authorize computer
# 4. Verify: adb devices
```

### Build fails with permission errors
```bash
# Fix Docker permissions (Linux):
sudo usermod -aG docker $USER
newgrp docker
```

---

## 📚 Related Documentation

- **Makefile** - Build automation (see `make help`)
- **CLAUDE.md** - Complete project documentation
- **SPEC.md** - Technical specification
- **README.md** - Project overview
- **.github/workflows/** - CI/CD workflows

---

**Last Updated:** 2026-02-10  
**TabSSH Version:** 1.0.0

# Repository Cleanup Plan

## Current Issues

### Problems Identified:
1. **18 shell scripts** scattered in root directory
2. **10+ log files** cluttering root
3. **Multiple duplicate/obsolete scripts**
4. **5+ markdown docs** not organized
5. **Build artifacts** mixed with source
6. **No clear directory structure** for tooling

---

## Proposed Directory Structure

```
tabssh/android/
├── app/                          # Android app source (keep as-is)
├── docs/                         # 📚 All documentation
│   ├── SPEC.md
│   ├── README.md
│   ├── CHANGELOG.md
│   ├── TODO.md
│   ├── UI_UX_GUIDE.md
│   ├── LIBRARY_COMPARISON.md
│   ├── DELIVERY_*.md
│   └── FDROID_SYNC_VERIFIED.md
├── scripts/                      # 🔧 Build & utility scripts
│   ├── build/                    # Build scripts
│   │   ├── build-dev.sh
│   │   ├── build-with-docker.sh
│   │   ├── docker-build.sh
│   │   └── final-build.sh
│   ├── docker/                   # Docker-specific
│   │   ├── Dockerfile
│   │   ├── Dockerfile.dev
│   │   ├── docker-compose.yml
│   │   └── docker-compose.dev.yml
│   ├── fix/                      # Legacy fix scripts (for reference)
│   │   └── [old fix scripts]
│   ├── check/                    # Validation scripts
│   │   ├── check-errors.sh
│   │   ├── quick-check.sh
│   │   └── get-errors.sh
│   └── validate-implementation.sh
├── build-logs/                   # 📊 Build output (gitignored)
│   ├── compile.log
│   ├── build.log
│   └── [all .log files]
├── build/                        # Gradle build output
├── .github/                      # GitHub config
├── fdroid-submission/            # F-Droid metadata
├── metadata/                     # F-Droid metadata
├── LICENSE.md                    # Keep in root
├── README.md                     # Keep in root
└── SPEC.md                       # Keep in root (primary doc)
```

---

## Cleanup Actions

### 1. Create New Directories
```bash
mkdir -p docs
mkdir -p scripts/{build,docker,fix,check}
mkdir -p build-logs
```

### 2. Move Documentation
```bash
mv CHANGELOG.md docs/
mv TODO.md docs/
mv UI_UX_GUIDE.md docs/
mv LIBRARY_COMPARISON.md docs/
mv DELIVERY_*.md docs/
mv EMAIL_CORRECTED.md docs/
mv FDROID_SYNC_VERIFIED.md docs/
mv FINAL_DELIVERY_CONFIRMATION.md docs/
```

### 3. Move Build Scripts
```bash
# Build scripts
mv build-dev.sh scripts/build/
mv build-with-docker.sh scripts/build/
mv docker-build.sh scripts/build/
mv final-build.sh scripts/build/
mv dev-build.sh scripts/build/

# Docker files
mv Dockerfile scripts/docker/
mv Dockerfile.dev scripts/docker/
mv docker-compose*.yml scripts/docker/

# Check scripts
mv docker-check-errors.sh scripts/check/
mv quick-check.sh scripts/check/
mv get-errors.sh scripts/check/
mv get-unique-errors.sh scripts/check/

# Fix scripts (archive)
mv fix_*.sh scripts/fix/
mv comprehensive-fix.sh scripts/fix/
mv docker-fix-*.sh scripts/fix/
mv docker-quick-build.sh scripts/fix/
mv docker-final-build.sh scripts/fix/
mv docker-fast-compile.sh scripts/fix/
mv final_fix.sh scripts/fix/
```

### 4. Move Logs
```bash
mv *.log build-logs/
mv *.txt build-logs/ (except README.txt if any)
```

### 5. Clean Up Obsolete Files
```bash
# Remove duplicate/obsolete scripts
rm -f dev-shell.sh (if not needed)
```

### 6. Update .gitignore
```
# Build outputs
build-logs/
*.log
*.txt
build/
.gradle/
app/build/

# IDE
.idea/
*.iml
.vscode/
.claude/

# OS
.DS_Store
Thumbs.db
```

---

## After Cleanup Structure

**Root Directory (Clean):**
- LICENSE.md
- README.md
- SPEC.md
- build.gradle
- settings.gradle
- gradlew / gradlew.bat
- .gitignore

**Organized Subdirectories:**
- `app/` - Source code
- `docs/` - Documentation
- `scripts/` - Tooling
- `build-logs/` - Logs (gitignored)
- `.github/` - CI/CD
- `fdroid-submission/` - F-Droid

---

## Benefits

✅ **Cleaner root** - Only essential files visible
✅ **Organized tooling** - Easy to find scripts
✅ **Better navigation** - Clear directory purpose
✅ **Easier maintenance** - Know where everything goes
✅ **Professional appearance** - Ready for open source
✅ **Better .gitignore** - No build artifacts in repo

# 📱 TabSSH - Modern SSH Client for Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https.shields.io/badge/License-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Version-1.0.0-orange.svg" alt="Version">
</p>

A beautiful, modern, open-source SSH client for Android with true browser-style tabs.

## ✨ Features

- 📑 **Browser-Style Tabs** - Multiple SSH sessions in tabs (like a web browser)
- 🔐 **Enterprise Security** - Hardware-backed encryption, biometric auth
- 🎨 **Beautiful Themes** - 6+ built-in themes, custom theme support
- ♿ **Accessibility First** - Full TalkBack support, high contrast modes
- 📁 **SFTP File Browser** - Upload/download files with beautiful UI
- 🔑 **Advanced SSH** - Port forwarding, X11, Mosh support
- 🌐 **SSH Config Import** - Import from ~/.ssh/config
- 💾 **Backup & Restore** - Export/import all your data
- 🔔 **Background Sessions** - Keep connections alive
- 🎯 **Zero Trackers** - No analytics, no ads, privacy-focused

## 🚀 Quick Start

```bash
# Clone the repository
git clone https://github.com/tabssh/android.git
cd android

# Build with Docker (recommended)
./build.sh

# Or build with local Gradle
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📚 Documentation

- **[SPEC.md](SPEC.md)** - Complete technical specification
- **[docs/](docs/)** - All documentation
  - [TODO.md](docs/TODO.md) - Development progress
  - [CHANGELOG.md](docs/CHANGELOG.md) - Release history
  - [UI_UX_GUIDE.md](docs/UI_UX_GUIDE.md) - Design guidelines
  - [LIBRARY_COMPARISON.md](docs/LIBRARY_COMPARISON.md) - Technical decisions

## 🛠️ Development

### Prerequisites
- Docker (recommended) OR
- Android SDK 34
- JDK 17
- Gradle 8.1.1

### Build Commands
```bash
# Check for errors
docker run --rm -v $(pwd):/workspace -w /workspace \\
  -e ANDROID_HOME=/opt/android-sdk tabssh-android \\
  ./gradlew compileDebugKotlin

# Build APK
./build.sh

# Run tests
./gradlew test
```

### Repository Structure
```
├── app/               # Android app source
├── docs/              # Documentation
├── scripts/           # Build & utility scripts
├── build-logs/        # Build outputs (gitignored)
└── fdroid-submission/ # F-Droid metadata
```

## 🔐 Security Features

- ✅ SHA256 host key verification
- ✅ Hardware-backed key storage
- ✅ Biometric authentication
- ✅ No plaintext password storage
- ✅ Screenshot protection
- ✅ Auto-lock on background

## 🎨 Themes

- 🦇 Dracula
- ☀️ Solarized Light/Dark
- ❄️ Nord
- 🌲 Monokai
- 🎯 One Dark
- ♿ High Contrast

## 📜 License

MIT License - see [LICENSE.md](LICENSE.md)

## 🤝 Contributing

Contributions welcome! Please read [SPEC.md](SPEC.md) for guidelines.

## 📞 Support

- 🐛 [Issues](https://github.com/tabssh/android/issues)
- 💬 [Discussions](https://github.com/tabssh/android/discussions)
- 📧 Email: support@tabssh.dev

---

**Made with ❤️ for the open-source community**

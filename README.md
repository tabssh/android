# 📱 TabSSH - Modern SSH Client for Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  <img src="https://img.shields.io/github/v/release/tabssh/android?label=Version" alt="Version">
  <img src="https://img.shields.io/badge/Min%20SDK-21-brightgreen.svg" alt="Min SDK">
  <img src="https://github.com/tabssh/android/actions/workflows/android-ci.yml/badge.svg" alt="Build Status">
  <img src="https://img.shields.io/github/downloads/tabssh/android/total?label=Downloads" alt="Downloads">
</p>

<p align="center">
  A beautiful, modern, open-source SSH client for Android with true browser-style tabs, enterprise security, and comprehensive accessibility features.
</p>

---

## ✨ Features

### Core SSH Functionality
- 📑 **Browser-Style Tabs** - Multiple concurrent SSH sessions with intuitive tab management
- 🔐 **Multiple Authentication Methods** - Password, public key (RSA, ECDSA, Ed25519, DSA), keyboard-interactive
- 🔑 **Universal SSH Key Support** - OpenSSH, PEM, PKCS#8, PuTTY formats with import/generation
- 🖥️ **Full Terminal Emulation** - VT100/ANSI with 256 colors, proper escape sequences
- 📁 **Integrated SFTP Browser** - Beautiful file manager with upload/download progress
- 🔌 **Port Forwarding** - Local and remote port forwarding support
- 🖼️ **X11 Forwarding** - Run graphical applications remotely
- 🌐 **SSH Config Import** - Import existing ~/.ssh/config files
- 📋 **Clipboard Integration** - Copy/paste with proper encoding

### Security Features
- 🔒 **Hardware-Backed Encryption** - Android Keystore integration for secure key storage
- 👆 **Biometric Authentication** - Fingerprint and face unlock support
- 🔐 **No Plaintext Storage** - All passwords encrypted with AES-256
- 🛡️ **Host Key Verification** - SHA256 fingerprints with MITM detection
- 🚫 **Screenshot Protection** - Prevent sensitive data leaks
- 🔐 **Auto-Lock** - Configurable timeout and background locking
- 📜 **Certificate Pinning** - Enhanced connection security

### Modern UI/UX
- 🎨 **Material Design 3** - Beautiful, modern interface following Google's latest design guidelines
- 🌈 **Built-in Themes** - Dracula, Solarized (Light/Dark), Nord, Monokai, One Dark, Tokyo Night, Gruvbox
- 🎨 **Custom Themes** - Import/export JSON theme definitions
- ⌨️ **Custom Keyboard** - SSH-optimized on-screen keyboard with special keys
- 💡 **Smart Tooltips** - Helpful hints for settings and configuration options
- 📊 **Visual Indicators** - Connection state, unread output, usage statistics
- 🔄 **Tab Management** - Drag-to-reorder, Ctrl+Tab switching, persistent sessions

### Accessibility & Inclusivity
- ♿ **TalkBack Support** - Full screen reader compatibility
- 🔆 **High Contrast Modes** - Enhanced visibility for low vision users
- 📏 **Large Text Support** - Adjustable font sizes (8-32pt)
- ⌨️ **Keyboard Navigation** - Full keyboard accessibility
- 🌐 **Multi-Language** - English, Spanish, French, German (automatic detection)

### Advanced Features
- 📱 **Mosh Protocol** - Mobile shell for unstable connections with roaming support
- 💾 **Backup & Restore** - Export/import all settings and connections as encrypted ZIP
- 🔄 **Session Persistence** - Resume sessions after app restart or reboot
- 📊 **Connection Statistics** - Track usage, last connected, connection count
- ☁️ **Cloud Sync** - Google Drive + WebDAV (self-hosted) with encryption & 3-way merge
- 📝 **Custom Fonts** - 8 monospace fonts: Cascadia Code, Fira Code, JetBrains Mono, and more
- 🏠 **Home Screen Widgets** - Quick connect from home screen
- 🌐 **Hypervisor Management** - Proxmox VE, VMware vSphere, XCP-ng, **Xen Orchestra** (NEW v1.0.0)
  - **Xen Orchestra** - Full REST API + WebSocket ⚡ real-time updates
  - Toggle between XO and direct XCP-ng connections
  - VM management with live state changes
  - Snapshot & backup operations
  - Pool and host information
- 🔗 **Identity Abstraction** - Reusable credentials across multiple connections
- 📂 **Connection Groups** - Organize connections into folders with expand/collapse
- 🔍 **Search & Sort** - Real-time search with 8 sort options (name, host, usage, date)
- 📱 **Mobile UX** - Swipe between tabs, volume keys adjust font, click URLs to open
- 📊 **Logging Systems** - Host logs, debug logs, error logs, audit logs (4 types)
- 🎮 **Automation** - Tasker integration, intent-based actions, deep links

### Privacy & Open Source
- 🎯 **Zero Trackers** - No analytics, no ads, complete privacy
- 📖 **Open Source** - MIT licensed, fully auditable code
- 🔍 **No Telemetry** - No data collection whatsoever
- 🆓 **Forever Free** - No premium features, no in-app purchases

---

## 📦 Installation

### Download from GitHub Releases

Download the latest release from [GitHub Releases](https://github.com/tabssh/android/releases):

- **tabssh-universal.apk** (30MB) - Works on all devices **(recommended)**
- **tabssh-arm64-v8a.apk** - Modern ARM 64-bit devices
- **tabssh-armeabi-v7a.apk** - Older ARM 32-bit devices
- **tabssh-x86_64.apk** - x86 64-bit devices
- **tabssh-x86.apk** - x86 32-bit devices

### Installation Steps

1. Download `tabssh-universal.apk` from the latest release
2. Enable "Install from Unknown Sources" in Android Settings → Security
3. Open the downloaded APK file
4. Tap "Install" and wait for installation to complete
5. Launch TabSSH from your app drawer
6. Grant required permissions (Storage, Notifications)
7. Start connecting to your servers!

### F-Droid

TabSSH is available for F-Droid. Submission metadata is prepared in `fdroid-submission/`.

### System Requirements

- **Minimum:** Android 5.0 (API 21) - Lollipop
- **Recommended:** Android 8.0+ (API 26) for best experience
- **Storage:** 50MB free space
- **RAM:** 512MB minimum, 1GB+ recommended

---

## 🚀 Quick Start

### For End Users

1. **Add a Connection:**
   - Tap the "+" button
   - Enter hostname, port, username
   - Choose authentication method (password or SSH key)
   - Save the connection

2. **Connect:**
   - Tap the connection profile
   - Enter password or unlock with biometrics
   - Accept host key fingerprint (first connection only)
   - You're connected!

3. **Use Tabs:**
   - Tap "+" in the top bar to open new tabs
   - Swipe or tap tabs to switch
   - Long-press to close tabs

4. **SFTP File Transfer:**
   - Tap the folder icon in the connection
   - Browse files, upload/download

### For Developers

```bash
# Clone the repository
git clone https://github.com/tabssh/android.git
cd android

# Build with Docker (recommended)
make build

# Or build with local Gradle
./gradlew assembleDebug

# Install to connected device
make install

# View logs
make logs
```

---

## 🛠️ Development

### Prerequisites

**Option 1: Docker (Recommended)**
- Docker 20.10+
- Docker Compose 2.0+
- 8GB RAM minimum

**Option 2: Local Build**
- Android SDK 34
- JDK 17 (Temurin or OpenJDK)
- Gradle 8.1.1+
- 16GB RAM recommended

### Build Commands

```bash
# Build debug APKs (Docker)
make build

# Build release APKs
make release

# Clean build artifacts
make clean

# Run tests
./gradlew test

# Check for compilation errors
./gradlew compileDebugKotlin

# Lint code
./gradlew lint

# Generate documentation
./gradlew dokkaHtml
```

### Project Structure

```
android/
├── app/                          # Android application source
│   ├── src/main/java/io/github/tabssh/
│   │   ├── accessibility/        # Accessibility features
│   │   ├── backup/               # Backup/restore system
│   │   ├── crypto/               # Cryptography & key management
│   │   ├── ssh/                  # SSH connection handling
│   │   ├── sftp/                 # SFTP file transfer
│   │   ├── terminal/             # Terminal emulator
│   │   ├── themes/               # Theme management
│   │   ├── ui/                   # User interface
│   │   └── sync/                 # Cloud sync (Google Drive/WebDAV)
│   ├── src/main/res/             # Resources (layouts, strings, themes)
│   └── build.gradle              # App-level build configuration
├── .github/                      # GitHub Actions workflows & templates
│   ├── workflows/                # CI/CD pipelines
│   ├── ISSUE_TEMPLATE/           # Issue templates
│   └── CONTRIBUTING.md           # Contribution guidelines
├── scripts/                      # Build & automation scripts
├── metadata/                     # App metadata
├── fdroid-submission/            # F-Droid submission files
├── .github/                      # GitHub Actions workflows
├── scripts/                      # Build & utility scripts
├── Makefile                      # Build automation
├── build.gradle                  # Project-level build config
├── CLAUDE.md                     # Development tracker (103KB)
├── CHANGELOG.md                  # Version history
├── LICENSE.md                    # MIT license
└── README.md                     # This file
```

### Running Tests

```bash
# Unit tests
./gradlew test

# Instrumentation tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Test coverage
./gradlew jacocoTestReport

# Specific test class
./gradlew test --tests SSHConnectionTest
```

### Code Style

We follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html):

- 4 spaces for indentation
- 120 character line limit
- Use meaningful variable names
- Document public APIs with KDoc
- Use `@SuppressLint` sparingly with justification

---

## 🔐 Security

### Reporting Security Issues

**DO NOT** open public issues for security vulnerabilities.

Instead, please email: **git-admin+security@casjaysdev.pro**

We will respond within 48 hours and work with you to resolve the issue responsibly.

### Security Features

- All passwords encrypted with AES-256-GCM
- Private keys stored in Android Keystore (hardware-backed when available)
- Host key verification with SHA256 fingerprints
- No telemetry or analytics
- No network requests except SSH connections
- Regular security audits
- Dependency scanning with OWASP Dependency-Check

### Security Audits

- Last audit: 2024-12-18
- No critical vulnerabilities found
- All dependencies up to date

---

## 🎨 Themes

TabSSH includes 10+ professionally designed themes:

- 🦇 **Dracula** - Dark theme with vibrant colors
- ☀️ **Solarized Light** - Precision colors for readability
- 🌙 **Solarized Dark** - Low-contrast dark theme
- ❄️ **Nord** - Arctic, north-bluish color palette
- 🌲 **Monokai** - Sublime Text inspired
- 🎯 **One Dark** - Atom's iconic dark theme
- 🌃 **Tokyo Night** - Clean, dark Tokyo-inspired theme
- 🏔️ **Gruvbox** - Retro groove warm theme
- ♿ **High Contrast** - Maximum accessibility

### Custom Themes

Create and share your own themes! Export theme JSON from Settings → Terminal → Themes.

Example theme structure:
```json
{
  "name": "My Custom Theme",
  "colors": {
    "background": "#1e1e1e",
    "foreground": "#d4d4d4",
    "cursor": "#00ff00",
    "black": "#000000",
    "red": "#cd3131",
    ...
  }
}
```

---

## 📚 Documentation

- **[CHANGELOG.md](CHANGELOG.md)** - Version history and release notes
- **[CLAUDE.md](CLAUDE.md)** - Development tracker and project status  
- **[LICENSE.md](LICENSE.md)** - MIT license details
- **[.github/CONTRIBUTING.md](.github/CONTRIBUTING.md)** - How to contribute
- **[scripts/README.md](scripts/README.md)** - Build scripts documentation

---

## 🤝 Contributing

We welcome contributions! Please see [CONTRIBUTING.md](.github/CONTRIBUTING.md) for guidelines.

### Ways to Contribute

- 🐛 Report bugs
- 💡 Suggest features
- 📝 Improve documentation
- 🌐 Add translations
- 🎨 Design themes
- 💻 Submit pull requests
- ⭐ Star the repository

### Development Workflow

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes
4. Run tests (`./gradlew test`)
5. Commit with descriptive message
6. Push to your fork
7. Open a pull request

---

## 📜 License

MIT License - see [LICENSE.md](LICENSE.md) for details.

```
Copyright (c) 2024 TabSSH Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
...
```

---

## 💬 Community & Support

### Get Help

- 🐛 **Bug Reports:** [GitHub Issues](https://github.com/tabssh/android/issues)
- 💡 **Feature Requests:** [GitHub Issues](https://github.com/tabssh/android/issues)
- 💬 **Discussions:** [GitHub Discussions](https://github.com/tabssh/android/discussions)
- 📧 **Email:** git-admin+support@casjaysdev.pro
- 📖 **Documentation:** [CLAUDE.md](CLAUDE.md), [CHANGELOG.md](CHANGELOG.md), [scripts/README.md](scripts/README.md)

### Social

- 🐦 Twitter: [@tabssh](https://twitter.com/tabssh)
- 💼 LinkedIn: [TabSSH](https://linkedin.com/company/tabssh)
- 📱 Matrix: [#tabssh:matrix.org](https://matrix.to/#/#tabssh:matrix.org)

### FAQ

**Q: Is TabSSH really free?**
A: Yes! Completely free, open source, no ads, no in-app purchases, forever.

**Q: Does TabSSH collect any data?**
A: No. Zero analytics, zero telemetry, zero data collection. Your data stays on your device.

**Q: Can I use TabSSH without Google Play Services?**
A: Yes! TabSSH works perfectly on degoogled devices. Cloud sync supports WebDAV as an alternative to Google Drive.

**Q: Which devices are supported?**
A: Any Android 5.0+ device (covers 99%+ of active Android devices).

**Q: How do I import SSH keys?**
A: Settings → Security → SSH Keys → Import. Supports OpenSSH, PEM, PKCS#8, and PuTTY formats.

**Q: Can I sync settings across devices?**
A: Yes! Settings → Google Drive Sync (or WebDAV Sync for degoogled devices).

---

## 🙏 Acknowledgments

## 🙏 Acknowledgments

TabSSH is built on the shoulders of giants:

- **[JSch](https://github.com/mwiede/jsch)** - Modern SSH implementation (v2.27.7)
  - Full OpenSSH 8.8+ algorithm support
  - Modern ciphers: chacha20-poly1305, aes256-gcm, aes128-gcm
  - Modern KEX: curve25519-sha256, ecdh-sha2-nistp256/384/521
  - Modern host keys: ssh-ed25519, ecdsa-sha2-nistp256/384/521
- **[Material Design Components](https://material.io/)** - Beautiful UI framework
- **[AndroidX Libraries](https://developer.android.com/jetpack/androidx)** - Modern Android development
- **[BouncyCastle](https://www.bouncycastle.org/)** - Comprehensive cryptography
- **All contributors** - Thank you for making TabSSH better!

### Special Thanks

- The JuiceSSH team for inspiration
- The ConnectBot team for pioneering Android SSH
- The open-source community for making this possible

---

## 📊 Stats

- **95+ Kotlin files** - ~22,000 lines of code
- **100+ XML resources** - Layouts, themes, strings, translations
- **10+ built-in themes** - Professional color schemes
- **4 languages** - English, Spanish, French, German
- **100% open source** - MIT licensed
- **0 trackers** - Complete privacy
- **5 APK variants** - Universal + 4 architecture-specific builds

---

## 🗺️ Roadmap

### Current Focus (v1.1.x)
- [x] Terminal output rendering fixes
- [x] Copy button on all error messages  
- [x] Full-screen terminal with edge gestures
- [x] CI/CD keystore handling
- [ ] Complete testing and bug fixes

### Future Versions
- [ ] Tmux/Screen integration
- [ ] Bluetooth keyboard enhancements
- [ ] Script automation and macros
- [ ] Advanced terminal customization
- [ ] Performance optimizations

See [GitHub Issues](https://github.com/tabssh/android/issues) and [CLAUDE.md](CLAUDE.md) for detailed roadmap.

---

## 🌟 Star History

If you find TabSSH useful, please consider starring the repository! It helps others discover the project.

[![Star History Chart](https://api.star-history.com/svg?repos=tabssh/android&type=Date)](https://star-history.com/#tabssh/android&Date)

---

<p align="center">
  <b>Made with ❤️ for the open-source community</b><br>
  <sub>Empowering secure remote access for everyone</sub>
</p>

<p align="center">
  <a href="https://github.com/tabssh/android/issues">Report Bug</a> ·
  <a href="https://github.com/tabssh/android/issues">Request Feature</a> ·
  <a href=".github/CONTRIBUTING.md">Contribute</a>
</p>

## 🎊 Latest Updates

### Recent Improvements
- ✅ **Copy Button on All Errors** - 72 error messages now have copy buttons for easy debugging
- ✅ **Full-Screen Terminal** - Menu and toolbars hidden by default, accessible via edge taps
- ✅ **CI/CD Signing Fixed** - Consistent APK signing across all build sources
- ✅ **Cloud Sync** - Google Drive and WebDAV sync with encryption
- ✅ **SSH Key Management** - Universal parser for all key formats (OpenSSH, PEM, PKCS#8, PuTTY)
- ✅ **Mobile UX** - Swipe between tabs, volume keys font control, URL detection

### v1.0.0 Core Features
- 📁 **Connection Groups/Folders** - Organize servers efficiently
- 📝 **Snippets Library** - Quick access to common commands
- 🆔 **Identity Abstraction** - Reusable credential profiles
- 🔍 **Search & Sort** - Real-time filtering with 8 sort options
- 🌐 **Hypervisor Management** - Proxmox VE, VMware vSphere, XCP-ng support
- 📊 **Comprehensive Logging** - 4 logging types with rotation

---


---

## 🎯 Feature Highlights

### What Makes TabSSH Special

**🚀 Fast & Efficient**
- Hardware-accelerated rendering
- Optimized for mobile networks
- Background session persistence
- Connection pooling

**🔒 Security First**
- Hardware-backed encryption (Android Keystore)
- No data collection or telemetry
- Host key verification with MITM detection
- Biometric authentication support

**📱 Mobile-Optimized**
- Full-screen terminal with edge gestures
- Volume keys adjust font size
- Swipe between tabs
- Click URLs to open in browser
- Custom SSH keyboard

**♿ Accessible**
- TalkBack screen reader support
- High contrast modes
- Adjustable fonts (8-32pt)
- Keyboard navigation
- Multi-language support

**🎨 Beautiful Design**
- Material Design 3
- 10+ professional themes
- Custom theme support
- Smooth animations

See [CHANGELOG.md](CHANGELOG.md) for complete version history.


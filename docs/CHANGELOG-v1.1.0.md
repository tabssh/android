# TabSSH v1.1.0 Release Notes

**Release Date:** 2026-02-04
**Version Code:** 2

## �� What's New

### ✨ Cloud Sync Now Fully Functional
- **Google Drive Sync** - Complete with AES-256-GCM encryption
- **WebDAV Sync** - Perfect for degoogled devices (LineageOS, CalyxOS, GrapheneOS)
- **Force Upload/Download** - Manual sync controls
- **Clear Remote Data** - Privacy-focused data deletion
- All sync backend files verified and operational

### 🌍 Multi-Language Support
- **Spanish** (Español) - 156 strings fully translated
- **French** (Français) - 156 strings fully translated
- **German** (Deutsch) - 156 strings fully translated
- **English** - Complete (default)
- Automatic language detection based on device settings
- Proper technical terminology for SSH/Linux in all languages

## 🔧 Improvements
- Fixed sync dialog implementations (authentication, password, account management)
- Enhanced SyncStage enum with ERROR and APPLYING states
- Improved sync error handling and user feedback
- All sync features now have confirmation dialogs

## 📦 Build Information
- **APK Size:** 30MB per variant
- **Architectures:** arm64-v8a, armeabi-v7a, x86_64, x86, universal
- **Min SDK:** 21 (Android 5.0+)
- **Target SDK:** 34
- **Compilation:** 0 errors, 0 warnings (critical)

## 🐛 Bug Fixes
- Fixed missing sync backend methods
- Fixed SyncStage enum incomplete values
- Fixed sync UI dialogs showing "coming soon" toasts

## 📝 Documentation Updates
- Corrected multi-language support claims
- Updated feature list accuracy
- Added comprehensive sync documentation

## 🔐 Security
- AES-256-GCM encryption for sync data
- PBKDF2 key derivation (100k iterations)
- Hardware-backed encryption (Android Keystore)
- No plaintext storage of sensitive data

## 🌟 Full Feature List

### Core SSH Features
- Browser-style tabs for multiple sessions
- Full VT100/ANSI terminal emulation (256 colors)
- Multiple authentication methods (password, public key, keyboard-interactive)
- Universal SSH key support (OpenSSH, PEM, PKCS#8, PuTTY v2/v3)
- In-app SSH key generation (RSA, ECDSA, Ed25519)
- Host key verification with SHA-256 fingerprints

### Advanced Features
- Port forwarding (local, remote, dynamic)
- X11 forwarding (run graphical applications)
- Mosh protocol (mobile shell for unstable connections)
- SFTP file browser with dual-pane interface
- SSH config import (~/.ssh/config)
- Certificate pinning for enhanced security

### Data & Sync
- Google Drive sync with end-to-end encryption
- WebDAV sync for self-hosted/degoogled devices
- Automatic 3-way merge with conflict resolution
- Backup/restore functionality
- Import/export connections as encrypted ZIP

### UI/UX Enhancements
- Material Design 3 interface
- 10+ built-in themes (Dracula, Solarized, Nord, etc.)
- Connection groups and folders
- Snippets library for common commands
- Volume keys control font size
- Swipe between tabs
- Click URLs to open in browser
- Search and sort connections (8 sort options)
- 3-section layout (Frequent/Groups/Ungrouped)

### Accessibility & Polish
- Full TalkBack screen reader support
- High contrast modes
- Large text support
- Biometric authentication (fingerprint/face)
- Performance monitoring overlay
- SSH transcript recording
- Android home screen widget

## 📱 Supported Devices
- **Android 5.0+** (API 21+) - 99%+ device coverage
- **All architectures:** ARM 32/64-bit, x86 32/64-bit
- **Degoogled ROMs:** LineageOS, CalyxOS, GrapheneOS (WebDAV sync)
- **Standard Android:** Google Play Services (Google Drive sync)

## �� Download
- Universal APK: `tabssh-universal.apk` (all architectures)
- ARM 64-bit: `tabssh-arm64-v8a.apk` (recommended for modern phones)
- ARM 32-bit: `tabssh-armeabi-v7a.apk` (older devices)
- x86 64-bit: `tabssh-x86_64.apk` (emulators/tablets)
- x86 32-bit: `tabssh-x86.apk` (emulators)

## 📄 License
MIT License - Free and open source

## 🙏 Credits
Built with Claude Code (Sonnet 4.5)

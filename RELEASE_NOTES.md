# TabSSH v1.0.0 - Release Notes

## What's New in This Build (2025-10-18 23:45)

### ✨ Features Implemented

#### Connection Management
- ✅ Connection count tracking - Shows "Connected X times" like JuiceSSH
- ✅ Automatic tracking of connection history
- ✅ Group/folder organization for connections
- ✅ Connection list with detailed information
- ✅ Quick Connect feature

#### User Interface
- ✅ Settings menu in toolbar (3-dot menu)
- ✅ About dialog with version information
- ✅ SSH key management dialog with 4 options:
  - Import from file
  - Paste key content
  - Generate new keypair
  - Browse existing keys
- ✅ Connection count display in list items
- ✅ Group assignment for connections

#### Notifications
- ✅ POST_NOTIFICATIONS permission for Android 13+
- ✅ Foreground service notification
- ✅ Connection status in notification
- ✅ Quick actions in notification

#### Database
- ✅ Connection count tracking in database
- ✅ Last connected timestamp
- ✅ Frequently used connections query
- ✅ Group organization

### 🔄 In Progress / Partial Implementation

- ⚠️ SSH key import (PEM parsing not yet implemented)
- ⚠️ Settings screen (fragments exist but empty)
- ⚠️ Frequently used section (query exists, UI not yet added)

### 🐛 Known Issues

1. **App crashes** - Awaiting device logs to debug
2. **Notifications may not show** - Runtime permission may need to be requested
3. **SSH key import** - Shows "coming soon" for actual import

### 📦 Installation

```bash
adb install -r ./binaries/tabssh-universal.apk
```

### 🧪 Testing Required

Please test the following:
1. Install APK and check for crashes
2. Add a new connection
3. Connect to a server (will increment connection count)
4. Check if notifications appear
5. Navigate to Settings menu
6. Try SSH key management options

### 📝 Next Steps

#### Critical
- [ ] Debug and fix all crashes
- [ ] Implement SSH key PEM parsing
- [ ] Complete Settings screen implementation
- [ ] Add runtime notification permission request

#### High Priority
- [ ] Add frequently used section to main screen
- [ ] Implement full settings with all JuiceSSH features
- [ ] Terminal customization (fonts, themes)
- [ ] Backup/restore functionality

#### Testing
- [ ] End-to-end SSH connection testing
- [ ] SFTP functionality
- [ ] Port forwarding
- [ ] Multi-tab sessions
- [ ] All authentication methods

### 🎯 Goal

Create a complete, production-ready JuiceSSH replacement with additional features. No placeholders, everything working.

---

**Build Information**
- Version: 1.0.0
- Build Time: 2025-10-18 23:43
- Build Type: Debug
- APK Size: 23MB
- Architectures: arm64-v8a, armeabi-v7a, x86_64, x86, universal


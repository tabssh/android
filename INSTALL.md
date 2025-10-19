# 📱 TabSSH Android - Installation Guide

## Quick Install (Recommended)

### Using ADB

```bash
# Connect your Android device via USB
# Enable USB debugging in Developer Options

# Install the universal APK (works on all devices)
adb install binaries/debug/app-universal-debug.apk

# Launch the app
adb shell am start -n com.tabssh/.ui.activities.MainActivity
```

### Manual Installation

1. Copy `binaries/debug/app-universal-debug.apk` to your Android device
2. On your device, go to **Settings → Security**
3. Enable **Install from Unknown Sources** or **Install Unknown Apps**
4. Open the APK file using a file manager
5. Tap **Install**

---

## Installation Options

### For Most Users
**Use:** `app-universal-debug.apk` (23MB)
- Works on all Android devices
- Single APK for easy installation

### For Modern Phones/Tablets
**Use:** `app-arm64-v8a-debug.apk` (23MB)
- Optimized for ARM 64-bit processors
- Most Android devices from 2017+

### For Older Devices
**Use:** `app-armeabi-v7a-debug.apk` (23MB)
- Works on older ARM 32-bit devices
- Android devices before 2017

### For Emulators
**Use:** `app-x86_64-debug.apk` (23MB) or `app-x86-debug.apk` (23MB)
- Android Studio emulator
- Genymotion, BlueStacks, etc.

---

## System Requirements

- **Minimum:** Android 8.0 (API 26) or higher
- **Recommended:** Android 10+ for best experience
- **Storage:** 50MB free space
- **RAM:** 512MB available memory
- **Network:** WiFi or mobile data for SSH connections

---

## First Time Setup

1. **Launch the App**
   - Tap the TabSSH icon

2. **Grant Permissions** (if prompted)
   - Storage access (for key import/export)
   - Network access (automatic)

3. **Create Your First Connection**
   - Tap the **+** button
   - Enter server details:
     - Host: `your-server.com`
     - Port: `22`
     - Username: `your-username`
   - Choose authentication:
     - Password
     - SSH Key
     - Keyboard-interactive

4. **Connect!**
   - Tap **Connect**
   - If it's a new host, accept the host key
   - Enter password/passphrase if required

---

## Testing the Installation

### View Logs
```bash
# Monitor TabSSH logs
adb logcat | grep TabSSH
```

### Test Connection
```bash
# After installing, try connecting to:
# - Your own SSH server
# - Or use a test server: test.rebex.net
#   Username: demo
#   Password: password
```

---

## Troubleshooting

### Installation Failed
- **Solution:** Enable "Install from Unknown Sources"
- **Location:** Settings → Security → Unknown Sources

### App Won't Launch
- **Solution:** Check minimum Android version (8.0+)
- **Check:** Settings → About Phone → Android Version

### Connection Issues
- **Check network:** Ensure device has internet access
- **Check firewall:** Server port 22 should be open
- **Check credentials:** Verify username/password

### Permission Denied
- **Solution:** Grant all requested permissions
- **Location:** Settings → Apps → TabSSH → Permissions

---

## Uninstallation

### Using ADB
```bash
adb uninstall com.tabssh
```

### Manual
1. Go to **Settings → Apps**
2. Find **TabSSH**
3. Tap **Uninstall**

---

## Advanced Options

### Developer Settings

Enable developer mode in the app:
1. Tap **Settings → About**
2. Tap version number 7 times
3. Developer options will appear

### Custom Themes

Import custom themes:
1. Create a `.json` theme file
2. Place in `/sdcard/TabSSH/themes/`
3. Restart app
4. Select from theme list

### Backup Connections

Export your connection profiles:
1. **Settings → Backup**
2. Tap **Export Connections**
3. Save to safe location
4. Import on new device

---

## Support

- **Documentation:** See `docs/` directory
- **Issues:** Check GitHub issues
- **Logs:** `adb logcat | grep TabSSH`

---

**Ready to SSH! Enjoy TabSSH!** 🚀

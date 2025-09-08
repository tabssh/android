# TabSSH Android App - Complete Technical Specification

**Version**: 1.0  
**Date**: January 2025  
**Repository**: https://github.com/TabSSH/android  
**Website**: https://tabssh.github.io  

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [User Interface](#3-user-interface)
4. [Core Features](#4-core-features)
5. [Security & Privacy](#5-security--privacy)
6. [Themes & Accessibility](#6-themes--accessibility)
7. [Preferences System](#7-preferences-system)
8. [Technical Implementation](#8-technical-implementation)
9. [Build & Deployment](#9-build--deployment)
10. [Testing Strategy](#10-testing-strategy)
11. [Documentation](#11-documentation)
12. [Project Management](#12-project-management)

---

## 1. Project Overview

### 1.1 Mission Statement
TabSSH is a modern, open-source SSH client for Android that provides a true tabbed interface for multiple SSH sessions while maintaining enterprise-grade security and accessibility standards.

### 1.2 Core Principles
- **Open Source**: MIT licensed, community-driven development
- **Security First**: Hardware-backed encryption, strict security policies
- **Privacy Focused**: No analytics, no tracking, local data only
- **Accessibility**: Full support for screen readers and accessibility features
- **Modern UX**: Clean, intuitive interface following Material Design

### 1.3 Target Users
- System administrators managing multiple servers
- Developers working with remote systems
- DevOps engineers requiring mobile SSH access
- Privacy-conscious users seeking open-source alternatives
- Users with accessibility needs

### 1.4 Key Differentiators
- True browser-style tabbed interface
- Tmux-like keyboard shortcuts
- All features included (no premium version)
- Complete open source transparency
- Enterprise-grade security on mobile

---

## 2. Architecture

### 2.1 Project Structure
```
app/src/main/java/com/tabssh/
├── ui/                     # User interface components
│   ├── activities/         # Main activities
│   ├── fragments/          # Fragments and dialogs
│   ├── adapters/          # RecyclerView adapters
│   ├── views/             # Custom views
│   └── utils/             # UI utilities
├── ssh/                   # SSH connectivity and protocols
│   ├── connection/        # Connection management
│   ├── auth/              # Authentication handlers
│   ├── config/            # SSH config parsing
│   └── protocols/         # SSH, SFTP, tunneling
├── terminal/              # Terminal emulation
│   ├── emulator/          # VT100/ANSI emulation
│   ├── renderer/          # Text rendering
│   └── input/             # Input handling
├── crypto/                # Cryptography and key management
│   ├── keys/              # SSH key handling
│   ├── storage/           # Secure storage
│   └── algorithms/        # Crypto utilities
├── storage/               # Data persistence
│   ├── database/          # SQLite database
│   ├── preferences/       # SharedPreferences wrapper
│   └── files/             # File management
├── themes/                # Theme system
│   ├── definitions/       # Theme definitions
│   ├── parser/            # Theme parsing
│   └── validator/         # Accessibility validation
├── accessibility/         # Accessibility features
│   ├── talkback/          # Screen reader support
│   ├── contrast/          # High contrast mode
│   └── navigation/        # Keyboard navigation
├── network/               # Network utilities
│   ├── proxy/             # Proxy support
│   ├── detection/         # Network state
│   └── security/          # Certificate handling
├── backup/                # Backup and restore
│   ├── export/            # Data export
│   ├── import/            # Data import
│   └── validation/        # Backup validation
└── utils/                 # Common utilities
    ├── logging/           # Logging system
    ├── performance/       # Performance monitoring
    └── helpers/           # Helper classes
```

### 2.2 Core Architecture Patterns
- **MVP (Model-View-Presenter)**: Clean separation of concerns
- **Repository Pattern**: Data access abstraction
- **Observer Pattern**: Event-driven communication
- **Factory Pattern**: Object creation for SSH components
- **Strategy Pattern**: Pluggable algorithms for crypto/auth

### 2.3 Threading Model
```java
// Main components and their threading
- UI Thread: View updates, user interactions
- SSH Thread Pool: Connection management (4 threads)
- Terminal Thread: Terminal I/O and rendering
- Crypto Thread: Key operations and encryption
- File Transfer Thread: SFTP operations
- Background Thread: Cleanup and maintenance
```

---

## 3. User Interface

### 3.1 Activity Structure

#### 3.1.1 MainActivity (Connection List)
```
┌─────────────────────────────┐
│ ☰ TabSSH    [Search] [+] [⋮]│ ← Toolbar with menu, search, add, overflow
├─────────────────────────────┤
│ Quick Connect               │ ← Quick connect section
│ [Host] [Port] [Username]    │
│            [Connect Button] │
├─────────────────────────────┤
│ Connections    [Grid/List]  │ ← Connection management
│ 📂 Production (2 active)    │   Show active connection count
│   🖥️ web-01 ●             │   ● = active session indicator
│   🖥️ db-01               │
│ 📂 Development             │
│   🖥️ dev-box ●           │
│ 📁 Personal               │
│   🖥️ home-server         │
└─────────────────────────────┘
```

**Features**:
- Quick connect bar for rapid connections
- Organized connection groups with expand/collapse
- Visual indicators for active sessions
- Search functionality for large connection lists
- Swipe actions (edit, delete, duplicate)
- Long-press context menus

#### 3.1.2 TabTerminalActivity (Core Innovation)
```
┌─────────────────────────────┐
│ [web-01][db-01][dev][+] [≡] │ ← Scrollable tab bar
├─────────────────────────────┤
│ user@web-01:~$ ls           │
│ Documents  Downloads        │ ← Terminal content
│ Pictures   Videos           │   (current active tab)
│ user@web-01:~$ █           │
├─────────────────────────────┤
│ [Ctrl][Alt][Esc][↑][↓][Tab]│ ← Function key row
│ [Kbd] [Files] [Paste] [⋮]   │ ← Action bar
└─────────────────────────────┘
```

**Tab Management**:
- Browser-style tabs with close buttons
- Drag to reorder tabs
- Tab overflow menu for many connections
- Visual indicators (connecting, connected, error)
- Tmux-style keyboard shortcuts

**Keyboard Shortcuts**:
```
Ctrl+T          - New tab
Ctrl+W          - Close tab
Ctrl+Tab        - Next tab
Ctrl+Shift+Tab  - Previous tab
Ctrl+1-9        - Switch to tab number
Ctrl+Shift+T    - Reopen closed tab
```

#### 3.1.3 ConnectionEditActivity
```
┌─────────────────────────────┐
│ ← Connection Settings       │
├─────────────────────────────┤
│ General                     │
│ Name: [Production Web]      │
│ Host: [192.168.1.100]      │
│ Port: [22]                 │
│ Username: [admin]          │
├─────────────────────────────┤
│ Authentication              │
│ ○ Password                 │
│ ● Public Key               │
│ ○ Keyboard Interactive     │
│ Key: [Select Key ▼]        │
├─────────────────────────────┤
│ Advanced                    │
│ Terminal: [xterm-256color]  │
│ Encoding: [UTF-8]          │
│ ☑ Compression              │
│ ☑ Keep Alive              │
└─────────────────────────────┘
```

### 3.2 UI Design System

#### 3.2.1 Typography
```java
public class UIDefaults {
    // Typography scale
    public static final float TEXT_SIZE_CAPTION = 12sp;    // Secondary text
    public static final float TEXT_SIZE_BODY = 14sp;       // Body text
    public static final float TEXT_SIZE_SUBTITLE = 16sp;   // Subtitles
    public static final float TEXT_SIZE_TITLE = 18sp;      // Titles
    public static final float TEXT_SIZE_HEADLINE = 20sp;   // Headlines
    public static final float TEXT_SIZE_DISPLAY = 24sp;    // Display text
    
    // Terminal typography
    public static final float TERMINAL_TEXT_SIZE_DEFAULT = 14sp;
    public static final float TERMINAL_TEXT_SIZE_MIN = 8sp;
    public static final float TERMINAL_TEXT_SIZE_MAX = 32sp;
    public static final String TERMINAL_FONT_DEFAULT = "Roboto Mono";
    public static final String[] TERMINAL_FONT_OPTIONS = {
        "Roboto Mono", "Source Code Pro", "Fira Code", 
        "JetBrains Mono", "Cascadia Code", "System Monospace"
    };
}
```

#### 3.2.2 Spacing & Layout
```java
// Spacing system (8dp grid)
public static final int SPACE_XS = 4dp;     // Micro spacing
public static final int SPACE_SM = 8dp;     // Small spacing
public static final int SPACE_MD = 16dp;    // Medium spacing (baseline)
public static final int SPACE_LG = 24dp;    // Large spacing
public static final int SPACE_XL = 32dp;    // Extra large spacing
public static final int SPACE_XXL = 48dp;   // Jumbo spacing

// Component dimensions
public static final int TAB_HEIGHT = 48dp;
public static final int TAB_MIN_WIDTH = 72dp;
public static final int TAB_MAX_WIDTH = 168dp;
public static final int TOOLBAR_HEIGHT = 56dp;
public static final int BOTTOM_BAR_HEIGHT = 56dp;
public static final int MIN_TOUCH_TARGET = 48dp;
```

#### 3.2.3 Color System
```java
// Material Design 3 color tokens
public class ColorSystem {
    // Primary colors (brand identity)
    public static final int PRIMARY_50 = 0xFFF3F4F6;
    public static final int PRIMARY_500 = 0xFF1976D2;   // Main brand color
    public static final int PRIMARY_900 = 0xFF0D47A1;
    
    // Semantic colors
    public static final int SUCCESS = 0xFF4CAF50;        // Green
    public static final int WARNING = 0xFFFF9800;        // Orange  
    public static final int ERROR = 0xFFF44336;          // Red
    public static final int INFO = 0xFF2196F3;           // Blue
    
    // Connection status colors
    public static final int CONNECTED = SUCCESS;
    public static final int CONNECTING = WARNING;
    public static final int DISCONNECTED = 0xFF757575;   // Gray
    public static final int ERROR_STATE = ERROR;
}
```

---

## 4. Core Features

### 4.1 SSH Connection Management

#### 4.1.1 Connection Profiles
```java
public class ConnectionProfile {
    // Basic connection info
    private String id;              // Unique identifier
    private String name;            // User-friendly name
    private String host;            // Hostname or IP
    private int port;               // SSH port (default: 22)
    private String username;        // Username
    
    // Authentication
    private AuthType authType;      // PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE
    private String keyId;           // Reference to stored key
    private boolean savePassword;   // Whether to save password
    
    // Connection settings
    private String terminalType;    // Terminal type (xterm-256color)
    private String encoding;        // Character encoding (UTF-8)
    private boolean compression;    // Enable compression
    private boolean keepAlive;      // Send keep-alive packets
    
    // Advanced settings
    private int connectTimeout;     // Connection timeout (seconds)
    private int readTimeout;        // Read timeout (seconds)
    private String proxyHost;       // Proxy configuration
    private int proxyPort;
    private ProxyType proxyType;    // HTTP, SOCKS, NONE
    
    // UI preferences
    private String theme;           // Terminal theme
    private String groupId;         // Connection group
    private int sortOrder;          // Display order
    private long lastConnected;     // Last connection timestamp
    private int connectionCount;    // Usage statistics
}
```

#### 4.1.2 SSH Configuration Import
```java
public class SSHConfigParser {
    /**
     * Parse ~/.ssh/config file format
     * Supports all standard SSH client options
     */
    public List<ConnectionProfile> parseConfig(String configContent) {
        // Parse SSH config syntax:
        // Host myserver
        //     HostName example.com
        //     User myuser
        //     Port 2222
        //     IdentityFile ~/.ssh/id_rsa
        //     ProxyJump bastion.example.com
        //     LocalForward 8080 localhost:80
    }
    
    // Supported SSH config directives
    private static final Set<String> SUPPORTED_DIRECTIVES = Set.of(
        "Host", "HostName", "User", "Port", "IdentityFile",
        "ProxyJump", "ProxyCommand", "LocalForward", "RemoteForward",
        "DynamicForward", "Compression", "ServerAliveInterval",
        "ConnectTimeout", "PasswordAuthentication", "PubkeyAuthentication"
    );
}
```

### 4.2 Terminal Emulation

#### 4.2.1 VT100/ANSI Support
```java
public class TerminalEmulator {
    // Terminal capabilities
    private static final String[] SUPPORTED_SEQUENCES = {
        // Cursor movement
        "CSI n A",      // Cursor up
        "CSI n B",      // Cursor down  
        "CSI n C",      // Cursor forward
        "CSI n D",      // Cursor back
        "CSI n ; m H",  // Cursor position
        
        // Text formatting
        "CSI 0 m",      // Reset
        "CSI 1 m",      // Bold
        "CSI 4 m",      // Underline
        "CSI 7 m",      // Reverse
        "CSI 30-37 m",  // Foreground colors
        "CSI 40-47 m",  // Background colors
        "CSI 90-97 m",  // Bright foreground
        "CSI 100-107 m", // Bright background
        
        // Screen operations
        "CSI 2 J",      // Clear screen
        "CSI K",        // Clear line
        "CSI n L",      // Insert lines
        "CSI n M",      // Delete lines
        
        // Advanced features
        "OSC 0 ; text ST", // Set window title
        "CSI ? 25 h/l",    // Show/hide cursor
        "CSI ? 1049 h/l",  // Alternate screen
    };
    
    // Terminal size
    public static final int DEFAULT_ROWS = 24;
    public static final int DEFAULT_COLS = 80;
    public static final int MAX_SCROLLBACK = 10000;
}
```

#### 4.2.2 Text Rendering Engine
```java
public class TerminalRenderer {
    // Rendering optimizations
    private Canvas canvas;
    private Paint textPaint;
    private Paint backgroundPaint;
    private Typeface terminalFont;
    
    // Character grid
    private TerminalChar[][] charGrid;
    private boolean[][] dirtyFlags;    // Track what needs redrawing
    
    // Rendering features
    private boolean antiAlias = true;
    private boolean ligatureSupport = false;  // Programming ligatures
    private boolean emojiSupport = true;
    private float lineSpacing = 1.2f;
    
    public void renderFrame() {
        // Efficient incremental rendering
        // Only redraw changed cells
        // Support for double-width characters (CJK)
        // Hardware acceleration when available
    }
}
```

### 4.3 Tabbed Interface

#### 4.3.1 Tab Management
```java
public class TabManager {
    private final List<SSHTab> tabs = new ArrayList<>();
    private int activeTabIndex = 0;
    private final int maxTabs;
    
    public class SSHTab {
        private String tabId;
        private String title;
        private ConnectionProfile connection;
        private SSHSession session;
        private TerminalEmulator terminal;
        private TabState state;  // CONNECTING, CONNECTED, DISCONNECTED, ERROR
        
        // Tab persistence
        private Bundle savedState;
        private long lastActivity;
        
        // Visual indicators
        private boolean hasUnreadOutput;
        private boolean hasError;
        private int unreadLines;
    }
    
    // Tab operations
    public SSHTab createTab(ConnectionProfile profile);
    public void closeTab(int index);
    public void switchToTab(int index);
    public void moveTab(int fromIndex, int toIndex);
    public SSHTab duplicateTab(int index);
    
    // Keyboard shortcuts
    public void handleKeyboardShortcut(int keyCode, KeyEvent event);
}
```

#### 4.3.2 Tab Persistence
```java
public class TabPersistence {
    /**
     * Save and restore tab state across app restarts
     */
    public void saveTabState(List<SSHTab> tabs) {
        // Save to SQLite database:
        // - Connection profiles
        // - Terminal scrollback (configurable amount)
        // - Cursor position
        // - Current working directory
        // - Environment variables
    }
    
    public List<SSHTab> restoreTabState() {
        // Restore previous session
        // Attempt to reconnect
        // Show restoration progress
    }
}
```

### 4.4 SFTP File Management

#### 4.4.1 File Browser UI
```
┌─────────────────────────────┐
│ ← /home/user/documents      │ ← Path breadcrumb
├─────────────────────────────┤
│ Local          │ Remote     │ ← Dual-pane view
├────────────────┼────────────┤
│ 📁 Downloads   │ 📁 backup  │
│ 📁 Pictures    │ 📁 logs    │ 
│ 📄 notes.txt   │ 📄 app.py  │
│ 📄 config.json │ 📄 data.db │
├────────────────┼────────────┤
│ [Upload] [New] │ [Download] │ ← Action buttons
└─────────────────────────────┘
```

#### 4.4.2 File Transfer Engine
```java
public class SFTPManager {
    // Transfer operations
    public TransferTask uploadFile(File localFile, String remotePath);
    public TransferTask downloadFile(String remotePath, File localFile);
    public TransferTask uploadDirectory(File localDir, String remoteDir);
    public TransferTask downloadDirectory(String remoteDir, File localDir);
    
    // Transfer features
    private boolean resumeSupport = true;
    private boolean preservePermissions = true;
    private boolean preserveTimestamps = true;
    private int bufferSize = 32768;  // 32KB
    private int maxConcurrentTransfers = 3;
    
    // Progress tracking
    public interface TransferListener {
        void onProgress(long bytesTransferred, long totalBytes);
        void onComplete(TransferResult result);
        void onError(TransferException error);
        void onPaused();
        void onResumed();
    }
}
```

### 4.5 Port Forwarding

#### 4.5.1 Tunnel Types
```java
public enum TunnelType {
    LOCAL_FORWARD,    // -L localPort:remoteHost:remotePort
    REMOTE_FORWARD,   // -R remotePort:localHost:localPort  
    DYNAMIC_FORWARD   // -D localPort (SOCKS proxy)
}

public class PortForwardingManager {
    private final Map<String, Tunnel> activeTunnels = new HashMap<>();
    
    public class Tunnel {
        private String id;
        private TunnelType type;
        private String localHost;
        private int localPort;
        private String remoteHost;
        private int remotePort;
        private boolean autoStart;
        private TunnelState state;
        
        // Statistics
        private long bytesTransferred;
        private int activeConnections;
        private long lastActivity;
    }
    
    // Tunnel management
    public Tunnel createTunnel(TunnelType type, String config);
    public void startTunnel(String tunnelId);
    public void stopTunnel(String tunnelId);
    public List<Tunnel> getActiveTunnels();
}
```

---

## 5. Security & Privacy

### 5.1 Password Management

#### 5.1.1 Secure Storage Architecture
```java
public class SecurePasswordManager {
    // Android Keystore integration
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALIAS_PREFIX = "tabssh_password_";
    
    /**
     * Storage levels (user configurable)
     */
    public enum StorageLevel {
        NEVER(0, "Never store passwords"),
        SESSION_ONLY(1, "Store for session only"),  
        ENCRYPTED(2, "Store encrypted"),
        BIOMETRIC(3, "Store with biometric protection");
    }
    
    // Core operations
    public void storePassword(String connectionId, String password, StorageLevel level);
    public String retrievePassword(String connectionId);
    public void deletePassword(String connectionId);
    public void deleteAllPasswords();
    
    // Biometric integration
    public void storePasswordWithBiometrics(String connectionId, String password, 
                                           BiometricPrompt.AuthenticationCallback callback);
    public void retrievePasswordWithBiometrics(String connectionId,
                                             BiometricPrompt.AuthenticationCallback callback);
}
```

#### 5.1.2 Security Policies
```java
public class SecurityPolicy {
    // Password policy settings
    public static class PasswordPolicy {
        public StorageLevel defaultStorageLevel = StorageLevel.ENCRYPTED;
        public boolean requireBiometricForSensitive = true;
        public long passwordTTL = TimeUnit.HOURS.toMillis(24);
        public boolean autoDeleteOnFailure = true;
        public int maxFailedAttempts = 3;
        public boolean requireScreenLock = true;
        
        // Host-based policies
        public StorageLevel getPolicyForHost(String hostname) {
            if (isSensitiveHost(hostname)) {
                return StorageLevel.BIOMETRIC;
            }
            return defaultStorageLevel;
        }
        
        private boolean isSensitiveHost(String host) {
            String[] sensitivePatterns = {
                "prod", "production", "live", "master",
                "bank", "financial", "payment", "secure"
            };
            return Arrays.stream(sensitivePatterns)
                    .anyMatch(pattern -> host.toLowerCase().contains(pattern));
        }
    }
}
```

### 5.2 SSH Key Management

#### 5.2.1 Key Import System
```java
public class PrivateKeyImporter {
    // Supported key formats
    public enum KeyFormat {
        OPENSSH_PRIVATE("-----BEGIN OPENSSH PRIVATE KEY-----"),
        RSA_PRIVATE("-----BEGIN RSA PRIVATE KEY-----"),
        DSA_PRIVATE("-----BEGIN DSA PRIVATE KEY-----"),
        EC_PRIVATE("-----BEGIN EC PRIVATE KEY-----"),
        PKCS8("-----BEGIN PRIVATE KEY-----"),
        PKCS8_ENCRYPTED("-----BEGIN ENCRYPTED PRIVATE KEY-----"),
        PUTTY_PRIVATE("PuTTY-User-Key-File-");
        
        // Auto-detection
        public static KeyFormat detectFormat(String keyContent);
    }
    
    // Import sources
    public ImportResult importFromFile(Uri fileUri);
    public ImportResult importFromClipboard();
    public ImportResult importFromText(String keyText);
    public ImportResult importFromSSHDirectory(File sshDir);
    public ImportResult importFromQRCode(String qrData);
    
    // Key generation
    public GenerateResult generateNewKey(KeyType type, int keySize, String comment);
}
```

#### 5.2.2 Key Storage
```java
public class KeyStorage {
    /**
     * Secure key storage using Android Keystore
     * Keys are encrypted and stored with metadata
     */
    public class StoredKey {
        private String keyId;
        private String name;
        private KeyType keyType;        // RSA, DSA, ECDSA, Ed25519
        private String comment;
        private String fingerprint;
        private long createdAt;
        private long lastUsed;
        private boolean requiresPassphrase;
    }
    
    public String storePrivateKey(ParsedKey key, String keyName);
    public PrivateKey retrievePrivateKey(String keyId);
    public List<StoredKey> listStoredKeys();
    public void deleteKey(String keyId);
    public void exportKey(String keyId, File outputFile);
}
```

### 5.3 Host Key Verification

#### 5.3.1 Known Hosts Management
```java
public class HostKeyManager {
    private final File knownHostsFile;
    
    public enum VerificationResult {
        ACCEPTED,           // Key matches known host
        NEW_HOST,          // First time connecting
        CHANGED_KEY,       // Host key has changed (security risk)
        INVALID_KEY        // Malformed key
    }
    
    public VerificationResult verifyHostKey(String hostname, int port, 
                                          PublicKey hostKey);
    public void addHostKey(String hostname, int port, PublicKey hostKey);
    public void removeHostKey(String hostname, int port);
    public List<HostKeyEntry> getAllHostKeys();
    
    // Security alerts
    public void showHostKeyChangedAlert(String hostname, PublicKey oldKey, 
                                      PublicKey newKey, 
                                      HostKeyVerificationCallback callback);
}
```

### 5.4 Privacy Features

#### 5.4.1 Data Protection
```java
public class PrivacyManager {
    // Screenshot protection
    public void enableScreenshotProtection(Activity activity) {
        activity.getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);
    }
    
    // Clipboard security
    public void setClipboardTimeout(int timeoutSeconds) {
        // Auto-clear clipboard after timeout
    }
    
    // Memory protection
    public void clearSensitiveData() {
        // Attempt to clear sensitive strings from memory
        // Zero out password arrays
        // Clear terminal scrollback if configured
    }
    
    // App backgrounding
    public void onAppBackgrounded() {
        if (preferences.isLockOnBackground()) {
            lockApp();
        }
        if (preferences.isClearClipboardOnBackground()) {
            clearClipboard();
        }
    }
}
```

---

## 6. Themes & Accessibility

### 6.1 Theme System

#### 6.1.1 Built-in Themes
```java
public class ThemeDefinitions {
    // Light themes
    public static final Theme LIGHT_DEFAULT = new Theme(/* ... */);
    public static final Theme SOLARIZED_LIGHT = new Theme(/* ... */);
    public static final Theme GITHUB_LIGHT = new Theme(/* ... */);
    public static final Theme ATOM_ONE_LIGHT = new Theme(/* ... */);
    public static final Theme TOMORROW_LIGHT = new Theme(/* ... */);
    public static final Theme GRUVBOX_LIGHT = new Theme(/* ... */);
    
    // Dark themes  
    public static final Theme DRACULA = new Theme(/* ... */);
    public static final Theme MONOKAI = new Theme(/* ... */);
    public static final Theme ONE_DARK = new Theme(/* ... */);
    public static final Theme NORD = new Theme(/* ... */);
    public static final Theme SOLARIZED_DARK = new Theme(/* ... */);
    public static final Theme GRUVBOX_DARK = new Theme(/* ... */);
}
```

#### 6.1.2 Theme Structure
```java
public class Theme {
    // Theme metadata
    public String name;
    public String author;
    public boolean isDark;
    public String version;
    
    // Terminal colors (16-color ANSI palette)
    public int background;
    public int foreground;
    public int[] ansiColors = new int[16]; // 0-7 normal, 8-15 bright
    
    // UI colors (Material Design 3)
    public int primaryColor;
    public int primaryVariant;
    public int secondaryColor;
    public int surfaceColor;
    public int backgroundColor;
    public int errorColor;
    
    // Text colors
    public int onPrimary;
    public int onSecondary;
    public int onSurface;
    public int onBackground;
    public int onError;
    
    // Terminal-specific
    public int selectionColor;
    public int cursorColor;
    public int urlColor;
    public int searchHighlight;
    
    // Status and navigation
    public int statusBarColor;
    public int navigationBarColor;
    public int tabBackgroundColor;
    public int tabTextColor;
    public int tabSelectedColor;
    public int dividerColor;
}
```

#### 6.1.3 Theme Validation
```java
public class ThemeValidator {
    // WCAG 2.1 compliance
    private static final double MIN_CONTRAST_AA = 4.5;
    private static final double MIN_CONTRAST_AAA = 7.0;
    
    public ValidationResult validateTheme(Theme theme) {
        ValidationResult result = new ValidationResult();
        
        // Check contrast ratios
        result.addIssue(validateContrast(theme.foreground, theme.background, "Terminal text"));
        result.addIssue(validateContrast(theme.onPrimary, theme.primaryColor, "Primary button text"));
        
        // Validate ANSI colors
        for (int i = 0; i < 16; i++) {
            result.addIssue(validateContrast(theme.ansiColors[i], theme.background, 
                                           "ANSI color " + i));
        }
        
        // Color blindness simulation
        result.addColorBlindnessReport(simulateColorBlindness(theme));
        
        return result;
    }
    
    public Theme autoFixContrast(Theme theme) {
        // Automatically adjust colors to meet AA standards
        // Preserve hue while adjusting lightness
        // Maintain visual hierarchy
    }
}
```

### 6.2 Accessibility Features

#### 6.2.1 Screen Reader Support
```java
public class AccessibilityManager {
    public void setupTerminalAccessibility(TerminalView terminalView) {
        terminalView.setContentDescription("SSH Terminal");
        terminalView.setAccessibilityDelegate(new TerminalAccessibilityDelegate());
        
        // Terminal content description
        terminalView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        
        // Custom accessibility actions
        terminalView.addAccessibilityAction(
            new AccessibilityNodeInfo.AccessibilityAction(
                R.id.action_read_screen, "Read entire screen"));
        terminalView.addAccessibilityAction(
            new AccessibilityNodeInfo.AccessibilityAction(
                R.id.action_read_line, "Read current line"));
    }
    
    private class TerminalAccessibilityDelegate extends AccessibilityDelegate {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, 
                                                     AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            
            // Provide meaningful descriptions of terminal content
            info.setClassName(TerminalView.class.getName());
            info.setContentDescription(getTerminalContentDescription());
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_UP);
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_DOWN);
        }
        
        private String getTerminalContentDescription() {
            // Convert terminal content to spoken text
            // Handle screen reader navigation
            // Announce connection status changes
        }
    }
}
```

#### 6.2.2 High Contrast Mode
```java
public class HighContrastTheme extends Theme {
    public HighContrastTheme(Theme baseTheme) {
        super(baseTheme);
        
        // Enforce high contrast ratios
        this.background = isDark ? 0xFF000000 : 0xFFFFFFFF;
        this.foreground = isDark ? 0xFFFFFFFF : 0xFF000000;
        
        // Ensure all colors meet AAA standards (7:1 ratio)
        for (int i = 0; i < ansiColors.length; i++) {
            ansiColors[i] = adjustForHighContrast(ansiColors[i], background);
        }
        
        // Enhance visual separators
        this.dividerColor = isDark ? 0xFF666666 : 0xFF999999;
        this.selectionColor = isDark ? 0xFF0066FF : 0xFF3399FF;
    }
}
```

#### 6.2.3 Motor Accessibility
```java
public class MotorAccessibilitySupport {
    public void configureForMotorImpairments() {
        // Larger touch targets
        setMinimumTouchTargetSize(56); // dp, larger than standard 48dp
        
        // Longer press timeouts
        setLongPressTimeout(1000); // ms, longer than standard 500ms
        
        // Reduced motion sensitivity
        setScrollSensitivity(0.5f);
        
        // Alternative input methods
        enableSwitchAccess();
        enableVoiceInput();
    }
    
    public void setupCustomKeyboard() {
        // Large, high-contrast keys
        // Sticky modifier keys (Ctrl, Alt stay pressed)
        // Configurable layouts
        // Hardware keyboard support
    }
}
```

---

## 7. Preferences System

### 7.1 Preference Categories

#### 7.1.1 General Preferences
```java
public class GeneralPreferences extends BasePreferences {
    // App behavior defaults
    public static final boolean DEFAULT_AUTO_BACKUP = true;
    public static final String DEFAULT_BACKUP_FREQUENCY = "weekly";
    public static final boolean DEFAULT_CRASH_REPORTING = true;
    public static final boolean DEFAULT_ANALYTICS = false; // Privacy-first
    public static final String DEFAULT_STARTUP_BEHAVIOR = "last_session";
    public static final String DEFAULT_LANGUAGE = "system";
    
    // User preferences
    public boolean isAutoBackupEnabled();
    public String getBackupFrequency();
    public String getStartupBehavior();
    public String getLanguage();
    
    // Setters with validation
    public void setAutoBackup(boolean enabled);
    public void setBackupFrequency(String frequency);
    public void setLanguage(String language);
}
```

#### 7.1.2 Security Preferences
```java
public class SecurityPreferences extends BasePreferences {
    // Password storage defaults
    public static final String DEFAULT_PASSWORD_STORAGE_LEVEL = "encrypted";
    public static final boolean DEFAULT_REQUIRE_BIOMETRIC_FOR_SENSITIVE = true;
    public static final int DEFAULT_PASSWORD_TTL_HOURS = 24;
    public static final boolean DEFAULT_AUTO_LOCK_ON_BACKGROUND = false;
    public static final int DEFAULT_AUTO_LOCK_TIMEOUT_MINUTES = 15;
    
    // Host key verification defaults
    public static final boolean DEFAULT_STRICT_HOST_KEY_CHECKING = true;
    public static final boolean DEFAULT_AUTO_ADD_HOST_KEYS = false;
    public static final boolean DEFAULT_WARN_ON_HOST_KEY_CHANGE = true;
    
    // Privacy defaults
    public static final int DEFAULT_CLEAR_CLIPBOARD_TIMEOUT = 60; // seconds
    public static final boolean DEFAULT_PREVENT_SCREENSHOTS = false;
    public static final boolean DEFAULT_HIDE_PASSWORDS_IN_LOGS = true;
    
    // Getters and setters
    public String getPasswordStorageLevel();
    public boolean isStrictHostKeyChecking();
    public int getClearClipboardTimeout();
    public boolean isPreventScreenshots();
}
```

#### 7.1.3 Terminal Preferences
```java
public class TerminalPreferences extends BasePreferences {
    // Appearance defaults
    public static final String DEFAULT_THEME = "dracula";
    public static final String DEFAULT_FONT_FAMILY = "Roboto Mono";
    public static final float DEFAULT_FONT_SIZE = 14.0f;
    public static final float DEFAULT_LINE_SPACING = 1.2f;
    public static final String DEFAULT_CURSOR_STYLE = "block";
    public static final boolean DEFAULT_CURSOR_BLINK = true;
    
    // Behavior defaults
    public static final int DEFAULT_SCROLLBACK_LINES = 1000;
    public static final boolean DEFAULT_WORD_WRAP = false;
    public static final boolean DEFAULT_COPY_ON_SELECT = false;
    public static final boolean DEFAULT_PASTE_ON_MIDDLE_CLICK = true;
    public static final boolean DEFAULT_AUTO_SCROLL = true;
    
    // Bell defaults
    public static final boolean DEFAULT_BELL_NOTIFICATION = true;
    public static final boolean DEFAULT_BELL_VIBRATE = false;
    public static final boolean DEFAULT_BELL_VISUAL = true;
    
    // Input handling defaults
    public static final boolean DEFAULT_ALT_SENDS_ESCAPE = true;
    public static final boolean DEFAULT_BACKSPACE_SENDS_DEL = false;
    
    // Terminal size defaults
    public static final int DEFAULT_TERMINAL_ROWS = 24;
    public static final int DEFAULT_TERMINAL_COLS = 80;
    public static final boolean DEFAULT_AUTO_RESIZE = true;
    
    // Getters and setters
    public String getTheme();
    public float getFontSize();
    public int getScrollbackLines();
    public String getCursorStyle();
    public boolean isCursorBlinkEnabled();
}
```

#### 7.1.4 UI Preferences
```java
public class UIPreferences extends BasePreferences {
    // Tab management defaults
    public static final int DEFAULT_MAX_TABS = 10;
    public static final boolean DEFAULT_CONFIRM_TAB_CLOSE = true;
    public static final boolean DEFAULT_REMEMBER_TAB_ORDER = true;
    public static final String DEFAULT_TAB_TITLE_FORMAT = "hostname";
    public static final boolean DEFAULT_SHOW_TAB_CLOSE_BUTTONS = true;
    
    // Keyboard defaults
    public static final boolean DEFAULT_SHOW_SOFT_KEYBOARD = true;
    public static final int DEFAULT_KEYBOARD_HEIGHT = 200; // dp
    public static final boolean DEFAULT_SHOW_FUNCTION_KEYS = true;
    public static final boolean DEFAULT_VOLUME_KEYS_AS_CTRL = false;
    
    // Interface defaults
    public static final boolean DEFAULT_FULLSCREEN_MODE = false;
    public static final String DEFAULT_ORIENTATION_LOCK = "auto";
    public static final boolean DEFAULT_KEEP_SCREEN_ON = false;
    
    // Gestures defaults
    public static final String DEFAULT_DOUBLE_TAP_ACTION = "zoom";
    public static final String DEFAULT_LONG_PRESS_ACTION = "select_text";
    public static final String DEFAULT_SWIPE_UP_ACTION = "show_keyboard";
    public static final String DEFAULT_SWIPE_DOWN_ACTION = "hide_keyboard";
    
    // Theme defaults
    public static final String DEFAULT_APP_THEME = "system"; // light, dark, system
    public static final boolean DEFAULT_DYNAMIC_COLORS = true;
    
    // Getters and setters
    public int getMaxTabs();
    public String getAppTheme();
    public String getTabTitleFormat();
    public boolean isFullscreenMode();
    public String getOrientationLock();
}
```

#### 7.1.5 Connection Preferences
```java
public class ConnectionPreferences extends BasePreferences {
    // Default connection settings
    public static final String DEFAULT_USERNAME = System.getProperty("user.name", "");
    public static final int DEFAULT_PORT = 22;
    public static final String DEFAULT_TERMINAL_TYPE = "xterm-256color";
    
    // Timeout defaults
    public static final int DEFAULT_CONNECT_TIMEOUT = 15; // seconds
    public static final int DEFAULT_AUTH_TIMEOUT = 10;
    public static final int DEFAULT_READ_TIMEOUT = 30;
    public static final int DEFAULT_KEEP_ALIVE_INTERVAL = 60;
    
    // Connection behavior defaults
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final int DEFAULT_RECONNECT_ATTEMPTS = 3;
    public static final int DEFAULT_RECONNECT_DELAY = 5; // seconds
    public static final boolean DEFAULT_COMPRESSION = true;
    
    // Authentication defaults
    public static final boolean DEFAULT_TRY_AGENT_AUTH = true;
    public static final boolean DEFAULT_TRY_KEYBOARD_INTERACTIVE = true;
    public static final String DEFAULT_PREFERRED_AUTH_METHODS = "publickey,keyboard-interactive,password";
    
    // Protocol defaults
    public static final String DEFAULT_SSH_VERSION = "2";
    public static final String DEFAULT_PREFERRED_CIPHERS = "aes256-gcm@openssh.com,aes128-gcm@openssh.com,aes256-ctr";
    
    // Getters and setters
    public int getConnectTimeout();
    public boolean isAutoReconnectEnabled();
    public String getPreferredAuthMethods();
    public String getPreferredCiphers();
}
```

### 7.2 Preference UI

#### 7.2.1 Settings Activity Structure
```xml
<!-- res/xml/preferences_main.xml -->
<androidx.preference.PreferenceScreen 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    
    <PreferenceCategory
        android:title="@string/pref_category_general"
        android:key="general">
        
        <ListPreference
            android:key="general_startup_behavior"
            android:title="@string/pref_startup_behavior"
            android:entries="@array/startup_behavior_entries"
            android:entryValues="@array/startup_behavior_values"
            android:defaultValue="last_session" />
            
        <SwitchPreferenceCompat
            android:key="general_auto_backup"
            android:title="@string/pref_auto_backup"
            android:summary="@string/pref_auto_backup_summary"
            android:defaultValue="true" />
    </PreferenceCategory>
    
    <PreferenceCategory
        android:title="@string/pref_category_security"
        android:key="security">
        
        <ListPreference
            android:key="security_password_storage_level"
            android:title="@string/pref_password_storage"
            android:entries="@array/password_storage_entries"
            android:entryValues="@array/password_storage_values"
            android:defaultValue="encrypted" />
    </PreferenceCategory>
    
    <!-- More categories... -->
</androidx.preference.PreferenceScreen>
```

---

## 8. Technical Implementation

### 8.1 Dependencies

#### 8.1.1 Core Libraries
```gradle
// build.gradle (app)
dependencies {
    // Android Support
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.fragment:fragment:1.6.2'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'
    implementation 'androidx.viewpager2:viewpager2:1.0.0'
    implementation 'androidx.preference:preference:1.2.1'
    
    // Material Design
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    
    // SSH Implementation
    implementation 'com.jcraft:jsch:0.1.55'
    
    // Security
    implementation 'androidx.biometric:biometric:1.1.0'
    implementation 'androidx.security:security-crypto:1.1.0-alpha06'
    
    // Database
    implementation 'androidx.room:room-runtime:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'
    
    // File operations
    implementation 'androidx.documentfile:documentfile:1.0.1'
    
    // Testing
    testImplementation 'junit:junit:4.13.2'
    androidTestImplementation 'androidx.test.ext:junit:1.1.5'
    androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
    androidTestImplementation 'androidx.test:runner:1.5.2'
    androidTestImplementation 'androidx.test:rules:1.5.0'
}
```

#### 8.1.2 Build Configuration
```gradle
// build.gradle (app)
android {
    compileSdk 34
    
    defaultConfig {
        applicationId "com.tabssh"
        minSdk 21  // Android 5.0 (covers 99%+ of devices)
        targetSdk 34
        versionCode 1
        versionName "1.0.0"
        
        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        
        // Enable vector drawables
        vectorDrawables.useSupportLibrary = true
        
        // Proguard configuration
        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
    }
    
    buildTypes {
        debug {
            applicationIdSuffix ".debug"
            debuggable true
            minifyEnabled false
        }
        
        release {
            minifyEnabled true
            shrinkResources true
            debuggable false
            
            // Signing config for F-Droid reproducible builds
            signingConfig signingConfigs.release
        }
    }
    
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
    
    buildFeatures {
        viewBinding true
        buildConfig true
    }
    
    // Lint configuration
    lintOptions {
        abortOnError false
        checkReleaseBuilds false
        disable 'InvalidPackage'
    }
}
```

### 8.2 Database Schema

#### 8.2.1 Room Database
```java
@Database(
    entities = {
        ConnectionProfile.class,
        StoredKey.class,
        HostKeyEntry.class,
        TabSession.class,
        ThemeDefinition.class
    },
    version = 1,
    exportSchema = false
)
@TypeConverters({Converters.class})
public abstract class TabSSHDatabase extends RoomDatabase {
    
    public abstract ConnectionDao connectionDao();
    public abstract KeyDao keyDao();
    public abstract HostKeyDao hostKeyDao();
    public abstract TabSessionDao tabSessionDao();
    public abstract ThemeDao themeDao();
    
    private static volatile TabSSHDatabase INSTANCE;
    
    public static TabSSHDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (TabSSHDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                        context.getApplicationContext(),
                        TabSSHDatabase.class,
                        "tabssh_database"
                    ).build();
                }
            }
        }
        return INSTANCE;
    }
}
```

#### 8.2.2 Entity Definitions
```java
@Entity(tableName = "connections")
public class ConnectionProfile {
    @PrimaryKey
    @NonNull
    public String id;
    
    @ColumnInfo(name = "name")
    public String name;
    
    @ColumnInfo(name = "host")
    public String host;
    
    @ColumnInfo(name = "port")
    public int port;
    
    @ColumnInfo(name = "username")
    public String username;
    
    @ColumnInfo(name = "auth_type")
    public String authType;
    
    @ColumnInfo(name = "key_id")
    public String keyId;
    
    @ColumnInfo(name = "group_id")
    public String groupId;
    
    @ColumnInfo(name = "theme")
    public String theme;
    
    @ColumnInfo(name = "created_at")
    public long createdAt;
    
    @ColumnInfo(name = "last_connected")
    public long lastConnected;
    
    @ColumnInfo(name = "connection_count")
    public int connectionCount;
    
    // Advanced settings stored as JSON
    @ColumnInfo(name = "advanced_settings")
    public String advancedSettings;
}

@Entity(tableName = "stored_keys")
public class StoredKey {
    @PrimaryKey
    @NonNull
    public String keyId;
    
    @ColumnInfo(name = "name")
    public String name;
    
    @ColumnInfo(name = "key_type")
    public String keyType;
    
    @ColumnInfo(name = "comment")
    public String comment;
    
    @ColumnInfo(name = "fingerprint")
    public String fingerprint;
    
    @ColumnInfo(name = "created_at")
    public long createdAt;
    
    @ColumnInfo(name = "last_used")
    public long lastUsed;
    
    @ColumnInfo(name = "requires_passphrase")
    public boolean requiresPassphrase;
    
    // Encrypted key data stored separately in secure storage
}
```

### 8.3 Performance Optimizations

#### 8.3.1 Memory Management
```java
public class MemoryManager {
    // Terminal buffer management
    private static final int MAX_SCROLLBACK_LINES = 10000;
    private static final int TRIM_THRESHOLD = 12000;
    
    public void manageTerminalMemory(TerminalEmulator terminal) {
        // Trim scrollback when it exceeds threshold
        if (terminal.getScrollbackLineCount() > TRIM_THRESHOLD) {
            terminal.trimScrollback(MAX_SCROLLBACK_LINES);
        }
        
        // Compress inactive tab buffers
        for (SSHTab tab : tabManager.getInactiveTabs()) {
            if (tab.getLastActivity() < System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)) {
                tab.compressBuffer();
            }
        }
    }
    
    // Bitmap management for terminal rendering
    private final LruCache<String, Bitmap> bitmapCache = new LruCache<String, Bitmap>(
        (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };
}
```

#### 8.3.2 Network Optimizations
```java
public class NetworkOptimizations {
    // Connection pooling
    private final ExecutorService sshExecutor = Executors.newFixedThreadPool(4);
    private final Map<String, Session> sessionPool = new ConcurrentHashMap<>();
    
    // Compression settings
    public void optimizeSSHConnection(Session session) {
        // Enable compression for slow networks
        session.setConfig("compression.s2c", "zlib,none");
        session.setConfig("compression.c2s", "zlib,none");
        
        // Optimize TCP settings
        session.setConfig("tcp_nodelay", "yes");
        
        // Tune buffer sizes based on network conditions
        int bufferSize = NetworkDetector.isHighSpeed() ? 65536 : 32768;
        session.setConfig("window_size", String.valueOf(bufferSize));
    }
    
    // Background connection management
    public void optimizeForMobile() {
        // Pause connections on metered networks if configured
        // Reduce keep-alive frequency on battery saver mode
        // Handle network switching gracefully
    }
}
```

---

## 9. Build & Deployment

### 9.1 CI/CD Pipeline

#### 9.1.1 GitHub Actions Workflow
```yaml
# .github/workflows/android-ci.yml
name: Android CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v4
    
    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
        
    - name: Cache Gradle packages
      uses: actions/cache@v3
      with:
        path: |
          ~/.gradle/caches
          ~/.gradle/wrapper
        key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
        restore-keys: |
          ${{ runner.os }}-gradle-
          
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
      
    - name: Run lint
      run: ./gradlew lintDebug
      
    - name: Run unit tests
      run: ./gradlew testDebugUnitTest
      
    - name: Run security scan
      run: ./gradlew dependencyCheckAnalyze
      
    - name: Build debug APK
      run: ./gradlew assembleDebug
      
    - name: Upload build artifacts
      uses: actions/upload-artifact@v3
      with:
        name: debug-apk
        path: app/build/outputs/apk/debug/
        
  ui-test:
    runs-on: macos-latest
    
    steps:
    - uses: actions/checkout@v4
    
    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
        
    - name: Run UI tests
      uses: reactivecircus/android-emulator-runner@v2
      with:
        api-level: 29
        script: ./gradlew connectedDebugAndroidTest
```

#### 9.1.2 Release Workflow
```yaml
# .github/workflows/release.yml
name: Release

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v4
    
    - name: Set up JDK 11
      uses: actions/setup-java@v3
      with:
        java-version: '11'
        distribution: 'temurin'
        
    - name: Decode keystore
      run: |
        echo ${{ secrets.KEYSTORE_BASE64 }} | base64 -d > release.keystore
        
    - name: Build release APK
      run: ./gradlew assembleRelease
      env:
        KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
        KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
        KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        
    - name: Create GitHub Release
      uses: softprops/action-gh-release@v1
      with:
        files: |
          app/build/outputs/apk/release/*.apk
          app/build/outputs/mapping/release/mapping.txt
        generate_release_notes: true
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        
    - name: Submit to F-Droid
      run: |
        # F-Droid metadata update workflow
        # Automated via F-Droid submission process
```

### 9.2 F-Droid Integration

#### 9.2.1 F-Droid Metadata
```yaml
# metadata/com.tabssh.yml
Categories:
  - System
  - Internet

License: MIT

AuthorName: TabSSH Team
AuthorEmail: hello@tabssh.org
AuthorWebSite: https://tabssh.github.io

SourceCode: https://github.com/TabSSH/android
IssueTracker: https://github.com/TabSSH/android/issues
Changelog: https://github.com/TabSSH/android/blob/main/CHANGELOG.md

Summary: Modern SSH client with tabbed interface

Description: |
    TabSSH is a modern, open-source SSH client for Android that provides a true 
    tabbed interface for multiple SSH sessions while maintaining enterprise-grade 
    security and accessibility standards.
    
    Features:
    * True tabbed interface for multiple SSH sessions
    * Secure by default with hardware-backed encryption
    * Beautiful themes including Dracula, Solarized, Nord
    * Integrated SFTP file browser and transfer
    * SSH key management and config import
    * Port forwarding support
    * Full accessibility support
    * Privacy-focused (no analytics or tracking)

RequiresRoot: false

Builds:
  - versionName: '1.0.0'
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes
```

### 9.3 Security Scanning

#### 9.3.1 Dependency Scanning
```gradle
// build.gradle (project)
plugins {
    id 'org.owasp.dependencycheck' version '8.4.0'
}

dependencyCheck {
    format = 'ALL'
    suppressionFile = 'config/dependency-check-suppressions.xml'
    failBuildOnCVSS = 7.0
    
    analyzers {
        experimentalEnabled = true
        archiveEnabled = true
        assemblyEnabled = false
    }
}
```

#### 9.3.2 Static Analysis
```gradle
// build.gradle (app)
android {
    lintOptions {
        abortOnError true
        checkReleaseBuilds true
        
        // Security-focused lint checks
        check 'SecureRandom'
        check 'TrustAllX509TrustManager'
        check 'BadHostnameVerifier'
        check 'SSLCertificateSocketFactoryCreateSocket'
        
        // Accessibility checks
        check 'ContentDescription'
        check 'ClickableViewAccessibility'
        check 'TouchTargetSize'
    }
}
```

---

## 10. Testing Strategy

### 10.1 Unit Testing

#### 10.1.1 Core Component Tests
```java
// SSH Connection Tests
@RunWith(MockitoJUnitRunner.class)
public class SSHConnectionTest {
    @Mock private Session mockSession;
    @Mock private ChannelShell mockChannel;
    
    @Test
    public void testConnectionEstablishment() {
        // Test successful connection
        // Test authentication failures
        // Test timeout scenarios
        // Test network errors
    }
    
    @Test
    public void testCommandExecution() {
        // Test command input/output
        // Test special characters
        // Test large output handling
    }
}

// Terminal Emulator Tests
public class TerminalEmulatorTest {
    private TerminalEmulator emulator;
    
    @Before
    public void setUp() {
        emulator = new TerminalEmulator(80, 24);
    }
    
    @Test
    public void testANSISequenceProcessing() {
        // Test cursor movement
        // Test color changes
        // Test text formatting
        // Test screen clearing
    }
    
    @Test
    public void testCharacterInput() {
        // Test ASCII characters
        // Test Unicode/UTF-8
        // Test control characters
        // Test special keys
    }
}

// Theme Validation Tests
public class ThemeValidatorTest {
    @Test
    public void testContrastValidation() {
        Theme theme = new Theme();
        theme.background = 0xFF000000;
        theme.foreground = 0xFF666666; // Low contrast
        
        ValidationResult result = ThemeValidator.validateTheme(theme);
        assertFalse(result.isValid());
        assertTrue(result.hasContrastIssues());
    }
    
    @Test
    public void testColorBlindnessSimulation() {
        // Test deuteranopia simulation
        // Test protanopia simulation  
        // Test tritanopia simulation
    }
}
```

### 10.2 Integration Testing

#### 10.2.1 SSH Integration Tests
```java
@RunWith(AndroidJUnit4.class)
public class SSHIntegrationTest {
    private TestSSHServer testServer;
    
    @Before
    public void setUp() {
        // Start test SSH server
        testServer = new TestSSHServer();
        testServer.start();
    }
    
    @Test
    public void testFullSSHWorkflow() {
        // Create connection profile
        ConnectionProfile profile = new ConnectionProfile();
        profile.host = "localhost";
        profile.port = testServer.getPort();
        profile.username = "testuser";
        
        // Establish connection
        SSHSession session = new SSHSession(profile);
        assertTrue(session.connect());
        
        // Execute commands
        String output = session.executeCommand("echo 'Hello World'");
        assertEquals("Hello World\n", output);
        
        // Test file transfer
        session.uploadFile(testFile, "/tmp/test.txt");
        assertTrue(session.fileExists("/tmp/test.txt"));
        
        // Cleanup
        session.disconnect();
    }
}
```

### 10.3 UI Testing

#### 10.3.1 Espresso Tests
```java
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MainActivityTest {
    @Rule
    public ActivityTestRule<MainActivity> activityRule = 
        new ActivityTestRule<>(MainActivity.class);
    
    @Test
    public void testConnectionCreation() {
        // Click add connection button
        onView(withId(R.id.fab_add_connection))
            .perform(click());
            
        // Fill connection details
        onView(withId(R.id.edit_connection_name))
            .perform(typeText("Test Server"));
        onView(withId(R.id.edit_connection_host))
            .perform(typeText("example.com"));
            
        // Save connection
        onView(withId(R.id.action_save))
            .perform(click());
            
        // Verify connection appears in list
        onView(withText("Test Server"))
            .check(matches(isDisplayed()));
    }
    
    @Test
    public void testTabManagement() {
        // Open connection (requires mock SSH server)
        // Verify tab creation
        // Test tab switching
        // Test tab closing
        // Verify tab persistence
    }
}
```

### 10.4 Accessibility Testing

#### 10.4.1 TalkBack Testing
```java

```java
@RunWith(AndroidJUnit4.class)
public class AccessibilityTest {
    @Test
    public void testScreenReaderSupport() {
        // Enable TalkBack simulation
        AccessibilityTestUtil.enableTalkBack();
        
        // Navigate through UI with TalkBack
        onView(withId(R.id.connection_list))
            .check(matches(hasContentDescription()));
            
        // Test terminal accessibility
        onView(withId(R.id.terminal_view))
            .check(matches(isAccessibilityFocusable()));
            
        // Verify accessibility actions
        onView(withId(R.id.terminal_view))
            .perform(AccessibilityActions.ACCESSIBILITY_ACTION_SCROLL_UP);
    }
    
    @Test
    public void testHighContrastMode() {
        // Enable high contrast
        PreferencesHelper.setHighContrastMode(true);
        
        // Verify contrast ratios
        onView(withId(R.id.terminal_view))
            .check(matches(hasMinimumContrastRatio(7.0)));
    }
    
    @Test
    public void testLargeTouchTargets() {
        // Verify all interactive elements meet minimum size
        onView(withId(R.id.tab_close_button))
            .check(matches(hasMinimumTouchTargetSize(48))); // dp
    }
}
```

### 10.5 Security Testing

#### 10.5.1 Security Test Suite
```java
public class SecurityTest {
    @Test
    public void testPasswordEncryption() {
        SecurePasswordManager manager = new SecurePasswordManager(context);
        String password = "secretpassword123";
        String connectionId = "test-connection";
        
        // Store password
        manager.storePassword(connectionId, password);
        
        // Verify encrypted storage
        assertNotEquals(password, getStoredRawValue(connectionId));
        
        // Verify retrieval
        assertEquals(password, manager.retrievePassword(connectionId));
    }
    
    @Test
    public void testKeyStorageSecurity() {
        KeyStorage keyStorage = new KeyStorage(context);
        PrivateKey testKey = generateTestKey();
        
        String keyId = keyStorage.storePrivateKey(testKey, "test-key");
        
        // Verify key is encrypted in storage
        assertFalse(isKeyStoredInPlaintext(keyId));
        
        // Verify key retrieval
        PrivateKey retrievedKey = keyStorage.retrievePrivateKey(keyId);
        assertEquals(testKey, retrievedKey);
    }
    
    @Test
    public void testHostKeyValidation() {
        HostKeyManager hostKeyManager = new HostKeyManager(context);
        PublicKey hostKey = generateTestHostKey();
        
        // First connection - should be new host
        VerificationResult result = hostKeyManager.verifyHostKey(
            "test.example.com", 22, hostKey);
        assertEquals(VerificationResult.NEW_HOST, result);
        
        // Add host key
        hostKeyManager.addHostKey("test.example.com", 22, hostKey);
        
        // Second connection - should be accepted
        result = hostKeyManager.verifyHostKey("test.example.com", 22, hostKey);
        assertEquals(VerificationResult.ACCEPTED, result);
        
        // Different key - should be changed
        PublicKey differentKey = generateTestHostKey();
        result = hostKeyManager.verifyHostKey("test.example.com", 22, differentKey);
        assertEquals(VerificationResult.CHANGED_KEY, result);
    }
}
```

---

## 11. Documentation

### 11.1 User Documentation

#### 11.1.1 Getting Started Guide
```markdown
# Getting Started with TabSSH

## Installation

### F-Droid (Recommended)
1. Open F-Droid app
2. Search for "TabSSH"
3. Install the app

### Direct Download
1. Visit [tabssh.github.io/download](https://tabssh.github.io/download)
2. Download the latest APK
3. Enable "Install from unknown sources" in Android settings
4. Install the APK

## First Connection

### Quick Connect
1. Open TabSSH
2. Enter your server details in the Quick Connect section:
   - **Host**: Your server's IP address or hostname
   - **Port**: SSH port (usually 22)
   - **Username**: Your username on the server
3. Tap "Connect"

### Saving Connections
1. Tap the "+" button in the top-right corner
2. Fill in connection details:
   - **Name**: A friendly name for this connection
   - **Host**: Server address
   - **Port**: SSH port
   - **Username**: Your username
3. Choose authentication method:
   - **Password**: Enter your password
   - **Public Key**: Select or import an SSH key
4. Tap "Save"

## Managing Tabs

TabSSH's main feature is its tabbed interface. Here's how to use it:

### Opening New Tabs
- Tap a saved connection to open it in a new tab
- Use Ctrl+T to open a new tab (hardware keyboard)
- Tap the "+" button in the tab bar

### Switching Between Tabs
- Tap on any tab to switch to it
- Use Ctrl+Tab to cycle through tabs
- Use Ctrl+1-9 to switch to specific tab numbers

### Closing Tabs
- Tap the "×" button on a tab
- Use Ctrl+W to close the current tab
- Long-press a tab for more options

## SSH Key Management

### Importing Existing Keys
1. Go to Settings → Security → SSH Keys
2. Tap "Import Key"
3. Choose your import method:
   - **From File**: Select your private key file
   - **From Clipboard**: Paste key text
   - **From SSH Directory**: Import from ~/.ssh/

### Generating New Keys
1. Go to Settings → Security → SSH Keys
2. Tap "Generate New Key"
3. Choose key type (RSA, ECDSA, or Ed25519)
4. Set key size and comment
5. Tap "Generate"

## Customization

### Themes
1. Go to Settings → Terminal → Theme
2. Choose from 12 built-in themes
3. Preview themes before applying

### Font and Size
1. Go to Settings → Terminal → Appearance
2. Adjust font family and size
3. Changes apply immediately

### Keyboard
1. Go to Settings → Interface → Keyboard
2. Configure function keys and shortcuts
3. Enable hardware keyboard shortcuts
```

#### 11.1.2 Advanced Features Guide
```markdown
# Advanced TabSSH Features

## SSH Configuration Import

TabSSH can import your existing SSH configuration from ~/.ssh/config files.

### Importing SSH Config
1. Go to Settings → Connections → Import SSH Config
2. Select your config file or paste the content
3. Review the imported connections
4. Save the connections you want to keep

### Supported SSH Config Options
- Host, HostName, User, Port
- IdentityFile, IdentitiesOnly
- ProxyJump, ProxyCommand
- LocalForward, RemoteForward, DynamicForward
- Compression, ServerAliveInterval
- PasswordAuthentication, PubkeyAuthentication

## Port Forwarding

### Local Port Forwarding
Forward a local port to a remote server through the SSH connection.

1. Open a connection
2. Go to Connection → Port Forwarding
3. Add Local Forward:
   - **Local Port**: Port on your device
   - **Remote Host**: Target server (often localhost)
   - **Remote Port**: Target port
4. Enable the tunnel

Example: Access a web server running on port 8080 on the remote server
- Local Port: 8080
- Remote Host: localhost  
- Remote Port: 8080

### Remote Port Forwarding
Forward a remote port back to your device.

### Dynamic Port Forwarding (SOCKS Proxy)
Create a SOCKS proxy for routing traffic through the SSH connection.

## File Transfer (SFTP)

### Opening File Browser
1. In any SSH session, tap the "Files" button
2. The dual-pane file browser opens
3. Left pane: Local files, Right pane: Remote files

### Transferring Files
- **Upload**: Drag from local to remote pane
- **Download**: Drag from remote to local pane
- **Batch Operations**: Long-press to select multiple files

### File Operations
- Create folders
- Delete files/folders
- Rename items
- Change permissions (remote files)
- View file properties

## Keyboard Shortcuts

### Tab Management
- Ctrl+T: New tab
- Ctrl+W: Close tab  
- Ctrl+Shift+T: Reopen closed tab
- Ctrl+Tab: Next tab
- Ctrl+Shift+Tab: Previous tab
- Ctrl+1-9: Switch to tab number

### Terminal
- Ctrl+C: Interrupt (SIGINT)
- Ctrl+D: EOF / Logout
- Ctrl+Z: Suspend (SIGTSTP)
- Ctrl+L: Clear screen
- Ctrl+A: Beginning of line
- Ctrl+E: End of line

### Application
- Ctrl+N: New connection
- Ctrl+S: Save current session
- Ctrl+O: Open connection
- Ctrl+,: Settings (comma)

## Themes and Customization

### Creating Custom Themes
While TabSSH includes 12 built-in themes, you can create custom themes:

1. Export an existing theme as a base
2. Edit the JSON file with your preferred colors
3. Import the custom theme
4. Apply and test

### Theme Color Properties
- background: Terminal background color
- foreground: Default text color
- ansiColors: Array of 16 ANSI colors (0-15)
- cursorColor: Cursor color
- selectionColor: Text selection highlight

## Security Features

### Password Storage Levels
- **Never**: Don't store passwords
- **Session Only**: Keep in memory during app session
- **Encrypted**: Store encrypted with device security
- **Biometric**: Require fingerprint/face unlock

### Host Key Verification
- Strict checking prevents man-in-the-middle attacks
- Automatic host key management
- Visual warnings for changed host keys

### Emergency Security
- Emergency wipe feature
- Auto-lock on app backgrounding  
- Screenshot prevention (optional)
- Clipboard auto-clear

## Accessibility

### Screen Reader Support
TabSSH fully supports TalkBack and other screen readers:
- Terminal content is announced
- Connection status updates are spoken
- All UI elements have proper labels

### Vision Accessibility
- High contrast mode
- Large text support
- Color blind friendly themes
- Zoom and pan support

### Motor Accessibility  
- Large touch targets
- Configurable gesture timeouts
- Hardware keyboard support
- Switch access compatibility
```

### 11.2 Developer Documentation

#### 11.2.1 Architecture Documentation
```markdown
# TabSSH Architecture

## Overview

TabSSH follows a modular architecture with clear separation of concerns:

- **UI Layer**: Activities, Fragments, Views
- **Business Logic**: SSH management, Terminal emulation
- **Data Layer**: Database, Preferences, File storage
- **Security Layer**: Encryption, Key management
- **Network Layer**: SSH protocols, Connection management

## Core Components

### SSH Connection Management

#### SSHSessionManager
Manages the lifecycle of SSH connections:

```java
public class SSHSessionManager {
    // Connection pool for reusing sessions
    private final Map<String, Session> sessionPool;
    
    // Active connections
    private final Map<String, SSHConnection> activeConnections;
    
    public SSHConnection createConnection(ConnectionProfile profile);
    public void closeConnection(String connectionId);
    public void closeAllConnections();
}
```

#### ConnectionProfile
Represents a saved SSH connection:

```java
@Entity(tableName = "connections")
public class ConnectionProfile {
    @PrimaryKey String id;
    String name, host, username;
    int port;
    AuthType authType;
    String keyId;
    Map<String, String> advancedSettings;
}
```

### Terminal Emulation

#### TerminalEmulator
Handles VT100/ANSI terminal emulation:

```java
public class TerminalEmulator {
    private TerminalBuffer buffer;
    private ANSIParser ansiParser;
    private CursorState cursor;
    
    public void processInput(byte[] data);
    public void handleKeyPress(int keyCode, KeyEvent event);
    public void resize(int rows, int cols);
}
```

#### TerminalRenderer
Renders terminal content to Canvas:

```java
public class TerminalRenderer {
    public void render(Canvas canvas, TerminalBuffer buffer);
    private void renderCursor(Canvas canvas);
    private void renderSelection(Canvas canvas);
}
```

### Tab Management

#### TabManager
Manages multiple SSH sessions in tabs:

```java
public class TabManager {
    private List<SSHTab> tabs;
    private int activeTabIndex;
    
    public SSHTab createTab(ConnectionProfile profile);
    public void switchTab(int index);
    public void closeTab(int index);
    public void saveTabState();
    public void restoreTabState();
}
```

## Data Flow

### Connection Establishment
1. User selects connection from UI
2. TabManager creates new SSHTab
3. SSHSessionManager establishes connection
4. TerminalEmulator initialized for session
5. UI updates to show new tab

### Terminal I/O
1. User types on keyboard
2. KeyboardHandler processes input
3. Input sent to SSH session
4. Server response received
5. TerminalEmulator processes ANSI sequences
6. TerminalRenderer updates display
7. UI refreshed

### File Transfer
1. User initiates transfer in SFTP browser
2. SFTPManager creates transfer task
3. Transfer runs in background thread
4. Progress updates sent to UI
5. Completion notification shown

## Security Architecture

### Encryption Layers
1. **Transport**: SSH protocol encryption
2. **Storage**: Android Keystore for passwords/keys
3. **Memory**: Attempt to clear sensitive data
4. **UI**: Optional screenshot protection

### Key Management Flow
1. Import/generate private key
2. Encrypt with Android Keystore
3. Store encrypted data + metadata
4. Decrypt when needed for authentication
5. Clear from memory after use

## Threading Model

### Main Thread
- UI updates and user interactions
- Should never block

### SSH Thread Pool
- Connection management (4 threads)
- Command execution
- I/O operations

### Terminal Thread
- Terminal emulation and rendering
- Single background thread per tab

### File Transfer Thread
- SFTP operations
- Separate thread per transfer

## Error Handling

### Connection Errors
- Network timeouts
- Authentication failures  
- Protocol errors
- Host key mismatches

### Recovery Strategies
- Automatic reconnection
- Connection pooling
- Graceful degradation
- User notification

## Performance Considerations

### Memory Management
- LRU cache for bitmaps
- Terminal buffer trimming
- Inactive tab compression

### Rendering Optimization
- Dirty region tracking
- Hardware acceleration
- Efficient font rendering

### Network Optimization
- Connection reuse
- Compression
- Keep-alive tuning
```

#### 11.2.2 API Documentation
```markdown
# TabSSH API Reference

## Core Classes

### ConnectionProfile

Represents an SSH connection configuration.

#### Constructor
```java
public ConnectionProfile()
public ConnectionProfile(String name, String host, int port, String username)
```

#### Methods
```java
// Basic connection info
public String getId()
public void setId(String id)
public String getName()
public void setName(String name)
public String getHost()
public void setHost(String host)
public int getPort()
public void setPort(int port)
public String getUsername()
public void setUsername(String username)

// Authentication
public AuthType getAuthType()
public void setAuthType(AuthType type)
public String getKeyId()
public void setKeyId(String keyId)

// Advanced settings
public String getTerminalType()
public void setTerminalType(String terminalType)
public boolean isCompressionEnabled()
public void setCompressionEnabled(boolean enabled)
public int getConnectTimeout()
public void setConnectTimeout(int timeoutSeconds)
```

### SSHSession

Manages an active SSH connection.

#### Methods
```java
// Connection management
public boolean connect()
public void disconnect()
public boolean isConnected()
public ConnectionState getState()

// Command execution
public String executeCommand(String command)
public void sendInput(String input)
public void sendKeyPress(int keyCode)

// File operations
public SFTPChannel openSFTPChannel()
public boolean uploadFile(File localFile, String remotePath)
public boolean downloadFile(String remotePath, File localFile)

// Port forwarding
public LocalPortForward createLocalForward(int localPort, String remoteHost, int remotePort)
public RemotePortForward createRemoteForward(int remotePort, String localHost, int localPort)
public DynamicPortForward createDynamicForward(int localPort)

// Session information
public String getServerVersion()
public String[] getSupportedCiphers()
public long getBytesTransferred()
public long getSessionDuration()
```

### TerminalEmulator

Handles terminal emulation and ANSI processing.

#### Methods
```java
// Terminal control
public void resize(int rows, int cols)
public void clear()
public void reset()

// Content access
public String getScreenContent()
public String getScrollbackContent()
public String getSelectedText()

// Cursor operations
public void setCursorPosition(int row, int col)
public Point getCursorPosition()
public void setCursorVisible(boolean visible)

// Input processing
public void processInput(byte[] data)
public void processKeyEvent(KeyEvent event)

// Display settings
public void setTheme(Theme theme)
public Theme getTheme()
public void setFontSize(float size)
public float getFontSize()
```

### SecurePasswordManager

Manages secure password storage.

#### Methods
```java
// Password operations
public void storePassword(String connectionId, String password)
public void storePassword(String connectionId, String password, StorageLevel level)
public String retrievePassword(String connectionId)
public void deletePassword(String connectionId)
public void deleteAllPasswords()

// Biometric operations
public void storePasswordWithBiometrics(String connectionId, String password, 
                                       BiometricPrompt.AuthenticationCallback callback)
public void retrievePasswordWithBiometrics(String connectionId,
                                          BiometricPrompt.AuthenticationCallback callback)

// Policy management
public void setPasswordPolicy(PasswordPolicy policy)
public PasswordPolicy getPasswordPolicy()
public boolean canStorePassword(String hostname)
```

### KeyStorage

Manages SSH private key storage.

#### Methods
```java
// Key operations
public String storePrivateKey(PrivateKey key, String name)
public PrivateKey retrievePrivateKey(String keyId)
public void deleteKey(String keyId)
public List<StoredKey> listStoredKeys()

// Key generation
public KeyPair generateKeyPair(KeyType type, int keySize)
public String generateKeyPair(KeyType type, int keySize, String comment)

// Key import/export
public String importKeyFromFile(File keyFile, String passphrase)
public String importKeyFromText(String keyText, String passphrase)
public void exportKey(String keyId, File outputFile)

// Key information
public String getKeyFingerprint(String keyId)
public KeyType getKeyType(String keyId)
public int getKeySize(String keyId)
```

## Enumerations

### AuthType
```java
public enum AuthType {
    PASSWORD,
    PUBLIC_KEY,
    KEYBOARD_INTERACTIVE,
    GSSAPI
}
```

### ConnectionState
```java
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    ERROR
}
```

### KeyType
```java
public enum KeyType {
    RSA(2048, 4096),
    DSA(1024, 1024),
    ECDSA(256, 521),
    ED25519(256, 256);
}
```

### StorageLevel
```java
public enum StorageLevel {
    NEVER,
    SESSION_ONLY,
    ENCRYPTED,
    BIOMETRIC
}
```

## Interfaces

### ConnectionListener
```java
public interface ConnectionListener {
    void onConnecting(String connectionId);
    void onConnected(String connectionId);
    void onDisconnected(String connectionId);
    void onError(String connectionId, Exception error);
    void onDataReceived(String connectionId, byte[] data);
}
```

### TransferListener
```java
public interface TransferListener {
    void onTransferStarted(String transferId);
    void onProgress(String transferId, long bytesTransferred, long totalBytes);
    void onTransferCompleted(String transferId);
    void onTransferFailed(String transferId, Exception error);
    void onTransferPaused(String transferId);
    void onTransferResumed(String transferId);
}
```

### ThemeChangeListener
```java
public interface ThemeChangeListener {
    void onThemeChanged(Theme newTheme);
    void onThemeValidationFailed(Theme theme, ValidationResult result);
}
```

## Exception Classes

### SSHException
```java
public class SSHException extends Exception {
    public SSHException(String message)
    public SSHException(String message, Throwable cause)
    public int getErrorCode()
    public String getDetailedMessage()
}
```

### AuthenticationException
```java
public class AuthenticationException extends SSHException {
    public AuthenticationException(String message)
    public AuthType getFailedAuthType()
    public String[] getSupportedAuthTypes()
}
```

### KeyStorageException
```java
public class KeyStorageException extends Exception {
    public KeyStorageException(String message)
    public KeyStorageException(String message, Throwable cause)
}
```
```

---

## 12. Project Management

### 12.1 Development Roadmap

#### 12.1.1 Version 1.0 (MVP) - Target: Q2 2025
```markdown
## Core Features (Must Have)
- [x] Project setup and basic architecture
- [x] SSH connection management
- [x] Basic terminal emulation (VT100/ANSI)
- [x] Tabbed interface
- [x] Connection profiles (save/load)
- [x] Password storage (encrypted)
- [x] Basic theming system (6 themes)
- [ ] SSH key import/generation
- [ ] SSH config import
- [ ] SFTP file browser
- [ ] Basic settings/preferences
- [ ] Host key verification
- [ ] F-Droid release preparation

## Quality Assurance
- [ ] Unit test coverage >80%
- [ ] UI testing for critical flows
- [ ] Security testing and audit
- [ ] Accessibility testing (TalkBack)
- [ ] Performance optimization
- [ ] Documentation completion

## Release Criteria
- [ ] No critical bugs
- [ ] Security review passed
- [ ] Accessibility compliance verified
- [ ] F-Droid metadata prepared
- [ ] User documentation complete
```

#### 12.1.2 Version 1.1 (Enhanced) - Target: Q3 2025
```markdown
## Enhanced Features
- [ ] Port forwarding (all types)
- [ ] Advanced theming (all 12 themes)
- [ ] Snippet management
- [ ] Advanced SFTP features
- [ ] Connection groups/folders
- [ ] Backup/restore functionality
- [ ] Multi-language support (5 languages)
- [ ] Hardware keyboard optimization
- [ ] Gesture customization

## Performance & Polish
- [ ] Memory usage optimization
- [ ] Battery usage optimization
- [ ] Animation and transition polish
- [ ] Advanced accessibility features
- [ ] Plugin architecture foundation
```

#### 12.1.3 Version 1.2 (Advanced) - Target: Q4 2025
```markdown
## Advanced Features
- [ ] Mosh protocol support
- [ ] X11 forwarding
- [ ] SSH agent forwarding  
- [ ] Jump host/ProxyCommand support
- [ ] Advanced terminal features (mouse support, etc.)
- [ ] Cloud backup (optional, privacy-preserving)
- [ ] Tile/split-screen support
- [ ] Advanced scripting/automation

## Platform Expansion
- [ ] Tablet optimization
- [ ] Chromebook support
- [ ] Android TV support (future)
- [ ] Wear OS companion (future)
```

### 12.2 Team Structure

#### 12.2.1 Core Team Roles
```markdown
## Project Leadership
- **Project Lead**: Overall project direction and coordination
- **Technical Lead**: Architecture decisions and code quality
- **Security Lead**: Security review and best practices
- **UX Lead**: User experience and accessibility

## Development Team
- **Android Developers** (2-3): Core app development
- **Security Engineer**: Cryptography and security features
- **UI/UX Designer**: Interface design and user experience
- **QA Engineer**: Testing and quality assurance

## Community Team
- **Community Manager**: GitHub issues, discussions, PR reviews
- **Documentation Lead**: User and developer documentation
- **Translation Coordinator**: Multi-language support
- **Release Manager**: CI/CD, releases, F-Droid coordination
```

#### 12.2.2 Contribution Guidelines
```markdown
## Code Contributions
- Fork the repository and create feature branches
- Follow coding standards and architectural patterns
- Include unit tests for new functionality
- Update documentation for user-facing changes
- Submit pull requests for review

## Issue Management
- Bug reports: Use provided template with reproduction steps
- Feature requests: Describe use case and benefit
- Security issues: Report privately via security@tabssh.org
- Questions: Use GitHub Discussions

## Review Process
- All code changes require review
- Security-sensitive changes require security team review
- UI changes require UX team review
- Breaking changes require technical lead approval

## Release Process
1. Feature development in feature branches
2. Integration testing on develop branch  
3. Release candidate testing
4. Security review and sign-off
5. Release tagging and deployment
6. F-Droid update submission
```

### 12.3 Quality Assurance

#### 12.3.1 Testing Standards
```markdown
## Code Coverage Requirements
- Unit tests: >80% line coverage
- Integration tests: Critical user flows
- Security tests: All security-sensitive code
- UI tests: Primary user workflows
- Accessibility tests: Full TalkBack compatibility

## Performance Standards
- App startup: <2 seconds on mid-range devices
- Connection establishment: <5 seconds on good network
- Memory usage: <100MB for 5 active tabs
- Battery impact: Minimal when not actively used
- APK size: <50MB total

## Security Standards
- All cryptographic operations reviewed
- No hardcoded secrets or keys
- Secure defaults for all preferences
- Regular dependency vulnerability scans
- Third-party security audit before 1.0 release

## Accessibility Standards
- WCAG 2.1 AA compliance
- TalkBack full compatibility
- Minimum touch target size: 48dp
- Color contrast ratios: 4.5:1 minimum
- Keyboard navigation support
```

#### 12.3.2 Release Criteria
```markdown
## Version 1.0 Release Gates
- [ ] All P0 (critical) bugs resolved
- [ ] Security review completed and approved
- [ ] Accessibility testing passed
- [ ] Performance benchmarks met
- [ ] Documentation completed
- [ ] Legal review (licenses, compliance)
- [ ] F-Droid submission requirements met
- [ ] Community feedback incorporated

## Post-Release Monitoring
- Crash reporting and analysis
- Performance monitoring
- User feedback collection
- Security incident response plan
- Update deployment strategy
```

### 12.4 Community Building

#### 12.4.1 Community Engagement
```markdown
## Communication Channels
- **GitHub**: Primary development hub
  - Issues: Bug reports and feature requests
  - Discussions: General questions and ideas
  - Wiki: Community-maintained documentation
  
- **Website**: https://tabssh.github.io
  - Downloads and releases
  - User documentation
  - Blog posts and updates
  
- **Matrix/Discord** (Future): Real-time community chat
- **Mastodon** (Future): Project updates and announcements

## Community Programs
- **Contributor Recognition**: Contributors page and release credits
- **Translation Program**: Crowdsourced translations
- **Beta Testing**: Early access to new features
- **Security Bug Bounty** (Future): Responsible disclosure rewards
- **Community Themes**: User-submitted theme gallery
```

#### 12.4.2 Governance Model
```markdown
## Decision Making
- **Technical Decisions**: Core team consensus
- **Feature Priorities**: Community input + team decision
- **Security Policies**: Security team authority
- **Release Timing**: Project lead decision

## Code of Conduct
- Welcoming and inclusive environment
- Respectful communication
- Focus on constructive feedback
- Zero tolerance for harassment
- Transparent enforcement

## Project Sustainability
- **Funding**: Donations, grants, sponsorships
- **Governance**: Transparent decision-making
- **Succession Planning**: Distributed knowledge and access
- **Legal Structure**: Appropriate legal entity (future)
```

---

## Conclusion

This comprehensive specification defines TabSSH as a modern, secure, and accessible SSH client for Android. The project emphasizes:

- **Open Source Transparency**: MIT licensed with full community involvement
- **Security First**: Enterprise-grade security suitable for professional use
- **Accessibility**: Full support for users with disabilities
- **Modern UX**: Clean, intuitive interface following Material Design
- **Privacy Focus**: No tracking, analytics, or data collection

The modular architecture, comprehensive testing strategy, and clear development roadmap position TabSSH to become the premier open-source SSH client for Android, filling the gap left by proprietary alternatives while maintaining the highest standards of security and usability.

**Repository**: https://github.com/TabSSH/android  
**Website**: https://tabssh.github.io  
**License**: MIT  
**Target Release**: Q2 2025  

---

*This specification serves as the authoritative technical document for TabSSH development. It will be updated as the project evolves while maintaining backwards compatibility and user privacy commitments.*





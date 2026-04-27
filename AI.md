# TabSSH Android — AI Project Specification

> **Audience:** AI coding assistants (Claude Code, Copilot, Gemini, etc.) and human contributors who need an accurate, code-grounded picture of how this project is actually built. **CLAUDE.md** is the operational/runbook document; this file is the architectural ground truth derived from a full source survey.
>
> **Generated:** 2026-04-25 from a parallel survey of 166 Kotlin sources, all Gradle/Docker/CI configs, and every preference/layout/menu XML.
>
> **Last verified against:** `versionCode 3` / `versionName 1.0.0`, database `v22` (post-Wave 3.1; v17 → v18 env_vars+agent_forwarding → v19 stored_keys.certificate → v20 connections.protocol → v21 workspaces table → v22 connections.color_tag), JSch `mwiede:2.27.7`, Termux `terminal-emulator:0.118.1`, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1.
>
> **Format conventions:**
> - File paths are repo-relative unless prefixed with `/`.
> - Class references use Kotlin FQN: `io.github.tabssh.<package>.<Class>`.
> - "Stub" or "framework only" means the file/class exists but is not wired into a working user-facing flow — treat as future work.

---

## Table of Contents

1. [Project identity](#1-project-identity)
2. [High-level architecture](#2-high-level-architecture)
3. [Build, toolchain, and dependencies](#3-build-toolchain-and-dependencies)
4. [Application layer](#4-application-layer)
5. [SSH connection layer](#5-ssh-connection-layer)
6. [Terminal emulation layer](#6-terminal-emulation-layer)
7. [Cryptography and key management](#7-cryptography-and-key-management)
8. [Storage and database](#8-storage-and-database)
9. [Sync system (SAF-based)](#9-sync-system-saf-based)
10. [Backup and restore](#10-backup-and-restore)
11. [Hypervisor integration](#11-hypervisor-integration)
12. [UI, theming, accessibility, i18n](#12-ui-theming-accessibility-i18n)
13. [Notifications, services, widgets, automation](#13-notifications-services-widgets-automation)
14. [Build and release infrastructure](#14-build-and-release-infrastructure)
15. [Package map](#15-package-map)
16. [Known stubs and limitations](#16-known-stubs-and-limitations)
17. [Editing guidelines for AI agents](#17-editing-guidelines-for-ai-agents)

---

## 1. Project identity

| Field | Value |
|---|---|
| **App ID** | `io.github.tabssh` |
| **Display name** | TabSSH |
| **Type** | Android SSH client with browser-style tabs |
| **Language** | Kotlin (primary), Java (interop) |
| **License** | MIT |
| **Repository** | https://github.com/tabssh/android |
| **Distribution** | GitHub Releases + F-Droid (planned) |
| **Min SDK** | 21 (Android 5.0) — covers ~99.5% of devices |
| **Target SDK** | 34 (Android 14) |
| **Compile SDK** | 34 |
| **JVM target** | 17 |
| **Kotlin code style** | `official` |

**Design pillars (as expressed in the code):**
1. **Tabbed terminal sessions** — `TabManager` + `ViewPager2` allow concurrent SSH sessions with swipe/keyboard navigation, modeled on browser tabs.
2. **Provider-neutral cloud sync** — uses Android's Storage Access Framework so the user picks any DocumentsProvider (Drive, Dropbox, OneDrive, Nextcloud, local) instead of the app embedding cloud SDKs.
3. **Real terminal emulator** — wraps Termux's `TerminalEmulator` (full VT100/ANSI/xterm-256color) rather than reimplementing.
4. **Hardware-backed crypto** — Android Keystore + AES-GCM for password storage, biometric unlock, BouncyCastle for SSH key parsing.
5. **F-Droid friendly** — no proprietary dependencies; reproducible build variant (`fdroidRelease`); zero analytics.

---

## 2. High-level architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Activities / Fragments (ui/activities, ui/fragments)        │
│  MainActivity → TabTerminalActivity → SFTPActivity, …       │
└────────────┬────────────────────────────────────────────────┘
             │
┌────────────▼────────────────────────────────────────────────┐
│ Managers (initialized lazily on TabSSHApplication)          │
│  SSHSessionManager, TerminalManager, TabManager,            │
│  ThemeManager, PerformanceManager, AuditLogManager,         │
│  SecurePasswordManager, KeyStorage, PreferenceManager       │
└────┬──────────────────┬──────────────────┬─────────────────┘
     │                  │                  │
┌────▼────┐  ┌──────────▼─────────┐  ┌────▼──────────────┐
│ SSH     │  │ Terminal           │  │ Storage           │
│ ssh/*   │  │ terminal/*         │  │ storage/database/ │
│ JSch    │  │ TermuxBridge →     │  │ Room (v17)        │
│ 2.27.7  │  │ Termux emulator    │  │ 12 entities       │
└────┬────┘  └──────────┬─────────┘  └────┬──────────────┘
     │                  │                  │
┌────▼──────────────────▼──────────────────▼─────────────────┐
│ Cross-cutting: crypto/, sync/, backup/, hypervisor/,        │
│ accessibility/, themes/, services/, protocols/              │
└─────────────────────────────────────────────────────────────┘
```

**State propagation:** Kotlin `StateFlow` / `Flow` from Room DAOs is the canonical reactive primitive. UI layers collect flows in lifecycle scopes; managers expose `StateFlow` for current state (active theme, tabs, performance metrics).

**Threading model:**
- IO dispatcher for SSH read loops, SFTP transfers, database writes, sync.
- Main dispatcher for UI updates.
- WorkManager (`androidx.work:work-runtime:2.9.0`) for periodic sync.
- A foreground `SSHConnectionService` keeps connections alive when the app is backgrounded.

---

## 3. Build, toolchain, and dependencies

### 3.1 Toolchain

| Component | Version | File |
|---|---|---|
| Android Gradle Plugin | 8.7.3 | `build.gradle` |
| Kotlin | 2.0.21 | `build.gradle` |
| Gradle wrapper | 8.11.1 | `gradle/wrapper/gradle-wrapper.properties` |
| OWASP DependencyCheck | 8.4.0 | `build.gradle` (CVSS fail threshold ≥ 7.0) |
| JVM target | 17 (Eclipse Temurin in Docker) | `app/build.gradle`, `docker/Dockerfile` |

### 3.2 Build types and flavors

Defined in `app/build.gradle`:

| Type | Minify | Shrink | ProGuard files | Signing |
|---|---|---|---|---|
| `debug` | off | off | — | shared `keystore.jks` (dev) |
| `release` | R8 on | on | `proguard-android-optimize.txt`, `proguard-rules.pro` | `keystore.jks` |
| `fdroidRelease` | R8 on | on | …+ `proguard-fdroid.pro` (deterministic, 5 optimization passes, strips line numbers, exports `seeds.txt`/`usage.txt`/`mapping.txt`) | `keystore.jks` |

**APK splits** (`splits.abi`): `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, plus universal. Output renamed to `tabssh-{arch}.apk` via the custom output naming rule in `app/build.gradle`.

### 3.3 Key dependencies (`app/build.gradle`)

| Category | Coordinate | Version |
|---|---|---|
| SSH | `com.github.mwiede:jsch` | 2.27.7 |
| Terminal | `com.termux.termux-app:terminal-emulator` (excludes `terminal-view`) | 0.118.1 |
| Crypto | `org.bouncycastle:bcpkix-jdk18on` | 1.77 |
| Crypto | `org.bouncycastle:bcprov-jdk18on` | 1.77 |
| HTTP/WS | `com.squareup.okhttp3:okhttp` | 4.12.0 |
| DB | `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (kapt) | 2.6.1 |
| Background | `androidx.work:work-runtime` | 2.9.0 |
| Security | `androidx.security:security-crypto` | 1.1.0-alpha06 |
| Biometric | `androidx.biometric:biometric` | 1.1.0 |
| UI | `com.google.android.material:material` | 1.11.0 |
| Charts | `com.github.PhilJay:MPAndroidChart` | v3.1.0 |
| JSON | `com.google.code.gson:gson` 2.10.1; `org.jetbrains.kotlinx:kotlinx-serialization-json` 1.6.0 |
| Test | JUnit 4.13.2, Mockito 5.7.0, Espresso 3.5.1, JaCoCo |

`settings.gradle` adds Termux's Maven repo and JitPack alongside Google + Maven Central.

### 3.4 Repository layout

```
android/
├── app/                          # Single Gradle module
│   ├── src/main/java/io/github/tabssh/
│   ├── src/main/res/
│   │   ├── layout/   (~83 files)
│   │   ├── menu/     (9 files)
│   │   ├── xml/      (9 preference files + paths/widget configs)
│   │   ├── values/, values-es/, values-fr/, values-de/
│   │   └── drawable/, mipmap-*/
│   ├── src/main/AndroidManifest.xml
│   ├── build.gradle
│   ├── proguard-rules.pro
│   ├── proguard-fdroid.pro
│   └── schemas/                  # Room exported JSON schemas
├── build.gradle, settings.gradle, gradle.properties
├── gradle/wrapper/
├── docker/                       # Dockerfile, Dockerfile.dev, compose files
├── scripts/                      # build/check/fix shell scripts
├── metadata/                     # F-Droid metadata
├── fdroid-submission/            # F-Droid YAML + canonical SPEC.md, CHANGELOG
├── config/dependency-check-suppressions.xml
├── keystore.jks (dev keystore — do NOT use for production)
├── Makefile, build.sh
├── README.md, CHANGELOG.md, SPEC.md (symlink → fdroid-submission/SPEC.md),
│ CLAUDE.md, TODO.AI.md, AI.md (this file), LICENSE.md
└── .github/                      # workflows, templates, CONTRIBUTING.md
```

---

## 4. Application layer

### 4.1 `TabSSHApplication`

`app/src/main/java/io/github/tabssh/TabSSHApplication.kt`

`onCreate()` flow:
1. `Logger.initialize()` and a global uncaught-exception handler.
2. `NotificationHelper.createNotificationChannels()` — see §13.
3. `registerActivityLifecycleCallbacks()` to track the foreground activity (used by `HostKeyVerifier` for blocking dialogs).
4. `initializeCoreComponents()` (try/catch — degrades gracefully if initialization fails).

Lazy singletons exposed on the application instance:
`database` (Room), `preferencesManager`, `securePasswordManager`, `keyStorage`, `sshSessionManager`, `terminalManager`, `themeManager`, `performanceManager`, `auditLogManager`, `tabManager`.

Crash handling: shows `CrashReportActivity` in debug, logs and clears sensitive in-memory state in release. Crash metadata persisted to `STARTUP_PREFS` SharedPreferences (`KEY_LAST_CRASH`, `KEY_CRASH_THREAD`, `KEY_CRASH_TIME`).

### 4.2 `AndroidManifest.xml`

Top-level config: `android:name=".TabSSHApplication"`, `android:allowBackup="false"`, `android:fullBackupContent="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`, `android:hardwareAccelerated="true"`, `android:largeHeap="true"`, default theme `@style/Theme.TabSSH.Dark`.

**Permissions:** `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` (API ≤28), `WAKE_LOCK`, `VIBRATE`, `POST_NOTIFICATIONS` (API 33+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`. Uses `tools:overrideLibrary` to allow Termux's library to coexist with `minSdk 21`.

**Exported components:** `MainActivity` (LAUNCHER), `TaskerIntentService` (Tasker plug-in), `QuickConnectWidgetProvider`. Everything else is `exported="false"`.

### 4.3 Activities (23)

| Activity | Purpose | Notable extras |
|---|---|---|
| `MainActivity` | 5-tab connection hub (Frequent / Connections / Identities / Performance / Hypervisors) with drawer | — |
| `TabTerminalActivity` | Multi-tab SSH terminal | `EXTRA_CONNECTION_PROFILE_ID`, `EXTRA_CONNECTION_PROFILE` (JSON), `EXTRA_AUTO_CONNECT` |
| `ConnectionEditActivity` | Create/edit `ConnectionProfile` | optional `CONNECTION_ID` |
| `GroupManagementActivity` | CRUD `ConnectionGroup` (folders, nested) | — |
| `SnippetManagerActivity` | CRUD `Snippet` library | — |
| `ClusterCommandActivity` | Send command to multiple connections | — |
| `KeyManagementActivity` | List/import/paste/generate/delete `StoredKey` | — |
| `IdentityManagementActivity` | CRUD `Identity` | — |
| `SFTPActivity` | Dual-pane SFTP browser | `EXTRA_CONNECTION_ID` |
| `PortForwardingActivity` | Local/remote/dynamic forward management | — |
| `SettingsActivity` | Hosts settings PreferenceFragments | — |
| `SyncSettingsActivity` | Hosts `SyncSettingsFragment` | — |
| `LogViewerActivity`, `AuditLogViewerActivity` | View app/audit logs | — |
| `KeyboardCustomizationActivity` | Build custom on-screen keyboard layout | — |
| `HypervisorEditActivity` | CRUD `HypervisorProfile` | — |
| `ProxmoxManagerActivity`, `XCPngManagerActivity`, `VMwareManagerActivity` | Per-hypervisor VM list & actions | hypervisor id |
| `VMConsoleActivity` | Hypervisor serial console (no SSH) | hypervisor + VM ids |
| `WidgetConfigurationActivity` | Configure quick-connect widgets | widget id |
| `TranscriptViewerActivity` | Replay recorded sessions | transcript id |
| `CrashReportActivity` | Debug crash dump UI (debug builds) | — |

`MainActivity` and `TabTerminalActivity` are `singleTop`. All have `parentActivityName` set for back navigation. `VMConsoleActivity` runs fullscreen (no action bar).

### 4.4 Fragments (7)

`FrequentConnectionsFragment`, `ConnectionsFragment`, `IdentitiesFragment`, `PerformanceFragment`, `HypervisorsFragment`, `ConnectionListFragment`, `SyncSettingsFragment`. Plus `PreferenceFragmentCompat` subclasses inside `SettingsActivity`: `SettingsMainFragment`, `GeneralSettingsFragment`, `TerminalSettingsFragment`, `SecuritySettingsFragment`, `ConnectionSettingsFragment`, `LoggingSettingsFragment`, `TaskerSettingsFragment`.

### 4.5 Services and receivers

| Component | Type | Purpose |
|---|---|---|
| `SSHConnectionService` | foreground (`dataSync`) | Holds SSH sessions while app is backgrounded; `START_STICKY`; auto-stops 30 s after last connection closes |
| `TaskerIntentService` | exported intent service | Tasker plug-in actions: `CONNECT`, `DISCONNECT`, `SEND_COMMAND`, `SEND_KEYS` |
| `QuickConnectWidgetProvider` | `AppWidgetProvider` | 1×1 launcher widget |
| `ConnectionWidgetProvider` (+ `Widget2x1`, `Widget4x2`, `Widget4x4`) | `AppWidgetProvider` | Multi-size connection widgets |
| `FileProvider` | content provider | Shares logs / transcripts via `app/src/main/res/xml/file_paths.xml` |

### 4.6 Canonical user flows

**Quick connect:** `MainActivity` (Frequent tab) → tap connection → `TabTerminalActivity.createIntent(profile, autoConnect=true)` → `SSHSessionManager.connect()` → `TermuxBridge` wired to `TerminalView`.

**New connection:** `MainActivity` → FAB → `ConnectionEditActivity` → save → returns to list.

**File transfer:** `TabTerminalActivity` menu → `SFTPActivity` (uses the existing SSH session) → upload/download via `SFTPManager`.

**Hypervisor console:** `MainActivity` (Hypervisors tab) → manager activity → VM row → `VMConsoleActivity` → `HypervisorConsoleManager` opens `ConsoleWebSocketClient` and pipes its streams into `TermuxBridge`.

---

## 5. SSH connection layer

Package `io.github.tabssh.ssh.*` (with `services/SSHConnectionService` for the foreground service). Built on **JSch (`com.github.mwiede:jsch:2.27.7`)** — the actively-maintained fork that supports `rsa-sha2-256`/`-512` (required by OpenSSH 8.8+).

### 5.1 `SSHConnection`

`ssh/connection/SSHConnection.kt` (~1200 lines) — the central connection lifecycle:

1. Optional **port knock** sequence before connect.
2. `JSch` session with custom `HostKeyRepository` (`HostKeyVerifier`).
3. **Jump host** support: opens an upstream SSH session and uses `setPortForwardingL` to tunnel to the real target, then connects through that tunnel.
4. **HTTP/SOCKS proxy** via `ProxyHTTP`, `ProxySOCKS4`, `ProxySOCKS5`.
5. Session config (compression, keep-alive, ciphers). Default cipher/MAC preferences:
   ```
   cipher.{s2c,c2s} = aes256-gcm@openssh.com,aes128-gcm@openssh.com,
                      aes256-ctr,aes192-ctr,aes128-ctr
   mac.{s2c,c2s}    = hmac-sha2-256-etm@openssh.com,hmac-sha2-512-etm@openssh.com,
                      hmac-sha2-256,hmac-sha2-512
   PreferredAuthentications = publickey,keyboard-interactive,password
   ```
6. Authentication priority: **public key** (if `effectiveKeyId` set) → **password** (`cachedPassword`) → **keyboard-interactive** fallback.
7. Opens shell channel (`session.openChannel("shell")`) with PTY type/size, exposes I/O streams.
8. Detailed error info on failure: `SocketTimeoutException`, `UnknownHostException`, `ConnectException`, `SocketException`, `JSchException`, with `errorType` / `userMessage` / `technicalDetails` / `possibleSolutions` displayed via `dialog_ssh_connection_error.xml`.
9. Auto-reconnect: up to 3 retries with 5 s delay for transient network errors; never retries auth failures.

### 5.2 Host key verification

`ssh/connection/HostKeyVerifier.kt` implements JSch's `HostKeyRepository`. Outcomes: `ACCEPTED`, `NEW_HOST`, `CHANGED_KEY`. Backed by `HostKeyDao` and shows `dialog_new_host_key.xml` or `dialog_host_key_changed.xml` with a SHA-256 fingerprint (and a visual "emoji fingerprint"). Trust levels persisted to the `host_keys` table: `UNKNOWN` / `ACCEPTED` / `VERIFIED`.

### 5.3 Port forwarding and proxies

`ssh/forwarding/PortForwardingManager.kt` manages local, remote, and dynamic (SOCKS) forwards. UI in `PortForwardingActivity` with three dialog layouts (`dialog_local_forward.xml`, `dialog_remote_forward.xml`, `dialog_dynamic_forward.xml`).

### 5.4 SSH config import

`ssh/config/SSHConfigParser.kt` parses `~/.ssh/config` syntax: `Host`, `HostName`, `User`, `Port`, `IdentityFile`, `ProxyJump`, `ProxyCommand`, `Compression`, `ServerAliveInterval`, `ConnectTimeout`, `Ciphers`, `Macs`. Each matched `Host` block becomes a `ConnectionProfile`.

### 5.5 SFTP

`sftp/SFTPManager.kt` opens `ChannelSftp` from an existing `SSHConnection`. `TransferTask` provides progress, cancellation, and (when the server supports it) resume. Default buffer 32 KB. UI: `SFTPActivity` (dual-pane), `FileAdapter`, `TransferAdapter`. Progress also surfaces through `NotificationHelper.showFileTransferProgress` (channel `file_transfer`).

---

## 6. Terminal emulation layer

### 6.1 `TermuxBridge`

`terminal/TermuxBridge.kt` wraps Termux's `TerminalEmulator` so we get full VT100/ANSI/xterm-256color (vim, htop, nano all work) without re-implementing the parser.

- Implements `TerminalOutput` (writes user input to the SSH `OutputStream`) and `TerminalSessionClient` (callbacks for clipboard, bell, title change, color change).
- Read loop: an IO-dispatcher coroutine reads the SSH `InputStream` in 8 KB chunks and calls `emulator.append()`.
- Default term type: `xterm-256color`.

The same bridge is used by `VMConsoleActivity` — a `ConsoleWebSocketClient` exposes piped `InputStream`/`OutputStream` and is wired to `TermuxBridge` via `HypervisorConsoleManager.wireToTerminal()`.

### 6.2 `TerminalView` (custom `View`)

`ui/views/TerminalView.kt`:
- Custom canvas rendering with monospace font, default 80×24 cells.
- 16-color ANSI palette + 256-color rendering of Termux buffer cells (foreground/background indices, bold, underline, reverse).
- Pinch-to-zoom (font size 8–32 sp).
- Long-press URL detection (regex matches `https?://…` and `www.…`); shows a dialog with **Open / Copy / Cancel**.
- `TerminalAccessibilityHelper` integration (TalkBack) with custom `READ_SCREEN`/`READ_LINE` actions.
- `BitSet`-based dirty-row tracking for incremental redraws.

### 6.3 `TabManager` and `SSHTab`

`ui/tabs/TabManager.kt`, `ui/tabs/SSHTab.kt`:
- Each `SSHTab` owns one `SSHConnection`, one `TermuxBridge`, and a `SessionRecorder`.
- `connectionState` (`CONNECTING` / `CONNECTED` / `DISCONNECTED` / `ERROR`), `title`, and `hasUnreadOutput` are all `StateFlow`.
- `TabManager` enforces a configurable max (default 10).
- Keyboard shortcuts (when a hardware keyboard is attached): `Ctrl+T` new, `Ctrl+W` close, `Ctrl+Tab` next, `Ctrl+Shift+Tab` previous, `Ctrl+1..9` jump.
- `TerminalPagerAdapter` exposes tabs through `ViewPager2` for swipe navigation; can be disabled via the `swipe_between_tabs` preference (falls back to single-`TerminalView` mode).

### 6.4 Input

- `KeyboardHandler` translates Android key events into ANSI control sequences.
- `view_custom_keyboard.xml` + `KeyboardKeyAdapter` implement an on-screen SSH keyboard (Esc, Tab, Ctrl, Alt, arrows, Fn keys, customizable rows 1–5).
- `GestureCommandMapper` + `TerminalGestureHandler` bind multi-touch gestures (2/3-finger swipes, pinch in/out — 10 mappings) to tmux/screen/zellij command sequences with configurable prefix.

### 6.5 Recording

`SessionRecorder` writes a transcript per session if `auto_record_sessions` is enabled. `TranscriptManager` lists saved files; `TranscriptViewerActivity` plays them back.

---

## 7. Cryptography and key management

### 7.1 Supported SSH key types

Defined in `crypto/keys/KeyType.kt`:

| Type | Default size | Allowed sizes | OpenSSH name |
|---|---|---|---|
| RSA | 3072 | 2048, 3072, 4096 | `ssh-rsa` |
| ECDSA | 256 | 256 (P-256), 384 (P-384), 521 (P-521) | `ecdsa-sha2-nistp{256,384,521}` |
| Ed25519 | 256 | 256 | `ssh-ed25519` |
| DSA | 2048 | 1024, 2048, 3072 | `ssh-dss` (legacy) |

### 7.2 `SSHKeyParser` and `SSHKeyGenerator`

`crypto/SSHKeyParser.kt` auto-detects and parses:
- OpenSSH v1 private keys (`-----BEGIN OPENSSH PRIVATE KEY-----`).
- PEM (PKCS#1, PKCS#8) — RSA, DSA, EC, encrypted variants.
- OpenSSH public-key lines (`ssh-rsa …`, `ecdsa-sha2-nistp256 …`, `ssh-ed25519 …`).
- PuTTY `.ppk` v2 and v3.

Encrypted keys are decrypted with the user's passphrase; OpenSSH v1 uses bcrypt KDF. BouncyCastle 1.77 is the underlying crypto provider.

`crypto/SSHKeyGenerator.kt` generates:
- RSA via `KeyPairGenerator("RSA")`.
- ECDSA via `ECGenParameterSpec` (P-256/P-384/P-521).
- Ed25519 via `KeyPairGenerator.getInstance("Ed25519")`.
- Output: PEM (encrypted if a passphrase is supplied) + OpenSSH public key.

Fingerprint helper produces SHA-256 fingerprints (and an emoji visual).

### 7.3 `KeyStorage`

`crypto/keys/KeyStorage.kt` — Android Keystore + AES-GCM:
- Two parallel storage slots per key:
  - `PREF_ENCRYPTED_KEY_PREFIX` — canonical PKCS#8 DER.
  - `PREF_JSCH_BYTES_PREFIX` — JSch-native byte format cached at import time for fast reload.
- `SSHConnection` falls back to reconstructing JSch bytes from PKCS#8 DER for legacy keys.

### 7.4 `SecurePasswordManager`

`crypto/storage/SecurePasswordManager.kt` — four storage levels:

| Level | Persistence | Notes |
|---|---|---|
| `NEVER` (0) | none | Always prompt |
| `SESSION_ONLY` (1) | in-memory | Cleared on app exit |
| `ENCRYPTED` (2, default) | Android Keystore + AES-GCM | Hardware-backed when available |
| `BIOMETRIC` (3) | AES-GCM with biometric-bound key | Requires `BiometricPrompt` to unlock |

Cipher: `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag. SharedPreferences file `tabssh_secure_storage`. Keys aliased `tabssh_password_<id>` and `tabssh_bio_password_<id>`. Configurable TTL (default 24 h).

If the Keystore is unavailable (e.g. broken ROM), the manager auto-degrades to `SESSION_ONLY`.

---

## 8. Storage and database

### 8.1 Room database

`storage/database/TabSSHDatabase.kt` — **version 17**, schema exported to `app/schemas/`.

### 8.2 Entities (12)

| Entity | Table | Notable fields | File |
|---|---|---|---|
| `ConnectionProfile` | `connections` | `id` (UUID), `name`, `host`, `port`, `username`, `authType`, `keyId`, `identityId`, `groupId`, `theme`, jump-host fields, port-knock fields, mosh, multiplexer mode, post-connect script, font override, sync metadata | `entities/ConnectionProfile.kt` |
| `StoredKey` | `stored_keys` | `keyId`, `name`, `keyType`, `fingerprint`, `requiresPassphrase`, `keySize`, sync metadata | `entities/StoredKey.kt` |
| `HostKeyEntry` | `host_keys` | `id` (`host:port`), `keyType`, `publicKey` (b64), `fingerprint`, `trustLevel` | `entities/HostKeyEntry.kt` |
| `TabSession` | `tab_sessions` | persisted terminal state for restore | `entities/TabSession.kt` |
| `ThemeDefinition` | `themes` | terminal palette + UI overrides as JSON, `usageCount` | `entities/ThemeDefinition.kt` |
| `TrustedCertificate` | `trusted_certificates` | hostname, fingerprint, PEM, issuer, validity, `trustLevel` (`SYSTEM`/`CA_SIGNED`/`USER_ACCEPTED`/`PINNED`) | `entities/TrustedCertificate.kt` |
| `SyncState` | `sync_state` | per-(`entityType`,`entityId`) sync tracking, `conflictStatus` | `entities/SyncState.kt` |
| `ConnectionGroup` | `connection_groups` | hierarchical (`parentId`), `icon`, `color`, `isCollapsed`, `sortOrder` | `entities/ConnectionGroup.kt` |
| `Snippet` | `snippets` | `command`, `category`, `tags`, `usageCount`, `isFavorite`, `{var}` placeholders | `entities/Snippet.kt` |
| `Identity` | `identities` | `username`, `authType`, `keyId`, encrypted `password` | `entities/Identity.kt` |
| `AuditLogEntry` | `audit_log` | per-event row with `eventType`, `command`, `output`, `exitCode` | `entities/AuditLogEntry.kt` |
| `HypervisorProfile` | `hypervisors` | `type` (`PROXMOX`/`XCPNG`/`VMWARE`), credentials, `realm`, `verifySsl`, `apiTypeOverride` (`auto`/`direct`/`centralized`), `linkedConnectionId` | `entities/HypervisorProfile.kt` |

### 8.3 DAOs

Twelve DAOs in `storage/database/dao/`. Notable queries:
- `ConnectionDao`: `getAllConnections()` (Flow), `getRecentConnections(limit)`, `getFrequentlyUsedConnections(limit)` (used by Frequent tab), `getUngroupedConnections()`, `searchConnections()`, `updateLastConnected()` (auto-increments connection count).
- `HypervisorDao`: `getAllHypervisors()` (Flow), `getByType()`, `updateLastConnected()`.
- `AuditLogDao`: range queries by date, by connection, by session; cleanup queries.
- All write APIs use `OnConflictStrategy.REPLACE`.

### 8.4 Migrations (`v1 → v17`)

| Step | Change |
|---|---|
| 1→2 | Add sync columns (`last_synced_at`, `sync_version`, `modified_at`, `sync_device_id`) to connections / keys / themes / host keys; create `sync_state` table with unique `(entity_type, entity_id)` |
| 2→3 | Create `connection_groups` (hierarchical, collapsible); indexes on `parent_id`, `sort_order` |
| 3→4 | Create `snippets`; indexes on category and favorites |
| 4→5 | Add proxy/jump-host fields to connections (`proxy_username`, `proxy_auth_type`, `proxy_key_id`) |
| 5→6 | Create `identities`; add `identity_id` FK to connections |
| 6→7 | Add `x11_forwarding` to connections |
| 7→8 | Create `audit_log` (FK to connections; indexes on `connection_id`, `timestamp`, `session_id`) |
| 8→9 | Add port-knock fields (`port_knock_enabled`, `port_knock_sequence` JSON, `port_knock_delay_ms`) |
| 9→10 | Create `hypervisors` |
| 10→11 | Add `use_mosh` to connections |
| 11→12 | Add `is_xen_orchestra` to hypervisors *(later deprecated, see 16→17)* |
| 12→13 | Add `multiplexer_mode` (`OFF`/`AUTO_ATTACH`/`CREATE_NEW`/`ASK`) and `multiplexer_session_name` to connections |
| 13→14 | Add `post_connect_script`, `font_size_override` |
| 14→15 | Add `linked_connection_id` to hypervisors |
| 15→16 | Add `password` (encrypted) to `identities` |
| 16→17 | Add `api_type_override` (`auto`/`direct`/`centralized`) to hypervisors — supersedes `is_xen_orchestra` |

### 8.5 Preferences

`storage/preferences/PreferenceManager.kt` wraps Android's default `SharedPreferences`. ~50 keys grouped by domain (general, security, terminal, keyboard, UI, notifications, connection defaults, multiplexer prefixes, accessibility, sync toggles, proxy). Selected non-obvious keys:

| Key | Default | Notes |
|---|---|---|
| `security_password_storage_level` | `encrypted` | one of `never`/`session_only`/`encrypted`/`biometric` |
| `security_password_ttl_hours` | 24 | TTL for encrypted passwords |
| `terminal_scrollback_lines` | 1000 | per-tab scrollback |
| `terminal_font_size` | 14.0 | sp; volume keys can adjust 8–32 |
| `volume_keys_font_size` | true | volume up/down adjusts terminal font |
| `swipe_between_tabs` | true | toggle ViewPager2 vs single TerminalView |
| `detect_urls` | true | long-press URL detection |
| `gesture_multiplexer_type` | `tmux` | `tmux`/`screen`/`zellij` |
| `multiplexer_custom_prefix_tmux` | `C-b` | bound to `_screen`/`_zellij` siblings |
| `audit_log_max_size_mb` | 100 | rolling cleanup |
| `host_log_filename_pattern` | `{user}_{host}` | configurable host log file template |

**SAF sync uses a separate file** `saf_sync_prefs` with `KEY_SYNC_URI` (persisted DocumentsProvider URI), `KEY_SYNC_PASSWORD_SET`, `sync_last_time`.

### 8.6 File storage

`storage/files/FileManager.kt` manages app-internal directories: `ssh_keys/` (mode 600 enforced), `temp/`, `downloads/`, `backups/`. Also handles temp file cleanup with TTL.

---

## 9. Sync system (SAF-based)

Package `io.github.tabssh.sync.*`. Uses Android's **Storage Access Framework** rather than embedding cloud SDKs — the user picks a target file via the system file picker and the URI is persisted with `takePersistableUriPermission`.

### 9.1 Wire format

| Offset | Bytes | Content |
|---|---|---|
| 0 | 32 | Header (`TABSSH_SYNC_V2` + padding) |
| 32 | 32 | PBKDF2 salt |
| 64 | 12 | AES-GCM IV |
| 76 | … | Ciphertext (GZIP'd JSON `SyncDataPackage`) |

GCM authentication tag is appended by the cipher (128 bits, embedded by Java's AES/GCM impl).

### 9.2 Crypto (`sync/encryption/SyncEncryptor.kt`)

- KDF: `PBKDF2WithHmacSHA256`, **100 000 iterations**, 256-bit key.
- Cipher: `AES/GCM/NoPadding`, 12-byte IV, 128-bit tag.
- Password strength validator: `WEAK` / `FAIR` / `GOOD` / `STRONG` / `VERY_STRONG` (≥12 chars and ≥3 character classes for `STRONG`).

### 9.3 `SAFSyncManager`

`sync/SAFSyncManager.kt` orchestrates upload/download:
- `setSyncPassword`, `getSyncUri`/`saveSyncUri`, `upload(SyncDataPackage)`, `download()`, `checkSyncFile()` (returns `OK`/`NOT_CONFIGURED`/`FILE_NOT_FOUND`/`NO_PERMISSION`/`READ_ONLY`/`ERROR`).
- `getSyncLocationName()` heuristically detects "Google Drive", "Dropbox", "OneDrive", "Nextcloud", "local storage" from the URI authority for the UI.

### 9.4 Data collection / application

- `sync/data/SyncDataCollector.kt` — `collectAll()` and `collectChangedSince(timestamp)` (delta sync via `modified_at`). Collects connections, keys, themes, host keys, and 6 preference categories (general, security, terminal, ui, connection, sync).
- `sync/data/SyncDataApplier.kt` — `applyAll(SyncDataPackage)` upserts rows into Room.

### 9.5 Scheduling

- `sync/worker/SyncWorker.kt` — `CoroutineWorker`; checks configuration → collect → upload; returns `Result.retry()` on transient failure.
- `sync/worker/SyncWorkScheduler.kt` — `PeriodicWorkRequest` (default 60 min flex ±15) and one-shot `OneTimeWorkRequest`. Respects `wifiOnly` constraint and the master `enabled` flag. Exponential backoff starts at 15 min.

### 9.6 Three-way merge and conflict resolution

`sync/merge/MergeEngine.kt` performs **base / local / remote** three-way merge for `ConnectionProfile`, `StoredKey`, `ThemeDefinition`, and `HostKeyEntry`. For each entity id present in either side it produces a `MergeResult<T>` with `merged`, `conflicts`, `deleted`, `added`, `updated` lists. Cases handled:

- present locally and remotely → field-level merge against base; per-field divergence becomes a `Conflict`
- present locally only → if base had it, that's a `deleted-modified` conflict; otherwise a clean local-only add
- present remotely only → mirror of the above
- absent on both → if base had it, record as deleted on both sides

`sync/merge/ConflictResolver.kt` applies user decisions. Each `ConflictResolution` carries a `ConflictResolutionOption` (keep-local / keep-remote / merge / etc.) and is dispatched per entity type (`connection` / `key` / `theme` / `host_key` / `preference`). Returns an `ApplyResolutionsResult { successCount, totalCount, errors }`. The `dialog_conflict_resolution.xml` UI is the front end.

### 9.7 Metadata and change observation

`sync/metadata/SyncMetadataManager.kt` owns sync identity, persisted in a separate `sync_metadata` SharedPreferences file:

| Key | Value |
|---|---|
| `device_id` | UUID generated on first run |
| `device_name` | derived from `Build.MANUFACTURER` + `Build.MODEL` (overridable) |
| `sync_version` | monotonically incremented |
| `last_sync_time`, `last_successful_sync` | timestamps |

It also constructs `SyncMetadata` (with `BuildConfig.VERSION_NAME`, `Build.MODEL`, item counts) attached to every uploaded `SyncDataPackage`.

`sync/observer/DatabaseChangeObserver.kt` reactively triggers sync when local data changes:
- `combine`s `getAllConnections`, `getAllKeys`, `getAllThemes`, `getAllHostKeys` flows.
- On any emission, if `sync_enabled` and `sync_on_change` are both true, schedules a **30 s debounced** `OneTimeWorkRequest<SyncWorker>` (tag `sync_on_change`). Subsequent changes within the debounce window cancel and reschedule, so a burst of edits results in one upload.
- `startObserving()` / `stopObserving()` are managed by the application's lifecycle.

### 9.8 UI

`SyncSettingsFragment` + `preferences_sync.xml` provide the file picker, password setup, manual upload/download/clear actions, frequency selector (`manual`/`15m`/`1h`/`6h`/`24h`), per-entity toggles, and sync-on-change.

---

## 10. Backup and restore

`backup/BackupManager.kt` produces a **ZIP** containing structured JSON exports — independent of the SAF sync system.

ZIP entries:
- `metadata.json` — `BACKUP_VERSION = 1`, `createdAt`, `appVersion`, `deviceModel`, `androidVersion`, item counts.
- `connections.json`, `keys.json`, `preferences.json`, `themes.json`, `certificates.json`, `host_keys.json`.

API: `createBackup(outputUri, includePasswords, encryptBackup, password)` → `BackupResult`, `restoreBackup(inputUri, password)` → `RestoreResult`, `validateBackup(uri)`. Helpers: `BackupExporter`, `BackupImporter`, `BackupValidator`.

The MainActivity drawer exposes Import/Export entries that fire SAF `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` and report restored counts in a confirmation dialog.

---

## 11. Hypervisor integration

Package `hypervisor/`. Each platform has its own client (no shared base class); a unified `HypervisorConsoleManager` routes serial-console requests to the right WebSocket implementation.

### 11.1 Proxmox VE

`hypervisor/proxmox/ProxmoxApiClient.kt` — REST.

| Operation | Endpoint |
|---|---|
| Auth | `POST /api2/json/access/ticket` (form-encoded; returns `authTicket` + `CSRFPreventionToken`) |
| List nodes | `GET /nodes` |
| List VMs | `GET /cluster/resources?type=vm` |
| Power | `POST /nodes/{node}/qemu/{vmid}/status/{start,stop,shutdown,reboot,reset}` |
| Get IP | guest agent or LXC interface query |
| **Termproxy (serial console)** | `POST /nodes/{node}/qemu/{vmid}/termproxy` → returns ticket + WS URL `wss://host:8006/api2/json/.../vncwebsocket?port=…&vncticket=…` |
| VNC proxy (graphical) | `POST /nodes/{node}/qemu/{vmid}/vncproxy` |

Realm format `user@pam` / `user@pve`. Optional SSL bypass.

### 11.2 XCP-ng / XenServer

`hypervisor/xcpng/XCPngApiClient.kt` — XML-RPC.

- Auth: `session.login_with_password(username, password)`. Detects HTML responses (Xen Orchestra fronting XCP-ng) and falls back to the XO client.
- Operations: `VM.get_all`, `VM.start`, `VM.{clean,hard}_shutdown`, `VM.{clean,hard}_reboot`, `VM.get_consoles`, `console.get_location`.
- Console: WebSocket at the location returned by `console.get_location`, fallback URL `wss://host/console?ref={consoleRef}&session_id={sessionId}`.

### 11.3 Xen Orchestra

`hypervisor/xcpng/XenOrchestraApiClient.kt` — REST + WebSocket.

- Auto-detects API version by probing `/rest/v6` → `/rest/v5` → `/rest/v0`.
- Auth: `POST /rest/vX/users/me/authentication_tokens` with HTTP Basic; the resulting token is sent as the `authenticationToken` cookie / `Authorization: Bearer …`.
- ~26 REST methods covering VMs (list/get/start/stop/restart/reset/suspend/resume), snapshots (list/create/delete/revert), backup jobs (list/run/runs/details), pools, hosts.
- **WebSocket** at `wss://host:port/api/` carries real-time events: `vm.{started,stopped,suspended,restarted,created,deleted}`, `snapshot.{created,deleted}`, `backup.completed`. Implements an `EventListener` interface that fires `onVMStateChanged`, `onVMCreated/Deleted`, `onSnapshotCreated/Deleted`, `onBackupCompleted`, `onConnectionStateChanged`, `onError`. UI shows a green ⚡ "Live Updates" indicator while connected.
- Console: `getConsoleWebSocketUrl(vmId)` queries `/rest/v0/vms/{vmId}/console`, falls back to `wss://host:port/api/console/{vmId}`.

### 11.4 VMware

`hypervisor/vmware/VMwareApiClient.kt` — REST.

- Auth: `POST /api/session` with HTTP Basic → session ID cookie.
- Operations: `GET /api/vcenter/vm`, `POST /api/vcenter/vm/{id}/power?action={start,stop,reset}`.
- Detects vCenter vs standalone ESXi by probing `/api/vcenter/datacenter`.
- **Console support: not yet implemented.**

### 11.5 `HypervisorConsoleManager` and `ConsoleWebSocketClient`

`hypervisor/console/`:
- `HypervisorConsoleManager` exposes `connectProxmoxConsole`, `connectXCPngConsole`, `connectXenOrchestraConsole`. Each returns a `ConsoleConnection` with bidirectional piped streams.
- `ConsoleWebSocketClient` handles per-protocol framing:
  - `PROXMOX_TERM`: text frames `"0:LENGTH:MSG"` (data) and `"1:COLS:ROWS:"` (resize).
  - `PROXMOX_VNC`: raw RFB bytes (not parsed; for VNC viewer integration).
  - `XCPNG`, `XO`, `VMWARE`: pass-through.
- `wireToTerminal(connection, bridge: TermuxBridge)` connects the piped streams to the terminal — same `TermuxBridge` used for SSH, so the user gets identical terminal behavior.

### 11.6 UI

- `HypervisorsFragment` (RecyclerView, FAB → `HypervisorEditActivity`, long-press for edit/delete, click → manager activity).
- `HypervisorEditActivity` + `dialog_add_hypervisor.xml` — dynamic field visibility (Proxmox shows realm, XCP-ng/VMware show API-type dropdown), default ports (Proxmox 8006, XCP-ng/VMware 443), "Import from SSH connection" pre-fill, `testConnection()` validation.
- Type-specific manager activities: `ProxmoxManagerActivity`, `XCPngManagerActivity`, `VMwareManagerActivity`. They show VM lists with power/snapshot/backup actions and route to `VMConsoleActivity` for serial console.

---

## 12. UI, theming, accessibility, i18n

### 12.1 Settings (9 preference XMLs)

`app/src/main/res/xml/`:

| File | Hosting fragment | Scope |
|---|---|---|
| `preferences_main.xml` | `SettingsMainFragment` | nav hub |
| `preferences_general.xml` | `GeneralSettingsFragment` | theme, language, behavior, notifications |
| `preferences_terminal.xml` | `TerminalSettingsFragment` | terminal theme, font, cursor, scrollback, gestures, keyboard, recording |
| `preferences_security.xml` | `SecuritySettingsFragment` | lock, biometric, host-key strict, port-knock default |
| `preferences_connection.xml` | `ConnectionSettingsFragment` | default user/port, timeouts, keep-alive, compression, mosh |
| `preferences_sync.xml` | `SyncSettingsFragment` (in `SyncSettingsActivity`) | SAF location, password, frequency, per-entity toggles |
| `preferences_audit.xml` | embedded | command auditing |
| `preferences_logging.xml` | `LoggingSettingsFragment` | debug / host / error / audit logging |
| `preferences_tasker.xml` | `TaskerSettingsFragment` | Tasker plug-in config |

### 12.2 Themes

`themes/`:
- `Theme.kt` — data class: id, name, author, isDark/isBuiltIn, terminal foreground/background/cursor/selection/highlight, 16-entry ANSI palette, Material 3 UI overrides, status/navigation bar tints.
- `BuiltInThemes.kt` — **23** built-ins: System Default / Dark / Light, Dracula, Solarized Dark/Light, Nord, One Dark, Monokai, Gruvbox Dark/Light, Tomorrow Night, GitHub Light, Atom One Dark, Material Dark, Tokyo Night/Light, Catppuccin Mocha, Rose Pine, Everforest, Kanagawa, Night Owl, Cobalt2.
- `ThemeManager.kt` — caching, current theme `StateFlow`, switching, listeners.
- `ThemeParser.kt` — JSON serialization (kotlinx.serialization), VS Code theme format compatibility, hex conversions.
- `ThemeValidator.kt` — WCAG 2.1 AA/AAA contrast ratio checks (4.5 : 1 minimum), color-blindness validation, auto-fix recommendations.

### 12.3 Accessibility

`accessibility/`:
- `AccessibilityManager.kt` — `setupTerminalAccessibility(view, buffer)` adds `READ_SCREEN` and `READ_LINE` actions, scroll announcements, connection-state announcements.
- `TalkBackHelper.kt` — strips ANSI escapes before TalkBack reads terminal output; tab/connection state descriptions; keyboard-shortcut hints.
- `HighContrastHelper.kt` — pure 16-color high-contrast palette for users who toggle `accessibility_high_contrast`.
- `KeyboardNavigationHelper.kt` — Tab / arrow-key / Enter / Escape navigation; Ctrl+T / Ctrl+W / Ctrl+Tab / Ctrl+C / Ctrl+V / F11 shortcuts; visible focus indicators.

### 12.4 Internationalization

| Locale | File | Approx. strings |
|---|---|---|
| English (default) | `res/values/strings.xml` | ~216 |
| Spanish | `res/values-es/strings.xml` | ~156 |
| French | `res/values-fr/strings.xml` | ~156 |
| German | `res/values-de/strings.xml` | ~156 |

`arrays.xml` lists Chinese and Japanese as selectable languages, but translation files are not yet present.

### 12.5 Layouts and menus

- ~83 layouts in `res/layout/` (activity / fragment / item / dialog / widget). Notable dialogs: `dialog_authentication`, `dialog_quick_connect`, `dialog_host_key_changed`, `dialog_new_host_key`, `dialog_ssh_connection_error`, `dialog_sync_password`, `dialog_conflict_resolution`, `dialog_add_hypervisor`, `dialog_vm_snapshots`, `dialog_backup_jobs`, `dialog_backup_runs`, `dialog_local_forward`, `dialog_remote_forward`, `dialog_dynamic_forward`, `dialog_edit_identity`, `dialog_edit_group`, `dialog_edit_snippet`, `dialog_bulk_edit`.
- 9 menus in `res/menu/`: `drawer_menu`, `main_menu`, `terminal_menu`, `sftp_menu`, `audit_log_menu`, `connection_edit_menu`, `log_viewer_menu`, `menu_hypervisors`, `menu_connections`.

### 12.6 Adapters and custom views

| Adapter / view | Purpose |
|---|---|
| `TerminalView` | terminal renderer |
| `PerformanceOverlayView` | draggable real-time CPU/mem/battery/network overlay |
| `ConnectionAdapter`, `GroupedConnectionAdapter` | connection list (flat / grouped) |
| `TerminalPagerAdapter`, `MainPagerAdapter` | tab pagers |
| `IdentityAdapter`, `FileAdapter`, `TransferAdapter`, `TunnelAdapter`, `SnippetAdapter`, `AuditLogAdapter`, `TranscriptAdapter`, `HypervisorAdapter`, `KeyboardKeyAdapter` | self-explanatory |

---

## 13. Notifications, services, widgets, automation

### 13.1 Notification channels

`utils/NotificationHelper.kt` creates four channels (Android 8+):

| Channel ID | Importance | Use |
|---|---|---|
| `ssh_service` | LOW | persistent foreground notification for `SSHConnectionService` |
| `ssh_connection` | DEFAULT | connect / disconnect / error events |
| `file_transfer` | LOW | SFTP progress (ongoing, BigText for completion) |
| `errors` | HIGH | actionable errors |

Per-channel toggles: `notifications_enabled`, `show_connection_notifications`, `show_error_notifications`, `show_file_transfer_notifications`, `notification_vibrate`.

### 13.2 Foreground service

`SSHConnectionService` (`services/SSHConnectionService.kt`) — `foregroundServiceType="dataSync"`, `START_STICKY`. Runs a 30-second connection-health loop, listens for `SessionManagerListener` events, updates the notification with the current connection count, auto-stops 30 s after the last connection closes.

### 13.3 Widgets

`QuickConnectWidgetProvider` (1×1 default) and `ConnectionWidgetProvider` with size variants `Widget2x1`, `Widget4x2`, `Widget4x4`. Layouts: `widget_1x1.xml`, `widget_2x1.xml`, `widget_4x2.xml`, `widget_4x4.xml`, `widget_quick_connect.xml`. Configured via `WidgetConfigurationActivity` (registered with `android.appwidget.action.APPWIDGET_CONFIGURE`).

### 13.4 Tasker plug-in

`TaskerIntentService` is exported and accepts actions `CONNECT`, `DISCONNECT`, `SEND_COMMAND`, `SEND_KEYS`. Configurable via `preferences_tasker.xml` (require unlock, allowed connections, log events, command timeout, default 30 000 ms).

---

## 14. Build and release infrastructure

### 14.1 Docker

| Image | Base | Use |
|---|---|---|
| `docker/Dockerfile` | `eclipse-temurin:17-jdk` | CI / `make build` (Android SDK 34, build-tools 34.0.0, Gradle 8.11.1 pre-cached at `/opt/gradle`) |
| `docker/Dockerfile.dev` | `debian:12-slim` | local dev (multi-platform SDK 31–34, NDK 25.2.9519653, CMake 3.22.1, Gradle 8.5, non-root `developer` UID 1000) |

Compose files: `docker/docker-compose.yml` (services: `tabssh-builder`, `tabssh-test`, `tabssh-build`, shared `gradle-cache` volume) and `docker/docker-compose.dev.yml` (interactive `tabssh-dev` shell, optional emulator).

### 14.2 Makefile

| Target | Effect | Output |
|---|---|---|
| `help` | print targets | stdout |
| `build` | clean + Docker `assembleDebug` | `./binaries/tabssh-{arch}.apk` |
| `check` | compile-only check, error-filtered | stdout |
| `clean` | remove `.gradle/`, `app/build`, `binaries` | — |
| `install` | `adb install -r` universal APK | device |
| `logs` | `adb logcat | grep TabSSH` | stream |
| `image` | build the Docker build image locally | `ghcr.io/tabssh/android:build` |

The Docker run wrapper bind-mounts the repo to `/workspace`, sets `ANDROID_HOME=/opt/android-sdk` and `GRADLE_USER_HOME=/workspace/.gradle`, and uses `--network=host`.

### 14.3 GitHub Actions

`.github/workflows/`:

| Workflow | Trigger | Job |
|---|---|---|
| `android-ci.yml` | push to `main`/`develop`, PR to `main` | structure + metadata + security + feature + docs validation |
| `development-builds.yml` | push to `main`/`master`/`devel`/`develop` | `assembleDebug`, rename APKs to `tabssh-*-dev.apk`, generate SHA-256 + release notes, publish prerelease tagged `development` |
| `release.yml` | tag `v*` | tests + `dependencyCheckAnalyze` + JaCoCo, then `assembleRelease` and `assembleFdroidRelease`, rename to versioned APKs, generate notes + checksums + mapping, create GitHub Release with 10 APKs (5 release + 5 fdroid), prepare F-Droid submission directory, run `scripts/notify-release.sh` (Matrix / Mastodon) |

Keystore is decoded from the `KEYSTORE_BASE64` secret. Gradle cache key is `${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}`.

### 14.4 Scripts

`scripts/`:
- `pre-commit-check.sh` — full Docker build validation (~6–8 min).
- `test-build.sh` — fast build smoke test.
- `clean-build.sh` — full clean rebuild.
- `install-to-device.sh` — auto-detects APK location, runs `adb install`.
- `prepare-fdroid-submission.sh` — assembles the F-Droid package.
- `notify-release.sh` — Matrix + Mastodon notifications (requires `MATRIX_TOKEN`, `MASTODON_TOKEN`).
- `check/quick-check.sh`, `check/dev-shell.sh` — error-count check, interactive Docker shell.

### 14.5 F-Droid metadata

`metadata/io.github.tabssh.yml` — categories (System / Internet / Security), MIT license, source/issue/changelog URLs, `Builds:` entry building `fdroidRelease` from tag `v1.0.0`. Description block lists feature categories.

### 14.6 ProGuard / R8

`app/proguard-rules.pro` keeps JSch, Room (DB + DAOs), AndroidX Security, Tink, model classes, terminal/SSH packages, JSR-305 annotations, and enum `valueOf`/`values`. `app/proguard-fdroid.pro` adds 5 optimization passes, `-allowaccessmodification`, removes line numbers, exports `seeds.txt` / `usage.txt` / `mapping.txt` to support deterministic F-Droid builds.

### 14.7 Dependency security

`config/dependency-check-suppressions.xml` is consumed by OWASP DependencyCheck 8.4.0 in `release.yml`; CVSS ≥ 7.0 fails the build.

---

## 15. Package map

| Package (`io.github.tabssh.…`) | Responsibility |
|---|---|
| (root) | `TabSSHApplication` |
| `accessibility` | TalkBack, high contrast, keyboard navigation |
| `audit` | `AuditLogManager` |
| `automation` | Tasker plug-in glue |
| `backup` | `BackupManager`, exporter/importer/validator |
| `crypto` | SSH key parser/generator |
| `crypto.keys` | `KeyStorage`, `KeyType` |
| `crypto.storage` | `SecurePasswordManager` |
| `hypervisor.console` | `HypervisorConsoleManager`, `ConsoleWebSocketClient` |
| `hypervisor.proxmox` | `ProxmoxApiClient`, models |
| `hypervisor.xcpng` | `XCPngApiClient`, `XenOrchestraApiClient`, models |
| `hypervisor.vmware` | `VMwareApiClient`, models |
| `notifications` | `NotificationHelper` (utility lives in `utils/`) |
| `performance` | `PerformanceManager`, `MetricsCollector`, charts feeder |
| `protocols.mosh` | Mosh framework (stub) |
| `protocols.x11` | X11 forwarding framework (stub) |
| `services` | `SSHConnectionService`, `TaskerIntentService` |
| `sftp` | `SFTPManager`, `TransferTask` |
| `ssh.config` | `SSHConfigParser` |
| `ssh.connection` | `SSHConnection`, `HostKeyVerifier` |
| `ssh.forwarding` | `PortForwardingManager` |
| `ssh.session` | `SSHSessionManager`, listeners |
| `storage.database` | `TabSSHDatabase` |
| `storage.database.dao` | DAOs |
| `storage.database.entities` | Room entities |
| `storage.files` | `FileManager` |
| `storage.preferences` | `PreferenceManager` |
| `sync` | `SAFSyncManager` |
| `sync.encryption` | `SyncEncryptor` |
| `sync.data` | `SyncDataCollector`, `SyncDataApplier` |
| `sync.merge` | `MergeEngine` (3-way merge), `ConflictResolver` (apply user decisions) |
| `sync.metadata` | `SyncMetadataManager` (device id, version, timestamps) |
| `sync.models` | `SyncDataPackage`, `SyncMetadata`, `SyncResult`, `Conflict`, `ConflictResolution`, `MergeResult`, `DeviceInfo`, `SyncItemCounts`, etc. |
| `sync.observer` | `DatabaseChangeObserver` (debounced sync-on-change) |
| `sync.worker` | `SyncWorker`, `SyncWorkScheduler` |
| `terminal` | `TermuxBridge`, `TerminalManager`, `SessionRecorder`, `TranscriptManager` |
| `terminal.emulator` | `ANSIParser`, `TerminalBuffer`, `TerminalRenderer` (legacy/secondary path) |
| `themes` | `Theme`, `ThemeManager`, `ThemeParser`, `ThemeValidator`, `BuiltInThemes` |
| `ui.activities` | 23 activities |
| `ui.adapters` | RecyclerView adapters (13) |
| `ui.fragments` | 7 fragments |
| `ui.tabs` | `TabManager`, `SSHTab` |
| `ui.views` | `TerminalView`, `PerformanceOverlayView` |
| `ui.widgets` | quick-connect / connection widgets |
| `utils` | `Logger`, `NotificationHelper`, `DialogUtils`, helpers |
| `widget` | `WidgetConfigurationActivity` |

---

## 16. Known stubs and limitations

These exist in source but are **not** wired into a working user-facing flow. Treat them as roadmap items.

- **Mosh** (`protocols/mosh/MoshConnection.kt`) — UDP session, prediction engine, and heartbeat are scaffolded; no production launch path. The `use_mosh` flag on `ConnectionProfile` is persisted (DB v11) and exposed in `ConnectionEditActivity` but does not currently switch the runtime to Mosh.
- **X11 forwarding** (`protocols/x11/X11ForwardingManager.kt`) — virtual display + Unix socket framework only; no rendering. The `x11_forwarding` flag is persisted (DB v7) and surfaced in the UI.
- **VMware console** — `VMwareApiClient` covers list / start / stop / reset only; `VMwareManagerActivity` does not yet route to `VMConsoleActivity`.
- **Frequently-used UI** — backend (`ConnectionDao.getFrequentlyUsedConnections()`) is wired; the Frequent tab works but is read-only by design.
- **Devkeystore** — `keystore.jks` is checked in for development; production releases must override `KEYSTORE_BASE64` via GitHub Secrets.
- **Connection groups in flat list** — full group infrastructure exists (`ConnectionGroup`, `ConnectionGroupDao`, `GroupedConnectionAdapter`, `GroupManagementActivity`); some list surfaces still render flat.
- **Chinese / Japanese strings** — listed in `arrays.xml` but `values-zh/` and `values-ja/` translation files are absent.
- **HypervisorProfile.isXenOrchestra** — flag is still in the schema (added in v12) but superseded by `apiTypeOverride` (added in v17). Keep both for compatibility; new code should write `apiTypeOverride` only.

---

## 17. Editing guidelines for AI agents

When modifying this codebase, prefer the following (derived from `CLAUDE.md` policies and the actual code):

1. **Don't reimplement what's there.** Termux owns the terminal emulator; JSch owns the SSH protocol; Room owns persistence; SAF owns cloud transport. New features should compose these — not replace them.
2. **Database changes must ship a migration.** Bump `TabSSHDatabase` version, add a `Migration` object, never destructive migrate. The exported schema in `app/schemas/` must be updated and committed.
3. **Sync surface is opinionated.** Anything user-visible and persisted that is *not* in `SyncDataCollector` won't sync. If you add a new entity, decide whether to sync it and update `SyncDataCollector`/`SyncDataApplier`/the wire-format docs in §9.
4. **Crypto stays at the boundary.** Don't add ad-hoc password storage — use `SecurePasswordManager`. Don't add ad-hoc key parsing — use `SSHKeyParser`. Don't add custom AES code — use `SyncEncryptor`.
5. **Activity composition over inheritance.** New screens should be activities or fragments hosted by `SettingsActivity`-style containers, not subclasses of existing activities.
6. **Use the existing notification channels.** Don't create new channels for one-off events.
7. **Keep `minSdk = 21` working.** Termux's library targets a higher SDK and is allowed via `tools:overrideLibrary`; new dependencies must respect minSdk 21 or be guarded by `Build.VERSION.SDK_INT` checks.
8. **F-Droid build must remain reproducible.** Don't introduce non-deterministic generated code, don't pull in proprietary services, don't add network-fetching Gradle plugins.
9. **Prefer `Flow` and `StateFlow`.** That's the existing reactive style; don't introduce LiveData, RxJava, or callback chains for new code.
10. **CLAUDE.md is the runbook, AI.md is the architecture.** When you change architecture, update AI.md. When you add a target/script/policy, update CLAUDE.md.
11. **Never add `Co-Authored-By` (or any attribution footer) to commit messages.** The maintainer runs Claude Code as themselves and authors every commit personally — there is no separate co-author. Adding the footer falsely implies a second contributor and pollutes git attribution. End commit bodies at the last description line, no trailer.
12. **Save commit messages to `{project_root}/.git/COMMIT_MESS`.** Project convention so the maintainer can `git commit -F .git/COMMIT_MESS`. Overwrite the file each time. Do not save to `/tmp/tabssh-android/`. Do not also paste inline (the file is the source of truth).

---

*End of AI.md. For build commands, day-to-day workflows, and policies see `CLAUDE.md`. For the public-facing feature list see `README.md`. The historical/marketing-oriented spec is preserved at `fdroid-submission/SPEC.md` (also reachable via the root `SPEC.md` symlink).*

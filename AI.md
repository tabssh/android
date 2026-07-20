# TabSSH Android — AI Project Specification

> **Audience:** AI coding assistants (Claude Code, Copilot, Gemini, etc.) and human contributors who need an accurate, code-grounded picture of how this project is actually built. **CLAUDE.md** is the operational/runbook document; this file is the architectural ground truth derived from a full source survey.
>
> **Generated:** 2026-04-25; updated 2026-05-12 from a parallel survey of ~201 Kotlin sources, all Gradle/Docker/CI configs, and every preference/layout/menu XML. Updated 2026-06-14: tab-freeze fix, swipe-lock during selection, SEL key removal, URL wrap detection, `Ctrl+` notation fix, multiplexer picker prefix fix, long-press context menu restore, clipboard menu restore.
>
> **Last verified against:** `versionCode 9` / `versionName 0.0.9`, database `v37` (full chain: v17→v18 env_vars+agent_forwarding → v19 stored_keys.certificate → v20 connections.protocol → v21 workspaces → v22 connections.color_tag → v23 cloud_accounts → v24 connections.remote_command → v25 connections.ip_mode → v26 macros → v27 hypervisor_accounts+account_id → v28 hypervisors.pinned_cert_sha256 → v29 OCI auth_type+5 OCI columns → v30 hypervisors.display_host/port → v31 connections.oci_instance_id → v32 monitor_slots → v33 OCI credentials promoted to hypervisor_accounts), JSch `mwiede:2.27.7`, Termux `terminal-emulator:0.118.1`, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1.
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
18. [QR pairing — desktop → mobile setup](#18-qr-pairing--desktop--mobile-setup)

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
│ 2.27.7  │  │ Termux emulator    │  │ 17 entities       │
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

**APK splits** (`splits.abi`): `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, plus universal. Output renamed to `tabssh-android-{arch}.apk` via the custom output naming rule in `app/build.gradle`, where `{arch}` is the simplified tag shared with the desktop client (`arm64`, `arm`, `amd64`, `x86`, `universal`). See "Unified Naming Schema" in `CLAUDE.md`.

### 3.3 Key dependencies (`app/build.gradle`)

| Category | Coordinate | Version |
|---|---|---|
| SSH | `com.github.mwiede:jsch` | 2.27.7 |
| Terminal | `com.termux.termux-app:terminal-emulator` (excludes `terminal-view`) | 0.118.1 |
| Crypto | `org.bouncycastle:bcpkix-jdk18on` | 1.79 |
| Crypto | `org.bouncycastle:bcprov-jdk18on` | 1.79 |
| HTTP/WS | `com.squareup.okhttp3:okhttp` | 4.12.0 |
| DB | `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (KSP) | 2.6.1 |
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
├── README.md, CHANGELOG.md, CLAUDE.md, TODO.AI.md, AI.md (this file), LICENSE.md
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

**Exported components:** `MainActivity` (LAUNCHER), `TaskerActionReceiver` (Tasker plug-in broadcast receiver), `ConnectionWidgetProvider` and its size-variant inner-class receivers (`Widget2x1`/`Widget4x2`/`Widget4x4`). Everything else is `exported="false"`.

### 4.3 Activities (37)

| Activity | Purpose | Notable extras |
|---|---|---|
| `MainActivity` | 5-tab connection hub (Frequent / Connections / Identities / Performance / Hypervisors) with drawer | — |
| `TabTerminalActivity` | Multi-tab SSH terminal | `EXTRA_CONNECTION_PROFILE_ID`, `EXTRA_CONNECTION_PROFILE` (JSON), `EXTRA_AUTO_CONNECT` |
| `ConnectionEditActivity` | Create/edit `ConnectionProfile` | optional `CONNECTION_ID` |
| `GroupManagementActivity` | CRUD `ConnectionGroup` (folders, nested) | — |
| `SnippetManagerActivity` | CRUD `Snippet` library | — |
| `ClusterCommandActivity` | Send command to multiple connections | — |
| `SFTPActivity` | Dual-pane SFTP browser | `EXTRA_CONNECTION_ID` |
| `PortForwardingActivity` | Local/remote/dynamic forward management | — |
| `SettingsActivity` | Hosts settings PreferenceFragments | — |
| `SyncSettingsActivity` | Hosts `SyncSettingsFragment` | — |
| `LogViewerActivity`, `AuditLogViewerActivity` | View app/audit logs | — |
| `KeyboardCustomizationActivity` | Build custom on-screen keyboard layout | — |
| `HypervisorEditActivity` | CRUD `HypervisorProfile` | — |
| `ProxmoxManagerActivity`, `XCPngManagerActivity`, `VMwareManagerActivity`, `OciManagerActivity`, `LibvirtManagerActivity` | Per-hypervisor VM/instance list & actions | hypervisor id |
| `VMConsoleActivity` | Hypervisor serial / graphical console (no SSH) | hypervisor + VM ids |
| `WidgetConfigActivity` (package `widget/`) | Configure quick-connect widgets | widget id |
| `VncHostsActivity`, `VncHostEditActivity` | CRUD VNC hosts (DB v34) and direct-VNC entry | — |
| `CloudAccountManagerActivity` | Per-cloud-account VM list and connect actions | account id |
| `ConfirmDisconnectActivity` | Transparent confirm dialog launched from notification "Disconnect" action | profile id |
| `HostDetailActivity` | Single-host live metrics + monitoring config | host id |
| `ImportExportActivity` | Master Import/Export hub (backups, SSH config import, bulk import) | — |
| `TranscriptViewerActivity` | Replay recorded sessions | transcript id |
| `CrashReportActivity` | Debug crash dump UI (debug builds) | — |
| `CloudAccountsActivity` | CRUD `CloudAccount` entities; select cloud provider (AWS / Azure / GCP / DigitalOcean / Hetzner / Linode / Vultr); tokens live in `SecurePasswordManager`, never in DB | Wave 5.1 |
| `ConnectionHistoryActivity` | Lists connections where `lastConnected > 0`, sorted most-recent-first; tap to reconnect | Wave 3.5 |
| `ImportFromQrActivity` | QR pairing inbound — camera scan, 6-digit code entry, Argon2id+AES-GCM decrypt, confirm + import; 3-attempt limit; state machine §18.7 | Wave QR |
| `MultiHostDashboardActivity` | Multi-host real-time performance dashboard; uses `MetricsCollector` per active `SSHConnection`; 5 s polling | — |
| `PinLockActivity` | App-lock PIN entry; `EXTRA_MODE` = `"set"` / `"verify"`; `MAX_ATTEMPTS = 5`; stores SHA-256 hash in `app_lock_pin_hash` pref; unconditional `FLAG_SECURE` | Wave 3.2 |
| `RemoteFileEditorActivity` | Inline text editor for remote files ≤ 1 MiB; download → `EditText` → upload via `SFTPManager`; binary-file guard (null-byte scan in first 8 KB) | Wave 1.7 |
| `ThemeEditorActivity` | Custom theme builder; live WCAG AA/AAA contrast feedback via `ThemeValidator`; imports `Theme` and `BuiltInThemes` for base selection | — |
| `WhatsNewActivity` | Reads `assets/whats_new.md` and renders in a `WebView`; accessible from Settings / About; **not** shown automatically on upgrade | Wave 3.6 |

`MainActivity` and `TabTerminalActivity` are `singleTop`. All have `parentActivityName` set for back navigation. `VMConsoleActivity` runs fullscreen (no action bar).

### 4.4 Fragments (8)

`FrequentConnectionsFragment`, `ConnectionsFragment`, `IdentitiesFragment`, `PerformanceFragment`, `HypervisorsFragment`, `ConnectionListFragment`, `CloudAccountsFragment`, `InfraFragment`. Plus `PreferenceFragmentCompat` subclasses inside `SettingsActivity`: `SettingsMainFragment`, `GeneralSettingsFragment`, `TerminalSettingsFragment`, `SecuritySettingsFragment`, `ConnectionSettingsFragment`, `LoggingSettingsFragment`, `TaskerSettingsFragment`. `SyncSettingsActivity` hosts a `PreferenceFragmentCompat` for SAF sync settings (defined inline in that activity).

### 4.5 Services and receivers

| Component | Type | Purpose |
|---|---|---|
| `SSHConnectionService` | foreground (`dataSync`) | Holds SSH sessions while app is backgrounded; `START_NOT_STICKY`; auto-stops 30 s after last connection closes |
| `TaskerActionReceiver` (`automation/TaskerActionReceiver.kt`) + `TaskerWorker` | exported `BroadcastReceiver` + `CoroutineWorker` | Tasker plug-in actions: `CONNECT`, `DISCONNECT`, `SEND_COMMAND`, `SEND_KEYS`. The receiver enqueues a `TaskerWorker` for execution. |
| `HostAvailabilityWorker` (`background/`) | `CoroutineWorker` (WorkManager) | Battery-aware background TCP probes for monitored hosts; 15 min periodic; constraints: network + not-low-battery |
| `MonitoringBootReceiver` (`background/`) | `BroadcastReceiver` | Re-schedules `HostAvailabilityWorker` on `BOOT_COMPLETED`. |
| `ConnectionWidgetProvider` (+ inner-class size variants `Widget2x1`, `Widget4x2`, `Widget4x4`) | `AppWidgetProvider` | Multi-size connection widgets — single class with inner classes per size; the 1×1 widget is the base class itself. There is no separate `QuickConnectWidgetProvider`. |
| `FileProvider` | content provider | Shares logs / transcripts via `app/src/main/res/xml/file_paths.xml` |

### 4.6 Canonical user flows

**Quick connect:** `MainActivity` (Frequent tab) → tap connection → `TabTerminalActivity.createIntent(profile, autoConnect=true)` → `SSHSessionManager.connect()` → `TermuxBridge` wired to `TerminalView`.

**New connection:** `MainActivity` → FAB → `ConnectionEditActivity` → save → returns to list.

**File transfer:** `TabTerminalActivity` menu → `SFTPActivity` (uses the existing SSH session) → upload/download via `SFTPManager`.

**Hypervisor console:** `MainActivity` (Hypervisors tab) → manager activity → VM row → `VMConsoleActivity` → `HypervisorConsoleManager` opens `ConsoleWebSocketClient` and pipes its streams into `TermuxBridge`.

### 4.7 Performance monitoring

`performance/PerformanceManager.kt` — app-scoped manager; `performance/MetricsCollector.kt` — per-connection SSH metrics collector.

**`MetricsCollector`** runs on the IO dispatcher. For each active `SSHConnection` it issues SSH exec commands (`sshConnection.executeCommand(...)`) to gather:
- **CPU:** reads `/proc/stat`; computes `CpuMetrics(userPercent, systemPercent, idlePercent, iowaitPercent, totalPercent)` by diffing successive snapshots.
- **Memory:** reads `/proc/meminfo`; produces `MemoryMetrics(totalMB, usedMB, freeMB, availableMB, buffersAndCacheMB, usedPercent)`.
- **Disk:** `df -h /` output parsed into usage percent and free bytes.
- **Network:** delta of `/proc/net/dev` counters → `NetworkStats(rxBytesPerSec, txBytesPerSec)`; `previousNetworkStats: Pair<Long, Long>` is kept between ticks.
- **Load average:** first line of `/proc/loadavg` → `loadAverage` (1/5/15 min).
- **Platform:** `uname -a` + `cat /etc/os-release` parsed into `PlatformInfo`; cached in `cachedPlatformInfo` (doesn't change per session).

All fields are wrapped in `PerformanceMetrics(timestamp, cpuUsage, memoryUsage, diskUsage, networkStats, loadAverage, platformInfo)`.

**History window:** 60 data points at 5-second intervals (5 minutes of rolling history) per connection. Consumed by `PerformanceFragment` (charts) and `PerformanceOverlayView` (draggable HUD in `TabTerminalActivity`). `MultiHostDashboardActivity` runs its own polling loop against multiple connections in parallel.

### 4.8 Background host monitoring

**Two-tier model:**

| Tier | When active | Transport | Cost |
|---|---|---|---|
| **Availability** | Always (background) | TCP connect to `host:port` (5 s timeout) | One TCP SYN per host per check — battery-friendly at hundreds of hosts |
| **Performance** | Opt-in per host (`MonitorSlot.enablePerformanceChecks`) | SSH `MetricsCollector` | Only runs if a live session already exists; never opens new SSH sessions from background |

**`HostAvailabilityWorker`** (`background/HostAvailabilityWorker.kt`) — WorkManager `CoroutineWorker`. Scheduled at 15-min intervals (Android minimum for periodic work). Constraints: `CONNECTED` network + `requiresBatteryNotLow`. Honours `PowerManager.isPowerSaveMode` and `monitoring_run_in_battery_saver` pref. Processes all enabled `MonitorSlot` rows in one Worker run (one network wake-up for N hosts, not N separate jobs). Emits notifications via `NotificationHelper.notifyHostDown/notifyHostRecovered/notifyHostStillDown`.

**`MonitorSlot`** (DB entity, v32) — per-host config + runtime state:
- `enabled`, `alertOnDown`, `alertOnRecovery`
- `cpuThreshold`, `memoryThreshold`, `diskThreshold`, `loadThreshold` (nullable — null means disabled)
- `enablePerformanceChecks` — opt-in SSH metric checks (battery trade-off)
- `checkIntervalMinutes` (desired; WorkManager enforces ≥ 15 min)
- `alertCooldownMinutes` — min gap between repeat "still down" alerts
- State: `isCurrentlyDown`, `consecutiveFailures`, `lastCheckedAt`, `lastSeenUp`, `lastNotifiedDownAt`

**Notification channels (added in v32):**
- `host_monitoring_v1` — HIGH — host down/recovery (audible + vibration)
- `host_metrics_v1` — DEFAULT — CPU/memory/disk threshold breaches (silent)

**UI entry points:**
- `MultiHostDashboardActivity` — groups (add/rename/collapse), host cards with status dot (green/red/grey), card tap → `HostDetailActivity`, card long-press or 🔔 icon → monitor config dialog
- `HostDetailActivity` — single-host live metrics (CPU/mem/disk/load/network/platform) + monitoring status + "Connect" / "Monitor settings" actions

---

## 5. SSH connection layer

Package `io.github.tabssh.ssh.*` (with `services/SSHConnectionService` for the foreground service). Built on **JSch (`com.github.mwiede:jsch:2.27.7`)** — the actively-maintained fork that supports `rsa-sha2-256`/`-512` (required by OpenSSH 8.8+).

### 5.1 `SSHConnection`

`ssh/connection/SSHConnection.kt` (~1200 lines) — the central connection lifecycle:

1. Optional **port knock** sequence before connect.
2. `JSch` session with custom `HostKeyRepository` (`HostKeyVerifier`).
3. **Jump host** support: opens an upstream SSH session and uses `setPortForwardingL` to tunnel to the real target, then connects through that tunnel.
4. **HTTP/SOCKS proxy** via `ProxyHTTP`, `ProxySOCKS4`, `ProxySOCKS5`.
5. Session config (compression, **always-on keepalive** at 60s, ciphers). Mobile-client policy: `setServerAliveInterval(60_000)` + `setServerAliveCountMax(3)` is set unconditionally regardless of profile flag. Default cipher/MAC preferences:
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

#### 5.1.1 Per-tab channels (Issue #163)

`openShellChannel()` no longer caches by Session — every call opens a fresh `ChannelShell` (or `ChannelExec` when `profile.remoteCommand` is set) on the existing JSch Session. Two tabs to the same profile thus get two independent stream pairs (real SSH multiplexing) instead of silently sharing one stream.

Supporting API on `SSHConnection`:
- `openChannels: MutableSet<Channel>` — every tab's channel against this Session.
- `closeChannel(ch)` — close one tab's channel without disturbing siblings.
- `resizePtyOf(ch, cols, rows)` — per-channel resize so opening the keyboard in tab A doesn't reflow tab B.
- `isSessionAlive()` — true if the underlying JSch Session is still up. `SSHTab.onDisconnected` uses this to distinguish "my shell exited, session is fine" (close just my channel — keep siblings alive) from "session died for everyone" (cascade so the global disconnect notification fires).

`SSHTab` tracks its own `ownChannel: Channel?`. Resize is routed through `resizePtyOf(ownChannel, ...)`. Tab close calls `closeChannel(ownChannel)` instead of disconnecting the whole Session. `disconnect()` iterates `openChannels` and closes them all.

#### 5.1.2 Tmux/Screen auto-launch + post-connect script (Issue #170)

`SSHTab.runPostConnectCommands()` runs ~500ms after the bridge wires up. Two sources, joined and sent down the shell channel as if the user typed them:

1. `profile.multiplexerMode != "OFF"` → injected one of:
   - tmux: `tmux new -A -s <name>` (AUTO_ATTACH/ASK), `tmux new -s <name>` (CREATE_NEW)
   - screen: `screen -RR <name>` (AUTO_ATTACH/ASK), `screen -S <name>` (CREATE_NEW)
   - zellij: `zellij attach --create <name>` (AUTO_ATTACH/ASK), `zellij --session <name>` (CREATE_NEW)

   Multiplexer type from the global `gesture_multiplexer_type` pref (default tmux). Session name from `profile.multiplexerSessionName` (default `tabssh`).

2. `profile.postConnectScript` lines (skipping `#`-prefixed comments and blank lines).

Both used to be defined-but-not-wired. The two flows now share one path; multiplexer command runs first, postConnectScript after.

### 5.1.3 `SSHSessionManager` — connection pool and listeners

`ssh/session/SSHSessionManager.kt` is the app-scoped registry for all active connections. It wraps `SSHConnection` so the UI never creates connections directly.

**Storage:**
- `activeConnections: ConcurrentHashMap<String, SSHConnection>` — connections currently open (keyed by `connectionId`).
- `connectionPool: ConcurrentHashMap<String, SSHConnection>` — recently-closed connections held for reuse (same key space).
- `_connectionStates: MutableStateFlow<Map<String, ConnectionState>>` — reactive state map consumed by the tab strip and `SSHConnectionService`.

**Pool double-check logic.** `createConnection(profile)` checks the pool before opening a new JSch session:
1. Look up `connectionPool[profile.id]`.
2. If found, validate via **both** `entry.state == ConnectionState.CONNECTED` AND `entry.isConnected()` (session heartbeat).
3. If both pass → reuse; if either fails → log `"Discarding stale pool entry (stateOk=…, sessionOk=…)"` and discard, then open fresh.

This double-check prevents silent reuse of a connection whose JSch session dropped (keepalive timeout, network change) while the state field still showed `CONNECTED`.

**Listener pattern.** `mutableListOf<SessionManagerListener>` holds UI observers. Events fired: `onConnectionStateChanged(id, state)`, `onConnectionError(id, error)`, `onAllConnectionsClosed()`. `SSHConnectionService` registers as a listener to update the persistent notification count. Tab strip collects `connectionStates` flow directly.

**Host key callbacks.** `hostKeyChangedCallback` and `newHostKeyCallback` are `var` properties wired to `HostKeyVerifier` (§5.2) at initialization time; they must be set before the first `createConnection()` call or host-key dialogs won't appear.

**Two connect paths — notification layer visibility:**

| Method | Starts `SSHConnectionService` | Fires `onConnectionEstablished` | Use for |
|---|---|---|---|
| `connectToServer(profile)` | ✅ yes | ✅ yes | Interactive terminal sessions (tab strip) |
| `connectForMonitoring(profile)` | ❌ no | ❌ no | Multihost dashboard, `HostAvailabilityWorker`, background metric polls |

`connectForMonitoring()` reuses an existing live session if one is already open for the profile (whether it was opened for a terminal or a prior monitoring call). If the session is new it is registered in `activeConnections` and `connectionPool` normally, but no persistent "Connected to…" notification is posted and `SSHConnectionService` is not started. This prevents Multihost dashboard probes from appearing as SSH session notifications in the shade.

### 5.2 Host key verification

`ssh/connection/HostKeyVerifier.kt` implements JSch's `HostKeyRepository`. Outcomes: `ACCEPTED`, `NEW_HOST`, `CHANGED_KEY`. Backed by `HostKeyDao` and shows `dialog_new_host_key.xml` or `dialog_host_key_changed.xml` with a SHA-256 fingerprint (and a visual "emoji fingerprint"). Trust levels persisted to the `host_keys` table: `UNKNOWN` / `ACCEPTED` / `VERIFIED`.

### 5.3 Port forwarding and proxies

`ssh/forwarding/PortForwardingManager.kt` manages local, remote, and dynamic (SOCKS) forwards. UI in `PortForwardingActivity` with three dialog layouts (`dialog_local_forward.xml`, `dialog_remote_forward.xml`, `dialog_dynamic_forward.xml`).

### 5.4 SSH config import

`ssh/config/SSHConfigParser.kt` parses `~/.ssh/config` syntax: `Host`, `HostName`, `User`, `Port`, `IdentityFile`, `ProxyJump`, `ProxyCommand`, `Compression`, `ServerAliveInterval`, `ConnectTimeout`, `Ciphers`, `Macs`. Each matched `Host` block becomes a `ConnectionProfile`.

### 5.4.1 Bulk import

`ssh/config/BulkImportParser.kt` — auto-detects and parses four formats from a single text blob or SAF-picked file:

| Format | Detection heuristic | What it extracts |
|---|---|---|
| CSV | First non-blank line contains `host` or `hostname` header (case-insensitive) | `host`, `port`, `username`, `name`, `auth_type`, `group` columns |
| JSON | Starts with `[` | Array of objects with the same field names as CSV |
| PuTTY `.reg` | First line contains `REGEDIT4` or `Windows Registry Editor` | Session entries under `[HKEY_CURRENT_USER\Software\SimonTatham\PuTTY\Sessions\...]` |
| Terraform `.tf` | Contains `resource "aws_instance"` or `resource "google_compute_instance"` | `public_ip`/`public_dns`, `connection.user`, `connection.port` from resource blocks |

Returns `ParseResult(format, hosts: List<ConnectionProfile>, warnings: List<String>)`. Unknown format returns a single-warning list with a "Supported: CSV, JSON, PuTTY .reg, Terraform .tf" message. UI entry point is `ConnectionsFragment`'s import action.

### 5.5 SFTP

`sftp/SFTPManager.kt` opens `ChannelSftp` from an existing `SSHConnection`. `TransferTask` provides progress, cancellation, and (when the server supports it) resume. Default buffer 32 KB. UI: `SFTPActivity` (dual-pane), `FileAdapter`, `TransferAdapter`. Progress also surfaces through `NotificationHelper.showFileTransferProgress` (channel `file_transfer`).

Additional SFTP capabilities:
- **Remote file editor** — inline text editor inside `SFTPActivity` for files ≤ 1 MiB. Downloads to a temp `InputStream` buffer, presents an `EditText`, uploads the modified bytes on save. Prevents opening binary files by checking for null bytes in the first 8 KB.
- **chmod** — `SFTPManager.setPermissions(path, permissions: Int)` calls `channel.chmod(permissions, path)`. `SFTPActivity` surfaces rwx checkboxes per category (owner/group/other) with a live octal display; maps to the integer before calling `setPermissions`.
- **SCP fallback** (`sftp/SCPClient.kt`) — device → server upload via `ssh remote 'scp -t /target'` for systems that serve SSH but have no SFTP subsystem. Speaks the `scp -t` wire protocol directly over `ChannelExec`. Invoked by `SFTPActivity` when `ChannelSftp` open fails with "subsystem" error.

### 5.6 Telnet

`ssh/connection/TelnetConnection.kt` — **fully implemented** RFC 854 Telnet client; **not a stub**.

Wired into `SSHConnection`'s auth path when `ConnectionProfile.protocol == Protocol.TELNET` (DB v20, migration 19→20). The `TelnetConnection` replaces `SSHConnection` in `SSHSessionManager.createConnection()` for Telnet profiles.

**Negotiation options implemented:**
- `ECHO` (option 1) — server-echo mode; client suppresses local echo when server sends `WILL ECHO`.
- `SGA` (option 3) — Suppress Go Ahead; negotiated to keep the stream half-duplex friendly.
- `TERMINAL-TYPE` (option 24) — responds with `xterm-256color` when the server requests terminal type.
- `NAWS` (option 31) — Negotiate About Window Size; sends `{cols, rows}` in network byte order when the terminal is resized.

`TelnetConnection` exposes the same `InputStream`/`OutputStream` pair as `SSHConnection`, so `TermuxBridge` wires to it identically. The IAC command parser strips Telnet control bytes from the data stream before bytes reach the terminal emulator.

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
- **Long-press URL detection** (regex matches `https?://…`, `www.…`, etc.); shows a dialog with **Open / Copy / Cancel**. URL detection runs first; if no URL is found the long-press delegates to `onContextMenuRequested`.
  - **Wrap-aware URL detection** — `detectUrlAtPosition()` walks backward through soft-wrapped rows to find the segment start, then forward to the segment end, then calls `termuxBuffer.getSelectedText(0, startRow, terminalCols, endRow)` (which joins soft-wrapped rows without `\n` natively) to build the combined string. The URL whose range covers the computed tap offset is returned; first URL in the window is the fallback. Handles URLs spanning 2+ visual rows (tapping any row of the URL works).
  - Helper `isRowSoftWrapped(r)`: for Termux buffer uses row-length heuristic (full-width row = soft-wrapped); for local `TerminalBuffer` uses `isRowWrapped()` directly.
  - Helper `buildWrappedWindowText(startRow, endRow)`: Termux path delegates to `getSelectedText()`; local buffer path manually joins with `isRowWrapped()` guard, inserting `\n` only at hard line breaks.
- **Long-press → terminal bottom sheet menu** (`showTerminalMenu()` in `TabTerminalActivity`): slides in from the bottom (always fully on-screen regardless of where the long-press occurred). Contains: New Tab, Open Tabs list, Toggle Keyboard, Toggle Key Bar, Enable/Disable Prefix Key, Find in Scrollback, Snippets, Start/Stop Recording, Broadcast, Port Forwarding, Share Session, Close Tab, Disconnect All, Settings. The **Start/Stop Recording** button (`btn_toggle_recording`) relabels itself each time the sheet opens to match the active tab's actual `sessionRecorder?.isRecording()` state, and calls the same `toggleRecording()` used by the action-bar overflow item and the keyboard's STOP_RECORDING key — so all three entry points stay in sync.
- **Text selection ActionMode** (`startTerminalSelectionActionMode`): `TYPE_FLOATING` bar with Copy / Select All / Paste / Cancel. Entered via **"Select Text…" in the clipboard menu** + drag, or double-tap, or `beginWordSelectionAtTouch()`. The ActionMode is a distinct path from the long-press menu — they do not combine. During the ActionMode `viewPager?.isUserInputEnabled = false` to prevent accidental tab swipes; restored in `onDestroyActionMode()`.
- **ViewPager2 tab-switch fix** — `onAttachedToWindow()` re-registers the `TermuxBridgeListener` and calls `requestFocus()` after ViewPager2 re-attaches the off-screen page, reversing the listener-removal that `onDetachedFromWindow()` performs. Without this, switching tabs froze the terminal (no redraws, no input).
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
- **Find-in-scrollback** — `TabTerminalActivity.showFindDialog()` opens an `AlertDialog` with an `EditText`. On submit it calls `TermuxBridge.findInScrollback(query)` which scans the Termux buffer rows for the query string (case-insensitive), highlights matches by temporarily inverting their cell colours, and scrolls to the first hit. Accessible from the terminal bottom sheet menu ("Find in Scrollback…").

**`MultiRowKeyboardView` — custom keyboard bar**

`ui/keyboard/MultiRowKeyboardView.kt` renders a 1–5 row SSH-function bar above the system keyboard. Key layout (rows shown from top):
- Row 1: Esc, Tab, Ctrl, Alt, arrow cluster (↑ ↓ ← →), Del, PgUp, PgDn, Home, End
- Row 2: F1–F12
- Row 3: PREFIX (2×), `|`, `\`, `-`, `~`, `_`, `[`, `]`, `{`, `}`, `;`, `'`, `` ` ``, `#`, `@`, `$`, `%`, `^`, `&`, `*`, `(`, `)`, CLIPBOARD (📋)
- Row 4: number row 0–9
- Row 5: customizable; alphabet row by default

Key dispatches route to `TabTerminalActivity.handleCustomKeyPress()`. Notable special keys:

| Key ID | Label | Behavior |
|---|---|---|
| `CLIPBOARD` | 📋 | Opens `showClipboardMenu()` — a three-item popup: **Paste** (reads clipboard via `coerceToText()` and calls `TerminalView.pasteText()`), **Select Text…** (arms drag-select via `armSelectionForNextDrag()` + toast), **Copy Screen** (`copyTerminalScreen()`) |
| `PREFIX` | **PRE** / **[PRE]** | Sends the current multiplexer prefix bytes to the terminal **immediately on tap** — PRE is a shortcut for physically pressing the bind (e.g. Ctrl-B), so it acts like that keypress rather than waiting for the user's next key. Label is always `"PRE"`; shows `"[PRE]"` while a lightweight visual-only latch is armed, cleared automatically on the user's next keystroke (tmux/screen is server-side waiting for that keystroke regardless of app state). Color: green when a multiplexer is detected (active state), grey when none. Second tap on PRE while armed cancels the visual latch without resending. Disabled entirely (tap is a no-op) when the user turns it off via "Disable Prefix Key" in the terminal long-press menu (`PreferenceManager.isPrefixKeyEnabled()`, default on). If no multiplexer is detected, shows `showMultiplexerPickerDialog()` with the user's **actual configured prefix** for each type (read from `PreferenceManager.getMultiplexerPrefix()` + `prefixToShortLabel()`), not hardcoded defaults. |
| `MENU` | Menu | Opens `showTerminalMenu()` bottom sheet |
| `TOGGLE` | (legacy) | Compat fallback; hides/shows the custom keyboard |
| `STOP_RECORDING` | ⏹ | Transient key appended to the far right of row 1, shown only while the active tab is recording (`MultiRowKeyboardView.setRecordingIndicatorVisible()`). Calls `toggleRecording()` to stop. Not part of the customizable/persisted layout — it's an overlay applied on top of whatever layout (default or user-edited) is currently rendered, re-applied after orientation changes, layout loads, and FN-mode exit, and never written into the saved layout JSON. `TabTerminalActivity` calls `setRecordingIndicatorVisible()` from `toggleRecording()`, `switchToTab()` (recording is per-tab), and the auto-record-on-connect path. |

**Removed keys:** `SEL` has been removed — text selection is now entered exclusively via "Select Text…" in the clipboard menu (📋 → Select Text…). The `SEL` case in `handleCustomKeyPress()` no longer exists.

**`PrefixParser` notation** (`terminal/gestures/PrefixParser.kt`): accepts `C-a`, `^A`, `Ctrl-a`, and `Ctrl+a` (both `-` and `+` separators) for Ctrl sequences; likewise `M-a`, `Alt-a`, `Alt+a` for Alt. Human-readable descriptions use `Ctrl+X` / `Alt+X` form. Hex notation `0x02` / `\x02` is also accepted.

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

Encrypted keys are decrypted with the user's passphrase; OpenSSH v1 uses bcrypt KDF. BouncyCastle 1.79 is the underlying crypto provider.

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

### 7.5 Privacy and data protection

**Screenshot prevention.** `TabSSHApplication.registerActivityLifecycleCallbacks` applies `FLAG_SECURE` globally to every window when `security_prevent_screenshots` is `true` (`PreferenceManager.KEY_PREVENT_SCREENSHOTS`). The flag is set on `onActivityStarted` and cleared on `onActivityStopped`. `PinLockActivity` additionally hard-codes `FLAG_SECURE` unconditionally — the PIN entry screen is never screenshottable regardless of the preference.

**Clipboard auto-clear.** `utils/ClipboardHelper.kt` wraps every clipboard write. After writing, it schedules a coroutine on the main thread to check whether the primary clip still matches and, if so, clear it. Delay is `security_clear_clipboard_timeout` (seconds; `0` = disabled, default). `ClipDescription.MIMETYPE_TEXT_SENSITIVE` is set so Android 13+ suppresses the clipboard content preview chip.

**PIN lock.** `ui/activities/PinLockActivity.kt` (Wave 3.2) is a separate full-screen activity for app-lock PIN management.

- `EXTRA_MODE`: `"set"` (first-time PIN creation / change) or `"verify"` (unlock gate on resume).
- `MAX_ATTEMPTS = 5`: after 5 failed verify attempts the activity finishes and the app lock state is reset to require re-entry of the PIN (effectively a lockout that requires a new PIN set).
- Storage: SHA-256 hash of the PIN stored in `app_lock_pin_hash` SharedPreferences key; enable flag at `app_lock_enabled`.
- Trigger: `security_auto_lock_background` pref triggers a `startActivity(PinLockActivity, mode=verify)` from `TabSSHApplication.registerActivityLifecycleCallbacks` when the app returns to foreground after being backgrounded longer than `security_auto_lock_timeout` seconds (default 300 s).
- `FLAG_SECURE` is set unconditionally in `PinLockActivity.onCreate()` regardless of the `security_prevent_screenshots` preference — the PIN entry screen is never screenshottable.

**In-memory password lifecycle.** `SecurePasswordManager.clearPassword(connectionId)` zeros the in-memory cache entry. Call sites:
- `SSHConnection`: called immediately on auth failure so the wrong credential is never retried silently.
- `SSHConnectionService` / `TabManager`: called when a connection's `SSHTab` is removed (connection count hits zero).
- Biometric TTL expiry path: when a `BIOMETRIC`-level password's TTL has elapsed, `clearPassword` is called before the next connect attempt forces a fresh `BiometricPrompt`.

`SESSION_ONLY` passwords are cleared when the process exits (they are never persisted). There is no explicit `Arrays.fill` zeroing of `CharArray`/`ByteArray` at present — password material is held as `String`, which the JVM GC reclaims on its own schedule.

---

## 8. Storage and database

### 8.1 Room database

`storage/database/TabSSHDatabase.kt` — **version 37**, schema exported to `app/schemas/`.

### 8.2 Entities (19)

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
| `HypervisorProfile` | `hypervisors` | `type` (`PROXMOX`/`XCPNG`/`VMWARE`/`OCI`), credentials, `realm`, `verifySsl`, `apiTypeOverride` (`auto`/`direct`/`centralized`), `linkedConnectionId`, `accountId` FK to `hypervisor_accounts`, deprecated OCI columns left in place (source of truth moved to `HypervisorAccount` in v33) | `entities/HypervisorProfile.kt` |
| `HypervisorAccount` | `hypervisor_accounts` | `id` (UUID), `name`, `username`, `realm`, `authType` (`password`/`oci_api_key`), OCI fields (`ociTenancyOcid`, `ociUserOcid`, `ociRegion`, `ociFingerprint`, `ociCompartmentOcid`). Passwords and PEM live in Keystore via `HypervisorPasswordStore` — never in DB. Added in v27; OCI columns added in v33. | `entities/HypervisorAccount.kt` |
| `Workspace` | `workspaces` | named tab groups, `connectionIds` (JSON array) | `entities/Workspace.kt` |
| `CloudAccount` | `cloud_accounts` | `provider`, `enabled`, `lastRefreshAt`, `lastCount` (token in Keystore, **not** in DB) | `entities/CloudAccount.kt` |
| `Macro` | `macros` | recordable raw byte sequence (`sequence_b64`), `usageCount` | `entities/Macro.kt` |
| `MonitorSlot` | `monitor_slots` | per-host background monitoring config + state: `enabled`, `alertOnDown/Recovery`, `cpuThreshold`, `memoryThreshold`, `diskThreshold`, `loadThreshold`, `enablePerformanceChecks`, `checkIntervalMinutes`, `alertCooldownMinutes`, `isCurrentlyDown`, `consecutiveFailures`, `lastCheckedAt`, `lastSeenUp`, `lastNotifiedDownAt` | `entities/MonitorSlot.kt` |
| `VncHost` | `vnc_hosts` | UUID `id`, `name`, `host`, `port`, `identityId`, color tag, sync metadata (added v33→34) | `entities/VncHost.kt` |
| `VncIdentity` | `vnc_identities` | UUID `id`, `name`; password stored in `SecurePasswordManager` under `vnc_identity_${id}` — never in DB (added v33→34) | `entities/VncIdentity.kt` |

### 8.3 DAOs

Fifteen DAOs in `storage/database/dao/`. Notable queries:
- `ConnectionDao`: `getAllConnections()` (Flow), `getRecentConnections(limit)`, `getFrequentlyUsedConnections(limit)` (used by Frequent tab), `getUngroupedConnections()`, `searchConnections()`, `updateLastConnected()` (auto-increments connection count).
- `HypervisorDao`: `getAllHypervisors()` (Flow), `getByType()`, `updateLastConnected()`.
- `AuditLogDao`: range queries by date, by connection, by session; cleanup queries.
- All write APIs use `OnConflictStrategy.REPLACE`.

### 8.4 Migrations (`v1 → v37`)

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
| 17→18 | Add `env_vars`, `agent_forwarding` to connections (Wave 1.2 / 1.5) |
| 18→19 | Add `certificate` to `stored_keys` (Wave 2.2 OpenSSH user certs) |
| 19→20 | Add `protocol` to connections (`SSH` / `Telnet`, Wave 2.3) |
| 20→21 | Create `workspaces` table (Wave 2.5 named tab groups) |
| 21→22 | Add `color_tag` to connections (Wave 3.1) |
| 22→23 | Create `cloud_accounts` (Wave 5.1, tokens in Keystore not DB) |
| 23→24 | Add `remote_command` to connections (Issue #37 SSH config RemoteCommand) |
| 24→25 | Add `ip_mode` to connections (`auto` / `ipv4` / `ipv6`, Issue #6) |
| 25→26 | Create `macros` table (Issue #173 recordable byte sequences) |
| 26→27 | Create `hypervisor_accounts` (reusable hypervisor credentials) + add `account_id` FK to `hypervisors` |
| 27→28 | Add `pinned_cert_sha256` to `hypervisors` (TOFU TLS pinning for hypervisor REST APIs) |
| 28→29 | Add `auth_type` discriminator (default `'password'`) + 5 nullable OCI columns to `hypervisors` (`oci_tenancy_ocid`, `oci_user_ocid`, `oci_region`, `oci_fingerprint`, `oci_compartment_ocid`) — OCI Phase 1 |
| 29→30 | Add `display_host` + `display_port` to `hypervisors` (tunnel / VPN display addresses) |
| 30→31 | Add `oci_instance_id` to `connections` (OCI SSH persistent config linking) |
| 31→32 | Create `monitor_slots` table (background host monitoring: availability + metric thresholds) |
| 32→33 | Promote OCI credentials from `hypervisors` to `hypervisor_accounts`: add `auth_type` + 5 OCI columns to `hypervisor_accounts`; for each existing OCI hypervisor row create a linked account row and set `account_id`; deprecated OCI columns on `hypervisors` left in place (SQLite < 3.35 no DROP COLUMN); Keystore entries migrate lazily on first access |
| 33→34 | Create `vnc_identities` and `vnc_hosts` tables (VNC support) |
| 34→35 | Add `ssh_identity_id` to `hypervisors` (libvirt SSH key auth) |
| 35→36 | Add `group_type` to `connection_groups` (distinguishes system auto-groups from user groups) |
| 36→37 | Add 23 performance indexes on FK and query columns across all major tables (connections, stored_keys, identities, host_keys, monitor_slots, hypervisors, vnc_hosts, connection_groups) |

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

### 8.6 Preference System Architecture

`storage/preferences/PreferenceManager.kt` is the single typed accessor for all `SharedPreferences` keys. It uses the app's default `SharedPreferences` file (plus `saf_sync_prefs` for sync state — see §8.5). All key constants are `private const val` in the companion object; external code calls typed getters/setters, never raw string keys.

Key groupings and representative defaults (compile-time constants from `PreferenceManager`):

| Group | Selected keys | Defaults |
|---|---|---|
| General | `general_startup_behavior`, `general_auto_backup`, `general_backup_frequency`, `app_language` | `last_tab`, `false`, — |
| Security | `security_password_storage_level`, `biometric_auth`, `security_password_ttl_hours`, `security_auto_lock_background`, `security_auto_lock_timeout`, `strict_host_key_checking`, `security_prevent_screenshots`, `security_clear_clipboard_timeout` | `encrypted`, `false`, `24`, `true`, `300` s, `false`, `false`, `0` |
| Terminal | `terminal_theme`, `terminal_font`, `terminal_font_size`, `terminal_line_spacing`, `terminal_cursor_style`, `terminal_cursor_blink`, `terminal_scrollback`, `terminal_word_wrap`, `terminal_copy_on_select`, `terminal_bell`, `terminal_bell_vibrate`, `terminal_bell_visual` | `dracula`, `monospace`, `14.0`, `1.0`, `block`, `true`, `1000`, `false`, `false`, `true`, `false`, `false` |
| Keyboard | `keyboard_row_count`, `keyboard_layout_json` | `2`, `null` |
| UI | `ui_max_tabs`, `ui_confirm_tab_close`, `ui_show_function_keys`, `ui_fullscreen_mode`, `keep_screen_on`, `app_theme`, `ui_dynamic_colors` | `10`, `true`, `true`, `false`, `false`, `system`, `false` |
| Notifications | `notifications_enabled`, `show_connection_notifications`, `show_error_notifications`, `show_file_transfer_notifications`, `notification_vibrate` | `true`, `true`, `true`, `true`, `false` |
| Connection defaults | `default_username`, `default_port`, `connect_timeout`, `auto_reconnect`, `compression_enabled` | `root`, `22`, `30` s, `true`, `false` |
| Accessibility | `accessibility_high_contrast`, `accessibility_large_touch_targets`, `accessibility_screen_reader` | `false`, `false`, `false` |
| Multiplexer | `multiplexer_custom_prefix_tmux`, `multiplexer_custom_prefix_screen`, `multiplexer_custom_prefix_zellij` | `C-b`, `C-a`, `C-Space` |

`migrateIntToStringPreference(KEY_KEYBOARD_ROW_COUNT)` is run on first load to handle a legacy type change.

### 8.7 File storage

`storage/files/FileManager.kt` manages app-internal directories: `ssh_keys/` (mode 600 enforced), `temp/`, `downloads/`, `backups/`. Also handles temp file cleanup with TTL.

### 8.8 `AuditLogManager`

`audit/AuditLogManager.kt` writes `AuditLogEntry` rows to Room and enforces size/age cleanup.

**Preference keys (all in default SharedPreferences):**

| Key | Default | Notes |
|---|---|---|
| `PREF_AUDIT_ENABLED` | `false` | master on/off |
| `PREF_AUDIT_MAX_SIZE_MB` | `100` | rolling max log file size |
| `PREF_AUDIT_MAX_AGE_DAYS` | `30` | entries older than this are pruned |
| `PREF_AUDIT_LOG_COMMANDS` | `false` | gate for `EVENT_COMMAND` entries |
| `PREF_AUDIT_LOG_OUTPUT` | `false` | capture terminal output per command |
| `PREF_AUDIT_AUTO_CLEANUP` | `true` | run cleanup on each app start |

**API:**

| Method | Event type written | Notes |
|---|---|---|
| `logConnect(connectionId, sessionId, success, errorMsg)` | `EVENT_AUTH_SUCCESS` or `EVENT_AUTH_FAILURE` | `errorMsg` non-null on failure |
| `logDisconnect(connectionId, sessionId, durationMs)` | `EVENT_DISCONNECT` | |
| `logCommand(connectionId, sessionId, command, output, exitCode)` | `EVENT_COMMAND` | gated by `PREF_AUDIT_LOG_COMMANDS` |

**Cleanup** (`checkAndCleanup()`): deletes rows older than `PREF_AUDIT_MAX_AGE_DAYS` first, then if the total byte size of remaining rows still exceeds `PREF_AUDIT_MAX_SIZE_MB × 1 MiB`, deletes the oldest rows in batches until under budget. Called automatically at app start when `PREF_AUDIT_AUTO_CLEANUP` is `true`.

UI: `AuditLogViewerActivity` + `AuditLogAdapter` + `audit_log_menu.xml`. `preferences_audit.xml` exposes all pref keys above.

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

- `sync/data/SyncDataCollector.kt` — `collectAll()` and `collectChangedSince(timestamp)` (delta sync via `modified_at`). Collects connections, keys, themes, host keys, **workspaces** (Wave 5.3), **snippets / identities / connection_groups** (Wave 5.4), and 6 preference categories (general, security, terminal, ui, connection, sync).
- `sync/data/SyncDataApplier.kt` — `applyAll(SyncDataPackage)` upserts rows into Room.

**Sync coverage matrix (Wave 5.4):**

| Entity | Synced? | Notes |
|---|---|---|
| `connections` | ✅ 3-way merge | full `MergeEngine` |
| `stored_keys` | ✅ 3-way merge | |
| `themes` | ✅ 3-way merge | |
| `host_keys` | ✅ 3-way merge | |
| `preferences` | ✅ | per-category |
| `workspaces` | ✅ last-write-wins | Wave 5.3 |
| `snippets` | ✅ last-write-wins | Wave 5.4 |
| `identities` | ✅ last-write-wins | Wave 5.4 |
| `connection_groups` | ✅ last-write-wins | Wave 5.4 |
| `cloud_accounts` | ✅ last-write-wins | Row synced via `collectCloudAccounts()`; token transferred in secrets map as `cloud_token_{id}` inside the AES-GCM envelope |
| `tab_sessions` | ❌ NOT synced | per-device runtime state |
| `audit_log` | ❌ NOT synced | per-device security trail |
| `trusted_certificates` | ✅ last-write-wins | Wave 7.1 |
| `hypervisor_profiles` | ✅ last-write-wins | Wave 7.1 (caveat: `id` is autogenerate Long → cross-device PK collisions could overwrite an unrelated row; users have ≤ 5 hypervisors typically so risk is low) |
| `hypervisor_accounts` | ✅ last-write-wins | 2026-05-16 audit. Row carries `name`/`username`/`realm`/timestamps; password lives in Keystore under `hypervisor_account_${id}` per device. Same autogenerate-Long PK collision caveat as `hypervisor_profiles`. |
| `macros` | ✅ last-write-wins | Wave 11 — base64 byte sequences |
| `monitor_slots` | ✅ last-write-wins | Wave 11 — full table, no `modifiedAt` delta |
| `vnc_hosts`          | ✅ last-write-wins | Wave 13 (2026-05-17) — UUID PK, no cross-device collision risk |
| `vnc_identities`     | ✅ last-write-wins | Wave 13 (2026-05-17) — metadata only; password transferred in secrets map as `vnc_identity_{id}` |
| `sync_state` | ❌ NOT synced | per-device sync bookkeeping; meaningless on another device |

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

`backup/BackupManager.kt` produces a single encrypted-or-plain `.tabssh` JSON file (current `BACKUP_VERSION = 3`) or a legacy ZIP — independent of the SAF sync system.

**Wire format v2** (written by `BackupExporter`): each entity file is `{"v":2,"items":[<kotlinx.serialization entity JSON>,...]}`. Replaces the hand-rolled per-field v1 shape that silently dropped fields.

**Entity files backed up:**
`connections.json`, `keys.json`, `themes.json`, `certificates.json`, `host_keys.json`, `identities.json`, `connection_groups.json`, `snippets.json`, `hypervisors.json`, `hypervisor_accounts.json`, `workspaces.json`, `cloud_accounts.json`, `macros.json`, `monitor_slots.json`, `vnc_hosts.json`, `vnc_identities.json`

**`preferences.json`** — hand-rolled v2 JSONObject with these categories:
- `general` — autoBackup, backupFrequency, startupBehavior, language
- `security` — passwordStorageLevel, requireBiometric, strictHostKeyChecking, clearClipboardTimeout
- `terminal` — theme, fontSize, fontFamily, cursorStyle, cursorBlink, scrollbackLines
- `ui` — maxTabs, confirmTabClose, appTheme, dynamicColors
- `notifications` — notifications_enabled, show_connection_notifications, show_error_notifications, show_file_transfer_notifications, notification_vibrate
- `monitoring` — monitoring_enabled, monitoring_run_in_battery_saver, monitoring_notify_down, monitoring_notify_recovery, monitoring_alert_cooldown_minutes, monitoring_default_cpu/memory/disk_threshold

**Secrets policy:** all credentials are always exported in `secrets.json` (`conn_pw_{id}` connection passwords via PreferenceManager; SSH private key bytes, Identity passwords, CloudAccount tokens, Hypervisor passwords, OCI PEM keys via SecurePasswordManager). The user controls whether to encrypt the backup file with a password; that is their security tradeoff.

**Tables excluded:** `tab_sessions` (runtime), `sync_state` (per-device), `audit_log` (large; export separately).

API: `createBackup(outputUri, includePasswords, encryptBackup, password)` → `BackupResult`, `restoreBackup(inputUri, password)` → `RestoreResult`, `validateBackup(uri)`. Helpers: `BackupExporter`, `BackupImporter`, `BackupValidator`.

The MainActivity drawer exposes Import/Export entries that fire SAF `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` and report restored counts in a confirmation dialog. Old v1 ZIP backups are still restorable via the `ZipInputStream` path in `BackupImporter`.

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
- **Console support: not implemented** — VMware WebMKS/VMRC is a proprietary protocol; SSH into the guest VM via `btn_ssh` is the supported path.

### 11.5 Oracle Cloud Infrastructure (OCI)

`hypervisor/oci/` — REST + RSA-SHA256 HTTP signed requests
(draft-cavage-http-signatures-08, OCI variant).

- **Auth model:** API-key only. No session tokens — `~/.oci/config`
  profiles carrying `security_token_file=` are rejected during import
  (1-hour CLI-renewable, no upload renewal path).
- **Onboarding:** Path A only — implemented inline inside
  `HypervisorEditActivity` (there is **no** standalone
  `OciOnboardingActivity`). User picks the config file via SAF, picks
  the `.pem` private key via SAF, enters the passphrase if encrypted;
  the imported key's MD5 fingerprint of `SubjectPublicKeyInfo DER`
  (formatted as colon-hex pairs) is round-tripped against the config's
  `fingerprint=` line before save.
- **Endpoints:** Identity at `https://identity.<region>.oci.oraclecloud.com`,
  Compute / Networking at `https://iaas.<region>.oraclecloud.com`. Region
  is selected from a `MaterialAutoCompleteTextView` seeded with the 34
  current commercial regions (free-text always allowed — Oracle adds
  regions quarterly).
- **Files:**
  - `OciKeyMaterial.kt` — PEM parser (PKCS#1 / PKCS#8 / encrypted),
    fingerprint computation, RSA private/public key extraction.
    Networking-free, BouncyCastle-only.
  - `OciSigner.kt` — cavage HTTP signing primitive. Builds the
    canonical `(request-target)\nhost\ndate[\nx-content-sha256\ncontent-type\ncontent-length]`
    string, signs with `SHA256withRSA`, emits
    `Authorization: Signature version="1",keyId="…",algorithm="rsa-sha256",headers="…",signature="<b64>"`.
    Exposes `asInterceptor()` for OkHttp.
  - `OciApiClient.kt` — Compute v1 client: `validateCredentials()`
    (`GET /20160918/users/{userOcid}` against Identity),
    `listInstances(compartmentOcid)`, `getInstance(id)`,
    `instanceAction(id, action)` for START / STOP / SOFTSTOP / RESET /
    SOFTRESET, `getInstancePublicIp()` via VNIC walk
    (`/vnicAttachments?instanceId=…` → `/vnics/{id}` for the primary
    VNIC). Reuses `HypervisorTrustManagerFactory` so OCI inherits
    the same TLS pinning behaviour as the other hypervisors.
  - `OciInstance.kt` — Compute Instance data class +
    `OciInstanceAction` enum.
  - `OciConfigParser.kt` — zero-dep INI parser for `~/.oci/config`,
    handles `[DEFAULT]` + named sections, rejects session-token
    profiles up front.
- **UI:** OCI onboarding is hosted **inline in `HypervisorEditActivity`**;
  `OciManagerActivity` shows the instance list with start/stop/softstop/
  reset/softreset (no console — deferred). When the user picks "OCI"
  in the type spinner the host/port/account/username/password/realm/
  api-type/ssl rows hide and a "Configure OCI credentials…" button
  expands the inline Path A picker (SAF config + SAF .pem + optional
  passphrase).
- **Secrets:** PEM private key + optional passphrase live in
  `SecurePasswordManager` under `oci_private_key_${id}` /
  `oci_passphrase_${id}`. Cleared on row delete via
  `HypervisorPasswordStore.clearOciSecrets`.
- **Out of scope (v1):** Instance Console Connection (separate
  bastion-over-SSH flow), multi-region per profile, compartment
  browser (paste OCID instead), `ListInstances` pagination, identity
  domains, anything outside Compute.

### 11.6 Libvirt / QEMU (SSH-tunneled VNC)

`hypervisor/libvirt/LibvirtApiClient.kt` — SSH-backed control plane. No HTTP API exists for libvirt; everything runs over a JSch session to the hypervisor host.

- **Auth:** Password or SSH key (`HypervisorProfile.sshIdentityId` set in DB v34→35, migration `MIGRATION_34_35`). When `sshIdentityId` is set, `getJSchBytesWithFallback()` loads JSch-native PEM bytes (reconstructing them from stored PKCS#8 DER for generated keys that pre-date the cache); the resulting bytes are added via `jsch.addIdentity(..., bytes, cert, null)`. Password is used as fallback. Credentials are validated **before** the SSH handshake so the user gets a clear error instead of opaque "Auth fail".
- **VM enumeration:** `virsh list --all` via `ChannelExec`, parsed into `LibvirtVm` records.
- **VNC discovery:** `virsh vncdisplay <domain>` returns `:N`; the client opens a JSch `direct-tcpip` channel to `127.0.0.1:(5900+N)` so the VNC stream tunnels through the SSH connection — no VNC port needs to be exposed on the hypervisor.
- **SSH fallback for no-VNC VMs:** `LibvirtApiClient.getVmIpAddress()` calls `virsh domifaddr` to discover the VM's IP. `LibvirtManagerActivity.offerSshFallback()` prompts the user, then `directSshToVm()`/`launchSshToVm()` builds a `ConnectionProfile` whose ProxyJump fields point at the hypervisor (`proxyType="SSH"`, host/port/username from `HypervisorProfile`). Key-auth hypervisors propagate `proxyKeyId`; password-auth hypervisors cache the hypervisor password `SESSION_ONLY` in `SecurePasswordManager` for the VM profile so `SSHConnection.setupJumpHost()` can retrieve it.
- **Files:** `LibvirtApiClient.kt`, `LibvirtVm.kt`. UI: `LibvirtManagerActivity` (running VMs include an inline SSH button when VNC is not configured).

### 11.7 `HypervisorConsoleManager` and `ConsoleWebSocketClient`

`hypervisor/console/`:
- `HypervisorConsoleManager` exposes `connectProxmoxConsole`, `connectXCPngConsole`, `connectXenOrchestraConsole`. Each returns a `ConsoleConnection` with bidirectional piped streams.
- `ConsoleWebSocketClient` handles per-protocol framing:
  - `PROXMOX_TERM`: text frames `"0:LENGTH:MSG"` (data) and `"1:COLS:ROWS:"` (resize).
  - `PROXMOX_VNC`: raw RFB bytes forwarded to `RfbClient` (see §11.7.1).
  - `XCPNG`, `XO`, `VMWARE`: pass-through.
- `wireToTerminal(connection, bridge: TermuxBridge)` connects the piped streams to the terminal — same `TermuxBridge` used for SSH, so the user gets identical terminal behavior.

#### 11.7.1 `RfbClient` — RFB/VNC protocol handler

`hypervisor/console/rfb/RfbClient.kt` implements the RFB 3.8 client used for graphical console display (Proxmox vncproxy, QEMU/libvirt direct-tcpip VNC).

**Event loop** (`eventLoop()`) dispatches on the first byte of each server message:

| Server message type | Value | Handler |
|---|---|---|
| `FramebufferUpdate` | 0 | `handleFramebufferUpdate()` |
| `SetColorMapEntries` | 1 | skipped (no palette mode) |
| `Bell` | 2 | `listener?.onBell()` |
| `ServerCutText` | 3 | `listener?.onClipboard(text)` |
| `ServerFence` | 248 | `handleServerFence()` |

**ServerFence (`handleServerFence()`):** QEMU and Proxmox vncproxy send a `ServerFence` (type 248) before the first `FramebufferUpdate`. The server holds its entire update queue until the client echoes a matching `ClientFence`. Wire format: 3 padding bytes + u32 flags + u8 len + `len` bytes payload. The client reply echoes the same payload with bits 0–2 of `flags` cleared (those are request-specific; the echo must not assert them). Without this reply the screen stays black forever.

**ENC_FENCE inline fence (`handleInlineFence()`):** Some servers include a pseudo-rect with encoding `ENC_FENCE` (-312 / 0xFFFFFEB8) inside a `FramebufferUpdate` rect list. Wire format (no rect header — rect x/y/w/h are always 0): u32 flags + u8 len + `len` bytes. Must be consumed immediately to keep the stream framed; the client sends a `ClientFence` reply identically to `handleServerFence()`.

**Negative-encoding guard:** `when (encoding)` in `handleFramebufferUpdate()` has an explicit `ENC_FENCE` branch. The `else` guard is `encoding > 0xFFFF || encoding < 0` — without the `< 0` arm, negative pseudo-encodings (which are valid ints < 0) would fall through to `decodeRect()` and corrupt the stream.

**Constants** (`RfbConstants.kt`):
- `ENC_FENCE = -312` (0xFFFFFEB8)
- `S2C_FENCE = 248`, `C2S_CLIENT_FENCE = 248` (same wire type for both directions)
- `ENC_EXTENDED_DESKTOP_SIZE = -308` (0xFFFFFECC)

#### 11.7.2 Direct VNC connections (`VncHost`) and background keep-alive

`VncHostsActivity`/`VncHostEditActivity` manage `VncHost` rows (`vnc_hosts` table, §8) for VNC servers reached directly (not via a hypervisor console proxy) — `VMConsoleActivity` opens a raw socket to `host:port` (or `host:5900+displayNumber`) and drives it with the same `RfbClient` used for hypervisor consoles.

- **`keepAliveInBackground`** (`VncHost.keepAliveInBackground`, default **true**): when the activity backgrounds (`onStop()`) with this on, it does not tear the session down — it `pause()`s the `RfbClient` (stops framebuffer-update requests) and hands the client + socket to the process-scoped `VncBackgroundSessionStore` singleton, then starts `VncKeepAliveService` (foreground service, holds a `PARTIAL_WAKE_LOCK` + `WifiLock`) so the OS doesn't kill the process. `onResume()` reclaims the parked session via `reattachVncHost()` if it's still in the store — no reconnect handshake, no black-screen flash. Off, the socket is closed on every background and a fresh RFB handshake runs on every return.
- **Idle-suspend timeout** — an indefinitely-parked session still burns battery/WiFi via the held locks, so `VncKeepAliveService` runs a 60 s-interval sweep (`idleSweepRunnable` → `VncBackgroundSessionStore.sweepIdle()`) that fully closes (`discardInternal`: `rfbClient.stop()` + `socket.close()`) any session parked ≥ `DEFAULT_IDLE_TIMEOUT_MS` (10 minutes). If `onResume()` finds `parkedInBackground == true` but the store no longer contains the session (it was swept), it reconnects fresh via `connectToConsole()` instead of leaving the UI stuck disconnected. The service stops itself once `VncBackgroundSessionStore.isEmpty` (every session either reclaimed or swept).
- This mirrors the pattern used for SSH background sessions (`SSHConnectionService`), but is deliberately leaner: a paused RFB client has nothing to poll at the protocol layer, so the service only needs to hold process-keepalive locks and run the idle sweep — no per-session heartbeat.
- **Not yet wired to swipeable tabs** — `VMConsoleActivity` is a standalone Activity, unrelated to the SSH `ViewPager2`/`TabManager`/`TerminalPagerAdapter` swipe system (§6.3). Mixing SSH and VNC connections in one swipeable tab strip is a scoped, not-yet-started feature (see the open design questions in project chat history: tab persistence model for VNC, multi-viewtype `TerminalPagerAdapter`, an edge-swipe carve-out for `VncView`'s pointer-forwarding touch model, and the fate of `VMConsoleActivity`/`VncHostsActivity` as standalone entry points).

### 11.8 UI

- `HypervisorsFragment` (RecyclerView, FAB → `HypervisorEditActivity`, long-press for edit/delete, click → manager activity).
- `HypervisorEditActivity` + `dialog_add_hypervisor.xml` — dynamic field visibility (Proxmox shows realm, XCP-ng/VMware show API-type dropdown, OCI hides every connection field and shows a "Configure OCI credentials…" button that expands the inline OCI onboarding picker), default ports (Proxmox 8006, XCP-ng/VMware 443), "Import from SSH connection" pre-fill, `testConnection()` validation. For OCI rows, save updates only `name` + `notes` and refuses brand-new rows (the wizard is the only entry point).
- Type-specific manager activities: `ProxmoxManagerActivity`, `XCPngManagerActivity`, `VMwareManagerActivity`, `OciManagerActivity`, `LibvirtManagerActivity`. They show VM/instance lists with power/snapshot/backup actions and route to `VMConsoleActivity` for serial console (OCI has no console — deferred). All four `HypervisorEditActivity`/manager UIs are normalized: `EXTRA_HYPERVISOR_ID`, shared `item_hypervisor_vm.xml` card, inline action buttons (state shown as colored text — Running/Stopped/Paused/Restarting; Hard Reset confirms via dialog).

### 11.9 Cloud account integration

Package `cloud/` (separate from `hypervisor/`). Manages SSH-accessible cloud VM inventories rather than hypervisor-level control. Managed via `CloudAccountsActivity` (§4.3).

**Supported providers and clients:**

| Provider | Client class | Scope |
|---|---|---|
| AWS | `AwsEc2Client` | EC2 instance list, public IP |
| Azure | `AzureVmClient` | VM list |
| GCP | `GcpComputeClient` | Compute instance list |
| DigitalOcean | `DigitalOceanClient` | Droplet list |
| Hetzner | `HetznerClient` | Server list |
| Linode | `LinodeClient` | Linode instance list |
| Vultr | `VultrClient` | VPS list |

**Secret storage pattern.** Cloud API tokens are stored in `SecurePasswordManager` under the key `cloud_token_${accountId}`. The `CloudAccount` DB entity stores only metadata (`provider`, `enabled`, `lastRefreshAt`, `lastCount`); the token is **never** written to the `cloud_accounts` table. Both the row and the token are synced: the row via `collectCloudAccounts()` and the token via the `cloud_token_{id}` entry in the AES-GCM secrets map (see §9.4).

---

## 12. UI, theming, accessibility, i18n

### 12.1 Settings (10 preference XMLs)

`app/src/main/res/xml/`:

| File | Hosting fragment | Scope |
|---|---|---|
| `preferences_main.xml` | `SettingsMainFragment` | nav hub |
| `preferences_general.xml` | `GeneralSettingsFragment` | theme, language, behavior, notifications |
| `preferences_terminal.xml` | `TerminalSettingsFragment` | terminal theme, font, cursor, scrollback, gestures, keyboard, recording — applies to SSH &amp; Mosh tabs (both run through `SSHTab`/`TabTerminalActivity`), not VNC (`VncView`, no shared prefs) |
| `preferences_security.xml` | `SecuritySettingsFragment` | app lock, biometric; SSH/Mosh host-key strict checking + port-knock default (Mosh bootstraps over SSH, so host-key prefs apply to it too) |
| `preferences_connection.xml` | `ConnectionSettingsFragment` | default user/port/timeout apply to both SSH and Mosh new-connection prefill; compression/X11/agent-forwarding are SSH-only (keep-alive is always-on at the SSH layer — no toggle) |
| `preferences_sync.xml` | `SyncSettingsFragment` (in `SyncSettingsActivity`) | SAF location, password, frequency, per-entity toggles |
| `preferences_audit.xml` | embedded | command auditing |
| `preferences_logging.xml` | `LoggingSettingsFragment` | debug / host / error / audit logging |
| `preferences_tasker.xml` | `TaskerSettingsFragment` | Tasker plug-in config |
| `preferences_monitoring.xml` | embedded | background host availability + metric-threshold monitoring |

### 12.2 Themes

`themes/`:
- `Theme.kt` — data class: id, name, author, isDark/isBuiltIn, terminal foreground/background/cursor/selection/highlight, 16-entry ANSI palette, Material 3 UI overrides, status/navigation bar tints.
- `BuiltInThemes.kt` — **23** built-ins: System Default / Dark / Light, Dracula, Solarized Dark/Light, Nord, One Dark, Monokai, Gruvbox Dark/Light, Tomorrow Night, GitHub Light, Atom One Dark, Material Dark, Tokyo Night/Light, Catppuccin Mocha, Rose Pine, Everforest, Kanagawa, Night Owl, Cobalt2.
- `ThemeManager.kt` — caching, current theme `StateFlow`, switching, listeners.
- `ThemeParser.kt` — JSON serialization (kotlinx.serialization), VS Code theme format compatibility, hex conversions.
- `ThemeValidator.kt` — validates WCAG 2.1 AA (`MIN_CONTRAST_AA = 4.5`) and AAA (`MIN_CONTRAST_AAA = 7.0`) contrast ratios for terminal text vs background, cursor vs background, and each of the 16 ANSI palette entries vs background. Returns a `ValidationResult` with `ValidationIssue` objects (`category`, `severity`, `description`, `autoFixAvailable`). Issues below AA are `severity = ERROR`; below AAA are `severity = WARNING`. Also runs color-blindness simulation (protanopia, deuteranopia, tritanopia) and flags palette entries whose simulated luminance distance is too small. Auto-fix recommendations bump foreground luminance until the target ratio is met. The `ThemeEditorActivity` surfaces issues in real time as the user edits colors.

### 12.3 Accessibility

`accessibility/`:
- `AccessibilityManager.kt` — `setupTerminalAccessibility(view, buffer)` adds `READ_SCREEN` and `READ_LINE` actions, scroll announcements, connection-state announcements.
- `TalkBackHelper.kt` — strips ANSI escapes before TalkBack reads terminal output; tab/connection state descriptions; keyboard-shortcut hints.
- `HighContrastHelper.kt` — pure 16-color high-contrast palette for users who toggle `accessibility_high_contrast`.
- `KeyboardNavigationHelper.kt` — Tab / arrow-key / Enter / Escape navigation; Ctrl+T / Ctrl+W / Ctrl+Tab / Ctrl+C / Ctrl+V / F11 shortcuts; visible focus indicators. `setupKeyboardNavigation(rootView)` installs the delegator on the root view tree so focus movement flows through every child.
- **Motor / switch-access affordances:** `accessibility_large_touch_targets` enlarges interactive hit targets. The custom on-screen keyboard (`KeyboardCustomizationActivity`) supports user-defined row count (1–5 rows, `keyboard_row_count` pref) and full key remapping persisted as JSON (`keyboard_layout_json`). Hardware-keyboard arrow keys receive modifier awareness in `TabTerminalActivity.onKeyDown` — Shift+arrows send ANSI cursor sequences, Ctrl+arrows send word-skip sequences — allowing switch-access and USB keyboard users to navigate without a mouse.
- **Contrast enforcement at runtime:** `HighContrastHelper` swaps the active theme's palette with a pure 16-color high-contrast palette when `accessibility_high_contrast` is `true`. This is applied as an overlay in `ThemeManager` after the base theme is loaded, so it works with both built-in and custom themes.

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

`utils/NotificationHelper.kt` manages all 7 channels. **All channels are created there** — `SSHConnectionService` no longer creates its own private channel.

| Channel ID | Importance | Use |
|---|---|---|
| `ssh_service_v2` | LOW | FG-service anchor + placeholder notification (`SSHConnectionService`) |
| `file_transfer_v2` | LOW | SFTP progress (ongoing, BigText for completion) |
| `errors_v2` | HIGH | actionable errors |
| `ssh_silent_v3` | LOW | per-host persistent session status (silent) — carries "Disconnect" action |
| `ssh_alerts_v3` | HIGH | per-host audible/vibrating session events (controlled by `notifSoundMode`/`notifVibrateMode`) |
| `host_monitoring_v1` | HIGH | host down/recovery alerts from background `HostAvailabilityWorker` |
| `host_metrics_v1` | DEFAULT | CPU/memory/disk threshold breach alerts (silent) |

**Notification groups** (Android 7+ notification collapsing):

| Group key | Summary ID | Covers |
|---|---|---|
| `tabssh_ssh_sessions` | 1000 | all `ssh_silent_v3` per-host notifications |
| `tabssh_monitoring` | 199999 | all `host_monitoring_v1` + `host_metrics_v1` alerts |

`SSHConnectionService.refreshAllHostNotifications()` calls `NotificationHelper.postSshGroupSummary(count)` every 30 s (and on sweep) to keep the SSH group summary accurate. Monitoring group summary is posted inline alongside each monitoring alert.

**Disconnect from notification:** every CONNECTED per-host notification carries a "Disconnect" action button. Tapping it launches `ConfirmDisconnectActivity` (transparent dialog, `Theme.TabSSH.Transparent`) which calls `SSHSessionManager.closeConnection(profileId)` on confirm.

**Multihost dashboard sessions are invisible to this layer.** `MultiHostDashboardActivity` opens connections via `SSHSessionManager.connectForMonitoring()` (§5.1.3). That path never fires `onConnectionEstablished` and never starts `SSHConnectionService`, so no per-host "Connected to…" notification is posted for monitoring-only sessions.

Per-channel toggles: `notifications_enabled`, `show_connection_notifications`, `show_error_notifications`, `show_file_transfer_notifications`, `notification_vibrate`. Master switch for monitoring: `monitoring_enabled` pref.

### 13.2 Foreground service

`SSHConnectionService` (`services/SSHConnectionService.kt`) — `foregroundServiceType="dataSync"`, `START_NOT_STICKY`. Runs a 30-second connection-health loop, listens for `SessionManagerListener` events, manages per-host + group summary notifications, auto-stops 30 s after the last connection closes.

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

**Bind-mount rules:** source tree → `/workspace`; Gradle cache and AVD state → named compose volumes. **Never** volume-mount `/opt/android-sdk` — it overlays the baked SDK. `ANDROID_HOME=/opt/android-sdk`, `GRADLE_USER_HOME=/workspace/.gradle`, `--network=host`.

### 14.2 Makefile

| Target | Effect | Output |
|---|---|---|
| `help` | print targets | stdout |
| `build` | Docker `assembleDebug` (depends on `fetch-mosh`, `fetch-fonts`) | `./binaries/tabssh-android-{arch}.apk` |
| `fetch-mosh` | Download `mosh-client` binaries from the latest GH release of the `mosh-binaries` workflow | `app/src/main/jniLibs/<abi>/libmosh-client.so` |
| `fetch-fonts` | Download Nerd Fonts (skip-if-present; `--force` to refresh) | `app/src/main/assets/fonts/` |
| `check` | compile-only check (KSP + compile, mirrors GH build), error-filtered | stdout |
| `clean` | remove `.gradle/`, `app/build`, `binaries` | — |
| `install` | `adb install -r` universal APK | device |
| `adb-reconnect` | Reconnect to phone over WireGuard (use after phone reboot) | adb |
| `logs` | `adb logcat \| grep TabSSH` | stream |
| `test` | Run UI tests on connected device/emulator (`TEST=name` or all) | test report |
| `test-install` | Build + install + run UI tests | — |
| `image` | build the Docker build image locally | `ghcr.io/tabssh/android:build` |

The Docker run wrapper bind-mounts the repo to `/workspace`, sets `ANDROID_HOME=/opt/android-sdk` and `GRADLE_USER_HOME=/workspace/.gradle`, and uses `--network=host`.

### 14.3 GitHub Actions

`.github/workflows/`:

| Workflow | Trigger | Job |
|---|---|---|
| `ci.yml` | push to `main`/`develop`, PR to `main` | structure + metadata + security + feature + docs validation |
| `dev-builds.yml` | push to `main`/`master`/`devel`/`develop` | `assembleDebug`, rename APKs to `tabssh-*-dev.apk`, generate SHA-256 + release notes, publish prerelease tagged `development` |
| `release.yml` | tag `v*` | tests + `dependencyCheckAnalyze` + JaCoCo, then `assembleRelease` and `assembleFdroidRelease`, rename to versioned APKs, generate notes + checksums + mapping, create GitHub Release with 5 release APKs (arm64/arm/amd64/x86/universal) + `mapping.txt` + checksums; `assembleFdroidRelease` is a smoke build only (F-Droid signs and publishes its own APKs, so ours are never uploaded), prepare F-Droid submission directory, run `scripts/notify-release.sh` (Matrix / Mastodon) |
| `mosh-binaries.yml` | manual / scheduled | Cross-compiles `mosh-client` binaries for `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`, packages and publishes them as a separate release the Android build consumes via `make fetch-mosh`. |

Keystore is decoded from the `KEYSTORE_BASE64` secret. Gradle cache key is `${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}`.

**`ci.yml` security validation — grep exclusion list (do not remove these):**

The security step greps `password.*=.*"[^"]{6,}"` across `app/src/main/java/` and pipes through a chain of exclusions to suppress false positives. The following exclusions are intentional and must be preserved:

| Exclusion | Reason |
|---|---|
| `grep -v "// "` | Commented-out code |
| `grep -v "test-password"` | Test fixture constants |
| `grep -v "getString"` | Reading a stored preference, not a literal |
| `grep -v "text.toString()"` | EditText value read, not a literal |
| `grep -v "fun set.*Password"` | Setter function signatures |
| `grep -v "= setString"` | SharedPreferences write helpers |
| `grep -v '\.summary = "'` | Preference summary display strings |
| `grep -v 'Password set'` | UI success message strings |
| `grep -v 'passwordIcon'` | Icon variable names |
| `grep -v 'hasPassword'` | Boolean flag names |
| `grep -v '\.hint = "'` | EditText hint strings |
| `grep -v "Logger.kt"` | Sanitization regex in the logger |
| `grep -v "Regex("` | Regex pattern definitions |
| `grep -v "JsonObject\|JsonArray\|jsonObject\|jsonArray\|Map<String"` | `BackupImporter.kt`: `val passwords: Map<String, String> = (root["passwords"] as? JsonObject)` — JSON key name, not a credential |
| `grep -v "encryptedLabel"` | Variable name containing "encrypted", not a plaintext credential |
| `grep -v '\.error = "'` | UI error label assignments (e.g. `passwordLayout?.error = "Wrong password…"`) — not credential literals |

If a new file triggers a false positive, add a targeted `grep -v` to the chain and document it here with the reason.

### 14.4 Scripts

`scripts/`:
- `pre-commit-check.sh` — full Docker build validation (~6–8 min).
- `clean-build.sh` — full clean rebuild.
- `install-to-device.sh` — auto-detects APK location, runs `adb install`.
- `prepare-fdroid-submission.sh` — assembles the F-Droid package.
- `notify-release.sh` — Matrix + Mastodon notifications (requires `MATRIX_TOKEN`, `MASTODON_TOKEN`).
- `dev-shell.sh` — interactive Docker shell for manual gradle invocations. Use `make check` for the fast error-count instead.
- `generate-keystore.sh` — recreate the dev signing keystore (one-time).
- `start-test-sshd.sh` — bring up the `tabssh/test-sshd` container (image lives at `docker/test-sshd/`); writes creds + keypair to `/tmp/tabssh-android/sshd/`.
- `android-emulator.sh` — headless test-emulator manager. **One AVD per
  (type, size); one running emulator at a time.** Subcommands `start` /
  `stop` / `delete` / `clean` / `list`; types `phone` / `tablet` / `fold`
  / `tv` with optional `small` / `large` size. Auto-installs missing SDK
  pieces via `sdkmanager`. Pins `-port` + `adb -s emulator-PORT` so
  boot-wait can't attach to a stale instance. See CLAUDE.md §"Test
  Emulators" for the full runbook.

### 14.5 F-Droid metadata

`metadata/io.github.tabssh.yml` — categories (System / Internet / Security), MIT license, source/issue/changelog URLs, `Builds:` entry building `fdroidRelease` from tag `v0.0.9`. Description block lists feature categories.

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
| `hypervisor.console` | `HypervisorConsoleManager`, `ConsoleWebSocketClient`, `RfbClient` (under `hypervisor.console.rfb`) |
| `hypervisor.proxmox` | `ProxmoxApiClient`, models |
| `hypervisor.xcpng` | `XCPngApiClient`, `XenOrchestraApiClient`, models |
| `hypervisor.vmware` | `VMwareApiClient`, models |
| `hypervisor.libvirt` | `LibvirtApiClient` (SSH-tunneled VNC + virsh), `LibvirtVm` |
| `hypervisor.oci` | `OciApiClient`, `OciSigner`, `OciKeyMaterial`, `OciConfigParser`, `OciInstance` |
| `hypervisor.vnc` | `VncDirectConnector`, `VncStreamHolder` (direct-VNC entry points + framebuffer holder) |
| `performance` | `PerformanceManager`, `MetricsCollector`, charts feeder |
| `protocols.mosh` | Mosh native client glue (`MoshHandoff`, `MoshNativeClient`, `TermuxMoshLauncher`) — fully wired |
| `services` | `SSHConnectionService`, `TaskerIntentService` |
| `sftp` | `SFTPManager`, `TransferTask` |
| `ssh.config` | `SSHConfigParser` |
| `ssh.connection` | `SSHConnection`, `HostKeyVerifier`, `SSHSessionManager` and listeners, `TelnetConnection` (RFC 854; fully implemented — not a stub) |
| `ssh.forwarding` | `PortForwardingManager`, `X11Proxy`, `HttpPortProbe` |
| `ssh.auth` | Auth type definitions (`AuthType` enum) |
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
| `cloud` | `CloudAccountsActivity`, cloud provider clients (Aws/Azure/Gcp/DigitalOcean/Hetzner/Linode/Vultr) |
| `ui.activities` | 36 activities (one additional, `WidgetConfigActivity`, lives in the root `widget/` package) |
| `ui.adapters` | RecyclerView adapters |
| `ui.dialogs` | Reusable dialog builders / fragments |
| `ui.fragments` | Fragments (8) |
| `ui.keyboard` | Custom on-screen keyboard widgets |
| `ui.models` | UI-only view-model state classes |
| `ui.tabs` | `TabManager`, `SSHTab` |
| `ui.utils` | UI-scoped helpers |
| `ui.views` | `TerminalView`, `PerformanceOverlayView` |
| `ui.widget` | UI widget helpers (note: app-widget receivers live in the root `widget/` package) |
| `utils` | `Logger`, `NotificationHelper`, `DialogUtils`, `ClipboardHelper`, `ActivityExtensions`, `FontManager`, `AnrWatchdog`, `ValidationHelper`, helpers |
| `widget` | `ConnectionWidgetProvider` (+ inner-class size variants) and `WidgetConfigActivity` |
| `background` | `HostAvailabilityWorker`, `MonitoringBootReceiver`, `BatteryOptimizationHelper`, `SessionPersistenceManager` |
| `cluster` | Cluster-command session fan-out (`ClusterCommandActivity` backend) |
| `network` | Low-level networking helpers used by hypervisor/SSH layers |
| `pairing` | QR pairing inbound (camera, decode, decrypt, import) |
| `platform` | Android platform-version shims |

---

## 16. Known stubs and limitations

These exist in source but are **not** wired into a working user-facing flow. Treat them as roadmap items.

- **Mosh** — fully wired. `MoshHandoff.kt` bootstraps via an SSH exec channel (`mosh-server` on the remote), parses the `MOSH CONNECT <port> <key>` response, then calls `TermuxBridge.connectMoshClient()` which launches the bundled native `mosh-client` binary through `TerminalSession` (JNI `forkpty()`). The binary handles all UDP/SSP/AES-128-OCB transport natively — no user action required. The `use_mosh` flag on `ConnectionProfile` (DB v11) switches the connection path in `SSHTab`. `MoshConnection.kt` scaffolding is superseded by this architecture.
- **X11 forwarding** — fully wired via `ssh/forwarding/X11Proxy.kt`. The proxy binds an ephemeral `localhost` port, JSch X11 channels are routed through it to either Termux:X11 (Unix socket) or XServer XSDL (TCP `:6000`). `SSHConnection.applyForwardingFlags()` passes the dynamic port via `session.setX11Port(proxy.port)`; the proxy is stopped in `disconnect()`. Non-fatal `X11NoServerException` surfaces in `TabTerminalActivity` as a Snackbar via the `SSHConnection.warnings` `SharedFlow`. The `x11_forwarding` flag is persisted (DB v7).
- **Frequently-used UI** — fully interactive: top-10 connections loaded, tap to connect, swipe-right for delete/duplicate/edit actions.
- **Devkeystore** — `keystore.jks` is checked in for development; production releases must override `KEYSTORE_BASE64` via GitHub Secrets.
- **Note — Telnet is NOT a stub.** `TelnetConnection` (§5.6) is fully implemented (RFC 854, ECHO/SGA/TERMINAL-TYPE/NAWS) and wired into `SSHSessionManager` for `protocol == TELNET` profiles. Do not treat it as unfinished.

---

## 17. Editing guidelines for AI agents

### 17.0 Navigating this spec

| You need to know about… | Read section |
|---|---|
| App identity, design pillars, SDK versions | §1 |
| High-level architecture diagram, threading model, state propagation | §2 |
| Build types, APK variants, ABI→arch mapping, all dependencies | §3 |
| Activities, fragments, services, canonical user flows | §4 |
| SSH connection lifecycle, jump hosts, auth, keepalive, per-tab channels | §5.1 |
| Host key verification (TOFU dialog, trust levels) | §5.2 |
| Port forwarding and proxies | §5.3 |
| `~/.ssh/config` import | §5.4 |
| Bulk import (CSV / JSON / PuTTY .reg / Terraform) | §5.4.1 |
| SFTP, remote file editor, chmod, SCP fallback | §5.5 |
| Terminal emulator (`TermuxBridge`, `TerminalView`) | §6.1–6.2 |
| Tab management (`TabManager`, `SSHTab`), keyboard shortcuts | §6.3 |
| On-screen keyboard, gesture bindings, find-in-scrollback | §6.4 |
| Session recording and replay | §6.5 |
| SSH key types, parsing, generation, fingerprints | §7.1–7.2 |
| Key storage (Android Keystore, AES-GCM) | §7.3 |
| Password storage levels, biometric unlock, TTL | §7.4 |
| Screenshot protection, clipboard auto-clear, password lifecycle | §7.5 |
| Room database version, full migration chain | §8.1–8.4 |
| All 19 entities and their notable fields | §8.2 |
| Preference keys and defaults by category | §8.6 |
| SAF sync wire format, encryption, 3-way merge, conflict resolution | §9 |
| Sync coverage matrix (what syncs, what doesn't, and why) | §9.4 |
| Backup/restore ZIP format | §10 |
| Proxmox / XCP-ng / Xen Orchestra / VMware / OCI / libvirt-QEMU APIs | §11 |
| Hypervisor console WebSocket framing | §11.6 |
| Settings XML files and hosting fragments | §12.1 |
| Themes (23 built-ins, `Theme.kt` fields, contrast validation) | §12.2 |
| Accessibility (TalkBack, high-contrast, keyboard nav, motor) | §12.3 |
| Notification channels | §13.1 |
| Build targets (`make` commands), Docker setup | §14.1–14.2 |
| CI workflows (ci, dev-builds, release) | §14.3 |
| ProGuard / R8 keep rules | §14.6 |
| Full package map (`io.github.tabssh.*`) | §15 |
| Known stubs and unimplemented features | §16 |
| Rules for AI agents editing this codebase | §17 |
| QR pairing wire format, encryption, mobile implementation status | §18 |

### 17.1 Build commands

| Goal | Command |
|---|---|
| Compile-only check | `make check` (~2 min cached) |
| Debug APKs → `./binaries/` | `make build` (~5 min cached) |
| Install on device | `make install` |
| Tail logcat | `make logs` |
| Run UI tests | `make test` |
| Clean artifacts | `make clean` |

Production releases are produced by the `release.yml` GitHub Actions workflow on tag push (`v*`) — there is no local `make release` target.

### 17.2 Artifact locations

| Artifact | Path |
|---|---|
| Debug APKs | `./binaries/` |
| Release APKs | `./releases/` |
| All temp files | `/tmp/tabssh-android/` |
| Room schemas | `app/schemas/` |

### 17.3 Code editing rules

When modifying this codebase follow these rules:

0. **Comment placement.** Comments go **above** the code they describe — never appended inline at the end of a code line. Single-line, ≤ 180 characters. Exception: tool-required directives (`// nolint`, `@Suppress`) that must sit on the same line are allowed inline, but an explanatory comment above is still required when the reason is not obvious. **Never** add `TODO`, `FIXME`, or `HACK` to committed code — resolve the issue before committing or track it in `TODO.AI.md`.

1. **Don't reimplement what's there.** Termux owns the terminal emulator; JSch owns the SSH protocol; Room owns persistence; SAF owns cloud transport. New features should compose these — not replace them.
2. **Database changes must ship a migration.** Steps in order: (1) bump `TabSSHDatabase` `version` constant; (2) add a `Migration` object for the new version step; (3) register it in the `databaseBuilder` migration chain; (4) run `make check` to trigger schema export; (5) commit the updated `app/schemas/` JSON. Never destructive-migrate — SQLite < 3.35 does not support `DROP COLUMN`; rename by adding a column, migrating data, and leaving the old column.
3. **Sync surface is opinionated.** Anything user-visible and persisted that is *not* in `SyncDataCollector` won't sync. If you add a new entity, decide whether to sync it and update `SyncDataCollector`, `SyncDataApplier`, and the sync coverage matrix in §9.4.
4. **Crypto stays at the boundary.** Don't add ad-hoc password storage — use `SecurePasswordManager`. Don't add ad-hoc key parsing — use `SSHKeyParser`. Don't add custom AES code — use `SyncEncryptor`. The canonical credential storage locations are:

   | Credential type | Storage |
   |---|---|
   | SSH session passwords | `SecurePasswordManager` (Keystore AES-GCM) |
   | SSH key passphrases | `SecurePasswordManager` |
   | Hypervisor inline passwords | `HypervisorPasswordStore` |
   | Hypervisor account passwords | `HypervisorPasswordStore.storeAccountPassword()` |
   | OCI PEM private key | `HypervisorPasswordStore.storeOciAccountKey()` — alias `oci_private_key_${accountId}` |
   | OCI key passphrase | `HypervisorPasswordStore.storeOciAccountPassphrase()` — alias `oci_passphrase_${accountId}` |
   | Cloud provider tokens | `SecurePasswordManager`, key `cloud_token_${accountId}` |

   **Never** write any of the above to the Room database — DB password columns must always be empty strings.

5. **Threading discipline.** `lifecycleScope.launch {}` defaults to `Dispatchers.Main` — never call Keystore, database, or filesystem ops inside a bare launch. `Room` suspend DAOs switch dispatchers automatically; `SecurePasswordManager.retrievePassword()` does **not** — always wrap it in `withContext(Dispatchers.IO)`. All `HypervisorPasswordStore` store/retrieve methods do switch internally. SAF launcher callbacks fire on Main; capture `context` before launching IO and guard `withContext(Dispatchers.Main)` blocks with `if (!isAdded) return@withContext`.
6. **Activity composition over inheritance.** New screens should be activities or fragments hosted by `SettingsActivity`-style containers, not subclasses of existing activities.
7. **Use the existing notification channels.** Don't create new channels for one-off events.
8. **Keep `minSdk = 21` working.** Termux's library targets a higher SDK and is allowed via `tools:overrideLibrary`; new dependencies must respect minSdk 21 or be guarded by `Build.VERSION.SDK_INT` checks.
9. **F-Droid build must remain reproducible.** Don't introduce non-deterministic generated code, don't pull in proprietary services, don't add network-fetching Gradle plugins.
10. **Prefer `Flow` and `StateFlow`.** That's the existing reactive style; don't introduce LiveData, RxJava, or callback chains for new code.
11. **AI.md is the architecture, CLAUDE.md is a short loader.** When you change architecture, update AI.md. When you add a dev rule, add it here in §17. Never add spec content to CLAUDE.md.
12. **Docker build quirk.** Do not volume-mount `/opt/android-sdk` — that overlays the baked SDK in the build container. The correct bind-mounts are: source tree → `/workspace`, Gradle cache and AVD state → named compose volumes. The device is on a remote server; ADB and logcat are unavailable. All debugging is static analysis only.
13. **Paste service quirk (MicroBin).** `mb.pste.us` and any self-hosted MicroBin instance return HTTP 404 on raw-paste URLs but still deliver the paste body. Never use `curl -f` for MicroBin URLs — it discards the body on 404. Always: `curl -qLs "https://mb.pste.us/raw/<id>"`.
14. **Never add `Co-Authored-By` (or any attribution footer) to commit messages.** End commit bodies at the last description line, no trailer.
15. **Commit workflow — pre-commit sequence (required on every commit).**

    1. `git status --porcelain` + `git diff --stat` — see exactly what changed.
    2. **Run `make check`** — compile + lint; never commit with violations or compile errors. This is the mandatory pre-commit gate (overrides the global `make test` rule; see `SPEC.md`).
    3. **Changelog gate** — for any commit that touches user-visible behaviour, confirm both `CHANGELOG.md` and `app/src/main/assets/whats_new.md` are staged (`git diff --stat` must list them); if either is absent, update it before continuing (see rule 17).
    4. Write `.git/COMMIT_MESS` from the `git diff --stat` output — describe every changed file; never write from memory.
    5. Re-read `COMMIT_MESS` and compare against the diff — rewrite if anything is missing or wrong.
    6. Run `gitcommit --dir {dir} all` — the only valid commit path; never bare `git commit` or `-m`.

    **`COMMIT_MESS` format:** `{emoji} Title (≤64 chars) {emoji}` + blank line + body + `- path: change` bullets per file. Emoji map: ✨ feat · 🐛 fix · 📝 docs · 🎨 style · ♻️ refactor · ⚡ perf · ✅ test · 🔧 chore · 🔒 security · 🗑️ remove · 🚀 deploy · 📦 deps. No bare `@` handles in the commit body. One logical change per commit.

    Save `COMMIT_MESS` to `{project_root}/.git/COMMIT_MESS` — never to `/tmp/tabssh-android/`.
16. **Downscale screenshots before reading them.** Android screenshots are 1080×2400+. Downscale first: `python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png`.
17. **Changelog hygiene — required on every commit.** Every commit that changes user-visible behaviour **MUST** update BOTH files in the same commit (never stale, never a separate follow-up):

    | File | Format | Audience |
    |------|--------|----------|
    | `CHANGELOG.md` | Keep-a-Changelog, `[Unreleased]` section | Developers, release notes |
    | `app/src/main/assets/whats_new.md` | Wave-numbered prose, user-facing | In-app "What's New" screen |

    - `CHANGELOG.md [Unreleased]` gets one bullet per logical change under **Added / Changed / Fixed**.
    - `whats_new.md` gets a new **Wave N** section (increment from the previous highest wave number) covering the most user-visible features in plain language. Skip internal refactors and CI changes.
    - Both files must be staged in the COMMIT_MESS diff. If the diff touches only code and not these files, the commit is incomplete.

### 17.4 Terminology

Use these terms consistently across code, comments, commit messages, and bug reports. Never substitute synonyms.

| Term | Definition |
|---|---|
| **Tab** | A single SSH session slot in the ViewPager2 tab strip. Each tab owns one `SSHConnection`, one `TermuxBridge`, and one `TerminalView`. |
| **Terminal / Terminal view** | The custom `TerminalView` canvas widget that renders the VT100/ANSI output for one tab. |
| **Session** | The live SSH connection for a tab — `SSHConnection` + `TermuxBridge` together. A tab has at most one session at a time. |
| **Keyboard bar / key bar** | The `MultiRowKeyboardView` strip of extra SSH keys (Esc, Tab, Ctrl, arrows, PRE, 📋 …) shown above the system keyboard. |
| **Clipboard menu** | The three-item popup opened by the 📋 key on the keyboard bar: Paste · Select Text… · Copy Screen. |
| **Selection mode / text selection** | The `TYPE_FLOATING` ActionMode (Copy / Select All / Paste / Cancel) entered via "Select Text…" in the clipboard menu or by double-tapping a word. |
| **Context menu / terminal menu** | The bottom-sheet menu (`showTerminalMenu()`) summoned by a long-press on the terminal when no URL is detected. Contains: New Tab, Open Tabs, Toggle Keyboard, Toggle Key Bar, Find in Scrollback, Snippets, Broadcast, Port Forwarding, Share Session, Close Tab, Disconnect All, Settings. |

---

## 18. QR pairing — desktop → mobile setup

**Status:** Mobile side shipped 2026-04-28; desktop side WIP (tracked in `tabssh/desktop`). The same content lives at `tabssh/desktop/QR_PAIRING.md` so the desktop project can reference the wire format without cloning android.
**Touches:** `tabssh/android` (this repo, Kotlin) + `tabssh/desktop` (Rust desktop app).

### 18.1 Goal

Make it easy to add an existing TabSSH connection from a desktop install to a phone without retyping. The friction we're solving:

- New phone, no existing sync set up.
- "Just got this server working on my laptop, want it on my phone for tomorrow's flight."
- Set up a colleague's phone for shared infra fast.

### 18.2 Flow

1. User opens TabSSH on **desktop** → menu → **Pair Phone…**
2. Desktop shows a modal with a checklist of current connection profiles. User picks which to send. On confirm, desktop renders a QR + a **6-digit code** + a 60-second countdown.
3. User opens TabSSH on **phone** → drawer menu → **Pair from desktop…**
4. Phone opens a camera preview, scans the QR.
5. Phone prompts the user for the 6-digit code shown on the desktop.
6. Phone shows a preview: *"Import N connections from {device_label}?"*.
7. User confirms → connections appear in the phone's list.

### 18.3 Non-goals (v1)

- ❌ Bidirectional pairing (mobile → desktop). Direction matters because desktop has filesystem write access for `~/.ssh/authorized_keys`; phones don't.
- ❌ Continuous sync. Use SAF-based cloud sync for that — it's already shipped (§9).
- ❌ Private key transfer. Phone generates its own keypair locally; desktop handles `authorized_keys` provisioning out-of-band.
- ❌ Multi-frame animated QR. Single-frame only in v1.

### 18.4 Data model

The QR payload, after decryption, is a CBOR-encoded `PairingPayload`:

```
PairingPayload {
  version: u8                    // 1
  expires_at: u64                // unix epoch seconds, ~60s after generation
  device_label: Option<String>   // "Alice's Linux desktop"
  connections: [ConnectionProfile]
  groups: [Group]                // optional, only those referenced by connections
  identities: [Identity]         // optional, only those referenced by connections
}

ConnectionProfile {
  name: String
  host: String
  port: u16
  username: String
  protocol: enum { SSH, TELNET }
  auth_type: enum { PASSWORD, PUBLIC_KEY, KEYBOARD_INTERACTIVE }
  // NO password, NO private key. Public-key fingerprint + comment only.
  ssh_key_public: Option<String>       // OpenSSH-format `ssh-ed25519 AAAA…` line
  ssh_key_fingerprint: Option<String>  // SHA-256 fingerprint for verification
  // Cosmetic + behavioral fields:
  color_tag: Option<u32>
  group_name: Option<String>
  identity_name: Option<String>
  terminal_type: String
  compression: bool
  keep_alive: u32
  env_vars: Option<String>             // multi-line KEY=value
  post_connect_script: Option<String>
  use_mosh: bool
  agent_forwarding: bool
  x11_forwarding: bool
  // Jump host config (no jump-host secrets either):
  proxy_host: Option<String>
  proxy_port: Option<u16>
  proxy_username: Option<String>
  proxy_auth_type: Option<String>
}
```

**CBOR over JSON** — ~30 % smaller, matters near the QR capacity ceiling. Use `ciborium` on the Rust side and a hand-written codec on the Android side (`pairing/QrPayloadCodec.kt`).

#### Capacity reality

QR Code at error-correction level **L** (max capacity):
- Alphanumeric mode: 4,296 chars
- Binary mode: 2,953 bytes

Typical encrypted-payload sizes:

| Content | Bytes (after AES-GCM + base64) |
|---------|-------|
| 1 connection, no key | ~250 |
| 1 connection + Ed25519 public key | ~400 |
| 5 connections, no keys | ~700 |
| 5 connections + 5 Ed25519 public keys | ~1,400 |
| 10 connections + RSA-4096 public keys | ~2,800 (close to ceiling) |

Cap v1 at 10 connections per QR.

### 18.5 Encryption

Threat model:

| Threat | Likelihood |
|--------|-----------|
| QR photographed by anyone with line-of-sight to the desktop screen | **HIGH — assume QR is public** |
| Brute-force of the 6-digit code (1M possible values) | High if the QR is captured |
| Replay across sessions | Low — TTL caps risk |
| Camera shoulder-surf (the 6-digit code) | Medium — same threat as 2FA codes; accepted |
| MITM on the QR scan | Effectively zero — phone reads from screen directly |

Scheme:

1. Desktop generates: 6-digit numeric **code**, 16-byte random **salt**, 12-byte random **nonce**.
2. Derive a 32-byte symmetric key: `key = Argon2id(password=code, salt=salt, m=64MiB, t=3, p=1)`.
3. Encrypt the CBOR-encoded `PairingPayload`: `ciphertext = AES-256-GCM(key, nonce, plaintext)`.
4. Build a CBOR-encoded `QrPayload { version: u8, salt: [u8;16], nonce: [u8;12], ciphertext: bytes }`.
5. Base64-encode `QrPayload` and render the QR in **byte mode**.
6. Display the 6-digit code separately as a label.

**Why base64 in byte mode rather than raw bytes:** ZXing's `ScanContract` returns the scanned content as a Kotlin `String`. Round-tripping arbitrary bytes through a `String` is fragile (encoding mangling). Standard base64 is ASCII-clean — survives the `String` boundary intact. The codec accepts standard or url-safe base64 with or without padding (`QrPayloadCodec.base64Decode`).

**Argon2id parameters** (`m=64 MiB, t=3, p=1`):
- Brute-force cost: 1 M codes × ~1 s/derivation = ~12 days on a single core. With a 60 s TTL the QR is gone before any meaningful fraction can be tried.
- Legitimate cost on phone: ~1 s on mid-range Android. Pure-Rust Argon2id isn't bundled on Android — we use `org.bouncycastle:bcprov-jdk18on` (already a dependency).

### 18.6 QR rendering

**Desktop side:** `qrcodegen` crate (pure Rust, no deps), 256×256 monochrome, ECC level **L**, alphanumeric mode (base64 fits).

**Mobile side:** `com.journeyapps:zxing-android-embedded:4.3.0` + `com.google.zxing:core:3.5.2`. **Why ZXing not ML Kit** — ML Kit Barcode pulls in `com.google.android.gms:play-services-base` even in its "bundled" variant; TabSSH targets de-Googled ROMs, so we can't have any Google Play Services dependency. ZXing is pure Java with zero Google deps.

### 18.7 UX

#### Desktop (Rust + egui)

Trigger: menu → File → Pair Phone…

State machine: `[Idle] → [Selecting] → [Generating] → [Active] → [Expired]`. Active layout: QR + 6-digit code + 60 s countdown + per-profile checklist + Cancel / Generate-new.

#### Mobile (Kotlin + Android)

Trigger: drawer menu → Pair from Desktop (QR).

Activity: `ImportFromQrActivity` (`app/src/main/java/io/github/tabssh/ui/activities/ImportFromQrActivity.kt`).

State machine: `[CheckingPermissions] → [Scanning] → [CodeEntry] → [Decrypting] → [Confirming] → [Importing] → [Success/Failure]`.

Failure-mode UI text:

| Failure | UI text |
|---------|---------|
| `version > 1` in `QrPayload` | "This QR was created by a newer TabSSH. Update your phone app." |
| `expires_at < now` after decrypt | "This QR has expired. Ask the desktop to generate a new one." |
| AES-GCM auth tag mismatch | "Wrong code. Try again. (3 attempts left.)" |
| 3 wrong code attempts | "Pairing cancelled. Generate a new QR." |
| CBOR decode error | "Couldn't read pairing data. Make sure both apps are up to date." |
| No connections in payload | "This QR has no connections to import." |
| Camera permission denied | "Camera access is required to scan QRs." |

**3-attempt limit** — important; without it a 6-digit code with no rate limit is brute-forceable in seconds locally.

### 18.8 Implementation status

#### Desktop (Rust)

- [ ] Add deps: `qrcodegen`, `ciborium`, `argon2`, `aes-gcm`, `rand`, `base64`.
- [ ] `src/pairing/payload.rs` — `PairingPayload`, `ConnectionProfile` (subset, no secrets), serde encode.
- [ ] `src/pairing/encrypt.rs` — generate code/salt/nonce; Argon2id; AES-GCM encrypt; serialise QrPayload.
- [ ] `src/pairing/qr.rs` — render `QrPayload` (base64) → QR bitmap via `qrcodegen`.
- [ ] `src/ui/pairing_dialog.rs` — egui state machine + QR display + countdown.
- [ ] Wire menu entry: File → Pair Phone…
- [ ] Tests: round-trip encrypt/decrypt with known test vectors (commit them — mobile reuses).

#### Mobile (Kotlin / Android) — ✅ implemented 2026-04-28

- [x] Deps: ZXing (`com.google.zxing:core:3.5.2` + `com.journeyapps:zxing-android-embedded:4.3.0`) — `app/build.gradle`. BouncyCastle Argon2 reused from existing `org.bouncycastle:bcprov-jdk18on:1.77`.
- [x] `pairing/PairingPayload.kt` — data classes + sealed result hierarchy.
- [x] `pairing/QrPayloadCodec.kt` — minimal hand-written CBOR decoder (~120 lines, no library) + base64 unwrap with `android.util.Base64` (API 21+ compatible).
- [x] `pairing/PairingDecryptor.kt` — Argon2id (m=64MiB, t=3, p=1) + AES-256-GCM via `javax.crypto` + TTL/version validation.
- [x] `ui/activities/ImportFromQrActivity.kt` — full state machine, dialog-driven UI, 3-attempt code retry.
- [x] `pairing/PairingImporter.kt` — name-based group/identity dedupe, fresh-UUID connection inserts (never overwrite existing).
- [x] Drawer menu entry — `drawer_menu.xml` "Pair from Desktop (QR)" in Import / Export group.
- [x] Camera permission — `AndroidManifest.xml` + runtime `RequestPermission` ActivityResultContract.
- [x] Activity registration in `AndroidManifest.xml`. MainActivity drawer click handler (`R.id.nav_pair_from_desktop`).
- [ ] Tests with desktop-generated vectors — waiting on desktop side.

#### Shared

- [ ] Pin CBOR field tags so both sides regression-test against the same blob.
- [ ] Commit at least 3 desktop-generated test vectors (different connection counts) into both repos.
- [ ] Decide v2 forward-compat rules (the `version` byte negotiates).

### 18.9 Open questions

1. **Public keys ride along, or generate-on-phone?** Current decision: ride along (so the user has a working OpenSSH-format public key after import) but **only public**. Phone never sees private. If the user wants the phone to authenticate with the same key, they re-import the private half via the existing key-management flow.
2. **Snippets / themes / workspaces too, or v1 connections-only?** Current decision: connections + groups + identities in v1. Snippets and themes can wait — independent of connection profiles, add payload weight.
3. **Should the desktop write the phone's public key to `authorized_keys` automatically?** Out of scope for v1.
4. **Verify-on-desktop step?** Phone shows a 4-digit checksum the user reads back to the desktop, desktop confirms. Adds friction; reduces "wrong device scanned the QR" risk. Defer to v2.

### 18.10 Alternatives considered

| Option | Why rejected for v1 |
|--------|---------------------|
| Local-network handshake (mDNS + HTTP) | Both devices must be on the same wifi. Corporate networks, hotel APs with client isolation, mismatched SSIDs all break it. |
| NFC tap | Only Android phones with NFC. Most desktops have no NFC reader/writer. |
| `tabssh://` URL via clipboard / SMS / Signal | Copy-paste between devices is awkward; messaging apps mangle URLs; cleartext URL on third-party servers is worse than an encrypted QR on a local screen. |
| SAF cloud sync (existing) | Right tool for "I want my data on all my devices forever". Wrong tool for "I just want this one connection on my phone, today". |
| USB tether + adb push | Works for developers; useless for users. |
| Bluetooth pairing | Pairing prompts on both sides, drivers, "is my Bluetooth on?" — way more friction than a camera. |

### 18.11 References

- `qrcodegen` Rust crate — pure Rust QR encoding.
- `ciborium` Rust crate — CBOR codec.
- `argon2` Rust crate — RFC 9106 Argon2id.
- `aes-gcm` Rust crate — RustCrypto AES-GCM.
- ZXing (`com.journeyapps:zxing-android-embedded`) — pure-Java QR scanner, zero Google deps.
- BouncyCastle `bcprov-jdk18on` — already in TabSSH Android deps; provides Argon2id.
- ISO/IEC 18004:2015 — QR Code spec.
- RFC 9106 — Argon2 KDF parameters.

---

*End of AI.md. For operating rules and AI.md navigation pointers see `CLAUDE.md`. For the public-facing feature list see `README.md`. For open work and task tracking see `TODO.AI.md`. The F-Droid formatted spec lives at `fdroid-submission/SPEC.md`.*

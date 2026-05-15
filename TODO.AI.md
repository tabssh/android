# TabSSH TODO

**Last Updated:** 2026-05-15
**Version:** 0.0.9 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> **Usage rules for AI agents:**
> 1. Open this file at the start of every session that touches 2 or more tasks.
> 2. Update status as you go — mark items shipped the moment they land in a commit.
> 3. Add every bug fix, feature, and doc change to ✅ Recently Shipped with its commit hash.
> 4. Never let this file go more than one session stale.
>
> **Source of truth hierarchy:** `AI.md` (architecture) → `TODO.AI.md` (open work) → `CLAUDE.md` (operating rules). This file tracks what has shipped and what needs to be done.

---

## ✅ Recently Shipped

### Bug fix batch (2026-05-15)

- **`(pending)`** 🐛 Debug log toggle shows Off on dev builds — `TabSSHApplication.onCreate()` now writes `debug_logging_enabled=true` to SharedPrefs when `BuildConfig.DEBUG_MODE` is true, so the Settings → Logging toggle reflects the actual active state.
  - Files: `TabSSHApplication.kt`
- **`(pending)`** 🐛 Performance monitor last-selected host not persisting — fixed Spinner restore race in `PerformanceFragment.updateConnectionSpinner()`: read saved ID before setting adapter (adapter init can fire `onItemSelected(0)` synchronously), use `spinnerConnection.post { setSelection }` to defer until after layout.
  - Files: `PerformanceFragment.kt`
- **`(pending)`** 🐛 SSH key not shown in connection editor — added `pendingRestoreKeyId` field to `ConnectionEditActivity`; `populateFields()` stores the key ID when `availableKeys` is empty (async load not done); `setupKeySpinner()` restores the selection once the key list is ready.
  - Files: `ConnectionEditActivity.kt`
- **`(pending)`** 🐛 Proxmox "unable to find serial interface" terminal garbage — `ConsoleWebSocketClient.onMessage()` now detects the serial error pattern in both "0:N:MSG" envelope and plain-text frames. Calls `connectionListener.onError()` with a descriptive fix-it message instead of writing garbage to the terminal pipe.
  - Files: `ConsoleWebSocketClient.kt`
- **`(pending)`** 🔧 About page hardcoded version string — `preferences_main.xml` changed from `"TabSSH 1.1.0"` / `"Build details"` to `"Loading…"` placeholder. `SettingsActivity` already overrides these dynamically with `BuildConfig.VERSION_NAME`, `GIT_COMMIT_ID`, `BUILD_DATE`, `BUILD_TYPE`.
  - Files: `preferences_main.xml`
- **`(pending)`** 📝 What's New updated — Wave 11 entry added covering dashboard v2, keyboard fixes, mosh exit-code behavior, and the 4 bug fixes above.
  - Files: `whats_new.md`

### Multi-host Dashboard v2 full redesign (2026-05-14)

- **`2596eeb78246`** ✨ `MultiHostDashboardActivity` full rewrite with sysadmin-grade host cards. CPU/MEM/DISK progress bars, load averages, uptime, network rates, process count. Dashboard groups are independent from connection groups (SharedPrefs JSON, no DB change). DiffUtil + payload-based metric updates. Group headers with add/rename/delete buttons. Long-press host → move to group. Per-host monitor bell. Status dot (green/grey connecting/red offline).
  - Files: `MultiHostDashboardActivity.kt`, `activity_multi_host_dashboard.xml`, `item_dashboard_host_card.xml`, `item_dashboard_group_header.xml`, `menu_dashboard.xml`, `bg_circle.xml`

### AI.md full source audit + gap fills (2026-05-12)

- **`(this session)`** 📚 AI.md gap-fill — 10 items from a parallel source survey applied:
  - §4.3 activities table updated from 23 → 32 (9 missing activities added: CloudAccountsActivity, ConnectionHistoryActivity, HypervisorAccountsActivity, ImportFromQrActivity, MultiHostDashboardActivity, PinLockActivity, RemoteFileEditorActivity, ThemeEditorActivity, WhatsNewActivity)
  - §4.7 new section: Performance monitoring (MetricsCollector, metrics model fields, 60-point history, 5 s polling)
  - §5.1.3 new section: SSHSessionManager pool + double-check logic + listeners
  - §5.6 new section: Telnet — corrected from stub to fully-implemented RFC 854 (ECHO/SGA/TERMINAL-TYPE/NAWS)
  - §7.5 PIN lock detail added (Wave 3.2, EXTRA_MODE, MAX_ATTEMPTS=5, pref keys, trigger)
  - §8.8 new section: AuditLogManager (event types, API, cleanup policy, pref keys)
  - §11.8 new section: Cloud account integration (7 providers, token storage pattern)
  - §15 package map: added `cloud` package row; expanded `utils` entry; updated activity count
  - §16: FIDO2 scope clarified (detection-only, no CTAP2); Telnet not-a-stub note added

### Multi-host Dashboard v2 + background monitoring (2026-05-14)

- **`(pending)`** ✨ Multi-host Dashboard v2: group add/rename, card tap → HostDetailActivity, per-host monitor config, battery-aware WorkManager background monitoring.
  - DB v31→v32: new `monitor_slots` table (`MonitorSlot` entity, `MonitorSlotDao`)
  - `HostAvailabilityWorker`: WorkManager 15 min periodic, TCP-only probes, respects battery saver + `monitoring_enabled` pref, emits down/recovery/still-down notifications
  - `NotificationHelper`: two new channels — `host_monitoring_v1` (HIGH, down/recovery) + `host_metrics_v1` (DEFAULT, threshold breaches); 4 new notification functions
  - `MultiHostDashboardActivity` rewrite: toolbar "Add group" menu, group header long-press = rename, status dot per card (green/red/grey from `MonitorSlot`), 🔔 bell per card + long-press = monitor config dialog, card tap = `HostDetailActivity`
  - `HostDetailActivity` (new): single-host live metrics (CPU/mem/disk/load/net/platform) + monitoring status + Connect + Monitor settings
  - `TabSSHApplication.applicationScope` made `val` (was `private val`) to allow companion-fun dialog access
  - `HostAvailabilityWorker.schedule()` called from `TabSSHApplication.initializeCoreComponents()`
  - AI.md: §4.5 HostAvailabilityWorker row, §4.8 new background monitoring section, §8.1 version 32, §8.2 MonitorSlot entity row, §8.4 migrations v29→32, §13.1 channel table updated

### Post-2026-05-02 commits (May 2 – May 12, 2026)

- **`3f4dc8c`** 🐛 Session reattach fix — `TabTerminalActivity` now uses Application-scoped `TabManager` (`app.tabManager`) so tabs survive activity destruction. Added `onNewIntent()` override (singleTop was silently dropping re-launch intents). Added `finish()` to `handleBackToMainActivity()` so the back-press cycle doesn't stack duplicate instances. `onDestroy()` no longer calls `tabManager.cleanup()`.
- **`1c8ff4d`** 🐛 Sync upload + `max_tabs` type cast — enabled Kotlin serialization Gradle plugin, annotated `SyncDataPackage` + members `@Serializable`. Fixed `max_tabs` `Int`→`String` SharedPreferences migration that caused `ClassCastException` after process restart.
- **`ca421975`** 🐛 UX batch — back keeps sessions, status dot in tab strip, SEL key + drag range-copy, URL long-press confirmation dialog, log-copy fixed (file existence check instead of placeholder-string sniff).
- **`a696a179`** 🎨 Terminal menu split into 3 flows — copy/paste actions, tab management, terminal control — replacing one long unsorted list.
- **`563b99a8`** 🎨 Terminal menu moved off-canvas — new ☰ key in the keyboard bar opens a bottom sheet so the terminal canvas stays full-height.
- **`ed7528dd`** 🐛 Cluster commands now prompt for unknown host keys — `ClusterCommandExecutor` was bypassing `HostKeyVerifier`; fixed so the TOFU dialog fires on first-ever cluster target.
- **`c4525bf3`** 🔔 Per-host SSH notifications with optional alert-on-output — new `per_host_notifications` preference; banner + optional sound when a backgrounded tab produces output.
- **`19f81e65`** 🎨 Themes wired end-to-end — `applySavedAppTheme()` reads `app_theme` pref and calls `AppCompatDelegate.setDefaultNightMode`; system fonts fetched from Google Fonts at build time (not committed to repo).
- **`9909e19c`** 🐛 UX batch — discoverable tabs (tab strip always visible), font loader, status dot, exit-prompt on last tab close, `KEEP_SCREEN_ON` wakelock wired to pref.
- **`30fe70b6`** 🐛 3 fixes — hypervisor default ports (Proxmox 8006, XCP-ng/VMware 443 pre-filled), `BadToken` dialog crash on disconnect, 24 h password TTL enforced at unlock rather than only at connect.

### "25 fixes" + "6 features" merges (Apr 30 / May 1, 2026)
- **Commits:** `482c2a04` ("25 fixes") and the follow-on 6-feature batch.
- 25-fix triage covered connect/reconnect lifecycle, notification system, cold-start ANR, per-tab same-host channels (Issue #163), on-screen keyboard ergonomics (`#161` toggle removed, `#162` vim/tmux reorder), always-on keepalive (`#166`), repo cleanup (`#160`).
- 6-feature follow-up: centralised error dialogs with Copy (`#167`), edge-swipe tab switching (`#168`), tmux/screen auto-launch + postConnectScript wired (`#170`), modifier-aware hardware-keyboard arrows (`#171`), recordable macros (`#173`, DB v25 → v26), ViewStub-defer Active Sessions strip (`#175`).
- DB now v26. New `macros` table for byte-exact recordable sequences (distinct from snippets, which carry typed text + `{?var}` substitutions).

### QR Pairing — Mobile side
- **Commit:** `ea4f687f572f` ("Added QR code support")
- **Spec:** [`AI.md` §18](AI.md#18-qr-pairing--desktop--mobile-setup)
- Mobile QR-import flow lets the desktop client send connection profiles to the phone via a one-shot encrypted QR + 6-digit code.
- 5 new Kotlin files (~1,016 LOC) at `app/src/main/java/io/github/tabssh/pairing/`: `PairingPayload.kt` (data classes), `QrPayloadCodec.kt` (hand-written CBOR codec, no library), `PairingDecryptor.kt` (Argon2id + AES-256-GCM), `PairingImporter.kt` (DB inserts with name-based dedupe), and `ImportFromQrActivity.kt` (state-machine UI).
- ZXing for the QR scanner (zero Google Play Services dependency, matching TabSSH's de-Googled-ROM stance).
- `tabssh-android-{arch}.apk` builds verified compile-clean.

### Issue #36 — ANR on app update
- **Commit:** `bed586fe45ec` ("Issue #36: ANR-on-update defenses")
- Moved `initializeCoreComponents()` + `wireGlobalHostKeyCallbacks()` into a background `applicationScope.launch{}` so lazy components (`SecurePasswordManager`, `themeManager`, `keyStorage`, `sshSessionManager`) initialise off the main thread instead of blocking it.
- Tightened log file rotation: 10 MiB single-backup → 1 MiB × 5-file rotation. Total on-disk logs bounded at ~10 MiB across both debug + app logs; rotation is rename-only (microseconds vs the previous 10 MiB copy).
- Strict improvement — no regression if a main thread races and beats the scope.
- The actual ANR trace from a reproducing device is still useful future data, but the structural fixes here address the four most-plausible causes regardless.

### Issue #37 — SSH config `RemoteCommand` + `SendEnv` end-to-end
- **Commit:** `05b7dac11642` ("Issue #37: SSH config RemoteCommand + SendEnv end-to-end")
- DB v23 → v24 — new `connections.remote_command` column.
- Parser: explicit `RemoteCommand` + `SendEnv` cases in `SSHConfigParser.kt`. SendEnv-derived names merge into the existing `envVars` field as `NAME=` placeholders.
- Connection layer: `SSHConnection.openShellChannel()` now branches on `profile.remoteCommand`. When set, opens `ChannelExec` with `setCommand(remoteCmd)` + `setPty(true)`; otherwise the existing `ChannelShell` path. Field type widened to `Channel?` (the JSch parent `ChannelSession` is package-private, so we dispatch on the concrete subclass for PTY-only methods).
- Exporter: round-trips both directives. SendEnv vs SetEnv split — bare names → SendEnv, NAME=value → SetEnv.
- UX: new Spinner + conditional Custom EditText in `ConnectionEditActivity`. 7 presets (Default — login shell / `create` (SourceForge) / `sftp` / `internal-sftp` / tmux / screen / Custom…). On profile load, snaps to a matching preset verbatim or surfaces "Custom…" with the value pre-filled.
- Fixes silent breakage for SourceForge `shell.sourceforge.net`, forced-`command="..."` jails, gateway/menu hosts, SFTP-only accounts.

---

## 📝 Open / Planned Work

### 🐛 Active Bug Queue — 2026-05-14

Confirmed bugs reported by the user on 2026-05-14. Fix in order listed.

| # | Bug | Status | Files |
|---|-----|--------|-------|
| B-1 | Keyboard landscape expansion unwanted — `distributeKeyWidths()` expands keys to fill landscape row width. User wants CSS/HTML natural-size behavior: keys stay WRAP_CONTENT in both orientations; landscape just gives more horizontal space (scrollable). | ✅ `c0bb3e4` | `KeyboardRowView.kt` |
| B-2 | SEL key freezes terminal — `armSelectionForNextDrag()` arms drag-select mode. If user doesn't immediately drag, the armed flag is never cleared → terminal stops accepting typed input. Fix: clear armed state on any non-drag touch event (tap, key press, focus loss). | ✅ `576fc36` | `TerminalView.kt` |
| B-3 | Scrollback reversed + laggy — scroll direction inverted; `renderTermuxBuffer` called on every scroll frame is expensive. | ✅ `df29e34` | `TerminalView.kt` |
| B-4 | New-tab picker doesn't switch to new tab — after connecting via quick-connect picker the app stays on the previous tab. Should auto-switch to the newly connected tab. | ✅ `b6c3386` | `TabTerminalActivity.kt` |
| B-5 | Exit=0 still prompts to reconnect — race: `onDisconnected` emitted DISCONNECTED before `closeChannel()` captured exit status, so `getShellExitStatus()` always returned -1. | ✅ `b6c3386` | `SSHTab.kt` |
| B-6 | Disconnect notifications not self-removing — notification update via `nm.notify()` on the same ID that was the foreground anchor retained the ongoing flag; `setTimeoutAfter` was ignored. | ✅ `d2238f7` | `SSHConnectionService.kt` |
| B-7 | Mosh not working — `mosh-client` binary missing from jniLibs; fallback to SSH is silent (no user notice). | ✅ moot after B-12 (binary now bundled, Wave 9.2) | `TabTerminalActivity.kt` |
| B-12 | Mosh stops at last-login banner — two root causes: (1) `-s` flag in mosh-server command blocked waiting for key on stdin → 8s timeout → empty output → bootstrap fails; (2) `ProcessBuilder` path gave mosh-client a pipe not a PTY → `tcgetattr(ENOTTY)` → immediate exit. Fix: removed `-s`; replaced ProcessBuilder with `TerminalSession` (JNI forkpty) via `TermuxBridge.connectMoshClient()`. | ✅ `e4770b09` | `MoshHandoff.kt`, `TermuxBridge.kt`, `SSHTab.kt` |
| B-13 | Proxmox console fails for VMs without serial interface — termproxy requires `serialN: socket` in VM hardware config; VMs without it got a dead "no serial console" error with no fallback. Fix: `connectProxmoxConsole()` now catches the serial error and automatically falls back to vncproxy (`PROXMOX_VNC` protocol), which works for all running VMs regardless of hardware. No auth-frame sent for VNC (ticket in URL). Extended 2026-05-15: `ConsoleWebSocketClient.onMessage()` now also catches the case where Proxmox's API returns a valid ticket but sends the error as a WebSocket data frame (bypasses the API-level catch); `isProxmoxSerialError()` helper checks both "0:N:MSG" envelope and plain-text frames. | ✅ `bfa72c87` + `(pending)` | `HypervisorConsoleManager.kt`, `ConsoleWebSocketClient.kt` |
| B-14 | OCI SSH Connect was ephemeral — created a bare `ConnectionProfile` (username only) on every tap with no auth method, no key, no persistence. Fix: persistent SSH config dialog (username, port, auth method, key picker) backed by `ConnectionProfile.ociInstanceId`. DB v30→v31 adds `oci_instance_id` column; first connect saves, subsequent taps pre-fill the saved values. | ✅ `55386d5b` | `OciManagerActivity.kt`, `ConnectionProfile.kt`, `ConnectionDao.kt`, `KeyDao.kt`, `TabSSHDatabase.kt`, `dialog_oci_ssh_config.xml` |
| B-10 | Mosh broken by B-5 — the new `else` branch in `onDisconnected()` fires asynchronously on main thread after `tab.disconnect()` clears `connection=null` but after `connectMosh()` has already set state CONNECTED; clobbers mosh state back to DISCONNECTED. | ✅ `8224a18` | `SSHTab.kt` |
| B-11 | Hamburger menu missing on large screens — `applySidebarMode()` locks drawer open (`LOCK_MODE_LOCKED_OPEN`) and hides toggle indicator. Auto-open on launch is bad UX. Remove forced sidebar; use normal overlay mode on all screen sizes so hamburger is always visible and drawer starts closed. | ✅ `8224a18` | `MainActivity.kt` |
| B-8 | Proxmox console serial interface error — HTTP 200 `{"data":null,"errors":"...serial..."}` not detected; friendly message never shown. | ✅ `44122f1` | `ProxmoxApiClient.kt` |
| B-9 | OCI VMs — no console access (stub). Instance control may also be broken. | ✅ `fdc75ea` | `OciManagerActivity.kt`, `item_oci_instance.xml` |

---

### ✅ Audit progress — 2026-05-02

The audit findings below are historical; this section tracks status.

| Item | Status | Commit |
|---|---|---|
| P0 #1 backup encryption real | ✅ shipped | `2e4d9648` |
| P0 #2 hypervisor TLS — silent-bypass closed | ✅ shipped | `5a4b26f5` |
| P0 #2 hypervisor TLS — TOFU + change-detect cert pinning | ✅ shipped | DB v28 + `crypto/tls/HypervisorTrustManagerFactory.kt` + `HypervisorCertPromptDialog.kt`, wired into all 5 clients |
| Hypervisor reusable accounts (settled the "identity for hypervisors?" question) | ✅ shipped | DB v27 + `HypervisorAccount` entity / DAO / Activity, drawer entry |
| P1 Tasker IPC permission gate | ✅ shipped | `2e4d9648` |
| P1 HostKeyVerifier timeout/destroyed-activity | ✅ shipped | `5ac8f999` (now `DIALOG_TIMEOUT_SECONDS=30` at `HostKeyVerifier.kt:565`) |
| P1 hypervisor passwords → Keystore | ✅ shipped | `ae2c613a` (`HypervisorPasswordStore.resolveCredentials/store/clear/persistCapturedPinIfAny`) |
| P1 WebSocket.send return ignored | ✅ shipped | `ae2c613a` (single-flight `attemptSend` + `sendFailureFired`) |
| P1 `profile.identityId!!` NPE | ✅ shipped | `2e4d9648` (read once into local val at `SSHConnection.kt:148`) |
| MAC-failure root-cause (the actual disconnect bug) | ✅ shipped | `bbf15665` (`writeLock: Mutex` at `TermuxBridge.kt:86,141`) |
| RECONNECT race that destroyed the activity | ✅ shipped | `1f25c29d` (`isReconnecting` flag at `TabTerminalActivity.kt:84,1796,1803`) |
| Tasker preferences fragment | ✅ shipped | `d714a7b4` (fragment at `SettingsActivity.kt:605-697`, IntentService consumes all 4 prefs) |
| advancedSettings JSON apply at connect | ✅ shipped | `d714a7b4` (`SSHConnection.applyAdvancedSettings` for Local/Remote/Dynamic forwards) |
| X11 fixes batch | ✅ shipped | (this batch) Deleted orphan/broken `X11ForwardingManager` (437 LOC of stub in-app X server), refactored duplicated SSHConnection X11/agent setup into `applyForwardingFlags`, surfaced setup failures via `onError` listener, copied `forwardX11`/`forwardAgent`/`compression`/`connectTimeout` from imported `~/.ssh/config` to entity columns (were previously dropped at parse time). |
| Audit batch — widget tap crash (`ConnectionWidgetProvider.kt:144` — `connection.id.toInt()` on UUID String) | ✅ shipped | this batch — threaded `widgetId` into `getConnectIntent` to use AppWidget's per-widget id as the PendingIntent request code (matches `QuickConnectWidgetProvider`). Crash fired on every widget tap. |
| Audit batch — `TabTerminalActivity` `TabManagerListener` leak | ✅ shipped | this batch — listener moved from anonymous-inline to a `tabManagerListener` field, removed in `onDestroy()` before `tabManager.cleanup()`. Anonymous listener held implicit `this@TabTerminalActivity`; was preventing GC across reconnect cycles. |
| Audit batch — `TabSSHDatabase.exportDatabase()` reading SQLite as text | ✅ shipped | this batch — deleted (was dead code, no callers, plus `dbFile.readText()` on a binary file would have produced corrupt output if anyone ever called it). |

---

### 🔒 Audit findings — 2026-05-01 (historical)

Two read-only Explore-agent passes — feature-completeness vs. README + project tracker docs, and bug/security. Cited file:line locations are direct from the audit and verified for the P0 entries.

#### 🚨 P0 — security-promise breaking, fix immediately

- **Backup encryption is fake (Base64 only).** `app/src/main/java/io/github/tabssh/backup/BackupManager.kt:268-285` — `encryptData()` is just `Base64.encodeToString(...)`, `decryptData()` is `Base64.decode(...)`. The exported ZIP claims to be password-protected; it isn't. SSH keys, host-key fingerprints, and identity passwords all readable to anyone with the file. Fix: real AES-256-GCM with PBKDF2 (≥100k iterations) keyed off the user's backup password. ~3h.
- **Hypervisor TLS verification globally disabled by default.** `verifySsl: Boolean = false` in `hypervisor/proxmox/ProxmoxApiClient.kt:21`, `hypervisor/console/ConsoleWebSocketClient.kt:27`, plus the matching XCP-ng / Xen Orchestra / VMware clients. The trust-all `X509TrustManager` accepts any cert — including attacker-issued — for hypervisor REST + serial-console traffic. No per-host pin or CA store. Fix: per-host opt-in with cert pinning, or per-host CA bundle. ~6h (DB schema + UI).

#### 🟠 P1 — exploitable / crash-prone

- **TaskerIntentService is `exported="true"` with no permission gate.** `app/src/main/AndroidManifest.xml:278-289` + `automation/TaskerIntentService.kt`. Any installed app can send `CONNECT` / `SEND_COMMAND` / `SEND_KEYS` intents and drive arbitrary commands on the user's SSH targets. Fix: either set `exported="false"` (Tasker still works on most ROMs through alternate IPC) or require a custom `io.github.tabssh.permission.TASKER` signature-level permission. ~1h.
- **8× `runBlocking(Dispatchers.IO)` on the main thread inside `HostKeyVerifier.check()`.** `ssh/HostKeyVerifier.kt:64,101,133,225,249,285,309,330`. The CountDownLatch waits at lines 470, 546 have **no timeout** — an Activity destroyed mid-prompt → permanent worker-thread hang. Already triggers ANR risk on slow devices. Fix: convert to fully-async via callback; latch wait with 30s timeout default-rejecting on expiry. ~4h.
- **Hypervisor passwords stored as plaintext columns** in `storage/database/entities/HypervisorProfile.kt:32-33`. SSH passwords go through `SecurePasswordManager` (Keystore-backed); hypervisor creds bypass it. Device backup or root → cleartext. Fix: route through SecurePasswordManager with `hypervisor_${id}` alias. ~2h.
- **`WebSocket.send()` return value ignored** in five places: `hypervisor/console/ConsoleWebSocketClient.kt:149,254,309,335,344`. Send-buffer-full or already-closed socket → user keystrokes silently dropped. **Likely contributor to the VM-console disconnect symptom we already saw.** Fix: check Boolean, surface failure to the UI / trigger reconnect. ~2h.
- **`profile.identityId!!`** at `ssh/connection/SSHConnection.kt:143`. Identity row deleted between the null-guard at 141 and the bang at 143 → NPE. Fix: `profile.identityId?.let { ... } ?: fallthrough`. ~10min.

#### 🟡 P2 — latent / defense-in-depth (re-verified 2026-05-02)

- Session passwords held in `mutableMapOf<String, String>` for app lifetime — `crypto/storage/SecurePasswordManager.kt:64`. Cleared on explicit `clearAllPasswords()` (lines 409, 436, 448) but NOT on lifecycle events (pause/destroy/biometric-lock). **Still open.**
- ~~Host-key dialog `latch.await()` no timeout~~ — **VERIFIED FIXED.** `HostKeyVerifier.kt:520` now uses `latch.await(DIALOG_TIMEOUT_SECONDS, SECONDS)` with `DIALOG_TIMEOUT_SECONDS=30` and a default-REJECT path on expiry (line 524).
- DB query on `Dispatchers.Main` in widget update — `widget/ConnectionWidgetProvider.kt:66`. Cosmetic-only: Room's suspend DAO funcs (`getConnectionById`) dispatch their own IO regardless of the launching scope, so this isn't an actual main-thread DB hit. Worth tidying for clarity but not a correctness bug. **Cosmetic / low priority.**
- Jump-host port-forward bind — `setPortForwardingL(0, profile.host, profile.port)` at `ssh/connection/SSHConnection.kt:713`. The 3-arg JSch overload defaults to `127.0.0.1` (not `0.0.0.0`), so this is safe in practice — but worth an explicit `setPortForwardingL("127.0.0.1", 0, host, port)` for self-documentation and version pinning. **Open (cosmetic).**
- `cachedPassword` / `cachedPassphrase` held as `String` for connection lifetime, never zeroed — `ssh/connection/SSHConnection.kt:101,104`. Same defense-in-depth shape as the SecurePasswordManager map. **Still open.**
- ~~Host-key dialogs walk the context chain with no Activity guard~~ — **VERIFIED FIXED** in commit `5ac8f999`. `HostKeyVerifier` now resolves the activity via `TabSSHApplication.getCurrentActivity()` and skips when `isFinishing || isDestroyed`.
- Logger key-bytes audit not yet performed — defensive grep across `Logger.[diwve]` calls touching `bytes`/`key`/`pass`/`secret` to confirm none print raw key material. **Open (low-priority hygiene pass).**
- Translation drift: `values/strings.xml` has 167 keys, each of `values-{es,fr,de}/strings.xml` has 157. The 10 missing keys (`cluster_progress`, `widget_*_description`, `sync_password_*`, `navigation_drawer_open/close`, `select_connection`) silently fall back to base English at runtime — Android's standard locale-resolution behaviour. **Accepted-known: needs a native-speaker translation pass before adding faux-translated stubs.**
- Hypervisor REST clients use `getJSONObject` rather than `optJSONObject` (`ProxmoxApiClient.kt:84` and similar across the four clients). Outer try/catch swallows the resulting `JSONException` so it doesn't crash, but the user-facing error loses the actual API response shape. **Accepted-known: defense-in-depth across ~20 call sites for marginal benefit; revisit if a real Proxmox/XO/etc. schema change actually fires opaque errors in practice.**

#### 🧩 Feature gaps — claimed but not wired

> **Audit re-check (2026-05-02):** several of the original claims here
> were stale by the time the audit ran. Verified-wired items are
> ~~struck through~~ below; only real gaps remain unmarked.

- ~~**`encryptBackup` UI promise**~~ — **VERIFIED WIRED** as of 2026-05-02. `BackupManager.encryptData` at lines 273-285 routes through `SyncEncryptor` (real AES-256-GCM + PBKDF2 100k iterations); `decryptData` is forward-compatible and tolerates legacy Base64-only blobs for restoring pre-fix backups.
- ~~**Hypervisor TLS**~~ — **VERIFIED WIRED** as of 2026-05-02. DB v28 carries `pinned_cert_sha256`; `crypto/tls/HypervisorTrustManagerFactory.installTrust(...)` runs in all 5 clients (Proxmox/XCP-ng/XO/VMware/ConsoleWebSocketClient) implementing TOFU + change-detect via `HypervisorCertPromptDialog`. `HypervisorEditActivity` shows the pinned fingerprint with a Forget button. The `verifySsl=false` switch is now a deliberate per-host bypass, not the only feature.
- ~~**AWS / GCP / Azure cloud import** — clients fully built~~ — **VERIFIED WIRED** as of 2026-05-02. `CloudAccountsActivity` has a drawer entry (`drawer_menu.xml:44 nav_cloud_accounts`) and `MainActivity` dispatches it. Audit was outdated.
- ~~**X11 toggle hidden**~~ — **VERIFIED WIRED** as of 2026-05-02. The switch is at `activity_connection_edit.xml:447` with NO `visibility="gone"`, and `ConnectionEditActivity` already binds it (load at line 494, save at lines 685/766/797). Audit was outdated.
- ~~**SSH user-certificate auth**~~ — **VERIFIED WIRED** as of 2026-05-02. `StoredKey.certificate` (DB v19) is consumed at `SSHConnection.kt:752-767` via `jsch.addIdentity(name, prvkey, pubkey=cert, passphrase)`. `KeyManagementActivity.kt:424-433` exposes paste/file pickers for attach/remove with `-cert-v01@openssh.com` validation. Audit was outdated.
- ~~**Snippet `{?var:default|hint}` substitution UI**~~ — **VERIFIED WIRED** as of 2026-05-02. `TabTerminalActivity.insertSnippet` calls `showVariablesDialog` (line 2780) which builds an EditText per `getVariableSpecs()` entry, with last-used recall in `snippet_var_recall` SharedPreferences. Audit was outdated.
- ~~**Recordable macros — zero UI**~~ — **VERIFIED WIRED** as of 2026-05-02. Record/replay flow exists in `TabTerminalActivity` (insertMacro at line 2259, getAllMacrosList + incrementUsageCount at 2284/2303). No dedicated CRUD activity yet, but the in-terminal flow is functional.
- **FIDO2 SSH signing** — `crypto/fido/Fido2SshIdentity.kt:35-40` throws `JSchException("FIDO2 SSH signing is alpha and not yet implemented")`. JSch upstream doesn't support `sk-*` key types; needs a JSch fork or alternate library. ~80h. **Likely defer indefinitely.**
- ~~**Mosh full protocol**~~ — **WIRED** as of 2026-05-14. `MoshHandoff.kt` bootstraps via SSH exec channel → parses `MOSH CONNECT <port> <key>` → `TermuxBridge.connectMoshClient()` launches the bundled native `mosh-client` binary via `TerminalSession` (JNI `forkpty()`). The binary handles all UDP/SSP/AES-128-OCB transport natively. No user clipboard paste required — the session is fully transparent. See B-12 for the two boot bugs fixed.
- ~~**Tasker preferences XML orphaned**~~ — **VERIFIED WIRED** as of 2026-05-02. `TaskerSettingsFragment` (`SettingsActivity.kt:605-697`) inflates the XML and is reachable from `preferences_main.xml:46-50`. `TaskerIntentService` honours `tasker_enabled`, `tasker_require_unlock` (KeyguardManager check), `tasker_allowed_connections` (whitelist), `tasker_log_events`, and `tasker_command_timeout` (default fallback when intent extra omitted).
- ~~**`advancedSettings` JSON apply at connect**~~ — **WIRED** as of 2026-05-02. `SSHConnection.applyAdvancedSettings(session)` runs immediately after a successful `session.connect()` and applies `localForwards`, `remoteForwards`, and `dynamicForwards` parsed from `~/.ssh/config`. Other directives (proxyJump/proxyCommand) already had their own paths; the extant gap was port forwards from imported configs being silently dropped.
- ~~**Xen Orchestra REST `TODO: Implement JSON parsing`**~~ — **MISLEADING AS WRITTEN** (re-verified 2026-05-02). The TODO at `XenOrchestraApiClient.kt:300` is on a generic `parseJsonResponse<T>` helper that is **defined and never called** (single grep hit at line 296). Concrete parsers ARE implemented for the methods that ship — `listVMs` (lines 365-393), `getVM` (lines 427-441), tags / OS-version helpers (`parseJsonArray`/`parseJsonObject` at 643/655). If a future caller wants type-generic parsing, the helper has to be filled in then; the existing call sites all parse JSON concretely. **Effective status: orphan dead code, can be deleted whenever someone passes through the file.**
- ~~**`activity_main_old.xml`** is an orphan layout~~ — **DELETED** in commit cleanup batch 2026-05-02.

---

### 📐 QR Pairing — Desktop side
- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS` Phase F line items)
- **Priority:** MEDIUM
- **Spec:** [`AI.md` §18](AI.md#18-qr-pairing--desktop--mobile-setup)

The mobile decoder is in place and waiting for the desktop encoder + interop test vectors. The spec doc has the wire format, encryption parameters, payload schema, and CBOR field names that both sides must agree on.

### 🐛 `advancedSettings` — partial coverage of remaining directives
- **Status:** Local/Remote/Dynamic forwards now apply at connect (`d714a7b4`). Other directives still parsed → stored → ignored.
- **Priority:** LOW (cosmetic — most users hit forwards first)

Re-verified 2026-05-02 against `SSHConnection.applyAdvancedSettings`:

| Directive | Parser | Stored | Applied at connect |
|---|---|---|---|
| `LocalForward` / `RemoteForward` / `DynamicForward` | ✅ | JSON | ✅ as of `d714a7b4` |
| `ProxyJump` / `ProxyCommand` | ✅ | JSON | ❌ — `ProxyJump` should populate the existing `proxy_host`/`proxy_port`/`proxy_username` columns at parse time instead of living in JSON. `ProxyCommand` has no JSch equivalent and would require a custom `Proxy` impl. |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | ❌ — `ServerAliveInterval` is overridden by the mobile-default 60s keepalive (intentional). `StrictHostKeyChecking` is hardwired to `"ask"` because we own the dialog flow (intentional). Both can stay ignored. |
| `ForwardAgent` / `ForwardX11` | ✅ | JSON + columns | ✅ as of the X11 fixes batch — `convertToConnectionProfile` now copies `host.forwardAgent`/`host.forwardX11` straight into `agentForwarding`/`x11Forwarding` columns. Same fix wired `compression` and `connectTimeout` while it was open. |
| `RequestTTY` | ✅ | JSON | 🟡 — partly: when `remoteCommand` is set we always allocate a PTY (`exec.setPty(true)`), matching `RequestTTY=yes`. The `force`/`no`/`auto` distinctions aren't honored. |

**Fix sketch (remaining):** for `ProxyJump`, parse `user@host:port` and populate the existing `proxy_host`/`proxy_port`/`proxy_username` columns directly. The forward-agent/X11/compression/connect-timeout copy is already done.

**Estimate:** ~1 hour for the ProxyJump piece.

---

### ☁️ OCI (Oracle Cloud Infrastructure) hypervisor support
- **Status:** ✅ All 7 phases shipped (commits `027818614874`, `d03da01d72b0`, `374fa64b8079`).
- **Priority:** MEDIUM (user-requested feature; 4th hypervisor target alongside Proxmox / XCP-ng / VMware).
- **Auth model:** OCI REST API + RSA-SHA256 HTTP request signing (per `draft-cavage-http-signatures-08`). API-key only — `key_file` lives until rotated by the user, no hourly expiry.
- **Onboarding:** Path A only — import `~/.oci/config` + `.pem` via SAF. **No** in-app keypair generation (Path B was explicitly dropped). **Reject** configs with `security_token_file=` (1-hour session tokens; CLI-only renewal).
- **Out of scope for v1:** Instance Console Connection (separate bastion-over-SSH flow), multi-region per profile, compartment browser (paste OCID instead), `ListInstances` pagination, identity domains, anything outside Compute.

**Phasing — every commit ships green and changes nothing user-visible until Phase 6:**

| Phase | Status | What it does | Files |
|---|---|---|---|
| 1 — DB v28→v29 + `HypervisorType.OCI` | ✅ shipped | `auth_type` discriminator (defaulted `'password'`) + 5 nullable OCI columns on `hypervisors`. Adds `OCI` to enum, fixes `when()` exhaustiveness across HypervisorAdapter / MainActivity / HypervisorsFragment / HypervisorEditActivity. | `HypervisorProfile.kt`, `TabSSHDatabase.kt` (`MIGRATION_28_29`), `HypervisorAdapter.kt`, `MainActivity.kt`, `HypervisorsFragment.kt`, `HypervisorEditActivity.kt` |
| 2 — `OciSigner` + `OciKeyMaterial` | ✅ shipped | Standalone RSA-SHA256 HTTP signing primitive. PEM parse + fingerprint round-trip via existing BouncyCastle. No networking, no Android deps beyond JDK + BC. | new `hypervisor/oci/OciSigner.kt`, `hypervisor/oci/OciKeyMaterial.kt` |
| 3 — `OciApiClient` (Compute v1) | ✅ shipped | OkHttp client mirroring `ProxmoxApiClient` shape: `validateCredentials()` → `GET /20160918/users/{userOcid}`; `listInstances`, `getInstance`, `instanceAction` (START / STOP / SOFTSTOP / RESET / SOFTRESET), `getInstancePublicIp` via VNIC walk. Reuses `HypervisorTrustManagerFactory`. | new `hypervisor/oci/OciApiClient.kt`, `hypervisor/oci/OciInstance.kt` |
| 4 — OCI config importer (Path A) | ✅ shipped | Single-screen importer: SAF pick `config` → multi-profile dialog if `[DEFAULT]` + others → SAF pick `.pem` (basename hint from `key_file=`) → passphrase prompt if encrypted → fingerprint round-trip self-test → live `validateCredentials()` → save. Region: `MaterialAutoCompleteTextView` with 34 seeded regions + free-text. Compartment defaults to tenancy OCID. Rejects `security_token_file=`. | new `hypervisor/oci/OciConfigParser.kt`, `ui/activities/OciOnboardingActivity.kt`, layouts, manifest entry |
| 5 — `OciManagerActivity` | ✅ shipped | Mirror of `ProxmoxManagerActivity`: list instances, status + region + IP, start/stop/softstop/reboot/reset buttons. **No console** (deferred). | new `ui/activities/OciManagerActivity.kt`, layouts, manifest entry |
| 6 — Wire production routing | ✅ shipped | "OCI" added to type spinner; type=OCI hides host/port/username/password/realm/account/api-type/ssl and shows "Configure OCI credentials" → launches `OciOnboardingActivity`. `validateFields()` gates by `type`. `testConnection()` for OCI loads the existing row's Keystore PEM and runs `validateCredentials()`. `MainActivity` + `HypervisorsFragment` route OCI to `OciManagerActivity`. | edits to `HypervisorEditActivity.kt`, `MainActivity.kt`, `HypervisorsFragment.kt`, layout |
| 7 — Polish | ✅ shipped | `HypervisorPasswordStore.clearOciSecrets(context, id)` helper; `HypervisorsFragment.deleteHypervisor` extended to clear `oci_private_key_${id}` + `oci_passphrase_${id}` from Keystore on OCI row delete. | edits to `HypervisorPasswordStore.kt`, `HypervisorsFragment.kt` |

**Secrets storage:** PEM private key + optional passphrase in `SecurePasswordManager` under `oci_private_key_${id}` / `oci_passphrase_${id}` — never in the DB. Same pattern as `cloud_token_${id}`.

**Region seed list (Phase 4):** `us-ashburn-1`, `us-phoenix-1`, `us-chicago-1`, `us-sanjose-1`, `ca-toronto-1`, `ca-montreal-1`, `sa-saopaulo-1`, `sa-vinhedo-1`, `sa-santiago-1`, `uk-london-1`, `uk-cardiff-1`, `eu-frankfurt-1`, `eu-amsterdam-1`, `eu-zurich-1`, `eu-stockholm-1`, `eu-marseille-1`, `eu-milan-1`, `eu-madrid-1`, `eu-paris-1`, `me-jeddah-1`, `me-dubai-1`, `me-abudhabi-1`, `ap-tokyo-1`, `ap-osaka-1`, `ap-seoul-1`, `ap-sydney-1`, `ap-melbourne-1`, `ap-mumbai-1`, `ap-hyderabad-1`, `ap-singapore-1`, `af-johannesburg-1`, `il-jerusalem-1`, `mx-queretaro-1`, `mx-monterrey-1`. Custom entry always allowed (Oracle adds regions regularly).

**No new Gradle deps** — BouncyCastle (SSH key parser) and OkHttp (everywhere) already in the build.

---

## 📚 Reference

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing (authoritative ground truth)
- `CLAUDE.md` — operational runbook (build commands, commit policy, file locations)
- `.agent/state.json` — current task state
- `.agent/changelog.md` — session change log
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — F-Droid formatted app description (not the architecture spec)
- `AI.md §18` — design spec for desktop→mobile QR pairing (desktop copy at `tabssh/desktop/QR_PAIRING.md`)
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

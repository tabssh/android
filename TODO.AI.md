# TabSSH TODO

**Last Updated:** 2026-05-02 (full re-verification pass against the codebase — every previously-listed audit item was diff'd against current code; entries that have actually shipped are now ~~struck through~~ with the verifying file:line citations)
**Version:** 0.0.9 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> Treat `CLAUDE.md` and `FEATURES_AUDIT.md` as the authoritative state-of-the-app docs. This file tracks open issues + planned work that hasn't been implemented yet.

---

## ✅ Recently Shipped

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
- **Mosh full protocol** — `protocols/mosh/MoshHandoff.kt:11-35` only bootstraps the SSP exchange and returns a CLI string the user must paste into a real Mosh client. True transparent UDP/AES-128-OCB Mosh would be ~60h. **Likely keep as handoff only — document accordingly.**
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
| `ForwardAgent` / `ForwardX11` | ✅ | JSON | 🟡 — read at connect from the dedicated `agentForwarding`/`x11Forwarding` columns on `ConnectionProfile`, NOT from the JSON. Importer does not currently copy from JSON to those columns. |
| `RequestTTY` | ✅ | JSON | 🟡 — partly: when `remoteCommand` is set we always allocate a PTY (`exec.setPty(true)`), matching `RequestTTY=yes`. The `force`/`no`/`auto` distinctions aren't honored. |

**Fix sketch:** at `SSHConfigParser.convertToConnectionProfile`, copy `forwardAgent`/`forwardX11` from `host.*` straight into `ConnectionProfile.agentForwarding`/`x11Forwarding` (currently they only land in the JSON blob). For `ProxyJump`, parse `user@host:port` and populate the proxy columns directly. Remove the now-redundant JSON copies once both flows are migrated.

**Estimate:** ~3 hours.

---

## 📚 Reference

- `CLAUDE.md` — project tracker, current state, recent waves
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — technical specification (architecture, schema, build)
- `AI.md` §18 — design spec for desktop→mobile QR pairing (folded in from the standalone `QR_PAIRING.md`; the desktop project carries its own copy at `tabssh/desktop/QR_PAIRING.md` for cross-repo reference)
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

# TabSSH TODO

**Last Updated:** 2026-05-01
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

### 🔒 Audit findings — 2026-05-01

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

#### 🟡 P2 — latent / defense-in-depth

- Session passwords held in `String` map for app lifetime, never cleared on pause/destroy — `crypto/SecurePasswordManager.kt:64`.
- Host-key dialog `latch.await()` no timeout — `HostKeyVerifier.kt:470, 546`.
- DB query on `Dispatchers.Main` in widget update — `widget/ConnectionWidgetProvider.kt:66`.
- Jump-host port-forward — no explicit `127.0.0.1` bind on `setPortForwardingL`, JSch default may be `0.0.0.0` — `ssh/connection/SSHConnection.kt:623`.
- `cachedPassword` / `cachedPassphrase` as `String` for connection lifetime, never zeroed — `ssh/connection/SSHConnection.kt:100-104`.
- Host-key dialogs walk the context chain to find an Activity; no guard if Activity destroyed mid-prompt — `HostKeyVerifier.kt:406-436`.
- Logger may surface key bytes if a future caller passes raw bytes (defensive grep audit needed across `Logger.[diwve]` calls touching `bytes`/`key`/`pass`).

#### 🧩 Feature gaps — claimed but not wired

- **`encryptBackup` UI promise — see P0-#1 above.** Same root cause.
- **Hypervisor TLS — see P0-#2 above.** Currently the only "feature" is an unsafe-by-default switch.
- **AWS / GCP / Azure cloud import** — clients fully built (`cloud/AwsEc2Client.kt:57-150`, `GcpComputeClient.kt`, `AzureVmClient.kt`) but `CloudAccountsActivity` has zero menu entry / deep link. Reachable from nowhere. Fix: add Settings → Advanced → Cloud Providers entry. ~5h.
- **X11 toggle hidden** in `app/src/main/res/layout/activity_connection_edit.xml:448` (`switch_x11_forwarding`). Manager (`X11ForwardingManager.kt:1-150`) and JSch hooks (`SSHConnection.kt:996-1002`) are present; the switch isn't bound to save/load in `ConnectionEditActivity`. Fix: unhide + bind. ~5h.
- **SSH user-certificate auth** — `crypto/keys/StoredKey.kt:49-50` has `certificate: String? = null` (added DB v19), `addIdentity` never consumes it. Fix: feed cert bytes alongside the private-key file in `SSHConnection.setupAuthentication`. ~10h.
- **Snippet `{?var:default|hint}` substitution UI** — parser is in `database/entities/Snippet.kt:42-60`, dialog class `SnippetVariableDialog` referenced but unwired to the snippet-execute path. Fix: prompt for values before insertion, cache last-used per (snippet, var) tuple. ~25h.
- **Recordable macros** — `Macro` Room entity + DAO complete (DB v26), zero UI. Fix: `MacroManagerActivity` (record button → byte capture; list with playback to active tab). ~35h.
- **FIDO2 SSH signing** — `crypto/fido/Fido2SshIdentity.kt:35-40` throws `JSchException("FIDO2 SSH signing is alpha and not yet implemented")`. JSch upstream doesn't support `sk-*` key types; needs a JSch fork or alternate library. ~80h. **Likely defer indefinitely.**
- **Mosh full protocol** — `protocols/mosh/MoshHandoff.kt:11-35` only bootstraps the SSP exchange and returns a CLI string the user must paste into a real Mosh client. True transparent UDP/AES-128-OCB Mosh would be ~60h. **Likely keep as handoff only — document accordingly.**
- **Tasker preferences XML orphaned** — `res/xml/preferences_tasker.xml` exists with full UI schema; no fragment inflates it. The intent service IS wired. Fix: add `TaskerSettingsFragment` and a Settings menu entry. ~5h.
- **Xen Orchestra REST `TODO: Implement JSON parsing`** at `hypervisor/xcpng/XenOrchestraApiClient.kt:~52`. WebSocket plumbing works; type-erased response parser isn't finished. ~25h.
- **`activity_main_old.xml`** is an orphan layout (zero `R.layout.X` references). Delete in next cleanup pass.

---

### 📐 QR Pairing — Desktop side
- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS` Phase F line items)
- **Priority:** MEDIUM
- **Spec:** [`AI.md` §18](AI.md#18-qr-pairing--desktop--mobile-setup)

The mobile decoder is in place and waiting for the desktop encoder + interop test vectors. The spec doc has the wire format, encryption parameters, payload schema, and CBOR field names that both sides must agree on.

### 🐛 `advancedSettings` JSON wired through to JSch session config
- **Status:** SCOPED
- **Priority:** MEDIUM
- **Impact:** Many `~/.ssh/config` directives are parsed and persisted but never applied at connect time. The parser writes them to `ConnectionProfile.advancedSettings` (JSON column); the exporter reads them for round-trip; **the connection layer doesn't consult them at all**. Verified by grep — 13 files reference `advancedSettings`, none of them are in `ssh/connection/` or `ssh/auth/`.

| Directive | Parser | Stored | Applied at connect |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | `advancedSettings` JSON | ❌ No |
| `LocalForward` / `RemoteForward` / `DynamicForward` | ✅ | `advancedSettings` JSON | ❌ No |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | `advancedSettings` JSON | ❌ No |
| `ForwardAgent` / `ForwardX11` / `RequestTTY` | ✅ | `advancedSettings` JSON | ❌ No |

**Fix:** wire each into the existing per-feature paths the way Issue #37 wired RemoteCommand. Most are one-liner `session.setConfig(...)` / `setServerAliveInterval(...)` calls; LocalForward/RemoteForward/DynamicForward route to the existing `PortForwardingActivity` / `SSHConnection` forward setup; `ProxyJump` should populate the existing `proxy_host` / `proxy_port` / `proxy_username` columns rather than living in JSON.

**Estimate:** ~6 hours including a parser unit test + an integration test against a known config.

---

## 📚 Reference

- `CLAUDE.md` — project tracker, current state, recent waves
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — technical specification (architecture, schema, build)
- `AI.md` §18 — design spec for desktop→mobile QR pairing (folded in from the standalone `QR_PAIRING.md`; the desktop project carries its own copy at `tabssh/desktop/QR_PAIRING.md` for cross-repo reference)
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

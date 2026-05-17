# TabSSH TODO

**Last Updated:** 2026-05-17
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

- **`e6586a3350e0`** 🐛 Notification text format + backup preference coverage — CONNECTED notification now always shows `host:port-title` (port was absent when title set); connection-type fallback (mosh/telnet/ssh) when no title; `BackupExporter`/`BackupImporter` `preferences.json` now includes `notifications` + `monitoring` categories; backward-compatible with old backups; `AI.md §10` updated.
- **`(this batch)`** ✨ Notification system overhaul — SSH sessions grouped under `tabssh_ssh_sessions` parent summary; monitoring alerts grouped under `tabssh_monitoring`; CONNECTED per-host notifications now include "Disconnect" action that launches `ConfirmDisconnectActivity` (transparent dialog, confirms before closing); `SSHConnectionService` private `"ssh_connections"` channel removed and consolidated into `NotificationHelper.CHANNEL_SERVICE`; `postSshGroupSummary()` called on every 30s heartbeat and sweep; `AI.md §13.1` updated.
- **`61dadd4b8f6f`** 🐛 QEMU/libvirt SSH key identity not loaded for generated keys — `generateKeyPair()` never stored JSch bytes; `retrieveJSchBytes()` returned null; key silently not used; now fixed with `getJSchBytesWithFallback()` on `KeyStorage` (shared by libvirt + `SSHConnection`) and `storeJSchBytes()` called at generate time.
- **`ae68921ee89d`** 🐛 QEMU/libvirt auth failure — `autoDeleteOnFailure` in `SecurePasswordManager` silently wiped Keystore credentials on any decryption error; `LibvirtApiClient.connect()` now fails fast with a helpful message instead of opaque SSH auth failure; `LibvirtManagerActivity` shows "Open Settings" dialog on credential miss; `validateFields()` no longer requires password when SSH key identity is selected for LIBVIRT; `saveHypervisor()` skips `store()` call when password is blank (key-only auth path).
- **`(this batch)`** 📝 Translation drift — 10 missing keys added to `values-es/`, `values-fr/`, `values-de/` (`cluster_progress`, `navigation_drawer_open/close`, `select_connection`, `sync_password_*`, `widget_*_description`).
- **`(this batch)`** 🔧 ProxyJump verified already wired — `SSHConfigParser.kt:299-302` populates proxy columns at parse time; `SSHConnection.setupJumpHost()` reads them. Stale TODO entry closed.
- **`(this batch)`** 🔒 Cached SSH credential zeroing — `SSHConnection.clearCachedCredentials()` + `SSHSessionManager.clearCachedCredentials()` called from `TabSSHApplication.onActivityStopped()` when whole app backgrounds. Prevents in-memory password survival across biometric-lock events.
- **`(this batch)`** ✨ Group badges on flat connection lists — `ConnectionAdapter` shows `"• GroupName"` badge on search result cards; `ClusterCommandActivity.ConnectionSelectionAdapter` shows group badges in multi-select picker. `item_connection.xml` and `item_cluster_connection.xml` updated.
- **`(this batch)`** ✨ X11 forwarding proxy — `X11Proxy.kt` (`ssh/forwarding/`) binds a `ServerSocket` on port 0 and relays JSch X11 channels to Termux:X11 (Unix socket) or XServer XSDL (TCP :6000). `SSHConnection.applyForwardingFlags()` now passes the dynamic port via `session.setX11Port(proxy.port)`. Non-fatal `X11NoServerException` shown as a Snackbar in `TabTerminalActivity` via `SSHConnection.warnings` `SharedFlow`. Proxy stopped in `disconnect()`.
- **`1d40e3f2`** 🐛 Remove spurious "Serial console unavailable" AlertDialog — VNC fallback is transparent; dialog was confusing noise. Only surface error if VNC itself fails.
- **`1d40e3f2`** 🐛 Fix `%s` showing literally in Settings → Monitoring → Alert cooldown — removed broken `android:summary="%s…"` from XML, added programmatic `SummaryProvider` in `MonitoringSettingsFragment` producing e.g. "1 hour between repeated 'still down' notifications".
- **`1d40e3f2`** ✨ QEMU/libvirt SSH key auth — `HypervisorProfile.sshIdentityId` (DB v34→35, `MIGRATION_34_35`), `LibvirtApiClient.connect()` loads key via `KeyStorage.retrieveJSchBytes()` + `jsch.addIdentity()`, `HypervisorEditActivity` adds SSH Key dropdown (LIBVIRT-only).
- **`(prev session)`** 🐛 Landscape dialog clipping — 11 layout files wrapped in `NestedScrollView`; `dialog_backup_runs.xml` RecyclerView fixed from 0dp+weight (invisible) to 200dp.
- **`(prev session)`** 🐛 RFB ExtendedDesktopSize constant wrong — `ENC_EXTENDED_DESKTOP_SIZE` corrected from `-479` to `-308` (0xFFFFFECC), fixing "Pipe closed" VNC crash on resize.
- **`(prev session)`** 🐛 `%s` literals in Logging + Sync preferences — `paste_microbin_url`, `paste_lenpaste_url`, `paste_stikked_url`, `sync_frequency` all fixed with `app:useSimpleSummaryProvider="true"`.
- **`(prev session)`** 🐛 Proxmox serial console fallback — `ConsoleWebSocketClient` detects serial-unavailable frame and calls back to `HypervisorConsoleManager` which retries with vncproxy transparently.
- **`2596eeb7`** ✨ Multi-host Dashboard v2 — sysadmin-grade host cards, dashboard groups (independent from connection groups), DiffUtil metric updates, group CRUD, per-host monitor bell.
- **`55386d5b`** 🐛 OCI SSH Connect ephemeral — persistent SSH config dialog backed by `ConnectionProfile.ociInstanceId`; DB v30→v31.
- **`bfa72c87`** 🐛 Proxmox console fails for VMs without serial interface — API-level detection + automatic vncproxy fallback.
- **`(this batch)`** ✨ OCI hypervisor support — all 7 phases shipped; DB v28→v29; `OciApiClient`, `OciSigner`, `OciManagerActivity`, importer, routing.
- **`ea4f687f`** ✨ QR Pairing mobile side — `ImportFromQrActivity`, `PairingDecryptor` (Argon2id + AES-256-GCM), `QrPayloadCodec` (hand-written CBOR), `PairingImporter`.
- **`d714a7b4`** 🔧 `advancedSettings` local/remote/dynamic forwards wired at connect.
- **`05b7dac1`** 🐛 SSH config `RemoteCommand` + `SendEnv` end-to-end; DB v23→v24.
- **`bed586fe`** 🐛 ANR on app update — `initializeCoreComponents()` moved to background scope; log rotation bounded.

---

## 📋 Documented but not yet implemented

These are in the codebase or spec but **not** working end-to-end. Post-v1 roadmap only.

| Item | AI.md | Effort | Notes |
|---|---|---|---|
| **FIDO2 SSH signing** | §7.1, §16 | ~80h, likely indefinite | `Fido2SshIdentity.kt` throws `JSchException("not yet implemented")`. JSch doesn't support `sk-ecdsa-sha2-nistp256` / `sk-ssh-ed25519` key types. Needs JSch fork or alternate SSH library. |
| **Chinese / Japanese translations** | §16 | ~4h per language | No translators assigned. English fallback works. Post-v1 when translators are available. |
| **QR Pairing — desktop side** | §18 | other repo | Mobile decoder is live (`ea4f687f`). Desktop encoder WIP in `tabssh/desktop`. Wire format + interop test vectors in `AI.md §18` / `QR_PAIRING.md`. |

---

## 📐 QR Pairing — Desktop side

- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS`)
- **Priority:** MEDIUM
- **Spec:** [`AI.md §18`](AI.md#18-qr-pairing--desktop--mobile-setup) / `tabssh/desktop/QR_PAIRING.md`

Mobile decoder is in place and waiting for the desktop encoder and interop test vectors. Wire format, encryption parameters, payload schema, and CBOR field names in the spec.

---

## 🔧 `advancedSettings` — remaining SSH config directives

Local/Remote/Dynamic forwards now apply at connect (`d714a7b4`). Status:

| Directive | Parser | Stored | Applied |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | columns | ✅ — `SSHConfigParser.kt:299-302` populates `proxy_host`/`proxy_port`/`proxy_username` at parse time. `ProxyCommand` has no JSch equivalent. |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | intentionally ignored (mobile keepalive + TOFU dialog own these) |
| `ForwardAgent` / `ForwardX11` / `compression` / `connectTimeout` | ✅ | JSON + columns | ✅ |
| `RequestTTY` | ✅ | JSON | 🟡 — `force` honored when `remoteCommand` set; `no`/`auto` distinctions ignored |

---

## 🧹 Post-v1: Dead code removal

**Recommendation:** Do not do a broad dead code sweep before v1. The risk/reward is wrong — no user-visible gain, and one missed case (Room entity field, preference key, ProGuard keep rule, reflection target) causes a crash or silent data loss.

**Safe to do now (zero crash risk):**
- Unused string resources and drawable references — R8/lint flags them; missing one doesn't crash
- `HypervisorProfile.isXenOrchestra` — deprecated flag superseded by `apiTypeOverride` (AI.md §16); can be removed with a migration that drops the column after v1 schema is locked

**After v1 ships:**
- Full dead code sweep — by then the migration chain is locked, you have a stable test device matrix, and ProGuard keep rules are proven
- Scope: unreferenced classes, orphan layout IDs, dead `when()` branches, unused DAO methods
- Tool: Android Studio "Inspect Code" + `grep -rn` cross-reference before touching anything
- Rule: never remove a symbol that appears in a ProGuard keep rule or is reachable via `findPreference("key")` string literals, even if static analysis shows zero references

**For dead comments in source** (the same caution applies — but lower risk than dead code):
- Safe to remove inline now: comments that describe what a bug *was* before a fix (the fix is the record, not the comment), and `// TODO:` comments in committed code that reference shipped work
- Keep: comments that explain *why* a non-obvious choice was made (architectural context, not fix history)

---

## 📚 Reference

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing
- `CLAUDE.md` — operational runbook (build commands, commit policy, file locations)
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — F-Droid formatted app description
- `AI.md §18` — QR pairing wire format (desktop copy at `tabssh/desktop/QR_PAIRING.md`)
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

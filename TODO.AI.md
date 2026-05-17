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

These are in the codebase or spec but **not** working end-to-end. All are roadmap items, not bugs.

| Item | AI.md | Effort | Notes |
|---|---|---|---|
| **X11 forwarding rendering** | §5.3, §16 | ~40h | Toggle persisted + in UI; old in-app X server deleted as dead code. Enabling the toggle currently does nothing. Needs a real VcXsrv-style virtual display or a forwarding-only path. |
| **FIDO2 SSH signing** | §7.1, §16 | ~80h, likely indefinite | `Fido2SshIdentity.kt` throws `JSchException("not yet implemented")`. JSch doesn't support `sk-ecdsa-sha2-nistp256` / `sk-ssh-ed25519` key types. Needs JSch fork or alternate SSH library. |
| **Chinese / Japanese translations** | §16 | ~4h per language | `values-zh/` and `values-ja/` directories absent. English fallback works but is not acceptable for a v1 targeting those locales. |
| **Connection groups on all list surfaces** | §16 | ~4h | `ConnectionGroup` entity + DAO + adapter all exist; some list views still render flat (no group headers). |
| **QR Pairing — desktop side** | §18 | other repo | Mobile decoder is live. Desktop encoder WIP in `tabssh/desktop`. Wire format + interop test vectors in `AI.md §18` / `QR_PAIRING.md`. |
| **advancedSettings `ProxyJump`** | TODO | ~1h | Parsed from `~/.ssh/config`, stored in JSON `advancedSettings`, but not wired to `proxy_host`/`proxy_port`/`proxy_username` columns. Fix: populate columns at import time. |
| **Cached passwords not zeroed on lifecycle** | P2 | ~2h | `SecurePasswordManager` in-memory map and `SSHConnection.cachedPassword`/`cachedPassphrase` survive biometric-lock, pause, and destroy. Defense-in-depth fix: clear on `onPause()` or on biometric-lock event. |
| **Translation drift** | §16 | ~1h | 10 keys missing from `values-es/`, `values-fr/`, `values-de/` string files (`cluster_progress`, `widget_*_description`, `sync_password_*`, `navigation_drawer_open/close`, `select_connection`). Silent English fallback at runtime. |

---

## 📐 QR Pairing — Desktop side

- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS`)
- **Priority:** MEDIUM
- **Spec:** [`AI.md §18`](AI.md#18-qr-pairing--desktop--mobile-setup) / `tabssh/desktop/QR_PAIRING.md`

Mobile decoder is in place and waiting for the desktop encoder and interop test vectors. Wire format, encryption parameters, payload schema, and CBOR field names in the spec.

---

## 🔧 `advancedSettings` — remaining SSH config directives

Local/Remote/Dynamic forwards now apply at connect (`d714a7b4`). Remaining:

| Directive | Parser | Stored | Applied |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | JSON | ❌ — `ProxyJump` should populate `proxy_host`/`proxy_port`/`proxy_username` at parse time. `ProxyCommand` has no JSch equivalent and would require a custom `Proxy` impl. |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | intentionally ignored (mobile keepalive + TOFU dialog own these) |
| `ForwardAgent` / `ForwardX11` / `compression` / `connectTimeout` | ✅ | JSON + columns | ✅ |
| `RequestTTY` | ✅ | JSON | 🟡 — `force` honored when `remoteCommand` set; `no`/`auto` distinctions ignored |

**Fix:** For `ProxyJump`, parse `user@host:port` and populate existing proxy columns directly. ~1h.

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

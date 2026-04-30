# TabSSH TODO

**Last Updated:** 2026-04-29
**Version:** 0.0.9 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> Treat `CLAUDE.md` and `FEATURES_AUDIT.md` as the authoritative state-of-the-app docs. This file tracks open issues + planned work that hasn't been implemented yet.

---

## ✅ Recently Shipped

### QR Pairing — Mobile side
- **Commit:** `ea4f687f572f` ("Added QR code support")
- **Spec:** [`QR_PAIRING.md`](QR_PAIRING.md)
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

### 📐 QR Pairing — Desktop side
- **Status:** 🔧 In progress (other instance — see `../desktop/.git/COMMIT_MESS` Phase F line items)
- **Priority:** MEDIUM
- **Spec:** [`QR_PAIRING.md`](QR_PAIRING.md)

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
- `QR_PAIRING.md` — design spec for desktop→mobile QR pairing
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

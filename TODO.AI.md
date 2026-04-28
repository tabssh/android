# TabSSH TODO

**Last Updated:** 2026-04-28
**Version:** 1.0.0 (pinned via release.txt — DO NOT MODIFY)

> Treat `CLAUDE.md` and `FEATURES_AUDIT.md` as the authoritative state-of-the-app docs. This file tracks open issues + planned work that hasn't been implemented yet.

---

## 🐛 Open Issues

### 🐛 Issue #36: ANR on app update — startup blocks on something stored in `/data`
- **Status:** 🔬 INVESTIGATING — needs ANR watchdog trace from a reproducing device
- **Priority:** HIGH (every update is a potential outage for users)
- **Impact:** App ANRs on first launch after update. Clearing **storage** fixes it (clearing cache alone does NOT). Storage clear is a non-starter — it nukes connections, keys, settings.

**Reproduction:**
1. Have TabSSH installed and use it normally (build up real data: connections, keys, settings, debug logs)
2. Install a newer build over the top
3. Launch — ANR on cold start. Sometimes recovers, sometimes "App not responding" dialog.
4. Settings → Apps → TabSSH → Clear Storage fixes it (and wipes all data)

**Top suspects, in likelihood order:**
1. **Pre-update crash report being read on the main thread.** `Thread.setDefaultUncaughtExceptionHandler` writes to a crash file; `CrashReportActivity` opens on next launch. If the saved crash blob is large or read synchronously, startup blocks. Check `/data/data/io.github.tabssh/files/` for `crash_report*` files.
2. **`Logger.initialize` reading a huge log file** on the main thread inside `TabSSHApplication.onCreate`. Auto-on for debug builds means log files can grow unbounded.
3. **`SecurePasswordManager` cold-load** decrypting all stored passwords / cloud tokens eagerly on first access — the encrypted SharedPreferences file is parsed in full synchronously.
4. **Room migration touching every row.** v17 → v23 are all small `ALTER TABLE`s on paper, but worth grepping for any column added with a `NOT NULL` + computed default which forces Room to rewrite the whole table.

**Files Likely Involved:**
- `app/src/main/java/io/github/tabssh/TabSSHApplication.kt` (`onCreate` is on the main thread)
- `app/src/main/java/io/github/tabssh/utils/logging/Logger.kt` (`initialize`)
- `app/src/main/java/io/github/tabssh/ui/activities/CrashReportActivity.kt`
- `app/src/main/java/io/github/tabssh/storage/database/TabSSHDatabase.kt` (migrations)
- `app/src/main/java/io/github/tabssh/crypto/storage/SecurePasswordManager.kt`
- `app/src/main/java/io/github/tabssh/utils/diagnostics/AnrWatchdog.kt` (this is what should catch the trace)

**Concrete next step:**
1. Reproduce the ANR with debug logging on (auto-on for debug builds)
2. `Settings → Logging → Copy Debug Logs` — the ANR watchdog should have captured the stuck main-thread stack
3. The trace identifies the blocking call in <30 seconds of inspection
4. Fix the specific blocker

**Belt-and-suspenders defenses (worth doing regardless of the trace):**
- Move `Logger.initialize` body to a background thread (queue writes; `Application.onCreate` returns immediately)
- Cap log file size with rotation (1 MB main, roll to `.log.1` … `.log.5`, drop older)
- Make the crash-report check non-blocking — read flag on main, do the file read off-thread, post the dialog
- Defer `SecurePasswordManager` decryption until a credential is actually needed (lazy init)
- Audit all of `TabSSHApplication.onCreate` for any synchronous I/O — none should be there

**Estimate:** 1–2h to add the defenses above + 30 min to fix once we have the trace.

---

## 📝 Planned Work

### 📐 QR Pairing — Desktop → Mobile setup
- **Status:** ✅ MOBILE SIDE SHIPPED 2026-04-28 — see `QR_PAIRING.md`. Desktop side TBD.
- **Priority:** MEDIUM
- **Estimate:** ~5–6 days for v1 (mobile side complete; desktop side ~2 days remaining)

Design captured in [QR_PAIRING.md](QR_PAIRING.md). Brief: desktop renders an encrypted QR + 6-digit code; phone scans it, types the code, imports the connection profiles. Argon2id-derived AES-256-GCM. CBOR payload. Single-frame QR up to ~10 connections in v1. No private-key transfer in v1 (phone generates its own keypair).

Mobile side is implemented — 5 new Kotlin files (~1,016 LOC) + ZXing dep + CAMERA permission + drawer entry. Compile-clean. Awaiting desktop-side encoder + interop test vectors.

Touches both `tabssh/android` and `tabssh/desktop`.

---

### 🐛 Issue #37: SSH config `RemoteCommand` + `SendEnv` not applied at connect time
- **Status:** 🔬 SCOPED — known gap, ready to implement
- **Priority:** HIGH (silent breakage for any host that needs an explicit command — SourceForge, forced-command jails, gateway/jump menus)
- **Impact:** A user imports a working `~/.ssh/config` from their laptop. The connection profile *looks* correct in the UI. They hit Connect — auth succeeds, then the connection hangs / closes silently because the remote needs a `RemoteCommand` to spawn a shell and TabSSH never sends it. From the user's perspective: "my laptop SSH works fine, why is TabSSH broken?"

**Concrete failing case:**
```
Host sourceforge
  HostName shell.sourceforge.net
  User username,project
  RemoteCommand create
  RequestTTY yes
```
On `shell.sourceforge.net`, an SSH session that opens a regular shell channel just disconnects — the remote requires `create` to be passed as an exec command to actually spawn a shell. Same pattern in:
- Hosts with `command="…"` forced in `authorized_keys` (security jails, automation accounts)
- JumpCloud / Teleport / etc. gateway hosts that drop you into a menu unless you pass a command
- SFTP-only accounts (`RemoteCommand internal-sftp`)

**Current behaviour (verified 2026-04-28):**

| Directive | Parser | Stored | Applied at connect |
|-----------|--------|--------|---------------------|
| `User` | ✅ | `ConnectionProfile.username` | ✅ Yes |
| `HostName` / `Port` / `IdentityFile` | ✅ | dedicated columns | ✅ Yes |
| `Compression` / `ConnectTimeout` | ✅ | dedicated columns | ✅ Yes |
| `ProxyJump` / `ProxyCommand` | ✅ | `advancedSettings` JSON | ❌ No |
| `LocalForward` / `RemoteForward` / `DynamicForward` | ✅ | `advancedSettings` JSON | ❌ No |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | `advancedSettings` JSON | ❌ No |
| `ForwardAgent` / `ForwardX11` / `RequestTTY` | ✅ | `advancedSettings` JSON | ❌ No |
| **`RemoteCommand`** | ❌ falls into `additionalOptions` | `advancedSettings` JSON | ❌ **No** |
| **`SendEnv`** | ❌ falls into `additionalOptions` | `advancedSettings` JSON | ❌ **No** |

The `advancedSettings` JSON column is **written** by the parser and **read** by the exporter (round-trip), but **nothing in `ssh/connection/` or `ssh/auth/` reads it back to apply at connect time**. Verified by grep — 13 files reference `advancedSettings`, none of them are the connection layer.

**Files Likely Involved:**
- `app/src/main/java/io/github/tabssh/ssh/config/SSHConfigParser.kt` — add explicit cases for `remotecommand` and `sendenv` in the `when` block (~lines 132–167)
- `app/src/main/java/io/github/tabssh/ssh/config/SSHConfigExporter.kt` — emit `RemoteCommand` and `SendEnv` lines on round-trip
- `app/src/main/java/io/github/tabssh/storage/database/entities/ConnectionProfile.kt` — new column `remote_command: String?` (env vars already have `envVars` from Wave 1.2)
- `app/src/main/java/io/github/tabssh/storage/database/TabSSHDatabase.kt` — DB v23 → v24 migration: `ALTER TABLE connections ADD COLUMN remote_command TEXT`
- `app/src/main/java/io/github/tabssh/ssh/connection/SSHConnection.kt` — when `remoteCommand` is non-null, open a `ChannelExec` instead of `ChannelShell`; allocate a PTY on the exec channel if `RequestTTY yes` is present (or always when interactive, since SourceForge needs it)
- `app/src/main/java/io/github/tabssh/ui/activities/ConnectionEditActivity.kt` — new dropdown + custom-text-input UX (see below)
- `app/src/main/res/layout/activity_connection_edit.xml` — new Spinner + EditText group
- `app/src/main/res/values/arrays.xml` — array for the preset list
- Apply `SendEnv` similarly: parse into `envVars` field (we already have it; Wave 1.2)

**Fix plan:**

1. **Parser** — add explicit cases in `SSHConfigParser.kt` for both directives:
   ```kotlin
   "remotecommand" -> currentHost?.remoteCommand = value
   "sendenv" -> currentHost?.sendEnv?.add(value)
   ```
   Add `remoteCommand: String?` and `sendEnv: MutableList<String>` to the `SSHHost` data class.

2. **Conversion** — `convertToConnectionProfile` writes `host.remoteCommand` to the new `remoteCommand` column; combines `host.sendEnv` entries into the existing `envVars` multi-line text field.

3. **DB migration v23 → v24** — single `ALTER TABLE connections ADD COLUMN remote_command TEXT`. Follow the established pattern from MIGRATION_22_23.

4. **Connection logic** — in `SSHConnection.openSession`, branch on `connection.remoteCommand`:
   ```kotlin
   if (!connection.remoteCommand.isNullOrBlank()) {
       val channel = session.openChannel("exec") as ChannelExec
       channel.setCommand(connection.remoteCommand)
       channel.setPty(true)  // PTY needed for interactive RemoteCommands like `create`
       // ...wire stdin/stdout/stderr like the shell channel does
   } else {
       // existing shell channel path
   }
   ```
   `ChannelExec.setPty(true)` is the JSch equivalent of `RequestTTY yes`.

5. **UX — Remote Command field in ConnectionEditActivity (per user's design choice 2026-04-28):**

   Layout: a **Spinner** ("Remote Command") + a conditionally-visible **EditText** for "Custom".

   Preset list (in `arrays.xml`):
   - **Default — login shell** (selecting this clears the field; behaves as today)
   - **`create`** (SourceForge shell.sourceforge.net pattern)
   - **`sftp`** (SFTP-only accounts — uses the SFTP subsystem on the remote)
   - **`internal-sftp`** (OpenSSH built-in SFTP subsystem)
   - **`tmux attach -d || tmux new`** (auto-attach to a tmux session, create if none)
   - **`screen -DR`** (auto-attach to a screen session, create if none)
   - **Custom…** (reveals the EditText for arbitrary input)

   Rules:
   - Default selection is "Default — login shell" → `remoteCommand` saved as null/empty
   - When a preset is selected, the EditText hides and the saved value is the preset string
   - When "Custom…" is selected, the EditText shows with whatever the current value is
   - **On import:** if the imported `RemoteCommand` matches one of the presets verbatim, snap the spinner to that preset; otherwise pre-select "Custom…" with the imported value pre-filled in the EditText
   - Help text below the field: "For most hosts leave this empty. Some hosts (e.g. shell.sourceforge.net) require a specific command to spawn a shell."

6. **Exporter round-trip** — `SSHConfigExporter` emits `RemoteCommand <value>` when set, and `SendEnv <name>` for each non-empty env var (only the names, per OpenSSH convention — values are taken from the local environment at connect time, but we should split this carefully: TabSSH `envVars` are name=value pairs, OpenSSH `SendEnv` is name-only. Probably emit `SendEnv` for the name and store the value in our env_vars column. Document this gracefully on round-trip.)

7. **Tests:** at minimum a parser unit test covering a SourceForge-style entry; an integration test against a real SSH server (the existing throwaway sshd at `127.0.0.1:2222` from the Phase 7 emulator session) that has a forced-command authorized_keys to verify the exec channel path works end-to-end.

**Estimate:** ~4–6 hours including testing.

**Bonus (cheap to do at the same time):** the parser's `additionalOptions` JSON dump is currently inert — none of `ProxyJump`, `LocalForward`, `RequestTTY`, etc. are read at connect time. Worth filing as a separate broader issue: "wire `advancedSettings` JSON through to JSch session config." Out of scope for this issue but flagged in the comparison table above.

---

## 📚 Reference

- `CLAUDE.md` — project tracker, current state, recent waves
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — technical specification (architecture, schema, build)
- `QR_PAIRING.md` — design spec for desktop→mobile QR pairing


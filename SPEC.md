# TabSSH Android — Project Rule Overrides

> This file overrides or extends the global `~/.claude/CLAUDE.md` rules specifically for this project. SPEC.md wins over AI.md, which wins over global rules.

---

## Commit workflow

`git commit` and `git push` are denied on this project. Use:

```
gitcommit --dir {project_dir} all
```

Pre-commit sequence:
1. `git status --porcelain` + `git diff --stat` — confirm scope
2. Write `.git/COMMIT_MESS` from the diff output (never from memory)
3. Re-read `COMMIT_MESS` and verify it matches the diff exactly
4. Run `gitcommit --dir /root/Projects/github/tabssh/android all`

Format: `{emoji} Title ≤64 chars {emoji}` + blank line + body + `- file: what changed` bullets per file. No AI attribution.
Emoji: 🐛 fix · ✨ feat · 📝 docs · ♻️ refactor · ⚡ perf · ✅ test · 🔒 security · 🗃️ db · 🚀 release · 🔧 chore.

**Green build = commit immediately** — `make check` exit 0 means commit without asking.

## Database changes

Any change to the Room schema (new column, new table, altered type) requires:
1. Bump `TabSSHDatabase` `version` constant
2. Add a `Migration` object for the new version step
3. Register the migration in the `databaseBuilder` migration chain
4. Export the updated schema: `make check` triggers schema export automatically
5. Document the step in `AI.md §8.4` migration table

**Never destructive-migrate** — SQLite < 3.35 does not support `DROP COLUMN`; drop is forbidden. Rename by adding a new column, migrating data, and leaving the old column in place.

## Sync surface

New persisted entities that should sync across devices must be added to:
- `sync/SyncDataCollector.kt` — collection side
- `sync/SyncDataApplier.kt` — application side

Update `AI.md §9.4` sync coverage matrix when the surface changes.

## Secrets storage rules

| Credential type | Storage location |
|---|---|
| SSH session passwords | `SecurePasswordManager` (Keystore AES-GCM) |
| SSH key passphrases | `SecurePasswordManager` |
| Hypervisor inline passwords | `HypervisorPasswordStore` |
| Reusable hypervisor account passwords | `HypervisorPasswordStore.storeAccountPassword()` |
| OCI PEM private key | `HypervisorPasswordStore.storeOciAccountKey()` — alias `oci_private_key_${accountId}` |
| OCI key passphrase | `HypervisorPasswordStore.storeOciAccountPassphrase()` — alias `oci_passphrase_${accountId}` |
| Cloud provider tokens | `SecurePasswordManager`, key `cloud_token_${accountId}` |

**Never** write any of the above to the Room database. The DB columns for passwords are always empty strings for any row touched by current code.

## Paste service quirks

**MicroBin** (`mb.pste.us` and any self-hosted MicroBin instance) returns HTTP 404 on raw-paste URLs but still delivers the paste body. `curl -f` treats 404 as an error and discards the body. Always fetch MicroBin URLs **without `-f`**:

```bash
curl -qLs "https://mb.pste.us/raw/<id>"   # correct — no -f
curl -q -LSsf "https://mb.pste.us/raw/<id>"  # WRONG — -f discards the body
```

## Temp files

All temporary files for this project go in `/tmp/tabssh-android/`. Never create temp files in the project root or `app/build/`.

(Note: this overrides the global convention of `/tmp/{project_org}/{internal_name}-XXXXXX` — the fixed path is intentional for script compatibility.)

## Screenshots

Android screenshots are 1080×2400+. Downscale before using `Read`:

```bash
python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png
```

## Docker / build

Do not volume-mount `/opt/android-sdk` — that overlays the baked SDK in the build container. The correct bind-mounts are:
- Source tree → `/workspace`
- Gradle cache and AVD state → named compose volumes (defined in `docker/docker-compose.yml`)

The device is on a remote server and cannot be connected via ADB. Logcat is unavailable. All debugging must be done via static code analysis only.

## VNC-as-console architecture

TabSSH treats VNC as a **text console transport**, not a GUI desktop viewer. The target users are server admins who want keyboard access to a machine's console (BIOS POST, boot loader, login prompt, text-mode shell). Full GUI viewers exist elsewhere; we are not that.

### Philosophy

- Render VNC output through `TermuxBridge` → `TerminalView`, exactly as SSH and Telnet do.
- No framebuffer bitmap rendering for direct VNC connections. The VNC framebuffer is not displayed as a scaled image.
- No mouse passthrough. No clipboard sync to remote desktop. No file drag-drop.
- The same custom key row (Esc, Tab, Ctrl, Alt, arrows, Fn keys) and system keyboard used for SSH appear for VNC. `KeyboardHandler` translates Android `KeyEvent` codes to RFB `KeyEvent` messages using an X11 keysym table.
- Terminal dimensions (cols × rows) are negotiated via RFB `SetDesktopSize` (pseudo-encoding 0xFFFFFE21, `ExtendedDesktopSize`). On view resize, send a new `SetDesktopSize` request — same path as SSH `resizePtyOf()`.
- Send `FramebufferUpdateRequest` for the full framebuffer on connect; from then on the flow is: keys in → RFB KeyEvent out, RFB FramebufferUpdate in → feed raw bytes to `TermuxBridge` as if they were SSH stdout.

### `VncConsoleChannel`

`vnc/console/VncConsoleChannel.kt` — implements the same `InputStream` / `OutputStream` interface that `TermuxBridge` consumes:

- **Input side** (server → terminal): RFB `FramebufferUpdate` messages are received and their raw pixel data is **not rendered**. Instead, for console-mode VNC, the channel extracts the ZRLE/Zlib-compressed text bytes or falls back to treating the raw framebuffer bytes as terminal output when the server is running in text/framebuffer mode. The clean path: negotiate `CopyRect` + `Raw` encodings only; this gives line-by-line byte changes that, for a text-mode console, are valid ANSI/VT100 sequences.
- **Output side** (terminal → server): `KeyboardHandler` converts Android `KeyEvent` → X11 keysym → RFB `KeyEvent` (message type 4, down + up pair). No pointer events.
- Piped `PipedInputStream` / `PipedOutputStream` pairs connect `VncConsoleChannel` to `TermuxBridge`, matching the `HypervisorConsoleManager.wireToTerminal()` pattern already used for hypervisor consoles (§11.6 of AI.md).

### Keyboard rules for VNC

- All keys go through `KeyboardHandler`, same code path as SSH.
- X11 keysym table must cover at minimum: printable ASCII (0x0020–0x007E), Latin-1 supplements (0x00A0–0x00FF), and the control keysyms: BackSpace (0xFF08), Tab (0xFF09), Return (0xFF0D), Escape (0xFF1B), Delete (0xFFFF), cursor arrows (0xFF51–0xFF54), F1–F12 (0xFFBE–0xFFC9), Ctrl/Shift/Alt modifiers (0xFFE1–0xFFEA).
- Ctrl+key sequences: send the modifier down, then the key, then the key up, then the modifier up — matching RFB spec §7.5.4.
- Custom key row buttons map to the same keysyms as their SSH counterparts (e.g. Esc → 0xFF1B, Tab → 0xFF09).
- Both custom keyboard and Android system keyboard must be present for VNC sessions. Do not suppress either.

### Proxmox serial console requirements

Proxmox `PROXMOX_TERM` (the `termproxy` endpoint) requires the VM to have a serial device configured:

```
# In Proxmox VM config (/etc/pve/qemu-server/<vmid>.conf):
serial0: socket
```

Without it, the WebSocket frame `"unable to find a serial interface"` is returned and the terminal console path is unavailable.

**Required error handling:** when `ConsoleWebSocket` receives the `"unable to find a serial interface"` binary frame, do not silently fall back to VNC graphical mode. Instead:

1. Show a dismissible banner in `VMConsoleActivity`:  
   *"Serial console unavailable — VM has no serial device. Add `serial0: socket` in Proxmox → VM Hardware, then reboot the VM. Falling back to VNC console mode."*
2. Proceed with the `vncproxy` fallback, but open it through `VncConsoleChannel` (console mode), not `VncView` (GUI mode).

The VNC fallback for Proxmox should be console-mode only — same `TerminalView` + `TermuxBridge` path. `VncView` (the graphical bitmap renderer) is reserved for future opt-in GUI mode and must not be the automatic fallback.

### RFB session sizing

- On connect: send `SetDesktopSize` immediately after `ServerInit`, using the current `TerminalView` cols × rows converted to pixels (cols × font_width_px, rows × font_height_px). Default: 80×24 cells.
- On view resize (`TerminalView.onSizeChanged`): send a new `SetDesktopSize`. Do not send the initial 80×24 resize if the view has already measured — check `cols > 0 && rows > 0` before the initial send to avoid the double-resize race visible in logs (resize sent at 80×24, then re-sent at the measured size a few hundred milliseconds later).
- `SetDesktopSize` is sent as a `SetEncodings` pseudo-encoding list including `ExtendedDesktopSize` (0xFFFFFE21) so the server knows to accept resize requests.

### Direct VNC connections (ConnectionEditActivity)

Direct VNC hosts (stored in `vnc_hosts` table, edited via `ConnectionEditActivity`) connect via `VncConsoleChannel` directly over TCP (no WebSocket wrapper). The connection flow:

1. TCP connect to `VncHost.effectivePort` (default 5900).
2. RFB version handshake (offer 3.8; accept 3.3/3.7/3.8).
3. Security negotiation — respect `VncHost.securityType`:
   - `"none"` → security type 1 (no auth).
   - `"vnc_auth"` → security type 2 (DES challenge; password from `SecurePasswordManager` under key `vnc_identity_${identityId}` or `vnc_host_${id}`).
   - `"auto"` → let server pick; if type 2 is offered, use it.
4. `ClientInit` with `shared-flag = 0` (exclusive access for a console session).
5. `SetEncodings`: Raw, CopyRect, ExtendedDesktopSize. No Zlib, no Tight, no ZRLE — text-mode consoles don't need compression encodings and the simpler set makes the byte-extraction path deterministic.
6. `SetDesktopSize` with terminal dimensions.
7. `FramebufferUpdateRequest` (incremental=0) for the full framebuffer.
8. Wire to `TermuxBridge` via `VncConsoleChannel` piped streams.

### What `VncView` is for

`VncView` (the bitmap/graphical renderer) is **not** used for the console path. It exists for future opt-in graphical mode (e.g. a user explicitly wants to view a desktop). It is not wired to the Proxmox VNC fallback or to direct VNC connections in the current implementation. Any code path that reaches `VncView` today (graphical fallback in `VMConsoleActivity`) should be replaced with the `VncConsoleChannel` path.

---

## Threading rules (Android-specific)

- `lifecycleScope.launch {}` defaults to `Dispatchers.Main` — never call Keystore, database, or filesystem operations inside a bare launch without switching to `Dispatchers.IO`
- Room `suspend` DAO functions dispatch to Room's internal IO thread automatically — safe to call from any coroutine
- `SecurePasswordManager.retrievePassword()` is `suspend` but does NOT internally switch dispatchers — always wrap in `withContext(Dispatchers.IO)` before calling
- All `HypervisorPasswordStore` `store*` / `retrieve*` methods use `withContext(Dispatchers.IO)` internally — safe from any coroutine
- SAF launcher callbacks fire on Main; use `val ctx = context ?: return@register` to capture context before launching IO, then guard `withContext(Dispatchers.Main)` blocks with `if (!isAdded) return@withContext`

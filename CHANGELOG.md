# Changelog

All notable changes to TabSSH are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added

- **Mosh auto mode** — per-connection Mosh setting is now three-way: Off / Auto (default) / On; "Auto" silently tries `mosh-server` on the remote and falls back to plain SSH if it isn't installed; the old on/off toggle is replaced by a dropdown in the connection editor; all existing connections default to "Auto"

- **Export private key** — "⬇️ Export private key…" option in the SSH key actions menu; prompts for an optional passphrase; exports the key in OpenSSH PEM format (encrypted with AES-256-CBC if a passphrase is provided, unencrypted otherwise) via the system file picker

- **Install SSH key on server** — "⬆️ Install on server…" option in the SSH key actions menu; shows a single-select list of saved SSH connections; connects and runs an idempotent `authorized_keys` install command (creates `~/.ssh` if absent, skips if the exact key line is already present); confirms success or "already installed" via toast

- **Session persistence** — `SessionPersistenceManager` is now wired as an `ActivityLifecycleCallbacks`; saves terminal scrollback and tab state to the database every 30 s while the app is in the foreground, immediately on background, and on every `onSaveInstanceState`; restores sessions on foreground return if the app was backgrounded for less than 24 h; applies auto-lock and clipboard-clear security policies on background

- **Volume key action setting** — Settings → Terminal → Volume Key Action; three options: "Font size (+ / −)" (default, preserves existing behaviour), "Scroll (page up / down)" (Volume Up = older content, Volume Down = newest), "System volume (off)"; existing `volume_keys_font_size` boolean preference is migrated automatically on first launch

- **Full preference sync and backup** — connection defaults, sync toggles, multiplexer key bindings, accessibility flags, and proxy configuration are now included in both SAF sync and backup/restore; previously these five categories were silently absent from both systems

### Changed

- **Identities tab reordered** — sections now appear as: Host Identities → SSH Keys → VM Credentials → VNC Identities; the former "Virtualization Identities" section is renamed "VM Credentials" with a shorter subtitle
- **OCI accounts removed from Identities tab** — OCI API-key credentials are managed exclusively through the dedicated OCI wizard; they are filtered from the VM Credentials list and the create/edit dialog no longer offers an OCI option
- **VNC identity dialog uses Material TextInputLayout** — replaced the programmatic plain-`EditText` dialog with a proper `TextInputLayout` form matching the rest of the app; password field gains visibility toggle and correct mask/replace behaviour

### Added

- **Bracketed paste** — pasting into vim, nano, or any editor that enables `?2004` (bracketed paste mode) now works correctly; the app tracks `ESC[?2004h`/`ESC[?2004l` from the server and wraps paste data in `ESC[200~` / `ESC[201~`; large pastes (configs, scripts, SQL) are chunked at 4 KB to prevent stalling the SSH write path; CRLF and bare LF are normalised to CR on the way out

- **OSC 8 hyperlinks** — SSH and VM console sessions now recognise OSC 8 hyperlink sequences (`\e]8;params;url\e\\anchor\e]8;;\e\\`); long-pressing an anchor word opens the embedded URL rather than relying on regex guessing; anchor text is underlined in link-blue during rendering for both the Termux path (TermuxBridge intercept) and the custom emulator path (ANSIParser + TerminalChar.url)
- **Visual URL underlines** — every detected URL (OSC 8 or regex-matched) is now underlined with a thin colored rect drawn below the text during render; color follows the theme's primary hue when set, otherwise defaults to a link-blue that reads on both dark and light backgrounds

### Fixed

- **Data wiped on app update** — removed `fallbackToDestructiveMigration()` from the Room database builder; future app updates will no longer silently destroy saved connections, SSH keys, and settings; any future schema change must supply a proper `Migration` object

- **SQLite power-loss corruption** — database now opens in WAL (Write-Ahead Logging) journal mode; writes are atomic even if the device loses power mid-write; the previous DELETE journal mode could produce a truncated or corrupted database file

- **SSH key delete could silently corrupt state** — deleting a key now follows Keystore → SharedPrefs (synchronous commit) → DB order; previously, the DB row was removed first so a process kill halfway through left an orphaned Keystore entry with no matching DB record; new order ensures the key remains fully intact if the Keystore step fails, and the ciphertext is flushed synchronously before the record is removed

- **Stored password cleanup used async SharedPrefs write** — `SecurePasswordManager.clearPersistedPassword()` used `apply()` (fire-and-forget); changed to `commit()` (synchronous, blocking) so the encrypted credential is guaranteed to be removed from disk before the Keystore key is deleted; also moved the Keystore delete before the SharedPrefs clear so a crash mid-cleanup leaves unreadable ciphertext rather than a live key with missing data

- **Room schema export disabled** — `exportSchema` is now `true`; KSP emits a JSON schema file per DB version into `app/schemas/`; future migrations can be validated against the expected schema at compile time instead of only failing at runtime on a user's device

- **URL detection matched trailing punctuation** — URLs followed by `.`, `,`, `)`, `]`, `'`, `"`, `;`, `:`, `!`, or `?` (as in normal prose) incorrectly included those characters in the matched URL; a trailing-strip pass now removes them
- **URL detection joined unrelated lines** — the word-wrap cross-row URL join (for URLs that split at a terminal column boundary) fired even on rows that ended with a hard newline; for VM console sessions the new `TerminalBuffer.isRowWrapped()` flag is now consulted so only genuinely soft-wrapped rows are joined; for SSH sessions the Termux library's `'\n'`-at-hard-newline behaviour already prevents a false match in the combined text
- **URL detection missed common schemes** — `ftp://`, `ftps://`, `ssh://`, `git://`, `svn://`, `file://` were not matched; all are now included

- **VM stop button had no effect on Proxmox** — the Stop button was sending `virsh`'s graceful ACPI shutdown signal (`/status/shutdown`), which requires the QEMU guest agent to be installed and responding; changed to `/status/stop` (hard power-off) which always works regardless of guest state
- **VM stop button had no effect on OCI** — the Stop action was sending `SOFTSTOP` (ACPI graceful), which silently did nothing when the OCI cloud agent was absent or the instance was unresponsive; changed to `STOP` (hard power-off) so the button is always reliable; errors are now logged instead of silently swallowed
- **OCI all actions failed after first instance load (TOFU cert loop)** — the TLS pin is stored as `"sha_identity;sha_iaas"` at fixed positions, but `getCapturedCertSha256()` used `listOfNotNull` which collapsed missing slots; an IAAS-only pin was stored as a bare `"sha"` with no semicolons, so on reload it was read into index 0 (identity) while `iaasPinnedSha` stayed null; every subsequent action triggered a fresh TOFU cert dialog that defaulted to REJECT after 30 s; fixed by always writing the fixed `"idSha;iaasSha"` format (empty slot = empty string, not omitted) and removing the `filter { isNotBlank() }` that compacted positions on parse
- **VM stop button had no effect on Libvirt / KVM** — the Stop button was calling `virsh shutdown` (graceful, requires guest agent); changed to `virsh destroy` (hard power-off) which cuts power immediately and always succeeds
- **Copy screen broke word-wrapped lines** — "Copy screen" (TermuxBridge) appended a `\n` after every display row unconditionally; long lines that soft-wrapped across two rows were split at the column boundary; fixed by delegating to a single `getSelectedText(0, 0, cols, rows-1)` call which respects the Termux library's `mLineWrap[]` flags
- **Copy screen broke word-wrapped lines in VM console** — `TerminalEmulator.getScreenContent()` had the same unconditional per-row `\n` injection; the underlying `TerminalBuffer` now tracks per-row soft-wrap flags (`rowWrapped[]`), set when auto-wrap fires and cleared on hard newlines, scroll, insert/delete line, and resize; `getScreenContent()` skips `\n` for wrapped rows so the logical line is reconstructed correctly

- **Identity picker didn't show selection in Connections → Edit host → Identity** — `MaterialAutoCompleteTextView` requires an explicit `setText(item, false)` call in the item-click listener to display the selected item; the SSH identity listener was missing this call (the VNC listener already had it); added to the SSH path so tapping an identity name now visually sticks in the field
- **Group long-press menu lacked "Bulk edit all hosts"** — the three-item context menu (Rename / Delete / Collapse All) now has "Bulk edit all hosts in this group" as the first entry, wiring directly into the existing `showBulkEditDialog` path
- **"Create Paste" bottom sheet clipped off-screen on mobile** — `ReportIssueDialog` built a plain `LinearLayout` with no scrolling; on small screens the action buttons were out of reach; wrapped the root in a `NestedScrollView` and added `onViewCreated` to force `STATE_EXPANDED` + sensible peek height at show time

- **Room DB crash on upgrade ("cannot verify data integrity")** — bumped database version to 3; devices where the DB was already at version 2 with the old schema never triggered `onUpgrade` on the previous bump, so the hash mismatch persisted; this bump forces `onUpgrade(2→3)` regardless of intermediate state

- **Mosh connection lost lastlog/MOTD** — when Mosh was enabled, the SSH shell channel opened briefly (printing lastlog/MOTD), then got ripped out and replaced by mosh-client, which syncs to the current terminal state and doesn't replay scrollback; fixed by bootstrapping `mosh-server` before opening any shell channel so `mosh-server`'s own login shell is the only one and its output is visible

- **Spurious "Text copied" system toast on clipboard auto-clear** — `ClipboardHelper` and `SessionPersistenceManager` both cleared the clipboard via `setPrimaryClip(empty)`, which on Android 13+ always triggers the OS "Text copied" notification even for a blank string; replaced with `clearPrimaryClip()` (API 28+) which clears silently

- **`encodePrivateKeySectionForOpenSSH` wrote broken OpenSSH private key files** — three bugs: (1) Ed25519 case was missing entirely so no private key bytes were written; (2) ECDSA case was missing so the private scalar was never written; (3) RSA wrote `(e, n)` in the private section but OpenSSH requires `(n, e, d, iqmp, p, q)` — fixed all three; the function is not yet called from UI code but is now correct for when private key export is wired up

- **SSH key name shows garbled binary after import** — `parseOpenSSHEd25519Key` was reading the 32-byte public-key copy in the private section as the private key, leaving the real 64-byte private key blob unconsumed; the comment-reading code then read those 64 binary bytes as the comment string, producing garbage in the key list; fixed by consuming the pubkey copy with a `readString` before reading the actual private key — this also fixes the silent auth failure caused by storing the wrong key material; added printability guard in `getDisplayName()` as a defence-in-depth layer for keys already in the DB
- **SSH key import shows garbled name** — `fileUri.lastPathSegment` on a `content://` URI returns an encoded path component, not the display filename; now queries `OpenableColumns.DISPLAY_NAME` via the `ContentResolver` with `lastPathSegment` as fallback
- **`PortForwardingManager.cleanup` audit-logging orphan scope** — per-tunnel audit-log write spawned a throwaway `CoroutineScope(Dispatchers.IO)` whose parent `Job` was never cancelled; routed through `app.applicationScope.launch(Dispatchers.IO)` to match the pattern used by `TaskerWorker` and `PerformanceFragment`
- **`HypervisorsFragment` REST reachability probe socket leak** — `Socket()` allocated, `connect()` could throw, `close()` was skipped; wrapped in `try { connect() } finally { close() }`
- **`X11Proxy.connectToXServer` LocalSocket and TCP Socket leak on connect throw** — both probes allocated the socket inside the `try` block; a `connect()` exception fell through to the catch arm without closing the descriptor; hoisted allocation above `try` and added explicit close in catch
- **`ImportExportActivity.importSSHConfigFromUri` InputStream leak window** — reshaped to chained `openInputStream(uri)?.bufferedReader()?.use { it.readText() }` form to eliminate the window between local-`val` assignment and `.use {}` entry
- **`VncStreamHolder.set` orphan-stream leak on producer re-launch** — set without consume left prior streams unclosed; added explicit close-then-replace under the `@Synchronized` block
- **`TabManager.switchToTab` unused `previousTab` local** — dead `val` removed
- **`ConsoleWebSocketClient.isConnected` missing `@Volatile`** — read by the keepalive thread loop and written by the OkHttp callback thread; added `@Volatile` to prevent JIT-cached reads firing one ghost-send after disconnect
- **Collapsed database to version 1** — dropped all 38 migration objects, 33 schema JSON files, and the `room.schemaLocation` KSP arg; `fallbackToDestructiveMigration()` replaces the migration chain; any alpha install is wiped on upgrade
- **Removed all legacy/compat code** — alpha build, no existing users: dropped GSSAPI + FIDO2_SECURITY_KEY from AuthType, FIDO2 error guard from SSHConnection, v1 backup restore path from BackupImporter + BackupManager, deprecated `terminal` alias from SSHTab, and `isXenOrchestra` DB column from HypervisorProfile (migrated to `apiTypeOverride`; DB schema → v39)
- **Removed FIDO2 alpha stub** — `Fido2Detector`, `Fido2SshIdentity`, the Settings detection entry, and the NFC/USB-host manifest declarations are removed; `FIDO2_SECURITY_KEY` auth-type enum value is kept for database compatibility but remains non-selectable; the error guard in `SSHConnection` stays to handle any legacy DB rows
- **Removed dead VMware console button** — `btnConsole` in `VMwareManagerActivity` was always `View.GONE`; removed the field and the three visibility assignments; `rowConnect` visibility now depends on `btnSsh` only
- **Audit log now records SSH session events** — `AuditLogManager` had all methods implemented but none were wired; session start/end, auth success/failure, SFTP upload/download/delete, and port-forward open/close are now recorded when audit logging is enabled in Settings → Logging → Audit
- **Connection list now shows groups** — the Connections tab was rendering a flat list even when connections were assigned to groups; switched to `GroupedConnectionAdapter` so groups are visible
- **`KeyStorage.importKeyFromFile` leaked SAF InputStream** — `openInputStream()` result was read via `.bufferedReader().readText()` without `.use {}`; the underlying `ParcelFileDescriptor` was never closed; reshaped to `?.bufferedReader()?.use { it.readText() }`
- **`TelnetConnection.connect` socket leak on connection failure** — `Socket()` was allocated inside `try{}` and only assigned to the field after `connect()` succeeded; a timeout/refusal meant `disconnect()` in the catch arm couldn't reach it; hoisted allocation above `try{}` and added explicit `s.close()` in the catch arm
- **`SessionRecorder.startRecording` FileWriter leak on init failure** — `fileWriter` was assigned before the initial write/flush, so a storage failure left an open fd in the field while `isRecording` stayed false; deferred field assignment until after the write succeeds, closes the local writer in the catch arm
- **`SessionRecorder.stopRecording` FileWriter leak on write failure** — `close()` was only called if `write()` succeeded; restructured to always null the field and call `close()` regardless of whether the trailing write threw
- **`TabManager.closeTab` / `cleanup` leaked per-tab scope and Termux bridge** — both sites called `tab.disconnect()` which only tears down the SSH session; `tab.cleanup()` (which also cancels `connectionScope` and cleans up `TermuxBridge`) is now called at both sites; every closed tab was leaking a Kotlin `SupervisorJob` scope and a `TermuxBridge` + write scope until process death
- **`PortForwardingManager.cleanup` never actually stopped tunnels** — `forwardingScope.launch { stopAllTunnels() }` was immediately followed by `forwardingScope.cancel()`, which cancelled the just-launched coroutine before it ran; active port forwards stayed attached to the JSch Session until Session disconnect; cleanup now issues `delPortForwardingL/R` directly on the calling thread before cancelling the scope
- **`SSHConnection` channel leak on `connect()` failure** — `openChannel()` allocates a slot on the JSch Session; any throw between `openChannel()` and the `openChannels.add()` tracking call left the channel forever attached to the Session; added `catch { ch.disconnect(); throw }` to the exec, shell, and sftp channel open paths
- **`VncDirectConnector` socket file-descriptor leak on connect failure** — `Socket()` was allocated then `socket.connect()` or `RfbClient` constructor could throw; caller never receives the socket so the fd leaked until GC finalised it; wrapped in `catch(Throwable) { socket.close(); throw }`
- **`TabManager` `ArithmeticException` on empty tab list** — `switchToNextTab`, `switchToPreviousTab`, and the `Ctrl+Tab` / `Ctrl+Shift+Tab` keyboard shortcut path computed `% tabs.size` with no empty-list guard; added `if (tabs.isEmpty()) return` guards to all three paths
- **`MetricsCollector.previousNetworkStats!!` TOCTOU race** — field checked non-null then force-dereferenced; a concurrent `resetNetworkStats()` on another thread could null it between the two reads; captured into a local `val` first
- **Long press shows terminal menu again** — all three `TerminalView` wiring sites in `TabTerminalActivity` had `onContextMenuRequested` pointing at `beginSelection()` (copy/paste ActionMode) instead of `showTerminalMenu()` (the bottom-sheet action menu); long press now reliably shows the menu on URL and non-URL text alike; text selection is still available via the dedicated SEL key
- **`ClusterCommandExecutor` SSH session + scope leak on error** — `SSHConnection` and `CoroutineScope(SupervisorJob)` were not cleaned up when `connect()` / `executeCommand()` threw; a `finally{}` block now always calls `disconnect()` and `scope.cancel()`
- **`PerformanceFragment` orphan coroutine scope per connect** — `SSHConnection` was constructed with a throwaway `CoroutineScope(Dispatchers.IO)` per tap; now routes through `app.applicationScope` matching the pattern used elsewhere
- **`SAFSyncManager.lastError!!` NPE race** — four sites assigned `lastError` then force-dereferenced it; a concurrent write on `Dispatchers.IO` could null the field between those two statements; all sites now capture a local `val` first
- **`MetricsCollector.parseNetworkStats` off-by-one** — guard `parts.size < 10` failed to protect the `parts[10]` read (txPackets); tightened to `< 11`

- **Ed25519 / RSA / DSA / ECDSA public-key export wrong format** — `KeyStorage.encode*PublicKey()` all called `key.encoded` which returns the X.509 SPKI/DER blob; sshd silently rejects SPKI-encoded `authorized_keys` lines; all four helpers now build the correct OpenSSH SSH wire format (length-prefixed type string + key-type-specific payload per RFC 4253 §6.6)
- **Vertical spacing setting has no effect** — `TerminalPagerAdapter` had no `lineSpacingPercent` parameter so every new terminal view used the default 1.2×; `applyTerminalUiPrefs()` only updated the active view; `lineSpacingPercent` now passed to the adapter at construction and applied in `onCreateViewHolder`; adapter exposes `setLineSpacingPercent()` called from `applyTerminalUiPrefs()` to update all bound views
- **Reverse-scroll direction setting has no effect after returning from Settings** — `applyTerminalUiPrefs()` never updated `reverseScrollDirection` on live views; now calls adapter `setReverseScrollDirection()` which updates all bound terminal views in place
- **Import/Export crash on fast tap** — `backupManager` was initialised in a background coroutine; tapping any card before it finished threw `UninitializedPropertyAccessException`; converted to nullable with a "not ready" message guard at each call site
- **Saved password not cleared when "Save Password" unchecked** — editing a connection and unchecking "Save Password" left the old Keystore credential in place; it was silently reused on the next connect; now calls `clearPassword(profile.id)` on uncheck
- **`finish()` indentation trap in connection-failure path** — the `finish()` call in the null-errorInfo branch was indented at the outer scope level, making it look like it ran for both errorInfo paths; re-indented to match its actual inner-`else` scope
- **`DynamicForward` host-qualified spec silently dropped** — `"127.0.0.1:1080"` form failed `toIntOrNull()` and was dropped without error; now uses `substringAfterLast(':')` to handle both bare port and `[host:]port` forms including IPv6
- **Monitoring cooldown never synced** — `monitoring_alert_cooldown_minutes` is stored as a string `"60"` but `toAnyMap()` converts numeric strings to `Int`; the apply side then `value as String` threw `ClassCastException` silently every sync; now coerces via `when (value) { is Number → toString(); is String → value }`
- **Cluster broadcast dialog empty** — `setMessage` + `setView` conflict silently dropped the "Send to N sessions" message; count now in the title, hint on the `EditText`
- **Split-pane SSH session leak** — `closeSplitPane()` and `onDestroy()` called `tab.disconnect()` but never `sshSessionManager.closeConnection()`; JSch session stayed open, notification persisted, slot never freed
- **`computeScroll` blank strip after `clear`** — `scrollYf` was not clamped in `computeScroll`; scrollback buffer shrink mid-fling left `scroller.currY` above the new max, rendering a blank strip; now `coerceIn(0f, maxScrollYPx())`
- **Pinch-to-zoom triggers spurious selection** — `ACTION_DOWN` with `pointerCount == 1` entered selection mode before the second finger arrived; now defers via `postDelayed` and cancels on `ACTION_POINTER_DOWN`
- **`screen` session attached status always false** — `awk '{print $1}'` stripped the `(Attached)`/`(Detached)` suffix before the `contains` check; removed awk, kept full line, split on `\t`
- **`screen` session names truncated at first dot** — `split(".")` took only segment 1; `dev.backend.api` showed as `backend`; now `split(".", limit = 2)`
- **`setAutoBackup` vs `setAutoBackupEnabled` alias mismatch** — `BackupImporter` was calling the legacy alias; unified to `setAutoBackupEnabled`
- **`setCursorBlink` vs `setCursorBlinkEnabled` alias mismatch** — same; unified to `setCursorBlinkEnabled`
- **Ed25519 export breaks on API < 33** — `generateEd25519KeyPair()` silently falls back to ECDSA P-256 on API < 33; `encodeEd25519PublicKey` then called `takeLast(32)` on a 91-byte EC SPKI blob producing garbage; now detects `ECPublicKey` and dispatches to `encodeECDSAPublicKey`; also validates the expected 44-byte SPKI length before extracting
- **Proxy `bypassHosts` round-trip data corruption** — separator changed from `","` to `"\n"` in backup export and sync collect; restore paths accept both for backward compat; commas are valid inside bypass-list entries and were splitting single entries into multiple on restore
- **DynamicForward bind address silently forced to 127.0.0.1** — the parsed bind address was discarded; a new `parseDynamicForwardSpec` helper preserves it and supports bare port, IPv4, and IPv6 `[::1]:port` forms; `parseForwardSpec` for LocalForward/RemoteForward also updated to handle IPv6 brackets
- **Run-batched renderer: character after wide glyph draws at wrong column** — after flushing for a wide char, `runStyle` was not reset; the next normal character's `sameStyle` check compared against the stale value and skipped setting `runStartCol`; `runStyle = 0L` now set after every wide-glyph draw; wide glyphs now use a separate `wideCharBuf` so `charBuf` aliasing cannot cause a future regression
- **Sync string casts for `frequency`, multiplexer prefixes, and `bypassHosts`** — all remaining `value as String` casts on ListPreference / string keys now use the defensive `when (value) { is String -> value; is Number -> value.toString(); else -> default }` pattern matching `monitoring_alert_cooldown_minutes`
- **`boundViewHolders.forEach` ConcurrentModificationException risk** — `setLineSpacingPercent` and `setReverseScrollDirection` now snapshot to `toList()` before iterating
- **`getItemCounts()` undercounting** — only counted 5 of 16 entity types; now counts all: connections, keys, themes, host keys, workspaces, snippets, identities, groups, hypervisors, certificates, macros, monitor slots, hypervisor accounts, VNC hosts, VNC identities, cloud accounts
- **`applySecrets()` silent failure** — missing `SecurePasswordManager` or `KeyStorage` (e.g. during test runs) now logs a warning instead of silently dropping all credentials
- **Terminal menu tab list wrong tab on stale index** — tapping a tab in the long-press menu after another tab closed activated the wrong tab; row click now resolves the live index by `tabId` instead of using the open-time snapshot position

- **Scroll direction preference** — `terminal_reverse_scroll` in Settings → Terminal; OFF (default) = swipe UP to see older output, matching JuiceSSH/Termux/ConnectBot; ON = old TabSSH inverted behaviour for users accustomed to it

### Changed

- **Long press = terminal menu (non-URL) / URL dialog (URL)** — long press on a URL opens the URL open/copy dialog as before; long press on non-URL text now opens the terminal action menu instead of starting text selection; copy/paste lives on the dedicated clipboard key in the keyboard bar
- **Terminal scroll rendering: run-batched drawText** — render loop previously called `canvas.drawText` once per character (~2 000 JNI calls/frame on an 80×25 terminal); now batches consecutive characters that share the same foreground colour and text effects into a single `drawText` call per run, reducing JNI draw calls by ~20×; double-width glyphs still draw solo; scroll invalidation changed from `postInvalidateOnAnimation` to `invalidate` for immediate 1:1 finger tracking

- **Terminal long-press menu redesigned** — full MD3 bottom sheet with drag handle, prominent "New Tab…" outlined button, tab list with per-row connection-state icon (green/amber/red/grey) and bold label for the active tab, plus two new sections ("Terminal" and "Session") covering all actions; removed paste (lives on the key bar); added Copy Screen, Snippets, Broadcast to All Tabs, and Share Session
- **Changelog hygiene rule** — CLAUDE.md now mandates that every user-visible commit updates both `CHANGELOG.md` and `app/src/main/assets/whats_new.md` in the same commit

### Changed

- **Terminal menu expanded** — long-press menu now includes: Toggle System Keyboard, Toggle Key Bar (label reflects current state), Find in Scrollback, Paste — previously only reachable via the command palette or keyboard shortcuts
- **Settings reorganised** — multiplexer settings (gesture toggle, gesture type, per-type prefix keys) moved from Settings → Terminal → Behavior to Settings → Connection → Multiplexer; Terminal settings now contains only terminal-display and input options
- **PRE key label** — the PRE key now shows the configured prefix shorthand (e.g. `^B`, `^A`, `^G`, `M-b`) while a multiplexer is active instead of always showing `PRE`; reverts to `PRE` when no multiplexer is detected
- **Prefix examples in settings** — each multiplexer prefix field (tmux, screen, zellij) now shows an inline example dialog explaining all supported notations: `C-b`, `^b`, `C-Space`, `M-b`, `Alt-b`, `0x02`, literal characters
- **CTL / ALT active state** — latched modifier keys now render with a solid green fill and dark green text (WCAG AA contrast) instead of a mere alpha change; the same green-fill treatment applies to the PRE key when a multiplexer is active
- **Keyboard key widths** — CTL, TAB, ENT, ESC reduced from 2× to 1.5× so the label fills the box rather than floating in empty space; text size bumped 12 → 13 sp
- **Smooth scrolling** — `scrollYf: Float` replaces the integer `scrollY`, with a canvas pre-translate by the sub-row fractional offset; rows now glide continuously instead of snapping a full row at a time, eliminating the jagged/jumpy feel
- **Scroll direction default** — standard mobile convention (swipe UP = older content) is now the default; old inverted direction is available as a preference

### Fixed

- **Password dialog shows no prompt text** — `setMessage` and `setView` both own the dialog's content area; the message was silently dropped; message now rendered as a `TextView` inside the same `FrameLayout` container as the `EditText`
- **Search overlay always "No active session"** — `setupSearchOverlay()` called from `onCreate()` before any tab exists always produced a null controller; `showSearchOverlay()` now lazily calls `setupSearchOverlay()` against the live active view on first use
- **Double `finish()` on clean tab exit** — `updateTabIcon` called `tabManager.closeTab()` then `finish()` when count hit 0; `closeTab` already fires `onTabClosed` which calls `finish()`; removed the duplicate call
- **`delay(200)` connect race** — replaced the fixed 200 ms sleep with `withContext(Dispatchers.Main) {}` which enqueues after the `Handler.post { addTabToUI() }` already queued by `onTabCreated`; applies to both SSH and Telnet connect paths
- **Blank terminal on tab-create failure** — `connectToProfile` showed an error toast but did not call `finish()` when `tab == null`; user was left on a blank unusable screen
- **`conn.disconnect()` on main thread** — `onDisconnected()` fires on the main looper via `TermuxBridge.runOnMain`; calling `conn.disconnect()` there blocks on JSch's socket teardown; moved to `connectionScope.launch { }` (Dispatchers.IO)
- **Long-press URL / context-menu on wrong row when scrolled** — `getTextAtPosition` computed row as `(y + scrollYInt) / cellHeight` (single division, truncation mismatch); now uses two-step `screenRow + scrollRows` matching `renderTermuxBuffer` exactly
- **Multiplexer detection loop runs when mode is OFF** — `detectMultiplexerViaExec()` probed every 30 s regardless of profile setting; now guarded by `if (profile.multiplexerMode != "OFF")`
- **tmux session names containing `:` corrupt parse** — format string used `:` as separator; changed to `|` which tmux session names cannot contain
- **Scrollback broken when scrolled** — `fracOffset` was computed as `scrollYf - View.getScrollY()` but `View.getScrollY()` is always 0 because we never call `View.scrollTo()`; this made `fracOffset` equal the full pixel scroll offset, shifting all terminal content off-screen the moment the user scrolled into scrollback; fixed to `scrollYf % cellHeight` (the true sub-row fractional remainder)
- **Mosh legacy field removed** — the "Global Mosh Server Command (legacy)" preference in Settings → Connection is gone; mosh command is configured per-connection in the connection editor
- **Notification "Disconnect" button silent** — `ConfirmDisconnectActivity` now disconnects via `TabManager.getAllTabs().find(profileId)?.disconnect()` so it works whether or not the connection is still in `SSHSessionManager.activeConnections` (which it may not be if the session already dropped)
- **Notification doesn't disappear on disconnect** — `SSHConnectionService.onConnectionStateChanged(DISCONNECTED)` was silently delegating to `onConnectionClosed` which is only called by explicit `closeConnection()`, not by natural remote-side disconnects; now updates the notification directly via the same `renderHostNotification(disconnectingState=true)` path

- **PRE keyboard key** — new PREFIX action key in the default keyboard bar (row 3, directly under ENT); 2× wide, sends the correct multiplexer prefix byte (C-b for tmux, C-a for screen, C-g for zellij); turns green when a multiplexer is detected, dims when none is active
- **Multiplexer auto-detection** — after connect, probes `$TMUX`, `$STY`, `$ZELLIJ_SESSION_NAME` via a background exec channel; re-probes every 30 s so the PRE key reacts to the user attaching or detaching a multiplexer without reconnecting
- **Multiplexer picker dialog** — tapping PRE with no multiplexer detected shows a type picker (tmux/screen/zellij) instead of sending a stray control byte into a non-multiplexer session
- **PRE key picker rendered blank** — `setMessage` and `setItems` both occupy the dialog body in `MaterialAlertDialogBuilder`; the message silently hid the item list so nothing was selectable; moved the hint into the title so the three options now render correctly
- **Per-multiplexer prefix settings** — Settings → Connection now has a "Multiplexer Prefixes" section to configure each type's prefix independently (tmux C-b, screen C-a, zellij C-g) — previously hidden behind a single global field
- **SSH key alias system** — keys are assigned an SSH-convention alias (`id_ed25519`, `id_rsa_001`, etc.) at import time; used to automatically resolve `IdentityFile` paths during `~/.ssh/config` import without manual key assignment
- **Smart SSH key naming** — key comment field now extracted from the OpenSSH v1 binary format (was always empty before); import dialog shows both Name (default = comment) and Alias (default = SSH convention) fields
- **Mosh command preset dropdown** — per-connection picker in the connection editor with common presets (Default, port range, IPv4/IPv6-only, full locale, custom path) plus a Custom option; replaces the global preference string that was never read by the app
- **Global SSH directive defaults** — Settings → Connection now exposes `Keepalive Interval` (seconds), `X11 Forwarding` default, and `Agent Forwarding` default; these feed new per-host `serverAliveInterval` (nullable, null = use global), `x11_forwarding_default`, and `agent_forwarding_default` fields
- DB migration v37 → v38: `stored_keys.alias` (TEXT, nullable), `connections.server_alive_interval` (INTEGER, nullable)

### Changed

- **Connection notifications** — title now shows the user-facing connection name ("prod server") instead of the raw IP; body shows protocol/terminal title separately; makes the notification drawer readable when you have multiple servers
- **`~/.ssh/config` import** — after parsing, resolve each `IdentityFile` basename against stored key aliases and fall back to key name; connections with a matching imported key have `keyId` set immediately (no manual assignment step)
- **`ServerAliveInterval`** from `~/.ssh/config` is now stored per-connection and applied at connect time; previously hardcoded to 60 s globally regardless of the config file value
- **Mosh bootstrap** — reads per-connection `advancedSettings["moshServerCommand"]` if set; fixes the hardcoded wrong default (`-s` flag was included, which causes `mosh-server` to block waiting on stdin)

### Fixed

- **Terminal long-press menu silent no-op** — pager adapter now calls `beginWordSelectionAtTouch` directly on each TerminalView instead of routing through `getActiveTerminalView()`, which could return null during RecyclerView relayouts or the wrong view during fast tab switches
- **Drag-to-select text jumps / selection vanishes** — added a 2× snap-radius proximity guard in `handleSelectionTouch`; tapping near a handle circle (which is drawn below the selection highlight) no longer fires `exitSelectionMode()` before the drag can begin
- **Identity and all ExposedDropdownMenu dropdowns empty** — replaced `AutoCompleteTextView` with `MaterialAutoCompleteTextView` across all 9 affected layout files; base class filters against current text so restored values hid all items
- **Tasker `ACTION_CONNECT` orphan sessions** — `TaskerWorker.handleConnect` now routes through `TabManager.createTab() + tab.connect()` so Tasker-initiated sessions appear in the tab bar and can be disconnected by the user
- **CI security grep false positive** — `passwordLayout?.error = "…"` matched the password-literal pattern; exclusion added with documentation in AI.md §14.3

## [0.9.1] - 2026-06-04

### Added

- **Sync password verification** — opening an existing sync file now prompts for the password and verifies it before accepting, preventing silent data corruption
- **Cloud Accounts Manager** — unified screen for DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, GCP, Azure, and OCI; OCI moved out of Hypervisors into Cloud Accounts
- **RequestTTY directive** — SSH config `RequestTTY` is now honoured for exec channels
- **Zellij support** — auto-attach/create for Zellij alongside existing tmux/screen support
- **Connection count tracking** — per-host usage statistics; cloud instances carry SSH credentials directly
- **Room migration test suite** — automated tests covering v32→v37 schema migrations
- **VNC arrow key toolbar** — on-screen arrow keys added to the VNC session toolbar
- **Clipboard key + key bar toggle** — new CLIPBOARD key in the SSH keyboard bar; bar can be toggled on/off

### Changed

- **Sync/backup toggles default to enabled** — all content categories (connections, keys, snippets, identities, themes) now default on; users opt out rather than in
- **Sync network default** — default changed from WiFi-only to WiFi + mobile
- **Hypervisor card UI** — redesigned VM cards; SSH/VNC Connect buttons appear before Start/Stop power buttons
- **Nav drawer** — reorganised: Accounts section added; Import/Export moved into Settings
- **Cloud Accounts** — merged into the Infra tab alongside Hypervisors

### Fixed

**Sync / Backup**
- Sync settings not persisting across app restarts (toggles ignored, prefs not round-tripped)
- Auth type displayed as "Password" for SSH key connections in backup/sync/pairing exports
- Remaining auth type format bugs in the pairing QR flow

**VNC / Console**
- Comprehensive RFB protocol rewrite: correct initial handshake, pixel format, framebuffer update request sequence
- VNC WebSocket subprotocol negotiation failure
- `inflate Z_STREAM_END` hang blocking ZRLE-encoded sessions
- ZRLE stream desync after partial tile reads
- Unknown QEMU audio vendor message (type `0xE0`) causing session drop
- Tight encoding: old-style palette filter causing stream desync
- Tight encoding: `ExplicitFilter` bit and int overflow
- VNC key input mapping; cursor not shown by default
- VNC screen freeze — now prefer ZRLE over Tight for QEMU/Proxmox
- Auto-reconnect without resize for hypervisor VNC (Proxmox)
- `IOException` crash in VNC console channel writer thread
- VNC resize: enable Proxmox resize; auto-reconnect on server rejection
- Duplicate `KeyUp` events sent on key release
- Missing `layout_width`/`height` crash in VNC toolbar

**Terminal / Tabs**
- Swipe feedback loop root cause (deferred `isUpdatingAdapter` flag clear in ViewPager2)
- Tab swipe freeze caused by `TransactionTooLargeException` in `ReportIssueDialog`
- `CancellationException` swallowing; real tab persistence across reconnects
- "New connection" always reattaching to an existing tab instead of opening a new one
- Active sessions strip showing stale entries; red dots not clearing; frequent-connects menu broken
- New-tab reattach bug; multi-session chooser not appearing; cursor disappearing after `DECTCEM`
- Long-press copy; missing Cancel button in ActionMode; word-wrap URL detection
- Selection drag; Vim navigation keys; 4 crash-on-bad-input paths
- `SIGWINCH` double-fire on terminal resize (debounced)
- `historyCache` unbounded growth; split-pane cancellation leak

**Cloud / Hypervisors**
- OCI pagination returning only the first page of instances
- OCI TLS certificate pin not persisting in the cloud account manager
- OCI credential persistence and import UX
- OCI cloud account add/edit/import flow
- Hypervisor API: response body leaks, missing confirm dialogs, poor error UX
- Hypervisor API: missing timeouts; call cancellation on `onDestroy`
- VMware re-authentication on token expiry
- Search on Hosts tab producing no results

**Security / Auth**
- TOFU host key certificate pin not persisting across app restarts
- Broken auth type picker; TOFU dead-end in port forwarding activity
- SSH key spinner race in proxy host selection
- Group-edit race conditions in `ConnectionEditActivity`
- Group field showing "No Group" when editing a grouped connection

**Performance / Stability**
- All blocking I/O moved off the main thread (Keystore, file ops, DB)
- Room: missing indexes on foreign keys and query columns; missing `@Transaction` guards; schema bumped to v37
- `TermuxBridge`: thread safety violations, read loop inefficiencies
- `TabTerminalActivity`: coroutine leaks, dead code, `warningsJob` accumulation, `splitTab` leak
- Soft foreign key orphans; mosh re-entry leak; unbounded `Flow` collectors
- `DiffUtil` not used in list adapters; `HostKeyDao` full-table scans
- `PreferenceManager` double-initialisation on startup
- Language picker not wired up; untranslated locales removed

---

## [0.9.0] - Initial release

### Added

- Browser-style tabbed SSH sessions with swipe navigation
- Full VT100/ANSI/xterm-256color terminal emulation via Termux TerminalEmulator
- SSH authentication: password, public key (RSA/ECDSA/Ed25519), keyboard-interactive
- SSH key management: import, generate, passphrase protection, OpenSSH certificates
- SFTP file browser with upload/download/rename/chmod/delete and remote editor
- Port forwarding: local, remote, dynamic SOCKS5
- X11 forwarding via Termux:X11
- ProxyJump multi-hop connections
- Port knocking before connect
- Agent forwarding
- Session recording and replay
- `~/.ssh/config` import
- Snippet library with `{var}` placeholder substitution
- Macro recording (raw byte sequences)
- Mosh protocol support
- Tmux/screen/zellij auto-attach and create-new modes
- Post-connect script execution
- Find-in-scrollback with prev/next navigation
- Proxmox VE, XCP-ng/Xen Orchestra, VMware vSphere, QEMU/libvirt hypervisor management
- VNC/RFB console client (Tight, ZRLE, CopyRect, Hextile encodings)
- 22 built-in terminal themes (Dracula, Solarized, Nord, Monokai, One Dark, Tokyo Night, Gruvbox, and more)
- Custom theme editor with WCAG 2.1 contrast validation; import/export JSON
- Material Design 3 UI with dark/light/auto mode
- Custom SSH keyboard bar (1–5 configurable rows)
- Hardware keyboard support with AltGr, xterm modifier-encoded arrows
- TalkBack / accessibility support
- Translations: English, Spanish, French, German
- Android Keystore hardware-backed credential encryption (AES-256-GCM)
- Biometric unlock for stored credentials
- Host key TOFU with SHA-256 fingerprints and MITM detection
- Screenshot protection (`FLAG_SECURE`)
- Clipboard auto-clear for sensitive pastes
- Tasker integration and deep link support
- Home screen quick-connect widget
- Encrypted ZIP backup and restore
- SAF-based cross-device sync (Google Drive, Dropbox, OneDrive, Nextcloud, local storage)
- Background SSH monitoring with CPU/memory/disk threshold alerts
- Connection groups with expand/collapse
- Real-time search with 8 sort options
- Connection statistics and per-host audit log
- Multi-host dashboard with live CPU/memory/disk metrics

[Unreleased]: https://github.com/tabssh/android/compare/v0.9.1...HEAD
[0.9.1]: https://github.com/tabssh/android/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/tabssh/android/releases/tag/v0.9.0

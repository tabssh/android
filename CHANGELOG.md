# Changelog

All notable changes to TabSSH are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added

- **Scroll direction preference** — `terminal_reverse_scroll` in Settings → Terminal; OFF (default) = swipe UP to see older output, matching JuiceSSH/Termux/ConnectBot; ON = old TabSSH inverted behaviour for users accustomed to it

### Changed

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

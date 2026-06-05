# Changelog

All notable changes to TabSSH are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

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

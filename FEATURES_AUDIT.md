# TabSSH Feature Audit

**Date:** 2026-04-26
**Goal:** Document every concrete user-facing feature in TabSSH, JuiceSSH, and Termius — categorise into "have / want / drop" so we can plan the path to parity.

**Operating principles:**
- TabSSH is **free + open source, MIT licensed, no feature gates**. Every Free/Pro/Plugin/Enterprise feature in JuiceSSH or Termius is a candidate for us to ship to everyone.
- **No Google services dependency.** Sync stays SAF-based (works on de-Googled ROMs).
- **Privacy first.** No telemetry, no cloud auto-discovery (AWS/GCP/DO host import is opt-in if at all).
- All current features are baseline. Anything new is additive.

---

## Legend

| Mark | Meaning |
|------|---------|
| ✅ | We have it |
| 🟡 | We have it partially or as a stub |
| ❌ | We don't have it — **gap** |
| 🚫 | Out of scope (e.g. paid SaaS team features, AI cloud calls, vendor-specific cloud imports) |
| 🆕 | We have it and they don't (our innovation) |

---

## SSH connectivity

| Feature | Us | JuiceSSH | Termius | Notes / priority |
|---|---|---|---|---|
| SSH protocol | ✅ | ✅ | ✅ | — |
| Mosh protocol | ✅ | ✅ | ✅ Free | Fully wired; `libmosh-client.so` bundled for all 4 ABIs; auto-falls-back to SSH |
| Telnet protocol | ✅ | ✅ Free | ✅ Free | `TelnetConnection.kt` |
| Local Android shell | ❌ | ✅ Free | — | Open a shell on the device itself. **LOW** |
| Serial console (USB) | ❌ | — | ✅ Free | Redpark USB-C/Lightning serial cables for switches/routers. **LOW** (USB-OTG mostly) |
| SFTP | ✅ | ✅ | ✅ | — |
| IPv6 | ✅ | ✅ | ✅ | — |
| Custom port per host | ✅ | ✅ | ✅ | — |
| Compression toggle | ✅ | ✅ | — | — |
| Keep-alive | ✅ | ✅ | ✅ | — |
| Auto-reconnect | ✅ | ✅ | — | — |
| Background sessions | ✅ | ✅ | ✅ | Foreground service |
| SSH config import (~/.ssh/config) | ✅ | ❌ (Plugin) | ✅ Free | We have parsing |
| Bulk import: PuTTY, CSV, JSON, OpenSSH config, Terraform | ✅ | ❌ | ✅ Free | `BulkImportParser.kt` — auto-detect + all four formats |
| Jump host / ProxyJump | ✅ | ✅ ("Connect-via") | ✅ Pro | We have it |
| Connect-via chained nested connections | ✅ | ✅ | ✅ | Same as jump host |
| HTTP/SOCKS proxy | ✅ | — | ✅ Pro | — |
| Port knocking | ✅ | ✅ Plugin | — | We have built-in |
| Custom SSH ciphers/MACs | ✅ | — | ✅ | We have modern defaults; no UI yet to override |
| ML-DSA / post-quantum auth | ❌ | — | ✅ Free | NEW. **LOW** until OpenSSH 9.x is widespread |
| ML-KEM key exchange | ❌ | — | ✅ Free | Same — JSch 2.27 already supports it |
| Per-connection env vars | ✅ | ✅ Free | ✅ Free | `ConnectionProfile.envVars` + UI in ConnectionEditActivity + `SSHConnection.setEnv()` |
| Per-connection startup script (post-connect) | ✅ | ✅ Free | ✅ Pro | We have it |
| Quick-connect (no save) | ✅ | ✅ | ✅ | — |

## Authentication

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Password auth | ✅ | ✅ | ✅ | — |
| Public-key (RSA/ECDSA/Ed25519/DSA) | ✅ | ✅ | ✅ | — |
| Keyboard-interactive | ✅ | ✅ | ✅ | — |
| In-app key generation | ✅ | ✅ | ✅ | — |
| Encrypted private-key passphrase | ✅ | ✅ | ✅ | — |
| Import OpenSSH/PEM/PuTTY keys | ✅ | ✅ | ✅ | — |
| Export public key | ✅ | ✅ | ✅ | — |
| 2FA prompt (TOTP keyboard-interactive) | ✅ | ✅ | — | Already works via keyboard-interactive |
| SSH agent functionality (keys stay on device) | ✅ | ✅ | — | Inherent (we never send key material) |
| SSH agent forwarding | ✅ | ✅ | ✅ Pro | UI toggle + JSch IdentityRepository populated from stored keys. |
| FIDO2 / U2F hardware key auth | 🟡 | — | ✅ Free | Stub `Fido2SshIdentity` exists; CTAP2 transport not implemented (~1k LOC). |
| SSH certificate auth | ✅ | — | ✅ Free | OpenSSH `*-cert.pub` attach via KeyManagementActivity, `addIdentity(name, prv, cert, pass)`. |
| Identity abstraction | ✅ | ✅ | ✅ | — |
| Biometric unlock for app/credentials | ✅ | ✅ | ✅ Pro | — |

## Terminal experience

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| VT100/xterm 256-color | ✅ | ✅ | ✅ | Termux emulator |
| True-color (24-bit) | 🟡 | — | ✅ Free | Termux supports it; verify our render path. **LOW** |
| UTF-8/Unicode | ✅ | ✅ | ✅ | — |
| Adjustable font size | ✅ | ✅ | ✅ | — |
| Volume keys → font size | ✅ | ✅ | ✅ | — |
| Pinch-zoom font size | ✅ | ✅ | — | — |
| Built-in terminal themes | ✅ 23 | ✅ ~6 | ✅ ~10+ | We're ahead 🆕 |
| Custom theme editor | ✅ | — | ✅ Free | `ThemeEditorActivity` — full GUI editor |
| Per-host theme override | ✅ | — | ✅ Free | — |
| Programmer fonts bundled | ✅ 8 | ✅ 8 | ✅ 7 | — |
| Cursor styles | ✅ | ✅ | ✅ | — |
| Bell options (audio/visual/silent) | ✅ | ✅ | ✅ | — |
| Configurable scrollback | ✅ | ✅ | ✅ | — |
| Find/search in scrollback | ✅ | — | ✅ Free | Shipped `7841256b841a` — `ScrollbackSearchController`, Ctrl+Shift+F |
| Long-press copy text | ✅ | ✅ | ✅ | — |
| Long-press context menu | ✅ | ✅ | ✅ | New (Phase 7.8) |
| Terminal autocomplete (suggestions while typing) | ❌ | — | ✅ Free | Like fish/zsh inline completion. **MEDIUM-HIGH** |
| AI command generation | ❌ | — | ✅ Free | English → shell command. **🚫 out of scope** (cloud LLM, privacy) — unless we wire local model later |
| AI Agent / chat with infra | ❌ | — | ✅ Pro | **🚫 out of scope** — cloud LLM |
| Broadcast input (one keystroke → many tabs) | ✅ | — | ✅ Pro | `TermuxBridge.broadcastTargets` fan-out; `showBroadcastTargetsDialog()` in TabTerminalActivity |
| Session log auto-record | ✅ | — | ✅ Pro | We have transcript recording |
| URL detection + open | ✅ | — | — | 🆕 |

## Tabs / sessions

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Multi-tab UI | ✅ | ✅ | ✅ | — |
| Swipe between tabs | ✅ | — | ✅ | — |
| Tab list / switcher | ✅ | ✅ | ✅ | — |
| Tab reordering (drag) | 🟡 | — | ✅ | Infrastructure exists, UI toggle deferred. **LOW** |
| Reconnect button on disconnected tab | ✅ | ✅ | ✅ | Fully implemented — non-zero exit shows Reconnect/Close dialog; clean exit auto-closes |
| Session indicator badge | ✅ | ✅ | ✅ | — |
| Background session via notification | ✅ | ✅ | ✅ | — |
| Workspaces (named tab groups) | ✅ | — | ✅ Free | Save/open/delete workspaces from TabTerminalActivity menu |
| Split view (multiple panes per tab) | ✅ | — | ✅ Pro (16-pane) | Wave 2.8 minimal split — bottom pane per tab, independent SSHTab |
| Command palette (Ctrl+K) | ✅ | — | ✅ Free | `PaletteDialog.kt` |
| Quick switcher (Ctrl+J) | ✅ | — | ✅ Free | Part of `PaletteDialog.kt` |
| Foldable-aware layout | ❌ | — | ✅ Free | Smooth fold/unfold. **LOW** until you need it |
| Resume session on app restart | ✅ | ✅ | ✅ | — |

## File transfer

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| SFTP browser | ✅ | ✅ | ✅ | — |
| Upload/download | ✅ | ✅ | ✅ | — |
| Multi-file selection | ✅ | — | ✅ | — |
| Recursive folders | ✅ | — | — | 🆕 |
| Resume transfers | ✅ | — | ✅ | — |
| Edit remote files in-app | ✅ | — | ✅ Free | `RemoteFileEditorActivity`; long-press → "Open / Edit" in SFTPActivity |
| Drag-and-drop | ❌ | — | ✅ Free | Inside the SFTP UI on tablets. **LOW** |
| chmod / permissions edit | ✅ | — | ✅ Free | `showPermissionsDialog()` in SFTPActivity — rwx checkboxes for user/group/other |
| Create/rename/delete | ✅ | — | ✅ | — |
| SFTP tabs (multiple file panels) | ❌ | — | ✅ Free | **LOW** |
| Native Android file-picker integration | ❌ | — | ✅ Free | Android SAF for upload destination. **MEDIUM** |
| File transfer queue | 🟡 | — | — | We have notifications per transfer; no central queue UI. **LOW** |

## Port forwarding

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Local (-L) | ✅ | ✅ Pro | ✅ | — |
| Remote (-R) | ✅ | ✅ Pro | ✅ | — |
| Dynamic SOCKS (-D) | ✅ | ✅ Pro | ✅ | — |
| Bind to all interfaces option | ✅ | — | — | 🆕 |
| Saved rules per host | ✅ | ✅ | ✅ | — |
| Quick start/stop toggle | ✅ | ✅ | ✅ | — |
| Auto-open browser to forwarded port | ❌ | ✅ Pro | — | One-tap "open the dev server I just tunnelled". **LOW** |
| Run forwards without terminal session | ✅ | ✅ Pro | ✅ Free | `PortForwardingActivity` background tunnels — pick connection, no terminal opened |
| Home-screen widget for one-tap forward | ❌ | ✅ Pro | — | **LOW** |
| X11 forwarding | 🟡 | ✅ doc | — | UI toggle, not rendered. **LOW** |

## Sync / backup

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Cloud sync | ✅ SAF | ✅ Pro (CloudSync) | ✅ Pro (Vault) | We don't depend on Google. 🆕 |
| End-to-end encryption | ✅ AES-256-GCM | ✅ AES-256 | ✅ | — |
| Cross-platform sync | 🚫 | ✅ Pro | ✅ Pro | We're Android-only. Acceptable. |
| Manual export/import | ✅ ZIP | ✅ Pro | ✅ Free | — |
| Per-entity sync toggles | ✅ | — | — | 🆕 |
| Three-way merge with conflict UI | ✅ | — | — | 🆕 (most apps just last-write-wins) |
| Auto-sync on change/schedule/launch | ✅ | ✅ | ✅ | — |
| WiFi-only toggle | ✅ | — | — | 🆕 |
| Sync via SAF (no Google services) | ✅ | ❌ | ❌ | 🆕 — works on de-Googled ROMs |

## Snippets / automation

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Snippets library | ✅ | ✅ Pro | ✅ Free | — |
| Categories / tags | ✅ | ✅ | ✅ | — |
| Variable placeholders | ✅ | — | ✅ | `{host}`/`{user}`/`{date}` + prompt-style `{?name:default|hint}` with recall + password masking |
| One-tap run on session | ✅ | ✅ | ✅ | — |
| Run on multiple hosts (cluster snippets) | ✅ | ✅ Plugin | ✅ Pro | We have ClusterCommandActivity |
| Sync snippets | ✅ | ✅ Pro | ✅ Pro | — |
| Tasker integration | ✅ | ✅ Plugin | — | We have it |
| AI-generated snippets | ❌ | — | ✅ Free | 🚫 cloud LLM out of scope |

## Customization

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Light/dark/system app theme | ✅ | ✅ | ✅ | — |
| 23 built-in terminal themes | ✅ | ~6 | ~10+ | 🆕 ahead |
| Custom keyboard layout (1-5 rows, customisable keys) | ✅ | ✅ extra row | ✅ extra row | 🆕 we go further |
| Per-host color tag | 🟡 | ✅ | ✅ | We have group colors not host colors. **LOW** |
| Color-coded groups | ✅ | ✅ | ✅ | — |
| Group inherited settings | ✅ | — | ✅ Free | — |
| Frequently-used quick list | ✅ | ✅ | — | — |
| Configurable swipe/tap behaviours | 🟡 | ✅ | — | Some hardcoded; expose to user. **LOW** |

## Mobile-specific UX

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Home-screen widgets | ✅ 4 sizes | ✅ multiple | ✅ Free | — |
| Widget filtered to a group | ❌ | ✅ Pro | — | **LOW** |
| Notification controls (disconnect from notif) | ✅ | ✅ | ✅ | — |
| One-tap reconnect from notif | 🟡 | — | ✅ Free | Tap opens terminal but we don't auto-reconnect dead sessions. **MEDIUM** |
| Material Design 3 | ✅ | partial | ✅ | — |
| Bottom nav bar on phones | ❌ | — | ✅ Free | Vault / Connections / SFTP / Settings. **MEDIUM** |
| Tablet top tabs | ❌ | — | ✅ Free | **LOW** |
| Foldable optimisation | ❌ | — | ✅ Free | **LOW** |
| Voice typing into terminal | ❌ | — | ✅ Free | Via Android voice keyboard. **LOW** |
| Hardware (Bluetooth) keyboard | ✅ | ✅ | ✅ | xterm-style modifier-encoded arrows / HOME / END / PG family — `\e[1;<mod><letter>` for Shift/Ctrl/Alt + nav (Issue #171). |
| AltGr handling | ✅ | ✅ | — | Right-Alt distinguished from real Alt; AltGr-composed unicode passes through as-is. |
| Chromebook layout | 🟡 | ✅ | — | Per PlatformManager hooks. **LOW** |
| Shake-to-send-Tab gesture | ❌ | — | ✅ Free | Termius novelty. **LOW** |
| Volume button → bind to Shift+Tab | ❌ | — | ✅ Free | **LOW** |
| Localisation | ✅ 4 | ✅ many | ✅ many | — |

## Security

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Host-key TOFU verify | ✅ | ✅ | ✅ | — |
| Host-key change MITM warning | ✅ | ✅ | ✅ | — |
| Known-hosts management UI | ✅ | ✅ | ✅ | — |
| AES-256 stored credentials | ✅ | ✅ | ✅ | — |
| Hardware-backed Keystore | ✅ | — | — | 🆕 |
| Biometric app lock | ✅ | — | ✅ Pro | — |
| PIN code app lock | 🟡 | ✅ | — | We have biometric but not PIN-only. **LOW** |
| Auto-lock after inactivity | ✅ | ✅ | ✅ Pro | — |
| Screenshot protection (FLAG_SECURE) | ✅ | — | — | 🆕 |
| Plugin permission system | 🚫 | ✅ | — | We don't have plugins (everything built-in). |
| FIDO2 SSH auth | ❌ | — | ✅ Free | (Listed in Auth above) |
| Open source (auditable) | ✅ MIT | ❌ | ❌ | 🆕 |
| No telemetry / analytics | ✅ | ✅ | partial | — |

## Hypervisor / cloud

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Proxmox VE management | ✅ | — | — | 🆕 |
| XCP-ng / Xen Server | ✅ | — | — | 🆕 |
| Xen Orchestra (REST + WS live updates) | ✅ | — | — | 🆕 |
| VMware ESXi/vCenter | 🟡 | — | — | API works, console wiring deferred. **MEDIUM** |
| Oracle Cloud Infrastructure (OCI Compute) | ✅ | — | — | Path A onboarding + signed REST. Console deferred. 🆕 |
| VM serial console via hypervisor (no VM network) | ✅ | — | — | 🆕 |
| AWS EC2 auto-import | ❌ | ✅ Pro | ✅ Pro | **🚫 unless explicitly wanted — cloud privacy** |
| GCP / DO / Azure auto-import | ❌ | — | ✅ Pro | Same — opt-in only |

## Performance monitoring

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| CPU / RAM / disk / net live polling | ✅ | ✅ Plugin | — | Built-in 🆕 |
| Configurable polling interval | ✅ | ✅ Plugin | — | — |
| Background SSH for metrics | ✅ | ✅ Plugin | — | — |
| Charts | ✅ MPAndroidChart | ✅ Plugin | — | — |
| Multi-host dashboard | ✅ | — | — | Shipped `2596eeb7` — sysadmin cards, dashboard groups, DiffUtil, monitor bell |

## Misc / advanced

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Plugin SDK | 🚫 | ✅ Free | — | Decision: we ship everything built-in, no plugin model. |
| Tasker integration | ✅ | ✅ Plugin | — | — |
| In-app changelog | ✅ | ✅ | ✅ | `WhatsNewActivity`, linked from nav drawer |
| Connection history view | ✅ | — | ✅ Free | We have last-connected timestamp |
| Cluster snippets fan-out | ✅ | ✅ Plugin | ✅ Pro | — |
| Team Vault / shared groups | 🚫 | ✅ Pro | ✅ Team+ | **🚫 out of scope** unless we add a self-hosted backend. SAF sync covers personal multi-device. |
| SAML SSO | 🚫 | — | ✅ Enterprise | Out of scope. |
| SCIM provisioning | 🚫 | — | ✅ Enterprise | Out of scope. |
| Audit log of activity | ✅ | — | ✅ Business | Already have AuditLogViewerActivity |
| CLI companion | ❌ | — | ✅ Free | Termius CLI for headless management. **LOW** (Android-only app) |
| Education / OSS free plans | n/a | — | ✅ Free | We're already free for everyone |
| FAQ / help links in-app | 🟡 | ✅ | ✅ | We have About; add a help section. **LOW** |

---

## Audit corrections (verified 2026-05-17 against live codebase)

The table rows below were wrong at time of audit. All confirmed against source:

| Row | Was | Correct status |
|-----|-----|----------------|
| Find/search in scrollback | ❌ HIGH | ✅ shipped `7841256b841a` — `ScrollbackSearchController`, Ctrl+Shift+F, live highlights |
| Reconnect button on disconnected tab | 🟡 Auto-close only | ✅ `showReconnectDialog()` fully implemented; exit 0 = silent close, non-zero = Reconnect/Close dialog |
| Multi-host dashboard | ❌ LOW | ✅ shipped `2596eeb7` — sysadmin cards, dashboard groups, DiffUtil, per-host monitor bell |
| Mosh | 🟡 not wired | ✅ `libmosh-client.so` bundled for all 4 ABIs; `MoshHandoff.bootstrap()` + `tab.connectMosh()` fully wired in `TabTerminalActivity` |
| Per-connection env vars | ❌ MEDIUM | ✅ `ConnectionProfile.envVars` field + `ConnectionEditActivity` UI + `SSHConnection` `setEnv()` fan-out |
| Bulk import: CSV/JSON/PuTTY/Terraform | ❌ MEDIUM | ✅ `BulkImportParser.kt` supports all four formats with auto-detection |
| SSH agent forwarding | "wire toggle" | ✅ `SSHConnection` sets `channel.setAgentForwarding(true)` + `IdentityRepository` populated |
| File-edit in SFTP | ❌ MEDIUM | ✅ `RemoteFileEditorActivity` + `openOrEditRemoteFile()` in `SFTPActivity` |
| chmod in SFTP | 🟡 display only | ✅ `showPermissionsDialog()` in `SFTPActivity` — full rwxr-xr-x checkboxes |
| Custom theme GUI editor | 🟡 no GUI | ✅ `ThemeEditorActivity` |
| Snippet variables `{?var}` | 🟡 basic only | ✅ `showVariablesDialog()` — hint text, password masking, per-variable recall from SharedPrefs |
| Workspaces (named tab groups) | ❌ MEDIUM | ✅ save/open/delete via `showSaveWorkspaceDialog()` / `showOpenWorkspaceDialog()` in `TabTerminalActivity` |
| Split view (multi-pane) | ❌ HIGH tablet | ✅ "Wave 2.8" minimal split — bottom pane per tab, independent SSHTab, `action_unsplit` |
| Command palette (Ctrl+K) | ❌ MEDIUM | ✅ `PaletteDialog.kt` |
| Telnet protocol | ❌ MEDIUM | ✅ `TelnetConnection.kt` |
| Broadcast input (interactive) | 🟡 batch only | ✅ `TermuxBridge.broadcastTargets` fan-out; `showBroadcastTargetsDialog()` in `TabTerminalActivity` |
| In-app changelog | ❌ LOW | ✅ `WhatsNewActivity`, linked from nav drawer |

**Genuine remaining gaps (as of 2026-05-17):**
- Local Android shell — no implementation found
- Auto-open browser on forwarded port — no implementation found
- Bottom nav bar on phones — no `BottomNavigationView` anywhere
- ML-DSA / ML-KEM post-quantum — JSch limitation (as-is)
- FIDO2 hardware key auth — JSch limitation (stub exists, documented in AI.md §16)

---

## Shipped since this audit (post-2026-04-30, in addition to crossed-out items above)

- ✅ **Multi-tab same-host with independent shells** (Issue #163) — open one profile in two tabs and get two independent `ChannelShell`s on a single JSch Session. Per-tab close + per-tab PTY resize. Sibling tabs survive when one shell exits.
- ✅ **Active Sessions strip** (Issue #165) — top of the Connections tab shows running tabs (with dynamic terminal title from OSC 0/2 + connection-state dot). Tap to focus. Disambiguates duplicate titles with `(#N)`. Backed by `TabManager.tabsFlow: StateFlow<List<SSHTab>>`.
- ✅ **Edge-swipe tab switching** (Issue #168) — single-finger fling within 24dp of the left/right edge (when ViewPager2 swipe-mode is off).
- ✅ **Tmux/Screen auto-launch + post-connect script** (Issue #170) — `profile.multiplexerMode` (`AUTO_ATTACH` / `CREATE_NEW`) + `profile.postConnectScript` lines now actually fire ~500ms post-connect. Both used to be defined-but-not-wired.
- ✅ **Always-on keepalive** (Issue #166) — `serverAliveInterval = 60_000ms` + `setServerAliveCountMax(3)` set unconditionally; the per-profile toggle is gone.
- ✅ **Centralised error dialogs with Copy** (Issue #167) — every `showError` and "Failed" dialog routes through `DialogUtils.showErrorDialog` for a guaranteed Copy button + clipboard toast.
- ✅ **Recordable macros** (Issue #173, DB v25 → v26) — capture raw byte sequences (escape codes, paste payloads, modifier-composed Ctrl/Alt) via `TermuxBridge.{start,stop}MacroRecording()`; replay verbatim. Distinct from snippets (which are typed text + `{?var}`).
- ✅ **Hardware keyboard modifier-aware nav keys** (Issue #171) — xterm-style `\e[1;<mod><letter>` for Shift/Ctrl/Alt + arrows / HOME / END / PG family. AltGr distinguished from real Alt.
- ✅ **Cold-start ANR fixes** (Issue #158) — `MainPagerAdapter` lazy `createFragment`, `repeatOnLifecycle(STARTED)` for Flow collection in `ConnectionsFragment` / `IdentitiesFragment`, DB pre-warm in `initializeCoreComponents`. ViewStub-defer of the Active Sessions strip (Issue #175) recovers the headroom we used adding the strip.
- ✅ **Cold-start commit-id marker** (Issue #164) — `## apk built from: <commit> ##` logged once per commit-id change to both app + debug log; persisted in shared_prefs so it doesn't spam every cold start. Resolves at build time via `providers.exec` (Gradle config-cache safe), falls back to `release.txt` then `"unknown"`.
- ✅ **Repo cleanup** (Issue #160) — `setup-emulator.sh`, `scripts/test-build.sh`, `scripts/check/quick-check.sh` deleted as duplicates of `make build` / `make check`. `keystore.jks.sh` → `scripts/generate-keystore.sh`. `scripts/check/dev-shell.sh` → `scripts/dev-shell.sh`. Test sshd container moved to `docker/test-sshd/` with a launcher at `scripts/start-test-sshd.sh` (bakes in the `tabssh-test` user + ed25519 client keypair).
- ✅ **On-screen keyboard ergonomics** (Issues #161, #162) — IME-toggle (⌨) key dropped from all default-row layouts (back-key already dismisses the soft keyboard). Defaults reordered for vim/tmux/coding: row 1 = ESC + CTL/ALT/FN + `:` + `/` + arrows; row 2 = page navigation + TAB/ENT/PASTE; row 3 = coding symbols `| \ - _ ~ \` $ * < >`.

## Suggested priority buckets (proposal — you decide)

### 🔥 Tier 1 — finish the half-done + quick wins (all shipped)
1. ~~**Mosh** — wire backend ↔ UI~~ ✅ done
2. ~~**Find/search in scrollback**~~ ✅ done `7841256b841a`
3. ~~**Per-connection environment variables**~~ ✅ done
4. ~~**SSH agent forwarding**~~ ✅ done
5. ~~**Reconnect button on disconnected tab**~~ ✅ done
6. ~~**Bulk import** — CSV/JSON/Terraform/PuTTY~~ ✅ done
7. ~~**File-edit in SFTP**~~ ✅ done
8. ~~**chmod editing in SFTP**~~ ✅ done

### 🚀 Tier 2 — meaningful new capabilities
9. **FIDO2 / hardware-key SSH auth** — JSch limitation; stub exists (AI.md §16); needs JSch fork ~80h
10. ~~**SSH certificate auth** — OpenSSH cert files~~ ✅ already done (listed as ✅ in table above)
11. ~~**Telnet protocol**~~ ✅ done (`TelnetConnection.kt`)
12. ~~**Workspaces (named tab groups)**~~ ✅ done
13. ~~**Split view (multi-pane terminal)**~~ ✅ done (Wave 2.8 minimal split)
14. ~~**Command palette (Ctrl+K)**~~ ✅ done (`PaletteDialog.kt`)
15. ~~**Custom theme GUI editor**~~ ✅ done (`ThemeEditorActivity`)
16. ~~**Snippet variables: prompt-style `{?password}`**~~ ✅ done
17. ~~**Broadcast input (interactive multi-host typing)**~~ ✅ done (`TermuxBridge.broadcastTargets`)

### 🎯 Tier 3 — polish & nice-to-haves
18. **Bottom-nav bar on phones** (Termius-style) — no implementation yet
19. **Per-host color tags** (in addition to group colors)
20. **Auto-open browser on forwarded HTTP(S) port** — no implementation yet
21. ~~**Run port forwards without a terminal session**~~ ✅ done (`PortForwardingActivity` background tunnels)
22. **PIN code app lock** (currently biometric-only)
23. ~~**In-app changelog / what's new**~~ ✅ done (`WhatsNewActivity`)
24. ~~**Per-host startup commands UI exposure**~~ ✅ done (post-connect script field in ConnectionEditActivity)
25. ~~**Bluetooth keyboard polish + AltGr**~~ ✅ **shipped (Issue #171)**
26. ~~**Connection history view**~~ ✅ done (last-connected timestamp + history in ConnectionProfile)

### 🧊 Tier 4 — speculative / situational
- True-color rendering verification
- Foldable layout
- Tablet top-tabs / desktop-style chrome
- SFTP tabs / drag-drop
- Cluster command live result streaming
- Voice-typing affordance
- Multi-host performance dashboard

### 🚫 Out of scope (unless you say otherwise)
- AI command generation / AI Agent (cloud LLM, privacy)
- AWS / GCP / DO / Azure auto-import (privacy + cloud creds)
- Team Vault / shared groups / SAML SSO / SCIM (SaaS backend)
- CLI companion (Android app only)
- Plugin SDK (built-in everything)
- Cross-platform desktop app (Android only)

### 🆕 Where we already lead
- Hypervisor management (Proxmox / XCP-ng / Xen Orchestra / VMware) — **none of them have this**
- VM serial console via hypervisor API (no VM network needed)
- 23 built-in themes (vs 6 / 10+)
- 1-5 row fully customisable keyboard
- SAF sync (works on de-Googled ROMs)
- Three-way merge with conflict UI
- Per-entity sync toggles
- Hardware-backed Android Keystore
- Screenshot protection
- Open source (MIT)
- Recursive folder transfer

---

## What this means for README + AI.md

After we settle which Tier-1/2/3 items we're committing to:

- **README.md** needs a "what's done / what's coming" rewrite — the current roadmap section is short.
- **AI.md** §17 (or new §18) should add a "feature parity tracker" pointing at this audit + the agreed scope.
- Existing CLAUDE.md "Recent Changes" entries describe history — leave intact, but the live status truth lives in TODO.AI.md.

---

## Discussion next

Mobile-friendly format. Topics in priority order. One per message. You reply yes/no/skip.

Sources:
- `/tmp/tabssh-android/audit-ours.md` (290 of our features)
- `/tmp/tabssh-android/audit-juicessh.md` (140 JuiceSSH features)
- `/tmp/tabssh-android/audit-termius.md` (135 Termius features)

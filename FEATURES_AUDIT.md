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
| Mosh protocol | 🟡 | ✅ | ✅ Free | **Code exists, UI toggle exists, not wired end-to-end. HIGH** |
| Telnet protocol | ❌ | ✅ Free | ✅ Free | Legacy gear (switches/routers). **MEDIUM** |
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
| Bulk import: PuTTY, CSV, JSON, OpenSSH config, Terraform | ❌ | ❌ | ✅ Free | **MEDIUM** |
| Jump host / ProxyJump | ✅ | ✅ ("Connect-via") | ✅ Pro | We have it |
| Connect-via chained nested connections | ✅ | ✅ | ✅ | Same as jump host |
| HTTP/SOCKS proxy | ✅ | — | ✅ Pro | — |
| Port knocking | ✅ | ✅ Plugin | — | We have built-in |
| Custom SSH ciphers/MACs | ✅ | — | ✅ | We have modern defaults; no UI yet to override |
| ML-DSA / post-quantum auth | ❌ | — | ✅ Free | NEW. **LOW** until OpenSSH 9.x is widespread |
| ML-KEM key exchange | ❌ | — | ✅ Free | Same — JSch 2.27 already supports it |
| Per-connection env vars | ❌ | ✅ Free | ✅ Free | **MEDIUM** — easy win, very useful |
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
| SSH agent forwarding | 🟡 | ✅ | ✅ Pro | UI toggle exists, not wired. **MEDIUM** |
| FIDO2 / U2F hardware key auth | ❌ | — | ✅ Free | YubiKey, Solo, Google Titan over USB-C/NFC. **MEDIUM-HIGH** |
| SSH certificate auth | ❌ | — | ✅ Free | OpenSSH cert files. **MEDIUM** |
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
| Custom theme editor | 🟡 | — | ✅ Free | We have JSON import/export but no GUI editor. **MEDIUM** |
| Per-host theme override | ✅ | — | ✅ Free | — |
| Programmer fonts bundled | ✅ 8 | ✅ 8 | ✅ 7 | — |
| Cursor styles | ✅ | ✅ | ✅ | — |
| Bell options (audio/visual/silent) | ✅ | ✅ | ✅ | — |
| Configurable scrollback | ✅ | ✅ | ✅ | — |
| Find/search in scrollback | ❌ | — | ✅ Free | **HIGH** — daily use |
| Long-press copy text | ✅ | ✅ | ✅ | — |
| Long-press context menu | ✅ | ✅ | ✅ | New (Phase 7.8) |
| Terminal autocomplete (suggestions while typing) | ❌ | — | ✅ Free | Like fish/zsh inline completion. **MEDIUM-HIGH** |
| AI command generation | ❌ | — | ✅ Free | English → shell command. **🚫 out of scope** (cloud LLM, privacy) — unless we wire local model later |
| AI Agent / chat with infra | ❌ | — | ✅ Pro | **🚫 out of scope** — cloud LLM |
| Broadcast input (one keystroke → many tabs) | 🟡 | — | ✅ Pro | We have ClusterCommandActivity but not interactive broadcast. **MEDIUM** |
| Session log auto-record | ✅ | — | ✅ Pro | We have transcript recording |
| URL detection + open | ✅ | — | — | 🆕 |

## Tabs / sessions

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Multi-tab UI | ✅ | ✅ | ✅ | — |
| Swipe between tabs | ✅ | — | ✅ | — |
| Tab list / switcher | ✅ | ✅ | ✅ | — |
| Tab reordering (drag) | 🟡 | — | ✅ | Infrastructure exists, UI toggle deferred. **LOW** |
| Reconnect button on disconnected tab | 🟡 | ✅ | ✅ | Auto-close currently; should also offer Reconnect. **MEDIUM** |
| Session indicator badge | ✅ | ✅ | ✅ | — |
| Background session via notification | ✅ | ✅ | ✅ | — |
| Workspaces (named tab groups) | ❌ | — | ✅ Free | Group related tabs into a workspace. **MEDIUM** |
| Split view (multiple panes per tab) | ❌ | — | ✅ Pro (16-pane) | Vertical/horizontal pane split. **HIGH for tablets** |
| Command palette (Ctrl+K) | ❌ | — | ✅ Free | Quick switch / open host. **MEDIUM** |
| Quick switcher (Ctrl+J) | ❌ | — | ✅ Free | Jump to open tab. **MEDIUM** |
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
| Edit remote files in-app | ❌ | — | ✅ Free | Tap a file → integrated text editor → save back. **MEDIUM** |
| Drag-and-drop | ❌ | — | ✅ Free | Inside the SFTP UI on tablets. **LOW** |
| chmod / permissions edit | 🟡 | — | ✅ Free | We display, don't edit. **MEDIUM** |
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
| Run forwards without terminal session | 🟡 | ✅ Pro | ✅ Free | Currently requires connection. **MEDIUM** |
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
| Variable placeholders | ✅ basic | — | ✅ | We have `{host}`/`{user}`/`{date}`. Add prompt-style `{?password}` etc. **MEDIUM** |
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
| Multi-host dashboard | ❌ | — | — | A "rack view" overlay across active connections. **LOW** |

## Misc / advanced

| Feature | Us | JuiceSSH | Termius | Notes |
|---|---|---|---|---|
| Plugin SDK | 🚫 | ✅ Free | — | Decision: we ship everything built-in, no plugin model. |
| Tasker integration | ✅ | ✅ Plugin | — | — |
| In-app changelog | ❌ | ✅ | ✅ | What's-new screen on update. **LOW** |
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

### 🔥 Tier 1 — finish the half-done + quick wins
1. **Mosh** — wire backend ↔ UI (we have both halves)
2. **Find/search in scrollback** — daily use
3. **Per-connection environment variables** — easy, very useful
4. **SSH agent forwarding** — wire toggle to real implementation
5. **Reconnect button on disconnected tab** — replace auto-close as the only option
6. **Bulk import** — CSV/JSON/Terraform/PuTTY in addition to ssh_config we already have
7. **File-edit in SFTP** — open file → text editor → save back
8. **chmod editing in SFTP**

### 🚀 Tier 2 — meaningful new capabilities
9. **FIDO2 / hardware-key SSH auth** — YubiKey via USB-C/NFC
10. **SSH certificate auth** — OpenSSH cert files
11. **Telnet protocol** — legacy gear
12. **Workspaces (named tab groups)**
13. **Split view (multi-pane terminal)** — big tablet win
14. **Command palette (Ctrl+K) + quick switcher (Ctrl+J)**
15. **Custom theme GUI editor** (we have JSON I/O, give it a UI)
16. **Snippet variables: prompt-style `{?password}`**
17. **Broadcast input (interactive multi-host typing)**

### 🎯 Tier 3 — polish & nice-to-haves
18. Bottom-nav bar on phones (Termius-style)
19. Per-host color tags (in addition to group colors)
20. Auto-open browser on forwarded HTTP(S) port
21. Run port forwards without a terminal session
22. PIN code app lock (currently biometric-only)
23. In-app changelog / what's new
24. Per-host startup commands UI exposure (we have post-connect script field already)
25. ~~Bluetooth keyboard polish + AltGr~~ — **shipped (Issue #171)**: xterm-style modifier-encoded nav keys, AltGr distinguished from real Alt
26. Connection history view (separate from "last connected")

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

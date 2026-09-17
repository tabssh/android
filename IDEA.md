## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, QEMU/libvirt, and OCI hypervisors, cloud instances, and container hosts (Docker, Incus, Podman, LXC/LXD) — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

Android is the reference implementation of the TabSSH ecosystem. TabSSH Desktop
(`../desktop`, Windows/macOS/Linux/BSD) and TabSSH Web (`../web`, self-hosted
browser client) are siblings that must interoperate with this app, not just
resemble it: the same encrypted sync blob, backup archive, QR pairing payload,
and theme file must work unmodified across all three. Any change here to a
shared format is a breaking change for both siblings until they catch up — see
"Must be compatible with" below.

## Project variables

project_name: tabssh
project_org: tabssh
# FROZEN — set once at first-time setup, never edit
internal_name: tabssh
# FROZEN — set once at first-time setup, never edit
internal_org: tabssh
# FROZEN forever — shipped applicationId
app_id: io.github.tabssh
min_sdk: 24
license: MIT
# views — existing app with XML layouts
ui_toolkit: views
di: manual
store_targets: fdroid, provider-releases
form_factors: phone, widget
repository: https://github.com/tabssh/android
desktop_sibling: ../desktop
web_sibling: ../web

### Applicability

database: yes
network: yes
notifications: yes
background_work: yes
backup_sync: yes
media: no

### Toolchain

# The maintained toolchain image (AI.md PART 4), shared by the GitHub
# workflows and local Docker builds for reproducibility
build_image: casjaysdev/android:latest
kotlin: 2.4.10
agp: 8.13.2
gradle: 8.14.5
compile_sdk: 35
target_sdk: 34
version_code_scheme: manual
http_client: OkHttp   # sole HTTP client app-wide (PART 9) — never mixed with Retrofit/Ktor

## Business logic

### Core SSH features the app must have
- Multi-tab SSH sessions modeled on browser tabs — swipe and keyboard navigation between live sessions
- Panes — tile up to 6 SSH/Telnet/Mosh sessions in a fixed auto-sized grid inside one terminal tab (two panes can split side by side or stacked); tap a pane to focus it; Sync Input mirrors typing from the focused pane to every other pane and highlights all pane tiles while on; close individually or as a group (Disconnect All / Keep Running in Background); auto-stacks to a single column on narrow screens
- Full VT100/ANSI/xterm-256color terminal emulation
- SSH authentication: password, SSH key (RSA, ECDSA, Ed25519, legacy DSA; OpenSSH, PEM, and PuTTY .ppk formats), keyboard-interactive
- SSH key management: import (file / paste), generate (RSA, ECDSA, Ed25519), fingerprint display, passphrase protection, OpenSSH certificate attachment
- Reusable credential identities (username + auth method) that can be attached to multiple connections
- Jump host (ProxyJump) support
- Port forwarding: local, remote, dynamic (SOCKS5)
- Port knocking before connect
- Agent forwarding
- SFTP file browser with upload, download, rename, chmod, delete; remote file editor; SCP upload fallback
- Session recording and replay (transcript) — transcripts stay on-device and must be readable by the user outside the app
- Session video recorder — screen capture to mp4 for any visible tab (SSH, VNC, Panes); SSH tabs choose between Terminal Cast only (the default — an asciinema v2 `.cast` with no screen capture and no capture consent prompt), Video only, or Video + Terminal Cast recording the `.cast` alongside the mp4, captured independently of the session transcript recorder so neither recorder can interfere with the other; both files save to `Movies/TabSSH` and offer a post-stop Share action; recording pauses (not stops) when the user swipes away from the recorded tab and auto-stops with a toast if that tab is closed
- Recordings browser — one list of everything the recorders produce (videos, terminal casts, session transcripts) from any tab type, filterable by kind, with per-item actions: play/share videos, upload/share casts, view transcripts, delete any; reachable from the terminal menu and from settings
- Dedicated Recording & Transcripts settings screen — recording and transcription options live in their own settings section (not under Terminal), so they apply to every tab type that can record and leave room for new recording features
- `~/.ssh/config` import
- Bulk import: CSV, JSON, PuTTY .reg, Terraform `.tf` config files — each format maps its fields onto connection profiles (host, port, user, auth, group)
- Custom on-screen keyboard with configurable rows
- Find-in-scrollback
- Snippet library with `{var}` and `{?name:default|hint}` placeholder substitution — placeholders are filled through a prompt UI at run time
- Macro library — record raw byte sequences and replay them into any session
- Mosh support — sessions must survive IP changes and network roaming
- Telnet connections alongside SSH (plain-text legacy protocol, clearly separated from SSH profiles)
- X11 forwarding to a local Android X server (XServer-XSDL / Termux:X11)
- Terminal multiplexer integration (tmux / screen / zellij) — auto-attach, create-new, and ask-on-connect modes, automatic detection of a running multiplexer, and a manual override picker
- Post-connect script execution
- Per-connection color tags, font size overrides, custom themes
- URL detection on long-press
- Performance dashboard with configurable monitor slots per host and metric graphs
- Multi-Host Dashboard — side-by-side CPU/memory/disk metric graphs across hosts, grouped independently from connection groups
- Domain & VPS Renewal Tracking — two trackers (Domain Tracker, VPS Hosting Tracker) for upcoming renewal dates, with CSV/Markdown import-export and reminder notifications as expiry approaches
- Touchpad-emulating terminal surface with three distinct zones: a left-edge wheel zone (mouse-wheel-notch scrolling — a quick flick fires one notch, a sustained drag repeats one notch per line of travel; notch size is user-configurable, default 3 lines), a right-edge desktop-terminal scrollbar (konsole/xfce4-terminal style) — a persistent track and draggable thumb that scrolls the terminal's own scrollback, the thumb fills the track when there is nothing to scroll back through — and everywhere else acting as a 1:1 touchpad (proportional drag/swipe scrolling, no gearing); swipe left/right still changes tabs; none of the three zones may interfere with each other or with left/right tab-switch swipes
- Per-session status notifications — every open tab gets its own shade entry (even when tabs share one host), tapping jumps to that exact tab, a Disconnect action closes just that session, and entries clear as soon as their tab closes

### Security requirements
- All passwords and private key passphrases must never be stored in plaintext or in the database
- Credential storage with tiered access levels: never / session-only / encrypted / biometric — encrypted tiers are backed by hardware-backed device key storage
- Biometric unlock for stored passwords — re-authentication gates every read; encrypted credentials persist until the user removes them, never expiring on a timer (no TTL)
- App-lock PIN with a failed-attempt lockout — the PIN must never be stored in plaintext or in any recoverable form
- Screenshot capture prevention (configurable); always enforced on PIN and auth screens
- SSH host key verification on first connect (TOFU) with fingerprint display
- Clipboard auto-clear after copying sensitive values (off by default; 30 s, 1 min, or 5 min) — never wipes clipboard content that another app put there
- Audit log of SSH commands and session events — stays on-device, with user-configurable size (MB) and age (days) retention caps and separate command/output capture toggles

### Sync and backup
- Cross-device sync via SAF — user supplies any DocumentsProvider (Google Drive, Dropbox, OneDrive, Nextcloud, local); app embeds no cloud SDKs
- Cross-device merge with per-entity conflict resolution — a conflicting row pauses sync and offers keep local / keep remote / keep both, with last-write-wins preselected
- Every conflict and its resolution is recorded in a dedicated Sync Log, viewable in the app; conflicts never go to the application or debug log, which stay reserved for genuine app faults
- Reusable network routes (proxies and SSH jump hosts) sync device-to-device like port-forward rules — full row, last-write-wins, and no secrets to keep Keystore-bound
- End-to-end encrypted sync — a user passphrase is required, there are no server-side keys, and sync data is never readable by the storage provider
- Backup and restore as a portable archive covering everything the app stores — encrypted when the user sets a password, plaintext when they do not; backups made by older app versions must always import into newer versions
- Stored secrets (SSH private keys and passphrases, connection, identity, hypervisor, VNC, telnet, container host and registry credentials, cloud API tokens, OCI private keys) are exported only in a password-encrypted backup, as a separate encrypted section; a backup made without a password contains no credentials at all, and choosing one shows a warning naming what is left out

#### Sync coverage matrix

Every user-authored entity syncs behind a toggle in Sync Settings — one
toggle per entity, except that the five container entities share a single
Containers toggle. All default on except dashboard config, which stays
per-device until the user opts in. What deliberately does not sync:

| Entity | Syncs | Backed up | Why |
|---|---|---|---|
| Connections, keys, identities, groups, workspaces, themes, snippets, macros | yes | yes | user-authored |
| Host keys, trusted certificates | yes | yes | trust state is per-user, not per-device |
| Hypervisors, hypervisor accounts, cloud accounts, container hosts, registry credentials, compose stacks, single-container configs, auto-update policies | yes | yes | user-authored |
| VNC hosts/identities, telnet hosts, port forwards, network routes, pane groups, monitor slots, dashboard config | yes | yes | user-authored |
| Domains, VPS hosts (trackers) | yes | yes | user-authored renewal data |
| Secrets (passwords, passphrases) | yes, separately | only in a password-encrypted backup | Keystore-bound; travels in its own encrypted section |
| Tab sessions | no | yes | restoring another device's open tabs is wrong |
| Audit log | no | yes | a local-device record of what happened on *this* device |
| Sync state, shadows, tombstones, pending conflicts, sync log | no | no | sync machinery, not user data |
| Connectable hosts | no | no | derived lookup table, rebuilt from its source rows |

New entity checklist: decide sync inclusion, then update
`SyncDataCollector`, `SyncDataApplier` and this matrix in the same commit.

### Hypervisor management
- Proxmox, XCP-ng (and Xen Orchestra), VMware, QEMU/libvirt (KVM) — list VMs/instances, start, stop, shutdown, reboot; snapshots on Proxmox, Xen Orchestra, VMware, and QEMU/libvirt (not on direct XCP-ng connections)
- QEMU/libvirt is managed over an SSH transport to the remote host — no libvirt TCP daemon needs to be exposed
- Built-in VNC console client for VM graphical consoles — consoles open as swipeable tabs next to terminal sessions
- SPICE console client for hypervisors that expose SPICE displays
- Reusable hypervisor credential accounts (username/password or OCI API key) shared across hypervisor profiles
- TLS certificate pinning (TOFU) for hypervisor REST APIs whether or not SSL verification is enabled on the profile (off by default to accommodate self-signed hypervisor certs) — with verification off the first-seen certificate is pinned silently; in both modes a changed certificate is accepted only on explicit user re-approval
- OCI API key authentication (tenancy, user, region, fingerprint, optional compartment, private key — the private key is Keystore-only)

### Cloud provider management
- Manage SSH-accessible instances across DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, Google Cloud Compute, Azure VMs, and Oracle Cloud (OCI)
- All eight providers expose the same feature surface — list instances, live state, power control, SSH connect — no provider gets a reduced experience
- Live instance state (running / stopped / transitioning) with start/stop control
- Cloud account credentials must never be stored in the database
- No vendor SDKs embedded — all providers accessed via their REST APIs

### Container host management
- Portainer-class management of container hosts reached over the user's existing SSH connections — no host agent, no exposed engine API port required
- Four engines at full parity: Docker, Incus, Podman, and LXC/LXD. The engine is chosen from a dropdown when the host is added — order Docker (preselected default), Incus, Podman, LXC/LXD — and every screen adapts to the engine instead of hiding behind a Docker-only assumption
- Capability-driven UI: a concept an engine does not have is hidden, never shown empty. Docker and Podman get compose stacks and disk usage; Incus and LXC/LXD get snapshots plus dedicated profiles and projects tabs
- A Containers sub-tab, first under the Infra main tab ahead of Hypervisors and Cloud; adding a host mirrors the hypervisor add flow but authenticates over SSH only — link a saved SSH connection or enter a custom endpoint (address, port, username, auth via password/SSH key/saved identity); the host name is optional, defaulting to the connection name or endpoint hostname. Host pickers (new tab, pane group, port forward, dashboard add-hosts) hide a container host whose linked SSH connection or cloud instance is already in the list, so the same machine is not offered twice
- Containers are a separate domain like hypervisors: custom-endpoint sessions and container exec tabs never appear in the active-sessions list, recents, connection stats, or session restore; custom-endpoint passwords are Keystore-only, never a database column
- Per-host view, tabs in order: Dashboard, Containers, Stacks, Images, Volumes, Networks. The dashboard is per host, and counts stack members even though the Containers list hides them — 3 standalone containers plus 2 stacks of 2 shows 2 stacks and 7 containers
- Containers: list, inspect, start/stop/restart/pause/kill/rename/remove, live-follow logs, live stats; enter any running container as a normal terminal tab via the engine's exec (shell auto-detected)
- Images (pull with progress, remove, prune), volumes, and networks on every engine
- Compose stacks are paste-first: paste a complete compose file and it is saved to a per-host configurable remote directory (default `/srv/$USER/compose/{name}`) and run; up/down/pull/restart with per-service status; remote directories are created on demand
- Single-container run configs: a form-based `run.yml` (mirroring `docker run` flags) per container under a second configurable remote directory (default `/srv/$USER/docker/{name}`), with a raw-YAML advanced toggle
- Hybrid transport, identical for every engine: the engine's REST API over an SSH forward of its unix socket when the server permits it, automatic fallback to the engine's CLI over SSH exec — every feature works on CLI-only hosts, with documented degradation (stats become polled)
- The socket path is auto-detected from the selected engine's known locations; a per-host override replaces it and also accepts `tcp://host:port` and `ssh://user@host`
- Socket forwarding requires sshd `AllowTcpForwarding yes` and `AllowStreamLocalForwarding yes`; a transport that cannot be established fails early with a blocking error card carrying an actionable remediation hint and a Retest action — never a silent permanent downgrade
- Container sessions are pooled per host: one SSH connection per host opened on demand, locked per host (parallel across hosts), LRU-capped at 16 open sessions, disconnected after 10 minutes idle, and dead sessions evicted with their relays closed — monitoring-only SSH connections are released with the session, user terminal connections never are
- App-driven, watchtower-style updates: periodic registry digest checks flag stale containers (notification + in-app badge); unattended pull+recreate is opt-in per policy and must preserve the container's configuration, with automatic rollback if the replacement fails
- Update checks run twice daily by default, at most 2 hosts concurrently; each container host can disable checks or set its own interval in hours (blank = default), stored on the host row
- Docker Hub, image servers, and private registries (Basic/Bearer) supported; registry credentials are Keystore-only, never a database column

### Accessibility and UI
- TalkBack support with content descriptions on all interactive elements
- High-contrast terminal mode and large touch targets
- Full keyboard navigation
- 23 built-in terminal themes; user-created custom themes; dark/light/auto per OS preference
- Mobile-responsive; supports both phone and tablet layouts
- Supported locales: English (default), German, Spanish, French — additional `values-xx/` folders added as translations arrive

### Automation and integrations
- Tasker/Locale plugin for launching connections from external apps
- Quick-connect home-screen widgets
- Tasker, widgets, and quick-connect all drive the same public intent surface, so third-party automation apps can launch connections too
- QR pairing for importing connection profiles from a Desktop or Web companion — the QR payload must be encrypted and useless without the user's passphrase

### Distribution constraints
- F-Droid compatible: no proprietary libraries, no analytics, reproducible build variant
- Zero telemetry by default; opt-in only
- Works fully offline (no cloud account required to use the app)
- No feature gating — all functionality available to all users

### Must be compatible with

Android is authoritative for every shared format below — Desktop and Web must
track it, never fork it. A PR that changes one of these without a matching
compatibility note here (and a heads-up to the sibling repos) is incomplete:

- `TABSSH_SYNC_V3` sync wire format — AES-256-GCM + Argon2id key derivation
  with a self-describing KDF-parameter header; Desktop and Web read/write the
  same encrypted blob so a device can sync against any storage provider
  regardless of which app last wrote to it
- QR pairing payload — CBOR + AES-256-GCM + Argon2id; a QR generated by
  Desktop or Web must decode here unmodified, and vice versa
- Backup archive format — version 4, a ZIP whose first entry is
  `manifest.json`, then one `{"v":4,"items":[...]}` file per entity (version 3
  single-JSON archives still restore); a backup made by any sibling must
  restore cleanly on the others
- Room schema (currently v27) — Desktop's SQLite and Web's schema track
  Android's entity shapes and field set; schema version numbers are
  independent per platform but the fields must line up
- Built-in terminal theme catalogue (23 themes) — byte-identical theme
  definitions and exported theme JSON across all three
- SSH host key fingerprints — SHA-256 + emoji visual fingerprint format must
  render identically so a fingerprint verified on one platform is recognizable
  on another
- Session recording/transcript format — interchangeable so a transcript
  captured on one platform is readable on the others

Compatibility notes:

- Sync wire format bumped V2 → V3 (2026-08): V3 adds a self-describing
  Argon2id KDF-parameter header. The legacy PBKDF2 V2 reader was deliberately
  removed pre-release ("legacy purge"); readers on all platforms accept V3
  only, and a V2 blob must be re-seeded by a current build
- Password TTL removed (2026-08): the security settings block in backup
  archives and sync blobs no longer carries a password-TTL value — encrypted
  credentials never expire (no TTL). Importers on all platforms must
  read-and-ignore the old TTL key from archives/blobs written before the
  removal; nothing may reintroduce an expiry timer on stored credentials

### Trust boundaries
- Remote SSH/telnet hosts, hypervisor and cloud APIs, clipboard contents, QR payloads, imported config/bulk files, and user-supplied sync storage are all untrusted input
- The device keystore and the app's own encrypted storage are the only trusted secret stores
- verifySsl defaults to off on hypervisor profiles — an accepted, documented design decision to accommodate self-signed hypervisor certs; TOFU pinning is the compensating control
- Cleartext HTTP stays permitted in network_security_config.xml — an accepted, documented deviation from the AI.md PART 9 cleartext ban: hypervisor/cloud endpoints are user-configured and may be plain-http consoles on private LANs; blocking cleartext would break those setups

### Permission justifications
- Camera: QR pairing import only; declared optional (app fully works without it)
- Notifications: per-session status entries and connection events
- USE_BIOMETRIC / USE_FINGERPRINT: unlocking the app PIN lock and container
  secrets with the device's fingerprint/face/biometric enrollment
  (`SecurePasswordManager`, `SettingsActivity`), as an alternative to typing
  the PIN
- READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE (`maxSdkVersion=29`) /
  MANAGE_EXTERNAL_STORAGE: the local SFTP file browser needs unrestricted
  filesystem access to transfer files outside the app's sandbox; a
  file-manager-class exception under AI.md PART 2/5. Requested lazily only
  when SFTP browsing is opened, with an in-app rationale first
- WAKE_LOCK / ACCESS_WIFI_STATE: keep the CPU/WiFi radio awake for the
  duration of a persistent SSH/mosh/telnet session's foreground service so
  the connection does not drop under Doze/WiFi-sleep
- VIBRATE: haptic feedback on notification delivery
- com.termux.permission.RUN_COMMAND: real Mosh support delegates the mosh
  client binary invocation to the Termux:API app via `RUN_COMMAND`; the user
  must separately grant it in Termux and enable
  `allow-external-apps=true` in `~/.termux/termux.properties`
- RECEIVE_BOOT_COMPLETED: `MonitoringBootReceiver` re-registers the
  WorkManager periodic host-monitoring task after a reboot or app update,
  since some OEM ROMs wipe the WorkManager DB
- REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: places the app in the
  "unrestricted" App Standby bucket so Doze does not defer background host
  monitoring beyond maintenance windows; the user is shown an in-app
  rationale before the system prompt
- io.github.tabssh.permission.TASKER (self-declared, `signature` protection
  level): scopes the Tasker/Locale plugin's fire receiver
  (`LocaleFireReceiver`) to same-signing-key callers only; third-party
  automation apps use the separate `com.twofortyfouram` Locale plugin
  protocol instead, which needs no permission of its own
- Foreground service (special use): keeps SSH/mosh sessions, their port
  forwards, and opted-in VNC sessions alive while backgrounded; typed
  `specialUse` because these are indefinite interactive connections — the
  `dataSync` type's 6-hour hard cap on API 35+ would sever them — with the
  subtype declared per service via `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
- Foreground service (media projection): required by API 34+ to keep an
  active session video recording capturing while the app is backgrounded;
  the system's own screen-capture consent dialog is always shown before
  recording starts — the permission only lets an already-consented capture
  keep running as a foreground service
- Network: the app's core purpose; no network use on first launch is still required

### What the app must never do
- Store raw passwords or PEM keys in the Room database
- Embed cloud provider SDKs or require a cloud account for sync
- Include analytics, crash reporting SDKs, or tracking pixels without explicit user opt-in
- Require network access on first launch

### Release

- Keystore escrow: no production keystore file exists in the repo or on any dev machine (AI.md PART 13). It is escrowed as the `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` (and optional `KEY_PASSWORD`) repo secrets under Settings > Secrets and Variables > Actions; every release channel hard-fails with an actionable error if either required secret is missing, with no ephemeral/generated fallback keystore ever produced.
- Release cadence: `stable` and `beta` are on-demand, triggered by pushing a `vX.Y.Z` / `*beta` tag; `development` runs daily on a schedule plus on every push to `main`, with its rolling `development` tag/release deleted and recreated each run.
- No ACRA-style crash-reporting endpoint is configured — crash reporting stays the AI.md PART 2 default (on-device log + user-triggered export) per the "no analytics/crash-reporting SDK without opt-in" constraint above.

### Accepted design decisions

- Container engine unix-socket forwarding uses the SSH library's native
  `direct-streamlocal@openssh.com` support (no custom protocol code) —
  verified working end-to-end against a real sshd on 2026-08-07. The server
  must allow both TCP and stream-local forwarding; the server reports a
  denial only generically, so the app maps that failure to an actionable
  sshd-configuration hint rather than showing a raw error.

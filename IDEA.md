## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, and OCI hypervisors, cloud instances, and container hosts (Docker, Incus, Podman, LXC/LXD) — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

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
- Full VT100/ANSI/xterm-256color terminal emulation
- SSH authentication: password, SSH key (RSA, ECDSA, Ed25519, OpenSSH format), keyboard-interactive
- SSH key management: import (file / paste / clipboard), generate, fingerprint display, passphrase protection, OpenSSH certificate attachment
- Reusable credential identities (username + auth method) that can be attached to multiple connections
- Jump host (ProxyJump) support
- Port forwarding: local, remote, dynamic (SOCKS5)
- Port knocking before connect
- Agent forwarding
- SFTP file browser with upload, download, rename, chmod, delete; remote file editor; SCP fallback
- Session recording and replay (transcript) — transcripts stay on-device and must be readable by the user outside the app
- Session video recorder — screen capture to mp4 for any visible tab (SSH, VNC, Panes); SSH tabs can additionally record an asciinema v2 `.cast` alongside the mp4, captured independently of the session transcript recorder so neither recorder can interfere with the other; both files save to `Movies/TabSSH` and offer a post-stop Share action; recording pauses (not stops) when the user swipes away from the recorded tab and auto-stops with a toast if that tab is closed
- `~/.ssh/config` import
- Bulk import: CSV, JSON, PuTTY .reg, Terraform `.tf` config files — each format maps its fields onto connection profiles (host, port, user, auth, group)
- Custom on-screen keyboard with configurable rows and gesture bindings
- Find-in-scrollback
- Snippet library with `{var}` placeholder substitution — placeholders are filled through a prompt UI at run time
- Macro library — record raw byte sequences and replay them into any session
- Mosh support — sessions must survive IP changes and network roaming
- Telnet connections alongside SSH (plain-text legacy protocol, clearly separated from SSH profiles)
- X11 forwarding to a local Android X server (XServer-XSDL / Termux:X11)
- Terminal multiplexer integration (tmux / screen / zellij) — auto-attach and create-new modes, automatic detection of a running multiplexer, and a manual override picker
- Post-connect script execution
- Per-connection color tags, font size overrides, custom themes
- URL detection on long-press
- Performance dashboard with configurable monitor slots per host and metric graphs
- Touchpad-emulating terminal surface with three distinct zones: a left-edge wheel zone (mouse-wheel-notch scrolling — a quick flick fires one notch, a sustained drag repeats one notch per line of travel; notch size is user-configurable, default 3 lines), a right-edge desktop-terminal scrollbar (konsole/xfce4-terminal style) — a persistent track and draggable thumb that scrolls the terminal's own scrollback, the thumb fills the track when there is nothing to scroll back through — and everywhere else acting as a 1:1 touchpad (proportional drag/swipe scrolling, no gearing); swipe left/right still changes tabs; none of the three zones may interfere with each other or with left/right tab-switch swipes
- Per-session status notifications — every open tab gets its own shade entry (even when tabs share one host), tapping jumps to that exact tab, a Disconnect action closes just that session, and entries clear as soon as their tab closes

### Security requirements
- All passwords and private key passphrases must never be stored in plaintext or in the database
- Credential storage with tiered access levels: never / session-only / encrypted / biometric — encrypted tiers are backed by hardware-backed device key storage
- Biometric unlock for stored passwords with configurable TTL
- App-lock PIN with a failed-attempt lockout — the PIN must never be stored in plaintext or in any recoverable form
- Screenshot capture prevention (configurable); always enforced on PIN and auth screens
- SSH host key verification on first connect (TOFU) with fingerprint display
- Clipboard auto-clear for sensitive pastes
- Audit log of SSH commands and session events — stays on-device, with user-configurable size (MB) and age (days) retention caps and separate command/output capture toggles

### Sync and backup
- Cross-device sync via SAF — user supplies any DocumentsProvider (Google Drive, Dropbox, OneDrive, Nextcloud, local); app embeds no cloud SDKs
- Cross-device merge with per-entity conflict resolution — a conflicting row pauses sync and offers keep local / keep remote / keep both, with last-write-wins preselected
- Every conflict and its resolution is recorded in a dedicated Sync Log, viewable in the app; conflicts never go to the application or debug log, which stay reserved for genuine app faults
- Reusable network routes (proxies and SSH jump hosts) sync device-to-device like port-forward rules — full row, last-write-wins, and no secrets to keep Keystore-bound
- End-to-end encrypted sync — a user passphrase is required, there are no server-side keys, and sync data is never readable by the storage provider
- Backup and restore as a portable archive covering everything the app stores — encrypted when the user sets a password, plaintext when they do not; backups made by older app versions must always import into newer versions
- A plaintext backup includes the stored secrets (SSH key passphrases, connection, container host, registry and VNC passwords) in the clear and is reachable only behind an explicit type-to-confirm warning naming that exposure

### Hypervisor management
- Proxmox, XCP-ng (and Xen Orchestra), VMware, QEMU/libvirt (KVM) — list VMs/instances, start, stop, shutdown, reboot, snapshot
- QEMU/libvirt is managed over an SSH transport to the remote host — no libvirt TCP daemon needs to be exposed
- Built-in VNC console client for VM graphical consoles — consoles open as swipeable tabs next to terminal sessions
- SPICE console client for hypervisors that expose SPICE displays
- Reusable hypervisor credential accounts (username/password or OCI API key) shared across hypervisor profiles
- TLS certificate pinning (TOFU) for hypervisor REST APIs when SSL verification is enabled on the profile (off by default to accommodate self-signed hypervisor certs) — a changed certificate is accepted only on explicit user re-approval
- OCI API key authentication (tenancy, user, region, fingerprint, compartment, private key)

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
- A Containers sub-tab alongside Hypervisors and Cloud Accounts under the Infra main tab; adding a host mirrors the hypervisor add flow but authenticates over SSH only — link a saved SSH connection or enter a custom endpoint (address, port, username, auth via password/SSH key/saved identity); the host name is optional, defaulting to the connection name or endpoint hostname
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
- High-contrast mode and large-text mode
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

- `TABSSH_SYNC_V2` sync wire format — AES-256-GCM + Argon2id key derivation;
  Desktop and Web read/write the same encrypted blob so a device can sync
  against any storage provider regardless of which app last wrote to it
- QR pairing payload — CBOR + AES-256-GCM + Argon2id; a QR generated by
  Desktop or Web must decode here unmodified, and vice versa
- Backup archive format — version 3, `{"v":3,"items":[...]}` per entity file;
  a backup made by any sibling must restore cleanly on the others
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

### Trust boundaries
- Remote SSH/telnet hosts, hypervisor and cloud APIs, clipboard contents, QR payloads, imported config/bulk files, and user-supplied sync storage are all untrusted input
- The device keystore and the app's own encrypted storage are the only trusted secret stores
- verifySsl defaults to off on hypervisor profiles — an accepted, documented design decision to accommodate self-signed hypervisor certs; TOFU pinning is the compensating control
- Cleartext HTTP stays permitted in network_security_config.xml — an accepted, documented deviation from the AI.md PART 9 cleartext ban: hypervisor/cloud endpoints are user-configured and may be plain-http consoles on private LANs; blocking cleartext would break those setups

### Permission justifications
- Camera: QR pairing import only; declared optional (app fully works without it)
- Notifications: per-session status entries and connection events
- Foreground service: keeps SSH/mosh sessions alive while backgrounded
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

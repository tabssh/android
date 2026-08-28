## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, and OCI hypervisors, cloud instances, and container hosts (Docker, Incus, Podman, LXC/LXD) — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

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

### Applicability

database: yes
network: yes
notifications: yes
background_work: yes
backup_sync: yes
media: no

### Toolchain

# The template default casjaysdev/android:latest does not exist yet —
# do not switch to it. This repo's pre-baked CI image (Android SDK +
# project Gradle dependency cache) is the build image, shared by the
# GitHub workflows and local Docker builds for reproducibility
build_image: ghcr.io/tabssh/android:build
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
- Desktop-terminal scrollbar (konsole/xfce4-terminal style) — a persistent right-edge track and draggable thumb that scrolls the terminal's own scrollback; the thumb fills the track when there is nothing to scroll back through; swipe up/down independently acts as a smooth-scrolling mouse wheel (default 3 lines per line of finger travel) for the app/shell to handle; the bar must never interfere with left/right tab-switch swipes
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
- QR pairing for importing connection profiles from a desktop companion — the QR payload must be encrypted and useless without the user's passphrase

### Distribution constraints
- F-Droid compatible: no proprietary libraries, no analytics, reproducible build variant
- Zero telemetry by default; opt-in only
- Works fully offline (no cloud account required to use the app)
- No feature gating — all functionality available to all users

### Trust boundaries
- Remote SSH/telnet hosts, hypervisor and cloud APIs, clipboard contents, QR payloads, imported config/bulk files, and user-supplied sync storage are all untrusted input
- The device keystore and the app's own encrypted storage are the only trusted secret stores
- verifySsl defaults to off on hypervisor profiles — an accepted, documented design decision to accommodate self-signed hypervisor certs; TOFU pinning is the compensating control
- Cleartext HTTP stays permitted in network_security_config.xml — an accepted, documented deviation from the AI.md PART 9 cleartext ban: hypervisor/cloud endpoints are user-configured and may be plain-http consoles on private LANs; blocking cleartext would break those setups

### Permission justifications
- Camera: QR pairing import only; declared optional (app fully works without it)
- Notifications: per-session status entries and connection events
- Foreground service: keeps SSH/mosh sessions alive while backgrounded
- Foreground service (media projection): required by API 34+ to run
  `SessionRecordingService`, which captures on-screen content to mp4 while
  the user has an active session video recording (TODO.AI.md item 53);
  system consent (`MediaProjectionManager.createScreenCaptureIntent()`) is
  shown by the Activity before the service ever starts, per Android's own
  MediaProjection contract — the permission only lets an already-consented
  capture keep running as a foreground service
- Network: the app's core purpose; no network use on first launch is still required

### What the app must never do
- Store raw passwords or PEM keys in the Room database
- Embed cloud provider SDKs or require a cloud account for sync
- Include analytics, crash reporting SDKs, or tracking pixels without explicit user opt-in
- Require network access on first launch

### Release

- Keystore escrow: no production keystore file exists in the repo or on any dev machine (AI.md PART 13). It is escrowed as the `KEYSTORE_BASE64` + `KEYSTORE_PASSWORD` (and optional `KEY_PASSWORD`) repo secrets under Settings > Secrets and Variables > Actions; every channel (`development.yml`, `beta.yml`, `release.yml`) hard-fails with an actionable `::error::` if either required secret is missing, with no ephemeral/generated fallback keystore ever produced.
- Release cadence: `stable` (`release.yml`) and `beta` (`beta.yml`) are on-demand, triggered by pushing a `vX.Y.Z` / `*beta` tag; `development` (`development.yml`) runs daily on a schedule plus on every push to `main`, with its rolling `development` tag/release deleted and recreated each run.
- No ACRA-style crash-reporting endpoint is configured — crash reporting stays the AI.md PART 2 default (on-device log + user-triggered export) per the "no analytics/crash-reporting SDK without opt-in" constraint above.

### Accepted design decisions

- Container engine unix-socket forwarding uses the SSH library's native
  `direct-streamlocal@openssh.com` support (no custom protocol code) —
  verified working end-to-end against a real sshd on 2026-08-07. The server
  must allow both TCP and stream-local forwarding; the server reports a
  denial only generically, so the app maps that failure to an actionable
  sshd-configuration hint rather than showing a raw error.

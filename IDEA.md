## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, and OCI hypervisors, cloud instances, and Docker hosts — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

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
- A plaintext backup includes the stored secrets (SSH key passphrases, connection, Docker, registry and VNC passwords) in the clear and is reachable only behind an explicit type-to-confirm warning naming that exposure

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

### Docker host management
- Portainer-class management of Docker hosts reached over the user's existing SSH connections — no host agent, no exposed Docker API port required
- A Docker Hosts section in the Hypervisors tab; a host links to a saved SSH connection or carries its own custom SSH endpoint (address, port, username, auth via password/SSH key/saved identity) — the host name is optional, defaulting to the connection name or endpoint hostname
- Docker is a separate domain like hypervisors: custom-endpoint sessions and container exec tabs never appear in the active-sessions list, recents, connection stats, or session restore; custom-endpoint passwords are Keystore-only, never a database column
- Containers: list, inspect, start/stop/restart/pause/kill/rename/remove, live-follow logs, live stats; enter any running container as a normal terminal tab via docker exec (shell auto-detected)
- Images (pull with progress, remove, prune), volumes, networks, and an engine dashboard with disk usage
- Compose stacks are paste-first: paste a complete compose file and it is saved to a per-host configurable remote directory (default `/srv/$USER/tabssh/docker/compose/{name}`) and run; up/down/pull/restart with per-service status; remote directories are created on demand
- Single-container run configs: a form-based `run.yml` (mirroring `docker run` flags) per container under a second configurable remote directory (default `/srv/$USER/tabssh/docker/docker/{name}`), with a raw-YAML advanced toggle
- Hybrid transport: Docker Engine API over an SSH forward of the host's unix socket when the server permits it, automatic fallback to the docker CLI over SSH exec — every feature works on CLI-only hosts, with documented degradation (stats become polled)
- Socket forwarding requires sshd `AllowTcpForwarding yes` and `AllowStreamLocalForwarding yes`; a denial must produce an actionable remediation hint, never a silent permanent downgrade — a manual "retest transport" action exists
- Docker sessions are pooled per host: one SSH connection per host opened on demand, locked per host (parallel across hosts), LRU-capped at 16 open sessions, disconnected after 10 minutes idle, and dead sessions evicted with their relays closed — monitoring-only SSH connections are released with the session, user terminal connections never are
- App-driven, watchtower-style updates: periodic registry digest checks flag stale containers (notification + in-app badge); unattended pull+recreate is opt-in per policy and must preserve the container's configuration, with automatic rollback if the replacement fails
- Update checks run twice daily by default, at most 2 hosts concurrently; each Docker host can disable checks or set its own interval in hours (blank = default), stored on the host row via an additive migration
- Docker Hub and private registries (Basic/Bearer) supported; registry credentials are Keystore-only, never a database column

### Accessibility and UI
- TalkBack support with content descriptions on all interactive elements
- High-contrast mode and large-text mode
- Full keyboard navigation
- 23 built-in terminal themes; user-created custom themes; dark/light/auto per OS preference
- Mobile-responsive; supports both phone and tablet layouts

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
- Network: the app's core purpose; no network use on first launch is still required

### What the app must never do
- Store raw passwords or PEM keys in the Room database
- Embed cloud provider SDKs or require a cloud account for sync
- Include analytics, crash reporting SDKs, or tracking pixels without explicit user opt-in
- Require network access on first launch

### Accepted design decisions

- Docker unix-socket forwarding uses the SSH library's native
  `direct-streamlocal@openssh.com` support (no custom protocol code) —
  verified working end-to-end against a real sshd on 2026-08-07. The server
  must allow both TCP and stream-local forwarding; the server reports a
  denial only generically, so the app maps that failure to an actionable
  sshd-configuration hint rather than showing a raw error.

## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, and OCI hypervisors — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

## Project variables

project_name: tabssh
project_org: tabssh
internal_name: tabssh
internal_org: tabssh
app_id: io.github.tabssh
min_sdk: 24
target_sdk: 34
compile_sdk: 35
language: Kotlin
license: MIT
repository: https://github.com/tabssh/android

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
- Session recording and replay (transcript) — transcripts stored on-device as plain-text `.log` files in the app's external `Transcripts/` directory
- `~/.ssh/config` import
- Bulk import: CSV, JSON, PuTTY .reg, Terraform `.tf` config files — each format maps its fields onto connection profiles (host, port, user, auth, group)
- Custom on-screen keyboard with configurable rows and gesture bindings
- Find-in-scrollback
- Snippet library with `{var}` placeholder substitution — placeholders are filled through a prompt UI at run time
- Macro library — record raw byte sequences and replay them into any session
- Mosh support — sessions survive IP changes/roaming via mosh's native UDP protocol (intrinsic roaming; no app-level reconnect layer required)
- Telnet connections alongside SSH (plain-text legacy protocol, clearly separated from SSH profiles)
- X11 forwarding to a local Android X server (XServer-XSDL / Termux:X11)
- Terminal multiplexer integration (tmux / screen / zellij) — auto-attach and create-new modes, with auto-detection heuristics (session listing plus a process-scan fallback) and a manual override picker
- Post-connect script execution
- Per-connection color tags, font size overrides, custom themes
- URL detection on long-press
- Performance dashboard with configurable monitor slots per host and metric graphs

### Security requirements
- All passwords and private key passphrases must never be stored in plaintext or in the database
- Credential storage with tiered access levels: never / session-only / encrypted / biometric — encrypted tiers are backed by hardware-backed device key storage
- Biometric unlock for stored passwords with configurable TTL
- App-lock PIN with a failed-attempt lockout — the PIN is stored only as a salted one-way hash, never plaintext
- Screenshot capture prevention (configurable); always enforced on PIN and auth screens
- SSH host key verification on first connect (TOFU) with fingerprint display
- Clipboard auto-clear for sensitive pastes
- Audit log of SSH commands and session events — stays on-device, with user-configurable size (MB) and age (days) retention caps and separate command/output capture toggles

### Sync and backup
- Cross-device sync via SAF — user supplies any DocumentsProvider (Google Drive, Dropbox, OneDrive, Nextcloud, local); app embeds no cloud SDKs
- Cross-device merge with per-entity conflict resolution
- End-to-end encrypted sync — a user passphrase is required and there are no server-side keys; sync archives are encrypted with a passphrase-derived key
- Backup and restore as a portable encrypted archive — versioned format with in-archive version metadata so older backups migrate forward on import

### Hypervisor management
- Proxmox, XCP-ng (and Xen Orchestra), VMware, QEMU/libvirt (KVM) — list VMs/instances, start, stop, shutdown, reboot, snapshot
- QEMU/libvirt is managed over an SSH transport to the remote host — no libvirt TCP daemon needs to be exposed
- Built-in VNC console client for VM graphical consoles — consoles open as swipeable tabs next to terminal sessions
- SPICE console client for hypervisors that expose SPICE displays
- Reusable hypervisor credential accounts (username/password or OCI API key) shared across hypervisor profiles
- TLS certificate pinning (TOFU) for hypervisor REST APIs when SSL verification is enabled on the profile (off by default to accommodate self-signed hypervisor certs) — pins are stored per profile and rotated only on explicit user re-approval
- OCI API key authentication (tenancy, user, region, fingerprint, compartment, private key)

### Cloud provider management
- Manage SSH-accessible instances across DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, Google Cloud Compute, Azure VMs, and Oracle Cloud (OCI)
- All eight providers expose the same feature surface — list instances, live state, power control, SSH connect — no provider gets a reduced experience
- Live instance state (running / stopped / transitioning) with start/stop control
- Cloud account credentials must never be stored in the database
- No vendor SDKs embedded — all providers accessed via their REST APIs

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
- QR pairing for importing connection profiles from a desktop companion — the QR payload is encrypted with a passphrase-derived key envelope

### Distribution constraints
- F-Droid compatible: no proprietary libraries, no analytics, reproducible build variant
- Zero telemetry by default; opt-in only
- Works fully offline (no cloud account required to use the app)
- No feature gating — all functionality available to all users

### What the app must never do
- Store raw passwords or PEM keys in the Room database
- Embed cloud provider SDKs or require a cloud account for sync
- Include analytics, crash reporting SDKs, or tracking pixels without explicit user opt-in
- Require network access on first launch

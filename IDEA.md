## Project description

TabSSH is an Android SSH client that brings browser-style tabbed sessions to the terminal. Users manage multiple concurrent SSH connections as swipe-able tabs, browse remote filesystems over SFTP, manage SSH keys and reusable credential identities, and optionally control Proxmox, XCP-ng, VMware, and OCI hypervisors — all from a single app. Sync across devices uses Android's Storage Access Framework so users supply their own cloud storage (Drive, Dropbox, Nextcloud, local, etc.) with no cloud accounts required by the app itself.

## Project variables

project_name: tabssh
project_org: tabssh
internal_name: tabssh
internal_org: tabssh
app_id: io.github.tabssh
min_sdk: 21
target_sdk: 34
compile_sdk: 34
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
- Session recording and replay (transcript)
- `~/.ssh/config` import
- Bulk import: CSV, JSON, PuTTY .reg, Terraform state
- Custom on-screen keyboard with configurable rows and gesture bindings
- Find-in-scrollback
- Snippet library with `{var}` placeholder substitution
- Macro recording (raw byte sequences)
- Mosh support
- Terminal multiplexer integration (tmux / screen / zellij) — auto-attach and create-new modes
- Post-connect script execution
- Per-connection color tags, font size overrides, custom themes
- URL detection on long-press

### Security requirements
- All passwords and private key passphrases must never be stored in plaintext or in the database
- Credential storage with tiered access levels: never / session-only / encrypted / biometric
- Biometric unlock for stored passwords with configurable TTL
- App-lock PIN with a failed-attempt lockout
- Screenshot capture prevention (configurable); always enforced on PIN and auth screens
- SSH host key verification on first connect (TOFU) with fingerprint display
- Clipboard auto-clear for sensitive pastes
- Audit log of SSH commands and session events

### Sync and backup
- Cross-device sync via SAF — user supplies any DocumentsProvider (Google Drive, Dropbox, OneDrive, Nextcloud, local); app embeds no cloud SDKs
- Cross-device merge with per-entity conflict resolution
- End-to-end encrypted sync — passphrase required, no server-side keys
- Backup and restore as a portable encrypted archive

### Hypervisor management
- Proxmox, XCP-ng (and Xen Orchestra), VMware, QEMU/libvirt (KVM) — list VMs/instances, start, stop, shutdown, reboot, snapshot
- Reusable hypervisor credential accounts (username/password or OCI API key) shared across hypervisor profiles
- TLS certificate pinning (TOFU) for hypervisor REST APIs
- OCI API key authentication (tenancy, user, region, fingerprint, compartment, private key)

### Cloud provider management
- Manage SSH-accessible instances across DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, Google Cloud Compute, Azure VMs, and Oracle Cloud (OCI)
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
- QR pairing for importing connection profiles from a desktop companion

### Distribution constraints
- F-Droid compatible: no proprietary libraries, no analytics, reproducible build variant
- Zero telemetry by default; opt-in only
- Works fully offline (no cloud account required to use the app)
- No feature gating — all functionality available to all users

### Build toolchain
- The project maintains its own build image `ghcr.io/tabssh/android:build`, built from `docker/Dockerfile.build` and refreshed monthly by `build-toolchain.yml`. This is the declared build image for the project.
- The image bakes the full Android toolchain — SDK, platform-tools, build-tools, CMake 3.22.1, and the pinned NDK 26.1.10909125 — plus the GitHub CLI and pre-warmed Gradle caches, so CI and local `make` never provision or lazily download the toolchain per run.
- All CI (`ci.yml`, `dev-builds.yml`, `release.yml`) and the Makefile build and test inside this image; no toolchain is installed inline in a workflow step.
- The generic `casjaysdev/android` maintained image does not exist yet; until it does, this project-declared image is the toolchain. If that image is later published, the build image may be rebased `FROM casjaysdev/android` (extend, never replace).

### What the app must never do
- Store raw passwords or PEM keys in the Room database
- Embed cloud provider SDKs or require a cloud account for sync
- Include analytics, crash reporting SDKs, or tracking pixels without explicit user opt-in
- Require network access on first launch

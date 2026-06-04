# 📱 TabSSH — Modern SSH Client for Android

<p align="center">
  <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform"></a>
  <a href="https://github.com/tabssh/android/blob/main/LICENSE.md"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License"></a>
  <a href="https://github.com/tabssh/android/releases/latest"><img src="https://img.shields.io/github/v/release/tabssh/android?label=Version" alt="Version"></a>
  <a href="https://developer.android.com/tools/releases/platforms"><img src="https://img.shields.io/badge/Min%20SDK-21-brightgreen.svg" alt="Min SDK"></a>
  <a href="https://github.com/tabssh/android/actions/workflows/ci.yml"><img src="https://github.com/tabssh/android/actions/workflows/ci.yml/badge.svg" alt="Build Status"></a>
  <a href="https://github.com/tabssh/android/releases"><img src="https://img.shields.io/github/downloads/tabssh/android/total?label=Downloads" alt="Downloads"></a>
</p>

<p align="center">
  A beautiful, modern, open-source SSH client for Android with true browser-style tabs,<br>
  enterprise security, hypervisor management, and cloud provider integration.
</p>

---

## ✨ Features

### Core SSH

- 📑 **Browser-Style Tabs** — Multiple concurrent SSH sessions; each tab gets its own `ChannelShell` on a shared JSch session
- 🔐 **Auth Methods** — Password, public key (RSA/ECDSA/Ed25519/DSA), keyboard-interactive
- 🔑 **Universal Key Support** — OpenSSH, PEM, PKCS#8, PuTTY; import or generate in-app
- 🖥️ **Full Terminal Emulation** — Termux TerminalEmulator (VT100/ANSI, 256 colors, vim/htop/tmux fully functional)
- 📁 **Integrated SFTP** — File manager with upload/download progress, remote editor, chmod
- 🔌 **Port Forwarding** — Local, remote, and dynamic (SOCKS5) forwarding
- 🖼️ **X11 Forwarding** — Run graphical apps remotely via Termux:X11 or XServer XSDL
- 🌐 **SSH Config Import** — `~/.ssh/config` with RemoteCommand, SendEnv, RequestTTY, ProxyJump
- ❤️ **Always-on Keepalive** — 60s serverAliveInterval; idle sessions survive carrier NAT and Wi-Fi sleep
- 🚀 **Tmux/Screen auto-launch** — Per-profile auto-attach/create for tmux/screen/zellij + postConnectScript
- 🌐 **IPv4 / IPv6 selection** — Per-host auto/ipv4/ipv6 with fallback toast
- 🔗 **ProxyJump** — Multi-hop connections through bastion hosts
- ⏺️ **Session Recording** — Capture and replay raw terminal sessions

### Security

- 🔒 **Hardware-Backed Encryption** — Android Keystore integration (hardware security module when available)
- 👆 **Biometric Authentication** — Fingerprint and face unlock
- 🔐 **No Plaintext Storage** — All credentials encrypted with AES-256-GCM
- 🛡️ **Host Key Verification** — TOFU with SHA256 fingerprints and MITM detection
- 🚫 **Screenshot Protection** — Prevents sensitive data from leaking to recents or screenshots
- 🔐 **Auto-Lock** — Configurable timeout; session credentials zeroed on background
- 📋 **Clipboard Auto-Clear** — Configurable TTL for pasted passwords

### UI/UX

- 🎨 **Material Design 3** — Google's latest design system throughout
- 🌈 **23 Built-in Themes** — Dracula, Solarized (Light/Dark), Nord, Monokai, One Dark, Tokyo Night, Gruvbox, and 16 more
- 🎨 **Custom Theme Editor** — Full GUI in Settings → Appearance; import/export JSON with WCAG 2.1 contrast validation
- ⌨️ **Custom SSH Keyboard** — 1–5 row on-screen bar optimized for vim/tmux/coding; drag-to-reorder keys within rows
- ⌨️ **Hardware Keyboard** — AltGr distinct from Alt; xterm modifier-encoded arrows (Ctrl-Right = `ESC[1;5C`), HOME/END/PG family
- 🗂️ **Tabs at Top** — TabBar flush at top; no toolbar chrome; navigation drawer via left-edge swipe
- 👆 **Edge-Swipe Tabs** — Single-finger fling within 24dp of left/right edge switches tabs
- 🔁 **Active Sessions Strip** — Running tabs with live OSC 0/2 terminal title and connection-state dot
- 📋 **Copyable Error Dialogs** — Every error dialog has a Copy button for clean bug reports
- 🔍 **Find in Scrollback** — Floating search bar with prev/next, match counter, amber highlights, Ctrl+Shift+F shortcut

### Accessibility

- ♿ **TalkBack** — Full screen reader support
- 🔆 **High Contrast** — Enhanced visibility for low vision users
- 📏 **Adjustable Fonts** — 8–32pt, 8 monospace families (Cascadia Code, Fira Code, JetBrains Mono, and more)
- ⌨️ **Keyboard Navigation** — Fully keyboard-accessible
- 🌐 **Translations** — English, Spanish, French, German

### Advanced

- 📡 **Background Monitoring** — Periodic TCP reachability probes; down/recovery notifications; CPU/memory/disk threshold alerts via SSH; configurable cooldown (15 min–12 h)
- 📱 **Mosh Protocol** — Mobile shell for unstable connections with roaming support
- 💾 **Backup & Restore** — Export/import all settings as encrypted ZIP
- ☁️ **Cloud Sync** — Storage Access Framework (Google Drive, Dropbox, OneDrive, Nextcloud, local — no Google services dependency); AES-256-GCM + PBKDF2 + 3-way merge with conflict UI
- 🏠 **Home Screen Widgets** — Quick-connect from launcher
- 📂 **Connection Groups** — Folders with expand/collapse; group badges in search
- 🔍 **Search & Sort** — Real-time search, 8 sort options
- 📊 **Connection Statistics** — Last connected, usage count, per-host logs
- 📝 **Snippets** — Quick command library with `{?name:default|hint}` variable placeholders
- ⏺️ **Macros** — Capture and replay raw byte sequences (escape codes, modifier-composed Ctrl/Alt)
- 🎮 **Automation** — Tasker integration, intent-based actions, deep links
- 📊 **Multi-Host Dashboard** — Side-by-side CPU/memory/disk metrics across hosts; dashboard groups independent from connection groups

### Hypervisor Management

Manage virtual machines directly from TabSSH — no separate app required.

- **Proxmox VE** — Full REST API; list/start/stop/shutdown/reboot/reset VMs and LXC containers; serial console (termproxy) with automatic VNC fallback; `last_connected` tracking
- **XCP-ng / Xen Orchestra** — XML-RPC direct host or Xen Orchestra REST + WebSocket; real-time VM state; snapshot/backup operations; pool/host info; auto-detects XO vs. direct
- **VMware vSphere / ESXi** — REST API; auto-detects ESXi vs. vCenter; full VM power management
- **QEMU/libvirt (KVM)** — SSH tunnel to host; `virsh list` domain enumeration; start/shutdown/reboot/hard-reset; VNC console tunnelled over SSH (no VNC port exposure required); SSH fallback via ProxyJump when VNC not configured

> **OCI Compute** has moved to **Cloud Accounts** (see below) for a unified multi-cloud experience.

### Cloud Provider Management ☁️

Manage instances across 8 cloud providers from a single Cloud Accounts screen.

- **DigitalOcean** · **Hetzner** · **Linode** · **Vultr** · **AWS EC2** · **Google Cloud Compute** · **Azure VMs** · **Oracle Cloud (OCI)**
- Live instance state (running / stopped / transitioning) with color-coded status dots
- **Start / Stop** power toggle per instance
- **Restart** (graceful) and **Force Restart** (hard power-cycle) for running instances
- **Connect** shortcut — launches SSH session to running instances with a public IP
- Accounts editable after creation; OCI credentials importable from `~/.oci/config` via file picker

### VNC Console *(alpha)* 🖥️

Pixel-perfect graphical console access to VMs — no separate VNC viewer required.

- RFB protocol client with Tight, ZRLE, CopyRect, Hextile, CoRRE, RRE encoding support
- ServerFence / ClientFence handshake (required for Proxmox vncproxy)
- ExtendedDesktopSize (SetDesktopSize) resize negotiation
- VNC password authentication with correct DES challenge-response (RFC 6143 §7.2.2)
- Proxmox WebSocket VNC via `vncproxy` API (`websocket=1`) — binary WebSocket frames, no separate port mapping required
- X11 keysym translation from Android `KeyEvent` — all modifier keys (Ctrl, Alt, Shift, Super), F1–F12, arrow cluster, Home/End/PgUp/PgDn
- Custom SSH keyboard bar and system keyboard both work inside VNC sessions
- Tunnelled over SSH for QEMU/libvirt — no VNC port needs to be exposed to the network

---

## 📦 Installation

### GitHub Releases

Download from [Releases](https://github.com/tabssh/android/releases):

| APK | Use case |
|---|---|
| `tabssh-universal.apk` | **Recommended** — all devices |
| `tabssh-arm64-v8a.apk` | Modern 64-bit ARM |
| `tabssh-armeabi-v7a.apk` | Older 32-bit ARM |
| `tabssh-x86_64.apk` | x86 64-bit |
| `tabssh-x86.apk` | x86 32-bit |

1. Download `tabssh-universal.apk`
2. Enable **Install from Unknown Sources** in Android Settings → Security
3. Open the APK and tap **Install**
4. Launch TabSSH, grant Storage + Notification permissions
5. Add a connection and connect

### F-Droid

Submission metadata is prepared in `fdroid-submission/`. Listing pending review.

### Requirements

- **Minimum:** Android 5.0 (API 21)
- **Recommended:** Android 8.0+ (API 26) for best performance
- **Storage:** 50 MB free
- **RAM:** 512 MB minimum

---

## 🚀 Quick Start

```
Add connection → Tap "+" → enter host/port/username → save
Connect        → Tap profile → accept host key → connected
New tab        → Tap "+" in TabBar, or Ctrl+T
SFTP           → Tap the folder icon in an active session
VNC console    → Hypervisors → tap VM → tap VNC/Console
Cloud          → Cloud Accounts → tap account → view live instances
```

---

## 🛠️ Development

### Prerequisites

**Docker (recommended)**
- Docker 20.10+ and Docker Compose 2.0+

**Local build**
- Android SDK 34, JDK 17 (Temurin/OpenJDK), Gradle 8.11.1+

### Build Commands

```bash
make build      # Debug APKs → ./binaries/   (~5 min, Docker-cached)
make check      # Compile-only check         (~2 min, Docker-cached)
make install    # Install to connected device
make logs       # Tail logcat
make test       # Run UI tests
make clean      # Remove build artifacts
```

Production releases are built by the `release.yml` workflow on tag push (`v*`).

### Project Structure

```
android/
├── app/src/main/java/io/github/tabssh/
│   ├── cloud/          # Cloud provider clients + CloudInstanceState
│   ├── crypto/         # AES-GCM, Keystore wrappers, key storage
│   ├── hypervisor/     # Proxmox, XCP-ng, VMware, libvirt, OCI API clients
│   ├── ssh/            # SSHConnection, SSHSessionManager, port forwarding, X11
│   ├── sftp/           # SFTP browser and file transfer
│   ├── terminal/       # TermuxBridge, TerminalView, VNC RFB client
│   ├── storage/        # Room DB (v36, 35 migrations), DAOs, entities
│   ├── sync/           # SAF-based 3-way merge sync
│   ├── backup/         # Encrypted ZIP backup/restore
│   └── ui/             # Activities, Fragments, Adapters, ViewModels
├── app/src/main/res/   # Layouts, strings, themes, drawables
├── app/schemas/        # Room migration JSON schemas
├── .github/workflows/  # CI/CD (android-ci, release)
├── docker/             # Dockerfile.build (toolchain image)
├── scripts/            # Build and automation scripts
├── fdroid-submission/  # F-Droid metadata
├── Makefile
└── release.txt         # Version pin (0.0.9)
```

---

## 🔐 Security

**Report vulnerabilities privately:** `git-admin+security@casjaysdev.pro` — we respond within 48 hours.

- AES-256-GCM for all stored credentials
- Android Keystore (hardware-backed when available)
- Host key TOFU with SHA256 fingerprints
- Zero telemetry, zero analytics, zero external network requests except SSH/cloud connections
- Session credentials zeroed from memory when the app backgrounds
- OWASP Dependency-Check in CI

---

## 🎨 Themes

23 built-in themes: **Dracula**, **Solarized Light/Dark**, **Nord**, **Monokai**, **One Dark**, **Tokyo Night**, **Gruvbox**, **High Contrast**, and 14 more.

**Custom themes:** Settings → General → Appearance → Theme Editor. Import/export JSON:

```json
{
  "name": "My Theme",
  "colors": {
    "background": "#1e1e1e",
    "foreground": "#d4d4d4",
    "cursor": "#00ff00"
  }
}
```

---

## 📊 Stats

| Metric | Value |
|---|---|
| Kotlin files | 258 |
| Lines of code | ~65,000 |
| Activities | 31 |
| Fragments | 7 |
| Services | 1 (`SSHConnectionService`) |
| Built-in themes | 23 |
| Translations | 4 (EN/ES/FR/DE) |
| APK variants | 5 (universal + 4 arch-specific) |
| Hypervisor backends | 4 (Proxmox, XCP-ng, VMware, QEMU/libvirt) |
| Cloud providers | 8 (DO, Hetzner, Linode, Vultr, AWS, GCP, Azure, OCI) |
| Room DB version | 36 (35 forward migrations from v1) |
| Trackers | 0 |

---

## 🤝 Contributing

See [CONTRIBUTING.md](.github/CONTRIBUTING.md) for guidelines.

```bash
git checkout -b feature/my-feature
# make changes
make check          # must pass
./gradlew test      # must pass
# open pull request
```

---

## 📜 License

MIT — see [LICENSE.md](LICENSE.md).

```
Copyright (c) 2024 TabSSH Contributors
```

---

## 💬 Support

- 🐛 **Bugs / Features:** [GitHub Issues](https://github.com/tabssh/android/issues)
- 💬 **Discussion:** [GitHub Discussions](https://github.com/tabssh/android/discussions)
- 📧 **Email:** git-admin+support@casjaysdev.pro

---

## 🙏 Acknowledgments

- **[JSch (mwiede fork)](https://github.com/mwiede/jsch)** — modern SSH2 (chacha20-poly1305, aes256-gcm, curve25519, ed25519)
- **[Termux Terminal Emulator](https://github.com/termux/termux-app)** — VT100/ANSI terminal core
- **[Material Design Components](https://material.io/)** — UI framework
- **[BouncyCastle](https://www.bouncycastle.org/)** — cryptography
- The ConnectBot and JuiceSSH teams for pioneering Android SSH

---

<p align="center">
  <b>Made with ❤️ for the open-source community</b><br>
  <sub>Empowering secure remote access for everyone</sub>
</p>

<p align="center">
  <a href="https://github.com/tabssh/android/issues">Report Bug</a> ·
  <a href="https://github.com/tabssh/android/issues">Request Feature</a> ·
  <a href=".github/CONTRIBUTING.md">Contribute</a>
</p>

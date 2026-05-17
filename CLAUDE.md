# TabSSH Android

> **Read `AI.md` (THE HOW) and `IDEA.md` (THE WHAT) before writing any code.**
> `SPEC.md` contains project-specific rule overrides — read it first when a rule seems ambiguous.

---

## How to navigate AI.md

| You need to know about… | Read AI.md section |
|---|---|
| App identity, design pillars, SDK versions | §1 |
| High-level architecture diagram, threading model, state propagation | §2 |
| Build types, APK variants, ABI→arch mapping, all dependencies | §3 |
| Activities, fragments, services, canonical user flows | §4 |
| SSH connection lifecycle, jump hosts, auth, keepalive, per-tab channels | §5.1 |
| Host key verification (TOFU dialog, trust levels) | §5.2 |
| Port forwarding and proxies | §5.3 |
| `~/.ssh/config` import | §5.4 |
| Bulk import (CSV / JSON / PuTTY .reg / Terraform) | §5.4.1 |
| SFTP, remote file editor, chmod, SCP fallback | §5.5 |
| Terminal emulator (`TermuxBridge`, `TerminalView`) | §6.1–6.2 |
| Tab management (`TabManager`, `SSHTab`), keyboard shortcuts | §6.3 |
| On-screen keyboard, gesture bindings, find-in-scrollback | §6.4 |
| Session recording and replay | §6.5 |
| SSH key types, parsing, generation, fingerprints | §7.1–7.2 |
| Key storage (Android Keystore, AES-GCM) | §7.3 |
| Password storage levels, biometric unlock, TTL | §7.4 |
| Screenshot protection, clipboard auto-clear, password lifecycle | §7.5 |
| Room database version, full migration chain | §8.1–8.4 |
| All 17 entities and their notable fields | §8.2 |
| Preference keys and defaults by category | §8.6 |
| SAF sync wire format, encryption, 3-way merge, conflict resolution | §9 |
| Sync coverage matrix (what syncs, what doesn't, and why) | §9.4 |
| Backup/restore ZIP format | §10 |
| Proxmox / XCP-ng / Xen Orchestra / VMware / OCI / libvirt-QEMU APIs | §11 |
| Hypervisor console WebSocket framing | §11.6 |
| Settings XML files and hosting fragments | §12.1 |
| Themes (23 built-ins, `Theme.kt` fields, contrast validation) | §12.2 |
| Accessibility (TalkBack, high-contrast, keyboard nav, motor) | §12.3 |
| Notification channels | §13.1 |
| Build targets (`make` commands), Docker setup | §14.1–14.2 |
| CI workflows (android-ci, dev builds, release) | §14.3 |
| ProGuard / R8 keep rules | §14.6 |
| Full package map (`io.github.tabssh.*`) | §15 |
| Known stubs and unimplemented features | §16 |
| Rules for AI agents editing this codebase | §17 |
| QR pairing wire format, encryption, mobile implementation status | §18 |

---

## Quick commands

| Goal | Command |
|---|---|
| Compile-only check | `make check` (~2 min cached) |
| Debug APKs → `./binaries/` | `make build` (~5 min cached) |
| Install on device | `make install` |
| Tail logcat | `make logs` |
| Run UI tests | `make test` |
| Clean artifacts | `make clean` |

Production releases are produced by the `release.yml` GitHub Actions workflow on tag push (`v*`) — there is no local `make release` target.

## File locations

| Artifact | Path |
|---|---|
| Debug APKs | `./binaries/` |
| Release APKs | `./releases/` |
| All temp files | `/tmp/tabssh-android/` |
| Room schemas | `app/schemas/` |

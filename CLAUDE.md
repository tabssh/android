# TabSSH Android — Agent Rules

> **Read `AI.md` before writing any code.** It is the single source of architectural truth for this project. This file contains operating rules and pointers into `AI.md`. `TODO.AI.md` tracks all open/planned work — use it for every session that touches 2 or more tasks.

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
| Room database version, full migration chain (v17→v29) | §8.1–8.4 |
| All 15 entities and their notable fields | §8.2 |
| Preference keys and defaults by category | §8.6 |
| SAF sync wire format, encryption, 3-way merge, conflict resolution | §9 |
| Sync coverage matrix (what syncs, what doesn't, and why) | §9.4 |
| Backup/restore ZIP format | §10 |
| Proxmox / XCP-ng / Xen Orchestra / VMware / OCI APIs | §11 |
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

## Mandatory rules

**TODO.AI.md** — open it at the start of any session touching 2+ tasks. Update status as you go. Every shipped feature and every bug fix must be logged there. Do not let it go stale.

**Commits** — `git commit` and `git push` are denied. Use `gitcommit --dir {project_root} all`. Steps:
1. `git status --porcelain` + `git diff --stat` — verify scope
2. Write `.git/COMMIT_MESS`; re-read before running
3. `gitcommit --dir /root/Projects/github/tabssh/android all`

Format: `{emoji} Title ≤64 chars {emoji}` + blank line + body + `- file: what changed` bullets. No AI attribution. Emoji: 🐛 fix · ✨ feat · 📝 docs · ♻️ refactor · ⚡ perf · ✅ test · 🔒 security · 🗃️ db · 🚀 release · 🔧 chore.

**Green build = commit immediately** — `make check` exit 0 means commit without asking.

**Database changes** — bump `TabSSHDatabase` version, add `Migration` object, update `app/schemas/`, document in `AI.md §8.4`. Never destructive-migrate.

**Sync surface** — new persisted entities that should sync must be added to `SyncDataCollector` / `SyncDataApplier`; update `AI.md §9` sync coverage matrix.

**Secrets** — never commit. Passwords/keys → `SecurePasswordManager`; cloud tokens → Keystore; OCI PEM → `SecurePasswordManager` under `oci_private_key_${id}`.

---

## Quick commands

| Goal | Command |
|---|---|
| Compile-only check | `make check` (~2 min cached) |
| Debug APKs → `./binaries/` | `make build` (~5 min cached) |
| Install on device | `make install` |
| Tail logcat | `make logs` |
| Production release | `make release` |
| Clean artifacts | `make clean` |

## File locations

| Artifact | Path |
|---|---|
| Debug APKs | `./binaries/` |
| Release APKs | `./releases/` |
| All temp files | `/tmp/tabssh-android/` |
| Room schemas | `app/schemas/` |

**Never** create temp files in the project root or `app/build/`.

## Screenshots

Android screenshots are 1080×2400+. Downscale before `Read`:
```bash
python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png
```

## Docker

Do not volume-mount `/opt/android-sdk` — that overlays the baked SDK. Source → `/workspace` bind-mount; `GRADLE_USER_HOME` and AVD state → named compose volumes.

# TabSSH Android — Runbook

**Version:** 0.0.9 (pinned via `release.txt`; bump only with explicit user approval)
**Database:** v29 — full migration chain and entity list in `AI.md §8.4`
**Architecture, features, packages:** see `AI.md` (the authoritative source of truth)
**Tasks / roadmap:** see `TODO.AI.md`; agent state in `.agent/`

---

## Quick commands

| Goal | Command |
|---|---|
| Debug build → `./binaries/` | `make build` |
| Compile-only error check | `make check` |
| Install debug APK on device | `make install` |
| Tail device logs | `make logs` |
| Build Docker image | `make image` |
| Clean build artifacts | `make clean` |
| Production release + GitHub | `make release` |

Docker run bind-mounts the repo to `/workspace`, sets `ANDROID_HOME=/opt/android-sdk` and `GRADLE_USER_HOME=/workspace/.gradle`.

## APK naming

Output: `tabssh-android-{arch}.apk` where `{arch}` is `arm64` / `arm` / `amd64` / `x86` / `universal`. `-dev` suffix on CI prerelease builds. See `AI.md §3.2` for the full ABI→arch mapping.

## Build times (reference)

| Task | Cached | Cold |
|---|---|---|
| `make check` | ~2 min | ~3 min |
| `make build` | ~5 min | ~12 min |
| `make release` | ~10 min | ~15 min |

## Test emulators (`scripts/android-emulator.sh`)

```
scripts/android-emulator.sh [phone|tablet|fold|tv] [small|large]
scripts/android-emulator.sh stop | delete <type> | clean | list
```

One AVD per (type, size); one running emulator at a time. Idempotent — re-running `start` on an already-running AVD is a no-op. Installs missing SDK pieces via `sdkmanager` automatically.

## File locations

| Artifact | Path |
|---|---|
| Debug APKs | `./binaries/` |
| Release APKs | `./releases/` |
| Temp files (all) | `/tmp/tabssh-android/` |
| Room schemas | `app/schemas/` |
| Dev keystore | `keystore.jks` (checked-in dev key only) |

**Never** create temp files in the project root or `app/build/`.

## Policies

**Git commits** — use `/usr/local/bin/gitcommit <command>` (reads `.git/COMMIT_MESS`, signs, commits, pushes in one step). Plain `git commit` and `git push` are both sandbox-denied. Workflow:
1. `git status --porcelain` — verify what changed.
2. Write `.git/COMMIT_MESS` with the correct message.
3. Re-read `.git/COMMIT_MESS` before running the wrapper.
4. `gitcommit all` (or `new` / `improved` / `fixes` / `docs` / `release`).

Commit style: `{emoji} Title ≤64 chars {emoji}` + blank line + body + `- file: what changed` bullets. No `Co-Authored-By` or AI attribution. Emoji map: 🐛 fix · ✨ feat · 📚 docs · ♻️ refactor · ⚡ perf · ✅ test · 🔒 security · 🗃️ db · 🚀 release · 🔧 chore.

**Green build = commit immediately** — for this project, a clean `make check` exit means commit without asking.

**Database changes** — bump `TabSSHDatabase` version, add a `Migration` object, update `app/schemas/`. Document the change in `AI.md §8.4`.

**Sync surface** — any new persisted entity that should sync must be added to `SyncDataCollector` / `SyncDataApplier`; update `AI.md §9` sync coverage matrix.

**Secrets** — never commit passwords, tokens, or private keys. OCI PEM + Proxmox/hypervisor credentials live in `SecurePasswordManager` under namespaced keys, never in the DB. Cloud provider tokens live in the Keystore, not in `cloud_accounts`.

**Screenshot reading** — Android screenshots are 1080×2400+. Downscale before `Read`: `python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png`.

## Docker conventions

| Concern | Location |
|---|---|
| Android SDK, Gradle, JDK | `Dockerfile` rootfs (baked-in, not volume-mounted) |
| `GRADLE_USER_HOME` cache | named compose volume (must match path set in Dockerfile) |
| Source code | compose bind-mount to `/workspace` |
| AVD / emulator state | named compose volume |

Do not volume-mount `/opt/android-sdk` — that overlays the baked SDK with an empty directory.

## Downscale screenshots helper

```bash
python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png
```

## See also

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing
- `TODO.AI.md` — open issues, roadmap items, wave tracking
- `.agent/state.json` — current agent task state
- `README.md` — public-facing overview
- `SPEC.md` (→ `fdroid-submission/SPEC.md`) — historical marketing spec

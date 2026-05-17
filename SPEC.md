# TabSSH Android — Project Rule Overrides

> This file overrides or extends the global `~/.claude/CLAUDE.md` rules specifically for this project. SPEC.md wins over AI.md, which wins over global rules.

---

## Commit workflow

`git commit` and `git push` are denied on this project. Use:

```
gitcommit --dir /root/Projects/github/tabssh/android all
```

Pre-commit sequence:
1. `git status --porcelain` + `git diff --stat` — confirm scope
2. Write `.git/COMMIT_MESS` from the diff output (never from memory)
3. Re-read `COMMIT_MESS` and verify it matches the diff exactly
4. Run `gitcommit --dir /root/Projects/github/tabssh/android all`

Format: `{emoji} Title ≤64 chars {emoji}` + blank line + body + `- file: what changed` bullets per file. No AI attribution.
Emoji: 🐛 fix · ✨ feat · 📝 docs · ♻️ refactor · ⚡ perf · ✅ test · 🔒 security · 🗃️ db · 🚀 release · 🔧 chore.

**Green build = commit immediately** — `make check` exit 0 means commit without asking.

## Database changes

Any change to the Room schema (new column, new table, altered type) requires:
1. Bump `TabSSHDatabase` `version` constant
2. Add a `Migration` object for the new version step
3. Register the migration in the `databaseBuilder` migration chain
4. Export the updated schema: `make check` triggers schema export automatically
5. Document the step in `AI.md §8.4` migration table

**Never destructive-migrate** — SQLite < 3.35 does not support `DROP COLUMN`; drop is forbidden. Rename by adding a new column, migrating data, and leaving the old column in place.

## Sync surface

New persisted entities that should sync across devices must be added to:
- `sync/SyncDataCollector.kt` — collection side
- `sync/SyncDataApplier.kt` — application side

Update `AI.md §9.4` sync coverage matrix when the surface changes.

## Secrets storage rules

| Credential type | Storage location |
|---|---|
| SSH session passwords | `SecurePasswordManager` (Keystore AES-GCM) |
| SSH key passphrases | `SecurePasswordManager` |
| Hypervisor inline passwords | `HypervisorPasswordStore` |
| Reusable hypervisor account passwords | `HypervisorPasswordStore.storeAccountPassword()` |
| OCI PEM private key | `HypervisorPasswordStore.storeOciAccountKey()` — alias `oci_private_key_${accountId}` |
| OCI key passphrase | `HypervisorPasswordStore.storeOciAccountPassphrase()` — alias `oci_passphrase_${accountId}` |
| Cloud provider tokens | `SecurePasswordManager`, key `cloud_token_${accountId}` |

**Never** write any of the above to the Room database. The DB columns for passwords are always empty strings for any row touched by current code.

## Paste service quirks

**MicroBin** (`mb.pste.us` and any self-hosted MicroBin instance) returns HTTP 404 on raw-paste URLs but still delivers the paste body. `curl -f` treats 404 as an error and discards the body. Always fetch MicroBin URLs **without `-f`**:

```bash
curl -qLs "https://mb.pste.us/raw/<id>"   # correct — no -f
curl -q -LSsf "https://mb.pste.us/raw/<id>"  # WRONG — -f discards the body
```

## Temp files

All temporary files for this project go in `/tmp/tabssh-android/`. Never create temp files in the project root or `app/build/`.

(Note: this overrides the global convention of `/tmp/{project_org}/{internal_name}-XXXXXX` — the fixed path is intentional for script compatibility.)

## Screenshots

Android screenshots are 1080×2400+. Downscale before using `Read`:

```bash
python3 /tmp/tabssh-android/resize.py <src>.png /tmp/tabssh-android/screenshots/<name>-small.png
```

## Docker / build

Do not volume-mount `/opt/android-sdk` — that overlays the baked SDK in the build container. The correct bind-mounts are:
- Source tree → `/workspace`
- Gradle cache and AVD state → named compose volumes (defined in `docker/docker-compose.yml`)

The device is on a remote server and cannot be connected via ADB. Logcat is unavailable. All debugging must be done via static code analysis only.

## Threading rules (Android-specific)

- `lifecycleScope.launch {}` defaults to `Dispatchers.Main` — never call Keystore, database, or filesystem operations inside a bare launch without switching to `Dispatchers.IO`
- Room `suspend` DAO functions dispatch to Room's internal IO thread automatically — safe to call from any coroutine
- `SecurePasswordManager.retrievePassword()` is `suspend` but does NOT internally switch dispatchers — always wrap in `withContext(Dispatchers.IO)` before calling
- All `HypervisorPasswordStore` `store*` / `retrieve*` methods use `withContext(Dispatchers.IO)` internally — safe from any coroutine
- SAF launcher callbacks fire on Main; use `val ctx = context ?: return@register` to capture context before launching IO, then guard `withContext(Dispatchers.Main)` blocks with `if (!isAdded) return@withContext`

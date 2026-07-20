# TabSSH Android — Audit Report

> **Read-only audit.** No source files were modified. Every finding below is a
> proposal for a later, separately-authorized change. File:line references are
> against the tree at audit time (versionName `0.9.1`, versionCode `10`).
>
> Scope: security, logic correctness, code quality, performance/optimization,
> build/CI/infra, and spec drift. Findings were produced by parallel review
> passes and reconciled; high-impact items were verified directly against source.

## How to read this

- **CRITICAL** — data loss, credential exposure, or a feature that is broken for
  every user. Fix before next release.
- **HIGH** — security weakness, wrong result under common conditions, or a
  release/reproducibility blocker.
- **MEDIUM** — correctness/robustness gap under specific conditions, or a
  meaningful process/maintenance risk.
- **LOW / NIT** — hygiene, style, or convention violations with no runtime impact.
- **OPT** — optimization opportunity (allocation, I/O, algorithmic).

Two dedicated sections at the end capture **spec drift** (features implemented but
not described in `IDEA.md`, per the user's note that IDEA.md is outdated) and
**AI.md internal drift** (the spec contradicting itself).

---

## Severity summary

| Sev | Count | Headline items |
|-----|-------|----------------|
| CRITICAL | 5 | Keystore password `tabssh123` in source (C1, PARTIAL — migration wired, re-key deferred to maintainer) · reverse-video broken (FIXED) · `getById(Long)` always null (FIXED) · libvirt shell injection (FIXED) · terminal copy ignores scroll offset (FIXED) |
| HIGH | 12 | Backup `includePasswords` toggle → incomplete restores (C6, FIXED) · F-Droid reproducibility (BUILD_DATE) (H1, FIXED) · F-Droid metadata stale (H2, FIXED) · BouncyCastle R8 keep rules (H3, FIXED) · reflection breaks under R8 (H4, FIXED) · AWS SigV4 encoding (H5, FIXED) · sync upload-only · libvirt `StrictHostKeyChecking=no` (H7, FIXED) · lint `checkOnly` (H8, FIXED — + 53 NewApi crashes fixed, minSdk→24) · alpha security-crypto (H9, FIXED — removed as unused) |
| MEDIUM | ~14 | PIN unsalted SHA-256 (H10, FIXED) · `runBlocking` in JSch callback · host-key fingerprint format (H11, FIXED) · CI never compiles · OWASP plugin outdated · orphaned coroutine scopes |
| LOW / NIT | ~10 | LiveData in new code · Gson+kotlinx dual · commented-out code · inline comments · `$(shell pwd)` · stale version strings |
| OPT | ~8 | Per-char Paint allocation · OSC8 per-write parsing · duplicate DAO queries |

---

# CRITICAL

## C1 — Hardcoded keystore password `tabssh123` in source — PARTIAL (migration wired)
**`app/build.gradle:99,101`**
```
storePassword System.getenv("KEYSTORE_PASSWORD") ?: "tabssh123"
keyPassword   System.getenv("KEY_PASSWORD")       ?: "tabssh123"
```
The signing fallback password is committed in plaintext. `release.yml` decodes
`KEYSTORE_BASE64` → `keystore.jks` but the visible signing step did not export
`KEYSTORE_PASSWORD` / `KEY_PASSWORD`, so a CI release build falls back to the
literal `tabssh123`. Confirmed with the maintainer: the repo has only the
`KEYSTORE_BASE64` secret, so `tabssh123` **is** the live production key password.

**Practical severity — reassessed to LOW/MEDIUM:** the password alone is useless
without the `keystore.jks` file, which is held as the `KEYSTORE_BASE64` GitHub
secret (not in source). Exploitation requires *both* the secret keystore and the
password. A full fix (rotate the key to a strong password, re-sign published APKs)
is a maintainer infra decision, deliberately deferred.

**Fix applied (migration groundwork, non-breaking):** `release.yml` and
`dev-builds.yml` now pass `secrets.KEYSTORE_PASSWORD` / `secrets.KEY_PASSWORD`
through as env on their build steps. When those secrets are absent GitHub expands
them to an empty string and Groovy's `?:` (empty = falsy) falls back to the
current password — so nothing breaks today. The moment the maintainer adds a
strong `KEYSTORE_PASSWORD` secret it takes over automatically with **no code
change**. The `dev-builds.yml` ephemeral-keystore generator was updated to use the
same `${KEYSTORE_PW:-tabssh123}` so the generated keystore and gradle never drift.
**Remaining (deferred to maintainer):** rotate the keystore password off
`tabssh123`, upload the re-keyed `KEYSTORE_BASE64`, add the `KEYSTORE_PASSWORD`
secret, then drop the `?: "tabssh123"` fallbacks entirely. Verified against source.

## C2 — Bracketed-paste ESC byte — FALSE POSITIVE (withdrawn)
**`TermuxBridge.kt:738,745`**
Initial reading suggested the `ESC` prefix was missing from the `[200~`/`[201~`
bracketed-paste markers. This was a display artifact — the raw bytes were verified
with `od -c`, which shows `033 [ 2 0 0 ~`: the ESC byte (octal 033) **is** present.
Bracketed paste is emitted correctly. No code change. Lesson: verify control-char
findings against raw bytes (`od`/`hexdump`), not the Read rendering.

<!-- original (withdrawn) finding retained below for the record -->
**`TermuxBridge.kt:738,745` (original text)**
```
if (bracketed) writeString("[200~")   // should be ESC + "[200~"
...            writeString("[201~")    // should be ESC + "[201~"
```
The `ESC` (``) prefix is missing, so in bracketed-paste mode the terminal
receives the literal text `[200~`/`[201~` instead of the control sequence. Every
paste into an app that enables bracketed paste (vim, bash/readline, tmux) is
corrupted with stray `[200~` text.

**Fix (WITHDRAWN — bytes already correct):** ~~prepend `` to both. Verified against source.

## C3 — Reverse-video / SGR color swap computed then discarded — FIXED
**`TerminalRenderer.kt:76-82`**
```
val tempColor = color      // saved for the fg/bg swap...
... // tempColor is never read again; swap never applied
```
The reverse-video path computes the swapped color into `tempColor` but never
assigns it back, so `ESC[7m` (reverse video) and selection highlighting render
with the wrong colors. Affects any TUI that relies on reverse video (menus,
selected rows, cursor highlight).

**Fix (DONE):** `TerminalRenderer.kt` now resolves effective fg/bg from the
16-colour palette, swaps them when `char.reverse` is set, and draws the cell
background whenever it differs from the base background — so reversed cells
(including reversed spaces) render correctly. The dead `tempColor` is gone.

## C4 — `ConnectionDao.getById(Long)` can never match (String UUID PK) — FIXED
**`ConnectionDao.kt:21`**
```
@Query("SELECT * FROM connections WHERE id = :id")
suspend fun getById(id: Long): ConnectionEntity?
```
The `connections.id` primary key is a String UUID, but this query takes a `Long`.
Room binds the numeric value; it never equals a UUID string, so the method always
returns `null`. Any caller relying on it silently fails to find the connection.

**Fix (DONE):** the dead `getById(Long)` method was **deleted** — it had zero
callers (all `.getById(` calls in the tree are on other DAOs), so removing it is
safe and preferable to changing the type (which would create a third String-keyed
duplicate). The live String-keyed lookups `getConnectionById` / `getConnection`
(`ConnectionDao.kt`) remain; consolidating those two into one is tracked as
optimization **O3** (separate, non-breaking refactor).

## C5 — Shell injection via unquoted VM name in libvirt/virsh path — FIXED
**`LibvirtApiClient.kt:154-213`**
The domain (VM) name is interpolated into a `virsh` command string sent over SSH
without quoting or validation. A VM named e.g. `x; rm -rf ~` (or any name the
remote host or an imported profile can influence) executes arbitrary commands on
the hypervisor host with the connecting user's privileges.

**Fix (DONE):** added `shQuote()` (POSIX single-quote escaping: wraps in `'…'`
and renders embedded quotes as `'\''`) and `requireValidDomain()` (rejects blank
names, whitespace, and NUL). Every `virsh <cmd> $domain` call now validates then
interpolates `${shQuote(domain)}`, so a name like `x; rm -rf ~` is passed as a
single literal argument. Verified: `make check` compiles clean.

## C6 — `includePasswords` toggle breaks the backup fidelity invariant (incomplete restores) — FIXED
**Severity: HIGH (data-fidelity defect — was mis-scoped as a credential leak).**

**FIX APPLIED.** The `includePasswords` toggle is gone; a backup is now
unconditionally complete on every path. Changes:
- `BackupExporter.collectBackupData()` — dropped both `includePasswords` and
  `includeSecrets` params; always exports the password sidecar and `secrets.json`.
- `BackupManager.createBackup()` — dropped the `includePasswords` param and the
  `includeSecrets` local; content is always full, `encryptBackup`/`password` only
  govern file-level protection.
- `ImportExportActivity` — deleted the "Include saved passwords" checkbox; both
  `showUnencryptedExportWarning`/`showExportPasswordDialog` call `performExport`
  with `password` only; `performExport(uri, password)` lost its flag.
- Rewrote the false unencrypted-warning copy: it now states the backup contains
  everything (passwords, private keys, tokens) and that the file must be secured.

Note (verified during fix): because `includeSecrets` was already hardcoded `true`,
`exportConnections(includePasswords || includeSecrets)` already forced passwords
into every backup — so the flag was *vestigial* and its removal is
behavior-preserving. The user-visible defect was the misleading checkbox/dialog
copy, not actual data loss. IDEA.md "never plaintext" reconciliation is left as
spec-drift item #13 (IDEA.md not modified during this audit).

Original finding retained below.

**`ImportExportActivity.kt:637-644,665-668,695` · `BackupManager.kt:97` · `BackupExporter.kt:156`**

**Design invariant (owner-confirmed):** a backup includes **absolutely
everything**; restoring from it must leave the app in the exact state it was in
when the backup was created. Backups are *deliberately* unencrypted-by-choice —
securing the exported file is the user's responsibility. Encryption-at-rest for
credentials is the job of **sync** (`SyncEncryptor`, PBKDF2), a separate subsystem
— *not* backup. So writing secrets into a plaintext backup is **correct and
intended**, not a leak. Verified to be the backup flow, not sync
(`exportBackupToUri` → `showExportOptionsDialog` → `showUnencryptedExportWarning`).

**The real defect: the `includePasswords` flag silently violates that invariant on
BOTH paths, so the default restore is incomplete (passwords lost).**
```
// Unencrypted path — hardcoded, no choice
performExport(uri, includePasswords = false, password = null)   // :644

// Encrypted path — checkbox DEFAULTS to unchecked
val includePasswordsCheckbox = CheckBox(this).apply {
    text = "Include saved passwords (encrypted)"
    isChecked = false                                           // :667
}
...
performExport(uri, includePasswordsCheckbox.isChecked, password) // :695
```
- Unencrypted backup: passwords **never** included.
- Encrypted backup: passwords included **only if** the user happens to tick a
  box that defaults off.

Either way, the default backup restores a device that is *missing its saved
passwords* — a silent breach of "restore = same state." (Keys and tokens are
already always included via the hardcoded `includeSecrets = true` at
`BackupManager.kt:97`, so passwords are the sole exception — an inconsistency, not
a policy.)

**Secondary defect — the unencrypted dialog copy lies about what it exports:**
```
// ImportExportActivity.kt:637-639
"Saved passwords and private keys are always excluded from
 unencrypted backups to reduce risk..."
```
Private keys are **not** excluded (`includeSecrets = true`). The copy is false and
must be corrected regardless of the flag decision.

**Fix — make a backup unconditionally complete:**
1. Remove the `includePasswords` parameter entirely (or force it `true`) throughout
   `BackupManager.createBackup` / `BackupExporter.collectBackupData`. A backup is
   always everything; there is no partial mode.
2. Delete the "Include saved passwords" checkbox (`ImportExportActivity.kt:665-686`)
   — it offers a choice that must not exist. The only user choice is
   encrypted-vs-plaintext, which governs *file protection*, not *contents*.
3. Set the unencrypted path (`:644`) to export the full payload as well.
4. Rewrite the unencrypted warning copy (`:634-641`) to tell the truth, e.g.:
   *"This backup is saved as plaintext JSON and contains everything — your
   connections, saved passwords, SSH private keys, and cloud API tokens. Anyone who
   can read this file has full access. Store it somewhere only you control. To
   protect the file itself, choose 'Export with password protection' instead."*
5. Drop the `IDEA.md` "never store in plaintext" framing for this path — that
   constraint governs the **Room DB and sync**, not a user-initiated backup the
   user has explicitly opted to leave unencrypted. Add a one-line note to
   `IDEA.md` distinguishing "at-rest DB/sync" from "user-exported full backup" so
   the intent is unambiguous (see spec-drift item #13).

Verified against source.

---

## C7 — Terminal copy ignores scroll offset → copies wrong region — FIXED
**`TerminalView.kt` — `getSelectedText()`**

Reported from the field: a selection highlighted in the scrolled-back transcript
copies text from a *different* region (offset downward by the scrollback amount).

Root cause: selection anchor/focus rows are stored as **visual (viewport) rows**
(`pixelToCell()` derives them from the touch Y with no scroll offset, and
`drawSelectionOverlay()` draws the highlight at those same visual rows). But
`getSelectedText()` passed those visual rows straight to
`buffer.getSelectedText()`, whose Y arguments are **external buffer rows**
(negative = scrollback). The renderer maps visual→external as
`externalRow = row - scrollRows` (`renderTermuxBuffer`, line 1290). Without that
shift, a selection made while scrolled up by N rows copies N rows too far down
(newer) than the highlight — exactly the observed symptom.

**Fix (DONE):** `getSelectedText()` now computes
`scrollRows = (scrollYInt / cellHeight).toInt()` (identical to the render path,
line 1212) and passes `startRow - scrollRows` / `endRow - scrollRows` to
`buffer.getSelectedText()`. Copied text now matches the highlighted region in
scrollback. Verified: `make check` compiles clean.

**Related defect (NOT yet fixed — separate feature, awaiting go-ahead):**
`getTextAtPosition()` (line 1575) and `detectUrlAtPosition()` (line 1678) compute
`row = screenRow + scrollRows` — the **opposite sign** from the render path
(`row - scrollRows`). Their comment claims "the same way renderTermuxBuffer does"
but it is inverted. Consequence: word double-tap selection and URL taps read the
wrong row whenever the transcript is scrolled back. Fixing also requires the
bounds checks (`row < 0` guards in `getTextAtPosition`/`getRowText`) to admit
negative external rows so scrollback taps resolve at all.

---

# HIGH

## H1 — `BUILD_DATE` baked at configure time breaks F-Droid reproducibility — FIXED
**`app/build.gradle`** — `defaultConfig` set `BUILD_DATE` from `new Date()`,
which flows into `fdroidRelease`. A wall-clock timestamp makes bit-for-bit
reproducible builds impossible; F-Droid will never match. **Fix applied:** added
`resolveBuildEpochSeconds` — resolves a stable timestamp from `SOURCE_DATE_EPOCH`
(the reproducible-builds standard, which F-Droid sets) → the last commit's author
timestamp (`git log -1 --format=%ct`, deterministic per checkout) → `0` as an
offline last resort. `BUILD_DATE` now formats that epoch with the same display
pattern **in UTC** (a second reproducibility trap fixed: `SimpleDateFormat`
previously used the default timezone). `providers.exec` keeps it config-cache
compatible. Verified: `make check` configures + compiles clean.

## H2 — F-Droid metadata pinned to a stale version/commit — FIXED
**`metadata/io.github.tabssh.yml`** declared `versionName 0.0.9 / versionCode 9 /
commit v0.0.9`, but the tree is `0.9.1 / 10`. F-Droid builds the metadata's
`commit`, so the current release would never ship on F-Droid. **Fix applied:**
brought the file to parity with the already-correct `fdroid-submission/` recipe —
bumped to `0.9.1 / 10 / v0.9.1`, added the `prebuild` local.properties steps, and
added `AutoUpdateMode: Version` + `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$`
+ `CurrentVersion`/`CurrentVersionCode`. The missing `UpdateCheckMode` was the
root cause of the staleness (no auto-detection of new version tags), so adding it
prevents recurrence. Verified against the sibling submission file.

## H3 — BouncyCastle JCA provider has no R8 keep rules — FIXED
**`app/proguard-rules.pro`** (BC registered at `TabSSHApplication.kt:109`). BC
registers algorithm classes by string name in its provider constructor; R8 full
mode strips them, silently breaking PEM/OCI key parsing (`OciKeyMaterial.kt`) on
release builds. Tink has keep rules; BC has none. **Fix applied:** added
`-keep class org.bouncycastle.** { *; }` and `-dontwarn org.bouncycastle.**` to
`proguard-rules.pro`. Verified: `minifyFdroidReleaseWithR8` (real minified build)
runs BUILD SUCCESSFUL — R8 parses and applies the rules with no errors.

## H4 — Reflection breaks under R8 minification — FIXED
The audit named two sites; the tree actually has **four** reflective reads of the
private `session` field on `io.github.tabssh.ssh.connection.SSHConnection`
(`HistoryFetcher.kt:65`, `MoshHandoff.kt:128`, `PortForwardingManager.kt:389`,
`SCPClient.kt:56`), all via `sshConnection.javaClass.getDeclaredField("session")`.
R8 renames/strips that private field in release builds, so the lookup throws
`NoSuchFieldException` and port forwarding / SCP / Mosh handoff / history fetch all
break. **Fix applied:** added a `-keepclassmembers` rule pinning
`SSHConnection.session` (`com.jcraft.jsch.Session`) under its source name. The
`SSHConnection.kt:588` `getMethod("setEnv", …)` site needs **no** new rule — the
JSch `Channel` method is already preserved by the pre-existing
`-keep class com.jcraft.jsch.** { *; }`. Verified on the same
`minifyFdroidReleaseWithR8` build.

## H5 — AWS SigV4 canonical query string not percent-encoding values (FIXED)
**`AwsEc2Client.kt:326`** builds `canonicalQueryString` without percent-encoding
parameter values. Any request whose query carries characters needing encoding —
notably the `NextToken` pagination cursor — produces a signature mismatch and
`SignatureDoesNotMatch`, so EC2 listings silently stop at the first page. **Fix:**
RFC-3986 percent-encode each key and value, then sort by encoded key.

**FIXED:** Rewrote `canonicalQueryString` to split each pair on the first `=`
only (a base64 `NextToken` value can itself contain `=`), RFC-3986 percent-encode
both key and value, sort by encoded key, and rejoin. Added `rfc3986Encode` helper
that encodes everything except the unreserved set `A-Za-z0-9-_.~` with uppercase
hex and space as `%20` (not the `+` that form-encoding emits). Also removed the
two `java.net.URLEncoder.encode(nextToken, "UTF-8")` calls at the DescribeInstances
builder sites — `URLEncoder` uses `application/x-www-form-urlencoded` rules
(space→`+`, leaves `*` unencoded, encodes `~`), which disagree with SigV4 and
double-encoded against the new canonicalizer. The raw cursor is now appended and
the canonicalizer applies the single correct encoding used for both the signed
canonical request and the request URL (`.url(".../?$canonicalQuery")`), keeping
them byte-identical so the signature verifies. Pagination beyond page 1 now works.

## H6 — Background sync is upload-only (never merges or downloads) — FIXED
**`SyncWorker.kt:46-50`** only pushes local state; it never pulls or merges remote
changes. `IDEA.md` requires "cross-device merge with per-entity conflict
resolution." As implemented, a second device's changes are never ingested and can
be overwritten. Additionally there are NO tombstones, so a naive download→merge
would resurrect deletions on the next peer upload.

**Approved fix (full merge w/ tombstones):** single `sync_tombstones` table +
per-entity LWW; all 19 synced entities; Hybrid recording (explicit delete-site
helper + diff-at-collect backstop). Implemented in slices:
- **Slice 1 — DONE:** `SyncTombstone`/`SyncShadow` entities + DAOs; registered in
  `TabSSHDatabase` (v4→v5, `MIGRATION_4_5` creates both tables additively); schema
  `5.json` exported; compiles clean (`make check`).
- **Slice 2 — DONE:** `TombstoneRecorder` helper + delete-site instrumentation
  across 13 files (connections, groups, keys, themes, identities, snippets,
  macros, VNC, cloud, hypervisor, monitor slots).
- **Slice 3 — DONE:** collector emits tombstones (`collectTombstones`) via the
  diff-at-collect backstop (`runBackstop`, shadow − live); `collectAll` includes
  them in the payload.
- **Slice 4 — DONE:** forward half — `SyncDataApplier.applyTombstones` applies
  remote tombstones on download: deletes each tombstoned local row unless a
  strictly-newer local copy exists (LWW; the 3 timestamp-less entities always
  delete), then re-records surviving tombstones via `recordIfAbsent` for
  transitive 3-device propagation; called at the end of `applyAll`. Reverse half
  — a `suppressed()` guard on all 16 upsert loops skips re-adding an incoming
  row that a local tombstone covers (3-device/re-download resurrection); an
  incoming row strictly newer than the delete is a legit resurrection and clears
  the stale tombstone.
  NOTE: the standalone `MergeEngine` (523 lines) and `collectChangedSince` are
  verified dead code (zero callers) — the real merge is `applyAll`'s inline LWW;
  `MergeEngine` is redundant and will NOT be wired in.
- **Slice 5 — DONE:** `SyncWorker.doWork` and `performSync` are now two-way —
  download→apply→collect→upload; `performDownload` applies then snapshots. Each
  path calls `SyncDataCollector.snapshotState()` after a successful apply/upload
  so the tombstone backstop diffs against the real last-synced baseline (it was
  never called before, so the shadow was always empty and the backstop never
  fired).
- **Slice 6 — DONE:** `MigrationTest.kt` rewritten for the real migration set
  (v3→v4→v5): `migrate3To4_columnAdded` asserts `keep_alive_in_background` on
  `vnc_hosts`, `migrate4To5_syncTablesCreated` asserts `sync_tombstones` and
  `sync_shadow` are created additively, plus a `migrateChain3To5` full-chain
  `runMigrationsAndValidate`. androidTest sources compile clean; the instrumented
  test itself needs a device/emulator to execute (unavailable here — documented
  ceiling), but Room's `validateDroppedTables`/schema validator runs inside it.
- **Ceiling (honesty):** timestamp-less entities (HypervisorProfile,
  TrustedCertificate, MonitorSlot) have no LWW signal → tombstone always wins for
  them. Sync/VNC behavior cannot be runtime-verified here (no device).

## H6b — `MigrationTest.kt` is stale and cannot compile against the live schema — FIXED
**`app/src/androidTest/.../MigrationTest.kt`** referenced `MIGRATION_32_33`…
`MIGRATION_36_37` and schema versions v32–v37, but the live DB is v4 (now v5) with
only `MIGRATION_3_4`/`MIGRATION_4_5`, and `app/schemas/` holds only 3/4/5.json.
These symbols did not exist, so the instrumented test could not build. **Fix
applied:** rewritten self-consistently for the real migration set (v3→v4→v5) in
H6 Slice 6; androidTest sources now compile clean.

## H6c — `MainActivityTest.kt` is dead and blocked the whole androidTest source set — FIXED
Surfaced while verifying H6b: compiling `compileDebugAndroidTestKotlin` also pulls
in `app/src/androidTest/java/com/tabssh/ui/MainActivityTest.kt` (note the stale
`com/tabssh` path under an `io.github.tabssh.ui` package). It drove a "quick
connect" inline form on MainActivity that no longer exists — 7 of its 8 referenced
view IDs (`edit_quick_host/port/username`, `btn_quick_connect`,
`fab_add_connection`, `text_empty_connections`, `text_welcome`) are absent from
`res/` (only `toolbar` survives), plus an unimported `containsString`. It has not
compiled since that UI was removed, so it silently broke the entire androidTest
source set (including the new `MigrationTest`). **Fix applied:** removed the dead
test rather than fabricate assertions against invented IDs; it exercised a deleted
feature and cannot be runtime-verified here anyway (no device). `MigrationTest`
also updated to the Room 2.6.1 `MigrationTestHelper(instrumentation, Class)`
constructor (the deprecated `(name, AssetManager, factory)` overload no longer
resolves under 2.6.1).

## H7 — libvirt SSH uses `StrictHostKeyChecking=no` — FIXED
**`LibvirtApiClient.kt:72`** disabled host-key verification for the hypervisor SSH
connection, defeating TOFU and enabling MITM against hypervisor management —
inconsistent with the app's own TOFU model everywhere else. **Fix applied:** the
hypervisor connection now wires the shared `HostKeyVerifier` (same known-hosts DB
as regular SSH) with `StrictHostKeyChecking="ask"`. Accept-new TOFU semantics: an
unknown host key is auto-trusted and persisted (new-host callback →
`ACCEPT_NEW_KEY`); a changed key is hard-rejected (changed callback →
`REJECT_CONNECTION`), and with no `UserInfo` set JSch fails closed on rejection.

## H8 — Lint `checkOnly` disables nearly all checks — FIXED
**`app/build.gradle:211`** — `checkOnly` *replaced* the enabled set with four IDs,
silently disabling `NewApi`, `MissingTranslation`, `WrongConstant`, and hundreds
more; the `enable 'ContentDescription', ...` on the next line was dead. **Fix
applied:** dropped `checkOnly`; the security checks (`SecureRandom`,
`TrustAllX509TrustManager`, `BadHostnameVerifier`,
`SSLCertificateSocketFactoryCreateSocket`) and the a11y checks
(`ContentDescription`, `ClickableViewAccessibility`) are now `enable`d *on top of*
the full default set, and the non-existent `TouchTargetSize` id was removed.
**Consequence handled in the same batch:** restoring the default set surfaced 67
real Error-severity issues that `abortOnError true` would fail on. Rather than
grandfather them with a baseline, all 67 were fixed (see below), so lint now runs
the full check set and passes clean — a future `NewApi`/`Base64`-class regression
will fail the build.

> **Latent NewApi crash class — FIXED (surfaced by H8):** correcting the lint
> config exposed 53 `NewApi` violations — API 23/24/26/28/30 calls invoked
> unguarded under `minSdk 21`, the same crash family as the `java.util.Base64`
> bug (a missing-API call throws `NoSuchMethodError`/`NoClassDefFoundError`, which
> the surrounding `catch (Exception)` blocks do **not** catch → hard crash on
> Android 5.0–8.x). **Fix applied — two-pronged:**
> - **`minSdk` raised 21 → 24** (`app/build.gradle`), clearing every API 22–24
>   call in one move (ThreadLocal.withInitial-adjacent, `ConcurrentHashMap.newKeySet`,
>   `SNIHostName`, `KeyGenParameterSpec` API-23 builder, `Context#getColor`, the
>   `'X'` ISO-8601 date pattern, `Collection#removeIf`, `startDragAndDrop`,
>   `AtomicInteger#updateAndGet`, `KeyguardManager#isDeviceSecure`,
>   `windowLightStatusBar`). Still covers ~96% of active devices.
> - **The 8 remaining API ≥ 26 sites guarded/replaced with all-API-safe forms:**
>   `AwsEc2Client` `ThreadLocal.withInitial` → anonymous `ThreadLocal` subclass;
>   `MoshNativeClient` `Process#isAlive` → `exitValue()` probe; `Logger`
>   `getLongVersionCode` → `SDK_INT >= P` guard with `versionCode` fallback;
>   `SecurePasswordManager` `setUserAuthenticationParameters` (API 30) →
>   `SDK_INT >= R` guard with `setUserAuthenticationValidityDurationSeconds(0)`
>   fallback; `themes.xml` `windowLightNavigationBar` (API 27) → `tools:targetApi`.
>
> **Other Error-severity fixes in the batch:** `RemoteFileEditorActivity.onBackPressed`
> `MissingSuperCall` (clean-exit path now calls `super`); 8 `UseAppTint`
> (`android:tint` → `app:tint` in AppCompat layouts; widget `tools:ignore`d as
> RemoteViews cannot use `app:tint`); 6 `MissingTranslation` (`mdm_desc_*` added to
> de/fr/es).

## H9 — Alpha security library on the credential path (FIXED)
**`androidx.security:security-crypto:1.1.0-alpha06`** (alpha since 2023) guards
encrypted credential storage and carries a fragile Tink/ProGuard story. **Fix:**
pin to stable `1.0.0`, or document why the alpha is required.

**FIXED (premise corrected):** The finding assumed `security-crypto` was on the
credential path — it is not. An exhaustive search (`security.crypto`,
`EncryptedSharedPreferences`, `EncryptedFile`, `MasterKey`,
`com.google.crypto.tink`) found **zero references** anywhere in `app/src`. Actual
credential encryption is `AndroidKeyStore` + AES/GCM `Cipher` used directly in
`crypto/storage/SecurePasswordManager.kt` (and `HypervisorPasswordStore.kt`).
The alpha dependency was declared but never used, so the correct fix is neither
pinning nor documenting — it is **removal**. Dropped the
`androidx.security:security-crypto:1.1.0-alpha06` line, eliminating the alpha
Tink/ProGuard surface entirely with no behavioral change. `jsr305` is retained
(its comment previously mis-attributed it to Tink) because it supplies the
`javax.annotation.*` types OkHttp references — comment corrected accordingly.
`make check` clean after removal, confirming nothing compiled against it.

## H10 — PIN stored as unsalted single-round SHA-256 — FIXED
**`PinLockActivity.kt:74-77,183`** — the app-lock PIN was hashed with a single
unsalted SHA-256. A 4–6 digit PIN is trivially reversible from a rainbow table if
the hash leaks (backup, rooted device). **Fix applied:** PIN hash is now salted
Argon2id (per-device 16-byte random salt) stored as `v2:<saltB64>:<hashB64>`,
reusing the pairing subsystem's tuned `PairingDecryptor.deriveKey` (64 MiB, t=3,
~1s — which also rate-limits on-device brute force alongside the 5-attempt
lockout). Derivation runs on `Dispatchers.Default` via `lifecycleScope` to keep it
off the UI thread, with a `busy` re-entrancy guard. **Migration:** PINs stored
under the legacy bare-SHA-256 scheme are verified against the old hash and then
transparently re-hashed to Argon2id in place on the next successful unlock, so the
weak form is never persisted again. *(Cross-listed HIGH/MEDIUM; treated HIGH
because it protects app-lock.)*

## H11 — Host-key fingerprint uses non-standard hex:colon format — FIXED
**`HostKeyVerifier.kt`** displayed fingerprints as colon-separated hex instead of
the OpenSSH `SHA256:<base64>` form users verify against. Users cannot cross-check
the TOFU prompt against `ssh-keygen -l` output, undermining the verification's
purpose. **Fix applied:** `generateFingerprint` now renders `SHA256:` + unpadded
base64 of the raw SHA-256 digest via `android.util.Base64` (`NO_PADDING or
NO_WRAP`) — API-1 safe, unlike `java.util.Base64` (API 26) which is a latent
minSdk-21 crash pervasive elsewhere in this file (see note below).
**Migration hazard fixed alongside:** `HostKeyDao.verifyHostKey` previously
matched on `publicKey == publicKey && fingerprint == fingerprint`, so a naive
format change would have flagged every previously-trusted host as `CHANGED_KEY`
(false security alarm). Verification now matches on the public key alone (the
host's true identity; the fingerprint is a derived display of it) and self-heals
a stale-format stored fingerprint in place on the next successful verify.

> **Related latent bug — FIXED:** `java.util.Base64` (API 26) was used under
> `minSdk 21` with no core-library desugaring — a guaranteed
> `NoClassDefFoundError` on Android 5.0–7.1 the moment any of these paths ran.
> Swept every occurrence to `android.util.Base64` (API 1): `HostKeyVerifier.kt`
> (host-key encode/decode), `OciSigner.kt` (OCI request signing), `GcpComputeClient.kt`
> (GCP JWT — URL-safe/no-pad preserved via `URL_SAFE or NO_PADDING or NO_WRAP`),
> and `Converters.kt` (Room byte-array TypeConverter, reached via `import java.util.*`).
> Encodings preserved: standard→`NO_WRAP`, url-safe→`URL_SAFE or NO_PADDING or NO_WRAP`,
> decode→`DEFAULT`, so on-disk/wire formats are unchanged and existing DB rows decode.

---

# MEDIUM

## M1 — `runBlocking` inside JSch host-key callback (deadlock risk) — FIXED
**`HostKeyVerifier.kt:64,104,136,175,231,255,291,315,336`** — the JSch handshake
thread calls `check()`, which uses `runBlocking(Dispatchers.IO)` to run a Room
query. Under bulk reconnect, a saturated IO pool can deadlock (blocked thread
waiting on a coroutine that can't be scheduled). **Fix applied:** added a
dedicated single-thread dispatcher `hostKeyDbDispatcher` (daemon thread,
app-lifetime, decoupled from the shared `Dispatchers.IO` pool) in the companion
object; all nine `runBlocking(Dispatchers.IO)` callback sites now run on it. The
calls are sequential and non-nested, so a single thread cannot self-deadlock, and
the shared IO pool can no longer be exhausted out from under the handshake thread.
Removed the now-unused `Dispatchers` import; `make check` clean.

## M2 — CI "validation" never compiles the project — FIXED
**`.github/workflows/ci.yml`** installed JDK 17 but never ran `./gradlew`; every
step was `test -f`/`grep`. Broken code (compile/KSP/test failures) passed CI and
only failed later in `release.yml`. **Fix applied:** added a real compile gate —
Setup Android SDK (api 34 / build-tools 34.0.0, same pinned action as
`release.yml`) + Gradle cache + `./gradlew kspDebugKotlin compileDebugKotlin
--no-daemon` (mirrors `make check`), inserted before the structural checks.
`act --list -W ci.yml` parses clean; all actions reuse `release.yml`'s
already-SHA-pinned versions.

## M3 — OWASP dependency-check plugin two majors behind
**`org.owasp.dependencycheck:8.4.0`** relies on the retired NVD legacy feed, so the
release CVE gate reports stale/empty data. **Fix:** bump to ≥10.x and supply an
`nvd.api.key`.

## M4 — Orphaned coroutine scopes — FIXED
**`TermuxBridge.kt:629`** (readJob scope) and **`TermuxBridge.kt:1188`** (Mosh
watchdog) created `CoroutineScope(Dispatchers.IO)` instances whose root `Job` was
never cancelled — cancelling `readJob`/`moshWatchdog` stopped the child coroutine
but leaked the enclosing scope's Job across reconnects. **Fix applied:** added a
class-level `sessionScope = CoroutineScope(Dispatchers.IO + Job())`; both the read
loop and the Mosh watchdog now `sessionScope.launch { }`, and `cleanup()` cancels
`sessionScope`'s Job alongside `writeScope` at terminal teardown. `make check`
clean.

## M5 — Six stub verification tasks report success unconditionally
**`app/build.gradle:383-440`** — `detectSecrets`, `checkFDroidCompliance`,
`validateThemeAccessibility`, `checkWCAGCompliance`, `detectMemoryLeaks`,
`runPerformanceBenchmarks` are `println "✅ ..."` no-ops. `detectSecrets` prints
"No hardcoded secrets detected" while C1 sits in the same file. A false audit
trail. **Fix:** implement or remove.

## M6 — DB upgrade path: only destructive fallback, zero migrations — FIXED
**`TabSSHDatabase.kt`** originally had `version = 3`,
`fallbackToDestructiveMigrationFrom(1, 2)`, no registered `Migration` objects;
only `schemas/.../3.json` existed. This was explained by commit `0bd35d42`, which
deliberately **reset** the version numbering to 3 (so the AI.md "37 migrations"
wording is stale — see AD1, this is *not* a "build 34 migrations" task). Residual
risk at the time: any user who ever installed a build on the old numbering and
upgraded would hit the destructive fallback and lose data.

**Verified as resolved (no new code needed — the H6 sync work already fixed
this):** the DB is now at `version = 5` with real `Migration` objects registered
for every bump since the v3 reset — `MIGRATION_3_4` (adds
`vnc_hosts.keep_alive_in_background`, additive) and `MIGRATION_4_5` (H6 sync
tombstone tables, additive) — both wired via `.addMigrations(MIGRATION_3_4,
MIGRATION_4_5)`. `fallbackToDestructiveMigrationFrom(1, 2)` remains scoped only
to versions 1/2, which per the class kdoc and `0bd35d4b2467`'s commit message
("All installs are now at v3 — the v3 release was the intentional last wipe")
never shipped with persisted user data on any released build. `app/schemas/`
holds `3.json`/`4.json`/`5.json`, matching the registered migration chain. No
user on a released build can hit the destructive path with real data at risk.
`MigrationTest.kt` (H6 Slice 6) exercises the v3→v4→v5 chain. Verified against
source; no code change required, audit entry corrected to match reality.

## M7 — `-printseeds/-printusage/-printmapping` write to module root — FIXED
**`proguard-fdroid.pro:22-24`** — relative paths drop `seeds.txt`/`usage.txt`/
`mapping.txt` next to `app/build.gradle` (the CI workspace), risking accidental
commit. **Fix applied:** paths redirected into
`build/outputs/mapping/fdroidRelease/`. Verified via the R8 minify gate
(`:app:minifyFdroidReleaseWithR8`, BUILD SUCCESSFUL): `seeds.txt`/`usage.txt`/
`mapping.txt` now land in the module build-output tree alongside R8's own
mapping; no stray files remain in the app module root.

## M8 — AI.md documents a release artifact set that doesn't ship — FIXED
**`AI.md:1195`** claimed "10 APKs (5 release + 5 fdroid)"; `release.yml` uploads 5
release APKs + `mapping.txt` + checksums, and `assembleFdroidRelease` is an
explicit "sanity check, not uploaded" smoke build (F-Droid signs/publishes its
own). **Fix applied:** AI.md row corrected to the real artifact set — 5 release
APKs + mapping + checksums, F-Droid build smoke-only and never uploaded.

## M9 — CHANGELOG not cut for 0.9.1
**`CHANGELOG.md`** has `[Unreleased]` fixes but no `[0.9.1]` block, though the
build and `release.txt` are at 0.9.1; F-Droid/fastlane metadata references it.
**Fix:** cut a `[0.9.1]` section.

## M10 — FLAG_SECURE default-off on the terminal (hardening, with caveat)
**`TabTerminalActivity`** — screenshot prevention is not enabled by default on the
terminal surface. Per `IDEA.md:50`, screenshot prevention on the terminal is
*"configurable"* (only PIN and auth screens are *"always enforced"*), so this is
**not a spec violation**. It is a hardening recommendation: consider defaulting
`FLAG_SECURE` on for the terminal (a live SSH session can display secrets) with a
user opt-out, and confirm PIN/auth screens do enforce it unconditionally. *(Framed
as recommendation, not defect.)*

## M11 — OSC-8 hyperlink parsing runs on every write — FIXED
**`TermuxBridge.kt`** re-scanned output for OSC-8 sequences on each write
regardless of whether any were present — a per-write UTF-8 `String` allocation,
a regex `findAll`, and two `lastIndexOf` calls even for plain bulk output.
**Fix applied:** `appendWithOsc8Tracking` now byte-scans the raw buffer for the
ESC byte (0x1B) first. Every sequence it intercepts — OSC 8 anchors
(`ESC ]8;…`) and the bracketed-paste toggles (`ESC [?2004h/l`) — begins with
ESC, so a buffer with no ESC byte makes the decode, regex, and both
bracketed-paste searches provably no-ops; those are skipped and the raw bytes
appended directly (the common case for `cat` of a text file, which carries no
escape sequences). Behavior is identical: the ESC scan is a strict superset of
what the parser could ever match. `make check` clean. *(Also closes the OPT
item.)*

## M12 — Reflection-based session access is fragile beyond R8 — FIXED
Beyond H4's release-build break, `getDeclaredField("session")`
(`PortForwardingManager.kt:389`) couples to a private JSch internal that can change
across fork versions with no compile-time signal. **Correction:** the reflection
targeted `SSHConnection`'s *own* private `session` field — not a JSch internal —
so the finding's "private JSch internal" premise was inaccurate; the real hazard
was R8 renaming that field to null out the lookup in release builds, plus zero
compile-time signal. **Fix applied:** added an `internal fun jschSession(): Session?`
accessor on `SSHConnection` and replaced all four reflection sites
(`PortForwardingManager.kt`, `HistoryFetcher.kt`, `MoshHandoff.kt`, `SCPClient.kt`)
with a direct call. `internal` keeps it off the public API surface while giving
every in-module collaborator a compile-checked, R8-safe path. `make check` clean.

## M13 — Clipboard auto-clear coverage — VERIFIED (no change)
`IDEA.md` requires "clipboard auto-clear for sensitive pastes." Audited all 25
clipboard write sites (`grep ClipboardHelper.copy` + `grep setPrimaryClip`):
- **Single-helper invariant holds.** Every write routes through
  `ClipboardHelper.copy`; there is *zero* raw `setPrimaryClip` bypass anywhere
  in `app/src/main/java`. The helper already implements label-owned,
  sensitive-only auto-clear correctly (verifies the stamped `ClipDescription`
  token before clearing, cancels on any newer non-sensitive copy, uses
  `applicationContext` to avoid Activity leaks, sets `IS_SENSITIVE` on API 33+).
- **No app-credential copy site exists.** Nothing copies a stored password,
  passphrase, or private key to the clipboard — so nothing needs
  `sensitive = true` today. The nearest sites are SSH *public* keys
  (`IdentitiesFragment`, not secret), remote VNC/SPICE clipboard sync
  (`VncView`/`SpiceView`, remote-origin), and snippet command text
  (`SnippetManagerActivity`). Snippets are a deliberate `sensitive = false`
  call — auto-wiping them would destroy legitimate command snippets the user
  copied on purpose; the helper's KDoc documents this.
**Honest caveat:** the `sensitive = true` path is therefore currently unexercised.
It is not dead — it is the correct hook for any *future* "copy password/key"
affordance — but there is no compile-time guard forcing such a future feature to
use it. That is a process risk, not a present defect. No code change warranted.

## M14 — `checkOnly` masks accessibility checks that IDEA.md requires — FIXED
Because H8's `checkOnly` disabled `ContentDescription`/`ClickableViewAccessibility`
enforcement, the TalkBack content-description requirement (`IDEA.md:74`) was not
lint-gated. **Fix applied:** covered by H8 — both a11y checks are now `enable`d and
the full default set runs, so the requirement is enforced.

---

# LOW / NIT

- **L1 — LiveData in new code.** `dao/ThemeDao.kt:18`, `dao/KeyDao.kt:21`,
  `dao/ConnectionDao.kt:18`, `ui/fragments/ConnectionListFragment.kt:99-103`
  expose/use `LiveData`/`MutableLiveData`. AI.md §17 rule 10 forbids LiveData in
  new code. **Fix:** migrate to `Flow`/`StateFlow`.
- **L2 — Dual JSON libraries.** Both `gson:2.10.1` (Room `Converters.kt`,
  `TabTerminalActivity.kt:80,1549`) and `kotlinx-serialization-json:1.6.0` (sync)
  ship. ~400 KB and two failure modes. **Fix:** consolidate on
  kotlinx-serialization; drop Gson.
- **L3 — Commented-out code.** `TabTerminalActivity.kt:2548-2552` (dead
  `onCreateOptionsMenu` block). Convention prohibits committed commented-out code.
  **Fix:** delete.
- **L4 — Inline trailing comment.** `settings.gradle:14` appends `// Termux ...`
  to a code line. Convention: comments on their own line above. **Fix:** move up.
- **L5 — `$(shell pwd)` in Makefile.** `Makefile:21-22` spawns a subshell;
  convention mandates `$(PWD)`. **Fix:** replace both.
- **L6 — Stale version strings in CI.** `ci.yml:153,162` hardcode "TabSSH 1.0.0"
  (project is 0.9.1). **Fix:** derive from `build.gradle`/`release.txt`.
- **NIT-1 — Outdated AndroidX/Kotlin deps.** `appcompat 1.6.1→1.7.1`,
  `core-ktx 1.12.0→1.16.0`, `fragment-ktx 1.6.2→1.8.8`, `lifecycle 2.7.0→2.9.1`,
  `coroutines 1.7.3→1.10.2`, `kotlinx-serialization 1.6.0→1.7.3`,
  `material 1.11.0→1.12.0`. `MPAndroidChart v3.1.0` unmaintained (2021). No known
  CVEs at current pins; lifecycle/coroutines gaps carry bug fixes.

---

# OPT — Optimization opportunities

- **O1 — Per-character Paint allocation in the renderer.**
  `TerminalRenderer.kt:54,64` allocates a `Paint` per glyph/run during draw. On a
  full-screen redraw this is thousands of allocations per frame → GC churn and
  dropped frames while scrolling. **Fix:** reuse a small pool of `Paint` objects
  keyed by style, mutate color/flags in place.
- **O2 — OSC-8 parse on every write.** See M11 — add an `ESC ]8` fast-path guard.
- **O3 — Duplicate DAO queries.** `ConnectionDao.kt:27,30`
  (`getConnectionById`/`getConnection`) are redundant with each other and with the
  (broken) `getById`. Collapse to one correct `getById(id: String)`.
- **O4 — Redundant serialization round-trips.** With Gson and kotlinx both in play
  (L2), `ConnectionProfile` is serialized via Gson in hot UI paths
  (`TabTerminalActivity.kt:1549`). Consolidating removes one serializer's warm-up
  and cuts APK size.
- **O5 — `runBlocking` on the connect path.** M1's `runBlocking` also serializes
  the host-key DB read on the handshake thread; a pre-cached known-hosts map would
  remove the per-connect DB round-trip entirely.
- **O6 — String-built shell commands over SSH.** The libvirt/virsh path (C5) also
  re-opens/reformats command strings per call; batching status queries reduces SSH
  round-trips for VM lists.
- **O7 — Configure-time work in `build.gradle`.** `resolveGitCommit()` and
  `BUILD_DATE` run at configure time (H1) — moving version/commit stamping to a
  cached task input speeds up incremental builds and helps reproducibility.
- **O8 — Bulk reconnect thread usage.** Combined with M1/M4, bulk reconnect
  currently spins per-session scopes without pooling; a shared, lifecycle-scoped
  dispatcher would cap thread growth.

---

# SPEC DRIFT — implemented but not in `IDEA.md`

> The user confirmed `IDEA.md` is outdated: features were added without updating
> the WHAT spec. These are **documentation gaps to close in a later IDEA.md edit**,
> not out-of-spec code. `IDEA.md` was intentionally **not modified** in this audit.
> Update `IDEA.md` to describe each of the following before the next release.

1. **VNC console client** — `VncHost`/`VncIdentity` entities, connect UI. Not in IDEA.md.
2. **SPICE console client** — remote-console support beyond SSH.
3. **Telnet protocol** — full client (AI.md §16 confirms fully implemented).
4. **X11 forwarding** — full implementation (AI.md §16).
5. **Mosh** — listed in IDEA.md core, but the watchdog/roaming implementation
   detail and its lifecycle are undocumented.
6. **Cloud providers beyond the IDEA list** — verify parity: code covers
   DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, GCP, Azure, OCI. IDEA lists the
   same set — confirm no additional provider slipped in.
7. **Hypervisor: libvirt/KVM management client** — `LibvirtApiClient` with virsh
   over SSH; IDEA mentions QEMU/libvirt at a high level but not the SSH/virsh
   transport or its console integration.
8. **Dashboard / monitor slots** — `exportDashboardConfig`, `MonitorSlots`
   entities (a metrics dashboard). Not in IDEA.md.
9. **Charting** — MPAndroidChart-backed graphs (implied by the dashboard).
10. **Macro library with raw-byte recording** — present (IDEA lists "macro
    recording" tersely; the stored-macro management UI is undocumented).
11. **Snippet library with `{var}` substitution** — in IDEA, but the variable
    prompt UI and storage are undocumented specifics.
12. **QR pairing decryptor (Argon2id)** — `PairingDecryptor`; IDEA mentions QR
    pairing but not the Argon2id-based crypto envelope.
13. **Backup/export format v3 (single-JSON)** — `BACKUP_VERSION`, secrets file
    layout; IDEA says "portable encrypted archive" without the schema/versioning.
14. **Sync encryption (PBKDF2 100k)** — `SyncEncryptor`; IDEA says "E2E encrypted
    sync" without the KDF/parameters.
15. **Secure password manager (Keystore AES-GCM)** — tiered storage
    implementation detail behind IDEA's "tiered access levels."
16. **App-lock PIN with lockout** — in IDEA; the PIN hashing/lockout mechanics
    (and their weaknesses, see H10) are undocumented.
17. **TLS certificate pinning (TOFU) for hypervisor REST** — in IDEA; the pinning
    store and rotation flow are undocumented.
18. **OCI API-key auth (PEM/BouncyCastle)** — in IDEA; the key-material parsing
    path is undocumented.
19. **Themes count** — IDEA says "23 built-in themes"; verify the shipped theme
    list matches (theme accessibility validation task exists but is a stub, M5).
20. **Terminal multiplexer auto-attach (tmux/screen/zellij)** — in IDEA; the
    detection/attach heuristics are undocumented.
21. **Session recording/replay transcript format** — in IDEA; the on-disk
    transcript format is undocumented.
22. **Bulk import formats** — CSV/JSON/PuTTY .reg/Terraform state parsers exist;
    IDEA lists them but not the field-mapping behavior.
23. **Tasker/Locale plugin + home-screen widgets + Quick-connect** — in IDEA;
    verify the implemented intent/plugin surface matches.
24. **Audit log of SSH commands/session events** — in IDEA; storage/retention and
    whether it can leak command args is undocumented (and worth a privacy note).

**Action:** treat this list as the IDEA.md update backlog. Each item should get a
sentence in the relevant IDEA.md section describing the WHAT, so the spec matches
shipped behavior.

---

# AI.md INTERNAL DRIFT

The HOW spec contradicts itself in places; reconcile in a later AI.md edit.

- **AD1 — DB version disagreement.** `AI.md §8.1` says DB "version 37"; `AI.md §2`
  says "Room (v17), 17 entities"; the code is `version = 3` (reset by commit
  `0bd35d42`). Three different numbers. The "37 migrations" framing is stale and
  should **not** be read as a task to author 34 migrations (see M6). **Fix:** make
  AI.md state `version = 3` and the true entity count, and note the numbering reset.
- **AD2 — Entity count.** §2's "17 entities" should be recomputed against the DAO
  set actually registered in `TabSSHDatabase` (VNC, monitor-slot, dashboard, etc.
  suggest more than 17). **Fix:** count and correct.
- **AD3 — §15 package map lists phantom packages.** The documented package map
  references packages that don't exist in the tree. **Fix:** regenerate the map
  from the actual `app/src/main/java/io/github/tabssh` layout.
- **AD4 — Release-artifact count.** §1195 "10 APKs (5+5)" vs the real 5 APKs +
  mapping (M8). **Fix:** correct.
- **AD5 — Stub status stale wording.** §16 lists Mosh/X11/Telnet under a "stubs"
  heading while stating they are fully implemented. Wording invites the exact
  false-positive this audit had to guard against. **Fix:** move them out of any
  "stub" framing.

---

# Areas checked and found clean

- **Action SHA pins** — all four workflows pin every third-party action to a full
  40-char SHA with a version comment. No tag pins.
- **Gradle wrapper** — 8.11.1, current.
- **JSch fork** — `com.github.mwiede:jsch:2.27.7` (maintained fork); ProGuard keep
  for `com.jcraft.jsch.**` is correct.
- **kotlinx-serialization ProGuard** — complete recommended keep set present.
- **Tink ProGuard** — `com.google.crypto.tink.**` keep present.
- **`keystore.jks` / `local.properties`** — both gitignored and untracked;
  `local.properties` holds only `sdk.dir`, no secrets. (Note: this is the *file*;
  the *password* leak is C1, a separate issue.)
- **TODO/FIXME/HACK** — zero in the 251 `.kt` files under `app/src/main`.
- **`printStackTrace()` / `System.out`** — zero in `app/src/main`.
- **`GlobalScope`** — zero direct usages (one prose comment only).
- **Sensitive data in logs** — `HostKeyVerifier` logs fingerprint+hostname (public
  metadata); no passwords/keys/tokens found in logging paths.
- **F-Droid proprietary deps** — none (no Firebase/Play Services/Analytics; ZXing
  chosen over ML Kit to avoid Play Services).
- **Gradle config cache** — enabled; `resolveGitCommit` uses `providers.exec`
  (cache-compatible).
- **OWASP suppression file** — `config/dependency-check-suppressions.xml` present.
- **CI concurrency** — distinct group keys + `cancel-in-progress: true` on all
  workflows.
- **Mosh / X11 / Telnet / frequently-used UI** — fully implemented per AI.md §16;
  **not** reported as stubs.

---

## Suggested fix order

1. **C1** (rotate/secure signing key) — blocks any trustworthy release.
2. **H1, H2** — the F-Droid reproducibility + metadata release blockers.
3. **C3, C4, C6 — DONE** (correctness batch, this session). C2 was withdrawn as a
   false positive (bracketed-paste bytes are correct). C3 reverse-video swap fixed,
   C4 dead `getById(Long)` deleted, C6 `includePasswords` toggle removed so every
   backup is complete + dialog copy corrected. Pending verification: Docker build +
   `make test` + lint gate before commit.
4. **C5, H7, H10, H11** — security hardening on hypervisor + local-auth paths.
5. **H3, H4** — release-build R8 breakage (test on a real minified build).
6. **H5, H6** — cloud correctness (AWS pagination, sync merge).
7. Remaining HIGH → MEDIUM → LOW → OPT as capacity allows.
8. **Spec drift + AI.md drift** — a documentation pass, independent of code.

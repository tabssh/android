# Production-Readiness Bug Audit — v1.0

Started: 2026-09-13
Scope: full tree, priority on commit `3ea9c71cc465` files (TermuxBridge.kt,
TabManager.kt, TabSSHApplication.kt, SessionPersistenceManager.kt,
ConnectableHostRegistry.kt, PaneGroupEditDialog.kt). Static analysis only —
no builds run, no source edited.

## Disposition

Every finding below has been acted on. This file is kept as the record of what
was found and why; it is not an open worklist. The earlier AI-owned checklist
was removed after completion; the current project task list is `TODO.md`.

- **M1–M4, N1–N9** — fixed in the same commit that added this file.
- **N10 (mosh bootstrap streams nulled without closing)** — investigated and
  dismissed as a non-issue. `connectMoshClient` nulls `inputStream` and
  `outputStream`, but its only caller, `SSHTab.connectMosh`, either retains the
  bootstrap SSH session deliberately (`moshX11BootstrapConnection = bootstrap`,
  for X11 forwarding) or disconnects it explicitly. Closing the streams inside
  `connectMoshClient` would break the X11-retain path. Not a leak.

The audit's own **Coverage** section at the end of this file names what it did
not examine. A follow-up pass is recorded below.

---

## BLOCKER

None found.

---

## MAJOR

### M1 — Session restore resizes the terminal transposed (rows/cols swapped)
- **Where:** `app/src/main/java/io/github/tabssh/background/SessionPersistenceManager.kt:438`
- **What:** Restore calls `tab.termuxBridge.resize(session.terminalRows, session.terminalCols)`. The signature is `TermuxBridge.resize(newColumns: Int, newRows: Int)` (`app/src/main/java/io/github/tabssh/terminal/TermuxBridge.kt:1376`). Arguments are swapped.
- **Why it matters:** The saved scrollback is replayed into an emulator sized e.g. 40×120 instead of 120×40 — wrapping/reflow of the restored transcript is wrong. A later view-driven resize corrects the dimensions but cannot un-mangle the already-replayed reflow.
- **Evidence:** Read both call site and function signature; parameter names are explicit.
- **Confidence:** CONFIRMED.
- **Suggested fix:** `resize(session.terminalCols, session.terminalRows)` — and do it before replaying scrollback.

### M2 — `disconnect()` does not clear `pendingUtf8`; stale bytes leak into the next session
- **Where:** `app/src/main/java/io/github/tabssh/terminal/TermuxBridge.kt:1485-1490` (reset block) vs. the `pendingUtf8` holdback buffer (declared ~line 547).
- **What:** `disconnect()` resets `osc8Links`, `bracketedPasteActive`, `pendingEscTail`, but not `pendingUtf8`. If a session drops mid-multibyte-character, the held-back partial UTF-8 bytes are prepended to the first chunk of the *next* connection on the same bridge (the bridge is documented as reconnect-safe and reused).
- **Why it matters:** First output after reconnect can be corrupted (mojibake or a swallowed byte), and it is nondeterministic — classic "reconnect shows garbage" report.
- **Confidence:** CONFIRMED by reading; not runtime-reproduced.
- **Suggested fix:** Reset `pendingUtf8` (and any sibling holdback state) in the same `disconnect()` block.

### M3 — `getScreenContent()` reads the emulator without `emulatorLock`
- **Where:** `app/src/main/java/io/github/tabssh/terminal/TermuxBridge.kt:1204-1214`; contrast `getScrollbackContent` (~1230) which takes `emulatorLock` and documents the torn-read risk. Also `estimateTranscriptBytes()` (~1260) is unlocked.
- **What:** The Termux AAR does no internal locking (verified via javap by team-lead: zero ACC_SYNCHRONIZED / monitorenter). Commit `3ea9c71cc465` added `emulatorLock` serialization, but `getScreenContent()` still reads `screen.getSelectedText(...)` with no lock. Callers run on arbitrary threads: `SSHTab.kt:967`, `TerminalView.kt:2649`, `TaskerWorker.kt:222`, `ScrollbackSearchController.kt:238`, and the mosh watchdog (`TermuxBridge.kt:1681`) from `sessionScope` (IO) while writes append concurrently.
- **Why it matters:** Torn reads / `IndexOutOfBoundsException` inside TerminalBuffer while the write path mutates rows. This is a gap in the very hazard the new lock was introduced to close — regression-class.
- **Confidence:** CONFIRMED (code inspection; race itself not reproduced).
- **Suggested fix:** Wrap the `screen` access in `getScreenContent()` (and `estimateTranscriptBytes()`) in `synchronized(emulatorLock)`.

### M4 — Database maintenance path is dead: old sessions never purged, VACUUM never runs
- **Where:** `app/src/main/java/io/github/tabssh/storage/database/TabSSHDatabase.kt:1285-1291` (`performMaintenance()`), `dao/TabSessionDao.kt:98`.
- **What:** `TabSSHDatabase.performMaintenance()` (30-day `deleteOldInactiveSessions` + `VACUUM`) has zero callers. Grep for `performMaintenance` finds only `SSHSessionManager.performMaintenance` being invoked (`SSHConnectionService.kt:887`, `SessionPersistenceManager.kt:198`) — never the database one.
- **Why it matters:** Inactive `tab_sessions` rows — each carrying persisted scrollback text — accumulate forever. Per-tab growth is bounded (see verified note V2), but rows for closed/never-restored tabs are never reclaimed and the DB is never vacuumed.
- **Confidence:** CONFIRMED (exhaustive grep).
- **Suggested fix:** Call `database.performMaintenance()` from an existing periodic path (e.g. next to `sshSessionManager.performMaintenance()` in SSHConnectionService, on IO).

---

## MINOR

### N1 — `TabManager.handleKeyboardShortcut` is dead code containing two latent bugs
- **Where:** `app/src/main/java/io/github/tabssh/ui/tabs/TabManager.kt:674-704`.
- **What:** Zero callers (exhaustive grep; the live handler is `TabTerminalActivity.kt:5002`, which is correct). Inside the dead copy: the `isCtrlPressed && KEYCODE_TAB` branch (687) precedes the `isCtrlPressed && isShiftPressed && KEYCODE_TAB` branch (697), making Ctrl+Shift+Tab unreachable; and the Ctrl+T branch (678-681) consumes the key while doing nothing.
- **Why it matters:** Divergent duplicate of live logic — if it's ever wired up, tab navigation regresses.
- **Confidence:** CONFIRMED.
- **Suggested fix:** Delete the function.

### N2 — `SessionPersistenceManager.configureSettings` is dead code
- **Where:** `app/src/main/java/io/github/tabssh/background/SessionPersistenceManager.kt:551`.
- **What:** No callers anywhere (grep). Its settings (auto-save interval etc.) can never take effect from preferences.
- **Confidence:** CONFIRMED.
- **Suggested fix:** Delete, or wire it to the settings screen if the prefs exist.

### N3 — CancellationException swallowed in two SFTP download paths → spurious error UI on normal teardown
- **Where:** `app/src/main/java/io/github/tabssh/ui/activities/RemoteFileEditorActivity.kt:247` (and the upload sibling at :301), `app/src/main/java/io/github/tabssh/sftp/RemoteFileOpener.kt:132` (and :251).
- **What:** `lifecycleScope.launch` bodies with `while (true) { delay(100) }` polls wrapped in `catch (e: Exception)`. Scope cancellation (activity destroyed mid-transfer) throws CancellationException from `delay`, which is caught, logged as "Download failed", and a Toast is shown; RemoteFileEditorActivity additionally calls `finish()`.
- **Why it matters:** User leaves the screen → gets a "Download failed" toast for a transfer that was simply cancelled. These are the only two files left with this pattern (systematic sweep: files containing coroutine delays + `catch (e: Exception)` and no CancellationException handling — all others already guard it).
- **Confidence:** CONFIRMED (pattern); runtime not reproduced.
- **Suggested fix:** `catch (e: CancellationException) { throw e }` before the generic catch in each.

### N4 — Fabricated cursor position and empty env/cwd persisted with each session save
- **Where:** `app/src/main/java/io/github/tabssh/ui/tabs/TabManager.kt:880` (saveTabState).
- **What:** Persists `cursorRow = tabStats.terminalRows / 2` (mid-screen invented value), `environmentVars = ""`, `workingDirectory = ""`.
- **Why it matters:** Misleading persisted data; anything that later trusts `cursorRow` restores a wrong cursor.
- **Confidence:** CONFIRMED.
- **Suggested fix:** Persist the real cursor row from the emulator (under `emulatorLock`) or store a sentinel meaning "unknown".

### N5 — Unsynchronized reads on TabManager/TermuxBridge state
- **Where:** `TabManager.kt:94` (`getActiveTabIndex()` reads `activeTabIndex` outside `tabsLock`); `TermuxBridge.kt:313` (`lastLoggedAltScreenState` touched from Termux callback + IO read loop — logging only).
- **Why it matters:** Stale/torn reads; low impact but inconsistent with the locking discipline used elsewhere in the same classes.
- **Confidence:** CONFIRMED (visibility hazard by inspection).
- **Suggested fix:** Take `tabsLock` in the getter; mark the log flag `@Volatile` or drop it.

### N6 — OSC 8 links: BEL-terminated and chunk-split sequences lose link tracking
- **Where:** `app/src/main/java/io/github/tabssh/terminal/TermuxBridge.kt:501` (regex requires ESC `\` (ST) terminator).
- **What:** OSC 8 terminated with BEL (0x07 — emitted by many tools) or split across read chunks is not captured into `osc8Links`; the text still renders (Termux consumes unknown OSC) but the link is not tappable.
- **Confidence:** CONFIRMED for the regex; limitation rather than crash.
- **Suggested fix:** Accept BEL as an alternative terminator; chunk-split handling can reuse the existing `pendingEscTail` holdback.

### N7 — `!!` non-null assertions in production code (AI.md PART 0 violation)
- **Where (non-test, ~25 sites in 15 files):** `TabTerminalActivity.kt` (4, incl. :290, :669, :1622-1623), `AwsEc2Client.kt` (4), `SshHostsFragment.kt` (2), `HypervisorEditActivity.kt` (:771, :913), `ConnectionEditActivity.kt` (2), `PortForwardCoordinator.kt` (:210, :229), `TerminalView.kt`, `VpsHostEditActivity.kt`, `DomainEditActivity.kt`, `VpsMarkdownImportExport.kt`, `DomainCsvImportExport.kt`, `TerminalRenderer.kt:33`, `TerminalBuffer.kt`, `SSHConnection.kt`, `NetworkDetector.kt:90`.
- **What:** AI.md PART 0 ("Null safety", line 266) forbids `!!` outside test code. Each site is a latent KotlinNullPointerException.
- **Confidence:** CONFIRMED (grep, string/comment matches filtered).
- **Suggested fix:** Replace with `?.let`/`checkNotNull` with message/early-return per site.

### N8 — Misplaced KDoc in TabSSHApplication
- **Where:** `app/src/main/java/io/github/tabssh/TabSSHApplication.kt:456-478`.
- **What:** The KDoc describing `migrateDockerNamingToContainer` sits stacked above `seedDefaultSnippets`'s own KDoc; `migrateDockerNamingToContainer` (line 526) itself is undocumented. Two consecutive doc blocks — the first is dangling.
- **Confidence:** CONFIRMED.
- **Suggested fix:** Move the 456-470 block above line 526.

### N9 — Registry refresh delete+insert is not transactional
- **Where:** `app/src/main/java/io/github/tabssh/storage/registry/ConnectableHostRegistry.kt:47-48` (and siblings :70-71, :117-118, :167-168).
- **What:** `deleteBySourceType(...)` then `insertAll(...)` as two separate DAO calls — a concurrent reader (picker open racing a background `refreshAll`, which `PaneGroupEditDialog.kt:71` launches) can observe an empty/partial registry.
- **Why it matters:** Transient empty host picker; self-heals on next read.
- **Confidence:** CONFIRMED pattern; race window small.
- **Suggested fix:** Wrap each replace in `db.withTransaction { }`.

### N10 — UNCONFIRMED: mosh bootstrap streams nulled without close
- **Where:** `app/src/main/java/io/github/tabssh/terminal/TermuxBridge.kt:1583-1584` (`connectMoshClient`).
- **What:** `inputStream`/`outputStream` set to null without closing. The bootstrap SSH channel may be torn down elsewhere (mosh design closes the SSH transport after handshake).
- **Confidence:** UNCONFIRMED — needs a trace of the SSH channel lifecycle in the mosh connect path to verify a leak exists.

---

## Verified-clean notes (checked, not findings)

- **V1:** All `PendingIntent` creation sites use `FLAG_IMMUTABLE` (per-file site/flag counts matched across every file); no `FLAG_MUTABLE` anywhere.
- **V2:** `tab_sessions` dual writers (TabManager `sessionId = tabId` upsert vs SessionPersistenceManager random-UUID insert) do NOT accumulate rows per tab: entity has `Index("tab_id", unique = true)` and `insertSession` uses `OnConflictStrategy.REPLACE`, which SQLite resolves by deleting the conflicting row on any unique constraint. Closed-tab inactive rows still persist forever — see M4.
- **V3:** `TaskerActionReceiver` is exported but protected by the `io.github.tabssh.permission.TASKER` signature-level permission (AndroidManifest.xml:576-579). Only `MainActivity` and `LinkHandlerActivity` are otherwise exported, both intentionally (launcher; ssh://-scheme handler with confirm-before-connect).
- **V4:** Room migration chain complete 3→27, all registered in `addMigrations` (TabSSHDatabase.kt:1254), no `fallbackToDestructiveMigration`.
- **V5:** No `GlobalScope` usage (PortForwardingManager.kt:428 is a comment; the code uses `applicationScope` correctly and guards the audit write with its own try/catch).
- **V6:** `TerminalLinkSanitizer.kt` (untracked WIP) unifies the OSC-8/ANSI link allowlists — the former dual-allowlist divergence risk is resolved by that WIP file; both TermuxBridge and ANSIParser delegate to it.
- **V7:** `ConnectableHostRegistry.persistOciCloudPin` correctly rethrows CancellationException from its `finally`-invoked body (:202-207).
- **V8:** `ContainerHost.linksCloudInstance()` key format matches registry cloud-instance ids (`parseCloudInstanceId`), so `refreshContainerHosts`'s preview lookup keying is correct.
- **V9:** CancellationException-swallow systematic sweep (coroutine-loop files catching bare Exception with no CancellationException guard) found only the two files in N3 — the pattern is otherwise consistently handled across 75 files.

---

## Coverage

**Examined in depth:** all six files of commit `3ea9c71cc465` (TermuxBridge.kt full 1833 lines, TabManager.kt full, TabSSHApplication.kt full, SessionPersistenceManager.kt full, ConnectableHostRegistry.kt full, PaneGroupEditDialog.kt full); TerminalLinkSanitizer.kt; TabSessionDao.kt + TabSession entity; TabSSHDatabase migration chain; AndroidManifest.xml exported surface; AI.md PART 0; TabTerminalActivity keyboard-shortcut region.

**Examined via targeted greps only:** PendingIntent flags (all files), `!!` inventory, GlobalScope, runBlocking sites, CancellationException pattern sweep, `catch (e: Exception)` distribution, Room destructive-migration.

**NOT covered (honest gaps):**
- **Category H (CHANGELOG claims cross-check):** not performed — CHANGELOG is large; no claim-by-claim verification done.
- **Category I (per-API-level correctness with internet citations):** not performed beyond in-code-documented items (e.g. TRIM_MEMORY deprecation already handled at TabSSHApplication.kt:1006-1010). No external CVE/API research was run this session.
- **Deep reads still needed:** hypervisor/vnc/spice consoles, sync and backup edge cases, provider-specific cloud behavior, and service lifecycle paths beyond the files changed in this pass. The pane group editor and its keyboard/input hand-off are now covered below.
- Credential-logging sweep (grep for secrets in log statements) was not run exhaustively.

---

## Follow-up continuation — 2026-09-30

This pass continued the production audit and fixed these confirmed issues:

- **Untrusted backup/sync input could exhaust memory.** SAF backup and sync
  files used unbounded `readBytes()`, ZIP entries could expand without a cap,
  and GZIP sync payloads were unbounded after decryption. Added strict limits
  on source files, entries, expanded ZIP data, and decompressed sync data.
  Cloud-provider API responses now also reject bodies larger than 16 MiB.
- **Encrypted archives could request unreasonable Argon2 work.** The V3
  header accepted up to 1 GiB and 64 passes despite current archives using
  64 MiB / 3 passes. Capped accepted parameters at 128 MiB / 10 passes before
  key derivation.
- **Cancellation could be swallowed while restoring/applying data.** Added
  `CancellationException` propagation to row-level sync and backup import
  handlers, data collection, conflict resolution, and backup/sync verification
  operations.
- **Replace-mode backup restore cleared SharedPreferences inside a Room
  transaction.** SharedPreferences are not transactional, so a later DB
  rollback could leave them erased. They are now cleared only after the Room
  transaction commits.
- **Recursive SFTP/SCP paths trusted filesystem/server link behavior.** SFTP
  recursive downloads now reject paths escaping the selected destination,
  skip remote symlinks, and cap recursion depth. Recursive uploads constrain
  canonical local paths and stop symlink cycles. SCP directory uploads now
  validate the top-level protocol name and honor cancellation while streaming.
- **Widget refresh work outlived its broadcast without a pending result.**
  `onUpdate()` now calls `goAsync()` and finishes the pending broadcast after
  the database reads and widget updates complete.
- **Logger anonymization state could race crash capture/export with its writer.**
  Sanitization and map resets now share the logger monitor.
- **Wake-lock, service state, and timestamp formatter concurrency findings**
  are also fixed in `PowerLockHelper`, `SSHConnectionService`, and `Logger`.

**Verification:** `git diff --check` and `make check` passed after these fixes.
The check runs Kotlin compilation, unit tests, Android Lint, and resource
processing in the project Docker image. Instrumented Room migration tests from
the preceding continuation also passed on a clean emulator.

## Follow-up continuation — panes IME and native-library alignment

- **Panes editor fields did not open the Android keyboard.** On API 34, taps
  focused the Step 2 row editors while Android's input manager still served the
  activity RecyclerView. The dialog window retained `FLAG_ALT_FOCUSABLE_IM`
  because its EditTexts are attached from a RecyclerView after the dialog is
  shown. Clearing that flag and issuing an explicit tap-triggered show request
  fixed the name, working-directory, and custom-title fields. Runtime checks
  covered typing, saving, reloading an existing group, hiding/reopening the IME
  on the same field, and canceling without changing stored values.
- **The upstream Termux `libtermux.so` was only 4 KB ELF-aligned.** Replaced
  the prebuilt artifact with the pinned Apache-licensed `v0.118.1` module source
  and rebuilt it using 16 KB ELF linker flags. This keeps the Java/JNI API at
  the pinned revision while removing the upstream APK compatibility warning.
- **Regression coverage:** added an instrumentation check that loads the JNI
  library from the app process. It passed on both API 34 and an API 35 emulator
  whose `getconf PAGESIZE` reports 16384.

**Verification:** `make check` passed with the vendored Termux JVM test suite.
Release and F-Droid release builds passed. Every debug, release, and F-Droid
APK passed `zipalign -c -P 16 -v 4`; all four Termux JNI ABIs report `p_align`
`0x4000` for every ELF LOAD segment. The native-library instrumentation test
also passed on the API 35 16 KB emulator.

## Follow-up continuation — whole-tree release audit

The audit was expanded beyond the prior subsystem-focused passes to cover
remaining app packages and code paths across automation, pairing, text imports,
session persistence, terminal recordings, audit logging, metrics, themes,
and mosh cleanup.

- **Tasker operations could swallow cancellation and leak SSH sessions** when
  tab creation failed at the tab limit. Cancellation now propagates, and the
  connection is closed when no tab can own it.
- **Pairing CBOR parsing accepted trailing data and narrowed hostile integers**
  before validating supported versions and ports. The decoder now requires
  complete input consumption and validates exact bounds for versions, ports,
  salts, nonces, and authenticated ciphertext. Regression tests cover trailing
  bytes and impossible lengths.
- **Container update cancellation could strand stopped or replaced workloads.**
  The applier now restores the previous state under non-cancellable cleanup;
  a regression test covers cancellation during replacement verification.
- **Several SAF imports read arbitrarily large text/key files.** Added bounded
  readers for connection, tracker, theme, cloud credential, key, and bulk
  imports. Session history decompression and sync snapshots are capped too.
- **Terminal recording limits counted characters rather than stored UTF-8 and
  event framing.** Recording now stops before a complete event would exceed the
  configured byte limit. Transcript paths fall back safely when external
  storage is unavailable.
- **Audit metadata interpolation could emit invalid JSON or forged syslog
  records.** Metadata now uses JSON serialization and syslog messages strip
  controls and line breaks. Retention cleanup now continues until under its
  cap and enforces bounded preferences.
- **Cancellation was also swallowed in metrics, theme operations, and mosh
  server cleanup.** These suspend paths now rethrow `CancellationException`.

**Verification:** the final Docker `make check` completed successfully after
these changes, including Kotlin compilation, Android Lint, resource processing,
and the JVM unit suite. `make release` and the workflow's
`assembleFdroidRelease` variant both succeeded. All five APK outputs for each
variant passed `zipalign -c -P 16 -v 4`. `git diff --check` passed.

`make instrumented` was attempted, but the Docker build container could not
reach an ADB device (`No device/emulator reachable`). Instrumented tests were
therefore not run in this continuation; the earlier API 34/API 35 terminal
checks recorded above are not evidence for this entire change set.

## Follow-up continuation — infra TLS and VNC TLS hostnames

- **Rotating infrastructure leaf certificates caused avoidable trust prompts.**
  Removed leaf pin enforcement and the pin editor from the hypervisor REST flow.
  Optional REST `verifySsl` now selects Android platform CA/hostname validation;
  when off, TLS is encrypted without certificate identity checks. Hypervisor
  console TLS follows the same no-certificate-check policy. SSH continues to use
  the known-hosts host-key verifier. Legacy pin columns/credential fields remain
  ignored for compatibility. Updated IDEA.md and SPEC.md to document the residual
  risk and transport distinction.
- **VNC TLS with an IP literal could fail before verification.** `RfbClient`
  attempted to create a DNS SNI name from IPv4/IPv6 input. It now omits SNI for
  address literals while preserving endpoint identification when certificate
  verification is enabled, with unit coverage for IPv4, IPv6, DNS names, and an
  invalid IPv4 address.
- **SPICE teardown could join its own GLib worker.** Native error/disconnect
  callbacks synchronously destroyed the session; native destruction stopped
  and joined the GLib loop thread that made the callback. The bridge now uses
  one process-wide dispatcher for GLib's global default context, serializes all
  session operations there, and queues callback-triggered cleanup off the active
  callback stack. Cleanup runs from `finally` so a throwing listener cannot leak
  the native session. Kotlin holds the input write lock through stop and destroy
  so no queued send can race session teardown.
- **SPICE framebuffer callbacks trusted server geometry before JNI allocation
  and copying.** Native code now caps geometry at 32 MP, validates stride and
  dirty rectangles before touching buffers, honors row padding, and converts the
  two documented primary formats (`32_xRGB`, `16_555`) to opaque Android ARGB.
  This fixes both out-of-bounds native reads/writes and invalid colors/alpha.
- **R8 was too old for the selected Kotlin metadata version.** AGP 8.13.2's
  bundled R8 8.13.19 emitted repeated Kotlin metadata parsing errors against
  Kotlin 2.4.10 during minification. Pinned R8 9.1.31 in `settings.gradle`,
  which meets Android's documented 9.1.29 minimum for Kotlin 2.4. Removed an
  F-Droid ProGuard option that R8 explicitly ignored.
- **Recursive local-path containment mishandled `/` as the selected root.**
  SFTP/SCP now build the containment prefix without adding a second separator.
- **SPICE JNI allocation/copy failures could leave a pending JNI exception on
  the persistent GLib thread.** The callback bridge clears allocation/copy
  exceptions before reporting a session error, and its stale compile-status
  comment now reflects the native build verification.
- **Lint rejected misleading indentation in the password carry-over sweep.**
  Rewrote the cursor and credential branches explicitly; the final lint run is
  clean.

**Verification:** final `make check` passed after all source and settings changes
(Kotlin compilation, JVM unit tests, Android Lint, and resource processing).
Containerized `assembleRelease` and `assembleFdroidRelease` both passed with the
R8 override; the Kotlin-metadata parsing errors disappeared. R8 still reports
that AGP's class-file provider cannot use asynchronous parsing, a non-fatal
performance notice. The F-Droid task was rerun after removing the ignored option.
The final SPICE bridge and pinned dependency stack cross-compiled and linked as
`libtabssh_native.so` for arm64-v8a. Debug, provider-release, and F-Droid APKs
passed 16 KB `zipalign`; debug/provider APK signatures verified. F-Droid output
is unsigned as intended. The pane editor IME runtime checks on API 34 and the
16 KB API 35 JNI instrumentation checks are recorded above from the prior pass.


## Follow-up audit — automation, recordings, retention, and X11

Baseline: `6680c1201cee`. SPEC.md was read first, including its accepted TLS
and cleartext behavior. This pass traced exported automation requests, audit
storage/retention, recording creation/sharing/cleanup, X11 relay sockets,
container health verification, and remaining unbounded HTTP reads.

Confirmed findings fixed:

- **High — X11 listener exposed beyond the device.** `X11Proxy.start()` used
  `ServerSocket(0)`, which binds every interface despite its localhost contract.
  It now binds IPv4 loopback explicitly. Either relay reaching EOF closes both
  sockets; repeated starts retain the existing listener. Regression coverage
  verifies the bind address and server-side EOF against a local test X server.
- **High — audit history removed after large SFTP uploads.** Upload events used
  the transfer byte count as the log-entry size, while most other events defaulted
  to zero. Retention now measures stored UTF-8 row content, including existing
  and imported rows. Room-backed tests cover a 2 GiB upload and Unicode entries
  whose legacy size was zero.
- **High — automation receiver crash and truncated commands.** Character caps
  did not enforce WorkManager's serialized 10 KB limit. Both receivers now use
  one validated builder and reject oversize input instead of truncating command
  text. The Locale editor rejects a request it cannot dispatch. Tests include
  a Unicode payload below the character cap but above the serialized limit.
- **Medium — recording collisions and long-name failures.** Second-resolution
  filenames let simultaneous sessions append to the same transcript or overwrite
  pending recording bookkeeping. A shared bounded filename generator adds a UUID
  for transcript, cast, and video targets. Concurrent cast uploads also use unique
  temporary files, and anonymous upload identity creation is synchronized.
- **Medium — failed capture setup leaked resources and pending files.** The
  service now owns resources immediately after allocation, cleans them up on
  failed setup, and finishes recording storage on destruction. MediaStore open
  failures discard their pending row; terminal writers close on write failure.
- **Medium — recording lookup could select a same-named file in another folder.**
  Share/delete lookup now restricts the exact recording directory and excludes
  pending rows. Listing uses the same filters. Provider-backed unit coverage
  checks failed opens and query filters; a device test covers cast creation,
  pending visibility, finalization, reading, and deletion.
- **Medium — unbounded remote response allocations remained.** Xen Orchestra,
  paste providers, and cast uploads now enforce decoded byte limits while
  retaining OkHttp charset handling. Tests cover unknown-length bodies and
  exact-limit non-UTF-8 text.
- **Low — wall-clock changes distorted timeout/backoff windows.** Container
  replacement verification and X11 retry backoff now use monotonic elapsed time.

- **High — startup migration could terminate the application.** Keystore initialization
  was outside the migration error handler. Initialization failures now preserve
  carry-over credentials for retry and allow startup to continue; cancellation
  still propagates. The full JVM suite exposed this through its unavailable
  Android Keystore provider.

- **High — scoped-storage cast recordings could not start.** The API 35 device
  test reproduced MediaProvider rejecting JSON files in Movies. Casts now use
  Documents/TabSSH and their own MIME type; listing and lookup include both
  exact directories, retaining legacy Movies compatibility.

Device verification: all 8 connected tests passed on API 35 with 16 KB pages
in casjaysdev/android:latest, including real cast MediaStore lifecycle coverage.
Final `make check` passed (Docker compilation, Android Lint, and 1,168 JVM
tests: zero failures, 10 skipped). `git diff --check` passed. Live cloud
provider operations and full GUI protocol interoperability are outside the
runtime coverage of this pass.

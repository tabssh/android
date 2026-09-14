# Production-Readiness Bug Audit — v1.0

Started: 2026-09-13
Scope: full tree, priority on commit `3ea9c71cc465` files (TermuxBridge.kt,
TabManager.kt, TabSSHApplication.kt, SessionPersistenceManager.kt,
ConnectableHostRegistry.kt, PaneGroupEditDialog.kt). Static analysis only —
no builds run, no source edited.

## Disposition

Every finding below has been acted on. This file is kept as the record of what
was found and why; it is not an open worklist. Remaining work lives in
`TODO.AI.md`.

- **M1–M4, N1–N9** — fixed in the same commit that added this file.
- **N10 (mosh bootstrap streams nulled without closing)** — investigated and
  dismissed as a non-issue. `connectMoshClient` nulls `inputStream` and
  `outputStream`, but its only caller, `SSHTab.connectMosh`, either retains the
  bootstrap SSH session deliberately (`moshX11BootstrapConnection = bootstrap`,
  for X11 forwarding) or disconnects it explicitly. Closing the streams inside
  `connectMoshClient` would break the X11-retain path. Not a leak.

The audit's own **Coverage** section at the end of this file names what it did
not examine; those gaps are carried forward as items in `TODO.AI.md`.

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
- **Deep reads of:** hypervisor/vnc/spice consoles, sftp internals, sync, backup, cloud provider clients, widget internals, services beyond grepped regions — several of these are assigned to sibling agents (console-stack-audit, rfb-stack-audit, sftp-explorer, panes-ime).
- Credential-logging sweep (grep for secrets in log statements) was not run exhaustively.

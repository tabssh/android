# TODO.AI.md

## Open issues from the 2026-09-13 debug-log export (build 11, commit 3fc0d05)

- [ ] **Verify the scrollback cap on a device under real pressure.** The budget
  is a quarter of `ActivityManager.memoryClass` and is enforced only from
  `onTrimMemory` at `TRIM_MEMORY_RUNNING_CRITICAL`/`TRIM_MEMORY_COMPLETE`.
  Neither level can be produced from a JVM unit test, so confirm on hardware:
  open several tabs, scroll each well past a screen, then apply memory pressure
  (another heavy app, or `adb shell am send-trim-memory io.github.tabssh
  RUNNING_CRITICAL`) and check the log reports a non-zero trim while the
  foreground tab keeps its history. Note that `TRIM_MEMORY_COMPLETE` is
  deprecated on API 34+ and may never be delivered — `RUNNING_CRITICAL` is the
  path that matters.

- [ ] **Historical context — the cap this replaced never ran.** The log's
  `Terminal stats: 0 total, 0 active, 0KB memory` line came from
  `terminal/emulator/TerminalManager.kt`, whose only registration entry point
  (`createTerminal()`) had zero callers anywhere in the tree — real terminals are
  created by `TermuxBridge` from the Termux library's own
  `com.termux.terminal.TerminalEmulator`, an unrelated class. Its 50 MB cap,
  30-minute inactive eviction, and `onTrimMemory` hook were all iterating an
  empty map, so the file and its three call sites in `TabSSHApplication` have
  been deleted rather than left reporting numbers that were always zero. Net
  effect: there is, and was, no terminal memory cap. If one is wanted, it has to
  be built against `TermuxBridge`/`TabManager`, which own the real sessions.

- [ ] **Pre-existing: the renderer mutates the emulator buffer with no lock.**
  `TermuxBridge.emulatorLock` now serializes every writer it owns — the SSH read
  loop, `injectLocally()`, `resize()`, `clearTranscript()` — plus the
  `getScrollbackContent()` read the session save depends on. One mutator is
  still outside it: `TerminalView.renderTermuxBuffer()` calls
  `buffer.allocateFullLineIfNecessary(internalRow)` at `TerminalView.kt:1596`,
  from `onDraw()` on the main thread, every frame. Despite the name that is a
  *write*, not a read: the bytecode shows it allocates a `TerminalRow` and
  stores it into `mLines[row]` when the slot is null. So the live race is the
  main-thread draw pass against the `Dispatchers.IO` SSH append, and it is not
  merely a stale-read concern. Every other buffer call in `TerminalView`
  (`getSelectedText`, `getLineWrap`/`isRowSoftWrapped`, `getActiveTranscriptRows`)
  is a confirmed pure read and needs nothing.
  This affects the **SSH path only**. Mosh sessions never use it: the library's
  own reader thread only fills a `ByteQueue` and posts to `MainThreadHandler`,
  and `TerminalEmulator.append()` is called from `handleMessage` on the main
  Looper — so for mosh, append, draw, and trim are all main-thread-confined and
  serial by construction. (Verified against terminal-emulator v0.118.1 bytecode:
  no `ACC_SYNCHRONIZED` and no `monitorenter` anywhere in `TerminalEmulator` or
  `TerminalBuffer`; the only monitor in the AAR is in
  `TerminalSession.cleanupResources`.)
  Two real fixes, both too large to bolt on here: take `emulatorLock` across the
  render pass (puts the draw loop and the IO read loop in contention every
  frame — needs profiling before it is chosen), or move the SSH append onto the
  main thread the way upstream already does for mosh. A suppression or a
  narrower lock around line 1596 alone would not close it.

- [ ] **The OSC 8 scheme allowlist is implemented twice.**
  `TermuxBridge.sanitizeOsc8Url` (companion object) and
  `ANSIParser.kt:830-832` carry the same control-character check, the same
  `substringBefore(':')` scheme extraction, and two separate allowlist constants
  (`OSC8_ALLOWED_SCHEMES` / `ALLOWED_LINK_SCHEMES`). This is a security control,
  which makes divergence between the copies the actual risk: a scheme added to
  one list and not the other silently leaves one path permissive. Collapse to a
  single shared helper and one constant. Noticed while fixing the lint failure
  below, not a regression from it.

- [ ] **Session restore never fires after a cold process start.**
  `SessionPersistenceManager.isAppInForeground` is initialised to `true` (line
  38), but `onActivityStarted` only calls `onAppForegrounded()` when
  `activeActivityCount == 1 && !isAppInForeground` (line 69). On a cold start the
  count goes 0 -> 1 with the flag already `true`, so the guard never passes and
  `restoreSessionState()` is not reached. No other call site restores on a fresh
  process. Net effect: if Android kills the app process and the user relaunches
  it, saved sessions in the database are never replayed — restore works only for
  a process that was backgrounded and is still alive. Likely fix is to
  initialise the flag to `false` so the first `onActivityStarted` counts as a
  foreground transition, but that needs checking against `onAppBackgrounded()`'s
  own guard (line 90) so a cold start does not immediately trip a spurious
  background save. Found while verifying the restore path; the CHANGELOG wording
  has been corrected to claim only the background-and-return case, which is the
  one that actually works.

- [ ] **A restored tab comes back disconnected and stays frozen.**
  `restoreTabTerminalState` replays scrollback into a bridge built by
  `TabManager.createTab()` (`TabManager.kt:108`), which constructs the
  `TermuxBridge` without connecting. Every `tab.connect(...)` call site is in
  `TabTerminalActivity`'s user-initiated new-tab flow, and nothing consults
  `PreferenceManager.isAutoReconnect()` at runtime to trigger a reconnect, so a
  restored tab shows its history but is dead until the user manually reconnects
  — with no visible indication that is what is required. (This is also why there
  is no replay-versus-live-output ordering hazard today; adding auto-reconnect
  would create one, and the replay would then have to complete before the first
  bytes arrive.) Decide whether restore should reconnect automatically or mark
  the tab as disconnected in the UI.

- [ ] **Verify the session-restore fix on a device.** `SessionPersistenceManager`
  wrote `is_active` from `SSHTab._isActive` ("this tab is on screen") while
  `restoreSessionState()` selects on `is_active = 1` meaning "this is the current
  persisted row" — fixed by writing `true` unconditionally after
  `deactivateAllSessions()`. No JVM test covers this path; confirm by opening a
  tab, switching away from it, backgrounding and reopening the app, and checking
  the tab returns with its scrollback intact.

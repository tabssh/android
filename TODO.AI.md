# TODO.AI.md

## Device-only verification (cannot be done without the physical device)

AI and adb have no access to the user's device, so these are checks the user
has to run. They are not "unknown" — the code paths were traced and reasoned
about — but neither has been exercised on hardware.

- [ ] **Verify the scrollback cap under real memory pressure.** The budget is a
  quarter of `ActivityManager.memoryClass` and is enforced only from
  `onTrimMemory` at `TRIM_MEMORY_RUNNING_CRITICAL`/`TRIM_MEMORY_COMPLETE`.
  Neither level can be produced from a JVM unit test. Confirm on hardware: open
  several tabs, scroll each well past a screen, then apply memory pressure
  (another heavy app, or `adb shell am send-trim-memory io.github.tabssh
  RUNNING_CRITICAL`) and check the log reports a non-zero trim while the
  foreground tab keeps its history. `TRIM_MEMORY_COMPLETE` is deprecated on API
  34+ and may never be delivered — `RUNNING_CRITICAL` is the path that matters.

- [ ] **Verify session restore end to end.** Two separate fixes now feed this
  path: `is_active` is written unconditionally after `deactivateAllSessions()`,
  and cold-start restore was unreachable until `isAppInForeground` was
  initialised to `false`. Confirm by opening a tab, switching away, backgrounding
  the app, force-stopping it, relaunching, and checking the tab returns with its
  scrollback intact and the disconnected indicator on its title.

## Audit coverage gaps (not yet reviewed by anything)

The v1 audit pass that produced the fixes in this commit explicitly did not
cover the following. They are unreviewed, not clean.

- [ ] **Cross-check `CHANGELOG.md` claim by claim against the code.** The
  Unreleased section describes a large amount of behavior; nothing has verified
  that each claim matches what the tree actually does. Three separate false
  claims have already been found and corrected by hand, so the base rate is not
  zero.

- [ ] **Per-API-level behavior review.** minSdk is 24 and the test device runs
  API 36; no pass has checked the `Build.VERSION.SDK_INT` guards, deprecated
  API usages, or the behavior changes between those levels against current
  Android documentation.

- [ ] **Un-audited subsystems.** No deep read was done of the hypervisor, VNC,
  or SPICE code, the SFTP internals, or the sync/backup/cloud/widget code.

## Known limitations (deliberate, not regressions)

- [ ] **OSC 8 anchors split across two socket reads lose link tracking.**
  `osc8Pattern` now accepts both terminators (ESC `\` and BEL), but it still
  scans a single decoded chunk. An anchor whose opening sequence lands at the
  end of one read and whose terminator arrives in the next is not captured into
  `osc8Links`; the text renders correctly, the link is just not tappable.
  Holding back an unterminated OSC 8 the way `pendingEscTail` holds back a
  partial `ESC[?2004h` would fix it, but an OSC has no bounded length, so the
  holdback needs a cap and a discard policy before that is safe to add. Not
  attempted here.

- [ ] **Restore does not re-apply the saved title, working directory, or cursor
  position.** `restoreTabTerminalState` replays scrollback and size only.
  `TabSession` carries `title`, `workingDirectory`, `environmentVars`, and the
  cursor coordinates, and the cursor is now persisted accurately, but nothing
  reads them back. Restoring the title in particular would collide with
  `setCustomTitle`, which writes `_title.value` directly without setting
  `terminalTitle` — so a user's custom title is already lost the next time
  `updateTitleWithStatus` runs. Both want fixing together, as one change to how
  a tab's title is owned.

- [ ] **Restored tabs do not auto-reconnect.** A restored tab shows its history
  and is marked disconnected (the `⏸` indicator now appears, which it did not
  before), but nothing consults `PreferenceManager.isAutoReconnect()` to bring
  it back up. Reconnecting automatically would also introduce a
  replay-versus-live-output ordering hazard that does not exist today: the
  scrollback replay would have to complete before the first live bytes arrive.
  Decide whether auto-reconnect is wanted before building it.

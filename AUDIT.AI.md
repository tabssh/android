# Project Audit

Started: 2026-06-14

Comprehensive deep audit. Tracking >5 findings here per audit-agent rules.
Items are deleted when fully fixed and committed.

## Pass 1: Security
(no high-severity findings — secrets policy enforced via SecurePasswordManager / HypervisorPasswordStore; no plaintext password storage observed in DB writes; no hardcoded credentials in source.)

## Pass 2: Code Quality
(Pass 2 `_bytesTransferred` finding resolved — see Completed.)

## Pass 3: Logic and Correctness
(four save-but-never-read preference bugs in this pass — all fixed in this commit; see Completed.)

## Pass 4: Documentation Completeness
- [ ] `CHANGELOG.md` present at repo root — flagged as forbidden by global audit rules, but project rule `AI.md` §17.3 #17 mandates it. Project rule wins; recording for posterity only — no action.

## Pass 5: Spec and Rules Compliance
- [ ] `ui/activities/CloudAccountsActivity.kt` — `@Deprecated` ("Use CloudAccountsFragment via main navigation instead"); still declared in `AndroidManifest.xml:288`. No code path launches it. Candidate for removal after a release cycle to confirm no external intent targets it (e.g. third-party launchers, app shortcuts).

## Pass 6: Code Flow Trace
- [ ] Cross-checked all XML preference keys against Kotlin consumers. Findings folded into Pass 3 above. Remaining XML keys with zero Kotlin consumers (other than the 4 fixed) are either PreferenceCategory headers or click-only Preferences whose handlers are wired in `SettingsActivity.onCreatePreferences()` — verified clean.

## Pass 7: TabTerminalActivity Deep-Dive
Targeted audit of `ui/activities/TabTerminalActivity.kt` (4154 lines) — read in full.
All findings fixed in this commit; see Completed section below.

## Completed
- `res/xml/preferences_general.xml` `confirm_exit` — wired into `MainActivity` `OnBackPressedCallback`; now shows an AlertDialog when enabled.
- `res/xml/preferences_security.xml` `ssh_agent_forwarding` — XML key realigned to `agent_forwarding_default` so `SSHConnection.applyForwardingFlags()` / `PreferenceManager.isAgentForwardingDefault()` see the user's toggle.
- `res/xml/preferences_logging.xml` `debug_log_level` — `Logger` now caches a `minLevel` from this pref on init and refreshes it live via `updateMinLevelFromPrefs()`; SettingsActivity calls the refresh on change. `d/i/w/e` log methods are gated by `shouldLog()`.
- `res/xml/preferences_logging.xml` `host_log_max_size_mb` — `Logger.logHostEvent()` now reads the SeekBarPreference (1–10 MB) per write, replacing the hard-coded 1 MiB cap.
- `ui/activities/TabTerminalActivity.kt` `sendKey("ctrl"|"alt")` — was a toast-only no-op; the `btn_ctrl` / `btn_alt` buttons in `activity_tab_terminal.xml` were wired but produced no terminal effect. Now toggles the existing `TerminalView.setPendingModifier()` latch (the same one-shot CTL/ALT mechanism already used by `MultiRowKeyboardView`), with toast feedback for arm/disarm states and a `null`-terminal guard. Honours auto-clear via `onModifierConsumed` to reset the multi-row keyboard's visual state.
- `ui/activities/TabTerminalActivity.kt` `pasteFromClipboard()` — added `isFinishing || isDestroyed` guard before reading the system clipboard service; on a torn-down activity the subsequent `Toast.makeText(this, ...)` would throw `WindowManager.BadTokenException`. Also added a missing user-facing toast for the "no active terminal view" branch (previously silent log-only failure).
- `ui/activities/TabTerminalActivity.kt` `showSnippetsPickerForActiveTab()` — removed; never called from anywhere in the codebase (grep confirmed). The live snippet picker is `showSnippetsDialog()` / `showAllSnippetsDialog()`. Dead code eliminated.
- `ui/activities/TabTerminalActivity.kt` `setBottomPaneFocused(focus)` — UX inconsistency where the `true` branch showed a "Bottom pane focused" toast but `false` was silent (so tapping back to the top pane gave no feedback). Now announces both transitions ("Bottom pane focused" / "Top pane focused") and only when state actually changes, preventing duplicate toasts from repeated taps on the same pane.

## Pass 8: Terminal Subsystem Deep-Dive
Targeted audit of `terminal/` directory plus `ui/views/TerminalView.kt` and `terminal/TermuxBridge.kt`. Categories covered: touch/gesture handling, drawing/rendering, accessibility, keyboard/IME, lifecycle, threading, memory, duplicate-vs-shared-utils. Findings below — all fixed in this commit.

- `ui/views/TerminalView.kt` accessibility node — `onInitializeAccessibilityNodeInfo` only populated `info.text` from the legacy `terminalBuffer`, which is null whenever a Termux/SSH session owns the view. Result: TalkBack saw an empty terminal node for every real SSH tab. Now prefers `termuxBridge?.getScreenContent()` (which uses Termux's `screen.getSelectedText` honouring line-wrap flags), and falls back to the legacy buffer for the in-memory emulator path.
- `ui/views/TerminalView.kt` dead fields — `accessibilityHelper`, `lastRenderedRows`, `lastRenderedCols`, `lastCursorRow`, `lastCursorCol`, `rowTextBuilder`, `clipRect` were all declared but never read or written after initial assignment. Removed; the `dirtyRows` BitSet is kept (with a clarifying comment) because rows-dirty marking is still useful infrastructure even though `invalidate(left,top,right,bottom)` is deprecated on modern Android.
- `ui/views/TerminalView.kt` `TerminalAccessibilityHelper` class — fully orphan helper class at end of file. Field declaration referencing it was already removed; the class itself was never instantiated and was dragging in an unused `AccessibilityAction` data class. Deleted entirely.
- `terminal/emulator/TerminalEmulator.kt` dead code — `processChar()`, `writeChar()`, `processControlChar()`, `handleKeyPress()`, `handleControlKey()`, `handleAltKey()`, `handleNormalKey()`, and `sendKeyPress()` were leftovers from the pre-ANSIParser era. All terminal input is now routed through `ansiParser.processInput()` (which writes to the buffer directly), and all key sequencing happens in `TermuxBridge.sendKeyPress()`. Cross-referenced every caller via grep — `SSHTab.sendKeyPress()` calls the **bridge** method, not the emulator's. Removed the dead methods plus the now-unused `cursorX`/`cursorY` mirror state that nothing ever read externally.
- `ssh/connection/SSHConnection.kt` `_bytesTransferred` wired — `getInputStream()` / `getOutputStream()` now return cached counting wrappers that increment `_bytesTransferred` on every successful `read()` / `write()`. The wrappers are recreated whenever the underlying JSch stream identity changes (new shell/exec channel after reconnect) and cleared in `disconnect()` so no stale raw-stream pointers survive a teardown. `ConnectionStats.bytesTransferred` and any `bytesTransferred` StateFlow observer now sees real traffic counts instead of a constant 0. Confirmed the wrap point is sufficient: all shell I/O in the app (Termux bridge, SSHTab read loop, exec channel consumers) routes through these two accessors — no direct `shellChannel.inputStream` access outside `openShellChannel()` (where the pre-`connect()` stream allocation is intentional and not part of the byte-count path).

## Pass 9: SSH Subsystem Deep-Dive
Targeted audit of `ssh/` directory (SSHConnection.kt 2167 lines, SSHSessionManager.kt 440 lines, auth/, forwarding/, connection/). Categories: auth flow completeness, error propagation, stale session reuse, port/X11 forwarding teardown, reconnect/retry, thread safety, resource leaks, byte-counter wiring, duplicate identity resolution.

Outcome: the SSH subsystem is in good shape from prior passes — stale pool entries are discarded via the `stateOk && sessionOk` gate in `SSHSessionManager.createConnection()`, `disconnect()` tears down `connectJob` / reconnector / `openChannels` / `sftpChannel` / `x11Proxy` / `session` / `jumpHostSession` in the correct order, port forwarding `cleanup()` issues per-tunnel JSch `unforward` calls before cancelling the scope, X11 proxy handles leaked-fd protection on connect failures, and `executeCommand()` has a try/finally channel-leak guard. Only the `_bytesTransferred` wiring required code change (now fixed — see above).

Duplicate identity resolution between `TabTerminalActivity.startConnection()` (line 1666 — resolves identity for password-prompt gating BEFORE connect) and `SSHConnection.connect()` (line 239 — resolves it again for its own credential pipeline) is intentional: the TabTerminalActivity needs `effectiveAuthType`/`effectiveKeyId` to decide whether to show a passphrase dialog before the connect attempt, and `SSHConnection.connect()` is invoked from many other code paths (monitoring, background, host availability) that have no UI to consult. Consolidating would force every non-UI caller to pre-resolve an `Identity`, increasing surface area for stale-cache bugs. Leaving as-is.

- `terminal/TermuxBridge.kt` OSC 8 link cap — `while (osc8Links.size > 200) osc8Links.removeAt(0)` on a `CopyOnWriteArrayList` is O(N) per element removed (each removal allocates a fresh backing array), so a flood that produced 200+ links in one append turned into O(N^2). Replaced with a single-allocation rotation: when the cap is reached, build a fresh list containing the last (CAP-1) entries plus the new one and swap the volatile reference in one atomic write. Readers on the UI thread still see a consistent snapshot. Extracted `OSC8_LINK_CAP = 200` to the companion object.

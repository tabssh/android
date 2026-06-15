# Project Audit

Started: 2026-06-14

Comprehensive deep audit. Tracking >5 findings here per audit-agent rules.
Items are deleted when fully fixed and committed.

## Pass 1: Security
(no high-severity findings — secrets policy enforced via SecurePasswordManager / HypervisorPasswordStore; no plaintext password storage observed in DB writes; no hardcoded credentials in source.)

## Pass 2: Code Quality
- [ ] `ssh/connection/SSHConnection.kt` `_bytesTransferred` — `MutableStateFlow<Long>` is declared, exposed as `bytesTransferred`, and read in the `SessionSnapshot` at line 2119, but is never written. Every `ConnectionStats` snapshot reports 0 bytes; consumers in `TabManager`, `SessionPersistenceManager`, etc. receive a constant 0. Real fix requires intercepting JSch InputStream/OutputStream wrappers to count bytes; out of scope for this audit pass — track as future work or remove the field and its `ConnectionStats` column to stop misleading callers.

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

# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## UI/UX issues found during SSH error-dialog research (not yet fixed)

Found while researching the SSH error classifier fix (see chat/PR for that
work).

42. **Intermittent "eats one character to the left of cursor" bug — likely
    root-caused, needs on-device confirmation before fixing** — user-reported
    repro: type a long command (e.g. `tmux-new ai --dir ~/Projects/github/dfprivate`),
    hit Enter, type a new command (e.g. `mycommand`), hit Enter again — the
    last character of the new command is occasionally missing
    (`mycomman`), not consistently. `TerminalView.kt`'s `InputConnection`
    implementation always returns `""` from `getTextBeforeCursor()`/
    `getTextAfterCursor()` (:3683,3685) and `setComposingRegion()` is a
    hardcoded no-op returning `false` (:3729), so Gboard (and any IME that
    tracks composing state via those calls) has no accurate view of what's
    actually on screen. Gboard's autocorrect/predictive-text engine resets
    its internal composing state on Enter/submit; when its stale internal
    model disagrees with the (always-empty) surrounding text we report, it
    can issue a stray `deleteSurroundingText(1, 0)` — which `deleteBefore()`
    (:3711-3721) forwards as a real DEL byte (0x7F) to the shell, eating one
    already-committed character. This matches the bug's "only periodically"
    character exactly (fires only when Gboard's autocorrect state happens to
    desync, not every keystroke) and is consistent with it being an
    IME-interaction issue rather than a `TerminalView` logic bug per se —
    but the app's fake/empty `InputConnection` surface is what allows Gboard
    to get into that state. Needs confirmation via `adb shell dumpsys
    input_method` + Gboard logcat during a live repro before deciding
    between (a) a targeted mitigation (e.g. briefly ignore a
    `deleteSurroundingText` call that arrives within one input-event of a
    `finishComposingText`/Enter submit, since real backspaces come through
    `sendKeyEvent`/`commitText` instead), or (b) reporting upstream to
    Gboard if it reproduces with other terminal apps using the same
    minimal-`InputConnection` pattern (Termux uses an equivalent stub).
    **MITIGATION IMPLEMENTED, NOT YET DEVICE-CONFIRMED** — option (a) is
    now implemented in `TerminalView.kt`: a `suppressNextDeleteUntilMs`
    window armed in `finishComposingText()`/`performEditorAction()` (Enter
    submit) causes `deleteBefore()` to swallow exactly one stray
    `deleteSurroundingText()` call that arrives with an empty
    `composingText` inside that window, on the theory that a real user
    backspace comes through `sendKeyEvent`/`commitText` instead. This has
    not yet been confirmed against a live on-device repro (`adb shell
    dumpsys input_method` + Gboard logcat) — the original bug's actual
    disappearance is unverified.
    **PASTE PATH CONFIRMED CLEAN, TYPE/ENTER REPRO STILL UNCONFIRMED** —
    user ran 3 live multi-line (5+ line) paste tests on-device through the
    real TabSSH terminal, over an active session (paste from this chat
    session, from a second unrelated session, and from a website): all
    three came through complete with zero dropped characters. This
    confirms the `commitText` paste path is clean, but paste is a
    different code path from the bug's actual repro (type a command, hit
    Enter, type a new command, hit Enter again — the *typing-after-Enter*
    composing-state desync), which still has not been exercised on-device.
    No `adb` access to the user's device from this environment to drive
    that repro directly — remains on the user to run and report back.
    **LIVE OCCURRENCE DURING NORMAL TYPING — MITIGATION WAS TOO NARROW, NOW
    BROADENED** — user typed a multi-line message on-device, on the current
    devel build (which already includes the `suppressNextDeleteUntilMs`
    mitigation above), and confirmed the resulting text matched exactly what
    they typed except for one dropped character: "do this" came out as
    "dothis" (the space was eaten). User explicitly ruled out an ordinary
    typo (separately confirmed other irregularities in the same message were
    mobile-keyboard typos, unrelated). Root cause: the suppression window was
    only armed after `finishComposingText()`/`performEditorAction()` (Enter),
    but `commitText()` — the path Gboard uses to finalize a word at a space
    press (e.g. `commitText("do ", 2)`, or separate `"do"`/`" "` commits) —
    actively cleared the window and never armed it, leaving that path
    unprotected against the same fake-empty-buffer stray
    `deleteSurroundingText()` Gboard can issue right after any finalize, not
    only after Enter. A stray delete landing right after a space commit
    deletes exactly the space just sent, producing "dothis". Fixed in
    `TerminalView.kt`'s `commitText()`: it now arms the same one-shot
    suppression window after sending non-paste, non-modifier text, alongside
    the existing arm sites. **Still not confirmed against a live on-device
    repro** — no `adb` access from this environment to capture `dumpsys
    input_method`/Gboard logcat; remains on the user to retest and report
    back whether the "do this"-style drop recurs.
51. **`make check`'s Gradle task list never runs resource linking, so a
    broken `AndroidManifest.xml` `android:string` reference is invisible to
    the local pre-commit gate** — `check`'s invocation
    (`kspDebugKotlin compileDebugKotlin lintDebug testDebugUnitTest`) has no
    `assembleDebug`/`assembleDevel`/AAPT step. Discovered when the item-49
    duplicate-string-consolidation commit (`8bd3b158712f`) deleted 4 string
    keys still referenced by `AndroidManifest.xml` `android:label`
    attributes — `make check` passed locally, but CI's
    `development.yml` (`./gradlew assembleDevel --no-daemon`) failed with
    AAPT "resource ... not found" errors; fixed in commit `f415d8d22ad4`.
    Fix: add a resource-linking-capable task (e.g. `assembleDebug` or a
    lighter `processDebugResources`-only invocation, whichever is faster) to
    the `check` target's Gradle task list so this class of bug is caught
    before commit, not after push.


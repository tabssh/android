# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## UI/UX issues found during SSH error-dialog research (not yet fixed)

Found while researching the SSH error classifier fix (see chat/PR for that
work).

4. **VNC/SPICE/Console have no structured error classification/dialog at
   all** — unlike SSH's `SSHConnectionErrorInfo`, `VncDirectConnector.kt`,
   `SpiceLoader.kt`, `RfbClient.kt`, `ConsoleStrategy.kt`, and
   `HypervisorConsoleManager.kt` surface failures via raw-message Toasts or
   generic catch blocks with no classification. Larger scope than the SSH
   classifier fix (new feature work: shared error-info struct + per-protocol
   taxonomy), not a simple classifier fix.
5. **Raw exception text/class names leak into user-facing UI** —
   `ConnectionHistoryActivity.kt:88` and `KeyboardCustomizationActivity.kt:433`
   show raw `e.message.toString()` in toasts; `TabTerminalActivity.kt:3744`
   (VNC reconnect toast) does the same. `?: e.javaClass.simpleName` fallback
   pattern (raw class name shown to user) appears in `ConsoleStrategy.kt:80`,
   `HypervisorConsoleManager.kt:345,868`, `RfbClient.kt:421-422`,
   `MoshHandoff.kt:178`, `BulkImportParser.kt:138`. Needs friendly
   user-facing messages instead.
   **PARTIALLY DONE** — `ConnectionHistoryActivity.kt`,
   `KeyboardCustomizationActivity.kt`, and `TabTerminalActivity.kt`'s VNC
   reconnect toast now route through the new `ThrowableMapper`
   (`utils/ThrowableMapper.kt`). `RfbClient.kt`, `HypervisorConsoleManager.kt`,
   `MoshHandoff.kt`, and `BulkImportParser.kt` are still unfixed — confirmed
   none of the four have an Android `Context` in scope at the fallback site
   (`ThrowableMapper`/`ConsoleErrorClassifier` both need `context.getString()`),
   so converting them needs a small `Context`-injection refactor first
   (constructor param or a context-free string-table variant of the mapper).
   `SpiceLoader.kt` has no user-facing error strings — nothing to do there.
   `MainActivity.kt:315,479,589` (`main_load_hypervisors_failed`,
   `main_quick_connect_save_failed` x2) also still splice raw `e.message`
   and were left alone as out of the batch's named scope — `ThrowableMapper`
   is already imported in that file, so wiring these three in is a small
   follow-up once picked up.
40. **711 raw dp and 389 raw sp literals across 80% of layouts** —
    `values/dimens.xml` exists with 86 tokens (and `values-land`,
    `values-sw600dp`, `values-sw720dp` overrides), but 129 of 161 layouts
    still mix raw literals with token refs, so the responsive overrides only
    partially apply. Worst: `activity_sync_settings.xml` (51),
    `bottom_sheet_terminal_menu.xml` (37), `activity_sftp.xml` (32),
    `activity_import_export.xml` (30), `item_dashboard_host_card.xml` (24).
    AI.md PART 7 § Layout rules requires dimension resources. Fix: sweep
    into `dimens.xml`, starting with the touch-target and icon sizes
    (`min_touch_target`, `icon_size_medium` already exist). Related smaller
    bug: `activity_tab_terminal.xml:176-199`'s `bottom_action_bar` is
    pinned to `48dp` while its children are `drawableTop` buttons with both
    icon and label — the label clips. Also `activity_multi_host_dashboard.xml:55`,
    `fragment_performance.xml:457` and `activity_cloud_accounts.xml:91`
    hardcode `app:tint="@android:color/white"` on FAB icons, and
    `bg_bottom_sheet_handle.xml:12` hardcodes `#33808080`.
41. **Code-quality issues worth folding into the same pass** — (a)
    `TabTerminalActivity.kt:820-823` performs four unchecked casts on a
    `View.tag` (`as Triple<*,*,*>`, then `as ImageView`/`as TextView`) with
    no `as?` guard — a stale tag is a `ClassCastException`; (b)
    `AuthKeysFragment.kt:303` creates a
    `CoroutineScope(Dispatchers.IO + SupervisorJob())` *inside* a
    `lifecycleScope.launch`, never stores or cancels it, so an SSH connect
    in flight outlives the fragment; (c) `TabSSHApplication.kt:868` is a
    bare `catch (_: Exception) {}` with no logging around shutdown logic,
    and `:867,884` call `runBlocking` on the main thread; (d)
    `HostKeyVerifier.kt` wraps every host-key DB read in `runBlocking`
    (`:90,131,163,202,264,288,324,348,369`) — convert to real `suspend`
    functions; (e) `VpsMarkdownImportExport.kt:158,169,212` repeat
    `ThreadLocal.get()!!` three times — collapse to one accessor; (f)
    `TerminalPagerAdapter.kt:458,702` never cancel `holderScope` in
    `unbind()`; (g) duplicated WakeLock/WifiLock lifecycles in
    `SSHConnectionService.kt:876,908,933,956` vs
    `VncKeepAliveService.kt:189,203,216,235`, and the
    `(application as TabSSHApplication)` cast repeated in 10+ activities —
    both want a shared helper per AI.md PART 7 § Reuse Before Creating; (h)
    `HypervisorAccountAdapter.kt:64-65` is the only row adapter whose
    `itemView` has no click listener, so tapping the row does nothing; (i)
    `ConnectionEditActivity.kt:1973`/`:2254` is the last remaining
    `startActivityForResult`/`onActivityResult` pair (the rest of the app
    already uses `registerForActivityResult`, including `:143` in the same
    file); (j) `SSHConnection.kt:596-602`, `TabTerminalActivity.kt:3138-3140`
    and `:3999` are the only three hardcoded Toast literals left out of 502
    call sites — otherwise the i18n-in-code rule is met.
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
45. **Two loose ends found during a color/theme resources pass**
    — noted but left alone since neither was in that pass's named scope. (a)
    `values/themes.xml`'s `Theme.TabSSH` sets `colorErrorContainer` to
    `@color/error_dark`, a flat hex with no `values-night` override —
    unlike every other Material3 role in that style (`colorSurface`,
    `colorOutline`, …) it does not adapt between light/dark, so the
    "error container" background is the same strong red in both modes
    instead of a subtle day tint / dark tint pair like
    `status_error_container` already provides. Consider repointing it at
    `@color/status_error_container` instead of `@color/error_dark`. (b)
    `drawable/high_contrast_background.xml` (which draws
    `@color/high_contrast_background`/`@color/high_contrast_foreground`)
    has zero references anywhere in `res/layout` or `java` — predates this
    batch, not created by it. Confirm it's genuinely unused and delete it
    (and the two colors, if nothing else picks them up) or wire it in
    wherever it was meant to be used.
49. **~220 additional duplicated string values remain in `strings.xml`
    beyond the 45 already consolidated** — found while doing that
    consolidation pass. The full scan found ~265 groups of identical string
    values; only the clearest 45 (exact-duplicate action/dialog labels,
    format-string templates, and `_hint` suffixed fields with an
    unambiguous canonical name) were consolidated in that pass. The
    remaining ~220
    groups were deliberately left alone because they are short generic
    words/phrases (e.g. single words, numbers, punctuation-only strings)
    or symbols where two strings share text incidentally rather than
    semantically, and forcing them onto one shared key risks an
    unrelated screen's copy changing when only one caller's wording
    should. Fix: re-run the duplicate-string scan, review the remaining
    groups case by case, and consolidate only the ones that are
    genuinely the same semantic string (not just coincidentally equal
    text) referenced from multiple places.
50. **Item 40's raw dp/sp literal sweep is only partially complete** — the
    five worst-offender layouts (`activity_sync_settings.xml`,
    `bottom_sheet_terminal_menu.xml`, `activity_sftp.xml`,
    `activity_import_export.xml`, `item_dashboard_host_card.xml`) plus a
    broad mechanical pass for four highly-recurrent patterns
    (`iconSize="20dp"`, `textSize="11sp"`, `minHeight="48dp"`,
    `iconPadding="12dp"`) were converted to `@dimen` references, and 12 new
    tokens were added to `dimens.xml`. Recurring `8dp`/`13sp` literals were
    found across roughly 27-28 more layout files but only fixed in
    `activity_import_export.xml`. Fix: re-run the item-40 sweep across the
    remaining `app/src/main/res/layout/*.xml` files not yet covered,
    reusing the tokens already in `dimens.xml` before adding new ones.


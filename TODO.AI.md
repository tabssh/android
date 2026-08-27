# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## UI/UX issues found during SSH error-dialog research (not yet fixed)

Found while researching the SSH error classifier fix (see chat/PR for that
work).

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

52. **Terminal content gets cut off / scrolls out of view when the system
    IME (Gboard) is open — confirmed recent regression, root cause not yet
    fully confirmed.** Screenshots (same Claude-Code-over-SSH session, with
    vs. without the Android keyboard open) show a 3-item multi-line message
    fully visible with the keyboard closed, but only the first item
    (truncated) visible with it open — items 2 and 3 are entirely off-screen,
    which a pure "fewer visible rows" theory doesn't fully explain (scroll
    offset `scrollYf` in `TerminalView.kt` is untouched by resize, so if the
    user was scrolled to bottom the newest lines should stay visible, not the
    oldest). One confirmed-but-conditional contributing regression was found
    and fixed in a separate commit: `activity_tab_terminal.xml`'s
    `bottom_action_bar` height was silently changed 48dp→72dp by the item-40
    dp-literal→dimen sweep (`e540691eacfc`), eating extra vertical space from
    the terminal grid — but that bar is only visible when Settings → General
    → "Show bottom nav bar" is enabled (off by default), so it may not be the
    actual cause of the reported screenshots. Ruled out via git-history
    review of the last 2 weeks: `MultiRowKeyboardView`/`KeyboardRowView` row
    height and row count defaults (unchanged), the two recent keyboard-bar
    feature commits (`9acaadd3623d` auto-repeat, `e37556ac6467` modifier
    lock — no height/padding diffs), `TerminalView.kt`'s resize-debounce/
    `gridTop`/`updateGridSize` system (unchanged since Aug 7–11, predates the
    regression window), and `TermuxBridge.resize()` (unchanged logic, only
    added log-throttling).

    Analyzed a real on-device debug log (in-app `debug_logging_enabled`
    export, no adb needed) covering one keyboard-hide→show cycle: both
    `TermuxBridge.resize()` and `TerminalView`'s `updateGridSize()` fired and
    completed cleanly in each direction (`90x37` keyboard hidden → `90x27`
    keyboard shown, columns unchanged), each followed by an
    `onScreenChanged - scheduling redraw` line ~120–200ms later. This rules
    out "resize doesn't fire"/"resize gets dropped" — the client-side resize
    mechanics work as designed on the reporting device. A 10-row shrink for
    Gboard's typical height is not abnormally large on its own. The debug
    log has no rendered-pixel content, so it cannot confirm or rule out
    whether the specific "item 1 truncated, items 2/3 fully invisible"
    symptom is a genuine TabSSH client-render bug vs. the remote CLI/shell's
    own reflow of multi-line output to fit a shorter terminal (normal
    curses/TUI behavior, would look identical). Zero `W/`/`E/`-level log
    entries and no other anomalies (reconnect loops, repeated errors) found
    anywhere else in the ~1300-line log across all tags.

    Still blocked on: confirmation of whether "Show bottom nav bar" is
    enabled on the affected device (the one concrete, git-history-confirmed
    regression touching this vertical-space budget in the timeframe) — and,
    if that's not it, a way to compare actual rendered terminal content
    before/after the keyboard opens (screen recording or a second log
    export with the exact repro content shown), since debug-log timestamps
    alone can't distinguish a client render bug from expected remote-app
    reflow.


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
    added log-throttling). Blocked on an on-device repro with logcat
    (`Logger.d("TerminalView", "Terminal resized: ...")` lines) while
    toggling the keyboard, and confirmation of whether "Show bottom nav bar"
    is enabled on the affected device — needs the user to reproduce with
    debug logging or confirm that setting before this can be root-caused
    further.


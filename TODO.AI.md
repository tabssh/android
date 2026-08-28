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

    **Fixed** — added `processDebugResources` to the `check` target's Gradle
    task list in `Makefile` (lighter than `assembleDebug`, still runs AAPT
    resource linking so a missing `android:string`/`android:label` reference
    now fails locally instead of only in CI).

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

    `TerminalView.kt`'s two resize/redraw debug-log lines now also emit
    `scrollYf`, `visualRows`, and `gridTop` (resize line) / `rows`, `cols`,
    `scrollYf` (redraw line) — added so a future repro log can show directly
    whether `scrollYf` stays at 0 (bottom) through the keyboard-open
    transition or drifts.

    **Second debug log analyzed (post-field-addition, build `2201ae2`),
    covering a full ~2-minute session with two keyboard show/hide cycles
    (37↔27 rows, cols constant at 90): `scrollYf=0.0` on every single one
    of ~35 redraw lines and every resize line, with no exceptions.** This
    conclusively rules out scroll-offset drift as the cause — the terminal
    never leaves the bottom/live position across either keyboard
    transition. Combined with the earlier finding that resize always
    completes cleanly in both directions within ~170ms, the client-side
    resize/scroll mechanics are now fully exonerated: no dropped resize,
    no scroll drift, zero `W/`/`E/`-level entries anywhere in either log.

    **User confirmed "Show bottom nav bar" is OFF on the affected device
    — the `bottom_action_bar` regression (`83071e7b3cb0`) is ruled out as
    the cause of this report.** With both the resize/scroll mechanics and
    that dimen regression exonerated, every code-level lead this session's
    investigation could find without pixel data is exhausted. Remaining
    candidate, not confirmable from a text log: the remote shell/CLI's own
    SIGWINCH reflow of multi-line output to fit a shorter terminal, which
    is normal curses/TUI behavior indistinguishable from a client bug in a
    text log. Next step needs a screen recording of the actual repro (the
    3-item multi-line message, keyboard toggled) to see what is actually
    drawn — no further text-log analysis can distinguish a real client
    render bug from expected remote-app reflow.

    **User reports the symptom is no longer reoccurring** as of this note
    — closing active investigation for now. No specific commit in this
    repo's history was identified as "the fix"; the most likely
    explanation is the conditional `bottom_action_bar` 48dp→72dp
    regression fix (`83071e7b3cb0`) resolved it for this device even
    though "Show bottom nav bar" was reported off at the time, or the
    symptom was remote-app SIGWINCH reflow (never a TabSSH client bug) and
    isn't reproducing under current conditions. Reopen with a fresh debug
    log (and ideally a screen recording) if the symptom returns.

53. **New feature request: built-in session video recorder (mp4 + asciinema
    `.cast`, dual format), with upload capability, saved to the device's
    `Videos/TabSSH` directory.** Distinct from the existing Session
    Transcript feature (`terminal/recording/SessionRecorder.kt`, renamed
    "Recording"→"Transcript" in the UI this session to avoid the naming
    collision with this new feature) — that recorder captures the raw
    text/ANSI byte stream, not pixels or frame timing. This is a genuinely
    new capability, not a small addition:
    - Needs `MediaProjection`/`MediaRecorder` for actual screen capture
      (mp4 output), which requires a foreground service (user-visible
      recording notification, Android screen-capture consent flow) and
      careful lifecycle handling across tab switches/app backgrounding.
    - The asciinema `.cast` format is a separate, simpler writer — JSON
      event stream of terminal output + timing, likely derivable from the
      same byte stream `SessionRecorder` already taps, but is a new file
      format/writer, not reuse of the existing transcript writer.
    - Producing both formats from one "start recording" action means two
      independent capture paths running concurrently and terminating
      together.
    - Save location: `Videos/TabSSH` under the device's public Movies/Videos
      directory (MediaStore-scoped on API 29+, matching how other
      user-facing exports in this app already handle scoped storage).
    - Upload: presumably via the same `PasteProviderFactory` paste
      infrastructure already used for logs/transcripts, though a video/cast
      file may not fit typical paste-service size/content-type
      expectations — needs its own design pass (possibly a different
      upload mechanism for the mp4 vs. the `.cast` file).
    - Explicitly deferred: user chose to log this for later planning rather
      than build or plan it now (`AskUserQuestion`, this session).

    **Fixed** — implemented per plan (`.claude/plans/moonlit-giggling-boot.md`):
    `SessionRecordingService` (MediaProjection → VirtualDisplay → MediaRecorder
    foreground service, mp4 to MediaStore-scoped `Movies/TabSSH`) +
    `AsciinemaCastWriter` (SSH-only asciicast v2 `.cast` writer, wired through
    a new independent `TermuxBridge.castRecorder`/`SSHTab.castWriter` field so
    it never contends with the existing Transcript feature's `outputRecorder`)
    + `VideoRecordingStorage` (MediaStore/legacy-file save helper). UI: a
    "Record Video…" bottom-sheet action works for any visible tab type
    (mp4-only for non-SSH tabs; SSH tabs get a "Video only" / "Video +
    Terminal Cast" chooser, pre-selectable via a new setting), MediaProjection
    consent via `registerForActivityResult`, auto-stop-with-toast on tab
    close or external projection revocation, and a post-stop "Share" dialog
    reusing `SFTPActivity.shareFile()`'s `ACTION_SEND` + `FileProvider`/
    MediaStore-Uri pattern for each produced file. Settings: mp4 quality
    preset (Low/Medium/High bitrate) and "include terminal cast by default"
    added to the existing recording preferences category. `make check`
    (Docker toolchain) passes clean: compile + `lintDebug` + unit tests +
    `processDebugResources`.

54. **Multi-line paste into the terminal drops/splits lines after the
    first — reported again this session, still unfixed.** Repro source
    this session: `/tmp/pasted.txt` containing 4 lines (`Demo`,
    `func hello() string {`, a tab-indented `return "hi"`, `}`) pasted as
    one clipboard block; only `Demo` (the first line) was actually
    pasted — the remaining 3 lines were each delivered as their own
    separate input instead of arriving together as part of the same
    paste. Needs investigation into wherever the app's paste path
    (terminal input / bracketed-paste handling) splits clipboard content
    on newlines instead of sending it as a single paste payload. User
    flagged this as a recurring/known issue ("we have that damn paste bug
    still"), so check prior sessions' notes/commits for earlier attempts
    at this before re-diagnosing from scratch.

    **Fixed** — root cause: `TermuxBridge.write()`/`TerminalEmulator`'s
    write path wraps every single `write()` call in its own async
    write/lock/flush unit, and the original `pasteText()` in both classes
    issued the bracketed-paste open marker (`ESC[200~`), the pasted body,
    and the close marker (`ESC[201~`) as 2-3 separate such calls with no
    guaranteed relative ordering/atomicity. This let the remote's
    bracketed-paste parser see a broken/interleaved
    `ESC[200~...ESC[201~` block, falling back to treating embedded line
    breaks as literal Enter presses — submitting only the first line and
    misrouting the rest as separate input, matching the reported repro.
    Fix: `TermuxBridge.pasteText()` and `TerminalEmulator.pasteText()`
    now fuse the open/close markers into the first/last content chunk so
    a paste that fits in one chunk (`PASTE_CHUNK_SIZE` = 4096 chars,
    covering the reported repro) goes out as a single atomic write; only
    pastes exceeding that size still need multiple writes, and even then
    each marker rides along with real content instead of standing alone.
    `make check` (Docker toolchain: compile + `lintDebug` +
    `testDebugUnitTest` + `processDebugResources`) passes clean.

    **Follow-up (same session)** — user asked to remove the
    `PASTE_CHUNK_SIZE` cap entirely and always send the whole paste in one
    write, regardless of size (no protocol/window-size reason for the
    4096-char cap, it was an arbitrary chunking limit). Both
    `pasteText()` implementations now build the marker+body+marker
    payload in one `buildString`/`writeString` (or `stream.write`) call
    with no chunking loop; `PASTE_CHUNK_SIZE` removed from both files.
    While doing this, found and fixed a second, unrelated bug introduced
    by the same-session marker-fusion commit (`3350d1735e32`):
    `TerminalEmulator.kt`'s `BRACKETED_PASTE_START`/`END` constants
    already embed the raw ESC byte, but the marker-fusion code prepended
    an *additional* ESC before them, double-prefixing every bracketed
    paste on the local (non-SSH-bridge) terminal path — fixed by using
    the constants directly instead of re-prefixing. `make check` passes
    clean.


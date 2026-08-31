# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

55. **Copy/paste inside an SSH terminal session drops the word-boundary
    space at soft-wrap points, joining words together** — reported with a
    repro: user selected and copied multi-line wrapped text rendered inside
    a TabSSH SSH session, then pasted it back into an SSH session; the
    result had words fused across former line-wrap boundaries (e.g.
    "but a" + "running" pasted as "but arunning", "specification" +
    "already-running" fused similarly), with no missing/extra newlines —
    the opposite symptom from the already-fixed item-54 bug (that one
    split single-line pastes into multiple Enter-submitted lines; this one
    silently drops the separator space where a line was soft-wrapped).
    Waiting on a user-provided screenshot (original wrapped text vs. what
    was actually pasted) to confirm exact repro before making code changes.

    Investigation so far (not yet fixed): SSH sessions extract selected
    text via the Termux terminal-view library's `TerminalBuffer
    .getSelectedText()` (called from `TerminalView.kt` `getSelectedText()`,
    ~line 3081, and `TermuxBridge.kt` ~lines 1160/1176) — a third-party
    dependency, not this project's own code. Termux's implementation joins
    soft-wrapped rows with no separator by design (relies on the wrapped
    row's last real column already holding the space character, not
    padding). `TerminalView.kt` has a *separate*, hand-rolled wrap-aware
    joiner used only for URL detection (`joinRowText()`/
    `buildWrappedWindowText()`, ~lines 2061–2093) that explicitly documents
    the "continuation rows keep trailing spaces, last row gets trimmed"
    convention — the actual copy-to-clipboard path
    (`getSelectedText()`/`buffer.getSelectedText(...)`) does not use this
    joiner and instead trusts the Termux library's own row-join behavior.
    Suspect area: either the Termux library row buffer is losing/not
    storing the boundary space for genuinely wrapped prose (as opposed to
    a hard character-wrap), or something upstream of the library call is
    trimming trailing whitespace per row before the library sees it. Needs
    the screenshot repro to confirm which, then a fix (likely reusing
    `joinRowText()`'s trailing-space-preservation approach for the actual
    selection-copy path, not just URL detection) plus a `make check` pass.

    Live repro sample (user-provided, kept byte-for-byte as pasted — the
    formatting/spacing artifacts below ARE the bug evidence, do not
    "clean up" this block):

    ```
    no screen shot yet, still rebuilding server, but you will see from screenshot copied text vs what you get from.the paste:
    ● One item pending — #55, the word-join paste bug (line-wrap boundary spaces getting  dropped on copy/paste). It's explicitly blocked: waiting on a user-provided screenshot  (original wrapped text vs. what actually pasted) to confirm the exact repro before  making any code changes. No other items in the file.  Do you have that screenshot, or want me to proceed on the investigation notes alone?✻ Baked for 14s                                                   new task? /clear to save 112.9k tokens
    ```

56. **`scrollByNotches()`'s alt-screen arrow-key branch sends bytes with no
    ESC prefix** — `TerminalView.kt` ~line 3692-3693 (left-edge wheel-zone
    handler, `scrollByNotches()`) builds the up/down key bytes as
    `"OA".toByteArray()` / `"OB".toByteArray()` — the literal two-character
    text "OA"/"OB", not an SS3 arrow-key escape sequence. The equivalent
    branch in `onScroll()` (main touchpad-drag path, ~line 2775-2776)
    correctly uses `"\u001bOA".toByteArray()` / `"\u001bOB".toByteArray()`
    (ESC O A / ESC O B). `TermuxBridge.write()` (`TermuxBridge.kt` ~line
    1061) writes the raw bytes as-is with no ESC injection, so this path
    appears to send literal "OA"/"OB" text rather than a real arrow key.
    Found incidentally while instrumenting all three swipe zones
    (left/wheel, right/scrollbar-thumb, middle/onScroll) with diagnostic
    `Logger.d("TerminalView.Scroll", ...)` calls for item-55-adjacent
    touchpad-scrollback bug work — not yet fixed, since the user has
    separately confirmed the left-zone arrow-key behavior currently "works"
    for them and asked this call site not be touched pending real log
    evidence. Needs: confirm via the new diagnostic logging whether this
    branch actually fires in the user's repro cases, and if so fix it to
    match `onScroll()`'s `"\u001bOA"`/`"\u001bOB"` byte sequences.

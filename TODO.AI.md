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

56. **Sibling repos' Room-schema-version cross-references are stale** —
    found incidentally while adding IDEA.md's "Must be compatible with"
    section documenting cross-platform compatibility with `../desktop` and
    `../web`. `../desktop/IDEA.md` and `../web/IDEA.md` both cite the
    Android Room schema as "currently v37" (desktop also cites
    "`../android/AI.md §9.1`"/"`§8.4`" for the sync wire format header
    layout and migration chain, section numbers that do not exist in this
    project's current `AI.md`). The actual schema version, verified
    directly from `storage/database/TabSSHDatabase.kt`'s `@Database`
    annotation, is v27, not v37 — this project's own IDEA.md and README.md
    have been corrected to v27 as part of this same change. The two
    sibling repos' stale v37/§9.1/§8.4 references are out of this
    project's write scope (a different repo) and need a matching
    correction there — either update them to v27 (if they were just never
    kept in sync) or, if `../desktop`/`../web` genuinely are ahead of what
    is in this repo's `main` right now, reconcile which number is actually
    correct before editing either side.

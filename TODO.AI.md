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


56. Real scrollback over Mosh via a mosh-client patch (Blink Shell approach).
    Mosh never feeds terminal scrollback — it repaints by cursor-addressed
    overwrite (mosh issue #122), so over Mosh TabSSH falls back to
    xfce4-style arrow-key swipe emulation on the alt screen. Blink Shell
    solved this properly by patching mosh to expose scrolled-off lines to
    the host terminal. TabSSH cross-compiles its own mosh-client in
    deps/prereqs/mosh/, so the same patch route is available. Investigate Blink's
    mosh fork and estimate the patch surface before committing to it.

57. Password/biometric TTL discrepancy between IDEA.md and code. IDEA.md
    claims "Biometric unlock for stored passwords with configurable TTL",
    but SecurePasswordManager deliberately removed TTL expiry (code comment
    at the retrieval path: Keystore keys are device-unlock-bound; the old
    24h expiry silently deleted saved passwords) and biometric keys use
    setUserAuthenticationValidityDurationSeconds(0) — re-auth on every use,
    no window. The security_password_ttl_hours preference still exists in
    PreferenceManager and is exported by backup/sync but has no runtime
    effect and no Settings UI. Decide: (a) accept no-TTL as the design —
    update IDEA.md (drop "configurable TTL", record it under Accepted
    design decisions) and remove the dead preference from PreferenceManager,
    BackupExporter/Importer, and SyncDataCollector/Applier; or (b) keep the
    spec — implement TTL enforcement and surface the setting in Settings.

# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

59. **Optional: per-call-site dimens migration for activity `dp()` helpers**
    — HostDetailActivity, WhatsNewActivity, ThemeEditorActivity,
    ConnectionHistoryActivity, and PinLockActivity each keep a generic
    private `dp(value)` conversion helper called with many literal dp
    values. Judged reuse infrastructure (same class as DialogFields), not
    an AI.md violation — converting every call site to dimens.xml tokens
    is a large diff with no visual change. Do only if the user asks.

56. **RESOLVED — do not pursue.** Research (web + local source, 2026-09-03)
    found the premise false: Blink Shell never shipped a mosh scrollback
    patch. `blinksh/mosh` is a plain iOS-port fork (main loop, threading,
    socket fixes — nothing in terminaldisplay.cc/terminalframebuffer.cc);
    Blink v0.716 actually *disabled* scrolling during mosh sessions, and
    their maintainers (Discussion #1933) recommend server-side tmux/screen
    with mouse mode — same as upstream mosh issue #122 (open since 2012,
    no fork or PR resolves it). TabSSH's build downloads unmodified
    upstream mosh 1.4.0 (no vendored source tree), so there is nothing to
    port. Decision: keep the existing xfce4-style swipe emulation, treat
    lack of native mosh scrollback as an accepted upstream limitation, and
    steer users to tmux/screen (which the expanded multiplexer remote
    commands now make one tap away). A from-scratch mosh patch (scrollback
    ring in Framebuffer::scroll() + client consumer) remains possible but
    is high-risk (prediction-engine/state-sync corruption) — revisit only
    if users demand it.

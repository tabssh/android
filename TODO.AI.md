# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

57. **script-lint UUOC violations in `scripts/`** — the 2026-09-03 lint-gate
    run flagged the same anti-pattern in 10 scripts: an unnecessary
    `dirname` command call where bash parameter expansion (`"${path%/*}"`)
    should be used. Files/lines: clean-build.sh:12 · dev-shell.sh:11 ·
    fetch-fonts.sh:22 · fetch-mosh-binaries.sh:21 · fetch-spice-libs.sh:22 ·
    fetch-tor-binaries.sh:23 · install-to-device.sh:14 ·
    pre-commit-check.sh:11 · prepare-fdroid-submission.sh:12 ·
    start-test-sshd.sh:20. Pre-existing, unrelated to in-flight work;
    fix in a dedicated cleanup commit.

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

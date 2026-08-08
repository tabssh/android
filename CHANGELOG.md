# Changelog

All notable changes to TabSSH are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Security

- **Tasker integration is now opt-in (default OFF)** — the setting previously defaulted to ON, which was harmless while the only entry point was the signature-gated `TaskerActionReceiver`, but the new Locale plugin fire receiver is exported without a permission (the protocol requires it), so on a fresh install any app on the device could have driven SSH sessions with no user action. Existing installs keep whatever the toggle is already set to
- **Tasker COMMAND_RESULT broadcasts no longer carry terminal screen content by default** — the event is an implicit, unprotected broadcast (the public Tasker contract requires it), so any installed app could register for it and read whatever was on screen — including credentials. The `result` extra now mirrors the new `status` extra (`completed`/`sent`) by default; a new "Include Command Output in Broadcasts" toggle in Tasker settings (default OFF) restores the old payload for users who accept the exposure. Screen content is not even captured unless the toggle is on
- **Locale plugin bundles must carry a connection ID, not just a name** — a fired bundle identifying its target by profile *name* is now rejected. Names are guessable, so accepting them let any app aim at a profile blind; IDs are opaque UUIDs that only TabSSH's own config screen hands out. Bundle validation is also fully exception-guarded, so a malformed payload from a host app can no longer crash the receiver or the config screen

### Fixed

- **Docker sessions now scale to many hosts** — the session manager serialized every host behind one global lock (opening host B waited for host A's whole SSH connect + transport detection) and cached sessions were kept forever: a dead SSH connection left its local socket relay listening and the session was retried as live, and heavy multi-host use accumulated one open SSH connection per host with no bound. Hosts now connect in parallel (per-host locking), the cache is LRU-capped at 16 sessions, sessions idle for 10 minutes are disconnected by a background sweep, and evicted or dead sessions close their relay and release their monitoring-only SSH connection (never a user terminal session sharing the same profile)
- **Docker dashboard spun forever once the API transport connected** — the first request on every connection succeeded, but any follow-up request reusing an OkHttp keep-alive connection through the SSH socket relay hung until the 60-second read timeout, so the dashboard never finished loading. The API client no longer reuses connections: every request gets a fresh local connection and its own SSH channel to the daemon socket — the pattern that already worked reliably
- **Docker Engine 29 hosts rejected every API call after version negotiation** — Docker 29 raised the daemon's minimum API version to 1.44, above this client's 1.43 ceiling, so every versioned request would come back HTTP 400 "client version is too old". Negotiation now honors the daemon's advertised `MinAPIVersion` and lifts the negotiated version to it when the ceiling falls below
- **Docker API transports always failed on IPv6-preferring devices (e.g. IPv6-only mobile carriers)** — the local relay that bridges the Engine API over SSH bound to the platform's "loopback address", which is `::1` when the device prefers IPv6, while the API client always dialed `127.0.0.1` — every probe was refused within milliseconds, so both API tiers failed and the host silently fell back to the slower CLI transport on every connection. The relay now binds explicitly to IPv4 loopback and logs the actual bound address
- **Docker screens could spin forever after a network change or on a slow command** — the SSH exec helper's timeout only bounded its polling loop; when the deadline expired with the remote command still running it fell into a blocking no-timeout read, which never returns on a connection killed by a WiFi/mobile handover and otherwise blocks until the command finishes anyway. On timeout it now takes whatever output is buffered and reports the timeout instead of hanging, so the dashboard shows an error dialog instead of an endless spinner
- **Docker host connections no longer show up as active SSH sessions** — opening the Docker manager (or testing a host in the editor) registered its SSH connection like a user terminal session: it started the foreground SSH service with wake/WiFi locks, fired the "Connected to …" alert, and lit the green connected dot on the linked connection. Docker-owned connections are now opened as monitoring-only, the same mechanism the multi-host dashboard already uses — no foreground service, no session notification, no connected indicator. A real terminal session to the same server is still reused and keeps its normal visibility
- **Docker hosts stuck on the CLI fallback transport never retried the faster API tiers** — once a host was detected as `cli_exec` that result was persisted and every later session fast-pathed straight to it, so the streamlocal/dial-stdio tiers were never attempted again (and the per-tier failure reasons never appeared in the debug log). A stored `cli_exec` now re-runs the full detection ladder on every new session: the host upgrades automatically the moment a better tier starts working, and the reasons the API tiers fail are logged each time. Hosts detected on an API tier keep the fast path
- **Multiplexer session names with a single quote in them failed to attach** — the attach/create commands stripped `'` from the name instead of escaping it, so a session genuinely named `dev'box` was listed correctly by the picker but attached as `devbox` and failed with "session not found"; names are now POSIX-escaped, which both preserves them and keeps every shell metacharacter inert
- **libvirt reported successful operations as failures when a name contained "failed"** — virsh success/failure was decided by scanning the whole output for `error:` or `failed`, but virsh echoes the object's own name back, so a domain or snapshot named e.g. `failed-boot` broke start/stop/reboot/reset and permanently broke snapshot listing for that domain; the check is now anchored to virsh's actual `error:` line prefix
- **Proxmox snapshot revert/delete built a malformed URL for names with special characters** — the snapshot name was interpolated into the REST path un-encoded, so `/`, `?`, `#`, or a space silently retargeted the request; path segments are now percent-encoded
- **Proxmox snapshot-delete failures showed only an HTTP status code** — the error body is now unwrapped the same way it already was for other calls, so the server's actual reason is shown
- **VMware guest shutdown/restart blamed VMware Tools for every failure** — an auth failure, TLS error, or network timeout was reported as "the VM must be powered on with VMware Tools running"; that hint is now only added when the fault actually says so
- **ASK-mode multiplexer picker dialog issues** — the picker leaked its window when the activity was destroyed or the user switched tabs (a dialog built for the previous tab stayed on screen and drove that tab), could show on a stopped activity, and permanently discarded the pending request when a typo failed name validation instead of re-prompting
- **Snapshot name validation in the Proxmox and VMware create dialogs** — Proxmox now checks the config-ID format it requires up front rather than round-tripping to a generic failure, and VMware trims and rejects an empty name; both now match the validation libvirt already did
- **Quick-connect (ephemeral) sessions crashed the background session saver** — the periodic session-state save tried to insert a tab-session row for profiles that were never persisted to the connections table, hitting a foreign-key constraint on every save cycle; ephemeral profiles are now skipped, matching what the tab manager's own save path already did
- **VNC/SPICE console tabs took no keyboard input** — the keyboard toggle only ever targeted a terminal view, so on a graphical console tab it did nothing, and custom keyboard-bar keys (ESC, arrows, F-keys, …) were silently dropped; the toggle now attaches the soft keyboard to the active VNC/SPICE view, bar keys are translated to X11 keysyms (VNC) or PS/2 scancodes (SPICE) and sent to the session, and latched CTRL/ALT/SHIFT modifiers bracket the next key exactly like they do in a terminal
- **Bottom terminal row (prompt / tmux status line) partially cut off** — changing the terminal font, typeface, or line spacing updated the cell height without recomputing the row count (and the font-size path never resized the Termux PTY, only the legacy emulator — with rows/cols transposed), so the grid could end up taller than the view and clip its last row; all grid inputs now funnel through one recompute that resizes the PTY, buffer, and emulator together, and any transient overflow now clips at the top so the bottom row stays visible (VNC/SPICE consoles are unaffected — they uniformly fit-scale the framebuffer on every resize)
- **Odd blank band between the terminal's last row and the custom keyboard bar** — the terminal view floors its height to whole character rows, and the leftover slack (up to one row, varying with window height and IME state) was drawn as empty background below the last row, showing as a gap above the keyboard bar; the character grid is now bottom-aligned so the last row sits flush against the keyboard bar and the slack merges into the top edge instead
- **VNC/SPICE console tabs never reacted to their session ending** — a dead VNC/SPICE session left the tab frozen on its last frame with no feedback, forever; console tabs now follow the same close policy terminals use for exit codes: an orderly server close (VM shut down, server ended the session — RFB EOF at a message boundary, native SPICE disconnect) auto-closes the tab like `exit`, while an abrupt drop (socket reset, mid-stream cut, protocol or native error) shows a "Connection closed" dialog — with a one-tap Reconnect for saved VNC hosts, or Close/Keep for hypervisor consoles whose sessions can't be re-established without their manager screen; user-initiated closes are never second-guessed
- **VNC/SPICE console tabs had no long-press menu** — a long hold on a console tab now opens the same session context menu terminal tabs have (a plain long-press still right-clicks in the remote session); "Select Text…" and "Copy Screen" are dropped on graphical consoles since there is no text buffer, and Paste types the clipboard into the remote session

- **Swiping in a mosh session at a plain shell prompt typed literal arrow keys (`^[[A`/`^[[B`) into the shell** — mosh pins the terminal to the alternate screen for its entire lifetime, so the alt-screen swipe fallback (arrow-key forwarding meant for vim/less/man/htop) fired at the prompt and injected keystrokes; the fallback in `TerminalView.onScroll()` is now gated on cursor keys being in application mode (DECCKM/smkx), which full-screen terminfo apps enable at startup and a shell prompt never does — swipes at an alt-screen prompt are swallowed instead of typed, and the forwarded sequences are always the SS3 (`ESC O A/B`) variants since the gate guarantees application mode

### Changed

- **The scrollback thumb is now a persistent desktop-style scrollbar (konsole/xfce4-terminal), not a transient overlay** — the auto-fading thumb introduced right after 1.0.0 never appeared in mouse-tracking/alt-screen sessions and vanished 1.2 s after every scroll; `TerminalView` now always draws a full-height right-edge track (faint) with a thumb that maps the terminal's local scrollback position, and the thumb fills the whole track when there is nothing to scroll back through (empty scrollback, alt-screen app, mosh) — visible but inert, exactly like a desktop terminal with an empty history; dragging the thumb scrolls only the terminal's own scrollback and still yields to horizontal movement, so left/right tab-switch swipes are unaffected; all fade timers and show/hide state are gone
- **Swipe up/down now scrolls like a desktop mousewheel: 3 lines per line-height of finger travel** — all three swipe paths in `TerminalView` (wheel-event forwarding under remote mouse tracking, arrow-key forwarding on the alternate screen, and smooth pixel scrolling of local scrollback, including fling velocity) are geared by a shared `WHEEL_STEP_LINES = 3` constant matching the desktop wheel default, replacing the previous 1:1 line-per-line mapping

### Added

- **Per-host Docker update-check settings** — each Docker host now has a "Check for image updates" toggle and an optional interval-in-hours override in the host editor (blank = the twice-daily default); the background worker honors both, checks at most 2 hosts concurrently instead of serially, and records the last completed check per host so a fresh worker run doesn't re-check hosts that aren't due yet
- **Docker hosts can now use a custom SSH endpoint** — instead of linking a saved SSH connection, a Docker host can carry its own address, port, username, and auth (password, SSH key, or saved identity), keeping Docker infrastructure separate from the connection list the way hypervisors already are. The password is stored only in the Android Keystore, never in the database. The host name is now optional and defaults to the linked connection's name (or the endpoint hostname for custom endpoints). Enter Terminal, transport testing, and watchtower-style update checks all work for custom endpoints; their sessions and `docker exec` tabs stay out of the Active Sessions strip, recents, connection stats, and session restore
- **VNC and hypervisor-console tabs now appear in the Connections tab's "Active Sessions" strip** — the strip was SSH-only; it now lists every live tab (SSH, VNC, console) as a chip with the tab's title (VNC host name / VM name) and the same connection-state dot, and tapping a chip jumps straight to that tab, exactly like SSH chips do
- **VM snapshots on every hypervisor backend** — Proxmox, VMware, and libvirt/QEMU now have snapshot create/list/revert/delete to match XCP-ng/Xen Orchestra: long-press a VM row in any hypervisor manager to open the snapshot dialog (same UI as XCP-ng's). Proxmox uses the REST snapshot endpoints for both QEMU VMs and LXC containers; VMware uses vim25 SOAP task calls (`CreateSnapshot_Task`/`RevertToSnapshot_Task`/`RemoveSnapshot_Task`, no memory capture); libvirt shells out to `virsh snapshot-create-as`/`snapshot-revert`/`snapshot-delete` over SSH
- **"Ask" multiplexer mode now actually asks** — a connection whose tmux/screen/zellij mode is set to Ask used to silently behave like Auto-Attach; it now lists the sessions found on the remote right after connect and shows a picker — attach to any existing session, create a new named one, or skip and keep a plain shell
- **Tasker/Locale plugin support** — TabSSH now implements the standard `com.twofortyfouram` Locale plugin protocol, so Tasker, Locale, and Automate list a "TabSSH Action" plugin: pick Connect/Disconnect/Send Command/Send Keys and a connection profile inside TabSSH's own config screen, and the host app replays it on trigger; fired bundles are strictly validated and still honor the Tasker settings gates (integration toggle, require-unlock, per-connection allowlist)
- **VMware guest shutdown and guest restart** — the VM row's Stop action now offers a clean "Shutdown Guest" (vim25 `ShutdownGuest`, needs VMware Tools) alongside hard "Power Off", and the reboot button is now a clean "Restart Guest" (`RebootGuest`) with the hard reset kept as "Hard Reset"
- **Full Docker host management, Portainer-class** — a new "Docker Hosts" section under the Hypervisors tab manages containers, images, volumes, networks, and compose stacks on any Docker host reachable over an existing SSH connection. Transport is hybrid: the Docker Engine REST API over an SSH unix-socket forward of `/var/run/docker.sock` (JSch `direct-streamlocal@openssh.com`, or a remote socat/nc bridge when the server denies streamlocal forwarding), with automatic fallback to `docker … --format '{{json .}}'` over plain SSH exec when neither transport tier is available. Per-host dashboard (engine info, disk usage), container lifecycle (start/stop/restart/pause/kill/rename/remove) with live-follow logs and stats, and an "Enter Terminal" action that opens a normal terminal tab running `docker exec -it` into the container (shell auto-detected, bash preferred). Compose stacks are paste-first — paste a complete `compose.yaml`, validate, and it's written to a configurable remote directory (`mkdir -p`'d first) — with up/down/pull/restart and per-service status; single containers have an equivalent form-based `run.yml` editor with a raw-YAML advanced toggle. All remote values are shell-quoted before touching a command string
- **App-driven Docker image update checks** — per-container auto-update policies are now checked every 12 hours by a background worker: the registry's current manifest digest (Docker Hub anonymous token flow, Basic and Bearer for private registries; credentials stay in the Android Keystore) is compared against the running container's pulled digest (inspect `RepoDigests`), and a newer image raises a "Docker Update Alerts" notification plus an in-app pending badge; policies with auto-recreate enabled additionally pull the new image and recreate the container unattended (stop → rename to `{name}_old` → create+start from the old container's own config → health-verify → remove old, with automatic rollback on failure) and report the outcome. Checks piggyback on already-open SSH sessions only — the worker never dials out from the background — and the whole feature has a master toggle under Settings → Monitoring

## [1.0.0] - 2026-07-30

### Security

- **Hypervisor TLS hostname verification** — the `verifySsl=true` + system-CA path now performs strict RFC 2818 hostname verification instead of accepting any certificate name; `verifySsl=false` remains an explicit trust-all opt-in for self-signed hypervisor certs (accepted design decision)

### Changed

- **Default tab-swipe edge zone widened from 48dp to 96dp, and a rejected mid-screen swipe now gives visible feedback instead of silently doing nothing** — the edge-zone gate added to stop tab-swipe from hijacking mid-screen terminal gestures (vim/tmux navigation, text selection) worked exactly as designed, but a swipe that started outside the strip simply failed with zero feedback, which was indistinguishable from a bug. `tab_swipe_edge_dp`'s default is now `96` (two Material touch targets, easier to hit from either side) and `applyEdgeSwipeGate()` in `TabTerminalActivity.kt` now detects when a real horizontal drag is rejected by the edge check and fires a haptic tick plus a brief edge-glow animation (new `swipe_edge_glow_start`/`swipe_edge_glow_end` views) at the nearer screen edge

### Added

- **WebDAV sync true three-way merge** — sync now keeps a base-snapshot layer of the last successfully synced state, enabling real three-way merges between local and remote changes; genuine both-sides-changed conflicts surface a resolution dialog instead of silently last-writer-wins
- **Font-size keyboard shortcuts** — Ctrl+= / Ctrl++ and Ctrl+- zoom the terminal font in ±2sp steps (session-only, never persisted), and Ctrl+0 resets to the size configured in Settings → Terminal → Font Size; all three are also available in the command palette
- **Direct VNC host connections and libvirt/QEMU VM consoles now open as swipeable tabs inside the main terminal screen instead of a separate full-screen viewer** — `VncHostsActivity` and `LibvirtManagerActivity` used to launch the standalone `VMConsoleActivity`; they now connect, create a `Tab.Vnc` on the shared `TabManager`, and focus it in `TabTerminalActivity` alongside SSH tabs, reusing the same swipe/tab-bar UI. `TerminalPagerAdapter`'s VNC view holder also gained the RFB handshake start (previously never wired for the tab-swipe path) and stale-listener cleanup on tab recycle needed to make this work. Libvirt consoles no longer auto-retry with resize disabled after a server-side resize rejection — that retry relied on tearing down a single-purpose activity, which doesn't fit a persistent multi-tab shell; libvirt/QEMU consoles now request no resize by default instead. Hypervisor console tabs (Proxmox/XCP-ng/libvirt `Tab.Console`) now get the same edge-only swipe protection as VNC tabs whenever they're in graphical mode — `applyEdgeSwipeGate()` in `TabTerminalActivity.kt` reads `ConsoleTab.isGraphicalMode` fresh on every touch, so a console that flips between text and graphical mid-session gets the right gating on the very next gesture; text-mode console tabs are unaffected and keep behaving like a plain SSH terminal
- **Proxmox and XCP-ng/Xen Orchestra VM consoles now open as swipeable tabs too, and the standalone VM console viewer is gone** — `ProxmoxManagerActivity.openConsole()` and `XCPngManagerActivity.openVMConsole()` now create/activate a `Tab.Console` on the shared `TabManager` instead of launching `VMConsoleActivity`; the main terminal quick-connect's VNC option (both saved and one-off connections) was migrated the same way. With every caller migrated, `VMConsoleActivity` and its `VncStreamHolder` handoff have been deleted outright. VNC and hypervisor-console tabs now also survive being backgrounded the same way SSH tabs already do — `TabManager` pauses and parks their session into the existing `VncBackgroundSessionStore`/`VncKeepAliveService` when the app leaves the foreground, and reclaims (or, if the session was idle-swept after 10 minutes, surfaces as disconnected) it on return, instead of risking Android killing the whole process mid-session with no protection at all

### Removed

- **"Show Function Key Row" setting** — the preference toggled nothing (the function-key row it referenced was never wired); the dead setting and its preference plumbing are gone
- **`VMConsoleActivity`, its layout, and `VncStreamHolder`** — the standalone full-screen VM/VNC console viewer is fully retired now that every caller opens a swipeable `Tab.Vnc`/`Tab.Console` inside `TabTerminalActivity` instead
- **PRE key long-press now opens the multiplexer picker to manually override the detected type** — even with the detection fixes below, auto-detection can't be 100% certain (e.g. a user with both tmux and zellij installed but only tmux attached); long-pressing PRE now opens the same picker dialog shown when detection first fails, letting you override it at any time instead of only when nothing was auto-detected

### Fixed

- **Double-tap-to-copy no longer races long-press selection** — a double tap could land while a long-press selection ActionMode was being created, leaving the copy menu unopenable or the selection stuck; the gesture paths are now serialized
- **DEC private-marker CSI sequences parsed correctly** — `ANSIParser` mishandled the `?` private marker in CSI sequences, corrupting parsing of DEC private-mode set/reset sequences
- **WCAG AA contrast for built-in themes** — Solarized Light foreground (4.1:1 → 5.0:1) and Rosé Pine cursor (2.3:1 → 5.5:1) corrected to meet the 4.5:1 AA minimum enforced by `ThemeValidator`
- **Tab swiping went permanently dead after the first touch outside the edge strip** — the edge-zone gate lived in a `RecyclerView.OnItemTouchListener` on ViewPager2's inner RecyclerView, but ViewPager2's `RecyclerViewImpl.onInterceptTouchEvent` short-circuits as `isAllowedToScroll() && super.onInterceptTouchEvent(ev)`, so the moment the gate disabled `isUserInputEnabled` on a mid-screen `ACTION_DOWN`, the listener stopped receiving events entirely — including the `ACTION_UP` that was supposed to re-enable input — leaving swipe dead for every later gesture, even ones starting on the edge. The gate now runs in a `dispatchTouchEvent` override in `TabTerminalActivity.kt` (`applyEdgeSwipeGate()`), which sees every event unconditionally; a swipe starting anywhere along the full height of the edge strip (width = the `tab_swipe_edge_dp` preference; 0 = anywhere) switches tabs, and the selection-suspend, VNC/graphical-console 96dp carve-out, and rejection haptic/glow feedback all behave as before
- **Single-view mode's edge-swipe fling now honors the `tab_swipe_edge_dp` preference** — `TerminalView`'s issue-#168 fling handler used a hardcoded 24dp edge strip regardless of the user's setting; `edgeSwipeDp` is now set from the preference in `setupTerminalView()`, with `0` meaning a horizontal fling anywhere on the terminal switches tabs (direction follows the fling), and the fling is suppressed while text selection is active to mirror pager-mode's selection suspend
- **PRE key stayed fully visible/active-looking after being disabled via the "Enable PRE Key" toggle, even though taps on it were already silently ignored** — `updatePrefixKeyVisual()` in `TabTerminalActivity.kt` always passed `enabled = true` to `KeyboardRowView.setKeyState()` regardless of `PreferenceManager.isPrefixKeyEnabled()`, so the key never rendered its dimmed state; `setKeyState()` and `MultiRowKeyboardView.setKeyState()` gained a new `dimmed` parameter that forces the heavily-dimmed look without touching Android's `View.isEnabled` (which would have also blocked the long-press re-enable picker), and `updatePrefixKeyVisual()` now checks the setting first and applies it
- **Creating (or closing) a tab while a text selection was active on the terminal permanently disabled tab-switch swipes, only recoverable by leaving and re-entering the terminal screen** — `startTerminalSelectionActionMode()` sets `swipeSuspendedForSelection = true` and disables `ViewPager2.isUserInputEnabled` while a text-selection `ActionMode` is showing, and the only place that resets both back is `onDestroyActionMode`; but `updateViewPagerAdapter()` (called by both the new-tab and close-tab paths) reassigns `viewPager.adapter` to rebuild the pager, which recycles the currently bound `TerminalView` out from under any active floating `ActionMode` without necessarily firing `onDestroyActionMode` — a known Android framework gap for `TYPE_FLOATING` action modes whose anchor view is forcibly detached — leaving `swipeSuspendedForSelection` stuck `true` forever, which the edge-swipe gate's own `ACTION_UP` handler then refuses to clear on every subsequent gesture. `updateViewPagerAdapter()` now calls `selectionActionMode?.finish()` first, which synchronously runs the existing cleanup while the view is still attached and valid before the adapter swap happens
- **Tapping a long URL that soft-wraps across multiple terminal rows (e.g. a CLI login link) could open a truncated/malformed link instead of the full URL** — `detectUrlAtPosition()` in `TerminalView.kt` had a "fast path" that ran the URL regex against only the tapped row's text and returned immediately on any match covering the tap column, before the wrap-aware multi-row reconstruction below it ever ran; since the URL path-segment pattern has no length limit, a tap on the first wrap row (where the `https://` scheme is visible — the natural tap target) matched and returned just that row's truncated prefix, never falling through to rebuild the full wrapped URL. The fast path now skips itself and falls through to the wrap-aware reconstruction whenever the row-local match runs to the end of a soft-wrapped row, since that's exactly the condition where it could be an artificially-truncated fragment of a longer URL
- **Swipe up/down stopped scrolling the terminal scrollback whenever an alt-screen program (vim, less, man, htop, or a full-screen multiplexer pane) was active** — `TerminalView.maxScrollYPx()` reads `TerminalBuffer.activeTranscriptRows`, which Termux always reports as `0` while the alternate screen buffer is active (by design — alt-screen apps have no client-side scrollback), so the local-scrollback swipe path silently became a permanent no-op the moment any such program ran; `TerminalGestureListener.onScroll()`/`onFling()` in `TerminalView.kt` now detect `TerminalEmulator.isAlternateBufferActive()` and forward the swipe as repeated Up/Down arrow-key escape sequences instead (respecting DECCKM application-cursor-keys mode and the existing `reverseScrollDirection` preference), which is the standard fallback full-screen terminal apps already interpret as navigation; this is in addition to, and mutually exclusive with, the pre-existing mouse-wheel-event forwarding used when the remote program has mouse tracking enabled (tmux with `set -g mouse on`, zellij's default mouse mode, vim's `:set mouse=a`, etc.) — note this does not reach tmux/screen/zellij's own native scrollback (copy-mode) at a plain shell prompt with no alt-screen program running and no mouse mode enabled, which has no client-detectable trigger; GNU screen has no mouse-scroll mechanism at all, with or without config
- **Horizontal tab-switch swipes leaked literal Down-arrow escape bytes (`^[[B^[[B^[[B`) into the remote terminal** — the alt-screen arrow-key-forwarding fallback above forwarded `onScroll()`'s vertical distance component even on a primarily-horizontal drag, because `GestureDetector` fires `onScroll()` for any drag, not just vertical ones; `TerminalGestureListener.onScroll()` in `TerminalView.kt` now bails out immediately (resetting both scroll accumulators) whenever `abs(distanceX) > abs(distanceY)`, since a dominant-horizontal drag is a tab switch owned by `ViewPager2`, not a scrollback gesture
- **Multiplexer auto-detection could report the wrong type (e.g. `zellij` for a tmux-only session), silently arming the PRE key with the wrong prefix bytes** — `SSHTab.probeMultiplexerOnce()`'s live-session probe had two independent bugs: (1) the `zellij list-sessions` check used a bare `grep -q .`, but zellij prints `No active zellij sessions found.` to stdout with exit `0` when nothing is running, so any server with zellij installed unconditionally false-positived as `zellij` regardless of what was actually running; (2) `tmux ls`/`screen -ls` locate the server via `$TMUX_TMPDIR`/`$SCREENDIR`/a custom `-S` socket path, but those are typically set in `~/.bashrc`/`~/.profile`, which the SSH exec channel's fresh non-interactive, non-login shell never sources — so a genuinely running tmux/screen server could be socket-invisible to the probe and fall through to the wrong branch. Fixed by excluding the zellij "no sessions" boilerplate from the match, and by adding a `pgrep`-based process-existence fallback (which has no socket-path dependency at all) after each socket check; probe output is now tagged (`tmux:env`/`tmux:live-socket`/`tmux:live-proc`/etc.) and logged on every run for future diagnosability
- Added `Logger.d` diagnostic logging around the tab-swipe suspend/resume state (`swipeSuspendedForSelection`) and the edge-swipe gate's `ViewPager2.isUserInputEnabled` toggling in `TabTerminalActivity.kt`, to make future swipe-gesture regressions easier to diagnose from logcat
- **Remote File Editor no longer double-saves the same file when you tap Save twice** — `RemoteFileEditorActivity.saveFile()` had no re-entry guard, so two quick taps fired two concurrent SFTP uploads against the same path and whichever finished last silently clobbered the other; the editor now sets an `isSaving` flag, disables the Save menu item via `onPrepareOptionsMenu`, and shows "Save already in progress…" instead of racing, with the flag cleared in a `finally` block so a failed upload still re-enables the action
- **Application Logs viewer no longer reads `tabssh.log` on the UI thread** — `LogViewerActivity.loadLogs()` was calling `File.readLines()` on `filesDir/tabssh.log` directly from `lifecycleScope.launch { … }` (the default Main dispatcher), which on a multi-MB log over slow flash trips Strict Mode and risks an ANR; the read now runs in `withContext(Dispatchers.IO)` and streams via `useLines` rather than slurping the full file, and `exportLogs()` likewise writes the output file off Main
- **Audit Log CSV export is now RFC-4180 compliant** — `AuditLogViewerActivity.exportLogs()` was building CSV by concatenating raw `${log.command}` / `${log.output}` fields with no quoting, so any audit row containing a comma, a newline, or a `"` produced a corrupt file that no spreadsheet could parse; every column now passes through a `csvField()` helper that wraps the value in `"` and doubles internal quotes, and the disk write was moved into `withContext(Dispatchers.IO)`
- **PIN lock brute-force counter now survives killing the app** — `PinLockActivity` tracked failed attempts in a plain `var attempts = 0` instance field, so an attacker who burned four guesses could simply force-stop the task (or rotate the device hard enough to recreate the activity) to reset the budget; the count is now persisted to `app_lock_pin_fail_count` and restored in `onCreate`, incremented and saved on every failed entry, and cleared on successful unlock or PIN setup; PIN-hash comparison also switched from `==` to a constant-time digest equality to avoid leaking byte-by-byte timing
- **Transcript delete no longer blocks the UI thread on slow storage** — `TranscriptViewerActivity.deleteTranscript()` was calling `TranscriptManager.deleteTranscript()` synchronously from the dialog's positive-button callback on Main; the manager walks the filesystem and unlinks, and on cheap eMMC this stalled the UI; the delete now runs in `lifecycleScope.launch(Dispatchers.IO)` and posts the success toast / list refresh — plus a failure toast that the original code lacked — back through `withContext(Dispatchers.Main)`

### Added

- **Keyboard Layout Editor now has a "Reset to default" menu action** — the editor let you drag-reorder and add / remove keys, but offered no way back to the factory layout if you got lost in your edits; tapping the overflow > "Reset to default" now prompts to confirm and restores `MultiRowKeyboardView.getDefaultRowLayouts(n)` for the currently selected row count, then waits for you to tap Save (the FAB) before persisting — same save model as every other change in this screen

### Fixed

- **SFTP "Select All Local" / "Select All Remote" menu items now actually select files** — both menu items were declared in `menu/sftp_menu.xml` with titles but their `onOptionsItemSelected` branches contained only a comment and returned `true`; added `selectAllLocal()` / `selectAllRemote()` on `FileAdapter` and wired the menu items, so taps now populate the selection set with every non-directory row and toast the count
- **SFTP upload / download no longer crashes when the user taps the button before the connection is ready** — `uploadSelectedFiles()` and `downloadSelectedFiles()` were calling `sftpManager?.uploadFile(...)` on a `lateinit var`, but `?.` does NOT catch `UninitializedPropertyAccessException`, so racing the async `setupSFTPManager()` crashed the app; both now check `::sftpManager.isInitialized` first and show "SFTP not connected yet" instead

### Removed

- **Two dead SFTP menu entries removed** — `R.id.action_transfer_settings` and `R.id.action_bookmarks` were declared in `menu/sftp_menu.xml` but had ZERO handlers in `onOptionsItemSelected` AND ZERO implementation anywhere in the source tree (grep across `app/src/main/` confirmed no transfer-settings screen and no bookmarks system); tapping either did nothing — they advertised UI features that were never built

### Fixed

- **Identity dropdown on the connection editor no longer silently resets to "No Identity" when editing an identity-bound profile** — `populateFields()` was firing the spinner restore synchronously while `loadSshIdentities()` was still fetching on `Dispatchers.IO`, so `availableIdentities` was always empty at the moment `restoreSshIdentitySpinner()` ran; the editor now awaits the identity list and rebuilds the adapter before restoring the selection
- **Editing a VNC host no longer removes it from its group or rewrites its creation time** — `VncHostEditActivity.saveHost()` was hardcoding `groupId = null`, rewriting `createdAt = now`, and calling `vncHostDao.insert()` (whose `OnConflictStrategy.REPLACE` masked the bug by overwriting the row); the editor now preserves the loaded record's `groupId` and `createdAt` and dispatches to `update()` for edits and `insert()` only for new records
- **PerformanceFragment no longer paints `textLoad1min` twice per frame** — two identical `setTextColor(when { … })` blocks (lines 495-499 and 501-506) ran back-to-back on every metrics tick; the second was exact dead code with the same comment and same branches, removed it
- **HypervisorsFragment delete and refresh-status now bound to view lifecycle** — both used the Fragment-scoped `lifecycleScope`, so a coroutine that survived `onDestroyView` could still call `requireContext()` / `Toast.makeText` against a dead view tree; switched both to `viewLifecycleOwner.lifecycleScope` and captured `requireContext()` on the main thread before the IO probe so `LibvirtApiClient` no longer receives a context fetched from a background dispatcher
- **Bulk-edit identity dropdown no longer wipes user selection on identity-table changes** — the dropdown adapter was being rebuilt on every emission of `identityDao().getAllIdentities()` while the dialog was open; replaced the continuous `.collect { … }` with a one-shot `.first()` so the adapter is set exactly once when the dialog opens

### Added

- **Connection editor now asks before discarding unsaved edits** — both the system Back button and the Cancel button now check a dirty flag tracked via a `TextWatcher` on the name / host / port / username / password fields; if anything has been changed since the form was populated the editor shows a "Discard changes? — Discard / Keep editing" prompt instead of dropping the user's work silently

### Removed

- **Orphan `CustomKeyboardView` removed** — the legacy single-row keyboard view and its `view_custom_keyboard.xml` layout had zero callers; `MultiRowKeyboardView` is the only active keyboard surface, used by both `activity_tab_terminal.xml` and `activity_vm_console.xml`
- **Dead `KeyboardLayoutManager` instance methods removed** — `getLayout`, `saveLayout`, `addKey`, `removeKey`, `moveKey`, `resetToDefault`, and the private `parseLayout` (single-row CSV) had zero callers; the file is now an `object` exposing only `parseLayoutJson`, `layoutToJson`, and `CURRENT_DEFAULT_LAYOUT_VERSION` — the multi-row JSON helpers everyone actually uses
- **Legacy `KeyboardKey.getDefaultKeys()` removed** — was only consumed by the deleted `KeyboardLayoutManager` instance methods; `MultiRowKeyboardView.getDefaultRowLayouts()` is the sole default-layout source

### Fixed

- **Tab-limit error message is now actionable** — opening a new tab when `ui_max_tabs` is reached previously showed a generic "Failed to create terminal tab" and closed the activity; now reports the actual cause ("Tab limit reached (N tabs open). Close a tab before opening a new one.") and tears down the SSH connection that was just established so it does not leak until process exit
- **MultiRowKeyboardView FN-swap state survives layout changes cleanly** — `setLayout()` now resets the `fnMode`/`savedLayout` snapshot taken when the user pressed FN, so a subsequent `restoreFromFn()` cannot re-paint the stale pre-change rows

- **Orphan `accessibility/` package removed** — `AccessibilityManager` (stub bodies), `HighContrastHelper`, `TalkBackHelper`, and `KeyboardNavigationHelper` had zero callers across UI code; the real accessibility surface is the contentDescription/Material 3 default screen-reader path through the layouts, not this parallel subsystem
- **Orphan `network/proxy/ProxyManager.kt` removed** — never called from any connect path; real per-host proxy is `SSHConnection.setupHttpSocksProxy()` driven by the per-profile proxy fields
- **Orphan `terminal/MultiplexerManager.kt` removed** — multiplexer auto-launch is genuinely wired through `SSHTab.buildMultiplexerCommand()` + `GestureCommandMapper`; this file was a parallel/legacy implementation never reached
- **Orphan `terminal/input/KeyboardHandler.kt` removed** — keyboard input is handled directly in `TerminalView` against the active emulator
- **Orphan `platform/PlatformManager.kt` removed** — never instantiated anywhere; detection logic was scaffolding
- **Orphan `utils/helpers/ValidationHelper.kt` removed** — zero callers across the codebase
- **Dead theme parsers removed** — `ThemeParser` no longer carries `parseFromVSCodeTheme()`, `parseFromITermScheme()` (was half-implemented and always returned null), `parseFromTerminalSexy()`, or their `@Serializable` data classes; only `parseThemeFromJson` is reachable from `ThemeManager`

### Changed

- **SEL key removed** — the legacy SEL key on the keyboard bar no longer exists; text selection is now entered exclusively via **"Select Text…"** in the clipboard menu (📋 → Select Text…); double-tap word-selection still works as before
- **`Ctrl+` prefix notation** — `PrefixParser` now accepts `Ctrl+a` (plus separator) in addition to `Ctrl-a` (dash separator) and the existing `C-a` / `^a` forms; human-readable descriptions always use `Ctrl+X` / `Alt+X` style

### Fixed

- **SFTP resume download no longer truncates the partial file** — `SFTPManager.downloadFile()` on a resumed transfer previously opened the local file with `outputStream()` (which zeroes the file) and then routed writes through a stub `appendOutputStream()` extension that also truncated; every "resume from offset N" silently produced a tail-only file with the on-disk prefix discarded; now uses `FileOutputStream(file, append=true)` for the resume branch so JSch's `ChannelSftp.RESUME` actually appends after the existing bytes
- **Backup validator sub-results no longer report valid backups as invalid** — `validateConnectionsData`, `validateKeysData`, `validatePreferencesData`, and `validateThemesData` each returned `ValidationResult(false, …)` regardless of whether the section parsed cleanly; downstream callers that consumed the per-section `isValid` flag treated every backup as broken; now returns `errors.isEmpty()` so the field matches the error list
- **`isBackupEncrypted()` now correctly identifies AES-GCM backups** — the previous "fails to parse as JSON AND matches a base64 regex" heuristic never matched because `SyncEncryptor` emits raw binary with a `TABSSH_SYNC_V2` magic header (not base64); encrypted backups were misreported as plaintext and the UI skipped the passphrase prompt before failing with a JSON parser error; now detects the 14-byte magic header directly
- **Settings → Notifications → "Show connection notifications" and "Vibrate" toggles now actually do something** — both were persisted, surfaced in Settings, synced and backed up, but no notification code path consulted them; `NotificationHelper.maybeAlertForHost()` now early-returns when the matching global toggle is off, and the global vibrate switch suppresses haptics across every per-profile vibrate mode
- **Duplicate SSH Agent Forwarding switch removed** — `agent_forwarding_default` appeared in both Settings → Connection (canonical) and Settings → Security; two widgets bound to one preference would race on toggle and confuse users about where the setting lives; removed from Security, kept in Connection
- **Settings numeric inputs now validated** — `connect_timeout` previously threw `NumberFormatException` on an empty or non-numeric entry (crashing the listener); `server_alive_interval`, `ui_max_tabs`, `tasker_command_timeout`, `audit_log_max_size_mb`, and `audit_log_max_age_days` had no bounds at all; all six now reject invalid input with a clear toast and sane minimum/maximum bounds
- **SSH bytes-transferred counter now reports real traffic** — `ConnectionStats.bytesTransferred` and the `bytesTransferred` StateFlow on every `SSHConnection` were declared and exposed but never written, so every consumer (session snapshots, dashboards, persistence) reported a constant `0 B`; `getInputStream()` / `getOutputStream()` now return cached counting wrappers that increment the counter on every successful read/write
- **`Confirm on exit` setting now works** — the toggle in Settings → General was previously saved but never read; `MainActivity`'s back-press handler now reads `confirm_exit` and shows an "Exit TabSSH?" prompt when enabled
- **SSH Agent Forwarding default toggle wired to the connection layer** — Settings → Security used preference key `ssh_agent_forwarding` while `SSHConnection.applyForwardingFlags()` read `agent_forwarding_default`; the user toggle had no effect on any actual connection; XML key realigned to `agent_forwarding_default` so the visible switch now governs the default the SSH session reads
- **`Debug Log Level` setting now filters log output** — the `debug_log_level` ListPreference (Verbose / Debug / Info / Warning / Error) was previously cosmetic; `Logger` now caches the level on init and reapplies it live via `updateMinLevelFromPrefs()` when SettingsActivity changes the value
- **`Max Size per Host (MB)` setting now caps host log rotation** — `host_log_max_size_mb` was previously saved but ignored, so host logs grew without bound at a hard-coded 1 MiB cap; `Logger.logHostEvent()` now reads the SeekBarPreference value (1–10 MB) on every write

- **Tab switch froze the terminal** — ViewPager2 calls `onDetachedFromWindow()` on off-screen pages, which removed the `TermuxBridgeListener` and stopped cursor blink; the SSH read loop kept running but `invalidate()` was never called so the view looked frozen; added `onAttachedToWindow()` to re-register the listener and call `requestFocus()` when the page slides back on-screen
- **Horizontal swipe could accidentally switch tabs during text selection** — `startTerminalSelectionActionMode()` now sets `viewPager.isUserInputEnabled = false` while the floating Copy ActionMode bar is active; swipe is re-enabled in `onDestroyActionMode()` (fires on Copy, Cancel, Paste, and tap-outside)
- **Tapping a word-wrapped URL opened a cut-off URL** — `detectUrlAtPosition()` only looked forward one row; tapping the second or later row of a wrapped URL found nothing and fell through; new implementation walks backward to the soft-wrap segment start, forward to the segment end (clamped to ±4 rows), calls `termuxBuffer.getSelectedText(0, startRow, terminalCols, endRow)` which joins soft-wrapped rows without `\n`, then finds the URL whose range covers the tap position; `isRowSoftWrapped()` helper provides the wrap flag for both Termux and local `TerminalBuffer` paths

### Added

- **Mosh auto mode** — per-connection Mosh setting is now three-way: Off / Auto (default) / On; "Auto" silently tries `mosh-server` on the remote and falls back to plain SSH if it isn't installed; the old on/off toggle is replaced by a dropdown in the connection editor; all existing connections default to "Auto"

- **Export private key** — "⬇️ Export private key…" option in the SSH key actions menu; prompts for an optional passphrase; exports the key in OpenSSH PEM format (encrypted with AES-256-CBC if a passphrase is provided, unencrypted otherwise) via the system file picker

- **Install SSH key on server** — "⬆️ Install on server…" option in the SSH key actions menu; shows a single-select list of saved SSH connections; connects and runs an idempotent `authorized_keys` install command (creates `~/.ssh` if absent, skips if the exact key line is already present); confirms success or "already installed" via toast

- **Session persistence** — `SessionPersistenceManager` is now wired as an `ActivityLifecycleCallbacks`; saves terminal scrollback and tab state to the database every 30 s while the app is in the foreground, immediately on background, and on every `onSaveInstanceState`; restores sessions on foreground return if the app was backgrounded for less than 24 h; applies auto-lock and clipboard-clear security policies on background

- **Volume key action setting** — Settings → Terminal → Volume Key Action; three options: "Font size (+ / −)" (default, preserves existing behaviour), "Scroll (page up / down)" (Volume Up = older content, Volume Down = newest), "System volume (off)"; existing `volume_keys_font_size` boolean preference is migrated automatically on first launch

- **Full preference sync and backup** — connection defaults, sync toggles, multiplexer key bindings, accessibility flags, and proxy configuration are now included in both SAF sync and backup/restore; previously these five categories were silently absent from both systems

### Changed

- **Identities tab reordered** — sections now appear as: Host Identities → SSH Keys → VM Credentials → VNC Identities; the former "Virtualization Identities" section is renamed "VM Credentials" with a shorter subtitle
- **OCI accounts removed from Identities tab** — OCI API-key credentials are managed exclusively through the dedicated OCI wizard; they are filtered from the VM Credentials list and the create/edit dialog no longer offers an OCI option
- **VNC identity dialog uses Material TextInputLayout** — replaced the programmatic plain-`EditText` dialog with a proper `TextInputLayout` form matching the rest of the app; password field gains visibility toggle and correct mask/replace behaviour

### Added

- **Bracketed paste** — pasting into vim, nano, or any editor that enables `?2004` (bracketed paste mode) now works correctly; the app tracks `ESC[?2004h`/`ESC[?2004l` from the server and wraps paste data in `ESC[200~` / `ESC[201~`; large pastes (configs, scripts, SQL) are chunked at 4 KB to prevent stalling the SSH write path; CRLF and bare LF are normalised to CR on the way out

- **OSC 8 hyperlinks** — SSH and VM console sessions now recognise OSC 8 hyperlink sequences (`\e]8;params;url\e\\anchor\e]8;;\e\\`); long-pressing an anchor word opens the embedded URL rather than relying on regex guessing; anchor text is underlined in link-blue during rendering for both the Termux path (TermuxBridge intercept) and the custom emulator path (ANSIParser + TerminalChar.url)
- **Visual URL underlines** — every detected URL (OSC 8 or regex-matched) is now underlined with a thin colored rect drawn below the text during render; color follows the theme's primary hue when set, otherwise defaults to a link-blue that reads on both dark and light backgrounds

### Fixed

- **Data wiped on app update** — removed `fallbackToDestructiveMigration()` from the Room database builder; future app updates will no longer silently destroy saved connections, SSH keys, and settings; any future schema change must supply a proper `Migration` object

- **SQLite power-loss corruption** — database now opens in WAL (Write-Ahead Logging) journal mode; writes are atomic even if the device loses power mid-write; the previous DELETE journal mode could produce a truncated or corrupted database file

- **SSH key delete could silently corrupt state** — deleting a key now follows Keystore → SharedPrefs (synchronous commit) → DB order; previously, the DB row was removed first so a process kill halfway through left an orphaned Keystore entry with no matching DB record; new order ensures the key remains fully intact if the Keystore step fails, and the ciphertext is flushed synchronously before the record is removed

- **Stored password cleanup used async SharedPrefs write** — `SecurePasswordManager.clearPersistedPassword()` used `apply()` (fire-and-forget); changed to `commit()` (synchronous, blocking) so the encrypted credential is guaranteed to be removed from disk before the Keystore key is deleted; also moved the Keystore delete before the SharedPrefs clear so a crash mid-cleanup leaves unreadable ciphertext rather than a live key with missing data

- **Room schema export disabled** — `exportSchema` is now `true`; KSP emits a JSON schema file per DB version into `app/schemas/`; future migrations can be validated against the expected schema at compile time instead of only failing at runtime on a user's device

- **URL detection matched trailing punctuation** — URLs followed by `.`, `,`, `)`, `]`, `'`, `"`, `;`, `:`, `!`, or `?` (as in normal prose) incorrectly included those characters in the matched URL; a trailing-strip pass now removes them
- **URL detection joined unrelated lines** — the word-wrap cross-row URL join (for URLs that split at a terminal column boundary) fired even on rows that ended with a hard newline; for VM console sessions the new `TerminalBuffer.isRowWrapped()` flag is now consulted so only genuinely soft-wrapped rows are joined; for SSH sessions the Termux library's `'\n'`-at-hard-newline behaviour already prevents a false match in the combined text
- **URL detection missed common schemes** — `ftp://`, `ftps://`, `ssh://`, `git://`, `svn://`, `file://` were not matched; all are now included

- **VM stop button had no effect on Proxmox** — the Stop button was sending `virsh`'s graceful ACPI shutdown signal (`/status/shutdown`), which requires the QEMU guest agent to be installed and responding; changed to `/status/stop` (hard power-off) which always works regardless of guest state
- **VM stop button had no effect on OCI** — the Stop action was sending `SOFTSTOP` (ACPI graceful), which silently did nothing when the OCI cloud agent was absent or the instance was unresponsive; changed to `STOP` (hard power-off) so the button is always reliable; errors are now logged instead of silently swallowed
- **OCI all actions failed after first instance load (TOFU cert loop)** — the TLS pin is stored as `"sha_identity;sha_iaas"` at fixed positions, but `getCapturedCertSha256()` used `listOfNotNull` which collapsed missing slots; an IAAS-only pin was stored as a bare `"sha"` with no semicolons, so on reload it was read into index 0 (identity) while `iaasPinnedSha` stayed null; every subsequent action triggered a fresh TOFU cert dialog that defaulted to REJECT after 30 s; fixed by always writing the fixed `"idSha;iaasSha"` format (empty slot = empty string, not omitted) and removing the `filter { isNotBlank() }` that compacted positions on parse
- **VM stop button had no effect on Libvirt / KVM** — the Stop button was calling `virsh shutdown` (graceful, requires guest agent); changed to `virsh destroy` (hard power-off) which cuts power immediately and always succeeds
- **Copy screen broke word-wrapped lines** — "Copy screen" (TermuxBridge) appended a `\n` after every display row unconditionally; long lines that soft-wrapped across two rows were split at the column boundary; fixed by delegating to a single `getSelectedText(0, 0, cols, rows-1)` call which respects the Termux library's `mLineWrap[]` flags
- **Copy screen broke word-wrapped lines in VM console** — `TerminalEmulator.getScreenContent()` had the same unconditional per-row `\n` injection; the underlying `TerminalBuffer` now tracks per-row soft-wrap flags (`rowWrapped[]`), set when auto-wrap fires and cleared on hard newlines, scroll, insert/delete line, and resize; `getScreenContent()` skips `\n` for wrapped rows so the logical line is reconstructed correctly

- **Identity picker didn't show selection in Connections → Edit host → Identity** — `MaterialAutoCompleteTextView` requires an explicit `setText(item, false)` call in the item-click listener to display the selected item; the SSH identity listener was missing this call (the VNC listener already had it); added to the SSH path so tapping an identity name now visually sticks in the field
- **Group long-press menu lacked "Bulk edit all hosts"** — the three-item context menu (Rename / Delete / Collapse All) now has "Bulk edit all hosts in this group" as the first entry, wiring directly into the existing `showBulkEditDialog` path
- **"Create Paste" bottom sheet clipped off-screen on mobile** — `ReportIssueDialog` built a plain `LinearLayout` with no scrolling; on small screens the action buttons were out of reach; wrapped the root in a `NestedScrollView` and added `onViewCreated` to force `STATE_EXPANDED` + sensible peek height at show time

- **Room DB crash on upgrade ("cannot verify data integrity")** — bumped database version to 3; devices where the DB was already at version 2 with the old schema never triggered `onUpgrade` on the previous bump, so the hash mismatch persisted; this bump forces `onUpgrade(2→3)` regardless of intermediate state

- **Mosh connection lost lastlog/MOTD** — when Mosh was enabled, the SSH shell channel opened briefly (printing lastlog/MOTD), then got ripped out and replaced by mosh-client, which syncs to the current terminal state and doesn't replay scrollback; fixed by bootstrapping `mosh-server` before opening any shell channel so `mosh-server`'s own login shell is the only one and its output is visible

- **Spurious "Text copied" system toast on clipboard auto-clear** — `ClipboardHelper` and `SessionPersistenceManager` both cleared the clipboard via `setPrimaryClip(empty)`, which on Android 13+ always triggers the OS "Text copied" notification even for a blank string; replaced with `clearPrimaryClip()` (API 28+) which clears silently

- **`encodePrivateKeySectionForOpenSSH` wrote broken OpenSSH private key files** — three bugs: (1) Ed25519 case was missing entirely so no private key bytes were written; (2) ECDSA case was missing so the private scalar was never written; (3) RSA wrote `(e, n)` in the private section but OpenSSH requires `(n, e, d, iqmp, p, q)` — fixed all three; the function is not yet called from UI code but is now correct for when private key export is wired up

- **SSH key name shows garbled binary after import** — `parseOpenSSHEd25519Key` was reading the 32-byte public-key copy in the private section as the private key, leaving the real 64-byte private key blob unconsumed; the comment-reading code then read those 64 binary bytes as the comment string, producing garbage in the key list; fixed by consuming the pubkey copy with a `readString` before reading the actual private key — this also fixes the silent auth failure caused by storing the wrong key material; added printability guard in `getDisplayName()` as a defence-in-depth layer for keys already in the DB
- **SSH key import shows garbled name** — `fileUri.lastPathSegment` on a `content://` URI returns an encoded path component, not the display filename; now queries `OpenableColumns.DISPLAY_NAME` via the `ContentResolver` with `lastPathSegment` as fallback
- **`PortForwardingManager.cleanup` audit-logging orphan scope** — per-tunnel audit-log write spawned a throwaway `CoroutineScope(Dispatchers.IO)` whose parent `Job` was never cancelled; routed through `app.applicationScope.launch(Dispatchers.IO)` to match the pattern used by `TaskerWorker` and `PerformanceFragment`
- **`HypervisorsFragment` REST reachability probe socket leak** — `Socket()` allocated, `connect()` could throw, `close()` was skipped; wrapped in `try { connect() } finally { close() }`
- **`X11Proxy.connectToXServer` LocalSocket and TCP Socket leak on connect throw** — both probes allocated the socket inside the `try` block; a `connect()` exception fell through to the catch arm without closing the descriptor; hoisted allocation above `try` and added explicit close in catch
- **`ImportExportActivity.importSSHConfigFromUri` InputStream leak window** — reshaped to chained `openInputStream(uri)?.bufferedReader()?.use { it.readText() }` form to eliminate the window between local-`val` assignment and `.use {}` entry
- **`VncStreamHolder.set` orphan-stream leak on producer re-launch** — set without consume left prior streams unclosed; added explicit close-then-replace under the `@Synchronized` block
- **`TabManager.switchToTab` unused `previousTab` local** — dead `val` removed
- **`ConsoleWebSocketClient.isConnected` missing `@Volatile`** — read by the keepalive thread loop and written by the OkHttp callback thread; added `@Volatile` to prevent JIT-cached reads firing one ghost-send after disconnect
- **Collapsed database to version 1** — dropped all 38 migration objects, 33 schema JSON files, and the `room.schemaLocation` KSP arg; `fallbackToDestructiveMigration()` replaces the migration chain; any alpha install is wiped on upgrade
- **Removed all legacy/compat code** — alpha build, no existing users: dropped GSSAPI + FIDO2_SECURITY_KEY from AuthType, FIDO2 error guard from SSHConnection, v1 backup restore path from BackupImporter + BackupManager, deprecated `terminal` alias from SSHTab, and `isXenOrchestra` DB column from HypervisorProfile (migrated to `apiTypeOverride`; DB schema → v39)
- **Removed FIDO2 alpha stub** — `Fido2Detector`, `Fido2SshIdentity`, the Settings detection entry, and the NFC/USB-host manifest declarations are removed; `FIDO2_SECURITY_KEY` auth-type enum value is kept for database compatibility but remains non-selectable; the error guard in `SSHConnection` stays to handle any legacy DB rows
- **Removed dead VMware console button** — `btnConsole` in `VMwareManagerActivity` was always `View.GONE`; removed the field and the three visibility assignments; `rowConnect` visibility now depends on `btnSsh` only
- **Audit log now records SSH session events** — `AuditLogManager` had all methods implemented but none were wired; session start/end, auth success/failure, SFTP upload/download/delete, and port-forward open/close are now recorded when audit logging is enabled in Settings → Logging → Audit
- **Connection list now shows groups** — the Connections tab was rendering a flat list even when connections were assigned to groups; switched to `GroupedConnectionAdapter` so groups are visible
- **`KeyStorage.importKeyFromFile` leaked SAF InputStream** — `openInputStream()` result was read via `.bufferedReader().readText()` without `.use {}`; the underlying `ParcelFileDescriptor` was never closed; reshaped to `?.bufferedReader()?.use { it.readText() }`
- **`TelnetConnection.connect` socket leak on connection failure** — `Socket()` was allocated inside `try{}` and only assigned to the field after `connect()` succeeded; a timeout/refusal meant `disconnect()` in the catch arm couldn't reach it; hoisted allocation above `try{}` and added explicit `s.close()` in the catch arm
- **`SessionRecorder.startRecording` FileWriter leak on init failure** — `fileWriter` was assigned before the initial write/flush, so a storage failure left an open fd in the field while `isRecording` stayed false; deferred field assignment until after the write succeeds, closes the local writer in the catch arm
- **`SessionRecorder.stopRecording` FileWriter leak on write failure** — `close()` was only called if `write()` succeeded; restructured to always null the field and call `close()` regardless of whether the trailing write threw
- **`TabManager.closeTab` / `cleanup` leaked per-tab scope and Termux bridge** — both sites called `tab.disconnect()` which only tears down the SSH session; `tab.cleanup()` (which also cancels `connectionScope` and cleans up `TermuxBridge`) is now called at both sites; every closed tab was leaking a Kotlin `SupervisorJob` scope and a `TermuxBridge` + write scope until process death
- **`PortForwardingManager.cleanup` never actually stopped tunnels** — `forwardingScope.launch { stopAllTunnels() }` was immediately followed by `forwardingScope.cancel()`, which cancelled the just-launched coroutine before it ran; active port forwards stayed attached to the JSch Session until Session disconnect; cleanup now issues `delPortForwardingL/R` directly on the calling thread before cancelling the scope
- **`SSHConnection` channel leak on `connect()` failure** — `openChannel()` allocates a slot on the JSch Session; any throw between `openChannel()` and the `openChannels.add()` tracking call left the channel forever attached to the Session; added `catch { ch.disconnect(); throw }` to the exec, shell, and sftp channel open paths
- **`VncDirectConnector` socket file-descriptor leak on connect failure** — `Socket()` was allocated then `socket.connect()` or `RfbClient` constructor could throw; caller never receives the socket so the fd leaked until GC finalised it; wrapped in `catch(Throwable) { socket.close(); throw }`
- **`TabManager` `ArithmeticException` on empty tab list** — `switchToNextTab`, `switchToPreviousTab`, and the `Ctrl+Tab` / `Ctrl+Shift+Tab` keyboard shortcut path computed `% tabs.size` with no empty-list guard; added `if (tabs.isEmpty()) return` guards to all three paths
- **`MetricsCollector.previousNetworkStats!!` TOCTOU race** — field checked non-null then force-dereferenced; a concurrent `resetNetworkStats()` on another thread could null it between the two reads; captured into a local `val` first
- **Long press shows terminal menu again** — all three `TerminalView` wiring sites in `TabTerminalActivity` had `onContextMenuRequested` pointing at `beginSelection()` (copy/paste ActionMode) instead of `showTerminalMenu()` (the bottom-sheet action menu); long press now reliably shows the menu on URL and non-URL text alike; text selection is available via "Select Text…" in the clipboard menu (📋)
- **`ClusterCommandExecutor` SSH session + scope leak on error** — `SSHConnection` and `CoroutineScope(SupervisorJob)` were not cleaned up when `connect()` / `executeCommand()` threw; a `finally{}` block now always calls `disconnect()` and `scope.cancel()`
- **`PerformanceFragment` orphan coroutine scope per connect** — `SSHConnection` was constructed with a throwaway `CoroutineScope(Dispatchers.IO)` per tap; now routes through `app.applicationScope` matching the pattern used elsewhere
- **`SAFSyncManager.lastError!!` NPE race** — four sites assigned `lastError` then force-dereferenced it; a concurrent write on `Dispatchers.IO` could null the field between those two statements; all sites now capture a local `val` first
- **`MetricsCollector.parseNetworkStats` off-by-one** — guard `parts.size < 10` failed to protect the `parts[10]` read (txPackets); tightened to `< 11`

- **Ed25519 / RSA / DSA / ECDSA public-key export wrong format** — `KeyStorage.encode*PublicKey()` all called `key.encoded` which returns the X.509 SPKI/DER blob; sshd silently rejects SPKI-encoded `authorized_keys` lines; all four helpers now build the correct OpenSSH SSH wire format (length-prefixed type string + key-type-specific payload per RFC 4253 §6.6)
- **Vertical spacing setting has no effect** — `TerminalPagerAdapter` had no `lineSpacingPercent` parameter so every new terminal view used the default 1.2×; `applyTerminalUiPrefs()` only updated the active view; `lineSpacingPercent` now passed to the adapter at construction and applied in `onCreateViewHolder`; adapter exposes `setLineSpacingPercent()` called from `applyTerminalUiPrefs()` to update all bound views
- **Reverse-scroll direction setting has no effect after returning from Settings** — `applyTerminalUiPrefs()` never updated `reverseScrollDirection` on live views; now calls adapter `setReverseScrollDirection()` which updates all bound terminal views in place
- **Import/Export crash on fast tap** — `backupManager` was initialised in a background coroutine; tapping any card before it finished threw `UninitializedPropertyAccessException`; converted to nullable with a "not ready" message guard at each call site
- **Saved password not cleared when "Save Password" unchecked** — editing a connection and unchecking "Save Password" left the old Keystore credential in place; it was silently reused on the next connect; now calls `clearPassword(profile.id)` on uncheck
- **`finish()` indentation trap in connection-failure path** — the `finish()` call in the null-errorInfo branch was indented at the outer scope level, making it look like it ran for both errorInfo paths; re-indented to match its actual inner-`else` scope
- **`DynamicForward` host-qualified spec silently dropped** — `"127.0.0.1:1080"` form failed `toIntOrNull()` and was dropped without error; now uses `substringAfterLast(':')` to handle both bare port and `[host:]port` forms including IPv6
- **Monitoring cooldown never synced** — `monitoring_alert_cooldown_minutes` is stored as a string `"60"` but `toAnyMap()` converts numeric strings to `Int`; the apply side then `value as String` threw `ClassCastException` silently every sync; now coerces via `when (value) { is Number → toString(); is String → value }`
- **Cluster broadcast dialog empty** — `setMessage` + `setView` conflict silently dropped the "Send to N sessions" message; count now in the title, hint on the `EditText`
- **Split-pane SSH session leak** — `closeSplitPane()` and `onDestroy()` called `tab.disconnect()` but never `sshSessionManager.closeConnection()`; JSch session stayed open, notification persisted, slot never freed
- **`computeScroll` blank strip after `clear`** — `scrollYf` was not clamped in `computeScroll`; scrollback buffer shrink mid-fling left `scroller.currY` above the new max, rendering a blank strip; now `coerceIn(0f, maxScrollYPx())`
- **Pinch-to-zoom triggers spurious selection** — `ACTION_DOWN` with `pointerCount == 1` entered selection mode before the second finger arrived; now defers via `postDelayed` and cancels on `ACTION_POINTER_DOWN`
- **`screen` session attached status always false** — `awk '{print $1}'` stripped the `(Attached)`/`(Detached)` suffix before the `contains` check; removed awk, kept full line, split on `\t`
- **`screen` session names truncated at first dot** — `split(".")` took only segment 1; `dev.backend.api` showed as `backend`; now `split(".", limit = 2)`
- **`setAutoBackup` vs `setAutoBackupEnabled` alias mismatch** — `BackupImporter` was calling the legacy alias; unified to `setAutoBackupEnabled`
- **`setCursorBlink` vs `setCursorBlinkEnabled` alias mismatch** — same; unified to `setCursorBlinkEnabled`
- **Ed25519 export breaks on API < 33** — `generateEd25519KeyPair()` silently falls back to ECDSA P-256 on API < 33; `encodeEd25519PublicKey` then called `takeLast(32)` on a 91-byte EC SPKI blob producing garbage; now detects `ECPublicKey` and dispatches to `encodeECDSAPublicKey`; also validates the expected 44-byte SPKI length before extracting
- **Proxy `bypassHosts` round-trip data corruption** — separator changed from `","` to `"\n"` in backup export and sync collect; restore paths accept both for backward compat; commas are valid inside bypass-list entries and were splitting single entries into multiple on restore
- **DynamicForward bind address silently forced to 127.0.0.1** — the parsed bind address was discarded; a new `parseDynamicForwardSpec` helper preserves it and supports bare port, IPv4, and IPv6 `[::1]:port` forms; `parseForwardSpec` for LocalForward/RemoteForward also updated to handle IPv6 brackets
- **Run-batched renderer: character after wide glyph draws at wrong column** — after flushing for a wide char, `runStyle` was not reset; the next normal character's `sameStyle` check compared against the stale value and skipped setting `runStartCol`; `runStyle = 0L` now set after every wide-glyph draw; wide glyphs now use a separate `wideCharBuf` so `charBuf` aliasing cannot cause a future regression
- **Sync string casts for `frequency`, multiplexer prefixes, and `bypassHosts`** — all remaining `value as String` casts on ListPreference / string keys now use the defensive `when (value) { is String -> value; is Number -> value.toString(); else -> default }` pattern matching `monitoring_alert_cooldown_minutes`
- **`boundViewHolders.forEach` ConcurrentModificationException risk** — `setLineSpacingPercent` and `setReverseScrollDirection` now snapshot to `toList()` before iterating
- **`getItemCounts()` undercounting** — only counted 5 of 16 entity types; now counts all: connections, keys, themes, host keys, workspaces, snippets, identities, groups, hypervisors, certificates, macros, monitor slots, hypervisor accounts, VNC hosts, VNC identities, cloud accounts
- **`applySecrets()` silent failure** — missing `SecurePasswordManager` or `KeyStorage` (e.g. during test runs) now logs a warning instead of silently dropping all credentials
- **Terminal menu tab list wrong tab on stale index** — tapping a tab in the long-press menu after another tab closed activated the wrong tab; row click now resolves the live index by `tabId` instead of using the open-time snapshot position

- **Scroll direction preference** — `terminal_reverse_scroll` in Settings → Terminal; OFF (default) = swipe UP to see older output, matching JuiceSSH/Termux/ConnectBot; ON = old TabSSH inverted behaviour for users accustomed to it

### Changed

- **Long press = terminal menu (non-URL) / URL dialog (URL)** — long press on a URL opens the URL open/copy dialog as before; long press on non-URL text now opens the terminal action menu instead of starting text selection; copy/paste lives on the dedicated clipboard key in the keyboard bar
- **Terminal scroll rendering: run-batched drawText** — render loop previously called `canvas.drawText` once per character (~2 000 JNI calls/frame on an 80×25 terminal); now batches consecutive characters that share the same foreground colour and text effects into a single `drawText` call per run, reducing JNI draw calls by ~20×; double-width glyphs still draw solo; scroll invalidation changed from `postInvalidateOnAnimation` to `invalidate` for immediate 1:1 finger tracking

- **Terminal long-press menu redesigned** — full MD3 bottom sheet with drag handle, prominent "New Tab…" outlined button, tab list with per-row connection-state icon (green/amber/red/grey) and bold label for the active tab, plus two new sections ("Terminal" and "Session") covering all actions; removed paste (lives on the key bar); added Copy Screen, Snippets, Broadcast to All Tabs, and Share Session
- **Changelog hygiene rule** — CLAUDE.md now mandates that every user-visible commit updates both `CHANGELOG.md` and `app/src/main/assets/whats_new.md` in the same commit

### Changed

- **Terminal menu expanded** — long-press menu now includes: Toggle System Keyboard, Toggle Key Bar (label reflects current state), Find in Scrollback, Paste — previously only reachable via the command palette or keyboard shortcuts
- **Settings reorganised** — multiplexer settings (gesture toggle, gesture type, per-type prefix keys) moved from Settings → Terminal → Behavior to Settings → Connection → Multiplexer; Terminal settings now contains only terminal-display and input options
- **PRE key label** — the PRE key now shows the configured prefix shorthand (e.g. `^B`, `^A`, `^G`, `M-b`) while a multiplexer is active instead of always showing `PRE`; reverts to `PRE` when no multiplexer is detected
- **Prefix examples in settings** — each multiplexer prefix field (tmux, screen, zellij) now shows an inline example dialog explaining all supported notations: `C-b`, `^b`, `C-Space`, `M-b`, `Alt-b`, `0x02`, literal characters
- **CTL / ALT active state** — latched modifier keys now render with a solid green fill and dark green text (WCAG AA contrast) instead of a mere alpha change; the same green-fill treatment applies to the PRE key when a multiplexer is active
- **Keyboard key widths** — CTL, TAB, ENT, ESC reduced from 2× to 1.5× so the label fills the box rather than floating in empty space; text size bumped 12 → 13 sp
- **Smooth scrolling** — `scrollYf: Float` replaces the integer `scrollY`, with a canvas pre-translate by the sub-row fractional offset; rows now glide continuously instead of snapping a full row at a time, eliminating the jagged/jumpy feel
- **Scroll direction default** — standard mobile convention (swipe UP = older content) is now the default; old inverted direction is available as a preference

### Fixed

- **Password dialog shows no prompt text** — `setMessage` and `setView` both own the dialog's content area; the message was silently dropped; message now rendered as a `TextView` inside the same `FrameLayout` container as the `EditText`
- **Search overlay always "No active session"** — `setupSearchOverlay()` called from `onCreate()` before any tab exists always produced a null controller; `showSearchOverlay()` now lazily calls `setupSearchOverlay()` against the live active view on first use
- **Double `finish()` on clean tab exit** — `updateTabIcon` called `tabManager.closeTab()` then `finish()` when count hit 0; `closeTab` already fires `onTabClosed` which calls `finish()`; removed the duplicate call
- **`delay(200)` connect race** — replaced the fixed 200 ms sleep with `withContext(Dispatchers.Main) {}` which enqueues after the `Handler.post { addTabToUI() }` already queued by `onTabCreated`; applies to both SSH and Telnet connect paths
- **Blank terminal on tab-create failure** — `connectToProfile` showed an error toast but did not call `finish()` when `tab == null`; user was left on a blank unusable screen
- **`conn.disconnect()` on main thread** — `onDisconnected()` fires on the main looper via `TermuxBridge.runOnMain`; calling `conn.disconnect()` there blocks on JSch's socket teardown; moved to `connectionScope.launch { }` (Dispatchers.IO)
- **Long-press URL / context-menu on wrong row when scrolled** — `getTextAtPosition` computed row as `(y + scrollYInt) / cellHeight` (single division, truncation mismatch); now uses two-step `screenRow + scrollRows` matching `renderTermuxBuffer` exactly
- **Multiplexer detection loop runs when mode is OFF** — `detectMultiplexerViaExec()` probed every 30 s regardless of profile setting; now guarded by `if (profile.multiplexerMode != "OFF")`
- **tmux session names containing `:` corrupt parse** — format string used `:` as separator; changed to `|` which tmux session names cannot contain
- **Scrollback broken when scrolled** — `fracOffset` was computed as `scrollYf - View.getScrollY()` but `View.getScrollY()` is always 0 because we never call `View.scrollTo()`; this made `fracOffset` equal the full pixel scroll offset, shifting all terminal content off-screen the moment the user scrolled into scrollback; fixed to `scrollYf % cellHeight` (the true sub-row fractional remainder)
- **Mosh legacy field removed** — the "Global Mosh Server Command (legacy)" preference in Settings → Connection is gone; mosh command is configured per-connection in the connection editor
- **Notification "Disconnect" button silent** — `ConfirmDisconnectActivity` now disconnects via `TabManager.getAllTabs().find(profileId)?.disconnect()` so it works whether or not the connection is still in `SSHSessionManager.activeConnections` (which it may not be if the session already dropped)
- **Notification doesn't disappear on disconnect** — `SSHConnectionService.onConnectionStateChanged(DISCONNECTED)` was silently delegating to `onConnectionClosed` which is only called by explicit `closeConnection()`, not by natural remote-side disconnects; now updates the notification directly via the same `renderHostNotification(disconnectingState=true)` path

- **PRE keyboard key** — new PREFIX action key in the default keyboard bar (row 3, directly under ENT); 2× wide, sends the correct multiplexer prefix byte (C-b for tmux, C-a for screen, C-g for zellij); turns green when a multiplexer is detected, dims when none is active
- **Multiplexer auto-detection** — after connect, probes `$TMUX`, `$STY`, `$ZELLIJ_SESSION_NAME` via a background exec channel; re-probes every 30 s so the PRE key reacts to the user attaching or detaching a multiplexer without reconnecting
- **Multiplexer picker dialog** — tapping PRE with no multiplexer detected shows a type picker (tmux/screen/zellij) instead of sending a stray control byte into a non-multiplexer session
- **PRE key picker rendered blank** — `setMessage` and `setItems` both occupy the dialog body in `MaterialAlertDialogBuilder`; the message silently hid the item list so nothing was selectable; moved the hint into the title so the three options now render correctly
- **Per-multiplexer prefix settings** — Settings → Connection now has a "Multiplexer Prefixes" section to configure each type's prefix independently (tmux C-b, screen C-a, zellij C-g) — previously hidden behind a single global field
- **SSH key alias system** — keys are assigned an SSH-convention alias (`id_ed25519`, `id_rsa_001`, etc.) at import time; used to automatically resolve `IdentityFile` paths during `~/.ssh/config` import without manual key assignment
- **Smart SSH key naming** — key comment field now extracted from the OpenSSH v1 binary format (was always empty before); import dialog shows both Name (default = comment) and Alias (default = SSH convention) fields
- **Mosh command preset dropdown** — per-connection picker in the connection editor with common presets (Default, port range, IPv4/IPv6-only, full locale, custom path) plus a Custom option; replaces the global preference string that was never read by the app
- **Global SSH directive defaults** — Settings → Connection now exposes `Keepalive Interval` (seconds), `X11 Forwarding` default, and `Agent Forwarding` default; these feed new per-host `serverAliveInterval` (nullable, null = use global), `x11_forwarding_default`, and `agent_forwarding_default` fields
- DB migration v37 → v38: `stored_keys.alias` (TEXT, nullable), `connections.server_alive_interval` (INTEGER, nullable)

### Changed

- **Connection notifications** — title now shows the user-facing connection name ("prod server") instead of the raw IP; body shows protocol/terminal title separately; makes the notification drawer readable when you have multiple servers
- **`~/.ssh/config` import** — after parsing, resolve each `IdentityFile` basename against stored key aliases and fall back to key name; connections with a matching imported key have `keyId` set immediately (no manual assignment step)
- **`ServerAliveInterval`** from `~/.ssh/config` is now stored per-connection and applied at connect time; previously hardcoded to 60 s globally regardless of the config file value
- **Mosh bootstrap** — reads per-connection `advancedSettings["moshServerCommand"]` if set; fixes the hardcoded wrong default (`-s` flag was included, which causes `mosh-server` to block waiting on stdin)

### Fixed

- **Terminal long-press menu silent no-op** — pager adapter now calls `beginWordSelectionAtTouch` directly on each TerminalView instead of routing through `getActiveTerminalView()`, which could return null during RecyclerView relayouts or the wrong view during fast tab switches
- **Drag-to-select text jumps / selection vanishes** — added a 2× snap-radius proximity guard in `handleSelectionTouch`; tapping near a handle circle (which is drawn below the selection highlight) no longer fires `exitSelectionMode()` before the drag can begin
- **Identity and all ExposedDropdownMenu dropdowns empty** — replaced `AutoCompleteTextView` with `MaterialAutoCompleteTextView` across all 9 affected layout files; base class filters against current text so restored values hid all items
- **Tasker `ACTION_CONNECT` orphan sessions** — `TaskerWorker.handleConnect` now routes through `TabManager.createTab() + tab.connect()` so Tasker-initiated sessions appear in the tab bar and can be disconnected by the user
- **CI security grep false positive** — `passwordLayout?.error = "…"` matched the password-literal pattern; exclusion added with documentation in AI.md §14.3

## [0.9.1] - 2026-06-04

### Added

- **Sync password verification** — opening an existing sync file now prompts for the password and verifies it before accepting, preventing silent data corruption
- **Cloud Accounts Manager** — unified screen for DigitalOcean, Hetzner, Linode, Vultr, AWS EC2, GCP, Azure, and OCI; OCI moved out of Hypervisors into Cloud Accounts
- **RequestTTY directive** — SSH config `RequestTTY` is now honoured for exec channels
- **Zellij support** — auto-attach/create for Zellij alongside existing tmux/screen support
- **Connection count tracking** — per-host usage statistics; cloud instances carry SSH credentials directly
- **Room migration test suite** — automated tests covering v32→v37 schema migrations
- **VNC arrow key toolbar** — on-screen arrow keys added to the VNC session toolbar
- **Clipboard key + key bar toggle** — new CLIPBOARD key in the SSH keyboard bar; bar can be toggled on/off

### Changed

- **Sync/backup toggles default to enabled** — all content categories (connections, keys, snippets, identities, themes) now default on; users opt out rather than in
- **Sync network default** — default changed from WiFi-only to WiFi + mobile
- **Hypervisor card UI** — redesigned VM cards; SSH/VNC Connect buttons appear before Start/Stop power buttons
- **Nav drawer** — reorganised: Accounts section added; Import/Export moved into Settings
- **Cloud Accounts** — merged into the Infra tab alongside Hypervisors

### Fixed

**Sync / Backup**
- Sync settings not persisting across app restarts (toggles ignored, prefs not round-tripped)
- Auth type displayed as "Password" for SSH key connections in backup/sync/pairing exports
- Remaining auth type format bugs in the pairing QR flow

**VNC / Console**
- Comprehensive RFB protocol rewrite: correct initial handshake, pixel format, framebuffer update request sequence
- VNC WebSocket subprotocol negotiation failure
- `inflate Z_STREAM_END` hang blocking ZRLE-encoded sessions
- ZRLE stream desync after partial tile reads
- Unknown QEMU audio vendor message (type `0xE0`) causing session drop
- Tight encoding: old-style palette filter causing stream desync
- Tight encoding: `ExplicitFilter` bit and int overflow
- VNC key input mapping; cursor not shown by default
- VNC screen freeze — now prefer ZRLE over Tight for QEMU/Proxmox
- Auto-reconnect without resize for hypervisor VNC (Proxmox)
- `IOException` crash in VNC console channel writer thread
- VNC resize: enable Proxmox resize; auto-reconnect on server rejection
- Duplicate `KeyUp` events sent on key release
- Missing `layout_width`/`height` crash in VNC toolbar

**Terminal / Tabs**
- Swipe feedback loop root cause (deferred `isUpdatingAdapter` flag clear in ViewPager2)
- Tab swipe freeze caused by `TransactionTooLargeException` in `ReportIssueDialog`
- `CancellationException` swallowing; real tab persistence across reconnects
- "New connection" always reattaching to an existing tab instead of opening a new one
- Active sessions strip showing stale entries; red dots not clearing; frequent-connects menu broken
- New-tab reattach bug; multi-session chooser not appearing; cursor disappearing after `DECTCEM`
- Long-press copy; missing Cancel button in ActionMode; word-wrap URL detection
- Selection drag; Vim navigation keys; 4 crash-on-bad-input paths
- `SIGWINCH` double-fire on terminal resize (debounced)
- `historyCache` unbounded growth; split-pane cancellation leak

**Cloud / Hypervisors**
- OCI pagination returning only the first page of instances
- OCI TLS certificate pin not persisting in the cloud account manager
- OCI credential persistence and import UX
- OCI cloud account add/edit/import flow
- Hypervisor API: response body leaks, missing confirm dialogs, poor error UX
- Hypervisor API: missing timeouts; call cancellation on `onDestroy`
- VMware re-authentication on token expiry
- Search on Hosts tab producing no results

**Security / Auth**
- TOFU host key certificate pin not persisting across app restarts
- Broken auth type picker; TOFU dead-end in port forwarding activity
- SSH key spinner race in proxy host selection
- Group-edit race conditions in `ConnectionEditActivity`
- Group field showing "No Group" when editing a grouped connection

**Performance / Stability**
- All blocking I/O moved off the main thread (Keystore, file ops, DB)
- Room: missing indexes on foreign keys and query columns; missing `@Transaction` guards; schema bumped to v37
- `TermuxBridge`: thread safety violations, read loop inefficiencies
- `TabTerminalActivity`: coroutine leaks, dead code, `warningsJob` accumulation, `splitTab` leak
- Soft foreign key orphans; mosh re-entry leak; unbounded `Flow` collectors
- `DiffUtil` not used in list adapters; `HostKeyDao` full-table scans
- `PreferenceManager` double-initialisation on startup
- Language picker not wired up; untranslated locales removed

---

## [0.9.0] - Initial release

### Added

- Browser-style tabbed SSH sessions with swipe navigation
- Full VT100/ANSI/xterm-256color terminal emulation via Termux TerminalEmulator
- SSH authentication: password, public key (RSA/ECDSA/Ed25519), keyboard-interactive
- SSH key management: import, generate, passphrase protection, OpenSSH certificates
- SFTP file browser with upload/download/rename/chmod/delete and remote editor
- Port forwarding: local, remote, dynamic SOCKS5
- X11 forwarding via Termux:X11
- ProxyJump multi-hop connections
- Port knocking before connect
- Agent forwarding
- Session recording and replay
- `~/.ssh/config` import
- Snippet library with `{var}` placeholder substitution
- Macro recording (raw byte sequences)
- Mosh protocol support
- Tmux/screen/zellij auto-attach and create-new modes
- Post-connect script execution
- Find-in-scrollback with prev/next navigation
- Proxmox VE, XCP-ng/Xen Orchestra, VMware vSphere, QEMU/libvirt hypervisor management
- VNC/RFB console client (Tight, ZRLE, CopyRect, Hextile encodings)
- 22 built-in terminal themes (Dracula, Solarized, Nord, Monokai, One Dark, Tokyo Night, Gruvbox, and more)
- Custom theme editor with WCAG 2.1 contrast validation; import/export JSON
- Material Design 3 UI with dark/light/auto mode
- Custom SSH keyboard bar (1–5 configurable rows)
- Hardware keyboard support with AltGr, xterm modifier-encoded arrows
- TalkBack / accessibility support
- Translations: English, Spanish, French, German
- Android Keystore hardware-backed credential encryption (AES-256-GCM)
- Biometric unlock for stored credentials
- Host key TOFU with SHA-256 fingerprints and MITM detection
- Screenshot protection (`FLAG_SECURE`)
- Clipboard auto-clear for sensitive pastes
- Tasker integration and deep link support
- Home screen quick-connect widget
- Encrypted ZIP backup and restore
- SAF-based cross-device sync (Google Drive, Dropbox, OneDrive, Nextcloud, local storage)
- Background SSH monitoring with CPU/memory/disk threshold alerts
- Connection groups with expand/collapse
- Real-time search with 8 sort options
- Connection statistics and per-host audit log
- Multi-host dashboard with live CPU/memory/disk metrics

[Unreleased]: https://github.com/tabssh/android/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/tabssh/android/compare/v0.9.1...v1.0.0
[0.9.1]: https://github.com/tabssh/android/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/tabssh/android/releases/tag/v0.9.0

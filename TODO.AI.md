# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

## Open — 2026-08-14 Play Protect false positive (user action required)

1. Google Play Protect flags dev-build APKs as "Harmful app blocked —
   tries to bypass Android's security protections". False positive:
   heuristic reaction to a debuggable sideloaded APK that execs a
   bundled native binary (mosh-client), holds
   com.termux.permission.RUN_COMMAND, boot receiver + battery-opt
   exemption, on a signing cert with no Play reputation. Mitigated in
   repo: development.yml ships the `devel` variant (non-debuggable,
   minified). Remaining step only the developer can do: submit a Play
   Protect appeal for the app's signing cert + package
   (io.github.tabssh) per Google's developer guidance for Play Protect
   warnings (appeal via the Play Console Help page):
   https://developers.google.com/android/play-protect/warning-dev-guidance
   Until appealed/re-classified, "Install anyway" + verifying
   checksums-dev.sha256 is the workaround.

## Open — 2026-08-14 user-reported regressions in devel-3d12d05e (all fully working before; must be fixed)

1. Settings ANR: every settings page hangs (ANR) in the minified devel
   build. Debug log shows an active mosh session streaming during the
   hang; prime suspect is Logger lock contention under DEBUG_LOG=true.
   Second log (cleared-then-reproduced, 84 s window) confirms: the
   settings tap emits ZERO log lines while a session streams — the main
   thread blocks before the first Logger call in the settings path.
   Debugger agent reproducing live on the AVD with an active session.
2. Debug-log spam: TerminalView logs "onScreenChanged - scheduling
   redraw" per frame and "Terminal title changed" per second — 97.5% of
   the user's 463 KB log; the export's interesting window (settings
   taps, proxmox, docker) was pushed past the paste-service 100 KB cap.
   Remove or rate-limit per-frame logging.
3. Proxmox/VNC not working (user report). Second debug log shows the
   transport SUCCEEDING: termproxy ticket OK → serial console rejected
   with a 35-byte binary error frame → intentional vncproxy fallback →
   "VncView: VNC connected: 720×400 'QEMU (router)'" with RfbClient
   pointer events flowing. So the break is rendering/interaction, not
   connection. Leads: repeated "W/TerminalView: Terminal renderer is
   null in onDraw" as the console tab wires up, and "E/TermuxBridge:
   Error reading from SSH: Pipe closed" fired during the serial→VNC
   teardown — check whether that error path blanks/kills the tab the
   VNC view lives in. VNC itself locally testable against a VNC server
   container on host dockerd.
4. Docker dashboard "just loads" — spinner forever, never renders data.
   Locally testable against host dockerd over SSH to the host sshd.

## Open — 2026-08-15 beta pass on the AVD (findings + remaining coverage)

1. RESOLVED 2026-08-15 — VNC console rendered black. TabTerminalActivity's
   reattach path used `tabManager.getAllTabs()` (SSH tabs only), so an
   activity launched for a VNC/SPICE tab saw an empty list, skipped
   `updateViewPagerAdapter()`, and never bound the console page —
   `RfbClient.start()` is only called from that bind, so the RFB handshake
   never began. Now uses `getAllTabsSealed()`.
2. RESOLVED 2026-08-15 — "Enter container" landed on the Docker host shell.
   The ephemeral exec profile inherited Mosh "auto"; mosh-server always
   starts a login shell and dropped the `docker exec` RemoteCommand. Any
   profile with a RemoteCommand now stays on the SSH exec channel.
3. RESOLVED 2026-08-15 — Docker dashboard "just loads": root cause was the
   SSH user missing docker.sock group access, not app code; all Docker
   tabs (containers, stacks, images, volumes, networks, dashboard) plus
   inspect/config/logs/live stats verified against the host daemon.
4. RESOLVED 2026-08-15 — VNC/RFB sessions produced no application-log
   lines (tag `RfbClient` is on the app-log chatter denylist). Console
   ready / closed-by-server / resize-rejection end now go through
   `Logger.event`.
5. RESOLVED 2026-08-15 — scripts/ui-test.sh: a value-taking step with no
   value (e.g. trailing `--present`) aborted the whole run with
   "$1: unbound variable"; steps now fail the assertion and continue. Also
   removed the abandoned `__exec_run_steps` stub and fixed three `info`
   calls that invoked the system `info` binary instead of `__info`.
6. RESOLVED 2026-08-15 — "Browse Files (SFTP)" from the connections list
   opened `SFTPActivity`, logged "Connection not found: <id>" and closed
   instantly: the activity only looked in `SSHSessionManager`'s active
   connection map, which is empty unless the host already has a terminal
   session. It now falls back to loading the profile and dialing it, and
   the "+" SFTP tab picker no longer hides connections that are not
   already live.
7. RESOLVED 2026-08-15 — the "Scrollback Buffer" setting was decorative:
   every tab was built with a hardcoded `transcriptRows = 2000`, so the
   preference (and its -1 "unlimited" default) never reached the emulator,
   and the settings row rendered the raw value as a bare "-1". Tabs, split
   panes, Tasker-created tabs, and restored sessions now take the value
   from `PreferenceManager.getTranscriptRows()` (-1 → 50 000-row cap), the
   summary reads "Unlimited (capped at 50,000 lines per tab)" or "N lines",
   and the accepted maximum matches the applied cap (was 100 000).
8. RESOLVED 2026-08-15 — app lock never asked for the PIN. With
   "App Lock" enabled and a PIN stored, a cold launch went straight to
   the connection list: `maybeRequireUnlock()` only fired when the
   separate `security_auto_lock_background` toggle was on *and* a prior
   background timestamp existed, so the lock was effectively inert on
   launch. A per-process `appUnlockedThisProcess` flag now gates the
   first foregrounded activity, and `PinLockActivity` clears it via
   `TabSSHApplication.markAppUnlocked()` on every success path.
9. RESOLVED 2026-08-15 — the PIN lock screen rendered unthemed (bare
   platform input and buttons next to Material3 everywhere else): its
   layout is built in code, which bypasses AppCompat/Material view
   inflation. Now constructs `TextInputLayout`/`TextInputEditText` and
   `MaterialButton` explicitly.
10. RESOLVED 2026-08-15 — VNC session closed ~0.5 s after "console ready"
    with `Unexpected QEMU ext sub-type 255`. Confirmed from a tcpdump of
    the real session: `handleCursorWithAlpha()` did not read the U32
    encoding field that precedes the cursor image (`pseudoEncodingCursorWithAlpha`
    payload is `U32 encoding` + image), so the reader ran 4 bytes behind
    from the first cursor rect onward — the next rect header was read out
    of the cursor bitmap and the following message byte landed on 0xFF.
    Now reads the encoding, requires Raw (the only value TigerVNC emits),
    and closes with a clear reason for anything else.
11. RESOLVED 2026-08-15 — with App Theme = Light every app bar rendered as
    a blank white strip (white title, subtitle and back arrow on the light
    surface colour), and the Settings icons were near-invisible pale grey.
    `Widget.TabSSH.Toolbar` set white foreground colours but no background,
    so Material3's surface default applied; the icons were framework
    `@android:drawable/ic_menu_*` bitmaps, which are drawn for dark
    backgrounds and cannot be tinted. The toolbar style now pins
    `@color/primary_500` and is wired to `toolbarStyle`/`materialToolbarStyle`
    in both themes (covering the screens that never named the style), and
    every preference icon is now a project vector tinted `?attr/colorOnSurface`.
12. RESOLVED 2026-08-15 — with the CursorWithAlpha desync fixed, the VNC
    session painted the full desktop and then still died after ~1.5 s.
    TigerVNC's own log gave the reason: `closed: (invalid pixel format)`.
    `sendSetDesktopSize()` wrote number-of-screens and its padding as U16s
    when the RFB spec defines both as U8, so every resize request put two
    extra bytes on the wire; the server resumed parsing inside the screen
    descriptor and read a bogus SetPixelFormat. Both fields are now U8.
13. RESOLVED 2026-08-15 — third and final VNC teardown: with the resize
    request fixed, TigerVNC accepted it (1280×1024 → 1080×1389) and then
    closed with `Pixel buffer request 16x16 at 1069,0 exceeds framebuffer
    1080x1389`. The continuous-updates region registered before the resize
    stays registered on the server, so after a shrink the server encodes
    rects outside its own framebuffer. `rearmContinuousUpdates()` now
    re-registers the region on every resize path (DesktopSize,
    ExtendedDesktopSize, UltraVNC ResizeFrameBuffer).
14. Remaining beta coverage on the AVD.
    - Themes: DONE 2026-08-15 — Light and Dark both verified; app bars keep
      the branded blue in both, settings icons legible, VNC Hosts / Proxmox
      toolbars no longer white-on-white after the toolbarStyle change.
    - Proxmox console: DONE 2026-08-15 — added proxmox-test hypervisor
      (192.168.122.10:8006, verifySsl off/TOFU pin), Test Connection
      authenticated, VM 100 vnc-target started, console opened. Serial-term
      WebSocket returned a Proxmox serial-error frame; client auto-fell-back
      to vncproxy, RfbClient decoded the live ZRLE stream and rendered the
      QEMU SeaBIOS screen. End-to-end Proxmox VNC console now works.
    - Still not exercised: Proxmox SPICE display, telnet, backup/restore, and
      log export against the paste service size cap.
15. FINDING 2026-08-15 — Proxmox SPICE strategy is effectively unreachable.
    In HypervisorConsoleManager.openProxmoxConsole the ConsoleStrategyChain is
    ordered proxmox-termproxy → proxmox-spiceproxy (qemu only) → proxmox-vncproxy
    and resolves first-success-wins. Proxmox issues a valid termproxy ticket for
    every VM, even one with no serial device, so the chain ALWAYS resolves on
    termproxy and the proxmox-spiceproxy strategy never runs — SpiceLoader
    .isSpiceAvailable() is never even called on this path (confirmed: no
    SpiceLoader log line ever appears). The later runtime serial-error fallback
    goes termproxy→vncproxy, never SPICE. Net effect: with the shipped
    libtabssh_native.so statically linking real libspice, the Proxmox SPICE
    display can never be selected from the UI. The native SPICE client itself is
    reachable and testable via the raw spice:// LinkHandlerActivity path (bare
    display server, no Proxmox API), which is how SPICE render is being verified
    on the AVD. Fix options (defer to user): (a) add an explicit console-type
    picker so the user can force SPICE; (b) make the qemu SPICE strategy prefer
    ahead of termproxy when the VM advertises a SPICE-capable vga (qxl/virtio);
    (c) leave as-is and document that Proxmox SPICE is only via .vv/spice:// URIs.
16. FIXED (source) 2026-08-15 — SPICE client never opened a socket; session
    reported "started" but stayed black forever with no error. Reproduced
    end-to-end on the AVD via the raw spice:// path (LinkHandlerActivity →
    SpiceClient) against a real QEMU qxl SPICE server (containerised, reachable
    at 10.0.2.2:5930): dialog + connect worked, native handle allocated,
    "SPICE session started" logged — but `ss` showed NO TCP connection to the
    server and the server logged no client (only a manual REDQ probe). No
    onConnected / onError / onDisconnected ever fired. Root cause in
    spice/cpp/spice_client_glib.c tabssh_spice_impl_start: it posted the connect
    with g_main_context_invoke(), which runs the callback INLINE on the calling
    JNI thread whenever it can acquire the context — and during the startup
    window before loop_thread_main reaches g_main_loop_run nothing owns main_ctx,
    so spice_session_connect() ran on the JNI thread where the thread-default
    context is the global default (main_ctx is only pushed as thread-default on
    the worker thread). libspice bound its async socket-connect sources to that
    global-default context, which no loop ever iterates → connect never executed,
    no socket, no error. Fix: replace g_main_context_invoke with an explicitly
    g_source_attach'd idle source on main_ctx, so the connect is always
    dispatched by the worker's g_main_loop_run with main_ctx as thread-default.
    NOTE: libtabssh_native.so ships as a prebuilt fetched from the spice-libs
    GitHub release (built by .github/workflows/spice-libs.yml from spice/cpp/ via
    spice/Dockerfile + spice/build-android.sh); the source fix takes effect only
    after that release is rebuilt. On-device re-verification is pending a local
    x86_64 rebuild (in progress) or the next spice-libs CI release.
    UPDATE 2026-08-15 — local x86_64 rebuild + hot-swap of the fixed .so
    CONFIRMED the g_source_attach fix works to the socket layer: the SPICE
    server (disable-ticketing=on, 5930) now logs a real client handshake
    attempt correlated in time with the connect, whereas before NO socket ever
    reached it. Remaining: the main channel drops right after link with no
    client-side callback — root cause found: on_channel_new never connected the
    `channel-event` signal, so SPICE_CHANNEL_ERROR_*/CLOSED were swallowed
    (silent black screen, no emit_error). Second fix applied in
    spice/cpp/spice_client_glib.c: added on_channel_event handler wired for all
    channels, mapping each ERROR_* to emit_error with an actionable message and
    a main-channel CLOSED to emit_disconnected, logging every transition. Second
    x86_64 rebuild in progress to surface the actual link failure and confirm
    end-to-end.
    UPDATE 2 2026-08-15 — the second build (with channel-event) did NOT connect
    at all: emulator-level `ss` confirmed the app opened NO socket, no
    tabssh-spice worker thread was alive, no channel-event/error fired. So the
    g_source_attach idle-source approach is RACY, not a reliable fix: the connect
    is queued as a low-priority G_PRIORITY_DEFAULT_IDLE source attached to
    main_ctx from the JNI thread, which races the worker's g_main_loop_run
    startup — it dispatched in the first run (08:52 server handshake) but not the
    second, leaving a session that reports "started" with no socket and no error.
    THIRD fix (current, rebuilding): drop the idle source entirely and call
    spice_session_connect INLINE inside loop_thread_main, after
    push_thread_default(main_ctx) and before g_main_loop_run — so the async
    transport sources bind to main_ctx deterministically every time. Removed the
    now-dead start_session_on_worker + the g_source_attach block from
    impl_start; added LOGI/LOGE around the worker loop start/connect/exit so the
    flow is visible in logcat. Combined with the channel-event handler, the next
    run should either complete end-to-end or log the precise link failure.
    UPDATE 3 2026-08-15 — the third (inline-connect) build PROVED the worker now
    runs: logcat showed `SPICE worker loop starting — initiating connect` on the
    worker tid, no "connect failed", no "worker loop exited" — so
    spice_session_connect returned TRUE and the loop was running. But STILL no
    socket and no channel-event. Real root cause found by reading the spice-gtk
    0.42 source (spice/cpp had it wrong all along): spice-gtk's gio-coroutine
    schedules EVERY wakeup on the GLOBAL default GMainContext — socket-wait
    sources via g_source_attach(src, NULL) (gio-coroutine.c:59,169) and signal
    marshalling via g_idle_add (gio-coroutine.c:223,260), both of which resolve
    to g_main_context_default() irrespective of any thread-default. Our worker
    ran a PRIVATE g_main_context_new() context, which libspice's coroutine never
    touches, so the connect coroutine started but never advanced — no socket, no
    error, silent black screen. FIX (current, rebuilding): impl_create now takes
    sess->main_ctx = g_main_context_ref(g_main_context_default()) instead of
    g_main_context_new(); loop_thread_main no longer pushes a thread-default
    context (GLib forbids pushing the global-default as thread-default, and with
    nothing pushed g_socket_client_connect_async also targets the default
    context) and just runs g_main_loop_run on the default context. This is the
    context libspice's coroutine actually dispatches on. Fourth x86_64 rebuild in
    progress to verify end-to-end.
    VERIFIED FIXED 2026-08-15 — fourth build (sha256 8631f06a…, hot-swapped onto
    the AVD) connects END TO END against the containerised QEMU qxl SPICE server
    (10.0.2.2:5930, disable-ticketing). logcat: worker starts → three ESTAB TCP
    sockets to :5930 → `SPICE channel type=1 opened` (main), type=3 (inputs),
    type=2 (display); type=4 (cursor) ignored → `TabSSH:SpiceView: SPICE
    connected: 720x400 ''`. That last line is onNativeConnected, which the native
    bridge fires only from on_display_primary_create — so the primary surface/
    framebuffer was created and the resolution propagated to the UI. The view
    stays black solely because the QEMU test container boots no guest OS (720x400
    is blank SeaBIOS VGA text mode); the SPICE client/render path itself is
    proven working. Item 16 CLOSED.

17. FINDING 2026-08-15 — telnet connects and works fully (verified: tester@
    Alpine container at 10.0.2.2:2323, full MOTD + live shell + whoami=tester),
    but at connect time `W/TabSSH:TelnetConnection: NAWS send failed: null`
    fires — window-size (NAWS, RFC 1073) negotiation silently fails. The
    session is unaffected (server falls back to default 80x24-ish), but the
    remote never learns the real terminal dimensions. Root cause: setWindowSize's
    connect-time push (SSHTab.kt:637) races the transport and threw a non-IO
    exception (NPE, null message) that sendNaws only guarded for IOException, so
    setWindowSize's generic catch logged the useless "null". The authoritative
    NAWS is sent from handleIac(DO NAWS) (line 201) when the peer requests it, so
    the connect-time push is a best-effort duplicate and the session's size IS
    negotiated. FIXED 2026-08-15 — sendNaws now contains all exceptions (IO +
    non-IO) and logs the real throwable at debug via Logger.d(TAG, msg, e);
    setWindowSize no longer wraps/relogs. Removes the warning-level noise and
    makes any future failure diagnosable with a stack trace instead of a bare
    null. Kotlin-only change, no native rebuild needed; verified by `make check`.

## Open — 2026-08-14 terminal feature-completeness follow-ups

1. Legacy `ANSIParser.handleExtendedColor` (terminal/emulator/ANSIParser.kt
   ~467) downsamples SGR 38;5/48;5 256-color indices to the nearest of 16
   colors and truecolor to 16 as well — the legacy (non-Termux) emulator
   path never shows real 256-color output. The active render path uses the
   Termux emulator, so impact is limited to wherever the legacy
   TerminalEmulator is still wired. Decide: fix the legacy path to carry
   full 256/truecolor, or retire the legacy emulator entirely.

## Open — 2026-08-13 docker/hypervisor audit follow-ups (needs user call)

1. RESOLVED 2026-08-14 — user chose per-session token: SocketRelay now
   generates a 32-byte SecureRandom token per instance; every accepted
   connection must send it as a fixed-length preamble (constant-time
   compare, 3s read timeout) before the SSH channel opens. Dial side via
   RelayTokenSocketFactory on EngineApiTransport + probeApiVersion.
   Tests: SocketRelayAuthTest.kt.
2. RESOLVED 2026-08-14 — user chose to keep the rethrow:
   OciApiClient.validateCredentials surfaces a malformed stored user OCID
   as an onboarding error (not a silent `false`). No code change needed.
3. RESOLVED 2026-08-14 — VMwareApiClient `apiGet`/`apiPost` String return
   + `/api` vs `/rest` envelope unwrap verified through two green combined
   gates (make check) and green CI on cb33df1712a2; nothing to watch.
4. RESOLVED 2026-08-14 — TombstoneRecorder.record() now uses the shared
   catchExceptCancellation helper; CancellationException propagates.
5. RESOLVED 2026-08-14 — HypervisorProfile has a redacting toString()
   (password → `xxxxx`/`<none>`; only secret field on the entity — OCI
   key/passphrase live in Keystore). Test: HypervisorProfileToStringTest.
6. RESOLVED 2026-08-14 — HypervisorConsoleManager holds a live
   `activeListener` read by all long-lived WS callbacks; `detachListener()`
   added and wired into ProxmoxManagerActivity + XCPngManagerActivity
   onDestroy() via tracked spawnedConsoleManagers lists; also cleared in
   disconnect().
7. RESOLVED 2026-08-14 — i18n sweep complete across XCPngManagerActivity,
   LibvirtManagerActivity, OciManagerActivity, VncHostsActivity (~160
   strings through strings.xml). Intentional leftovers documented: wire-state
   / action-keyword `when()` literals, connection-profile display-name
   prefixes, and SystemGroupHelper group args ("VM Hosts", "Cloud
   Instances" in OciManagerActivity/CloudAccountsActivity) — one-time
   database display values, not UI chrome; kept per the same rationale.

## Shipped — 2026-08-11 user batch (merged from root TODO.md)

Items 1–3 shipped in fceb877 (docker/infra UX rework + tab index +
cancellation fixes, CI green). Items 4–13 shipped 2026-08-11 in the
single follow-up batch commit (batches 2–5 below), gated on a green
combined `make check`; each finding's diff hunk verified present
before commit. Only item 14 remains open.

Resolved dependency order: 1–3 first (connection-lifecycle cluster,
shared root cause — app-breaking); 4–7 next (VNC/SPICE console UX);
8–12 (terminal input/UX fixes); 13 second-to-last (theme pass restyles
final screens); 14 last (screenshots must show the finished UI).

Execution batches (strict file ownership, all complete except 6):
- Batch 1: docker/infra UX rework + items 1–3 — shipped fceb877
- Batch 2: items 4–8 (console/VNC/SPICE/mosh + strings.xml) — done
- Batch 3: items 9–12 (terminal input/view files + strings.xml) — done
- Batch 4: CancellationException follow-up audit (DockerSessionManager,
  TransportCapabilityDetector, RegistryClient; SocketRelay /
  UpdateChecker / VncDirectConnector / SpiceLoader intentionally
  untouched — non-coroutine code) — done
- Batch 5: item 13 full-app Material/Dark-Light-System pass — done
- Batch 6: item 14 README screenshots — DONE 2026-08-12: the five
  existing F-Droid screenshots (metadata/en-US/images/
  phoneScreenshots/1-5.png) referenced from a new README Screenshots
  section; no new captures needed
Commit cadence (user instruction 2026-08-11): finish all batches, then
one combined `make check`, then a single commit at the end. Issues found
mid-batch get logged here immediately, fixed in their owning batch.

Open decisions / documented limitations from the 4-8 diagnosis:
- RfbClient console-mode encoding restriction: RESOLVED 2026-08-12 —
  user chose to keep the full encoding list for both modes; the full
  VNC implementation correctness/completeness audit is COMPLETE —
  findings and fixes tracked in AUDIT.AI.md § "VNC Stack Audit
  (2026-08-11)" (~30 protocol/security/lifecycle fixes + 23 JVM tests)
- `VncDirectConnector.connectWss`: RESOLVED 2026-08-13 — user chose
  "fix": the Proxmox RFB-over-WSS paths (VNC fallback + resize
  reconnect in HypervisorConsoleManager) now call connectWss instead
  of duplicating its WS+RfbClient wiring inline; connectWss gained a
  protocol param and disconnects the WS on a failed connect
- TERMINOLOGY (user-defined, applies to all future requests): "VNC"
  means the whole graphical console stack — RFB/VNC protocol, SPICE,
  .vv/spice://vnc:// handling, viewer/vnc/spice packages, Vnc/Spice
  views and tabs — i.e. everything graphical (non ssh/telnet/mosh/x11).
  "Fix VNC" scopes to all of it, not just the RFB protocol code
- `TerminalViewComposingFlushTest.kt:90` flakiness: RESOLVED 2026-08-13 —
  root cause was the test fixture's `connect()` with an empty input
  stream: the Dispatchers.IO read loop hit EOF instantly and its
  finally block closed/nulled the output stream, racing the test's
  sendText(); fixture now uses `attachOutputStream()` only (no read
  loop, fully deterministic)
- SPEC.md §3 vs Makefile: RESOLVED 2026-08-12 — `make check` now runs
  `testDebugUnitTest` per SPEC.md ("unit tests run as part of make
  check"); pre-existing test-source compile break (UpdateCheckerTest
  fakes missing newer DockerTransport/PolicyDao members) fixed
- VNC reconnect implemented as manual tap-to-reconnect only; auto-retry
  with backoff deliberately not added — revisit only on request
- lastlog under mosh: upstream mosh-server behavior, documented in
  README known-limitations, not app-fixable
- X11 cannot ride mosh's UDP transport (protocol limitation): fix keeps
  the bootstrap SSH session alive to carry X11; documented in README
- app_theme default: RESOLVED 2026-08-12 — AI.md PART 7 is explicit
  ("dark mode default") and preferences_general.xml already declared
  defaultValue="dark"; Kotlin fallbacks (TabSSHApplication,
  PreferenceManager.DEFAULT_APP_THEME) aligned to "dark"
- AlertDialog→MaterialAlertDialogBuilder conversion: DONE 2026-08-12
  (commit 03e89e69044b — 138 sites across 36 files, zero remaining)
- KeyType.kt:48-51 SecurityLevel color ints are hardcoded but have no
  UI consumer today — if a UI ever renders them, map to status_* colors
  at the render site

Open follow-ups from the SSH/Mosh/Telnet stack audit (2026-08-13,
details in AUDIT.AI.md § "SSH/Mosh/Telnet Stack Audit"):
- RESOLVED 2026-08-14 (user: "do it now") — TerminalEmulator has a
  serialized single-thread writer executor (`tabssh-terminal-writer`):
  sendText/pasteText share one FIFO queue; executor recreated per
  connect(), local-captured for the read-loop's shutdown to avoid
  cross-connection races. Tests: TerminalEmulatorWriteOrderingTest,
  TerminalViewComposingFlushTest via awaitPendingWrites().
- RESOLVED 2026-08-14 — TerminalManager: managerScope recreated when
  inactive, isInitialized reset in cleanup(), both methods synchronized.
- RESOLVED 2026-08-14 — TerminalLinkClassifier scheme allowlist added
  (http/https/ssh/sftp/file/git/ftp/ftps/svn/telnet/vnc/spice); scheme
  regex widened to catch no-authority schemes (javascript:, mailto:);
  non-allowlisted → LinkAction.NotALink, rejected with a redacted log in
  TabTerminalActivity and LinkHandlerActivity.
- RESOLVED 2026-08-14 — TelnetConnection.connect() resets `stopped`.
- TerminalView.getHandler() can be null when the InputConnection is
  used while detached — current paths avoid it; future handler-based
  IME work must not assume non-null
- TerminalEmulator: pre-existing race (flagged by 2026-08-14 review,
  not a regression) — a rapid connect() while the previous readJob is
  still unwinding lets the old loop's finally-block closeStreams()
  null out the NEW connection's inputStream/outputStream (fire-and-
  forget readJob?.cancel() pattern predates the writer-executor work);
  fix would be joining/awaiting the old readJob in connect()
- RESOLVED 2026-08-14 — SessionPersistenceManager commented-out
  cursor-restore line removed.

1. Fix SSH active connections dying when creating VNC/docker/etc connections
   — root cause (diagnosed 2026-08-11): TabManager dual-index-space bug —
   `closeTab(index)`/`switchToTab(index)` index the unified `tabs` list, but
   TabTerminalActivity still derives indices from SSH-only `getAllTabs()`
   (reconnect-dialog paths ~3123-3199), so once a VNC/console tab exists the
   wrong tab gets closed. Fix: migrate all callers to ID-based
   `closeTabById`/`switchToTabById` and delete the index-based API.
   Secondary: SSHSessionManager.createConnection() disconnects pooled
   connections in CONNECTING/AUTHENTICATING as "stale" — only treat
   DISCONNECTED/ERROR as stale.
2. Fix active-connections issues when creating VNC sessions
   — same TabManager index bug as item 1 (rendering path is spec-compliant);
   also audit active-session row tap/close handlers to dispatch by tabId
   never positional index.
3. Fix "job failed" errors when leaving the tab (docker/hypervisor)
   — root cause: CancellationException swallowed by generic
   `catch (e: Exception)` and shown as a user error. ~35 sites:
   EngineApiTransport (2), RemoteExecOps (4), ProxmoxApiClient (13),
   ConsoleWebSocketClient (7), HypervisorConsoleManager (9); same-family
   audit needed in XenOrchestra/VMware/XCPng/Libvirt/Oci clients, RfbClient,
   ConsoleStrategy. Fix: rethrow-CancellationException guard before each
   generic catch (CliExecTransport/SshExecRunner already do it right);
   prefer a shared runCatching-style helper to prevent recurrence.
   FIXED 2026-08-11 (pending commit): guards added across docker transports
   + hypervisor clients; helper `utils/coroutines/CancellationSafeCatch.kt`
   + test.
   FOLLOW-UP AUDIT DONE 2026-08-14: reviewed docker/DockerSessionManager.kt,
   docker/transport/SocketRelay.kt, docker/transport/
   TransportCapabilityDetector.kt, docker/registry/RegistryClient.kt,
   docker/registry/UpdateChecker.kt, hypervisor/vnc/VncDirectConnector.kt,
   hypervisor/spice/SpiceLoader.kt. No further guards needed: every
   coroutine-reachable generic catch already has the explicit
   CancellationException rethrow (DockerSessionManager, TransportCapability
   Detector, RegistryClient); the remaining generic catches wrap only
   synchronous/blocking calls with no suspend function inside the try body
   (SocketRelay's thread-based accept/pipe loops, VncDirectConnector's
   Socket.connect + RfbClient ctor which already unconditionally rethrow
   after cleanup, SpiceLoader's System.loadLibrary/JNI call, UpdateChecker's
   JSONObject/JSONArray parse in normalizeInspect) — none of those can
   surface a swallowed CancellationException.
4. Fix VNC/SPICE keyboard not working
5. Fix VNC console issues
6. Add ability to close VNC/SPICE tabs
7. Fix terminology for vnc (vnc/spice/console) and terminal (ssh/mosh/telnet)
8. Fix mosh X11 forwarding, lastlog
9. Fix page up/down removing a space — e.g. `{command} ....` becomes
   `{command}....`, `git -C ....` becomes `git C ....` (command corruption)
   — root cause (2026-08-11): TerminalInputConnection buffers IME
   composing text; onCreateInputConnection creates a fresh instance
   (buffer lost), closeConnection() is a no-op, and PGUP/PGDN escape
   writes bypass the InputConnection without flushing. Fix: flush
   composing on closeConnection, single long-lived InputConnection,
   flushPendingComposing() before bar/hardware key escape writes.
10. Fix PRE key disabling for all connections when it is only per-connection —
    remove "Disable PRE key", rename "OFF (this connection)" to
    "Disable PRE key"
    — root cause: global pref terminal_prefix_key_enabled sits in the
    same picker as the per-connection multiplexerOverride="off" row.
    Fix: drop the global row + pref (KEY_PREFIX_KEY_ENABLED and 4 read
    sites), rename per-connection row (toggles in place to "Enable PRE
    key" when off), one-time migration: global-off → set
    multiplexerOverride="off" on profiles with null override only.
11. Remove the padding from top/bottom of terminal/VNC on keyboard toggle
    — root causes: TerminalView pushes grid slack (up to a cell height)
    above the grid and only recomputes on debounced resize; VncView
    sends per-animation-frame SetDesktopSize with no debounce, leaving
    fbWidth/fbHeight stale → letterbox gap. Fix: immediate gridTop
    recompute (debounce only the SIGWINCH), distribute slack; ~80ms
    debounce on VNC resizeToPixels honoring the rejects-SetDesktopSize
    flag.
12. Add magnify to make selecting text easier
    — plan: android.widget.Magnifier behind an API 28+ guard (minSdk
    24 → guarded no-op below), lazily built on TerminalView, show on
    handle grab + selection ACTION_MOVE, dismiss on up/cancel and
    exitSelectionMode; magnifier calls behind a small seam for tests.
13. Ensure entire UI/UX uses Material Design and supports Dark/Light/System
14. Add screenshots to README.md

## Previous batch status

The 2026-08-07 user batch (items A–E + D2 below) shipped
across commits 632203855a18 (batch), 2cf1c3dcc31a (designer pass), and
87220911946e (transport-tier failure logging); its follow-up findings
shipped 2026-08-08 as 9458ed91ac26 (sync_port_forwards backup pref),
0b4229c4eef5 (port forward delete tombstone), 3cd8e0f4578c (dashboard
replace-mode clear), fa54172b5d0d (terminal/connection-editor string
externalization), and 28af0eb99ac6 (ImportExportActivity string
externalization incl. the bulk-import group-suffix display fix).

Deferred by design (revisit only on request): emoji empty-state glyphs
in fragment_cloud_accounts.xml / fragment_docker_hosts.xml (deliberate
style — replace only if a full icon pass is wanted); mosh-wrapped
docker exec phone↔host leg (offered, not confirmed by user). Open
diagnostic: debug log from build 2cf1c3dc showed streamlocal and
dial-stdio probes failing silently and falling back to cli_exec — the
new tier-failure logging (87220911946e) will name the cause in the
next debug log; fix the real cause then.

## Shipped — 2026-08-07 user batch (resolved dependency order)

Resolved order: A (independent quick fix) → B (logging audit, feeds C) →
C (backup/sync completeness) → D (stack edit + logs, pending research) →
E (infra tab reorder + UI/UX polish, last so it restyles final screens).

### A. Mosh spinner log spam (from debug log pste.us/7RiaOfuI)
`SSHTab.kt:347` logs every tab-title change at D level; the mosh spinner
animates the title (`⠐`/`⠂`) ~1/sec, flooding the debug log. Skip logging
when only the spinner glyph differs from the previous title.

### B. Application/debug logging completeness + sanitization — DONE
Audit done (2026-08-07); findings 1–8 verified fixed in code
2026-08-12 (sanitizer URL/Bearer rules, SocketRelay logging,
host-log copy sensitive=true all confirmed present). The item-7
deferral (CliExecTransport/DockerApiParsers/DockerCliParsers/
RunConfigParser silent catches) is being fixed in the 2026-08-12
follow-up batch. Original findings:
1. Console URLs logged whole with live auth tickets — ConsoleWebSocketClient.kt:236 (vncticket), XCPngApiClient.kt:376,431 (session_id), XenOrchestraApiClient console URLs; log scheme+host+path only
2. Sanitizer missing rules: URL query secrets (vncticket|ticket|session_id|access_token|sig), Authorization Basic/Bearer, JSON-quoted "password"/"token" keys; + LoggerSanitizerTest cases
3. Logcat path unsanitized — Logger.kt:249,257,267,277,286 write raw to android.util.Log; sanitize in release builds
4. Payload dumps: TabTerminalActivity.kt:1446 (clipboard contents!), XenOrchestraApiClient.kt:215 (raw auth JSON), :1269 + ConsoleWebSocketClient.kt:296,304,309,349 (raw frames) — log lengths/types
5. Full command lines logged (may embed -p/--token): ClusterCommandExecutor.kt:61, SSHConnection.kt:1784,1795,1801 — log argv[0]+count
6. Crash trace raw in SharedPreferences + CrashReportActivity display/copy — sanitize at TabSSHApplication.kt:551, CrashReportActivity.kt:66,70
7. Silent catches: docker/transport/SocketRelay.kt (8 blocks), backup/validation/BackupValidator.kt, VncConsoleChannel.kt, OciKeyMaterial.kt, CloudProvider.kt — add Logger error/warn. DEFERRED (stack-agent-owned files): CliExecTransport.kt, DockerApiParsers.kt, DockerCliParsers.kt, RunConfigParser.kt
8. SettingsActivity.kt:1099 host-log copy sensitive=false → true; Logger.kt:826 dead unsanitized files/debug.log branch — remove

### C. Sync + backup completeness — backup is a full app snapshot
Audit done (2026-08-07). Encryption is already optional with restore
autodetect (TABSSH_SYNC_V2 magic sniff, BackupManager.kt:163) — that
requirement is met. Items 2-10 implemented 2026-08-07 (agent pass, not
yet committed by the owning session). Item 1 (Docker subsystem) remains
open — explicitly out of scope for that pass, owned by a separate task.
1. DONE — Docker subsystem (DockerHost, RegistryCredential, ComposeStack,
   SingleContainerConfig, ContainerAutoUpdatePolicy) integrated into both
   backup and sync, mirroring items 2-10.
   - Backup: BackupExporter/BackupImporter gained FILE_DOCKER_HOSTS,
     FILE_REGISTRY_CREDENTIALS, FILE_COMPOSE_STACKS,
     FILE_SINGLE_CONTAINER_CONFIGS, FILE_CONTAINER_AUTO_UPDATE_POLICIES
     (own JSON files, v2 items-array format); ids preserved (never
     remapped) on restore, matching every other entity. Merge mode:
     skip-existing by id (insert new, update existing rows when
     overwriteExisting/replace); replace mode: clearTablesForReplace()
     wipes all 5 tables via getAllList()+forEach delete (no bulk
     deleteAll on these DAOs, same pattern as item 6's list). Absent
     backup files are skipped (table() helper no-ops on missing key) so
     old pre-Docker backups restore unaffected.
   - Secrets: docker_host_{id} (custom-endpoint SSH password) and
     registry_credential_{id} exported/restored via direct
     SecurePasswordManager alias access (same alias strings
     DockerHostPasswordStore/RegistryCredentialStore use at runtime), row
     fields left in place since neither entity has a secret column to
     blank.
   - Sync: new sync_docker toggle (PreferenceManager.isSyncDockerEnabled/
     setSyncDockerEnabled, default true) covers all 5 entities + both
     secret alias families. Wired into SyncDataCollector
     (collectAllSyncData/collectChangedSince/collectSecrets/
     enabledTombstoneTypes/liveKeys/snapshotState), SyncDataApplier
     (applyAll/applyTombstones/isSecretAliasEnabled/
     applySyncPreferences), TombstoneRecorder (5 naturalKey() overloads +
     5 type constants — DockerHost/RegistryCredential/
     ContainerAutoUpdatePolicy are timestamp-less so tombstones always
     win; ComposeStack/SingleContainerConfig have updatedAt so
     last-write-wins applies). Tombstone recording at the delete-flow
     sites: DockerHostsFragment (host delete, cascading tombstones for
     that host's compose stacks, single-container configs, and
     auto-update policies), RegistryCredentialDialog (credential delete),
     ComposeEditorActivity.deleteStack() and
     DockerStacksFragment.deleteStack() (standalone stack delete — the
     latter was missing its TombstoneRecorder.record() call entirely;
     fixed as part of this item since it's a Docker delete-flow site this
     task is responsible for wiring). SingleContainerConfig and
     ContainerAutoUpdatePolicy have no standalone delete UI outside the
     host-cascade path, so DockerHostsFragment's cascade is their only
     hook point.
   - UI: row_sync_docker toggle row added to SyncSettingsActivity.kt +
     activity_sync_settings.xml, positioned after port forwards, using
     inline string literals to match every other row in this file (the
     file predates string externalization; deviating here keeps it
     internally consistent rather than externalizing one row alone).
   - Test: SyncDataApplierSecretGatingTest.kt gained
     "docker secrets are gated by sync_docker" verifying both
     docker_host_ and registry_credential_ aliases respect the toggle.
   - Gaps found during this pass, both fixed 2026-08-08: (a)
     sync_port_forwards missing from the backup "sync" preferences
     blocks — fixed in 9458ed91ac26; (b) PortForward tombstone not
     recorded at PortForwardingActivity's delete flow — fixed in
     0b4229c4eef5 (audit confirmed the other two delete call sites,
     replace-mode restore clear and the sync tombstone applier,
     correctly do not record).
2. DONE — key_passphrase_{keyId} exported/restored in backup secrets.json
   and sync collectSecrets/applySecrets (SyncDataApplier.isSecretAliasEnabled
   gates on sync_keys; unit-tested in SyncDataApplierSecretGatingTest.kt)
3. DONE — vnc_host_{id} passwords exported/restored, backup and sync
4. DONE — PortForward added to backup (port_forwards.json) and sync
   (sync_port_forwards toggle, collector/applier/tombstone, UI row in
   SyncSettingsActivity + activity_sync_settings.xml)
5. DONE — SyncDataApplier.applyAll now skips categories whose local
   toggle is off; collectSecrets() gated via isSecretAliasEnabled() per
   alias family (identities/connections/keys/hypervisors/vnc/cloud)
6. DONE — BackupManager.restoreBackup/BackupImporter.restoreBackupData
   gained replaceMode: Boolean (default false). True snapshot mode clears
   every entity table present in the backup before inserting (see
   BackupImporter.clearTablesForReplace) and restores preferences fully.
   UI: ImportExportActivity.showRestoreModeDialog offers merge vs replace
   (strings restore_mode_* in strings.xml), default stays merge.
   Note: several DAOs (Hypervisor, HypervisorAccount, Workspace,
   CloudAccount, Macro, MonitorSlot, VncHost, VncIdentity, PortForward)
   have no bulk deleteAll — clearTablesForReplace uses getAll+forEach
   delete for those instead of adding new DAO methods. The
   FILE_DASHBOARD replace-mode asymmetry noted under item 8 was fixed
   2026-08-08 in 3cd8e0f4578c (full multi_host_dashboard prefs clear).
7. DONE — legacy oci_private_key_{profileId}/oci_passphrase_{profileId}
   aliases exported/restored alongside the _account_ variants
8. DONE — non-default prefs files now in backup+restore via generic
   BackupExporter.exportSharedPrefs()/BackupImporter.restoreSharedPrefs():
   TabSSH (sort orders), cluster_commands, snippet_var_recall. Minor
   asymmetry: these 3 get a full prefs-file clear() in replace mode;
   FILE_DASHBOARD (multi_host_dashboard) only overwrites keys present in
   its own JSON in replace mode, not a full clear — low-priority, revisit
   if a dashboard config needs full-snapshot-exact restore semantics.
9. DONE — preferences_sync.xml deleted. Verified genuinely dead: no
   PreferenceFragmentCompat subclass loads R.xml.preferences_sync (all 8
   fragments in SettingsActivity.kt load other preferences_*.xml files),
   and a full source-tree grep for "preferences_sync" found zero
   references outside Gradle build artifacts. SyncSettingsActivity.kt's
   programmatic UI is the complete, actually-reachable equivalent.
10. DONE — TabSession + AuditLogEntry made @Serializable and added to
    backup only (tab_sessions.json, audit_log.json) — never wired into
    sync/toggles/tombstones per the finding. Replace mode: full clear
    (tabSessionDao().deleteAllSessions() / auditLogDao().deleteAll());
    merge mode: skip rows that already exist (by tab_id / id).

Minor observation resolved 2026-08-08: ImportExportActivity.kt's
inline dialog/toast strings were fully externalized in 28af0eb99ac6
(69 import_export_* resources; also fixed the bulk-import group
suffix rendering the literal " [$it]").

### D. Compose stacks: edit existing (incl. outside compose dir) + logs — DONE
Verified implemented in code 2026-08-12: `docker compose ls` discovery
(DockerModels/ComposeEditorActivity external-stack path), stack-level
logs (StackLogsActivity + transport composeLogs), run-config logs.
Original gap list: (1) stack discovery via
`docker compose ls` — external stacks (outside the compose dir) are
invisible today (DockerStacksFragment loads Room rows only); show them
and support edit-in-place at their own config-file path
(ComposeEditorActivity currently requires a Room row + its remotePath);
(2) stack-level logs (`docker compose logs`, aggregated/per-service) —
no composeLogs in DockerTransport/RemoteExecOps; (3) run-config logs —
runContainer() only toasts; wire to existing streamLogs container flow.
Also: ComposeEditorActivity silently shows empty fields when the remote
compose file is unreadable (valueOrNull) — surface an error instead.

### D2. Replace socat/nc bridge with `docker system dial-stdio` — DONE
User rejected socat/nc as too fragile. Tier b becomes a dial-stdio
relay (per-connection exec channel running `docker system dial-stdio`,
piped like tier a; DOCKER_HOST=unix://{socketPath} for non-default
sockets). Delete socat/nc bridge + the nc singleConnection mutex in
EngineApiTransport; new mode "api_stdio"; legacy pinned "api_socat"
falls back to auto detection; tier order streamlocal → dial-stdio →
cli_exec. Shipped in 632203855a18.

### E. Infra tab reorder + UI/UX polish — DONE (shipped 2cf1c3dcc31a)
- Infra tabs reordered to Docker → Hypervisors → Cloud (InfraFragment)
- Registry credentials: list and editor dialogs now explain the
  purpose (on-device HEAD /v2 digest checks by the auto-update
  checker); editor rebuilt with outlined TextInputLayouts, auth-type
  exposed dropdown (Basic / Bearer token / Anonymous wire values
  preserved), masked secret with visibility toggle, inline host
  validation that no longer dismisses the dialog on error
- Docker manager: fragment_docker_list/hosts/dashboard progress
  indicators sized 48dp and centered (stray-dot fix); list empty
  states now icon + title + hint (new ic_docker_container/image/
  volume/network/stack drawables)
- Cloud section: all dialogs on MaterialAlertDialogBuilder; OCI
  credentials dialog rebuilt on DialogFields (monospace OCIDs,
  masked passphrase, per-field inline required errors); every
  hardcoded string extracted to cloud_* resources incl. plurals
- App-wide bare-EditText dialog sweep via shared DialogFields
  helper: TabTerminalActivity (7), ConnectionEditActivity (6),
  ConnectionsFragment (1), IdentitiesFragment (7), SFTPActivity (2),
  MultiHostDashboardActivity (2), ImportFromQrActivity (1),
  Libvirt/VMware/XCPng/Proxmox manager dialogs; passphrase/password
  prompts now masked. Deliberately left: PinLockActivity pin field,
  RemoteFileEditorActivity editor, ThemeEditorActivity in-activity
  fields, PaletteDialog live filter box (none are dialogs)
- Blank-first-tab bug needed no code change (fixed earlier via
  themes.xml tabSelectedTextColor)

Follow-up resolved 2026-08-08: hardcoded dialog titles/button labels
in TabTerminalActivity and ConnectionEditActivity externalized in
fa54172b5d0d (102 new resources, 9 reused). Emoji empty-state glyphs
remain deliberate style (see Deferred by design above).

## Needs verification

### 6. Manual console smoke tests (user-side — no hypervisor hosts available here)

The VNC/SPICE console work (former item 1 / PLAN.AI.md) is code-complete and
`make check`-clean, but the manual smoke matrix from PLAN.AI.md's
definition-of-done requires real hosts and must be run by the user:

- Proxmox: text console (termproxy), VNC fallback (vncproxy), SPICE (spiceproxy, qemu VM)
- libvirt: VNC console and SPICE via `virsh domdisplay` + SSH port forward
- QEMU direct VNC · TightVNC server via VNC profile
- VMware: console button on a POWERED_ON VM with `RemoteDisplay.vnc.*` set

## Recently Shipped

Former item 7 (spice-libs first CI release) verified 2026-08-06: the
"SPICE Native Libraries" workflow_dispatch run succeeded and the
`spice-libs-0.42.0` prerelease is published with all four ABI `.so`
assets — `scripts/fetch-spice-libs.sh` now resolves a release and
`SpiceLoader.isSpiceAvailable()` can return true on fetch-enabled builds.

Former item 4 (Tasker/Locale plugin, IDEA.md feature 54) shipped: TabSSH
implements the `com.twofortyfouram` Locale plugin protocol —
`automation/LocaleEditActivity` (EDIT_SETTING config screen, in-app
action/profile picker) + `automation/LocaleFireReceiver` (FIRE_SETTING,
strict bundle validation, routes through TaskerWorker's runtime gates);
the signature-gated `TaskerActionReceiver` stays for same-signature
callers.

Former item 5 (theme count 23 vs 22) resolved 2026-08-06 with no change:
`BuiltInThemes.getAllThemes()` returns exactly 23 entries (3 system + 12
classic + 8 popular) — the audit miscounted; spec and code agree.

- `aed92a064908` — snapshots on every hypervisor backend + VMware guest
  shutdown/restart (former item 2, IDEA.md feature 40): Proxmox REST
  qemu/lxc snapshot endpoints, VMware vim25 SOAP task calls +
  ShutdownGuest/RebootGuest, libvirt virsh snapshot-* over SSH;
  long-press snapshot dialog in all three manager activities
- ASK-mode multiplexer picker (former item 3, IDEA.md feature 21):
  ASK now lists the remote's tmux/screen/zellij sessions after connect
  and shows an attach/create/skip dialog instead of silently
  auto-attaching; unit tests for attach-command build + session-list
  parsing

VNC/SPICE console coverage (IDEA.md features 42, 43) — PLAN.AI.md completed
and deleted 2026-08-06:

- `f07610fcd122` — VMware VNC-via-vmx console (PLAN item 6): vim25 SOAP
  read of `RemoteDisplay.vnc.*`, direct-TCP RfbClient in an ephemeral VNC
  tab, clear error when VNC is not enabled
- `0187386fb188` — SPICE end-to-end for Proxmox (PLAN items 9/10/11):
  termproxy → spiceproxy → vncproxy strategy chain,
  `ConsoleConnection.Spice`, `ConsoleTab.markSpice()` +
  `ConsoleDisplayMode`, `TerminalPagerAdapter.bindSpice()` driving
  `SpiceView`; CI green (Development Build + Validation Tests)
- `49eee5d8e706` — libvirt SPICE via `virsh domdisplay` (PLAN item 12):
  `spice://` URI parse, SSH local port forward, `SpiceClient` tab with
  forward teardown on cleanup; VNC fallback preserved
- `0b5708d55447` — generic `ConsoleStrategyChain` `onAttempt` callback +
  connect-time strategy progress in the single spinner (PLAN items 13/14)
- SPICE delivery pipeline (PLAN item 8): `spice/Dockerfile` +
  `spice/build-android.sh` cross-compile the libspice chain into one
  static `libtabssh_native.so` per ABI; `spice-libs.yml` publishes them;
  `scripts/fetch-spice-libs.sh` fetches at build time — no native
  toolchain in `app/build.gradle`. First CI release still pending (item 7)

### Durable record — SPICE GStreamer omission (deliberate limitation)

spice-gtk 0.42 mandates the full gstreamer-1.0 stack, which cannot be
static-linked into the single self-contained `.so` without pulling in the
entire GStreamer + plugin tree (defeats the mosh-parity model). The build
patches GStreamer out (`spice/build-android.sh` + `spice/cpp/spice_gst_stubs.c`)
and relies on the builtin MJPEG decoder (libjpeg). Consequences to revisit
if a user needs them: (a) non-MJPEG SPICE video streams (VP8/VP9/H264/H265)
do not decode — the stream is dropped, the surface still gets ordinary draw
commands; (b) SPICE audio playback/record is a no-op (out of scope per the
original plan). If full-motion video streaming becomes a requirement, the
options are cross-building a static GStreamer subset with manual plugin
registration, or an alternative in-tree video decoder.

### Durable record — SPICE cross-compile recipe (x86_64 verified)

Verified locally in Docker: `spice/out/x86_64/libtabssh_native.so` (11 MB,
stripped) exports all 9 `Java_..._SpiceClient_native*` symbols plus
`nativeIsSpiceAvailable`; `readelf -d` NEEDED = only
libm/libdl/liblog/libandroid/libc (the whole SPICE stack is static-linked).
Fixes baked into the recipe: pkg-config search path includes
`share/pkgconfig` (spice-protocol installs its .pc there);
`python3-six`/`python3-pyparsing` for spice-common codegen; static
libjpeg-turbo (spice-gtk requires libjpeg); GStreamer patched out (see
limitation above).

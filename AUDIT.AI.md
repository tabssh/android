# Project Audit

Started: 2026-08-07

Scope: the three recently shipped commits (`aed92a064908` snapshots,
`9f4e2a132a26` ASK-mode multiplexer picker, `dc1e3e4cb997` Tasker/Locale
plugin), the new exported IPC surface, doc consistency, and a general
health sweep. Fixes are applied in the working tree, uncommitted, for
user review.

The previous audit's `AUDIT.AI.md` (2026-07-31) was fully resolved but
never deleted — its content is superseded by this file.

## Pass 1: Security

- [x] `automation` + `storage/preferences`: **Tasker integration defaulted to
  ON** (`PreferenceManager.isTaskerEnabled` default `true`,
  `preferences_tasker.xml` `defaultValue="true"`) while the allowlist defaults
  to empty (= all connections) and require-unlock defaults to off. With
  `LocaleFireReceiver` now exported without a permission, a fresh install
  granted every app on the device the ability to drive SSH sessions with no
  user action — the defense the manifest comment and receiver kdoc describe
  did not exist by default. — FIXED: default flipped to `false` in both
  places. Existing installs keep their persisted value.
- [x] `automation/LocalePlugin.kt`: **`isBundleValid` accepted a bundle
  identified by connection *name* alone.** `TaskerWorker.resolveProfile` falls
  back to name lookup, so a hostile app only had to guess a profile name
  ("prod", "home") to target it. — FIXED: a non-empty connection ID is now
  mandatory. IDs are opaque UUIDs only handed out by `LocaleEditActivity`.
- [x] `automation/LocalePlugin.kt`: bundle reads were unguarded. A host app
  controls the payload; unparcelling a class this process cannot load throws,
  and an uncaught throw in `onReceive`/`onCreate` is a crash any installed app
  could trigger. — FIXED: validation wrapped, defaults to reject.
- [x] `automation/TaskerWorker.kt:269-276`: **`broadcastCommandResult` puts
  `termuxBridge.getScreenContent()` into an implicit, unprotected
  `sendBroadcast`.** Every app on the device can register for
  `io.github.tabssh.event.COMMAND_RESULT` and read terminal output —
  credentials, key material, whatever is on screen. Chained with the fire
  receiver this is exfiltration, not just leakage. NOT FIXED: the `event.*`
  broadcasts are a documented public contract that existing Tasker tasks
  depend on, so restricting them (custom permission, or dropping the screen
  content in favour of a result the host must pull) is a user-decision.
  DECIDED (user, 2026-08-07): status-only broadcast by default — keep exit
  status/metadata, drop screen content; add an "include command output in
  broadcasts" toggle in Tasker settings, default OFF, restoring old payload
  when enabled. — FIXED (2026-08-07, own commit after the Docker feature):
  new `status` extra (`completed`/`sent`); `result` mirrors `status` unless
  the new `tasker_include_output` toggle (default OFF) is on, in which case
  it carries the screen content as before. Screen content is not captured
  at all when the toggle is off. Events help dialog updated to document the
  contract.

## Pass 2: Code Quality

- [ ] whole tree: raw control bytes (`0x1b` and friends) are embedded
  literally in string literals across `terminal/TermuxBridge.kt`,
  `ui/keyboard/MultiRowKeyboardView.kt`,
  `hypervisor/vnc/console/VncConsoleChannel.kt`, and
  `automation/TaskerWorker.kt`. They are correct today but invisible in
  diffs and review, and a formatter or `sed` pass can silently eat them.
  NOT FIXED: converting to `\u001b` escapes is a ~50-site mechanical change
  across terminal-critical code, outside this audit's working set.
- [ ] `ui/activities/{Proxmox,VMware,Libvirt}ManagerActivity.kt`: snapshot
  `AlertDialog`s are shown without retaining a reference and no `onDestroy`
  dismisses them — rotating with one open leaks the window. NOT FIXED: this
  is the pre-existing dialog pattern throughout all three activities, not
  specific to the audited commits; fixing it properly is a per-activity
  pattern change.
- [ ] `ui/activities/TabTerminalActivity.kt`, `ui/tabs/SSHTab.kt`: redundant
  fully-qualified names (`io.github.tabssh.ui.tabs.SSHTab`,
  `kotlinx.coroutines.flow.StateFlow`) where the file already imports the
  symbol. NOT FIXED: cosmetic, and the pattern predates these commits.

## Pass 3: Logic and Correctness

- [x] `hypervisor/libvirt/LibvirtApiClient.kt`: virsh success/failure was
  `output.contains("error:") || output.contains("failed")` at nine sites, but
  virsh echoes the object's own name back on success — a domain or snapshot
  named `failed-boot` made every successful start/destroy/shutdown/reboot/
  reset throw, and permanently broke `listSnapshots` for that domain. —
  FIXED: new `isVirshError` anchors on virsh's `error:` line prefix.
- [x] `ui/tabs/SSHTab.kt`: `buildMultiplexerCommand`/`buildAttachCommand`
  stripped `'` from session names rather than escaping. Injection-safe, but
  silently mangled legitimate names (`dev'box` → attach fails). — FIXED:
  added `shQuote` (POSIX `'\''`), tests updated plus a metacharacter
  containment test for remote-supplied names.
- [x] `hypervisor/proxmox/ProxmoxApiClient.kt`: snapshot name interpolated
  un-encoded into the REST **path** in `rollbackSnapshot`/`deleteSnapshot`. —
  FIXED: `encodePathSegment` applied to node and snapshot name.
- [x] `ui/activities/TabTerminalActivity.kt`: ASK-picker dialog leaked on
  destroy, survived tab switches while capturing the old tab, collected
  without `repeatOnLifecycle` (could `show()` on a stopped activity), and
  destroyed the pending request on a name-validation typo. — FIXED all four.
- [x] `ui/activities/{Proxmox,VMware}ManagerActivity.kt`: no snapshot-name
  validation (Proxmox requires config-ID format; VMware did not even trim). —
  FIXED: both now validate, matching libvirt.
- [x] `hypervisor/vmware/VMwareApiClient.kt`: `shutdownVM`/`rebootGuest`
  rewrote *any* `IOException` as "needs VMware Tools". — FIXED: hint added
  only for tools/power-state faults.
- [x] `hypervisor/proxmox/ProxmoxApiClient.kt`: `apiDelete` discarded the
  error body. — FIXED: shared `proxmoxErrorDetail` used by both verbs.
- [ ] `hypervisor/vmware/VMwareApiClient.kt` `listSnapshots`:
  `RetrievePropertiesEx` result is used without checking for a continuation
  token (`ContinueRetrievePropertiesEx`). Harmless for one VM's snapshot list
  today; silently truncates if it ever paginates. NOT FIXED: needs a live
  vCenter to verify.
- [ ] `ui/tabs/SSHTab.kt:1216-1220`: zellij's decorated `list-sessions`
  fallback output includes `(EXITED - attach to resurrect)` entries, which are
  parsed as attachable and offered in the picker. NOT FIXED: needs a zellij
  host to confirm the exact output shape across versions.

## Pass 4: Documentation Completeness

- [x] `CHANGELOG.md`: no entries for the fixes above. — FIXED: Security and
  Fixed entries added under `[Unreleased]`.
- [x] `app/src/test/.../LocalePluginBlurbTest.kt`: kdoc claimed the bundle
  paths "need an Android runtime and are exercised on-device", but Robolectric
  4.14.1 is already a test dependency. — FIXED: claim corrected and
  `LocalePluginBundleTest` added under Robolectric.
- README.md / IDEA.md / TODO.AI.md checked for stale claims (Tasker
  "disabled pending redesign", SPICE libs unpublished): none found, all three
  already reflect the shipped state.

## Pass 5: Spec and Rules Compliance

- [x] `AUDIT.AI.md`: the 2026-07-31 audit file had every item resolved but
  was never deleted, contrary to the "delete when all resolved, do not empty"
  rule. — FIXED: superseded by this file.
- AI.md PART 0 non-negotiables re-checked against the audited commits:
  Keystore-only credentials, Room migration discipline, no LiveData, no
  hardcoded colors, 4-space Kotlin / 2-space XML, trailing newlines, comments
  above-line ≤180 chars, no TODO/FIXME/HACK, no AI attribution — all clean in
  the three commits.
- PART 6 "exported components are individually justified and validate every
  incoming extra": `LocaleEditActivity` and `LocaleFireReceiver` are both
  justified in the manifest and both validate — satisfied (and strengthened
  by the Pass 1 fixes).

## Pass 6: Code Flow Trace

- Proxmox CSRF: `CSRFPreventionToken` verified present on both `apiPost` and
  the new `apiDelete` — no gap.
- VMware SOAP: snapshot tree walk does recurse into `childSnapshotList`;
  `xmlEscape` escapes `&` first and is applied to every interpolated value.
- libvirt: `shQuote` is correct POSIX escaping and is applied to every
  user-controlled value in all four snapshot commands.
- ASK picker: `connectionScope` is `Dispatchers.IO` and
  `SSHConnection.executeCommand` re-wraps in `withContext(Dispatchers.IO)` —
  no main-thread client or DB calls. Locale `LocaleEditActivity` profile load
  uses suspend Room DAOs, which switch dispatchers themselves.

## Completed

- Tasker integration default flipped to opt-in; Locale bundles require a
  connection ID; bundle validation exception-guarded.
- libvirt virsh error detection anchored; multiplexer session names escaped
  rather than stripped; Proxmox snapshot path segments encoded.
- ASK-picker dialog lifecycle, Proxmox/VMware snapshot-name validation,
  VMware guest-op error text, Proxmox delete error body.
- CHANGELOG entries and `LocalePluginBundleTest` added.

---

# Docker Implementation Audit (2026-08-07)

Scope: the whole Docker feature — transport tiers, session manager,
update-check worker, registry client, exec tabs, logging. All findings
fixed in the working tree, uncommitted, for user review.

## Findings

- [x] `docker/DockerSessionManager.kt`: **one global `Mutex` serialized
  every host** — opening host B waited behind host A's full SSH connect +
  transport detection; hundreds of hosts meant a strictly serial queue. —
  FIXED: per-host `Mutex` map; slow work runs under the host's own lock,
  shared cache state under a monitor lock only.
- [x] `docker/DockerSessionManager.kt`: **cached sessions lived forever
  with no cap, no idle timeout, and no dead-session eviction** — a dead
  SSH connection left its `SocketRelay` listening and was handed back as
  live; heavy multi-host use accumulated one open SSH connection per host
  unbounded. — FIXED: access-ordered LRU capped at `MAX_OPEN_SESSIONS`
  (16), `IDLE_TIMEOUT_MS` (10 min) idle disconnect via a 60 s background
  sweeper, dead sessions evicted; eviction closes the transport/relay and
  disconnects the monitoring-only SSH connection unless another cached
  session or a user terminal shares the profile. Pure eviction logic
  extracted to `DockerSessionPolicy` with JVM tests
  (`DockerSessionPolicyTest`, 9 cases).
- [x] `background/DockerUpdateCheckWorker.kt`: **hosts were checked
  serially with no per-host cadence control** — every 12 h run hit every
  host. — FIXED: `Semaphore(2)`-bounded concurrent fan-out
  (`MAX_CONCURRENT_HOSTS = 2`), per-host due-time gate extracted to
  `UpdateCheckGate` (JVM tests, 6 cases), `last_update_check` persisted
  per host only after a completed pass.
- [x] `storage/database/entities/DockerHost.kt` + `TabSSHDatabase.kt`:
  **no per-host update-check settings existed.** — FIXED: three additive
  columns (`update_check_enabled` default 1, `update_check_interval_hours`
  nullable, `last_update_check` default 0), `MIGRATION_10_11` (DB v11,
  additive `ALTER TABLE` only), `DockerHostDao.updateLastUpdateCheck`,
  host-editor UI (switch + interval field, `strings.xml` entries).
- [x] `storage/database/TabSSHDatabase.kt`: class kdoc still claimed
  "Current version: 9". — FIXED: 11.
- [x] `docker/registry/UpdateChecker.kt` / `UpdateApplier.kt`: check
  outcomes and apply starts were not logged, leaving the debug log blind
  on the two most support-relevant paths. — FIXED: `Logger.d`/`Logger.i`
  lines added; all Logger app-log output already passes
  `sanitizeForPublic`, and no raw credential/token is logged anywhere in
  the docker tree (verified: RegistryClient logs only the auth realm URL).
- [x] `CHANGELOG.md`: two `### Added` sections under `[Unreleased]`. —
  FIXED: merged into one.

## Verified sound (no change)

- Do-not-re-break list intact: IPv4 loopback bind in `SocketRelay`;
  `Connection: close` + zero-idle pool in `EngineApiTransport` and
  `TransportCapabilityDetector`; `MinAPIVersion` lift logic; `cli_exec`
  never fast-pathed; monitoring-only SSH connections; `SshExecRunner`
  timeout drain.
- Exec tabs already appear in the tab strip and long-press terminal
  context menu (they are ordinary `TabTerminalActivity` tabs); their
  exclusion from Active Sessions, recents, stats, and session restore is
  structural (ephemeral `docker-exec:` profile ids) and deliberate.

---

# VNC Stack Audit (2026-08-11)

Scope: the whole RFB client stack — `hypervisor/console/rfb/` (`RfbClient`,
`RfbDecoder`, `PixelFormat`, `RfbConstants`), `hypervisor/vnc/`,
`ui/views/VncView.kt`, `ui/tabs/VncTab.kt`. Reference: RFC 6143 plus the
community rfbproto extension registry; behaviour cross-checked against
TigerVNC and noVNC. The VNC server is untrusted input
(IDEA.md § Trust boundaries), so every wire-derived value is treated as
hostile. All findings below are fixed in the working tree, uncommitted.

## Pass 1: Handshake and authentication

- [x] `RfbClient.handshake()`: the server's minor version was clamped with
  `coerceIn(3, 8)`, so an unknown minor (e.g. `RFB 003.889`) negotiated 3.8.
  RFC 6143 §7.1.1 requires anything not 3.7/3.8 to be treated as 3.3, whose
  security handshake is a completely different wire format — every such
  connection desynced. — FIXED: explicit `>=8 / ==7 / else 3` mapping, and
  the greeting is now rejected unless it starts with `RFB `.
- [x] `RfbClient`: the SecurityResult failure reason string was read
  unconditionally. It exists only in 3.8 (RFC 6143 §7.1.3), so a failed
  3.3/3.7 auth blocked forever on a reason string that would never arrive.
  — FIXED: version-gated, with a bounded `readReasonString()`.
- [x] `RfbClient.authenticateVeNCrypt()`: the post-version acknowledgement
  was accepted only when it equalled 1. VeNCrypt 0.2 sends **0 = OK**, so
  every conforming VeNCrypt server was rejected. — FIXED (inverted), and the
  post-sub-type acknowledgement now follows TigerVNC's "only 0 is failure".
- [x] `RfbClient.authenticate()`: the VeNCrypt branch consumed a **1-byte**
  security result. The standard **U32** SecurityResult follows the sub-auth
  for every VeNCrypt sub-type, so 3 bytes of it were left in the stream and
  ServerInit was parsed from the wrong offset on every VeNCrypt connection.
  — FIXED: the branch now falls through to the shared U32 handling.
- [x] `RfbClient`: handshake reason strings, the ServerInit desktop name and
  ServerCutText were all read with a server-supplied U32 length and no cap —
  a hostile server could force a multi-GB allocation. — FIXED: `MAX_BLOB_BYTES`
  (16 MiB) / `MAX_REASON_LEN` (64 KiB) caps.

## Pass 2: Pixel format and framebuffer

- [x] `PixelFormat.toArgb`: 24 bpp (3-byte) pixels fell into `else -> 0L`,
  painting every pixel of a 24 bpp server black. 24 bpp is legal RFB. —
  FIXED: both byte orders handled.
- [x] `PixelFormat.toArgb`: channel scaling divided by the server-supplied
  channel maximum, so a zero maximum threw `ArithmeticException` and killed
  the reader thread. — FIXED: `scaleChannel()` treats a zero maximum as an
  absent channel.
- [x] `PixelFormat.cpixelToArgb`: the 3-byte ZRLE CPixel was always
  reassembled as little-endian. Big-endian omits the *leading* byte, so every
  channel was shifted and the colour order inverted on a big-endian server. —
  FIXED: byte order honoured.
- [x] `RfbClient`: framebuffer and cursor allocations multiplied two U16s
  straight off the wire (`IntArray(w * h)`), which overflows `Int` and can
  request gigabytes. — FIXED: `allocFramebuffer()` / `checkCursorSize()` with
  a `MAX_FB_PIXELS` cap, applied at all four allocation sites.
- [x] `RfbClient`: `din.skipBytes()` may skip short and its return value was
  discarded at 21 sites, each a potential silent desync. — FIXED: a
  `skipFully()` helper that loops and falls back to blocking reads.

## Pass 3: Rectangle decoding

- [x] `RfbDecoder.decodeRect`: no rectangle was validated against the
  framebuffer before decoding, so a server-supplied out-of-range rect wrote
  outside its bounds or threw `ArrayIndexOutOfBoundsException` on the reader
  thread. — FIXED: `validateRect()` rejects the session (the only safe action
  — the payload length is encoding-dependent and cannot be skipped).
- [x] `RfbDecoder.decodeCopyRect`: the *source* rectangle was not
  bounds-checked. — FIXED: out-of-range blits are dropped after the 4-byte
  header is consumed, keeping the stream in sync.
- [x] `RfbDecoder.decodeRre` / `decodeCorre`: a negative sub-rectangle count
  made `repeat(n)` a silent no-op, leaving the whole sub-rectangle payload in
  the stream and mis-parsing every subsequent message. — FIXED:
  `checkedSubRectCount()` throws.
- [x] `RfbDecoder.decodeHextile`: sub-rectangles were not confined to their
  own tile (RFC 6143 §7.7.4), so an over-sized one painted over the
  neighbouring tile or off the framebuffer. — FIXED: clamped to the tile.
- [x] `RfbDecoder.decodeTight`: compression types 0x0B–0x0F are undefined in
  every Tight variant but fell through to BasicCompression, reading a
  wrong-length payload and permanently desyncing the zlib stream. — FIXED:
  fatal `IOException`.
- [x] `RfbDecoder.decodeTight`: the 8 bpp palette loops indexed the palette
  with an unchecked byte (`AIOOBE` on a hostile stream). — FIXED: index
  guarded against the declared colour count.
- [x] `RfbDecoder.decodeTight`: a JPEG/PNG payload smaller than the rectangle
  it claims to cover made `Bitmap.getPixels` throw and kill the session. —
  FIXED: the rect is dropped after its payload is fully consumed; the length
  itself is now range-checked too.
- [x] `RfbDecoder.inflateAll`: the inflate buffer doubled without limit — a
  zlib bomb was an OOM. — FIXED: growth capped at `MAX_RECT_BYTES`.
- [x] `RfbDecoder.fillRect` / `writeRunToFb`: both now clip against the
  current rectangle's framebuffer bounds, since every coordinate reaching
  them was decoded from server data.
- [x] `RfbClient` FBU loop: the zero-dimension rect special case drained only
  Tight's control byte, not Fill's TPIXEL, so a zero-area Fill rect desynced
  the stream. Reference clients (TigerVNC `TightDecoder.cxx`, noVNC
  `tight.js`) never special-case zero-area rects. — FIXED: the special case
  is deleted; `decodeRect` is always called and only the listener callback is
  gated on a non-zero area.
- [x] `RfbClient` DesktopSize pseudo-rect: the new size was read from the
  rect's **x/y** instead of its **width/height**, resizing the framebuffer to
  the origin (normally 0×0) and failing every later bounds check. — FIXED.
- Verified: the advertised encoding list (ZRLE, Tight, Zlib, Hextile, CoRRE,
  CopyRect, RRE, Raw) is exactly `RfbDecoder.PIXEL_ENCODINGS` — nothing is
  advertised that the decoder cannot decode.

## Pass 4: Input, rendering and thread safety

- [x] `VncView.onDraw`: the bitmap reference was read under `fbLock` but drawn
  outside it, so the RFB reader thread could `recycle()` it mid-draw
  ("Canvas: trying to use a recycled bitmap"). — FIXED: the whole draw runs
  under the lock.
- [x] `VncView.androidKeyToKeysym`: only `event.unicodeChar` was consulted.
  It is 0 whenever a modifier suppresses the character, so **every Ctrl and
  Alt chord was silently dropped**; dead keys (negative, `COMBINING_ACCENT`)
  were dropped too; and characters above U+00FF were sent as bare code points,
  colliding with the X11 function-key keysym range. — FIXED: unmodified
  fallback, accent mask, and the X11 `0x01000000 | codepoint` convention via
  a new `codePointToKeysym()`, which `sendChar`/`sendCodePoint` now share.
- [x] `VncView`: there was no `onGenericMotionEvent`, so an external mouse's
  wheel did nothing and the remote cursor never followed a hovering mouse;
  mouse right/middle clicks were also flattened to left clicks. — FIXED:
  wheel notches map to X11 buttons 4/5 and (new) 6/7, hover moves are
  forwarded, and the real button mask is used on press.
- [x] `VncView.fbWidth`/`fbHeight`: written by the reader thread under
  `fbLock` but read unlocked by gesture and layout code, so a desktop resize
  could be missed. — FIXED: `@Volatile`.
- [x] `VncView.onFramebufferUpdate`: `Bitmap.setPixels` throws rather than
  clipping; a geometry mismatch killed the reader thread and dropped the
  session. — FIXED: mismatched rects are logged and dropped.
- [x] `RfbClient.fbWidth`/`fbHeight`: written by the RFB reader thread, read
  from the UI thread by `sendPointerEvent`, the `width`/`height` properties
  and the re-attach path. — FIXED: `@Volatile`.
- [x] `RfbClient.sendClipboardText`: sent the string verbatim. RFC 6143 §7.5.6
  defines ClientCutText as latin-1 with **LF-only** line endings, so pasting
  Android text containing CRLF put stray CRs into the remote clipboard. —
  FIXED: CRLF and lone CR are normalised to LF before the ISO-8859-1 encode.

## Pass 5: Integration (Proxmox, libvirt, VMware, VNC hosts UI)

- [x] `VncHostsActivity.kt` / `VncHostEditActivity.kt` — deleting a VNC host
  removed the DB row but left its Keystore secret under `vnc_host_{id}`
  forever; a host later re-imported with the same id silently inherited the
  dead password. — FIXED: both delete paths call
  `securePasswordManager.clearPassword("vnc_host_$id")`.
- [x] `VncHostEditActivity.kt` — no range validation on port or display
  number; a display of `-1` or `99999` produced an out-of-range TCP port. —
  FIXED: port validated `1..65535`, display `0..(65535-5900)`.
- [x] `VncDirectConnector.connectWss` — passed `listener = null` to
  `ConsoleWebSocketClient.connect`, discarding `onFailure`/`onClosed`, so a
  TLS failure reached the RFB side only as EOF and was classified as a clean
  user disconnect. — FIXED: caller's listener is forwarded.
- [x] `ConsoleWebSocketClient.kt` — the RFB byte bridge used
  `PipedInputStream`/`PipedOutputStream`. Those bind to the *writing thread*
  and throw `IOException("Write end dead")` once it exits; OkHttp delivers
  frames from a recycled pool thread, so an idle VNC session failed on the
  server's next update. The 64 KiB ring buffer also blocked OkHttp's reader
  thread — and with it ping/pong and close frames — whenever the RFB consumer
  fell behind. — FIXED: new `ByteStreamPipe` (thread-agnostic, non-blocking
  writer, 8 MiB cap that fails loudly instead of stalling the transport).
  Dead `SSLContext`/`TrustManager`/`X509TrustManager`/`X509Certificate`
  imports removed at the same time.
- [x] `LibvirtManagerActivity.kt` — the libvirt VNC path passed
  `vncPassword = null`, so every domain with
  `<graphics type='vnc' passwd='…'/>` failed VNC-Auth. — FIXED: new
  `LibvirtApiClient.getVncPassword()` reads it from
  `virsh domdisplay --include-password` (URI userinfo); never logged.
- [x] `LibvirtApiClient.openVncChannel` — returned only the two streams, so
  the `ChannelDirectTCPIP` behind them was unreachable and leaked (channel +
  pump threads) per libvirt VNC tab; stopping an `RfbClient` closes only the
  streams it was handed. — FIXED: returns a `VncChannel` holding the channel,
  and `VncTab` gained an `onCleanup` hook (mirroring `ConsoleTab`) that the
  activity wires to `VncChannel.close()`.
- [x] `LibvirtApiClient.getVncDisplay` — ran `virsh vncdisplay` without
  `2>/dev/null`, skipped the `isVirshError` check every sibling call makes,
  and used a greedy `(?:.*:)(\d+)` that would mine a display number out of an
  arbitrary stderr line. — FIXED: stderr suppressed, error check added,
  regex anchored per line.
- [x] `TabManager.parkOne` / `VncBackgroundSessionStore` — parked every
  session with `socket = null`, so `discardInternal` had nothing to close: the
  10-minute idle sweep stopped the RFB reader but left a WSS console's
  WebSocket, TLS socket and threads running for the life of the process. —
  FIXED: `ParkedSession` gained an idempotent `transportTeardown` hook;
  console tabs park `consoleManager.disconnect()`, VNC tabs park their
  `onCleanup`.
- [x] `ProxmoxApiClient.getVNCProxy` — caught every exception and returned
  `null`, making a 403 (no `VM.Console` permission), a missing VNC device, a
  TLS failure and a socket timeout indistinguishable from "server returned no
  data". — FIXED: rethrows; `null` now means only a 200 with empty `data`.
  `HypervisorConsoleManager`'s VNC-fallback error message surfaces the cause.
- [x] `VncKeepAliveService.onStartCommand` — called `stopSelf()` and returned
  before `startForeground()` on the stop-action and empty-store paths, while
  the caller starts it with `startForegroundService()` →
  `ForegroundServiceDidNotStartInTimeException` on API 26+. — FIXED:
  `startForeground` runs first; all stop paths go through
  `stopForegroundAndSelf()` (`STOP_FOREGROUND_REMOVE`), including the idle
  sweep, which previously left the notification behind.

## Tests added

- `app/src/test/.../console/ByteStreamPipeTest.kt` — writer thread exiting
  between writes, per-write thread rotation, non-blocking writer past 64 KiB,
  buffer-cap failure, EOF after writer close, blocked reader released on
  close, chunk-boundary-crossing partial reads.
- `app/src/test/.../rfb/PixelFormatTest.kt` — 24 bpp both byte orders, zero
  channel maximum, RGB-565 scaling, CPixel reassembly both byte orders.
- `app/src/test/.../rfb/RfbDecoderBoundsTest.kt` — out-of-range and negative
  rect geometry, undersized framebuffer, unhandled encoding, CopyRect source
  bounds (and payload consumption), negative RRE count, RRE and Hextile
  sub-rectangle clipping, reserved Tight compression type, zero-area rect
  payload consumption.

## Follow-up found when the unit-test gate first ran (2026-08-13)

- [x] `VncView` pan clamping used top-left-convention bounds
  `[0, fb - view/scale]` while the rendering origin is centred
  (`(view - fb*scale)/2 - pan*scale`) — panning could overshoot the
  bottom/right edge and could never reach the top/left edge, and
  `focalPan`'s anchoring was clamped away for any leftward/upward pan.
  Present in both `focalPan` and `onScroll`; predates this audit
  (born with the focal-pan fix, never executed — the test source set
  did not compile until now). — FIXED: symmetric bounds
  `±(fb - view/scale)/2` in both sites; `VncViewFocalPanTest`
  expectations corrected to the centred-origin convention.
- [x] `SSHTabConnectMoshX11Test`: one `= runBlocking { … }` test method
  returned a non-Unit value (JUnit4 "should be void"
  initializationError). — FIXED: `runBlocking<Unit>`.

## Notes for the user

- `SPEC.md` vs `Makefile`: RESOLVED — `make check` now runs
  `testDebugUnitTest` alongside KSP + compile, matching SPEC.md §3.
- The unit-test source-set compile break is FIXED —
  `UpdateCheckerTest.kt`'s `FakePolicyDao`/`FakeTransport` now implement
  the members added to the production interfaces (`getAllList`, the
  `compose*ByProject` family, `composeLs`, `composeLogs`).
- User-visible behaviour changed by this audit (mouse wheel and right/middle
  button support, 24 bpp rendering, libvirt VNC password support). AI.md
  PART 0 requires a `CHANGELOG.md` entry in the same commit — the committing
  session must add it; this audit was instructed not to commit.

---

# SPICE Stack Audit (2026-08-13)

Scope: the whole SPICE stack — `hypervisor/spice/` (`SpiceClient`,
`SpiceConnectionParams`, `SpiceConstants`, `SpiceKeyMap`, `SpiceListener`,
`SpiceLoader`), `ui/views/SpiceView.kt`, the SPICE paths in
`ui/tabs/ConsoleTab.kt` and `ui/keyboard/ConsoleKeyMapper.kt`, the SPICE
integration in `HypervisorConsoleManager`, `ConsoleStrategy`,
`ProxmoxApiClient` (spiceproxy), `LibvirtApiClient` (domdisplay) and the
three manager activities, plus the Kotlin side of the JNI contract with
`libtabssh_native.so`. Reference: the SPICE protocol documents and
spice-gtk / virt-viewer behaviour. The SPICE server is a trust boundary
(IDEA.md § Trust boundaries), so every wire-derived value is hostile. All
findings below are fixed in the working tree, uncommitted.

## Pass 1: JNI contract and lifecycle

- [x] `app/proguard-rules.pro`: none of the eight `onNative*` callbacks the
  native library resolves by literal name and signature through
  `GetMethodID` were kept. R8 renames them in any minified build, so every
  release/F-Droid APK would have had a SPICE client that connects and then
  receives nothing — no framebuffer, no cursor, no disconnect. Debug builds
  hid it completely. — FIXED: explicit `-keepclassmembers` block for all
  eight signatures (proguard-rules.pro:140).
- [x] `SpiceClient`: the native handle was a plain `Long` mutated from the
  UI thread, the native callback thread and `stop()`. A disconnect racing an
  input event could pass a freed handle to JNI (use-after-free), and two
  `stop()` calls could double-free it. — FIXED: `AtomicLong` handle
  (SpiceClient.kt:65) plus a `ReentrantReadWriteLock` (:74); ownership is
  taken with `getAndSet(0L)` so exactly one caller can ever destroy
  (`destroyOwned`, :167), and every native call goes through `withHandle`
  (:182) under the read lock.
- [x] `ConsoleTab.cleanup()` (:239) stopped the client without detaching it,
  so a late native callback could still reach a listener bound to a view
  hierarchy that had already been torn down. — FIXED: `listener` and
  `onSessionEnded` are cleared before `stop()`.

## Pass 2: Hostile-server validation

- [x] `SpiceClient`: surface and framebuffer-update callbacks trusted the
  server's dimensions and the array length together. A hostile or buggy
  server could report a surface whose pixel count overflowed `Int`, or a
  rect larger than the array backing it, producing an out-of-bounds copy in
  the view. — FIXED: `SpiceConstants.MAX_DIMENSION` = 16384 (:114), a
  `MAX_PIXELS` product computed in `Long` (SpiceClient.kt:50) and a single
  `isValidSurface()` gate (:255) applied to all four inbound paths
  (:266, :278, :289, :301). Invalid updates are dropped, not clamped.
- [x] `SpiceView`: rect updates were blitted without checking that
  `x + w`/`y + h` stayed inside the current surface, and the framebuffer
  dimensions were read unsynchronised from the draw thread while the
  callback thread reallocated them. — FIXED: `@Volatile` dimensions (:83),
  a single `allocateSurface()` (:329) and `synchronized(fbLock)` around
  every read and write of the surface (:270, :337, :395, :671).

## Pass 3: Secret handling

- [x] `SpiceConnectionParams`: the compiler-generated data-class `toString`
  printed the SPICE ticket verbatim, and the params object reaches several
  log sites. — FIXED: redacting `toString()` (:75).
- [x] `ProxmoxApiClient.SPICEProxyResult`: same problem — the ticket
  returned by `/spiceproxy` was printable. — FIXED: hand-written
  `toString()` (:639) with the password masked, plus `equals` that does not
  leak by field ordering (:625).
- [x] `LibvirtApiClient`: the `virsh domdisplay` result was logged in full,
  so a `spice://…?password=…` URI put the ticket in logcat. — FIXED: the
  log line now carries host and port only.

## Pass 4: Input completeness

- [x] `SpiceKeyMap`: 22 keycodes had no PC scancode (function keys past F10,
  the numeric keypad, `Insert`, `Delete`, `Home`, `End`, `PageUp`,
  `PageDown`, the navigation cluster), so those keys silently did nothing on
  a SPICE console. — FIXED: full scancode table in `SpiceConstants`, plus a
  modifier-aware fallback that recovers the unmodified base key when a
  modified press has no direct mapping.
- [x] `SpiceView`: pointer events reported a hardcoded left button and there
  was no `onGenericMotionEvent`, so right-click, middle-click and the scroll
  wheel were unreachable from a mouse or trackpad. — FIXED: real button
  state from `MotionEvent.buttonState`, and `onGenericMotionEvent` (:461)
  translates `ACTION_SCROLL` into button 4/5 press-release pairs the way
  spice-gtk does.
- [x] `SpiceView`: the focal-pan clamp was asymmetric (the same bug the VNC
  audit found in `VncView`), so panning left or up snapped back. — FIXED:
  symmetric `±(fb - view/scale)/2` bounds.

## Pass 5: Coroutines and integration

- [x] `ProxmoxManagerActivity` (:359), `LibvirtManagerActivity`,
  `XCPngManagerActivity`, `ProxmoxApiClient.getSPICEProxy`: console launches
  caught `Exception` generically, swallowing `CancellationException` and
  leaving a cancelled scope running to completion. — FIXED:
  `CancellationException` is rethrown before every generic catch.
- [x] `LibvirtApiClient`: the host and port parsed out of `domdisplay` were
  passed to the client without validation. — FIXED: port range-checked
  1..65535 and the host rejected if it carries whitespace, control
  characters or separators, before it is dialled.

## Pass 6: virt-viewer `.vv`, `spice://`, `vnc://` (feature work)

Implemented in a new `hypervisor/viewer/` package, deliberately free of
Android framework types (`android.net.Uri`'s unit-test stubs throw) so all
three parsers are exercised by plain JVM tests.

- `VirtViewerConnection.kt` — shared descriptor for all three sources, with
  a redacting `toString()` and `toSpiceParams()`.
- `VirtViewerFile.kt` — `.vv` INI parser (`[virt-viewer]`: `type`, `host`,
  `port`, `tls-port`, `password`, `ca`, `host-subject`, `proxy`, `title`,
  `delete-this-file`, `fullscreen`, `enable-usbredir`, `release-cursor`,
  `secure-attention`, `toggle-fullscreen`; unknown keys ignored, last value
  wins). Bounded at 256 KiB / 4096 lines / 4 KiB per value / 64 KiB CA.
- `UriParsing.kt` — shared, hostile-input-hardened URI helpers. Percent
  decoding happens *before* validation, so an encoded control character or
  separator cannot slip past as literal `%00` text.
- `SpiceUri.kt` — `spice://` and `spice+tls://`, including the
  `?tls-port=` form virt-manager emits; userinfo rejected.
- `VncUri.kt` — `vnc://[user[:password]@]host[:port]` per RFC 7869 plus the
  TigerVNC/RealVNC userinfo convention, default port 5900, `?password=` /
  `?username=` fallback. Userinfo is split at the **last** `@` so an encoded
  `@` in a user name cannot move the host boundary.
- `VncAuthProbe.kt` — a `vnc://` link carries no saved host to pull a
  password from, and `RfbClient` only discovers that one is needed inside
  the tab, after the launcher has finished. The probe runs the version
  handshake, reads the offered security types and closes — it never selects
  a type and never authenticates — so the launcher can prompt before the
  tab exists. It mirrors `RfbClient`'s own preference order (VeNCrypt,
  None, then VNC auth), and returns "no password" on any failure so the
  real connect surfaces the error.
- `LinkHandlerActivity` — dispatches the new schemes and `.vv` files, always
  through a `MaterialAlertDialogBuilder` confirmation showing host, port and
  whether the link is encrypted; never auto-connects. `.vv`
  `delete-this-file=1` is honoured, and every deletion failure is swallowed
  at debug level. `.vv type=vnc` and `vnc://` converge on one
  `launchVncTab()` helper.
- `AndroidManifest.xml` — VIEW filters for `spice`, `spice+tls`, `vnc`,
  `application/x-virt-viewer`, and a `content`/`file` + `.vv` pathPattern
  fallback. Custom schemes are exempt from App Links verification; no new
  permissions.

Secrets: a ticket or password from a `.vv` file or a URI is used once,
in-memory, and never persisted — the console tab gets
`ConsoleConnectParams(password = "")` and the VNC path a transient
`VncHost` with `keepAliveInBackground = false`. It is never written to the
Keystore store the saved VNC hosts use, and never logged.

## Tests added

- `VirtViewerFileTest` — 30 tests: full and minimal descriptors, TLS-only,
  CA unescaping, unknown keys/sections/comments, case-insensitivity,
  repeated keys, boolean spellings, every malformed rejection, hostile
  bounds (oversized document/value/line-count/host, ports 0/-1/65536/
  999999999999), redacting `toString`, `toSpiceParams`, `parseOrNull`,
  `looksLikeVirtViewerFile`.
- `SpiceUriTest` — 22 tests: both schemes, case-insensitive scheme,
  `tls-port`/`port` parameters, percent-decoded parameters, ignored path and
  fragment, IPv6 literals, userinfo rejection, malformed IPv6, oversized URI
  and host, `%00` in the host, redaction.
- `VncUriTest` — 36 tests: default and explicit port, userinfo with and
  without a password, password-only userinfo, `?password=`/`?username=`,
  userinfo precedence, `?port=` override, percent-encoded userinfo, the
  last-`@` host boundary, IPv6 literals, and the hostile set (bad ports,
  missing host, oversized host/username/password, encoded control character
  and separator in the host, malformed IPv6), plus redaction and the
  guarantee that a parse error never quotes the password.
- `VncAuthProbeTest` — 12 tests: VNC-auth-only, None present, VeNCrypt
  present, the RFB 3.7 / high-minor-3.8 / unknown-minor-3.3 version
  replies, the RFB 3.3 U32 path (None and VNC auth), zero type count,
  non-RFB greeting, unparseable minor version, and the assertion that the
  probe writes exactly the 12-byte version reply and nothing else.

Full gate result: `make check` BUILD SUCCESSFUL, 466 unit tests, 0
failures, 10 skipped.

## Notes for the user

- This audit was instructed not to commit and not to touch `CHANGELOG.md`.
  The `.vv` / `spice://` / `vnc://` support and the SPICE input additions
  are user-visible, so AI.md PART 0 requires a `CHANGELOG.md` entry in the
  same commit — the committing session must add it.
- The `proguard-rules.pro` finding is the one to note: SPICE was
  non-functional in every minified build and no debug testing could have
  revealed it.
- Flaky test, pre-existing and outside this audit's scope:
  `TerminalViewComposingFlushTest > flushPendingComposing sends composing
  text before an escape write races ahead` failed with a
  `ComparisonFailure` at `TerminalViewComposingFlushTest.kt:90` on one
  `make check` run and passed on the next with no change to the file or
  anything it depends on. It is a timing-dependent test and should be made
  deterministic; recorded here because this session was instructed not to
  edit `TODO.AI.md`.

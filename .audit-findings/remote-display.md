# Remote Display / Hypervisor Stack — Audit Findings

Scope: VNC/RFB client, SPICE client, hypervisor API clients (Proxmox, XCP-ng
direct XAPI, Xen Orchestra, VMware, libvirt-over-SSH, OCI), their views,
input handling, and connection lifecycle. All paths relative to
`app/src/main/java/io/github/tabssh/` unless absolute.

---

### [BLOCKER] [CONFIRMED] XCPngApiClient mis-parses the XAPI XML-RPC envelope — session ID and every parsed value extracted from the wrong region

**Where:** `hypervisor/xcpng/XCPngApiClient.kt:142-145` (authenticate),
`358-362` (parseStringResponse), and the sibling `parseIntResponse` /
`parseBooleanResponse` helpers (~376-400).

**What:** XAPI wraps every response payload in a Status/Value struct:

```xml
<methodResponse><params><param><value>
  <struct>
    <member><name>Status</name><value>Success</value></member>
    <member><name>Value</name><value>OpaqueRef:xxxx</value></member>
  </struct>
</value></param></params></methodResponse>
```

`authenticate()` does:

```kotlin
if (response.contains("<value>") && response.contains("OpaqueRef:")) {
    val start = response.indexOf("<value>") + 7
    val end = response.indexOf("</value>", start)
    sessionId = response.substring(start, end)
```

The first `<value>` is the OUTER wrapper and the first `</value>` closes the
INNER "Success" value, so `sessionId` captures
`<struct><member><name>Status</name><value>Success` (plus whitespace) — never
the OpaqueRef. The `contains("OpaqueRef:")` guard still passes because the
ref appears later in the body, so authentication is reported successful while
the stored session is garbage. `parseStringResponse` has the identical
first-`<value>` extraction, so VM name-labels, power states, and console
locations are equally mis-extracted whenever the server emits the documented
struct envelope.

**Failure scenario:** User adds an XCP-ng/XenServer host in direct-XAPI mode
(live call sites: `XCPngManagerActivity.kt:418`,
`HypervisorEditActivity.kt:789`). "Test connection"/login appears to succeed;
every subsequent call (`VM.get_all`, `VM.get_power_state`,
`console.get_location`) carries an invalid `session_id`, XAPI answers
`SESSION_INVALID` faults, and the manager shows an empty/never-loading VM
list — or garbage names — with no error dialog.

**Fix:** Parse the XML-RPC response properly (XmlPullParser, as
VMwareApiClient already does for SOAP): locate the `<member>` whose
`<name>` is `Value` and take that member's `<value>` text; check the
`Status` member for `Failure` and surface `ErrorDescription`. The parse path
is untested (only `XapiConsoleUrlTest.kt` exists) — add a fixture test with
a real XAPI envelope.

---

### [MAJOR] [CONFIRMED] XCPng XML-RPC faults and Status:Failure responses (HTTP 200) are treated as success — power operations return true when the server refused

**Where:** `hypervisor/xcpng/XCPngApiClient.kt:321-356` (xmlRpcCall) and the
power methods `startVM` 214-225, `shutdownVM` 227-238, `rebootVM` 240-251,
`hardShutdownVM` 253-264, `hardRebootVM` 270-281.

**What:** `xmlRpcCall` throws only on non-2xx HTTP. XAPI delivers both
`<fault>` responses and `Status: Failure` struct responses with HTTP 200.
Only `authenticate()` looks for "Fault"; the power methods do
`xmlRpcCall(...); Logger.i("... succeeded"); true` unconditionally.

**Failure scenario:** User taps "Shutdown" on a VM whose power state forbids
it (`VM_BAD_POWER_STATE`), or the session has expired (`SESSION_INVALID`),
or the account lacks permission. XAPI returns a Failure struct with HTTP
200; the app logs success and returns `true`; the UI reports the action
succeeded while nothing happened on the server. Compounds with the BLOCKER
above: the garbage session makes every power op fail server-side, yet each
one reports success.

**Fix:** In `xmlRpcCall`, after the HTTP check, inspect the body for
`<fault>` and for a `Status` member equal to `Failure`; throw with the
sanitized `ErrorDescription` / faultString.

---

### [MAJOR] [CONFIRMED] Pinch-zoom begins by sending a spurious left-click into the VM (VncView and SpiceView)

**Where:** `ui/views/VncView.kt:398-412` (ACTION_DOWN), `427-432`
(ACTION_UP); `ui/views/SpiceView.kt:412-428` / `442-450` (structural twin).

**What:** `onTouchEvent` fires a pointer event with `BTN_LEFT` pressed on
every `ACTION_DOWN` (`lastButtonMask = ... BTN_LEFT; firePointer(...)`,
VncView.kt:410-411) before it can know whether a second finger is about to
land. When the gesture turns out to be a pinch, the button-down has already
been delivered; the release only comes at `ACTION_UP`/`ACTION_CANCEL`.

**Failure scenario:** User pinch-zooms over a desktop icon, button, or
window title bar: the VM receives a left-button press at the first finger's
position (selecting/activating/dragging whatever is under it) that is held
for the duration of the pinch. Zooming over a window title bar drags the
window; over a text editor it sets the caret or starts a selection.

**Fix:** Defer the button-down until the gesture is disambiguated — e.g.
send press on `ACTION_DOWN` only after tap-slop expiry or first
`ACTION_MOVE` with `pointerCount == 1`, and on `ACTION_POINTER_DOWN`
immediately send a button-up if a press was already emitted.

---

### [MAJOR] [CONFIRMED] Scroll-wheel emulation drops slow scrolls entirely — no accumulator across events (VncView and SpiceView)

**Where:** `ui/views/VncView.kt:209` (`val steps = (distY /
40f).toInt()...`), `ui/views/SpiceView.kt:224` (same expression).

**What:** `onScroll` converts each per-event distance to wheel notches with
integer truncation and no carry. GestureDetector delivers `onScroll` per
frame; a slow one-finger scroll produces many events with `distY` in the
2-25 px range, every one of which truncates to 0 steps.

**Failure scenario:** At fit zoom (`userScale <= 1.05`), user drags a finger
slowly to scroll a document/terminal in the VM: nothing scrolls at all,
because no single 16 ms movement reaches 40 px. Fast flicks scroll; slow
deliberate scrolling is dead. Perceived as "scrolling is broken/laggy".

**Fix:** Keep a `scrollRemainder` float field: `remainder += distY; steps =
(remainder / 40f).toInt(); remainder -= steps * 40f`; reset on
ACTION_DOWN.

---

### [MAJOR] [CONFIRMED] One-finger drag at fit zoom emits scroll clicks while the left button is still held down (VncView and SpiceView)

**Where:** `ui/views/VncView.kt:414-417` (ACTION_MOVE re-fires
`lastButtonMask` = BTN_LEFT) combined with `207-216` (onScroll fires
BTN_SCROLL_* press/release for the same movement);
`ui/views/SpiceView.kt:429-441` + `227-234`.

**What:** For a single-finger drag at fit zoom, BOTH paths run on every
move event: `onTouchEvent`/ACTION_MOVE sends motion with BTN_LEFT held
(started at ACTION_DOWN), and `gestureDetector.onScroll` (fast drags,
`|distY| >= 40`) interleaves press/release of scroll buttons 4/5. The VM
sees left-drag and wheel events simultaneously.

**Failure scenario:** User drags fast across the screen at fit zoom to move
a window or select text: the selection/drag proceeds while the remote app
also receives wheel scrolls, so the content under the drag scrolls away
mid-selection; in a terminal the buffer jumps while the mouse button is
down. Wrong, compounding input reaches the server.

**Fix:** Make the two paths exclusive: while `lastButtonMask != 0` (a
button press was already delivered), suppress `onScroll`'s wheel emulation;
or treat single-finger movement at fit zoom as either drag or scroll based
on a mode toggle, never both.

---

### [MINOR] [CONFIRMED] Long-press right-click is always preceded by a full left-press (both views)

**Where:** `ui/views/VncView.kt:184-188` (`onLongPress` fires BTN_RIGHT)
after `398-411` already sent BTN_LEFT at ACTION_DOWN;
`ui/views/SpiceView.kt:191-199` same pattern.

**What:** By the time `onLongPress` fires, a BTN_LEFT press has been held
for ~500 ms at the same spot. The sequence delivered is left-down (500 ms)
→ right-down → right-up (100 ms later) → left held until finger lift.

**Failure scenario:** Long-press to right-click a desktop icon: the icon
first receives a left press (selecting it, or beginning a drag), then the
right-click menu opens while the left button is still logically down; on
lift, the left-up lands wherever the finger is. On some apps the left-hold
cancels or repositions the context menu; text fields begin a selection
before the menu appears.

**Fix:** When the press is still within tap-slop and long-press fires,
send a left-button release before the right press (or defer the initial
left press as in the pinch fix, which resolves this too).

---

### [MINOR] [CONFIRMED] Double-tap zoom toggle also delivers two real left-clicks to the VM (both views)

**Where:** `ui/views/VncView.kt:174-182` (`onDoubleTap` toggles zoom) while
`onTouchEvent:398-432` independently sends a full left press/release for
each of the two taps; `ui/views/SpiceView.kt:181-189`.

**What:** The gesture layer consumes the double-tap for zoom, but the raw
touch path has already forwarded both taps as clicks.

**Failure scenario:** User double-taps a folder icon to zoom in: the folder
opens (remote double-click) AND the view zooms. Every zoom toggle is also a
remote double-click at that position.

**Fix:** Same root cause as the pinch/long-press findings — press
forwarding must be deferred until the gesture layer disambiguates
(single-tap confirmed), e.g. move click delivery to
`onSingleTapConfirmed`.

---

### [MINOR] [CONFIRMED] zoomActual() sets userScale far above MAX_SCALE with no clamp (both views)

**Where:** `ui/views/VncView.kt:740-743` (`userScale = 1f / fitScale`, no
`coerceIn`; MAX_SCALE = 4.0 at line 51); `ui/views/SpiceView.kt:684-689`.

**What:** Pinch zoom clamps to `MIN_SCALE..MAX_SCALE`
(VncView.kt:228), but the "actual size" action assigns `1/fitScale`
directly. For a 3840×2160 framebuffer on a ~1080 px-wide phone, fitScale ≈
0.28 → userScale ≈ 3.6 is fine, but a 7680-wide framebuffer or split-screen
window gives userScale > 4 — beyond what any pinch can reach or return
from smoothly (the next pinch snaps it into range, causing a visible jump).

**Failure scenario:** Connect to a high-DPI/wide VM in split-screen, tap
"1:1 zoom": view jumps past the app's own zoom ceiling; first pinch
afterwards snaps the scale discontinuously.

**Fix:** `userScale = (1f / fitScale).coerceIn(MIN_SCALE, MAX_SCALE)` (and
accept that true 1:1 is capped), or raise MAX_SCALE consistently.

---

### [MINOR] [CONFIRMED] Xen Orchestra client leaks a pooled connection on every successful power/snapshot/backup action

**Where:** `hypervisor/xcpng/XenOrchestraApiClient.kt` — startVM 505-511,
stopVM 537-543, rebootVM 567-573, resetVM 598-604, suspendVM 628-634,
resumeVM 658-664, createSnapshot 795-801, deleteSnapshot 825-831,
revertSnapshot 855-861, triggerBackup 980-986.

**What:** Each does `if (response.isSuccessful) { Logger.i(...); true }`
without reading or closing the body on the success branch. The failure
branch reads the body (via error handling); success leaves the Response
open until GC finalization. The file's own comment at 1527-1529 documents
exactly this defect ("the body was never consumed or closed, so the
connection stayed checked out of the pool") but the fix was applied only to
`getConsoleWebSocketUrl`.

**Failure scenario:** User manages VMs through XO, performing a series of
start/stop/snapshot actions: each success checks a connection out of
OkHttp's pool permanently (until finalizer). OkHttp logs "connection
leaked" warnings; after ~5 concurrent leaked connections to the same host,
new calls to that XO instance stall waiting for a pool slot until
finalization runs.

**Fix:** Wrap in `response.use { ... }` (or `response.close()` /
`body.close()` on the success branch), as already done at 1527.

---

### [MINOR] [CONFIRMED] XO client reads response bodies unbounded, unlike every other hypervisor client

**Where:** `hypervisor/xcpng/XenOrchestraApiClient.kt` — `response.body?.string()`
throughout (e.g. the list/detail GET paths).

**What:** Proxmox, XCPng, VMware, and OCI clients all bound reads at 8 MiB
with an explicit "hypervisor is a trust boundary" rationale
(`ProxmoxApiClient.kt:845-853`, `VMwareApiClient.kt:323-331`,
`OciApiClient.kt:165-173`). The XO client buffers whatever the server
sends.

**Failure scenario:** A compromised or misbehaving XO endpoint (or a
captive portal answering in its place with a huge page) streams an
unbounded body; the app buffers it until OOM kills the process.

**Fix:** Port `readBounded` from ProxmoxApiClient.

---

### [MINOR] [CONFIRMED] vnc:// URI passwords containing '+' are silently corrupted to spaces

**Where:** `hypervisor/vnc/UriParsing.kt:140-147` (`decode()` uses
`URLDecoder.decode(s, "UTF-8")`), applied to userinfo at `VncUri.kt:92`.

**What:** `URLDecoder` implements form-encoding, where `+` means space.
URI userinfo percent-encoding has no such rule. The project already fixed
this exact class of bug twice elsewhere (`ProxmoxApiClient.encodePathSegment`
comment at 76-77; `LibvirtApiClient.extractVncUserinfoPassword` doc at
103-111 explicitly recounts "a correct password such as a+b2cd42 failed
the VNC-Auth challenge").

**Failure scenario:** User opens a `vnc://user:pa+ss@host` deep link (or a
.vv/bookmark that routes through VncUri): password becomes `pa ss`, VNC
auth fails with "authentication failed", no hint why.

**Fix:** Percent-decode without form semantics: replace only `%hh`
sequences (or pre-escape `+` as `%2B` before URLDecoder, as
encodePathSegment does in reverse).

---

### [MINOR] [CONFIRMED] RFB extended clipboard pseudo-request consumed but never answered

**Where:** `hypervisor/console/rfb/RfbClient.kt:~1551-1564` (ServerCutText
with negative length — Extended Clipboard, QEMU/TigerVNC).

**What:** When the server negotiates the Extended Clipboard pseudo-encoding
and sends a `caps`/`request` message (length < 0), the client reads and
discards the payload without replying (no `provide`/`notify`). Legacy
(positive-length) cut text works.

**Failure scenario:** Against a QEMU VNC server with extended clipboard
active, server-initiated clipboard requests go unanswered — copy in the VM
never reaches the Android clipboard and the server may stop sending legacy
cut text once extended caps were exchanged, so clipboard sync silently dies
for the session.

**Fix:** Either do not advertise the Extended Clipboard pseudo-encoding, or
implement the minimal caps + provide/request flow.

---

### [MINOR] [CONFIRMED] RfbDecoder Inflaters are never end()ed

**Where:** `hypervisor/console/rfb/RfbDecoder.kt:972-976` (`reset()` nulls
the Zlib/ZRLE/Tight `Inflater` references without calling `end()`).

**What:** `Inflater` holds native zlib memory freed only by `end()` or
finalization. Each reset/reconnect cycle abandons up to 6 inflaters (Tight
uses 4 streams) to the finalizer.

**Failure scenario:** Long session with repeated reconnects
(NetworkAwareReconnector on flaky Wi-Fi): native memory accumulates between
GC finalizer runs; on memory-constrained devices this contributes to the
process being killed in the background.

**Fix:** Call `end()` on each non-null Inflater in `reset()` before
nulling.

---

### [MINOR] [CONFIRMED] LibvirtApiClient.startDomain swallows virsh errors whose text contains "started"

**Where:** `hypervisor/libvirt/LibvirtApiClient.kt:361-372`.

**What:**

```kotlin
if (!output.contains("started")) {
    if (isVirshError(output)) { throw ... }
}
```

The error check runs only when the output does NOT contain the substring
"started". virsh error messages echo the domain name, so any domain whose
name contains "started" (e.g. `restarted-web`, `started-fresh`) makes every
`virsh start` failure — including "Domain not found" and "already active" —
pass silently: the method logs success and returns normally. Every other
power method (destroy/shutdown/reboot/reset, lines 379-425) checks
`isVirshError` unconditionally; only startDomain has the guard inverted.

**Failure scenario:** User taps Start on a shut-off domain named
`restarted-web` while it is already running (or after it was undefined):
virsh prints `error: ... 'restarted-web' ...`, the app reports the start
succeeded, the list still shows the wrong state with no error.

**Fix:** Check `isVirshError(output)` first, unconditionally, matching the
other four power methods; the `contains("started")` success check is
redundant with it.

---

### [MINOR] [PLAUSIBLE] XO WebSocket event layer speaks a protocol Xen Orchestra does not

**Where:** `hypervisor/xcpng/XenOrchestraApiClient.kt:1334-1498` (sends
`{"type":"authenticate","token":...}`, expects events like `vm.started` /
`snapshot.created`), and the fallback console URL
`wss://$host:$port/api/console/$vmId` at 1553.

**What couldn't be verified:** XO's real WebSocket API at `/api/` is
JSON-RPC 2.0 (`{"jsonrpc":"2.0","method":"session.signInWithToken",...}`).
The custom `type`-tagged messages this layer sends would be rejected or
ignored, so live event updates would never arrive (the UI would fall back
to polling or stale state) — but I could not verify against current XO
server source/docs from this environment, and the layer may target a
different/proxied endpoint. The fallback console URL shape is likewise
unverified.

---

### [MINOR] [PLAUSIBLE] XCPng VM.start booleans sent as strings

**Where:** `hypervisor/xcpng/XCPngApiClient.kt:216` — `VM.start` params
sent as `<value>false</value>` instead of `<value><boolean>0</boolean></value>`.

**What couldn't be verified:** XML-RPC's default type for an untyped
`<value>` is string; XAPI's parser may or may not coerce "false" to a
boolean. If it does not, `VM.start` fails with a type error (which the
fault-swallowing bug above then hides). Needs a live XAPI host to confirm.

---

### [MINOR] [PLAUSIBLE] ConsoleWebSocketClient binary error-check drops a char, not a byte

**Where:** `hypervisor/console/ConsoleWebSocketClient.kt` (serial-error
prefix check uses `text.drop(1)` on a string decoded from a binary frame).

**What couldn't be verified:** If the first byte is part of a multi-byte
UTF-8 sequence, `drop(1)` removes one decoded char (potentially multiple
bytes), so the forwarded payload could differ from the wire bytes.
Whether any real server puts multi-byte UTF-8 at that position is
unverified; for ASCII protocols the behavior is identical.

---

## Verified-safe (checked, no finding)

- **ProxmoxApiClient.kt** (939 lines, full read): `encodePathSegment`
  corrects URLEncoder `+`; 8 MiB bounded reads; `sanitizeServerText`;
  redacted `toString()` on ticket-bearing results; spiceproxy field
  length/port bounding; CA `\n` unescape; `tlsVerify=true` forced in
  `toConnectionParams`; `@Volatile` tickets; CancellationException
  re-thrown; error propagation on all power/snapshot ops (apiPost throws
  on non-2xx with proxmoxErrorDetail).
- **HypervisorTrustManagerFactory.kt** (full read): three-mode TOFU model
  consistent; `systemTrustedLeaves` correctly gates strict hostname
  verification to system-CA-accepted leaves only; pin comparison
  case-insensitive; empty-chain rejected; `onPinCaptured` synchronous
  persistence design sound.
- **HypervisorCertPromptDialog**: handshake thread blocks on a
  CountDownLatch with 30 s timeout defaulting to REJECT — inside the
  clients' 60 s callTimeout, no deadlock.
- **VMwareApiClient.kt** (873 lines, full read): bounded reads;
  `parseSessionId` rejects control chars (header-injection guard);
  `requireValidMoRef` blocks path retargeting; both /rest and /api envelope
  shapes handled; 401 re-auth-once with correct decide-before-read
  ordering; SOAP `xmlEscape` on every interpolation including password;
  `withSoapSession` Logout in finally; XmlPullParser DOCDECL off;
  faultstring sanitized; snapshot-tree parser state machine correct for
  vim25 schema order; xsd:dateTime parser handles Z and ±hh:mm.
- **OciApiClient.kt + OciSigner.kt** (full read): OCID validation before
  path interpolation; query params via HttpUrl builder (encoded);
  bounded reads; error bodies logged by size only; pagination bounded
  (MAX_PAGES, repeated-token detection); dual-host pin storage with
  fixed positions; cavage signing string matches wire headers (bare
  content-type re-stamped on the body to defeat BridgeInterceptor
  charset injection); empty body correctly signed for instanceAction.
- **LibvirtApiClient.kt** (817 lines, full read, except the startDomain
  finding above): `shQuote` POSIX single-quote escaping as the injection
  barrier; `requireValidDomain`/`requireValidSnapshotName`
  defence-in-depth; `isVirshError` anchored to line start (name-echo
  false-positive documented and avoided); exec output bounded at 1 MiB
  with timeout-means-throw (no partial-output-as-success);
  `--include-password` URIs never logged (scheme only); VNC password
  taken raw from userinfo (URLDecoder bug explicitly avoided);
  `parseSpiceUri` reads `password=` query param, bounds host/ports;
  channel disconnect on connect-throw; TOFU host-key verification with
  changed-key hard reject; `vncdisplay` parse anchored per-line.
- **XenOrchestraApiClient.kt** partial: `isAcceptableConsoleUrl` blocks
  off-host token leak; `executeRequest` 401 re-auth closes the stale
  response before retry and throws on re-auth failure; tokens never
  logged; REST version auto-detect (v6→v5→v0) sound.
- **XCPngApiClient.kt** partial: `xmlEscape` blocks XML value-smuggling;
  bounded xmlRpcCall read; session never logged; `consoleUrlWithSession`
  covered by XapiConsoleUrlTest; `Logger.urlForLogging` redacts
  session-bearing URLs.
- **RfbClient.kt** (1951 lines, full read, except the extended-clipboard
  finding): handshake for RFB 3.3/3.7/3.8; VncAuth/VeNCrypt paths;
  per-host tlsVerify switch is by-design; blob/pixel/rect caps enforce
  untrusted-server hardening (MAX_BLOB_BYTES 16 MB, MAX_FB_PIXELS 64 Mpx,
  MAX_RECT_BYTES 64 MB).
- **RfbDecoder.kt** (full read, except Inflater finding): bounds checks on
  every encoding path.
- **VncDirectConnector.kt**: socket closed on any throw between allocation
  and return; `connectWss` forwards the caller's listener so TLS failures
  are not misread as clean disconnects; onClientReady early-publish
  prevents the onPinCaptured race.
- **JnlpFile.kt / VirtViewerFile.kt / VirtViewerConnection**: XXE
  disallow-doctype-decl with manual `<!DOCTYPE` fallback; bounded
  document/argument sizes; redacted toString; host plausibility checks.
- **ConsoleStrategy chain / HypervisorConsoleManager / ConsoleTab /
  VncConsoleChannel / ByteStreamPipe**: ranked-candidate fall-through
  with CancellationException re-thrown; null-vs-throw contract honored.
- **SpiceClient / PixelFormat / SpiceUri / SpiceKeyMap /
  SpiceConnectionParams / SpiceLoader / SpiceConstants**: isValidSurface
  MAX_PIXELS enforcement covers SpiceView allocateSurface; params
  require() port validation.
- **VncBackgroundSessionStore / VncKeepAliveService /
  NetworkAwareReconnector**: park/reclaim lifecycle and bounded backoff
  correct.
- **VncAuthProbe / VncTab / VncServerProfile / ConsoleKeyMapper /
  ConsoleErrorInfo / ConsoleDisconnectReason**: no findings.

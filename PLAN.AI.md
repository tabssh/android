# PLAN — Universal VNC / SPICE Console Coverage

> **Lifecycle:** delete this file when every section under "Work Items" is shipped and reflected in `TODO.AI.md → Recently Shipped`. Do NOT commit a half-done plan as the source of truth — it's scratch.

## Goal (from user, verbatim)

> VNC must support proxmox (with spice display support), qemu, libvirt, xcp-ng, vmware, tightvnc, and all vnc extensions as well and everything should just automagically work… invisible to user.
>
> When done with that we need to add better autodetection for the entire VNC. Fallbacks are not errors (we can and should log though) and are silent until the last fallback fails — then it's an actual error.

## Terminology

"VNC" in the user-facing UI (button labels, settings, host-list) is the
catch-all for **any remote-display console** — standard RFB servers, hypervisor
VM displays that happen to speak RFB, and hypervisor VM displays that speak
SPICE. The implementation dispatches RFB vs SPICE transparently from server
capabilities; the user never sees the protocol name. Code-level types stay
protocol-accurate (`RfbClient`, `SpiceClient`, `RemoteDisplayView`) so the
internal vocabulary doesn't lie.

## Architectural decisions (locked)

- **SPICE:** NDK + upstream `libspice-client-glib` + `libspice-protocol`, JNI wrapper. APK grows ~8–12 MB; all SPICE channels supported (display, inputs, cursor, playback, record, smartcard, usbredir).
- **VMware:** VNC-via-vmx only (ESXi VMs configured with `RemoteDisplay.vnc.enabled = TRUE`). MKS/WMKS is out of scope — proprietary, no public spec, fragile across versions. Clear error when VNC is not enabled.

## Current state (survey baseline)

| Hypervisor | Today | Target |
|---|---|---|
| Proxmox | termproxy text + vncproxy VNC | + SPICE (spiceproxy) for `display=spice` VMs |
| QEMU (direct) | Direct VNC works | unchanged |
| libvirt (SSH) | VNC via `virsh vncdisplay` tunnel | + SPICE stream via libvirt |
| XCP-ng (XAPI) | Text console only | + VNC console (`console.get_location` for VNC consoles) |
| Xen Orchestra | Text only | + VNC console (XO REST `/vms/:id/vnc`) |
| VMware | Stub — list/power only | + VNC-via-vmx direct connect to ESXi host:port |
| TightVNC | Works (standard RFB Tight encoding) | unchanged |
| Direct VNC | Works (TCP, VeNCrypt, X509) | + WSS variant for any host that exposes WebSocket VNC |

## RFB extension gaps (vs. current `PREFERRED_ENCODINGS`)

Already supported: ZRLE · Tight · Zlib · Hextile · CoRRE · CopyRect · RRE · Raw · ExtendedDesktopSize · QEMU EDS · DesktopSize · Cursor / RGBA Cursor / XCursor · PointerPos · Fence · ContinuousUpdates (advertised) · DesktopName · LEDState · LastRect.

Gaps to close:
- **ContinuousUpdates** — advertised, but `EnableContinuousUpdates` (client→server msg 150) and `EndOfContinuousUpdates` (S→C msg 150) need a verified end-to-end flow. Currently we advertise and the constant exists; confirm we actually send EnableContinuousUpdates and stop spamming FBURs when the server is in CU mode.
- **XVP** (S→C 250 / C→S 250) — already parsed in `RfbConstants`; verify dispatch path. Lets the client request shutdown/reboot/reset of the VM.
- **ServerIdentity / DesktopName updates** mid-session — extend the listener so `onDesktopNameChanged` re-evaluates server-type heuristics (e.g. a server that swaps to `"QEMU (...)"` mid-stream should also flip `canRequestResize`).
- **JPEG quality + compression level pseudo-encodings** — already in constant list; verify they're actually emitted in `sendSetEncodings()` based on user pref (battery vs. quality).
- **TightPng** — `ENC_TIGHT_PNG` defined; not in `PREFERRED_ENCODINGS`. Add behind a setting (some servers only).
- **GII** (Generic Input Interface) — defined but no dispatcher. Low priority (only relevant for joystick/multi-touch passthrough to guest).
- **Anthony Liguori QEMU Pointer Motion Change** + **QEMU Audio** — defined but no dispatcher. Audio out of scope; pointer motion change worth wiring (sends absolute coords instead of relative).

## Work items (dependency-ordered)

Numbers reflect execution order. A task is "ready" only when all of its prerequisites are complete.

### Foundation (no SPICE dependency — can ship first)

1. **Strategy chain refactor** — introduce `ConsoleStrategy` interface and `ConsoleStrategyChain.connect()` that tries each strategy in order; only the final failure surfaces to UI; intermediate failures are `Logger.i` only. Refactor existing Proxmox termproxy→vncproxy code to use it. *Files:* new `hypervisor/console/ConsoleStrategy.kt`, modify `HypervisorConsoleManager.kt`, `ProxmoxApiClient.kt`.
2. **Server-class autodetection** — central `VncServerProfile` derived from desktop name + capability signals (ExtendedDesktopSize support, encoding list, security types). Replaces the inline `name.startsWith("QEMU (")` check shipped in `8e559a9b671f`. Adds: TigerVNC, TightVNC, RealVNC, x11vnc, UltraVNC, libvirt-built-in, Proxmox-vncproxy variants. *Files:* new `hypervisor/console/rfb/VncServerProfile.kt`, modify `RfbClient.kt`.
3. **RFB extension polish** — verify ContinuousUpdates handshake, wire XVP dispatch, mid-session DesktopName re-detect, JPEG quality/compression-level emission gated on settings. Add TightPng to encoding list behind a pref. *Files:* `RfbClient.kt`, `RfbDecoder.kt`, `RfbConstants.kt`, new `settings/VncSettings.kt`.
4. **XCP-ng VNC console** — extend `XCPngApiClient` to detect graphical-console VMs (XAPI `VM.get_consoles` returns refs; each has a `protocol` field — `rfb` vs `vt100`); return a `Graphical(RfbClient)` for `rfb` consoles. WebSocket transport same as text. *Files:* `XCPngApiClient.kt`, `HypervisorConsoleManager.kt`.
5. **Xen Orchestra VNC console** — XO REST `/rest/v0/vms/:id/console` returns an upgrade-able WebSocket; detect `protocol=rfb`. *Files:* `XenOrchestraApiClient.kt`.
6. **VMware VNC-via-vmx** — `VMwareApiClient.openConsole()` reads VM config for `RemoteDisplay.vnc.enabled` + `RemoteDisplay.vnc.port` + `RemoteDisplay.vnc.password`; opens direct TCP to ESXi host on that port; returns `RfbClient`. Clear error when VNC is not enabled. *Files:* `VMwareApiClient.kt`, `HypervisorConsoleManager.kt`.
7. **Direct VNC + WSS variant** — `VncDirectConnector` already does TCP; add `connectWss(url, ...)` for hosts that expose RFB over WebSocket (some KasmVNC / novnc setups). *Files:* `VncDirectConnector.kt`.

### SPICE (gated on NDK build pipeline)

8. **NDK + libspice build harness** — add `app/src/main/cpp/` with `CMakeLists.txt` that vendors `spice-protocol` (header-only) + `spice-gtk` (the GLib client). Cross-compile for `arm64-v8a`, `armeabi-v7a`, `x86_64`. Update `app/build.gradle` with `externalNativeBuild { cmake { ... } }`. Decide static vs. shared; prefer static to avoid the multi-`.so` shipping headache. *Files:* new `cpp/CMakeLists.txt`, `cpp/spice_jni.c`, modify `app/build.gradle`, possibly new `docker/Dockerfile.ndk` for reproducible builds.
9. **SPICE JNI client** — minimal Kotlin facade `hypervisor/console/spice/SpiceClient.kt` mirroring `RfbClient`'s shape (`connect/disconnect/onConnected/onFramebufferUpdate/sendPointerEvent/sendKeyEvent`). Channels wired: main, display, inputs, cursor. *Files:* new `hypervisor/console/spice/`, JNI glue in `cpp/`.
10. **SPICE-aware `VncView`** — rename to `RemoteDisplayView` or add a parallel `SpiceView` (same Canvas/Bitmap rendering, different event source). *Files:* `ui/views/`, `ui/activities/TabTerminalActivity.kt`.
11. **Proxmox spiceproxy** — call `/nodes/{node}/qemu/{vmid}/spiceproxy`, parse the returned `.vv` config (host, port, ticket, TLS cert), feed to `SpiceClient`. *Files:* `ProxmoxApiClient.kt`.
12. **libvirt SPICE stream** — `virsh domdisplay <vm>` returns `spice://host:port` for SPICE-configured VMs; tunnel over SSH the same way the VNC path does. *Files:* `LibvirtApiClient.kt`.

### Autodetect + silent-fallback semantics (final pass)

13. **Hypervisor connector chain** — every connector returns a list of `ConsoleStrategy` candidates ranked by likely-to-work. The manager runs the chain; per-strategy failure emits `Logger.i("strategy X failed: ..."); next`; only the final exhaustion surfaces a UI error. Replaces the current hardcoded "termproxy then vncproxy" pair with a generic ordered chain. *Files:* `HypervisorConsoleManager.kt`, every `*ApiClient.kt`.
14. **UI: progress vs. error distinction** — replace the current "Reconnecting without resize…" toast pattern with a single progress overlay that reports the active strategy by name (low-key, debug log only unless final). User sees one spinner; the spinner text updates as we fall through. *Files:* `TabTerminalActivity.kt`.

## Definition of done

- Connecting to any of the 7 listed targets opens a working console without user-visible "trying X…" or "X failed, falling back to Y" toasts.
- SPICE-configured Proxmox VMs render the guest display.
- An ESXi VM with `RemoteDisplay.vnc.enabled = TRUE` opens via VNC; one without surfaces a clear, specific error.
- `make check` clean; manual smoke test against at least Proxmox (text + VNC + SPICE), libvirt (VNC + SPICE), QEMU direct, and one TightVNC server.
- `TODO.AI.md → Recently Shipped` lists each commit with its hash.

## Out of scope (explicit)

- VMware WMKS reverse engineering.
- SPICE audio playback/record (channels exist in `libspice-client` but no UI plumbing this round).
- USB redirect (`usbredir`) — needs `libusb` + Android USB host API permissions; deferred.
- Smartcard passthrough.

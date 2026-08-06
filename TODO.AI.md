# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

Source: 2026-08-06 feature-coverage audit of IDEA.md § Business logic against
the codebase (57 spec features → 53 implemented, 4 partial, 0 missing).

## Open — spec feature gaps (priority order)

### 1. Finish PLAN.AI.md — VNC/SPICE console coverage (IDEA.md features 42, 43)

**Highest priority: this is in-flight work; complete it before starting new
features.** Owned by `PLAN.AI.md`; tracked in detail there. Foundation items
(strategy chain, VncServerProfile, RFB polish, XCP-ng VNC, Xen Orchestra VNC,
direct VNC WSS) have shipped. Still open:

- SPICE delivery pipeline (PLAN item 8): DONE — mosh-parity out-of-tree
  native build is in place. `spice/Dockerfile` + `spice/build-android.sh`
  cross-compile the libspice chain into one `libtabssh_native.so` per ABI;
  `.github/workflows/spice-libs.yml` publishes them; `scripts/fetch-spice-libs.sh`
  drops them into `app/src/main/jniLibs/<abi>/` at build time (wired into
  `make build` + dev-builds/release CI). `app/build.gradle` runs no native
  toolchain. REMAINING: run `spice-libs.yml` on CI to shake out the meson
  cross-build and publish the first `spice-libs-*` release; until then
  `SpiceLoader.isSpiceAvailable()` returns false and every path falls back
  to VNC (by design).
- SPICE consumer facade (PLAN item 9): `hypervisor/console/spice/SpiceClient.kt`
  Kotlin facade over `SpiceLoader` not yet wired. `ui/views/SpiceView.kt`
  exists but is not driven end-to-end.
- SPICE cross-compile recipe: RESOLVED for x86_64 (verified locally in
  Docker — `spice/out/x86_64/libtabssh_native.so`, 11 MB, stripped, all 9
  `Java_..._SpiceClient_native*` + `nativeIsSpiceAvailable` symbols exported,
  `readelf -d` NEEDED = only libm/libdl/liblog/libandroid/libc, i.e. the
  whole SPICE stack is static-linked). Fixes applied: pkg-config search path
  now includes `share/pkgconfig` (spice-protocol installs its .pc there);
  `python3-six`/`python3-pyparsing` for spice-common codegen; static
  libjpeg-turbo (spice-gtk requires libjpeg); GStreamer patched out (see the
  limitation below). REMAINING: run `spice-libs.yml` so CI builds the other
  three ABIs (arm64-v8a, armeabi-v7a, x86) and publishes the first
  `spice-libs-0.42.0` prerelease — only x86_64 is proven; the 32-bit
  armeabi-v7a build in particular may surface ABI-specific issues.
- SPICE GStreamer omission (durable record of a deliberate limitation):
  spice-gtk 0.42 mandates the full gstreamer-1.0 stack, which cannot be
  static-linked into the single self-contained `.so` without pulling in the
  entire GStreamer + plugin tree (defeats the mosh-parity model). The build
  patches GStreamer out (`spice/build-android.sh` + `spice/cpp/spice_gst_stubs.c`)
  and relies on the builtin MJPEG decoder (libjpeg). Consequences to revisit
  if a user needs them: (a) non-MJPEG SPICE video streams (VP8/VP9/H264/H265)
  do not decode — the stream is dropped, the surface still gets ordinary draw
  commands; (b) SPICE audio playback/record is a no-op (already out of scope
  per PLAN.AI.md). If full-motion video streaming becomes a requirement, the
  options are cross-building a static GStreamer subset with manual plugin
  registration, or an alternative in-tree video decoder.
- VMware VNC-via-vmx (PLAN item 6): not implemented — no `RemoteDisplay.vnc`
  handling in `hypervisor/vmware/VMwareApiClient.kt`.
- libvirt SPICE domdisplay (PLAN item 12): not implemented — no `domdisplay`
  / `spice://` path in `hypervisor/libvirt/LibvirtApiClient.kt`.

Delete PLAN.AI.md once its definition-of-done is met and shipped commits are
recorded under Recently Shipped below.

### 2. Hypervisor snapshot missing on 3 of 4 backends (IDEA.md feature 40)

Snapshot (and full power control) is only implemented for XCP-ng / Xen
Orchestra. IDEA.md requires list/start/stop/shutdown/reboot/snapshot across
all four hypervisors.

- `hypervisor/proxmox/ProxmoxApiClient.kt` — start/stop/shutdown/reboot only; no snapshot
- `hypervisor/vmware/VMwareApiClient.kt` — only startVM/stopVM/resetVM; no shutdown, reboot, or snapshot
- `hypervisor/libvirt/LibvirtApiClient.kt` — start/destroy/shutdown/reboot only; no snapshot

Add snapshot create/list/restore/delete for Proxmox, VMware, libvirt; add
shutdown+reboot for VMware. (Console display for these is item 1's job; this
item is the management API surface.)

### 3. tmux/screen/zellij manual picker — ASK mode unimplemented (IDEA.md feature 21)

Auto-attach, create-new, and live autodetection all work. The manual
override picker (ASK mode) does not exist.

- `ssh/SSHTab.kt:852` — ASK mode is treated as AUTO_ATTACH; comment defers the
  tab-level picker dialog to "a future iteration"

Surface a picker dialog when the multiplexer mode is ASK: list detected
sessions with attach / create-new choices.

### 4. Tasker/Locale plugin disabled (IDEA.md feature 54)

Receiver code exists but is gated behind a signature-level permission and
disabled in the manifest, and there is no twofortyfouram Locale plugin
protocol — third-party Tasker cannot invoke connections. (Widgets + the
public intent surface, features 55/56, do work.)

- `automation/TaskerActionReceiver.kt`, `automation/TaskerWorker.kt` — exist but unreachable
- `AndroidManifest.xml:87-101` — receiver gated behind `io.github.tabssh.permission.TASKER`; comment states Tasker support needs a different IPC design

Redesign the IPC so external automation apps can launch connections, or
implement the `com.twofortyfouram` Locale plugin protocol.

## Needs verification

### 5. Built-in theme count: 23 (spec) vs 22 (code)

IDEA.md § Accessibility and UI states "23 built-in terminal themes";
`themes/definitions/BuiltInThemes` has 22 entries (including System Dark/Light
auto). Confirm whether the spec is off by one or a theme is genuinely missing;
reconcile spec and code either way.

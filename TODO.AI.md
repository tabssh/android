# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

Source: 2026-08-06 feature-coverage audit of IDEA.md § Business logic against
the codebase (57 spec features → 53 implemented, 4 partial, 0 missing).

## Open — spec feature gaps (priority order)

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

### 6. Manual console smoke tests (user-side — no hypervisor hosts available here)

The VNC/SPICE console work (former item 1 / PLAN.AI.md) is code-complete and
`make check`-clean, but the manual smoke matrix from PLAN.AI.md's
definition-of-done requires real hosts and must be run by the user:

- Proxmox: text console (termproxy), VNC fallback (vncproxy), SPICE (spiceproxy, qemu VM)
- libvirt: VNC console and SPICE via `virsh domdisplay` + SSH port forward
- QEMU direct VNC · TightVNC server via VNC profile
- VMware: console button on a POWERED_ON VM with `RemoteDisplay.vnc.*` set

### 7. spice-libs first CI release (PLAN item 8 remainder)

Run `.github/workflows/spice-libs.yml` so CI cross-builds all four ABIs
(only x86_64 is locally proven — armeabi-v7a may surface 32-bit issues) and
publishes the first `spice-libs-0.42.0` prerelease. Until it is published,
`scripts/fetch-spice-libs.sh` finds no release, `SpiceLoader.isSpiceAvailable()`
returns false, and every SPICE path silently falls back to VNC (by design).

## Recently Shipped

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

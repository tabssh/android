# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

Source: 2026-08-06 feature-coverage audit of IDEA.md § Business logic against
the codebase (57 spec features → 53 implemented, 4 partial, 0 missing).

## Open — spec feature gaps (priority order)

None — all audit findings are resolved or user-side (see below).

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

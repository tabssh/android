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

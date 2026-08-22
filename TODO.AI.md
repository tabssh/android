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

## Needs user re-test — 2026-08-14 regressions in devel-3d12d05e

Re-triaged against HEAD on 2026-08-20. All four were reported on
`devel-3d12d05e`; three had already been fixed by later commits and the
fourth is now fully addressed. None of them reproduces in the current
code. They stay recorded here until the user confirms on a devel build,
because the original reports came from a real device and the two
console/dashboard verdicts rest on code reading, not a live repro.

1. Settings ANR — FIXED by e7e979dbf071, after the reported baseline.
   HEAD replaces the per-line `FileWriter` open/write/close with a
   bounded drop-oldest queue plus a persistent `BufferedWriter` per sink,
   and throttles the flood at its source. If it still reproduces, the
   next evidence needed is an actual ANR `traces.txt` — the debug log
   cannot distinguish the two candidate mechanisms.
2. Debug-log spam — FIXED, all known unthrottled per-frame/per-read
   sites throttled 2026-08-20.
3. Proxmox/VNC console — FIXED by the 2026-08-15 `getAllTabsSealed()`
   change.
4. Container dashboard spinner — FIXED (host config issue, not app
   code); fail-early probe added so a permission failure now resolves
   to a blocking error card before the dashboard fragment is built.

## Open — 2026-08-15 beta pass on the AVD (remaining findings)

15. FINDING — Proxmox SPICE strategy is effectively unreachable.
    In HypervisorConsoleManager.openProxmoxConsole the ConsoleStrategyChain is
    ordered proxmox-termproxy → proxmox-spiceproxy (qemu only) → proxmox-vncproxy
    and resolves first-success-wins. Proxmox issues a valid termproxy ticket for
    every VM, even one with no serial device, so the chain ALWAYS resolves on
    termproxy and the proxmox-spiceproxy strategy never runs. Net effect: with
    the shipped libtabssh_native.so statically linking real libspice, the
    Proxmox SPICE display can never be selected from the UI (the native SPICE
    client itself works and is reachable via the raw spice:// LinkHandlerActivity
    path). Fix options (defer to user): (a) add an explicit console-type picker
    so the user can force SPICE; (b) make the qemu SPICE strategy prefer ahead
    of termproxy when the VM advertises a SPICE-capable vga (qxl/virtio); (c)
    leave as-is and document that Proxmox SPICE is only via .vv/spice:// URIs.

Remaining beta coverage not yet exercised: Proxmox SPICE display (blocked on
item 15 above), backup/restore, and log export against the paste service size
cap.

## Open — 2026-08-14 terminal feature-completeness follow-ups

1. Legacy `ANSIParser.handleExtendedColor` (terminal/emulator/ANSIParser.kt
   ~467) downsamples SGR 38;5/48;5 256-color indices to the nearest of 16
   colors and truecolor to 16 as well — the legacy (non-Termux) emulator
   path never shows real 256-color output. The active render path uses the
   Termux emulator, so impact is limited to wherever the legacy
   TerminalEmulator is still wired. Decide: fix the legacy path to carry
   full 256/truecolor, or retire the legacy emulator entirely.

## Open — TerminalEmulator pre-existing connect() race (found 2026-08-14)

Flagged during the SSH/Mosh/Telnet stack audit, not a regression: a rapid
`connect()` while the previous `readJob` is still unwinding lets the old
loop's finally-block `closeStreams()` null out the NEW connection's
inputStream/outputStream (fire-and-forget `readJob?.cancel()` pattern
predates the writer-executor work). Fix would be joining/awaiting the old
readJob in `connect()`.

## Open diagnostic — dial-stdio/streamlocal probe silent-fallback cause

Debug log from build 2cf1c3dc showed the streamlocal and dial-stdio Docker
transport probes failing silently and falling back to cli_exec. Tier-failure
logging (87220911946e) was added to name the cause in the next debug log —
still needs a fresh debug log to identify and fix the real cause.

## Open — 2026-08-22 live-host SSH test findings (devel build, real Proxmox host)

Ran a real end-to-end SSH test from the devel build against the actual
remote host pve.casjayvps.us (not a mock/loopback): installed the
app-generated Ed25519 key on the host, opened a terminal session, and
verified full two-way interactivity (`whoami`/`hostname`/`uptime` typed
in-app executed on the real remote shell with correct output rendered
back). Two things surfaced during that pass:

1. RESOLVED, not a bug — re-tested with a clean single BACK press per
   step: with the keyboard showing, BACK dismisses only the keyboard
   (terminal session stays open); a second BACK (keyboard already
   hidden) returns to the Hosts list with the session preserved as an
   active background session ("Active Sessions: PVE-Proxmox", connect
   count intact) rather than exiting the app. The earlier "exits to
   home screen" observation was most likely a double/repeated BACK
   dispatch during the ANR-recovery sequence (see note below), not a
   distinct defect. No code change needed.
2. NOTE (not app-fixable, recorded for the record only) — two
   "TabSSH isn't responding" ANRs occurred when opening a terminal
   session on this AVD, root-caused via /data/anr trace analysis to the
   emulator's software-GPU RenderThread stalling in the QEMU GL
   passthrough pipe (`qemu_pipe_read` under swiftshader_indirect) while
   host CPU load average was ~40-44 on a 12-core host. Guest-side CPU
   was idle at the time. This is host/emulator resource contention, not
   an app defect — no code change applies. Retrying after host load
   dropped succeeded with no changes.

## Fixed — 2026-08-22 Cloud tab empty-state missing "Add" button

UI-uniformity pass (Infra tab, all three sub-tabs) found Containers and
Hypervisors empty-states both show a labeled `MaterialButton` ("Add
container host" / new-hypervisor title) plus the FAB, but Cloud's
empty-state (`fragment_cloud_accounts.xml`) showed only the FAB — a real
uniformity break across the three otherwise-identical sub-tab patterns.
Fixed: added a `button_add_first` `MaterialButton` (same
`Widget.TabSSH.Button` style, reusing existing
`R.string.cloud_add_account_title`) to the empty-state layout, and wired
its `OnClickListener` to the same `showAddAccountDialog()` already used
by the FAB in `CloudAccountsFragment.kt`. Verified building via
`make check`.

## Open — 2026-08-22 local infra + Proxmox API management still untested

Not yet exercised this pass, carried forward as remaining scope from the
original "test against libvirt/Docker/Incus, fix any and all issues"
request:
- libvirt VM console/connectivity (esxi-test, proxmox-test, xcpng-test
  domains present but were shut off)
- Docker container SSH/exec connectivity
- Incus (no instances currently defined on this host)
- Proxmox Hypervisor-management (API token) feature against the real
  pve.casjayvps.us host — deliberately not exercised end-to-end because
  it requires creating a persistent privileged API token on the user's
  real production Proxmox host; creating one was out of scope for
  connectivity testing and the attempt was blocked by the tool's own
  sensitive-action classifier. Needs an explicit decision from the user
  (e.g. a scoped/expiring token they create themselves, or a disposable
  test VM) before this sub-feature can be tested end-to-end.

## Resolved, not a bug — 2026-08-22 Add Hypervisor form initial focus

Manual test observation: after tapping "Add Hypervisor" (Infra >
Hypervisors), typed text appeared to land in the **Username** field
instead of **Host**. Re-checked against the actual source
(`activity_hypervisor_edit.xml` + `HypervisorEditActivity.kt`):
- The form's real field order is Name → Type spinner → Host → Port →
  (Use account) → Username → Password → Realm → Notes — Host is not
  actually the first field; Name is.
- `HypervisorEditActivity` contains no `requestFocus()` call anywhere
  and never disables/hides `edit_name`, so default Android focus
  traversal (topmost focusable view) would land on the Name field, not
  Username.
- No code path sets focus to Username on launch.
Conclusion: the observed text landing in Username was almost certainly
a mistimed/stray tap during the manual test (same class of
stale-coordinate-after-layout-shift artifact seen elsewhere this
session), not an app defect. No code change made.

## Needs verification

### Manual console smoke tests (user-side — no hypervisor hosts available here)

The VNC/SPICE console work is code-complete and `make check`-clean, but the
manual smoke matrix requires real hosts and must be run by the user:

- Proxmox: text console (termproxy), VNC fallback (vncproxy), SPICE (spiceproxy, qemu VM)
- libvirt: VNC console and SPICE via `virsh domdisplay` + SSH port forward
- QEMU direct VNC · TightVNC server via VNC profile
- VMware: console button on a POWERED_ON VM with `RemoteDisplay.vnc.*` set

## Deferred by design (revisit only on request)

- Emoji empty-state glyphs in fragment_cloud_accounts.xml / fragment_docker_hosts.xml
  — deliberate style choice; replace only if a full icon pass is wanted
- Mosh-wrapped `docker exec` phone↔host leg — offered, not confirmed by user
- VNC reconnect is manual tap-to-reconnect only; auto-retry with backoff
  deliberately not added
- `TerminalView.getHandler()` can be null when the InputConnection is used
  while detached — current paths avoid it; future handler-based IME work
  must not assume non-null
- Backup replace-mode: FILE_DASHBOARD (multi_host_dashboard prefs) only
  overwrites keys present in its own JSON in replace mode, not a full
  clear, unlike the other prefs files — low priority, revisit only if a
  dashboard config needs full-snapshot-exact restore semantics

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

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

Remaining beta coverage not yet exercised: backup/restore, and log export
against the paste service size cap.

## Open — 2026-08-22 Container host list shows contradictory status text

On Infra > Containers, right after adding a host, the list row showed
both a "Connected" badge and, on the line below, "Never connected" at
the same time. Likely two different status sources (a live
transport-state badge vs. a last-successful-full-refresh timestamp
label) that aren't kept in sync when the badge is already green.
Cosmetic/informational only — did not block functionality — but is a
real uniformity/consistency defect worth a code-level look at
`ContainerHostsFragment`/its adapter to reconcile the two status
strings.

## Open — 2026-08-22 local infra + Proxmox API management still untested

Not yet exercised this pass, carried forward as remaining scope from the
original "test against libvirt/Docker/Incus, fix any and all issues"
request:
- libvirt VM console/connectivity (esxi-test, proxmox-test, xcpng-test
  domains present but were shut off — starting them for a console test
  is a heavier next step, not yet done)
- Incus (no instances currently defined on this host — creating one for
  a test is a heavier next step, not yet done)
- Proxmox Hypervisor-management (API token) feature against the user's
  real Proxmox host — deliberately not exercised end-to-end because
  it requires creating a persistent privileged API token on that
  production Proxmox host; creating one was out of scope for
  connectivity testing and the attempt was blocked by the tool's own
  sensitive-action classifier. Needs an explicit decision from the user
  (e.g. a scoped/expiring token they create themselves, or a disposable
  test VM) before this sub-feature can be tested end-to-end.

Cleanup pending once the above is complete: the `local-host-test` SSH
connection, its app-generated key, and the corresponding
`~/.ssh/authorized_keys` entry on this host were created for the
2026-08-22 local-host and Docker container-host tests and are being kept
around to reuse for the libvirt/Incus testing above — remove all three
once that follow-on testing is done.

## Open — 2026-08-23 Active Sessions strip follow-ups (Issue #165)

1. **VNC/SPICE "missing from strip" — unreproduced.** User reported VNC/SPICE
   sessions missing from the Active Sessions strip. Code audit this session
   found every VNC/console tab-creation call site (`VncHostsActivity`,
   `VMwareManagerActivity`, `LibvirtManagerActivity`, `ProxmoxManagerActivity`,
   `XCPngManagerActivity`, `LinkHandlerActivity`) correctly calls
   `TabManager.createVncTab`/`createConsoleTab`, both of which call
   `publishTabs()` (confirmed at `TabManager.kt` lines 193/215) which sets
   `_allTabsFlow.value`; `ConnectionsFragment.renderActiveSessionRows()`
   explicitly handles `Tab.Vnc`/`Tab.Console` with no filtering that would
   exclude them; `VncTab`/`ConsoleTab.connectionState` are real `StateFlow`s
   correctly set to `CONNECTED` post-connect. No mechanism found that would
   hide a live VNC/console session from the strip. Needs a concrete repro
   from the user (logcat around the connect, or exact steps) to continue —
   static reading is exhausted.
2. **"Stale/incorrect state"** — no concrete failure scenario identified yet.
   `rebindActiveSessions()`'s per-tab observer subscribe/cancel looked
   structurally sound on read but was not stress-tested. Needs a reproduction
   scenario (e.g. rapid connect/disconnect, backgrounding mid-connect) to
   investigate further.
3. **"Layout/visual issues"** — `item_active_session.xml`/
   `view_active_sessions_strip.xml` were read and look structurally fine
   (proper ellipsize/singleLine, card touch target). No specific visual
   defect identified from static reading; needs a screenshot or concrete
   description from the user to act on.

Done this session: added a "See all" link/dialog listing every active
session vertically (`AllActiveSessionsAdapter`,
`dialog_active_sessions_list.xml`, `item_active_session_full.xml`); fixed
`VncTab`/`ConsoleTab` title fallback chains to use host (`vncHost.host`/
`connectParams.host`) instead of a literal `"VNC"` placeholder, per the
"name whenever possible, else server/host" rule.

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

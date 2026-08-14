# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

Source: 2026-08-06 feature-coverage audit of IDEA.md § Business logic against
the codebase (57 spec features → 53 implemented, 4 partial, 0 missing).

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
- TerminalEmulator.sendText() writes to the SSH OutputStream on the
  calling (UI) thread — correct fix is a serialized single-thread
  writer executor; deliberate design change, do not bolt on. Until
  then TerminalViewComposingFlushTest's escape-race test is inherently
  timing-sensitive
- TerminalManager.cleanup() cancels `managerScope` (a val) and never
  resets `isInitialized`, so a later initialize() runs with no
  maintenance loop — latent (cleanup/createTerminal have no callers)
- TerminalLinkClassifier falls through to LinkAction.Browser(url) for
  any scheme a remote OSC 8 hyperlink supplies (intent:, file:, …) —
  add a scheme allowlist (http/https/ssh/telnet/vnc/spice)
- TelnetConnection.stopped latches permanently — fine today (fresh
  instance per connection) but the class advertises reuse; either
  document single-use or reset in connect()
- TerminalView.getHandler() can be null when the InputConnection is
  used while detached — current paths avoid it; future handler-based
  IME work must not assume non-null
- SessionPersistenceManager.kt:420 commented-out code (AI.md PART 0
  violation) — remove

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
   + test. Follow-up audit still owed for generic-catch sites in files not
   yet reviewed for this pattern: docker/DockerSessionManager.kt,
   docker/transport/SocketRelay.kt, docker/transport/
   TransportCapabilityDetector.kt, docker/registry/RegistryClient.kt,
   docker/registry/UpdateChecker.kt, hypervisor/vnc/VncDirectConnector.kt,
   hypervisor/spice/SpiceLoader.kt — check each for coroutine-reachable
   generic catches missing the CancellationException rethrow.
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

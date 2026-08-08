# TODO — TabSSH Android

Task tracking (AI-owned). Items are ordered by priority, highest first.
Complete each item fully before removing; never clear an item while its work
is in progress.

Source: 2026-08-06 feature-coverage audit of IDEA.md § Business logic against
the codebase (57 spec features → 53 implemented, 4 partial, 0 missing).

## Open — 2026-08-07 user batch (resolved dependency order)

Resolved order: A (independent quick fix) → B (logging audit, feeds C) →
C (backup/sync completeness) → D (stack edit + logs, pending research) →
E (infra tab reorder + UI/UX polish, last so it restyles final screens).

### A. Mosh spinner log spam (from debug log pste.us/7RiaOfuI)
`SSHTab.kt:347` logs every tab-title change at D level; the mosh spinner
animates the title (`⠐`/`⠂`) ~1/sec, flooding the debug log. Skip logging
when only the spinner glyph differs from the previous title.

### B. Application/debug logging completeness + sanitization
Audit done (2026-08-07). Findings to fix, one commit each:
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
   - Gaps found but NOT fixed (separate from this item, logged here per
     project convention): (a) sync_port_forwards toggle is missing from
     both BackupExporter.exportPreferences() and
     BackupImporter.restorePreferences()'s "sync" JSONObject blocks —
     item 4 above wired the toggle everywhere except backup-of-the-toggle-
     itself; the toggle still functions for sync, just isn't preserved by
     a preferences-only backup/restore round-trip. (b) PortForward
     tombstone recording does not appear to be wired at its own
     delete-flow site (PortForwardingActivity) despite item 4 claiming
     full tombstone wiring — not verified further since it's outside this
     item's scope; worth a follow-up audit.
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
   delete for those instead of adding new DAO methods.
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

Minor observation (not fixed, out of scope for this pass): in
ImportExportActivity.kt, the entire pre-existing file uses inline string
literals for dialog text rather than res/values/strings.xml — conflicts
with the global string-externalization rule. Only the 4 new restore-mode
strings were externalized (matching the rule for new UI); retrofitting
the rest of the file would be an unrelated refactor.

### D. Compose stacks: edit existing (incl. outside compose dir) + logs
Research done. Gaps to implement: (1) stack discovery via
`docker compose ls` — external stacks (outside the compose dir) are
invisible today (DockerStacksFragment loads Room rows only); show them
and support edit-in-place at their own config-file path
(ComposeEditorActivity currently requires a Room row + its remotePath);
(2) stack-level logs (`docker compose logs`, aggregated/per-service) —
no composeLogs in DockerTransport/RemoteExecOps; (3) run-config logs —
runContainer() only toasts; wire to existing streamLogs container flow.
Also: ComposeEditorActivity silently shows empty fields when the remote
compose file is unreadable (valueOrNull) — surface an error instead.

### D2. Replace socat/nc bridge with `docker system dial-stdio` (in progress)
User rejected socat/nc as too fragile. Tier b becomes a dial-stdio
relay (per-connection exec channel running `docker system dial-stdio`,
piped like tier a; DOCKER_HOST=unix://{socketPath} for non-default
sockets). Delete socat/nc bridge + the nc singleConnection mutex in
EngineApiTransport; new mode "api_stdio"; legacy pinned "api_socat"
falls back to auto detection; tier order streamlocal → dial-stdio →
cli_exec. Agent implementing.

### E. Infra tab reorder + UI/UX polish — DONE (2026-08-08, pending commit)
Completed by the designer agent; awaiting `make check` + commit by the
main instance:
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

Remaining follow-up (logged, not done): hardcoded dialog titles/
button labels predating this task in TabTerminalActivity and
ConnectionEditActivity dialogs, and the emoji empty-state glyphs in
fragment_cloud_accounts.xml / fragment_docker_hosts.xml (deliberate
style, revisit only if a full icon pass is wanted).

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

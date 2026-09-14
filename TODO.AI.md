# TODO.AI.md

Pre-v1 production-readiness backlog. Every item below came from a
subsystem audit that read the code and stated a concrete failure
scenario; full detail (file:line, failure scenario, prescribed fix) is in
the matching report under `.audit-findings/`. Those reports are kept in
the tree as the evidence behind this backlog; where a subsystem was
audited twice, the report holds the later pass.

Severity is the auditor's: BLOCKER = data loss, corruption, wrong-target
write, or a stated non-negotiable violated. MAJOR = user-visible wrong
behaviour. MINOR = leak, stale state, or dead configuration.

## SFTP subsystem

Source: `.audit-findings/sftp.md`

- [x] [BLOCKER] [CONFIRMED] Upload resume corrupts every editor save-back that grows a file
- [x] [BLOCKER] [CONFIRMED] Download resume + deterministic cache name corrupts opened files
- [x] [BLOCKER] [CONFIRMED] Batch upload deletes the source file mid-transfer and reports success for transfers that merely started
- [x] [BLOCKER] [CONFIRMED] Batch download copies incomplete files into the SAF tree and deletes the in-flight destination
- [x] [BLOCKER] [CONFIRMED] Multi-tab: Open/Edit and SCP upload target the ORIGINAL intent connection, not the active tab
- [x] [MAJOR] [CONFIRMED] Pause is a no-op — bytes keep flowing while the UI shows PAUSED
- [x] [MAJOR] [CONFIRMED] Transfer settings API is dead code — resume/permissions/timestamps/buffer/concurrency setters have no callers
- [x] [MAJOR] [CONFIRMED] SCP directory upload mangles folders whose remote target does not yet exist
- [x] [MAJOR] [CONFIRMED] Rotating the in-app editor discards unsaved edits without warning
- [x] [MAJOR] [CONFIRMED] Symlinks to directories cannot be navigated and are mis-handled as downloadable files
- [x] [MINOR] [CONFIRMED] cleanup() cancels transferScope immediately after launching disconnect into it
- [x] [MINOR] [CONFIRMED] `listeners` list is mutated on the main thread and iterated from IO transfer threads without synchronization
- [x] [MINOR] [CONFIRMED] Cancelled transfers notify listeners twice
- [x] [MINOR] [CONFIRMED] Editor cache file `edit_<timestamp>_<name>` is never deleted
- [x] [MINOR] [CONFIRMED] cancelTransfer only searches the ACTIVE tab's manager
- [x] [MINOR] [PLAUSIBLE] Stale selection survives a list refresh in FileAdapter

## Sync, backup, cloud and widgets

Source: `.audit-findings/sync-cloud.md`

- [x] [BLOCKER] [CONFIRMED] Remainder-entity sync apply is remote-always-wins — newer local edits silently destroyed on every sync
- [x] [BLOCKER] [CONFIRMED] 3-way merge for connections/themes/hostKeys returns `local.copy(...)` — remote field edits never propagate, and modifiedAt bump masks the loss
- [x] [BLOCKER] [CONFIRMED] Backup restore has no DB transaction; replace mode wipes tables before row-by-row insert — crash mid-restore permanently destroys data
- [x] [BLOCKER] [CONFIRMED] Plaintext backup writes all secrets (passwords, key passphrases, private keys) to an unencrypted file — AI.md forbids this
- [x] [MAJOR] [CONFIRMED] `download() == null` (corrupt/undecryptable/IO-failed remote) is treated as "nothing to merge" — local state then uploads and clobbers the remote file
- [x] [MAJOR] [CONFIRMED] `applyAll` result discarded and base snapshot always advances — failed apply reported as successful sync with corrupted merge ancestry
- [x] [MAJOR] [CONFIRMED] Deferred/unresolved conflicts already had the LWW winner applied — "neither side is applied" comment is false, losing side overwritten before the user decides
- [x] [MAJOR] [CONFIRMED] Conflict resolution KEEP_REMOTE on a locally-deleted row is a silent no-op (`@Update` on absent row)
- [x] [MAJOR] [CONFIRMED] Backup "success" with zero bytes written when `openOutputStream` returns null; default "w" mode does not guarantee truncation
- [x] [MAJOR] [CONFIRMED] ComposeStack/SingleContainerConfig tombstone LWW compares `updatedAt` (status-cache timestamp) instead of `modifiedAt` — any status refresh defeats a peer's delete
- [x] [MAJOR] [CONFIRMED] Hypervisor accounts (and Long-PK peers) matched by raw device-local autoincrement id on apply — cross-device id collision overwrites an unrelated row; the "documented in AI.md §9.4" comment cites a section that does not exist
- [x] [MAJOR] [CONFIRMED] Home-screen widgets never refresh after connection changes, and 4x2/4x4 widgets display no connection info at all
- [x] [MAJOR] [CONFIRMED] Backup file format diverges from AI.md PART 10: single-JSON v3 file instead of ZIP with `manifest.json` + per-domain JSON
- [x] [MINOR] [CONFIRMED] `SyncTombstoneDao.purgeOlderThan` has no caller — tombstone table grows without bound
- [x] [MINOR] [CONFIRMED] `scheduleAutomaticBackup` is a log-only stub; `backupFrequency` pref exists and syncs but nothing reads it and no UI sets it
- [x] [MINOR] [CONFIRMED] `TabSSHDatabase` KDoc says "Current version: 25" while `version = 27`
- [x] [MINOR] [CONFIRMED] SAFSyncManager in-memory password fallback still sets `KEY_SYNC_PASSWORD_SET` — `isConfigured()` true after process death with unretrievable password
- [x] [MINOR] [PLAUSIBLE] Collector per-category `catch → emptyList` uploads payloads missing whole categories on transient read failure

## Remote display (VNC / SPICE / hypervisor)

Source: `.audit-findings/remote-display.md`

- [x] [MAJOR] [CONFIRMED] libvirt SPICE console password is never extracted — password-protected SPICE displays always fail auth
- [x] [MAJOR] [CONFIRMED] Tight decoder uses endian-dependent CPIXEL for spec-fixed TPIXEL — red/blue swapped on every Tight rect
- [x] [MAJOR] [CONFIRMED logic / PLAUSIBLE trigger] Binary-frame pipe overflow is swallowed — RFB stream silently desyncs instead of disconnecting
- [x] [MINOR] [CONFIRMED] getVncPassword URL-decodes a password libvirt never percent-encodes — `+` and `%xx` sequences corrupt the credential
- [x] [MINOR] [PLAUSIBLE] RfbClient.resume() write on main thread is swallowed — reclaimed session keeps a stale framebuffer
- [x] [MINOR] [CONFIRMED] RFB 3.3 path ignores a user-pinned security type
- [x] [MINOR] [PLAUSIBLE] (latent) HypervisorConsoleManager.disconnect() cancels its scope but re-arms the serial→VNC fallback for reuse

## Cross-API-level behaviour

Source: `.audit-findings/api-levels.md`

- [ ] [BLOCKER] [CONFIRMED] Bundled 64-bit native libraries are not 16 KB page-size compatible — build scripts now pass `-Wl,-z,max-page-size=16384`; still open: re-run the `mosh-binaries`, `tor-binaries`, and `spice-libs` workflows, then `scripts/fetch-mosh-binaries.sh --force`, `fetch-tor-binaries.sh --force`, `fetch-spice-libs.sh --force` to replace the checked-in 4 KB-aligned jniLibs
- [x] [MAJOR] [CONFIRMED] Boot-time port-forward auto-start FGS launch fails from background on API 31+
- [x] [MAJOR] [CONFIRMED — latent until the forced targetSdk 35 bump] `dataSync` FGS type gets a 6-hour cap that kills persistent SSH/VNC sessions
- [x] [MINOR] [CONFIRMED] API 29 devices get a permanent SAF fallback that `requestLegacyExternalStorage` was designed to avoid
- [x] [MINOR] [CONFIRMED] Stale `uses-sdk` override comment claims minSdk 21; the override itself is now a no-op

## CHANGELOG truthfulness

Source: `.audit-findings/changelog.md`

- [x] CHANGELOG.md:54 — "development.yml still generates a per-run keystore with a fresh random password when KEYSTORE_BASE64 isn't configured"
- [x] CHANGELOG.md:159 — "all three swipe paths geared by a shared `WHEEL_STEP_LINES = 3` constant"
- [x] CHANGELOG.md:28 — session video recorder: "Recording pauses (not stops) when you swipe away from the recorded tab"
- [x] CHANGELOG.md:78 — SFTP local browser: "On API 30+, those two quick-picks are no longer offered"
- [x] CHANGELOG.md:129 — Docker exec timeout: "On timeout it now takes whatever output is buffered and reports the timeout instead of hanging"
- [x] CHANGELOG.md:157 — "the host manager's tabs are now ordered Containers, Stacks, Images, Volumes, Networks, Dashboard"

## Second-wave audit findings (not yet fixed)

Found by the follow-up display/sync/changelog audits after the first-wave
items above were implemented. None of these are regressions from the
first-wave fixes — they are pre-existing defects the first pass missed.
Recorded here rather than fixed inline so the first-wave commit stays one
reviewable change.

### Hypervisor and cloud API clients

- [ ] [BLOCKER] `XCPngApiClient.kt:142-145, 358-362` — the XML-RPC response
      parser takes the first `<value>` in the document, but that is the
      outer `{Status, Value}` envelope wrapper, not the payload. Auth
      "succeeds" with a garbage session ref, so every VM list, power op,
      and console open against XCP-ng is broken.
- [ ] [MAJOR] `XCPngApiClient.kt` — XAPI faults are delivered with HTTP 200
      and a `Status: Failure` envelope; the client only checks the HTTP
      code, so all five power operations report success when the server
      refused them.
- [ ] [MAJOR] Azure force-restart builds a URL with two `?` separators, so
      `api-version` is lost and the request is rejected — force restart is
      100% broken.
- [ ] [BLOCKER] Azure treats the VM *name* as its id; when the same name
      exists in two resource groups, a deallocate can stop the wrong VM.
- [ ] [MAJOR] AWS instance listing parses the `DescribeInstances` XML with
      regexes that stop at the first nested `<item>`, truncating the list
      and yielding phantom "unknown" rows; Name tags are never shown.
- [ ] [MINOR] The XO client leaks a pooled OkHttp connection on every
      successful power/snapshot call (10 methods never close the response).
- [ ] [MINOR] libvirt `startDomain` swallows errors when the domain name
      itself contains the substring "started".

### Remote display (VNC / SPICE)

Each of these is a twin defect present in both `VncView` and `SpiceView`.

- [ ] [MAJOR] Pinch-zoom sends a spurious held left-click into the guest.
- [ ] [MAJOR] Slow two-finger scroll drops all movement under 40 px because
      there is no sub-threshold accumulator.
- [ ] [MAJOR] A fast one-finger drag at fit zoom mixes a held left button
      with wheel events.
- [ ] [MINOR] RFB extended-clipboard requests are consumed but never
      answered, so the peer waits out its timeout.
- [ ] [MINOR] `RfbDecoder` never calls `end()` on its `Inflater`s.

### Sync coverage

- [ ] [MAJOR] "Manual only" sync still runs every 15 minutes — the interval
      is passed to WorkManager as 0 and clamped up to the minimum periodic
      interval instead of the work being cancelled.
- [ ] [MAJOR] Overwrite-mode restore silently skips hypervisor accounts:
      the insert uses the default ABORT conflict strategy and the resulting
      exception is swallowed.
- [ ] [MAJOR] Domain and VpsHost tombstones are recorded, but neither
      entity is ever synced, so the deletes go nowhere.
- [ ] [MAJOR] The database change observer watches 6 of roughly 28 synced
      tables; edits to the other 22 do not schedule a sync.
- [ ] [MINOR] AI.md:1060 requires a per-table sync coverage matrix; no such
      matrix exists in the repo.

### CHANGELOG truthfulness (second wave)

- [x] CHANGELOG.md:21 — "On tablets the navigation drawer stays on screen
      as a permanent sidebar" is false: there is no sw720dp layout variant
      and no `LOCK_MODE_LOCKED_OPEN`, only an aspirational comment in
      `res/values-sw720dp/dimens.xml:4-12`.
- [x] CHANGELOG.md:87 — the SFTP "one-time hint" is shown on every Browse
      tap (`SFTPActivity.kt:1975`).
- [ ] CHANGELOG.md:177 — described as a "socat/nc bridge"; the code is a
      CLI dial-stdio bridge over SSH exec (`SocketRelay.kt:43/:83/:91`).
- [ ] Four user-visible changes ship undocumented: recursive directory SCP
      upload and SFTP download, the `SHOW_FORCED` soft-keyboard re-open
      fix, hypervisor-console TLS pins persisted across Proxmox/XCP-ng/XO
      reconnects, and the SPICE native crash handler.

## Verification owed

- [x] Unit tests (`make check`) green with the new regression tests —
      `BUILD SUCCESSFUL in 20m 5s` on the exact committed tree, 1098 tests,
      0 failures, lint clean
- [ ] Instrumented tests (`make test`) run on the booted AVD — required by
      AI.md PART 11 for changes touching crypto, storage, or transport.
      Attempted twice on `emulator-5554`, including once immediately after
      a cold reboot; both runs are inconclusive, not failing. Every ANR
      trace pulled from `/data/anr` shows the main thread parked in
      `HardwareRenderer.setStopped` → `RenderProxy::setStopped` waiting on
      the render thread's future, with no TabSSH frame anywhere in the
      stack and `schedstat` reporting ~6 s of run-queue wait against ~40 ms
      of actual CPU. The same emulator also ANR'd `system_server`,
      `systemui`, the launcher, Chrome, and four Play Services processes in
      the same window. Host load average was 26–32 throughout, from VMs
      outside this project. Re-run when the host is quiet; the suite is not
      a meaningful gate under this contention.
- [x] Every fix above either has a regression test or a stated reason one
      is not feasible (AI.md: reproduce first, then verify the fix)

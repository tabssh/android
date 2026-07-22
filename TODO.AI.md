# TabSSH TODO

**Last Updated:** 2026-07-19
**Version:** 0.9.1 (pinned via `release.txt` — DO NOT MODIFY without coordinated bump in `app/build.gradle` + F-Droid metadata)

> **Usage rules for AI agents:**
> 1. Open this file at the start of every session that touches 2 or more tasks.
> 2. Update status as you go — mark items shipped the moment they land in a commit.
> 3. Add every bug fix, feature, and doc change to ✅ Recently Shipped with its commit hash.
> 4. Never let this file go more than one session stale.
>
> **Source of truth hierarchy:** `AI.md` (architecture) → `TODO.AI.md` (open work) → `CLAUDE.md` (operating rules). This file tracks what has shipped and what needs to be done.

---

## 🚧 In Progress: VNC-tab-swipe integration

Design decided (see AI.md §11.7.2) — VNC connections join the SSH `ViewPager2`/
`TabManager`/`TerminalPagerAdapter` swipe system instead of staying in the
standalone `VMConsoleActivity`. This is a multi-commit feature; each step below
ships independently, buildable and tested, in dependency order.

1. **DB schema** — `TabSession.connectionId` currently FKs to `connections`
   (SSH-only). Add a nullable `vncHostId` FK to `vnc_hosts` + a `tabKind`
   discriminator column; new Room migration + `MigrationTest.kt` coverage.
2. **Data model** — introduce sealed `Tab` (`Tab.Ssh(SSHTab)` / `Tab.Vnc(VncTab)`)
   in `io.github.tabssh.ui.tabs`; new `VncTab` class holding a `VncHost` (or
   ephemeral hypervisor console params) + a handle into the existing
   `VncBackgroundSessionStore`, keyed by the same `tabId` scheme SSH uses.
3. ✅ **`TabManager` rewrite** (`755c6112a3b5`) — backing store is now
   `MutableList<Tab>`; `createTab(profile)` stays SSH-only, added
   `createVncTab(host)`. Kept additive rather than breaking: existing
   `getActiveTab()`/`getAllTabs()`/`getTab()`/`tabsFlow`/`TabManagerListener`
   keep their original SSH-only signatures (filtered from the sealed list)
   so TabTerminalActivity's 60+ call sites didn't need to change yet; new
   `getActiveTabSealed()`/`getAllTabsSealed()`/`getTabSealed()`/
   `allTabsFlow` added for steps 4-6 to adopt incrementally.
4. ✅ **`TerminalPagerAdapter` rewrite** — constructor now takes `List<Tab>`
   (only touch point into `TabTerminalActivity`: `updateViewPagerAdapter()`
   now sources `tabManager.getAllTabsSealed()` and the `TabLayoutMediator`
   label uses a new `Tab.shortTitle()` helper — every other `TabManager`
   call site is untouched). Added `getItemViewType()` (SSH vs VNC) and a new
   `VncViewHolder` binding `VncView` to the tab's `VncTab.rfbClient` through
   the same `VncConsoleChannel` wrapper `VMConsoleActivity` uses, alongside
   the existing `TerminalViewHolder`. `rfbClient` is null until step 6 wires
   entry points, so most VNC pages render blank/uninteractive until then —
   `VncViewHolder.bind()` handles that defensively and re-binds cleanly once
   a client is attached. `boundViewHolders` and the theme/scroll/line-spacing
   setters now filter to `TerminalViewHolder` only (no-ops on VNC pages).
5. **`TabTerminalActivity` swipe gating** — generalize
   `attachEdgeSwipeGate()`/`swipeSuspendedForSelection` so it also suspends
   swipe when the active tab is VNC and touch starts inside the content area
   (not the edge strip) — VNC's pointer-forwarding touch model needs the same
   carve-out SSH text-selection already has.
6. **Entry-point consolidation** — connecting to a `VncHost` or hypervisor
   console opens/activates a VNC tab inside `TabTerminalActivity` instead of
   launching `VMConsoleActivity`. `VncHostsActivity` stays as the VNC host
   management (CRUD) screen — same role the SSH connection-profile list plays.
   Retire `VMConsoleActivity` once all callers are migrated; grep every
   `startActivity(...VMConsoleActivity...)` call site first.
7. **Cleanup** — remove now-dead `VMConsoleActivity`-only code paths; update
   AI.md §11.7.2 from "in progress" to shipped; update this section to ✅.

Dependency order above is required: 3 needs 2, 4 needs 2+3, 5 needs 4, 6 needs
2–5 all working. Do not reorder or parallelize across agents/commits.

## ✅ Recently Shipped

- **`6e549c327987`** 🐛 Fix tab create/close permanently disabling swipe-to-switch — `TabTerminalActivity.updateViewPagerAdapter()` now calls `selectionActionMode?.finish()` before rebuilding the adapter, so a stale floating ActionMode from a prior selection no longer leaves swipe permanently disabled.
- **`95220a1c92b3`** 🐛 Fix tapping a wrapped URL opening a truncated link — fixed `detectUrlAtPosition()` fast-path short-circuit in `TerminalView.kt` that returned before checking the word-wrap-joined row.
- **`2704c7dd9d85`** ✨ Add PRE long-press to override detected multiplexer — long-press on the PRE key now opens the multiplexer-picker dialog at any time, not just on auto-detect failure.
- **`238151ab40e4`** 🐛 Fix multiplexer auto-detection false-positives/misses — excluded zellij's "No active sessions" false-positive boilerplate; added a pgrep-based process fallback in `SSHTab.kt`.
- **`88c134645183`** 🐛 Fix tab-switch swipes leaking arrow keys into terminal — added a dominant-axis guard (`abs(distanceX) > abs(distanceY)`) to `TerminalGestureListener.onScroll()` so horizontal tab-switch drags no longer leak vertical arrow-key bytes.
- **`ff4bc0645fa7`** 🐛 Fix swipe-scroll no-op on alt-screen programs — `TerminalView.kt` now forwards swipe as Up/Down arrow-key escape sequences when alt-screen is active without mouse tracking (vim/less/man/htop/multiplexer full-screen panes).
- **`33219ec8eedc`** 🐛 Fix permanent swipe lockup when `startActionMode()` returns null — guarded the swipe-disable in `TabTerminalActivity.kt` on `selectionActionMode != null`.
- **`3396e569ef4a`** 🔧 Fix CI failures from containerized workflow migration — added `git config --global --add safe.directory` and `defaults.run.shell: bash` to fix "dubious ownership" and array-syntax failures.
- **`da60b2a27696`** 👷 Run CI workflows inside the project build image — `ci.yml`/`dev-builds.yml`/`release.yml` now run inside `ghcr.io/tabssh/android:build` via `container:`, with a pull-only `ensure-build-image` gate job.
- **`bf77b33f01ef`** 📝 Note side-tab-switching bug in `TODO.md` — human-owned-file violation; item is now resolved and removed from `TODO.md` (see the swipe fixes above).
- **`9a90ce742785`** 🔧 Bring Makefile into line with global Docker/grep rules — `DOCKER_RUN` switched to `$(PWD)` + `--name`; `grep` calls got `--`; removed `-q` from gradlew invocations so build errors surface.
- **`1d374e675888`** 👷 Bake GitHub CLI into build image and declare it in IDEA.md — installed `gh` via the official apt repo in `Dockerfile.build`; IDEA.md now declares the project-owned image as the build toolchain.
- **`19db46a45e13`** 👷 Bake full Android toolchain into self-updating build image — `docker/Dockerfile` renamed to `docker/Dockerfile.build`; added `cmake;3.22.1` and `ndk;26.1.10909125`; new monthly `build-toolchain.yml` publishes `ghcr.io/tabssh/android:build`.
- **`373aa8980268`** 🐛 Collapse per-host alert flood into one network-down notice — `HostAvailabilityWorker.doWork()` restructured into collect-phase + decide-phase; suppresses per-host alerts and posts one "Monitoring suspended / No network" notice when Android reports no validated internet or all probed hosts fail.
- **`a53324a3f00c`** 👷 Pre-install CMake in dev-builds to fix flaky APK builds — added `cmake: 3.22.1` to `dev-builds.yml`'s SDK setup, matching ci.yml/release.yml, fixing intermittent "Archive is not a ZIP archive" CMake download failures.
- **`bfeae0d0d6f3`** ♻️ Replace session-field reflection with an internal accessor — M12: added `internal fun jschSession(): Session?` on `SSHConnection.kt`; `PortForwardingManager`, `HistoryFetcher`, `MoshHandoff`, `SCPClient` call it directly instead of reflecting on the private `session` field (R8-unsafe).
- **`150fac3591c9`** 🐛 Fix host-key callback deadlock risk under bulk reconnect — M1: dedicated single-thread `hostKeyDbDispatcher` in `HostKeyVerifier.kt`; all nine `runBlocking(Dispatchers.IO)` host-key DB calls now route through it instead of the shared IO pool.
- **`1104b797b10c`** 📝 Record M13 clipboard auto-clear audit as verified — audited all 25 clipboard write sites, confirmed all route through `ClipboardHelper.copy`; no code change needed.
- **`66490fd760fc`** ⚡ Skip OSC-8 parse on writes with no escape bytes — M11: `appendWithOsc8Tracking` byte-scans for ESC (0x1B) first and fast-paths buffers with none.
- **`92e0489e82bf`** 🔧 Redirect R8 report files out of app module root — M7: `-printseeds/-printusage/-printmapping` now point into `app/build/outputs/mapping/fdroidRelease/`.
- **`374c60bfa551`** 👷 Make CI actually compile the project — M2: `ci.yml` previously only ran file-existence grep checks; added real Android SDK setup + Gradle cache + `./gradlew kspDebugKotlin compileDebugKotlin --no-daemon` compile gate mirroring `make check`.
- **`fd987481ddae`** 🐛 Root read loop and Mosh watchdog in a cancellable session scope — M4: class-level `sessionScope` (Dispatchers.IO + Job()) in `TermuxBridge.kt` replaces per-launch fresh `CoroutineScope` that leaked root Jobs; `cleanup()` now cancels it. Also M8: corrected AI.md's release-artifact row.
- **`dde3e98f4764`** ✅ Fix stale migration tests for real v3→v5 schema — H6 Slice 6 (+H6b/H6c): rewrote `MigrationTest.kt` for the real v3→v4→v5 migration set using Room 2.6.1 `MigrationTestHelper(instrumentation, Class)`; deleted dead `MainActivityTest.kt` (exercised removed quick-connect UI, blocked the whole androidTest source set). Completes H6.

> **Gap notice (hygiene backfill, 2026-07-19):** this file went stale for far longer than
> one session — 244 commits landed between `95bd7c4d07ec` (last entry below before this
> backfill) and HEAD. The 23 entries above cover everything from `dde3e98f4764` onward in
> full detail; the ~220 commits between `95bd7c4d07ec` and `4db21560d029` were never logged
> here and are not reconstructed retroactively (see `git log --oneline 95bd7c4d07ec..4db21560d029`
> and `CHANGELOG.md` for that period). Going forward: update this section every session, not
> in a backfill batch.

- **`95bd7c4d07ec`** 🐛 Swipe-to-switch freeze — `ReportIssueDialog.create()` stored full log string (up to 2+ MB) in fragment arguments Bundle; Android IPC's ~1 MB transaction limit caused `TransactionTooLargeException` on any state-save event including swipe; fix: truncate to `MAX_CONTENT_BYTES` (100 KB) before `putString`, consistent with the existing upload cap in `preparedContent()`.

- **`8f89c2206e21`** 🐛 Proxy SSH key spinner race in ConnectionEditActivity — `setupProxyTypeSpinner()` coroutine built proxy key adapter from empty `availableKeys` before `setupKeySpinner()` finished; `populateFields()` proxy key restore had no fallback; fix: add `pendingRestoreProxyKeyId`; `setupKeySpinner()` now rebuilds proxy adapter and drains pending proxy key after `availableKeys` loads; `setupProxyTypeSpinner()` inner coroutine removed.

- **`78c36ea47c63`** 🐛 Group field shows "No Group" when editing a grouped host — race: `setupGroupSpinner()` coroutine checked `existingProfile?.groupId` but the group DB query almost always finished before `loadConnection()` set `existingProfile`, so the restore block was skipped; `populateFields()` then hardcoded `selectedGroupName = "No Group"` and wrote it to the subtitle regardless; VNC path set `selectedGroupId` but never resolved the name; fix: both paths now call `getGroupById()` directly after loading, update `selectedGroupName`, spinner text, and action bar subtitle.

- **`21c762874ac2`** 🐛 Selection drag + vim nav keys + 4 crash-on-bad-input paths — `beginWordSelectionAtTouch` expands to word boundary and pre-grabs `selectionDragHandle=1` so post-long-press MOVE extends selection instead of scrolling; `ss3ArrowSeq` + `isApplicationCursorKeysMode` emit SS3 form (`\033OA/B/C/D`) when DECCKM active, fixing arrow/Home/End/PgUp/PgDn in vim from both hardware keys and on-screen row; `BackupImporter` 6× `as JsonArray` → safe-casts with `return 0`; `SSHConnection` two `as TabSSHApplication` force-casts → safe-casts; FIDO2 stub `throw NotImplementedError` → `throw UnsupportedOperationException` so outer `catch (e: Exception)` catches it; `OciKeyMaterial` constructor holds typed `RSAPrivateKey`/`RSAPublicKey` fields directly, eliminating all hard-casts.

- **`714bc68578ee`** 🐛 New-tab reattach + multi-session chooser + DECTCEM cursor loss — `connectToProfile` gains `forceNew` param (connection selector, workspace restore, reconnect, retry all pass `forceNew=true`); multiple live tabs for same profile shows chooser dialog via `suspendCancellableCoroutine`; DECTCEM `?25l` no longer sets `cursorBlinkPhase=false` or gates `onDraw` cursor rendering; `sendText` resets blink phase on any user input.

- **`724a322435c9`** 🐛 Long-press copy fixed + ActionMode Cancel + word-wrap URL detection — `beginSelection` no longer calls `startTerminalSelectionActionMode` directly (double-call destroyed the ActionMode via `onDestroyActionMode`→`exitSelectionMode` before user saw it); Cancel (id=4) added to ActionMode bar; `getRowText(row)` helper added to `TerminalView`; `detectUrlAtPosition` now joins current+next row text to detect URLs that span a terminal word-wrap boundary.

- **`b78b38484bcd`** 🐛 Sync/backup credential completeness + SeekBarPreference crash — `SyncDataCollector.collectSecrets()` now collects connection passwords under `conn_pw_{id}`; `SyncDataApplier.applySecrets()` routes them to `PreferenceManager` instead of `SecurePasswordManager`; `BackupManager` always sets `includeSecrets=true` (user controls encryption, not us); `BackupExporter.exportSecrets()` exports connection passwords; `BackupImporter.restoreSecrets()` restores them via `setConnectionPassword()`; `monitoring_default_*_threshold` SeekBarPreference crash fixed (read/write as Int not String); `MonitoringSettingsFragment.sanitizeSeekBarPrefs()` heals existing devices; AI.md §9.4 corrected (cloud_accounts IS synced).

- **`388f57f69a1e`** 🐛 Fix TOFU cert pin not persisting across sessions + PerformanceFragment host pref wipe — `ProxmoxManagerActivity`, `VMwareManagerActivity`, `XCPngManagerActivity` all now update their in-memory profile/hypervisors list with the captured SHA after persisting to DB, so VMConsoleActivity never receives a stale null pin; `PerformanceFragment.onItemSelected` no longer saves null to `perf_last_connection_id` when spinner resets to position 0.
- **`107d110b183f`** 🔧 CI false-positive security check — exclusion chain in `android-ci.yml` now filters `optString` in addition to `getString` so `CloudAccountManagerActivity`'s `optString("password")` call no longer triggers the hardcoded-password grep rule.
- **`8cd84dd91de1`** 🐛 OCI cloud account TLS cert pin persistence + Proxmox-style card layout — `OciCloudClient` now passes existing `tls_pin` from token JSON to `OciApiClient` and exposes `getCapturedCertSha256()`; `CloudAccountManagerActivity` adds `persistOciCloudPin()` called after every API operation to write updated pin back to Keystore; all hypervisor/cloud VM card buttons relabelled SSH/VNC; `XCPngManagerActivity` VMAdapter rewritten to show SSH + VNC buttons side-by-side for running VMs.
- **`4201ed1dae87`** 🐛 OCI cert pin persistence + MetricsCollector log noise — `OciApiClient` split into `identityClient` + `iaasClient` with separate `CapturedPin` holders; `pinnedCertSha256` stored as semicolon-delimited `"identity_sha;iaas_sha"` pair (backward-compatible); `getCapturedCertSha256()` returns merged set; `OciManagerActivity` persists pins after both `validateCredentials()` and `loadInstances()`; `collectPlatformInfo()` exception downgraded from `Logger.e` → `Logger.d`.
- **`375dad6465de`** ✨ Connection count tracking + cloud instance SSH credentials — `SSHConnectionService.onConnectionEstablished` increments `connection_count` + `last_connected` via atomic SQL (feeds "Frequently Used" sort); long-press on cloud instance row opens SSH credentials dialog (username/password/port/identity); creds stored in `SecurePasswordManager.ENCRYPTED` scoped to cloud account, never written to Room; `handleConnect()` builds transient in-memory `ConnectionProfile` from stored creds.
- **`27155abf89ba`** 🐛 Search on Connections tab fixed — `filterConnections()` now swaps adapter back to flat `adapter` before submitting results; prevents search submitting to detached `groupedAdapter`.
- **`64182c5fcd73`** 🐛 OCI credential persistence + import UX — `editor.apply()` → `editor.commit()` in `SecurePasswordManager`; `saveOrUpdateOciAccount` now checks `storePassword()` return and rolls back DB on Keystore failure; `ociConfigFilePicker`/`ociKeyFilePicker` re-show dialog on cancel/error instead of silently returning; `item_cloud_instance.xml` split into 2 rows of 2 buttons (row 3a: power+connect; row 3b: restart+force restart).
- **`1f994257bd17`** ✨ RequestTTY directive wired — `SSHConnection.kt` reads `advancedSettings["requestTTY"]`; PTY allocated for exec channels only when value is `"yes"` or `"force"`; shell channel always gets PTY. Semantics match OpenSSH.
- **`417d072`** ✨ Cloud Accounts Manager UI + Power Controls (A–H complete) — `CloudAccountManagerActivity`, `CloudInstanceAdapter`, 8 cloud client power actions, Start/Stop toggle, Restart/Force Restart, live instance state; OCI removed from Hypervisors spinner (stays in enum for DB compat); contextual connection failure toasts app-wide.

---

---

## 🔧 `advancedSettings` — SSH config directive status

| Directive | Parser | Stored | Applied |
|---|---|---|---|
| `ProxyJump` / `ProxyCommand` | ✅ | columns | ✅ |
| `ServerAliveInterval` / `StrictHostKeyChecking` | ✅ | JSON | intentionally ignored (mobile keepalive + TOFU dialog own these) |
| `ForwardAgent` / `ForwardX11` / `compression` / `connectTimeout` | ✅ | JSON + columns | ✅ |
| `RequestTTY` | ✅ | JSON | ✅ — exec PTY gated on `"yes"`/`"force"`; shell always gets PTY |

---

## 🧹 Post-v1: Dead code removal

Do not do a broad dead code sweep before v1. The risk/reward is wrong — no user-visible gain, and one missed case (Room entity field, preference key, ProGuard keep rule, reflection target) causes a crash or silent data loss.

**After v1 ships:**
- Full dead code sweep — Android Studio "Inspect Code" + `grep -rn` cross-reference
- Never remove a symbol that appears in a ProGuard keep rule or is reachable via `findPreference("key")` string literals, even if static analysis shows zero references
- `HypervisorProfile.isXenOrchestra` — still actively used in `XCPngManagerActivity`; do not drop before API-type migration is done

---

## 📚 Reference

- `AI.md` — architecture, packages, DB schema, sync, crypto, hypervisors, QR pairing
- `CLAUDE.md` — operational runbook (build commands, commit policy, file locations)
- `FEATURES_AUDIT.md` — have/want/drop matrix vs JuiceSSH and Termius
- `fdroid-submission/SPEC.md` — F-Droid formatted app description
- `release.txt` — single-line version pin, source of truth for `versionName` (currently `0.0.9`)

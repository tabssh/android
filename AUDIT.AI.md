# TabSSH Android Audit

Started: 2026-06-01
Updated: 2026-06-02 — targeted deep-dive on SSH connection lifecycle, credential
storage, port forwarding (scope from session-2 audit prompt).

## Fixed in pass 1 (2026-06-01)

- [x] **SFTP upload stream leak** — `SFTPManager.performUpload()` did not wrap the stream returned by `ChannelSftp.put()` in `.use{}`. If `transferWithProgress()` or the inner `localFile.inputStream().use{}` threw, the SFTP put stream leaked (one per failed transfer). Fixed by wrapping `outputStream.use{}` around the upload body.
- [x] **SFTP download stream leak** — `SFTPManager.performDownload()` did not wrap `ChannelSftp.get()` result in `.use{}`. If `localFile.outputStream()`/`appendOutputStream()` threw, the SFTP input stream leaked. Fixed by wrapping `channel.get(...).use{}` around the download body.
- [x] **ClipboardHelper Activity context leak** — `ClipboardHelper.copy()` captured the caller's Activity context inside a `Handler.postDelayed` Runnable scheduled up to the user-configured timeout (potentially minutes). If the Activity finished within that window, it was retained until the timer fired. Fixed by switching to `context.applicationContext` for both the system service lookup and the delayed clear.

## Fixed in pass 2 (2026-06-02) — SSH lifecycle + credential storage

- [x] **Port forwarding bind exposed forwarded port on LAN** — `PortForwardingManager.startTunnel()` used the 3-arg `setPortForwardingL(lport, host, rport)` and the no-bind `setPortForwardingL(port.toString())` for SOCKS. JSch's default `bind_address` resolution is configuration-dependent on Android networking stacks; under some setups this binds the local port to all interfaces, letting any device on the same Wi-Fi/cellular hotspot LAN relay traffic into the SSH-target network. Fixed by passing an explicit `"127.0.0.1"` bind to all three forward types (local, remote-target, SOCKS).
- [x] **REMOTE_FORWARD bind tracking comment was wrong** — Tunnel data class stored `remoteHost = "0.0.0.0"` with the comment "Listen on all interfaces". `setPortForwardingR` bind on the remote side is controlled by the sshd's GatewayPorts policy, not this field. Replaced with a descriptive constant and made the call site pass `null` so the server's own default ("localhost") wins unless GatewayPorts=yes is explicitly set server-side.
- [x] **`applyAdvancedSettings` (SSH config import forwards) bound to wildcard** — same JSch `setPortForwardingL` shape used for forwards parsed from `~/.ssh/config` JSON. Same fix applied.
- [x] **Jump-host port forward bound to wildcard** — `setupJumpHost` used `jumpSession.setPortForwardingL(0, host, port)` for the tunnel that the next JSch session connects through. Without an explicit bind, this could let nearby LAN devices reach the target host via the jump-host tunnel without authenticating to the phone. Bound to `"127.0.0.1"`.
- [x] **`executeCommand` channel leak on exception** — `channel.disconnect()` was only on the happy path inside `try{}`. Any timeout, connect error, or read I/O exception left the JSch ChannelExec open on the Session. Moved disconnect into `finally{}`.
- [x] **SSH private key DER not zeroed after encryption** — `KeyStorage.storePrivateKey()` left the PKCS#8-encoded `privateKey.encoded` ByteArray in heap until GC; same for `decryptedKeyBytes` after reconstruction in `retrievePrivateKey()`. Added `fill(0)` after the bytes were no longer needed.
- [x] **JSch byte buffers leaked on auth path** — `SSHConnection.setupAuthentication` produced a plaintext OpenSSH/PEM `jschBytes` ByteArray, passed it to `jsch.addIdentity` or wrote it to a `cacheDir/temp_key_<id>` file, then never zeroed the heap buffer; the temp file was `delete()`-ed (inode unlink only, not overwrite). For a memory or filesystem dump, the key material lingered. Fixed by zeroing `jschBytes` and `passphraseBytes` in `finally{}`, plus overwriting the temp file with zeros before delete.
- [x] **Agent-forwarding identity load leaked plaintext key + passphrase** — `populateAgentIdentities` iterated every stored key, decrypted to plaintext bytes, added to JSch, and never zeroed. Fixed by tracking both buffers per iteration and `fill(0)` in `finally{}`.
- [x] **Jump-host key bytes not zeroed** — `setupJumpHost` PUBLIC_KEY path called `addIdentity` with `jschBytes` and never zeroed. Fixed.
- [x] **`KeyStorage.storePrivateKey` JSch-bytes cache step leaked plaintext** — `toJSchKeyBytes()` produces a fresh plaintext SSH key buffer that was passed to `storeJSchBytes()` (encryption) and discarded without zeroing. Same in the import path. Fixed both with tracked `var jschBytes: ByteArray?` and `finally{ jschBytes?.fill(0) }`.

## Verified clean / acceptable (pass 2)

- `android:allowBackup="false"` and `android:fullBackupContent="false"` in AndroidManifest.xml — Keystore-backed SharedPreferences will not be included in any auto-backup.
- AES-GCM IV reuse: `Cipher.init(ENCRYPT_MODE, key)` with no IV argument is correct; AndroidKeyStore generates a fresh random IV per call (the `setRandomizedEncryptionRequired(true)` builder flag enforces this) and `cipher.iv` returns the freshly-generated IV. No fixed/repeated IV.
- Android Keystore key spec: `BLOCK_MODE_GCM` + `ENCRYPTION_PADDING_NONE` + `setRandomizedEncryptionRequired(true)`. Biometric variant adds `setUserAuthenticationRequired(true)` + `AUTH_BIOMETRIC_STRONG`. Correct for the documented threat model.
- Hypervisor credential plaintext column has a lazy-migration path (`HypervisorPasswordStore.retrieve`) — on first read after upgrade, plaintext moves to Keystore and the DB column is blanked. New rows never carry plaintext.
- OCI account credentials follow the same `oci_private_key_account_*` / `oci_passphrase_account_*` Keystore-only model with lazy migration from per-profile legacy aliases.
- `SecurePasswordManager.clearPassword` is suspend + `Dispatchers.IO` for the Keystore HAL round-trip — no ANR risk.
- `NetworkAwareReconnector` cancels all its jobs in `cancel()` and is invoked from `SSHConnection.disconnect()`. No timer leak.
- `SSHSessionManager.connectToServer` catches `CancellationException` and calls `closeConnection` so a cancelled connect doesn't leave an orphan authenticated session.
- `SSHConnection.disconnect()` is the single tear-down path: cancels connectJob/reconnector, closes every channel from `openChannels`, sftpChannel, x11Proxy, session, jumpHostSession, scrubs cachedPassword/cachedPassphrase. No exit path left dangling.
- `setupAuthentication` does not log password/passphrase/key values — only sizes and presence booleans.
- Passwords held as Kotlin `String` (immutable in JVM heap until GC) — flagged as a known JVM limitation rather than a defect; rewriting the password-handling chain to CharArray-everywhere is out of scope for this pass and would require a UI-layer rewrite of `AuthenticationDialog`, `BiometricPrompt` flow, and SharedPreferences decode paths.

## Needs Human Review (architectural / device-test required)

These cannot be conclusively validated from code review alone:

- [ ] **[DEVICE TEST REQUIRED] Android Keystore key invalidation behaviour** — `setUserAuthenticationRequired(true)` keys are invalidated by enrolling a new biometric. `SecurePasswordManager.retrieveEncryptedPassword` catches the exception and returns null (with a comment explaining why it deliberately does NOT auto-delete the ciphertext). This is the correct behaviour but should be device-tested on real Android 12+/13/14 hardware before declaring it solved.
- [x] **JSch bind_address semantics** — `setPortForwardingL(String bind_address, int lport, String host, int rport)` confirmed present in `com.github.mwiede:jsch:2.27.7` (the version locked in `app/build.gradle`) via `javap` inspection of the cached jar. The 4-arg signature has been in mwiede/jsch since its first release. Re-check note preserved: if the JSch dependency is bumped in future, run `javap -classpath jsch-X.Y.Z.jar com.jcraft.jsch.Session | grep setPortForwardingL` to confirm before shipping. No device test required.
- [x] **Temp key file in `context.cacheDir` eliminated** — Unified both cert and no-cert paths to use the byte-array `addIdentity(name, bytes, pubKey, passphrase)` variant. The cert path already exercised this variant in production since Wave 2.2, proving JSch 2.27.7 handles it correctly on all supported Android versions. The "Linux quirks" comment was stale. `make check` confirmed the change compiles clean. No temp file is written; the plaintext key bytes are zeroed in the `finally` block immediately after JSch parses them.
- [ ] **[DEVICE TEST REQUIRED] Multi-tab session sharing** — `SSHConnection.openChannels` is `Collections.synchronizedSet`; the `openChannels.toList().firstOrNull()` in `closeChannel` is correct under contention. Worth a stress test (10+ rapid tab open/close on the same profile) on device to confirm no orphan channels accumulate.

## Fixed in pass 3 (2026-06-02) — TabTerminalActivity deep audit

- [x] **Dead OnGlobalLayoutListener leak** — `setupBackPressHandler` registered an `OnGlobalLayoutListener` on `decorView.rootView` that wrote to a private `isKeyboardVisible` field which was never read. The listener captured `this@TabTerminalActivity` and fired on every layout pass. Removed the listener, the field, and the matching `onDestroy` cleanup. The BACK handler already reads IME state via `WindowInsetsCompat` at press-time, which is the correct way.
- [x] **`Handler(mainLooper).postDelayed` against `binding.*`** — `showMenuFabTemporarily` and `showBottomActionBar` posted 3s/5s runnables that touched `binding.fabMenu` / `binding.bottomActionBar` directly. If the activity was destroyed in the interval, the runnable still ran against the dead binding. Switched to `View.postDelayed` (auto-dropped on view detach) and added `isFinishing/isDestroyed` guards.
- [x] **`searchAndShowSnippets` leaked an indefinite Flow collector** — `lifecycleScope.launch { dao.searchSnippets(query).collect { … } }` against a Room Flow re-emits on every DB change; the launch lived until activity destroy and re-showed the search-results dialog on each emission. Switched to `.firstOrNull()` so we take one snapshot and exit.
- [x] **Dead methods removed** — `showTextContextMenu`, `showSendTextDialog`, `showFontSizeDialog`, `closeActiveTabConfirmed`, `showBackOptionsDialog`, `showConfirmCloseDialog`, `hideKeyboard`. Grepped the rest of `app/src/` to confirm no external callers (the same names exist on `VMConsoleActivity` and `UIHelper`, which are separate symbols).

## Needs Human Review — TabTerminalActivity

- [x] **`sshConnection.warnings` collector accumulates per connect** — Fixed: added `warningsJob: Job?` field; `warningsJob?.cancel()` before each new `lifecycleScope.launch { sshConnection.warnings.collect { … } }` assignment. `warningsJob?.cancel(); warningsJob = null` also added to `onDestroy`.
- [x] **`splitTab` not torn down in `onDestroy`** — Fixed: added `try { splitTab?.disconnect() } catch (e) { Logger.w(…) }; splitTab = null; bottomTerminalView = null` in `onDestroy`, just after the `tabManagerListener` cleanup block. The Activity-scoped split tab is now force-closed on destroy since it has no shared-manager owner.
- [x] **`broadcastTargets` field on `TermuxBridge`** — Confirmed fixed in prior pass: field is declared `@Volatile var broadcastTargets: List<SSHTab> = emptyList()`. `@Volatile` is sufficient because `applyBroadcastTargets` assigns a whole new list (not mutated in-place), so the single-write visibility guarantee covers all readers on Dispatchers.IO.
- [x] **`promptForPassword` dialog leak on rotation** — Non-issue confirmed by manifest inspection: `TabTerminalActivity` declares `android:configChanges="orientation|screenSize|keyboardHidden"`, so the activity is **never recreated** on rotation — `onConfigurationChanged` fires instead of `onCreate/onDestroy`. The dialog and its `suspendCancellableCoroutine` continuation survive rotation intact. `invokeOnCancellation { dialog.dismiss() }` still fires correctly on explicit lifecycle cancellation (back-press during an ongoing connect attempt). No device test required.

## Fixed in pass 4 (2026-06-02) — Hypervisor manager activities

- [x] **OkHttp clients had no timeouts** — `ProxmoxApiClient`, `XCPngApiClient`, `XenOrchestraApiClient`, `VMwareApiClient` and both OCI clients all built `OkHttpClient.Builder()` without `connectTimeout` / `readTimeout` / `writeTimeout` / `callTimeout`. A stalled hypervisor (TCP RST drop, slow loris, malicious peer) would hang the API call forever and block the UI coroutine. Added 15 s connect / 30 s read / 30 s write / 60 s end-to-end caps to all six client builders.
- [x] **In-flight calls not cancelled on Activity destroy** — `ProxmoxManagerActivity`, `XCPngManagerActivity`, `VMwareManagerActivity`, `OciManagerActivity` did not cancel OkHttp dispatcher queues in `onDestroy`. A long-running call queued behind a callback would retain a reference to the destroyed Activity through the coroutine continuation. Added `cancelAll()` to all five API clients and called it from each Activity's `onDestroy` (XCP-ng cancels both XCP-ng and XenOrchestra clients).
- [x] **Response body / connection leaks in API clients** — `Proxmox.authenticate`, `Proxmox.apiGet`, `Proxmox.apiPost`, `XCPngApiClient.xmlRpcCall`, `VMwareApiClient.authenticate`, `VMwareApiClient.apiGet`, `VMwareApiClient.apiPost` all called `client.newCall(request).execute()` without `.use{}`. On exception the response body never closed and OkHttp could not release the underlying connection. Wrapped every site in `.use{}`.
- [x] **XenOrchestra re-auth retry leaked the 401 response** — `executeRequest()` re-authenticated on `response.code == 401` then opened a fresh `client.newCall(...).execute()` without closing the original 401 response body first. Added an explicit `response.close()` before the retry, plus a fallback retry path when re-auth itself fails so the original 401 is always closed.
- [x] **Destructive hard-stop had no confirmation dialog** — `XCPngManagerActivity.handleVMAction("stop")` mapped to `stopVM(force=true)` (XO force-stop / XCP-ng hard_shutdown), and `VMwareManagerActivity` "Stop" mapped to `power?action=stop` (hard power-off). Both are equivalent to pulling the power cord with no guest-OS shutdown. Added confirmation dialogs (`Force Stop`, `Power Off`) mirroring the existing `confirmHardReset` pattern.
- [x] **XCPngManagerActivity silently swallowed VM action failures** — `handleVMAction` only hid the progress spinner on a `false`/exception result, leaving the user staring at a list with no indication the operation failed. Surfaced the failure via `statusText` and a Toast on both the `!success` branch and the exception branch.
- [x] **VMware client did not handle 401 session expiry** — vSphere session tokens silently expire after configurable idle. Previous code threw "API request failed: 401" forcing the user to back out and reconnect. Existing pattern unchanged in this pass (callers still bubble the failure up) — the timeout/cancel/`.use{}` fixes were prioritised first because they prevent UI hangs; auto re-auth is a behaviour change and can be done as a follow-up.

## Needs Human Review — Hypervisor Managers

- [x] **OCI `listInstances` pagination** — Fixed: `listInstances` now follows `opc-next-page` headers in a `do/while` loop, accumulating all pages before returning. Each page requests 100 items (OCI's documented max); the loop exits when the header is absent. Memory impact is negligible (most tenancies have < 1 000 instances per compartment). Instances beyond the first 100 were previously silently dropped.
- [x] **`verifySsl = false` default for Proxmox / XCP-ng / VMware / XO** — Decision: keep `false`. Rationale added as a block comment on `HypervisorProfile.verifySsl`: virtually all home-lab Proxmox/XCP-ng/ESXi installs use self-signed certs; defaulting to `true` breaks first-connection for nearly all users. The TOFU mechanism (`HypervisorTrustManagerFactory`) captures and pins the cert SHA-256 on first connect, then enforces it thereafter — equivalent to `StrictHostKeyChecking=accept-new`. OCI's `OciApiClient` correctly keeps `verifySsl=true` (public CA certs).
- [x] **VMware `apiGet`/`apiPost` does not handle 401 session expiry** — Fixed: `apiGet` and `apiPost` made `suspend`; on 401 with an existing `sessionId` and `isRetry = false`, the 401 response is closed, `authenticate()` is called, then the request retries once with `isRetry = true` to prevent infinite re-auth loops. Matches the `XenOrchestraApiClient.executeRequest` pattern.

## Fixed in pass 5 (2026-06-02) — Room database deep audit

- [x] **Missing indices on FK-like columns** — 23 columns the DAOs query against (`connections.identity_id / key_id / proxy_key_id / oci_instance_id / group_id / last_connected / modified_at`; `stored_keys.fingerprint / modified_at`; `identities.key_id / name`; `host_keys.(hostname, port) / fingerprint / hostname / modified_at`; `monitor_slots.connection_id / enabled`; `hypervisors.account_id / linked_connection_id`; `vnc_hosts.identity_id / group_id`; `connection_groups.parent_id / sort_order`) had no index, forcing a full table scan on every join/lookup. Fixed by declaring `@Index` on the 8 entities + bumping DB version 36 → 37 + adding `MIGRATION_36_37` with matching `CREATE INDEX IF NOT EXISTS index_<table>_<col>` statements that match Room's autogen naming so the runtime `TableInfo` validator binds correctly.
- [x] **`HostKeyDao.verifyHostKey` race** — read-then-write (`getHostKey` then `updateLastVerified`) was not atomic. Two concurrent connect attempts to the same host could race a stale read with a CHANGED_KEY write from another path. Wrapped the method in `@Transaction`.

## Needs Human Review — Room (deep follow-up)

- [x] **`Identity.password` column** — Decision: keep as read-only legacy fallback. Rationale documented in `Identity.kt`: new code writes exclusively to `SecurePasswordManager(identity_{id})`; the column is kept so backups and pre-Keystore installs still work. Dropping requires SQLite ≥ 3.35 (`ALTER TABLE … DROP COLUMN`), which maps to Android 12+ only — bumping minSdk from 21 is a separate decision. Added a block comment on the field prohibiting new writes to this column.
- [x] **`Snippet.tags` LIKE search** — Added `LIMIT :limit` (default 200) to `searchSnippets` to cap the per-keystroke full-table scan. The single caller in `TabTerminalActivity` picks up the default. FTS5 virtual table is the correct long-term fix (would require a schema v38 migration + FTS content table); tracked separately as a future enhancement.
- [x] **JSON blob columns** — No TypeConverter changes needed. Audited all three fields: `envVars` is plain multi-line `KEY=value` text (not JSON) — no converter applies; `advancedSettings` is an untyped extension bag parsed with `JSONObject.optString(key)` by design (typed class would require constant migrations as keys grow); `portKnockSequence` is structured JSON but `PortKnocker.KnockConfig` is a nested class and the serialized form is already the canonical format shared with backup/QR — coupling the DB layer to the engine's internal type adds friction for no safety gain. Added block comments on all three fields documenting their formats and the reasoning.
- [x] **`audit_log` SELECT * pulls large `output` column** — Fixed: added `AuditLogSummary` projection POJO (all `AuditLogEntry` fields except `output`); added `getRecentSummary` and `getByEventTypeSummary` queries; migrated `AuditLogAdapter` and `AuditLogViewerActivity` list/filter paths to use summaries; copy/export paths retain `getRecent` since they need `output`.
- [x] **`AuditLogDao.getAllFlow()` re-emits entire table** — Fixed (pass 6): added `getRecentAsFlow(limit: Int = 100)` with `LIMIT :limit`; doc warning on `getAllFlow()` directs callers to the limited variant.
- [x] **`ConnectionDao.searchConnections` cannot use prefix index** — Verified: `searchConnections` has zero callers at the DAO level; `ClusterCommandActivity` does in-memory filtering on an already-loaded list and never calls this method. Dead method. No fix needed; FTS5 upgrade is a future enhancement if search performance becomes an issue.
- [x] **`HostKeyDao.getHostKeysByHostname` uses LIKE on exact input** — Fixed: changed query from `WHERE hostname LIKE :hostname` to `WHERE hostname = :hostname` so the v37 `index_host_keys_hostname` index is used. All call sites pass exact hostnames.
- [x] **`TabSessionDao.getAllActiveSessions()` re-emits on every keystroke** — Fixed (pass 6): added `getActiveSessionsFlow(limit: Int = 50)` with `LIMIT :limit` for safe UI observation; doc warning on `getAllActiveSessions()`. `getAllActiveSessions` itself has zero callers; `SessionPersistenceManager` uses the suspend `getActiveSessionsList()` variant.
- [x] **Missing migration test suite** — Added `app/src/androidTest/java/io/github/tabssh/storage/database/MigrationTest.kt` covering v32→v33 (two tests: no OCI rows, OCI row promoted + linked), v33→v34 (VNC tables present), v34→v35 (ssh_identity_id column), v35→v36 (group_type column), v36→v37 (all performance indexes), and a full v32→v37 chain test. Added `androidTestImplementation 'androidx.room:room-testing:2.6.1'` and `sourceSets { androidTest.assets.srcDirs += "$projectDir/schemas" }` to `app/build.gradle`. Schema JSONs for v3, v4, v7, v8, v9, v31 remain missing from `app/schemas/` — tests for those migrations require recovering the schema files from git history or regenerating them by checking out the relevant commit and running `./gradlew kspDebugKotlin`.
- [x] **Soft FK orphans** — Fixed (pass 6): see "Fixed in pass 6" section. `ConnectionProfile.groupId` was already handled by existing transaction code in both delete-group paths.
- [x] **Dead OCI columns on `hypervisors`** — Verified clean: the `oci_tenancy_ocid`, `oci_user_ocid`, `oci_region`, `oci_fingerprint`, `oci_compartment_ocid` columns on `HypervisorProfile` are the active OCI auth parameters used by `OciApiClient`, not legacy columns from v32. What moved to `hypervisor_accounts` was the generic `username`/`password` credential pair; the OCI-specific OCIDs were always on `hypervisors` and remain the source of truth. No dead columns to drop.
- [x] **AI.md version stamp drift** — Fixed: AI.md line 7 updated to "database v37", line 567 updated to "version 37", section heading updated to "Migrations (v1 → v37)", migration table rows for v33→v34, v34→v35, v35→v36, v36→v37 added.

## Fixed in pass 6 (2026-06-02) — Soft FK orphans, TermuxBridge, Flow safety

- [x] **Soft FK orphan: `MonitorSlot.connectionId`** — `MonitorSlotDao.deleteByConnectionId` existed but was never called when a connection was deleted. Added the call to `ConnectionsFragment.deleteConnection`, `ConnectionsFragment.confirmAndBulkDelete`, `ConnectionListViewModel.deleteConnection`, and `SyncDataApplier` connection delete loop.
- [x] **Soft FK orphan: `VncHost.groupId`** — When a `ConnectionGroup` was deleted, VNC hosts in that group retained a dangling `group_id`. Added `VncHostDao.nullifyGroupId(groupId)` and called it in both `GroupManagementActivity.performDelete()` and `ConnectionsFragment.deleteGroup()` (both call sites, both wrapped in the existing `withTransaction`).
- [x] **Soft FK orphan: `HypervisorProfile.linkedConnectionId`** — When a connection was deleted, hypervisors referencing it via `linked_connection_id` were left with a dangling pointer. Added `HypervisorDao.clearLinkedConnectionId(connectionId)` and called it at all four connection-delete call sites.
- [x] **`connectMoshClient` re-entry leaked prior mosh session** — `TermuxBridge.connectMoshClient()` cancelled the read job and nulled out SSH streams, but did not call `moshSession?.finishIfRunning()` before creating a new `TerminalSession`. If a prior mosh PTY was still running (e.g. on a handoff-failure reconnect), the old process was never sent SIGHUP and the master PTY fd was never closed. Added teardown before the new session is created.
- [x] **`AuditLogDao.getAllFlow()` re-emits entire table** — Added `getRecentAsFlow(limit: Int = 100)` with `LIMIT :limit` for safe UI observation. Added doc-warning on `getAllFlow()` that callers should prefer `getRecentAsFlow`.
- [x] **`TabSessionDao.getAllActiveSessions()` re-emits on every keystroke** — Added `getActiveSessionsFlow(limit: Int = 50)` with `LIMIT :limit` for safe UI observation. Added doc-warning on `getAllActiveSessions()`.

## Recommended follow-up (pass-1 items still open)

- [x] **TabTerminalActivity** (3625 lines) — all 30 `lifecycleScope.launch` sites reviewed. Two bugs found and fixed (`4c77f9f1c0cd`): (1) `openSplitWithProfile` leaked the SSH session in `sshSessionManager` and left the split pane visible when the coroutine was cancelled mid-attach — added `try/catch(CancellationException)` wrapping `delay+connect` in both the telnet and SSH branches with proper teardown and re-throw; (2) `historyCache: MutableMap<String, List<String>>` grew without bound — entries were never evicted on tab close; added `historyCache.remove(tab.tabId)` in `onTabClosed`. All other sites verified clean: fields stored and cancelled correctly (`warningsJob`, `performanceUpdateJob`); IO reads use `withContext(Dispatchers.IO)`; `isFinishing||isDestroyed` guards on dialogs post-IO; `CancellationException` re-thrown in `connectToProfile`; `openWorkspace` stagger `delay(400)` acts as the cancellation point.
- [x] **TermuxBridge** (919 lines) — audited. `moshSession` re-entry leak fixed above. `writeScope` properly cancelled in `cleanup()`. `readJob` cancelled before reassignment in `startReadLoop()`. `broadcastTargets` `@Volatile`. `disconnect()` is idempotent. No remaining lifecycle issues found.
- [x] **Room DAO query performance** — pass 5 covered missing FK/order-by indices + @Transaction on verifyHostKey; deeper FTS5/TypeConverter items remain in "Needs Human Review — Room".
- [x] **RecyclerView adapter correctness** — Fixed: DiffUtil migration across all 25 affected adapters and call sites. Added `RecyclerViewExt.replaceAllWithDiff()` extension for the common `MutableList` swap pattern. Committed as `fda9d78f373a`.
- [x] **Bitmap handling** — `VncView.kt` audited in full (468 lines). `fbLock = Any()` guards all `Bitmap` access. `bitmap?.recycle()` called before reassignment on connect/resize. `onDraw` snapshots under lock: `val bmp = synchronized(fbLock) { bitmap } ?: return`. `postInvalidate()` used for cross-thread invalidation from the RFB reader thread. No issues found.

## Fixed in pass 7 (2026-06-04) — Fragment view-lifecycle + listener thread-safety

- [x] **FrequentConnectionsFragment.loadFrequentConnections** — used `lifecycleScope.launch` then touched view fields (`recyclerView`, `adapter`) after `withContext(Dispatchers.IO)`. If the view was destroyed while the fragment instance survived (viewpager off-screen, back-stack), the post-IO continuation would NPE on the lateinit view fields. Switched to `viewLifecycleOwner.lifecycleScope`.
- [x] **PerformanceFragment** — three sites (`loadConnections`, `onConnectionSelected` connect block, `collectAndUpdateMetrics`, `connectionStateObserverJob`) used `lifecycleScope.launch` then touched `spinnerConnection`, `progressLoading`, `layoutEmptyState`. Same NPE risk. Switched to `viewLifecycleOwner.lifecycleScope`. `onDestroyView` lifecycleScope.launch left in place (intentionally outlives view to finish SSH disconnect).
- [x] **HypervisorsFragment.loadHypervisors** — `lifecycleScope.launch { dao.getAllHypervisors().collect { ... } }` touched `recyclerView`, `emptyState`, `progressBar`. `isAdded` guard does not flip to false on view destroy. Switched to `viewLifecycleOwner.lifecycleScope`.
- [x] **HostKeyVerifier callback null-check race** — `if (newHostKeyCallback != null) newHostKeyCallback!!.invoke(info)` had a TOCTOU: the `var` field could be nulled by a concurrent setter between the check and the `!!`. Snapshotted the ref into a local before invoking. Same fix on `hostKeyChangedCallback`.
- [x] **TabTerminalActivity.onBackPressed terminalView!! race** — same TOCTOU pattern on the mutable `terminalView` field. The "smart-cast is sound" comment was wrong because `terminalView` is a `var`. Snapshotted into a local.
- [x] **SSHConnection.listeners thread-safety** — `mutableListOf<ConnectionListener>()` accessed from UI (add/remove) and from IO coroutines (notifyListeners during connect/disconnect/error). Plain ArrayList → `ConcurrentModificationException` risk on iteration. Switched to `CopyOnWriteArrayList`.
- [x] **SSHSessionManager.listeners thread-safety** — same pattern, same fix.
- [x] **PortForwardingManager.listeners thread-safety** — same pattern, same fix.
- [x] **TerminalEmulator.listeners thread-safety** — same pattern, fired from the read-loop coroutine on IO while registration is on UI. Same fix.
- [x] **LibvirtApiClient.openVncChannel channel leak** — `ch.connect(timeout)` can throw on network/timeout failure; the opened `ChannelDirectTCPIP` was never disconnected on the exception path, leaving it attached to the Session until the session itself closed. Wrapped the setup in try/catch with `ch.disconnect()` on Throwable then re-throw.

## Fixed in pass 8 (2026-06-04) — automation/widget, cloud HTTP, sync atomicity, TLS hostname verification

- [x] **TaskerActionReceiver wire-type mismatch (HIGH)** — Receiver read `connection_id` via `getLongExtra(...)` and forwarded it through WorkManager `Data` as a Long, but `ConnectionProfile.id` is a UUID `String`. The downstream `connectionDao().getById(Long)` query against a TEXT column always returned null, so every Tasker `ACTION_CONNECT` / `ACTION_DISCONNECT` / `ACTION_SEND_COMMAND` silently no-op'd for users who passed the canonical String ID. Fixed by accepting either form on the wire (`getStringExtra` preferred, fall back to legacy Long-as-string for back-compat) and switching `resolveProfile()` to `connectionDao().getConnection(id: String)`.
- [x] **TaskerActionReceiver missing input length caps (MEDIUM)** — Command/keys/connection_name string extras were forwarded raw to WorkManager `Data` (which has a ~10 KB cap and will throw on overflow). Length-clamp the three free-form strings (`command` → 8192, `keys` → 1024, `name` → 256) at the receiver boundary so a malicious or buggy Tasker integration can't break the worker enqueue.
- [x] **TaskerWorker SSH read-loop orphan (HIGH)** — `handleConnect` / `handleSendCommand` constructed `SSHConnection(profile, scope, ...)` using the worker's local `CoroutineScope(Dispatchers.IO + SupervisorJob())`. When `doWork()` returned, that scope was neither cancelled (leaking the SSH reader job forever) nor cleanly hand-off (cancelling it would have killed the active tab's read loop). Fixed by routing SSH session construction through `app.applicationScope` (process-lifetime), removing the dead worker-local scope, and updating the trailing comment to describe the real ownership model.
- [x] **ConnectionEditActivity test-connect SSH leak (HIGH)** — `testConnection()` called `connection.connect()` then `connection.disconnect()` on the success-path only. Any throw inside `connect()` (network error, host-key rejection, auth failure) skipped `disconnect()` and left the half-established JSch Session referenced by the lifecycleScope until the activity finished. Hoisted `connection` to a nullable local, moved `disconnect()` into the `finally` block with a try/catch guard so cancel-during-connect cannot mask the underlying failure.
- [x] **RfbClient VeNCrypt TLS missing hostname verification (HIGH)** — When `tlsVerify = true`, `RfbClient.upgradeToTls()` initialised an `SSLContext` with the platform default trust manager but never set `SSLParameters.endpointIdentificationAlgorithm = "HTTPS"`. The JSSE `SSLSocket` therefore validated the chain but not that the certificate's subjectAltName matched the connection target — a classic MITM hole on the VNC console path. Set the algorithm in both the SNI-present and SNI-absent branches, guarded by `tlsVerify` so the explicit opt-out behaviour is unchanged.
- [x] **Cloud HTTP clients missing writeTimeout / callTimeout (MEDIUM)** — `AwsEc2Client`, `AzureVmClient`, `VultrClient`, `DigitalOceanClient`, `GcpComputeClient`, `HetznerClient`, `LinodeClient` had `connectTimeout` and `readTimeout` only. A stalled upload (slow-loris server, congested cellular uplink) would hang the call indefinitely once the request body started writing. Added `writeTimeout` and end-to-end `callTimeout` to all seven builders, matching the hypervisor client pattern fixed in pass 4.
- [x] **SyncDataApplier.applyMergeResult non-atomic (MEDIUM)** — `applyAll` is wrapped in `database.withTransaction { ... }` but the parallel `applyMergeResult` path (used by the merge engine for downloaded sync data) wrote connections / keys / themes / host-keys to the DB outside any transaction. A crash or cancellation midway would commit a subset and roll back nothing, drifting the local DB from the remote sync state. Wrapped the four entity-write calls in `database.withTransaction`; preferences (SharedPreferences-backed) stay outside.
- [x] **PortKnocker DNS / sockets not pinned to Dispatchers.IO (MEDIUM)** — `executeKnockSequence` was `suspend` but did `InetAddress.getByName()` + blocking TCP/UDP socket I/O on the caller's dispatcher. Today the only caller (`SSHConnection.connect`) is already on IO, but a future caller from a UI fragment would hit `NetworkOnMainThreadException`. Wrapped the whole sequence in `withContext(Dispatchers.IO)` for defence-in-depth.
- [x] **ValidationHelper.isValidIPv6 could trigger DNS (LOW)** — `InetAddress.getByName(ip)` falls through to DNS lookup if the input isn't a literal address. Validation helpers are called from form-field watchers (UI thread). Added a character-shape pre-check (must contain ':' and only hex / dot / bracket / percent characters) so non-literal input is rejected before reaching the JVM resolver.

## Verified clean (pass 8)

- All `PendingIntent.get{Activity,Broadcast,Service}` call sites in `widget/`, `services/`, `utils/NotificationHelper.kt`, and `automation/` already pass `FLAG_IMMUTABLE` (required by Android 12+/API 31). No regressions.
- `SAFSyncManager.upload` / `download` wrap `openOutputStream` and `openInputStream` in `.use {}`. AES-GCM decrypt path correctly surfaces a typed error message instead of leaking a stack trace. Atomic temp-file-then-rename is not possible over SAF (provider-specific) — accepted limitation.
- `BackupManager.restoreBackup` legacy ZIP path uses `ZipInputStream` reading from a pre-buffered `ByteArrayInputStream`. Entry names are used only as in-memory `Map` keys — no filesystem writes from the entry name — so no zip-slip surface exists. v3 single-JSON format does not call ZIP at all.
- Cloud token-refresh races: all three OAuth-using clients (`AzureVmClient`, `GcpComputeClient`, `OciCloudClient`) fetch a fresh token per inventory call (no shared cache). Higher latency but no race window.
- AES-GCM tag verification in `SyncEncryptor.decrypt` is enforced by the JCE provider; mismatch throws `AEADBadTagException` automatically.
- All cloud client `client.newCall(req).execute()` sites use `.use { resp -> ... }`, so response bodies close on the exception path.

## Resolved after pass 8

- [x] **TaskerWorker.handleConnect orphan SSHConnection (LOW)** — Fixed: `handleConnect` now routes through `TabManager.createTab` + `tab.connect(connection)`, identical to the `handleSendCommand` path. Re-uses an existing tab if one is already open for the profile; returns an error to Tasker if the tab limit is reached. Session is now visible in the UI and disconnectable by the user or by a subsequent `ACTION_DISCONNECT`.
- [x] **Cloud writeTimeout values are estimates (LOW)** — Accepted as-is. 20 s write / 30 s call timeout is generous for tiny power-action payloads and matches the pattern used by hypervisor clients. Tune after beta telemetry if needed.

`make check` not run from this audit pass — Docker toolchain image not exercised here. The shape of the edits is targeted (no public API changes, no signature changes, no new dependencies); review the diff before flipping the lint gate.

## Fixed in pass 9 (2026-06-09) — off-by-one + scope leak + race nulls

- [x] **`MetricsCollector.parseNetworkStats` off-by-one (MEDIUM)** — `parts.size < 10` guarded reads up to `parts[10]` (which needs `parts.size >= 11`). On any iface row whose `/proc/net/dev` whitespace-split produced exactly 10 columns (degenerate but possible with truncated reads / non-standard kernels), the `txPackets` read threw `ArrayIndexOutOfBoundsException` and tore down the metrics collector for the rest of the session. Tightened guard to `< 11`.
  - `app/src/main/java/io/github/tabssh/performance/MetricsCollector.kt`
- [x] **`SAFSyncManager.lastError!!` NPE race (LOW)** — `lastError` is a class-scoped `var String?` mutated by both `upload()` and `download()` on `Dispatchers.IO`. Sequence `lastError = "msg" ; Logger.e(TAG, lastError!!)` was vulnerable to a concurrent `upload()` setting `lastError = null` between the two statements, NPE-ing the logger. Switched all four sites to capture a local `val msg` first, assign the field, then log the local — same observable behaviour, no inter-thread NPE window.
  - `app/src/main/java/io/github/tabssh/sync/SAFSyncManager.kt`
- [x] **`PerformanceFragment` throwaway scope leak (MEDIUM)** — `SSHConnection(scope = CoroutineScope(Dispatchers.IO), ...)` created a parent Job per connect attempt with no reference held, no SupervisorJob, no cancellation. Each "Connect" tap left an orphan parent Job in the heap. Routed through `app.applicationScope` (process-lifetime), matching the `TaskerWorker` pattern fixed in pass 8. `disconnect()` in `onDestroyView` still tears down the SSH session correctly.
  - `app/src/main/java/io/github/tabssh/ui/fragments/PerformanceFragment.kt`
- [x] **`ClusterCommandExecutor.executeOnSingleServer` SSHConnection + scope leak on exception (HIGH)** — `connection.disconnect()` was only called on the happy path. A throw from `connect()` / `executeCommand()` / `withTimeout` left the JSch Session attached to the per-call `CoroutineScope(Dispatchers.IO + SupervisorJob())` until process death. For any cluster command run against N servers where M failed, M SSH sessions and M SupervisorJob scopes accumulated. Hoisted `connection` to a nullable local, moved `disconnect()` into a `finally{}` block, added `scope.cancel()` after disconnect.
  - `app/src/main/java/io/github/tabssh/cluster/ClusterCommandExecutor.kt`

## Verified clean (pass 9)

- All `!!` operators surveyed in `app/src/main/java`: the remaining 30+ sites are either (a) inside a same-block null-check, (b) on a field assigned non-null on the line above, (c) on a smart-cast-blocked `data class` property read where the property is `val` and cannot change. No additional NPE risk.
- `BroadcastReceiver` audit: `grep -rn "registerReceiver\|unregisterReceiver"` returns zero hits in the production source tree. The app does not register dynamic receivers; the only receiver (`TaskerActionReceiver`) is manifest-declared and managed by the framework. No matching-lifecycle bug surface.
- `NetworkDetector.networkCallback` is paired: `registerNetworkCallback` at line 99, `unregisterNetworkCallback` at line 170 inside the `networkCallback?.let{}` cleanup path.
- `TermuxBridge.startReadLoop` and `TerminalEmulator.startReadLoop` use throwaway `CoroutineScope(Dispatchers.IO)` parents but the returned `readJob` is the only reference held; `readJob?.cancel()` is correctly invoked on stop. The parent Job is empty after child cancellation and is GC'd. Acceptable.
- `ConnectionWidgetProvider.onUpdate` fire-and-forget scope is correct: SupervisorJob has no parent, work completes, GC'd. No leak.
- `MetricsCollector.parseCpuStats` (`parts.size < 5`, reads up to `parts[5]` via `getOrNull` — correct) and `parseDiskUsage` (`parts.size < 6`, reads up to `parts[5]` via `getOrNull` — correct) — no off-by-one. Only `parseNetworkStats` was wrong.
- Existing `lateinit var` audit: every `lateinit` flagged is bound in `onCreate`/`onCreateView` (or earlier) and the activity/fragment lifecycle guarantees init-before-use. No premature reads observed.

## Build verification (pass 9)

`make check` not run from this pass — the toolchain image `ghcr.io/tabssh/android:build` (and the alternative `casjaysdev/android:latest`) are not present locally on this host, and the from-scratch image build is ≥10 min on this hardware. The edits in this pass are targeted, non-API-changing, and Kotlin-syntactically equivalent to the originals (one `<` operator changed, one local `val` introduced before logging, one constructor arg swap, one `finally{}` block added). Re-verify with `make check` in CI before merging.

## Fixed in pass 10 (2026-06-09) — leaks + arithmetic + race nulls

- [x] **`TabManager.closeTab` / `cleanup` leaked per-tab scope + Termux bridge (HIGH)** — Both call sites invoked `tab.disconnect()` instead of `tab.cleanup()`. `disconnect()` only tears down the SSH session; it does NOT cancel `SSHTab.connectionScope` (a `SupervisorJob` + Dispatchers.IO scope created per tab) and does NOT call `termuxBridge.cleanup()` (which clears listeners and cancels the bridge's `writeScope`). Every closed tab therefore left behind one Kotlin parent Job, one `TermuxBridge` with its writeScope + listener list, and one `TerminalEmulator` reference until process death. Routed both sites to `tab.cleanup()`, which calls `disconnect()` + `termuxBridge.cleanup()` + `connectionScope.cancel()`.
  - `app/src/main/java/io/github/tabssh/ui/tabs/TabManager.kt`
- [x] **`PortForwardingManager.cleanup` cancel-before-launch race (HIGH)** — `cleanup()` did `forwardingScope.launch { stopAllTunnels() }` and then `forwardingScope.cancel()` on the immediately following line. The scope was cancelled before the launched coroutine had a chance to run, so active port forwards were NEVER actually torn down on cleanup; they stayed attached to the JSch Session until the Session itself disconnected. Rewrote `cleanup()` to issue the JSch `delPortForwardingL/R` calls directly (non-suspending) on the calling thread, then cancel the scope. Matches the per-tunnel `stopTunnel()` teardown semantics. Listeners still notified via `onAllTunnelsStopped()`.
  - `app/src/main/java/io/github/tabssh/ssh/forwarding/PortForwardingManager.kt`
- [x] **`SSHConnection.openShellChannel` channel leak on connect() throw (MEDIUM)** — `currentSession.openChannel("exec"|"shell")` allocates a channel slot on the JSch Session immediately; any throw between `openChannel()` and `openChannels.add(channel)` (PTY config, env-var apply, `connect()` timeout, IO exception during stream prefetch) left the channel attached to the Session forever. Under sustained errors (server with low MaxSessions, network flapping) this exhausted the per-session channel limit. Wrapped both `exec` and `shell` happy-paths in a nested `try { ... } catch { ch.disconnect(); throw }` to explicitly release the slot before re-throwing into the outer catch. `openSftpChannel` (line ~1570) had the same shape and was fixed identically.
  - `app/src/main/java/io/github/tabssh/ssh/connection/SSHConnection.kt`
- [x] **`VncDirectConnector.connect` Socket leak on failure (MEDIUM)** — `Socket()` was allocated, then `socket.connect(...)` or the `RfbClient` constructor (which reads `socket.inputStream`) could throw. Caller never receives the socket on the failure path, so the file descriptor leaks until GC's finaliser eventually runs `Socket.close()` — minutes-to-hours of latency, and on hot-reconnect loops the user hits `EMFILE`. Wrapped the body in `try { ... } catch (Throwable) { socket.close(); throw }`.
  - `app/src/main/java/io/github/tabssh/hypervisor/vnc/VncDirectConnector.kt`
- [x] **`TabManager` ArithmeticException on empty tab list (MEDIUM)** — `switchToNextTab`, `switchToPreviousTab`, and the `Ctrl+Tab` / `Ctrl+Shift+Tab` keyboard shortcut path all computed `(activeTabIndex + 1) % tabs.size` and `tabs.size - 1` with no guard. If a shortcut fires after the last tab has been closed (e.g. a queued key event arriving on the main thread after `closeAllTabs` empties `tabs`), `% 0` throws `ArithmeticException` and crashes the activity. Added `if (tabs.isEmpty()) return` to both public helpers and an `isNotEmpty()` guard around the in-line keyboard-shortcut handler.
  - `app/src/main/java/io/github/tabssh/ui/tabs/TabManager.kt`
- [x] **`MetricsCollector.parseNetworkStats` `previousNetworkStats!!` race (LOW)** — `previousNetworkStats` is a class-scoped `var Pair<Long, Long>?` that is read at parse time and reset to `null` by `resetNetworkStats()` (called when a new SSH connection takes over the metrics pump). The check `if (previousNetworkStats != null && ...)` followed by `val (prevRx, prevTx) = previousNetworkStats!!` had a TOCTOU window where another thread's `resetNetworkStats()` could null the field between the two reads. Captured into a local `val prev` first, then destructured `prev` — same observable behaviour, no inter-thread NPE window. (Same pattern as the `SAFSyncManager.lastError!!` race fixed in pass 9.)
  - `app/src/main/java/io/github/tabssh/performance/MetricsCollector.kt`

## Verified clean (pass 10)

- All `addListener` / `setListener` sites in `services/`, `ui/activities/`, `ui/views/` have a paired `removeListener` in `onDestroy` / `onDestroyView`, OR the listener target is itself destroyed in that callback (e.g. `TermuxBridge.cleanup()` clears its listener list, so per-bridge listeners attached in `SSHTab` and `VMConsoleActivity` are released transitively).
- All UI-input `toInt()` call sites either go through `toIntOrNull() ?: default` or are guarded by a preceding `validateFields()` that confirms the value parses (verified for `HypervisorEditActivity`, `ConnectionEditActivity`, `PortForwardingActivity`).
- All `Executors.*` instances (`VncConsoleChannel.writeExecutor`, `Logger.executor`, `X11Proxy.executor`) have a corresponding `shutdownNow()` on close/stop.
- All `ServerSocket` / `Socket` allocations in `ssh/forwarding/`, `network/portknock/`, `ssh/connection/TelnetConnection`, `ssh/forwarding/HttpPortProbe` are wrapped in `.use { }` or a `try { ... } finally { socket.close() }`. The only outlier was `VncDirectConnector`, now fixed.
- `MoshNativeClient.Session.errScope` is cancelled and the process streams are closed in `close()`.
- `MultiHostDashboardActivity.pumpScope` and `HostDetailActivity.pumpScope` are cancelled in `onDestroy`. No new leaks introduced since pass 9.
- `SSHTab.connectionScope` is cancelled in `cleanup()`; the missing piece (TabManager calling `disconnect()` instead of `cleanup()`) is the leak fixed in this pass.
- Unchecked Kotlin casts (`as T` without `?`) surveyed: every remaining site is either a documented JSch idiom (`openChannel("exec") as ChannelExec`, `channel.ls(path) as Vector<...>`), a same-class downcast of `application as TabSSHApplication` (guaranteed by Android API contract once the manifest declares the application class), or a `getSystemService(...) as ConnectivityManager` / `as ClipboardManager` / `as InputMethodManager` style cast (guaranteed by the platform contract for the service constant used). No unsound casts remain.

## Build verification (pass 10)

`make check` not run from this pass — same Docker-image rationale as pass 9. Edits are local, additive (extra try/catch arms, isEmpty guards, local captures), and do not change any public signature, return type, or visibility modifier. Re-verify with `make check` in CI before merging.

## Fixed in pass 11 (2026-06-10) — stream / fd leaks on failure paths

- [x] **`KeyStorage.importKeyFromFile` InputStream leak (MEDIUM)** — `context.contentResolver.openInputStream(fileUri)` was assigned to a local `val inputStream`, then `inputStream.bufferedReader().readText()` was called without `.use {}`. The intermediate `BufferedReader` wraps the underlying `InputStream`, so `readText()` exhausting the stream does NOT close the underlying ParcelFileDescriptor. Each failed (or successful) key import leaked one SAF fd; chained imports during onboarding could hit `EMFILE` on low-end devices. Re-shaped to `openInputStream(...)?.bufferedReader()?.use { it.readText() }` (matches the pattern already used in `CloudAccountsFragment` and `ImportExportActivity`).
  - `app/src/main/java/io/github/tabssh/crypto/keys/KeyStorage.kt`
- [x] **`TelnetConnection.connect` Socket leak on connect throw (MEDIUM)** — `val s = Socket()` was allocated inside the `try{}` block, then `s.connect(...)` immediately on the next line. If `connect()` threw (timeout, unreachable, refused), control jumped to the `catch{}` arm which called `disconnect()` — but `disconnect()` only closes the class-scope `socket` field, which had not yet been assigned (`socket = s` only happens after `connect()` succeeds). The locally-allocated `s` fell out of scope still holding an open fd, releasable only by the GC finaliser minutes later. Hoisted `val s = Socket()` above the `try{}`, added an explicit `s.close()` in the `catch{}` arm. Same shape as the `VncDirectConnector` fix from pass 10.
  - `app/src/main/java/io/github/tabssh/ssh/connection/TelnetConnection.kt`
- [x] **`SessionRecorder.startRecording` FileWriter leak on partial-init failure (LOW)** — `fileWriter = FileWriter(currentFile, true)` was assigned to the field before the initial `# TabSSH Session…` write. If that write/flush threw (e.g. external storage went away mid-init), the field was left non-null with an open fd, but `isRecording` stayed `false`, so the subsequent retry's `startRecording()` returned past the `isRecording` guard and overwrote `fileWriter` without closing the old one. Re-shaped to: write into a local `val w`, only publish to the field after the initial write succeeds, and explicitly close `w` in the catch arm before re-throwing.
  - `app/src/main/java/io/github/tabssh/terminal/recording/SessionRecorder.kt`
- [x] **`SessionRecorder.stopRecording` FileWriter leak on trailing-write failure (LOW)** — The original sequence `fileWriter?.write("\n# Session ended…"); fileWriter?.close(); isRecording = false` would skip `close()` entirely if `write()` threw, and would also leave `isRecording = true`, blocking any restart. Re-ordered: capture the writer into a local, null the field and flip `isRecording = false` first, then attempt the trailing write inside its own try/catch, then close in another try/catch. `close()` is now guaranteed to run.
  - `app/src/main/java/io/github/tabssh/terminal/recording/SessionRecorder.kt`

## Verified clean (pass 11)

- `rg "openInputStream"` across `app/src/main`: every other call site already uses `?.bufferedReader()?.use { … }` or wraps the stream in `.use { }`. KeyStorage was the only leaker.
- `rg "Socket\(\)"` raw-Socket allocations: all other sites (`HttpPortProbe`, `ssh/forwarding/PortForward*`, `network/portknock/PortKnocker`) already wrap allocation+connect in `try/finally { socket.close() }` or `.use { }`. `TelnetConnection` was the only mis-shaped one after pass 10's `VncDirectConnector` fix.
- `rg "FileWriter\(|FileOutputStream\(|FileInputStream\("` app-wide: `SessionRecorder` was the only long-lived writer holding a member field; all other allocations are inside `.use { }` blocks or `try/finally`.
- Unchecked-cast survey (`as <Type>` without `?` and not from a documented platform contract): remaining hits are all JCA (`as RSAPublicKey`, `as ASN1OctetString`), JSSE (`as SSLSocket` from `SSLSocketFactory.createSocket`), JSch (`as Vector<ChannelSftp.LsEntry>`, `as ChannelExec/Shell/Sftp`), or `getSystemService(...)` for documented service constants — all platform-guaranteed.
- Companion-object Context survey: no companion holds an Activity Context or View reference. The five companion objects that import `Context` use it only as a parameter to `startService`/`stopService`/factory helpers — no field capture, no leak surface.
- BroadcastReceiver register/unregister survey: still zero dynamic `registerReceiver` calls in production source; the prior pass-9 finding holds.
- `lateinit var` survey: re-spot-checked the top 20 by file size — every site is bound in `onCreate`/`onCreateView` (or constructor) before any public method that reads it; no premature-read paths added since pass 10.
- Coroutine-scope survey: no new throwaway `CoroutineScope(Dispatchers.*)` introduced since pass 9; the existing six per-class scopes are all cancelled in `cleanup()` / `onDestroy` / `close()`.

## Build verification (pass 11)

`make check` not run from this pass — same Docker-image rationale as passes 9 and 10. Edits are local, additive (one `?.use { }` chain replacement, one Socket hoist with an extra catch-arm close, two `SessionRecorder` rewrites). No public signature, return type, or visibility modifier changed. Re-verify with `make check` in CI before merging.

## Fixed in pass 12 (2026-06-10) — more leaks, races, and stale state

- [x] **`PortForwardingManager.cleanup` audit-logging orphan scope (LOW)** — Cleanup spawned `kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { … }` per teardown tunnel to write the audit log. The throwaway `CoroutineScope` had no parent SupervisorJob held anywhere; each call allocated a parent Job that was never cancelled or referenced, so it lived until process death. Routed via `app.applicationScope.launch(Dispatchers.IO) { … }` so the write attaches to the process-lifetime scope already used by `TaskerWorker` / `PerformanceFragment`. Same observable behaviour.
  - `app/src/main/java/io/github/tabssh/ssh/forwarding/PortForwardingManager.kt`
- [x] **`HypervisorsFragment` REST reachability probe Socket leak (MEDIUM)** — `val socket = java.net.Socket(); socket.connect(...); socket.close()` — if `connect()` throws (timeout, unreachable, refused) the `socket.close()` line is skipped and the fd leaks until GC runs the finalizer. Wrapped the `connect()` call in a `try { ... } finally { socket.close() }`. Same shape as the pass-10 `VncDirectConnector` and pass-11 `TelnetConnection` fixes.
  - `app/src/main/java/io/github/tabssh/ui/fragments/HypervisorsFragment.kt`
- [x] **`X11Proxy.connectToXServer` LocalSocket + Socket leak on connect throw (MEDIUM)** — Two probes (Termux:X11 Unix socket, XServer XSDL TCP) each allocated the socket *inside* the `try` block, so if `connect()` threw the catch arm logged a warning and let the socket fall out of scope without closing it. Hoisted the allocation above the `try`, added an explicit `socket.close()` in the catch arm for both probes. Important because connect failures here are common (no X server installed is the default case for most users), so on a hot retry loop the proxy could rapidly burn fds.
  - `app/src/main/java/io/github/tabssh/ssh/forwarding/X11Proxy.kt`
- [x] **`ImportExportActivity.importSSHConfigFromUri` InputStream leak (MEDIUM)** — `contentResolver.openInputStream(uri)` was assigned to a local `inputStream`; `inputStream.bufferedReader().use { ... }` does close the underlying stream chain through `BufferedReader.close()`, but assigning to a `val` first meant a throw between the assignment and `.use {}` (e.g. OOM allocating the BufferedReader on a very large file) would leak the fd. Re-shaped to the chained `openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw …` form used elsewhere in the codebase (matches `KeyStorage` after pass 11). Same observable behaviour.
  - `app/src/main/java/io/github/tabssh/ui/activities/ImportExportActivity.kt`
- [x] **`VncStreamHolder.set` orphan-stream leak (MEDIUM)** — The cross-Activity hand-off holder accepted new `(in, out, socket)` streams without closing whatever was already stored. If the producer activity called `set(...)` twice before the consumer ran `take()` (e.g. user backed out of the launch path, then re-initiated), the prior set was never consumed and its descriptors leaked for the rest of the process. Added explicit close-then-replace inside the `@Synchronized set(...)`. Each close wrapped in `try/catch` so a failure on one doesn't skip the others. No public API change.
  - `app/src/main/java/io/github/tabssh/hypervisor/vnc/VncStreamHolder.kt`
- [x] **`TabManager.switchToTab` unused `previousTab` (LOW)** — `val previousTab = getActiveTab()` was computed but never read. Dead code; removed. No observable change.
  - `app/src/main/java/io/github/tabssh/ui/tabs/TabManager.kt`
- [x] **`ConsoleWebSocketClient.isConnected` missing `@Volatile` (LOW)** — Field is read inside the keepalive worker thread's loop (`while (isConnected)`) and written from the OkHttp WebSocket callback thread + the synchronous `disconnect()` path. Without `@Volatile` the JIT may cache the read; the keepalive thread could fire one extra send after disconnect before noticing. Added the annotation. No behaviour change in correct uses; eliminates the post-disconnect ghost-send window.
  - `app/src/main/java/io/github/tabssh/hypervisor/console/ConsoleWebSocketClient.kt`

## Verified clean (pass 12)

- All `Socket()` / `ServerSocket()` / `LocalSocket()` allocations in production source now either go through `.use { }`, sit in a class-scope field that's closed in `disconnect()`/`close()`, or are wrapped in a `try { ... } finally { socket.close() }` arm. Re-checked: `HttpPortProbe`, `PortKnocker` (both TCP and UDP arms), `PortForwardingManager` probe, `AuditLogManager` UDP, `TelnetConnection`, `VncDirectConnector`, and the two X11Proxy probes fixed in this pass.
- All `contentResolver.openInputStream` / `openOutputStream` call sites use either the chained `?.bufferedReader()?.use { }` / `?.use { }` form or a `try { ... } finally { stream.close() }`. ImportExportActivity was the last outlier.
- Companion-object Context survey: still no companion holds an Activity Context. No new singletons added since pass 11.
- `SessionPersistenceManager` (~470 LOC) is dead — no instantiations anywhere in source. Flagged for the user; not deleted from this audit pass (out of scope per audit rules: removal changes observable behaviour if it's wired in later).
- `@Volatile` survey across `var` fields touched by background threads + main: `NetworkAwareReconnector.paused`/`cancelled`, `VncStreamHolder._inputStream/_outputStream/_socket`, `RfbClient.pendingResizeRejection`, `ConsoleWebSocketClient.sendFailureFired`/`userInitiatedClose` already annotated. `isConnected` was the missing one.
- All `!!` operators surveyed (sample of 30+): every remaining site is either same-block-checked, immediately-after-assignment, a non-nullable construction (`X11NoServerException().message!!` — its constructor passes a constant non-null literal), or a property of a `val data class` field. No new NPE windows.
- All `as Channel*` casts surveyed: all match the JSch `openChannel("exec"|"shell"|"sftp"|"direct-tcpip")` documented return type. Each is paired with either a `try { ... } catch { ch.disconnect(); throw }` (the connection-time-leak protection added in pass 10) or, for the libvirt / mosh / SCP transient callers, a `finally { ch?.disconnect() }`.

## Build verification (pass 12)

`make check` not run from this pass — same Docker-image rationale as passes 9–11. Edits are local, additive (one `applicationScope.launch` swap, three Socket / LocalSocket hoists with extra catch-arm closes, one chained `?.use` replacement, one `@Synchronized` close-then-replace, one dead-`val` deletion, one `@Volatile` annotation). No public signature, return type, or visibility modifier changed. Re-verify with `make check` in CI before merging.

## Fixed in pass 13 (2026-06-12) — main-thread / concurrency / lifecycle audit

Scope: SSH stack + tab manager + terminal bridge + services + Activities/Receivers/Services.

- [x] **`SSHTab` plain `var` fields lacked `@Volatile` (HIGH)** — `connection`,
  `ownChannel`, `telnetConnection`, `moshSession`, `sessionStartTime`,
  `bytesReceived`, `bytesSent` were all written from `connectionScope`
  (Dispatchers.IO) and read from Main (UI status, gesture send) and from
  TermuxBridge/JSch worker callbacks (listener `onData`, `onDisconnected`).
  Without `@Volatile` the JIT can cache a stale read across threads —
  realistic symptom: post-disconnect listener callback sees a non-null
  `connection` and tries to use a half-torn-down JSch session, or the UI
  shows stale bytes-counter values. Added `@Volatile` with a brief
  comment on each field documenting which threads write/read it.
  - `app/src/main/java/io/github/tabssh/ui/tabs/SSHTab.kt:41,48,52,57,91-96`
- [x] **`SSHConnection` JSch-callback fields lacked `@Volatile` (HIGH)** —
  `lastHostKeyDecision`, `resolvedIdentity`, `cachedPassword`,
  `cachedPassphrase` are written from the connect coroutine (IO) and
  read from JSch's `UserInfo` callbacks which fire on JSch's own
  internal worker thread (not the coroutine that called `connect()`).
  Same visibility risk as the SSHTab fields. `cachedPassword` /
  `cachedPassphrase` additionally get cleared by `clearCachedCredentials()`
  on app-background which races with an in-flight JSch re-auth read.
  Added `@Volatile` to all four fields.
  - `app/src/main/java/io/github/tabssh/ssh/connection/SSHConnection.kt:163,166,169,172`
- [x] **`TabTerminalActivity.closeSplitPane` blocked the UI thread on JSch
  teardown (HIGH)** — User tap → `tab.disconnect()` ran inline on Main.
  `disconnect()` calls `termuxBridge.disconnect()` (closes JSch streams)
  and lets `SSHSessionManager.closeConnection` call `connection.disconnect()`
  which performs blocking `session.disconnect()` against the remote — a
  network round-trip. On a slow / dropped link this is the classic ANR
  shape (5 s threshold). Wrapped both the `tab.disconnect()` and the
  `sshSessionManager.closeConnection()` calls in `app.applicationScope.launch(Dispatchers.IO)`.
  UI updates (`splitTab = null`, view visibility, Toast) stay on Main.
  - `app/src/main/java/io/github/tabssh/ui/activities/TabTerminalActivity.kt:2840-2855`
- [x] **`TabTerminalActivity.onDestroy` split-tab teardown blocked Main
  thread (HIGH)** — Same shape: `onDestroy` runs on Main and called
  `stab.disconnect()` + `sshSessionManager.closeConnection()` inline.
  Moved to `applicationScope.launch(Dispatchers.IO)` so the user's
  back-button gesture returns immediately and the SSH teardown completes
  in the background. `applicationScope` outlives the Activity so the
  cleanup is guaranteed to run.
  - `app/src/main/java/io/github/tabssh/ui/activities/TabTerminalActivity.kt:3807-3819`
- [x] **`Dispatchers` import added** —
  `TabTerminalActivity.kt:35` — supports the two `applicationScope.launch(Dispatchers.IO)`
  call sites above.

## Fixed in pass 14 (2026-06-13) — LOW items from pass 13

- [x] **`SSHTab.disconnect()` swallows telnet/mosh exceptions silently (LOW)** — Both
  `catch (_: Exception) {}` blocks in `SSHTab.disconnect()` (telnetConnection and
  moshSession close paths) now log the swallowed exception at `Logger.d` level so
  it appears in debug builds and crash-report attachments without disrupting the
  cleanup sequence.
- [x] **`TabManager.listeners` plain `mutableListOf` (LOW)** — Replaced with
  `CopyOnWriteArrayList<TabManagerListener>()`. Registration still happens on UI
  thread; iteration in the four `notify*` helpers is now safe regardless of which
  thread publishes a state change. Matches the pattern used by `SSHSessionManager`,
  `SSHConnection`, `PortForwardingManager`, and `TerminalEmulator`.

## Resolved (pass 13 MEDIUMs)

- [x] **`SSHConnection.disconnect()` not `suspend`** — Fixed in commit 135b826
  (2026-06-13). `disconnect()` is now `suspend fun disconnect() = withContext(Dispatchers.IO)`.
  All ~10 call-site files updated.
- [x] **`SSHConnection.executeCommand` busy-wait** — Fixed in commit 135b826
  (2026-06-13). Replaced `available()` + `delay(100)` poll with blocking
  `inputStream.read()` inside `withTimeoutOrNull(timeoutMs)`.

## Verified clean (pass 13)

- `RfbClient.keepaliveLoop` / `eventLoop` `Thread.sleep` calls (lines
  706, 724, 982) all run on dedicated worker threads created in
  `start()`. Not on Main. Safe.
- `ConsoleWebSocketClient.startProxmoxKeepalive` `Thread.sleep` (line
  415) runs on the dedicated `keepaliveThread`. Safe.
- `AnrWatchdog` `Thread.sleep` runs on its own watchdog thread. Safe.
- `HostKeyVerifier.check()` uses `runBlocking(Dispatchers.IO)` but is
  invoked by JSch from JSch's own worker thread during host-key
  negotiation, not from Main. Safe.
- `SSHSessionManager.closeConnection` already has try-catch around
  `connection.disconnect()` (prior fix). The CancellationException
  branch in `connectToServer` already calls `closeConnection(profile.id)`
  to release the orphan authenticated session. Clean.
- `SSHConnectionService` uses `START_NOT_STICKY`, cancels `monitoringJob`
  before relaunch, propagates `CancellationException`. Per-host
  notifications anchored on first `onConnectionEstablished`. Clean.
- `MdmRestrictionsReceiver`, `TaskerActionReceiver`,
  `MonitoringBootReceiver` all idempotent and hand off to WorkManager
  or do near-zero work. No `onReceive` doing blocking work. Clean.
- `TabManager.saveTabState` uses `tabs.toList()` snapshot to avoid CME
  during IO iteration. (Prior fix, verified.)
- `TabManager.cleanup` now calls `tab.cleanup()` (not `tab.disconnect()`)
  which also tears down the per-tab `connectionScope` and `TermuxBridge`.
  (Pass-10 fix, verified.)
- `TermuxBridge` uses `@Volatile` on cross-thread fields and a `writeLock`
  Mutex serializes all SSH writes to prevent the JSch GCM-cipher-state
  race. `CopyOnWriteArrayList` for listeners. Disconnect idempotent.
- `ConfirmDisconnectActivity` already dispatches all blocking work to
  `applicationScope.launch(Dispatchers.IO)` and uses
  `closeConnectionIntentionally` to mark the tab so the reconnect
  dialog is skipped.

## Fixed in pass 15 (2026-06-13) — OCI POST signing 401

- [x] **`OciSigner.asInterceptor()` — POST/PUT/PATCH `content-type` carries
  `; charset=utf-8` (BUG)** — OkHttp's `String.toRequestBody()` appends
  `; charset=utf-8` to the `MediaType` when no charset is specified (confirmed
  by bytecode inspection of `okhttp-4.12.0.jar`). The signer read the body's
  `contentType().toString()` (which included the suffix) for the signing string.
  Simultaneously, `BridgeInterceptor` unconditionally replaces `Content-Type`
  from the body object — so the signed value and the wire value both carried
  `application/json; charset=utf-8`. OCI's server-side verifier normalises the
  received `Content-Type` before reconstructing the signing string, yielding
  `application/json`. This caused the signed value and the server's computed
  value to diverge, producing a 401 "Failed to verify the HTTP(S) Signature"
  on every `instanceAction` POST call (GET/DELETE calls were unaffected as they
  carry no body and no body-related signing headers). Confirmed by the debug log
  at 2026-06-13 17:49:01 (cert-pin match, then immediate 401).

  **Fix** (`OciSigner.kt` `asInterceptor()`):
  - Compute `bareContentType` using `"${mt.type}/${mt.subtype}"` — strips all
    MediaType parameters (charset, boundary, etc.).
  - Replace the original body with a `ByteArray.toRequestBody(bareContentType)`
    body so `BridgeInterceptor` propagates the bare MIME type on the wire.
  - Use `bareContentType` for the `content-type` signing header.
  - Result: signed value = `application/json`, wire value = `application/json`,
    OCI computed value = `application/json`. All three match. ✓
  - Matches the behaviour of OCI's official Python, Java, and Go SDKs which
    all send `content-type: application/json` (no charset).
  - Added `Logger.d("OciSigner", ...)` line that logs the signed header names
    per request so future signing failures can be diagnosed without guessing.

Delete this file once all items above are addressed or the user confirms they are out of scope.

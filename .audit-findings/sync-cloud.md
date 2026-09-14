# Audit: sync, backup/restore, cloud, widgets

Auditor: sync-cloud subsystem agent. Every file in scope read end to end.
Line numbers refer to the current working tree.

---

### [BLOCKER] [CONFIRMED] 3-way merge silently drops one-sided remote edits for connections and themes

**Where:** `app/src/main/java/io/github/tabssh/sync/merge/MergeEngine.kt:100-101` → `mergeConnectionFields` (174-184); same pattern for themes at 343-361.

**What:** When base, local, and remote all exist and both sides changed (or only remote changed but local `modifiedAt` is newer/equal after clock skew), `mergeConnectionFields` returns the **local** content while adopting `max(local.modifiedAt, remote.modifiedAt)`. Adopting remote's newer timestamp onto local content makes the divergence permanent: the next sync sees equal timestamps and never revisits the row, and no conflict record is created for this path.

**Failure scenario:** Device B edits a connection's host and syncs. Device A, whose copy of the same row has a same-or-newer `modifiedAt` (any edit, or clock skew), syncs next: merge keeps A's content, stamps it with B's timestamp, uploads. B's edit is gone everywhere, silently. AI.md:1059 says verbatim "never silent last-write-wins for user-authored data" — this is silent local-wins, worse than LWW.

**Fix:** When both sides differ from base (or remote differs and local doesn't cleanly win), emit a per-item conflict record via SyncLogManager/ConflictResolver and pause that row, per AI.md:1059. Never adopt the other side's `modifiedAt` onto surviving content.

---

### [BLOCKER] [CONFIRMED] Upload after failed download clobbers the peer's sync file and reports success

**Where:** `app/src/main/java/io/github/tabssh/sync/SyncWorker.kt:62-78` and `app/src/main/java/io/github/tabssh/ui/activities/SyncSettingsActivity.kt:551-562`; root cause `app/src/main/java/io/github/tabssh/sync/saf/SAFSyncManager.kt:290-353` (`download()`).

**What:** `download()` returns `null` for "file not present" AND for decrypt failure, read IOException, and parse failure alike. Both sync drivers treat `null` as "nothing remote yet", skip merge, then upload the local dataset over the existing remote file and record the cycle as successful (snapshotState advances).

**Failure scenario:** Device A's sync passphrase is stale (peer rotated it) or the SAF read hiccups once. Download decrypt fails → null → A overwrites the shared file with its own state. Device B's newer edits in that file are destroyed; both devices report green syncs. Data loss with success reported.

**Fix:** Make `download()` distinguish "absent" from "failed" (sealed result or thrown exception). On failure: abort the cycle with an error, never upload, never snapshot.

---

### [BLOCKER] [CONFIRMED] applyAll blind-REPLACEs ~22 non-merge-tracked entity types, losing newer local edits

**Where:** `app/src/main/java/io/github/tabssh/sync/data/SyncDataApplier.kt` (e.g. snippet loop 222-247 — plain `insertSnippet(s.copy(...))` after tombstone check, no timestamp compare; same shape for workspaces, groups, port forwards, VNC hosts/identities, etc.). Delta collector `SyncDataCollector.kt:469+` (`collectChangedSince`) has **zero callers** — every sync is full-table.

**What:** Only connections/keys/themes/hostKeys go through MergeEngine (SyncMergeCoordinator strips exactly those four). Every other type in the package is applied by unconditional REPLACE of the remote row over the local row, with only tombstone suppression. No local-vs-remote `modifiedAt` comparison exists anywhere on this path.

**Failure scenario:** Device B uploads at 10:00. Device A edits a snippet at 10:05, then syncs at 10:10: download returns B's 10:00 package; applier REPLACEs A's 10:05 snippet with B's stale copy; A then uploads the stale content. A's edit is lost on every device. IDEA.md:107 blesses LWW only for network routes; this is unconditional remote-wins for user-authored data (snippets, workspaces, groups...), violating AI.md:1059.

**Fix:** At minimum compare `modifiedAt` per row before insert (true LWW); properly, extend merge tracking, and wire the already-written `collectChangedSince` delta path or delete it.

---

### [MAJOR] [CONFIRMED] "Manual only" sync frequency still auto-syncs every 15 minutes

**Where:** `app/src/main/java/io/github/tabssh/sync/SyncWorkScheduler.kt:136-148` (frequency → interval mapping returns 0 for manual) and 37-50 (PeriodicWorkRequest built unconditionally; WorkManager clamps intervals below 15 min **up to 15 min**). Scheduling triggers: `SyncSettingsActivity.kt:533, 266, 294`, `TabSSHApplication.kt:333`.

**What:** Selecting "Manual only" maps to interval 0, which is still passed to `PeriodicWorkRequest`; WorkManager clamps 0 to MIN_PERIODIC_INTERVAL_MILLIS (15 min). The periodic job is never cancelled for the manual setting.

**Failure scenario:** User picks "Manual only" precisely to stop background SAF/network churn (metered data, battery, shared-file contention) — the app keeps syncing every 15 minutes anyway.

**Fix:** For manual frequency, `WorkManager.cancelUniqueWork(...)` instead of enqueueing.

---

### [MAJOR] [CONFIRMED] Overwrite-mode restore silently skips existing hypervisor accounts

**Where:** `app/src/main/java/io/github/tabssh/backup/BackupImporter.kt:467-472` (plain `@Insert` DAO — default `OnConflictStrategy.ABORT`), exception swallowed per-row at 658-664.

**What:** In overwrite mode every other entity replaces existing rows; hypervisor accounts use a bare insert that ABORTs on PK conflict, and the per-row catch logs and continues, counting the restore as successful.

**Failure scenario:** User restores a backup to repair a corrupted hypervisor account entry. The stale local row survives, the backup row is dropped, and the restore summary reports success.

**Fix:** Use insert-or-update (as `SyncDataApplier` already does for the same entity) or delete-then-insert in overwrite mode.

---

### [MAJOR] [CONFIRMED] Domain and VPS-host trackers are half-wired into sync: tombstones recorded, entities never synced, exclusion undocumented

**Where:** `app/src/main/java/io/github/tabssh/sync/tombstone/TombstoneRecorder.kt:65-66` (constants `DOMAIN`, `VPS_HOST`); record() call sites `VpsHostEditActivity.kt:266`, `DomainTrackerActivity.kt:391`, `DomainEditActivity.kt:248`, `VpsTrackerActivity.kt:400`. `SyncModels.kt` `SyncDataPackage` has no domains/vpsHosts fields; collector and applier never touch them.

**What:** Deleting a domain/VPS host records a sync tombstone for an entity type that is never collected, uploaded, or applied. The tombstones are dead weight that syncs to peers and can never suppress anything. AI.md:1060 requires a coverage matrix in IDEA.md ("what syncs, what doesn't, and why") — IDEA.md has no such matrix, so this exclusion is undocumented and looks like an unfinished wiring job rather than a decision.

**Failure scenario:** User tracks domains on two devices expecting sync (everything else syncs); domains silently don't. Nothing in the app or docs says so.

**Fix:** Either add domains/vpsHosts to SyncDataPackage + collector + applier, or drop the two tombstone types and their call sites, and in both cases write the AI.md-required coverage matrix into IDEA.md.

---

### [MAJOR] [CONFIRMED] Home-screen widgets go permanently stale: no periodic update and the refresh helper is dead code that misses 3 of 4 providers

**Where:** all four widget info XMLs set `android:updatePeriodMillis="0"` (`app/src/main/res/xml/widget_1x1_info.xml`, `widget_2x1_info.xml`, `widget_4x2_info.xml`, `widget_4x4_info.xml`); `app/src/main/java/io/github/tabssh/widgets/ConnectionWidgetProvider.kt:179-183` (`updateAllWidgets`) queries only `ComponentName(context, ConnectionWidgetProvider::class.java)` — never the Widget2x1/4x2/4x4 subclasses' widget ids — and has **zero callers** anywhere in the app.

**What:** After configuration a widget's label/target is only re-rendered on ACTION_APPWIDGET_UPDATE, which never fires (period 0) except at boot/resize. Renaming or deleting the bound connection never refreshes the widget. The one helper written for this is uncalled, and even if called would skip every subclass provider's instances.

**Failure scenario:** User renames "prod-db" to "prod-db-OLD" and creates a new "prod-db". Widget still shows and connects to the old profile (or a deleted one → broken tap) indefinitely.

**Fix:** Call an update helper from connection rename/delete paths, and enumerate all four provider ComponentNames (or move to a shared helper that each subclass registers with).

---

### [MAJOR] [CONFIRMED] Sync-on-change observer watches only 6 of ~28 synced tables

**Where:** `app/src/main/java/io/github/tabssh/sync/observer/DatabaseChangeObserver.kt:61-83` — `observeAllChanges()` combines flows from connectionDao, keyDao, themeDao, hostKeyDao, vncHostDao, vncIdentityDao only.

**What:** Edits to snippets, workspaces, connection groups, port forwards, hypervisor accounts, cloud accounts, etc. never trigger the 30s-debounced change sync; those changes only leave the device on the next periodic (15 min) or manual sync.

**Failure scenario:** User creates a snippet on the phone, picks up the tablet a minute later expecting it (change-sync is on) — it isn't there and won't be for up to 15 minutes.

**Fix:** Add flows for every table the collector serializes (or observe the DB's invalidation tracker for the synced-table set).

---

### [MAJOR] [CONFIRMED] AWS regex XML parsing truncates each instance at its first nested `<item>` — phantom "unknown" rows and lost Name tags

**Where:** `app/src/main/java/io/github/tabssh/cloud/AwsEc2Client.kt:223-255` (`parseLiveInstancesPage`) and 260-302 (`parseInstances`); the lazy `Regex("<item>([\s\S]*?)</item>")` at 227/266; `extractNameTag` 310-315.

**What:** Every EC2 instance `<item>` contains nested `<item>` elements (`groupSet`, `tagSet`, `blockDeviceMapping`). The lazy regex ends the instance block at the **first nested `</item>`** (groupSet security group), so: (a) `tagSet` is never inside the parsed block → `extractNameTag` always returns null → instances display as raw instance ids, never their Name tag; (b) the regex then resumes and matches each remaining nested `<item>` (second security group, tag entries, block-device entries) as a standalone pseudo-instance. `parseLiveInstancesPage` has no skip filter, so each pseudo-block is emitted as `CloudInstanceState(id="", name="", status="unknown")`.

**Failure scenario:** An account with one running instance (2 security groups, a Name tag, one EBS volume) shows the real row named `i-0abc...` plus several blank "unknown" phantom rows in the cloud manager; power actions on a phantom row post an empty InstanceId to AWS and fail. `parseInstances` (import) is spared the phantoms only because blank host rows are skipped, but Name tags are still lost there too.

**Fix:** Parse with `XmlPullParser` (already available on Android) tracking `<item>` depth, or split instance blocks on top-level `<item>` boundaries by depth counting; at minimum filter blocks with blank `instanceId` in `parseLiveInstancesPage`.

---

### [MAJOR] [CONFIRMED] Azure force restart always fails: action URL contains two `?`

**Where:** `app/src/main/java/io/github/tabssh/cloud/AzureVmClient.kt:213` (`action = "restart?skipShutdown=true"`) combined with 237 (`.../$action?api-version=2023-03-01`).

**What:** The final URL is `.../restart?skipShutdown=true?api-version=2023-03-01` — the second `?` is literal, so ARM sees a single query param `skipShutdown=true?api-version=2023-03-01` and no `api-version`. ARM rejects any request without `api-version` (HTTP 400 MissingApiVersionParameter) → `azureVmAction` returns false.

**Failure scenario:** Every "Force restart" tap on an Azure VM shows the "action failed" toast; the graceful path (plain `restart`) works, so users conclude force restart is broken — it is, 100% of the time.

**Fix:** Build the query properly: `.../restart?api-version=2023-03-01&skipShutdown=true` (pass action path and query params separately).

---

### [MAJOR] [CONFIRMED] Azure power actions can target the wrong VM when names repeat across resource groups

**Where:** `app/src/main/java/io/github/tabssh/cloud/AzureVmClient.kt:188` (`id = name` — VM name used as instance id) and 224 (`cachedInstances.firstOrNull { it.id == instanceId }` to resolve resource group).

**What:** Azure VM names are unique per resource group, not per subscription. Two VMs named `web-01` in `staging-rg` and `prod-rg` produce two rows with identical id `web-01`; the RG lookup returns whichever came first in the listing, and the action URL (`.../resourceGroups/$rg/.../virtualMachines/$instanceId/...`) is built from that RG regardless of which row the user tapped.

**Failure scenario:** User taps Stop on prod's `web-01`; lookup resolves staging's row first; staging's VM is deallocated while prod keeps running — a destructive action lands on the wrong machine.

**Fix:** Use the full ARM resource id (already parsed at line 150) as `CloudInstanceState.id`, extract RG+name from it in `azureVmAction`.

---

### [MINOR] [CONFIRMED] GCP fetchLiveInstances breaks the CloudAuthException contract on 401/403

**Where:** `app/src/main/java/io/github/tabssh/cloud/GcpComputeClient.kt:146-151` throws `IllegalStateException` for all non-2xx including 401/403; contrast `fetchInventory` at 98-100 and the contract at `CloudProvider.kt:98-100`.

**What:** An API-level 403 (compute scope/IAM revoked after a successful OAuth exchange) surfaces as the generic "Load failed" toast instead of the "token invalid — re-add account" hint the UI reserves for CloudAuthException (`CloudAccountManagerActivity.kt:160-165`).

**Fix:** Mirror the 401/403 branch from `fetchInventory`.

---

### [MINOR] [CONFIRMED] Hetzner stopInstance issues a hard power cut despite the graceful contract

**Where:** `app/src/main/java/io/github/tabssh/cloud/HetznerClient.kt:116-117` uses the `power_off` action; `CloudProvider.kt` stopInstance KDoc says "(graceful)". Hetzner's API offers `shutdown` (ACPI, graceful).

**What/Failure:** User taps Stop expecting the confirmed-dialog graceful stop; the guest is power-cut mid-write — filesystem/database risk on the user's server.

**Fix:** Use `shutdown` for stopInstance; keep `power_off` for a force path if desired.

---

### [MINOR] [PLAUSIBLE] Backup export can silently succeed with no bytes written, and overwrite uses "w" not "wt"

**Where:** `app/src/main/java/io/github/tabssh/backup/BackupManager.kt:197` — `openOutputStream(outputUri)?.use { it.write(bytes) }`; null stream skips the write, method still returns success. Default mode "w" does not truncate on all providers ("wt" does), so overwriting a larger older backup can leave trailing garbage after the ZIP payload.

**Failure scenario:** A provider returns null (revoked grant) → user is told the backup succeeded, file is empty/old. Or user re-exports a smaller backup over a larger one on a provider where "w" doesn't truncate → file has trailing bytes; strict readers or the encrypted-magic path can misparse.

**Fix:** Treat null stream as failure; open with "wt".

---

### [MINOR] [CONFIRMED] Auto-backup is vestigial: preference defaults ON, scheduler is a no-op stub with zero callers

**Where:** `app/src/main/java/io/github/tabssh/utils/preferences/PreferenceManager.kt:212` (`isAutoBackupEnabled` defaults **true**); `BackupManager.kt:361-364` (`scheduleAutomaticBackup` only logs, and nothing calls it); no settings UI surfaces the toggle.

**What/Failure:** Any user (or future code) reading `isAutoBackupEnabled()` believes automatic backups are on; none ever run. Dead preference + stub in production code.

**Fix:** Implement a WorkManager backup job, or delete the stub, the preference, and its strings.

---

### [MINOR] [CONFIRMED] Backup restore rejects every prior format version, contra AI.md:1050

**Where:** `app/src/main/java/io/github/tabssh/backup/BackupValidator.kt:45-50` rejects `version != 3`; `BackupManager.kt` states no legacy read path exists. AI.md:1050 requires restore to support "at least one prior format version"; IDEA.md:109 says older-app backups "must always import into newer versions".

**Mitigant:** pre-v1, no released build ever produced version 1/2 files — but the first post-v1 format bump will strand every existing backup unless a legacy path is added then. Flagging so it's a tracked obligation, not a surprise.

**Fix:** When version 4 arrives, keep a v3 read path; consider asserting this in a test now.

---

### [MINOR] [CONFIRMED] AWS power actions return false silently on a malformed token

**Where:** `app/src/main/java/io/github/tabssh/cloud/AwsEc2Client.kt:133+` (`awsInstanceAction`) — token that doesn't split into `AKID:SECRET:REGION` returns false with no log or distinct error, showing the generic "action failed" toast.

**Fix:** Log and/or throw IllegalStateException with the format hint like `fetchInventory` does.

---

### [MINOR] [CONFIRMED] Stale/wrong comments in sync and widget code

**Where/What:**
- `app/src/main/java/io/github/tabssh/sync/data/SyncDataCollector.kt:297-300` — comment describes CloudAccount handling that no longer matches the code below it.
- `app/src/main/java/io/github/tabssh/cloud/DigitalOceanClient.kt:21-23` — KDoc claims "at most one page" is fetched; the code fully paginates via `links.pages.next`.
- `app/src/main/java/io/github/tabssh/widgets/ConnectionWidgetProvider.kt:157-158` — comment references `QuickConnectWidgetProvider`, which does not exist.

**Fix:** Correct the three comments.

---

## Verified-safe (checked, no finding)

- **SyncEncryptor** — AES-GCM, random nonce per message, PBKDF2 params sane; magic header versioned.
- **SyncBaseSnapshotStore** — snapshot only advances after a fully successful upload (`snapshotState` ordering in both sync drivers).
- **SyncWorker mutex** — non-unique "sync_on_change" one-time enqueues are safe; a process-wide mutex serializes cycles.
- **SyncLogManager** (`sync/log/SyncLogManager.kt`) — conflicts go to the dedicated SyncLog table with retention cleanup; never the app log.
- **SyncMetadataManager** — device id is SHA-256-derived and truncated; version counter and timestamps in dedicated prefs.
- **SyncModels / Conflict** — `getResolutionOptions`/preselection logic correct; `@JsonNames("dockerHosts")` keeps legacy field readable; Long-PK collision caveat is documented per AI.md §9.4.
- **applyAll hypervisor-account path** — insert-else-update, correct (the backup importer, not sync, has the ABORT bug above).
- **Applier local-only counter preservation** — snippet `usageCount` and similar local counters survive apply.
- **applyNamedPreferenceFiles** — allowlist-based, no arbitrary pref-file writes; `isSecretAliasEnabled` deny-by-default.
- **ConflictResolver** — keep-local/keep-remote/keep-both paths all correct, keep-both re-keys properly.
- **BackupValidator structure** — field validation for connections/keys/preferences/themes sound.
- **BackupManager encryption paths** — encryption-downgrade guard (146-163); encrypted restore detects `TABSSH_SYNC_V3` magic and prompts for the password.
- **DatabaseChangeObserver debounce** — 30s debounce + distinct trigger, no tight loop (coverage gap reported separately above).
- **TombstoneRecorder** — best-effort, never throws into UI flows; natural-key overloads correct (dead DOMAIN/VPS_HOST types reported above).
- **WidgetConfigActivity** — RESULT_CANCELED default, empty-state handling, updates the configured widget id immediately.
- **Widget manifest registration** — all four providers + config activity registered correctly; 4x2/4x4 are deliberate static app launchers (no `android:configure` — by design); theme colors via `?attr`.
- **HetznerClient** — pagination and auth taxonomy correct (stop semantics reported above).
- **DigitalOceanClient** — graceful `shutdown` for stop; full pagination; public-v4-then-v6 address pick.
- **VultrClient** — cursor pagination URL-encoded; `halt` is Vultr's only stop API; skips `0.0.0.0` placeholder IPs.
- **LinodeClient** — graceful shutdown; page-count pagination; private-range filtering correct.
- **AwsEc2Client SigV4** — canonical request, RFC-3986 query encoding, sorted pairs, UTC ThreadLocal formatters, key-derivation chain, NextToken pagination all correct (XML parsing reported above).
- **OciCloudClient** — hard STOP is documented as deliberate; `ociAction` rethrows CancellationException; TOFU pin persisted immediately via `onPinCaptured`; multi-field creds live only in the Keystore-bound token slot.
- **GcpComputeClient auth/signing** — RS256 JWT exchange correct; service-account JSON parsed per call, private key never logged or persisted outside Keystore.
- **AzureVmClient auth/pagination** — client-credentials exchange, `nextLink` pagination, NIC→public-IP join, 401/403 taxonomy all correct (force-restart URL and name-as-id reported above).
- **Client instance reuse for zone/RG caches** — `CloudAccountManagerActivity.kt:64-83` documents and implements one client per provider tag, so GCP/Azure caches survive between fetch and action.
- **Cloud credentials at rest** — all providers' secrets stored only via `securePasswordManager` (`cloud_token_{id}`), decrypted per call, never written to the DB or logs.

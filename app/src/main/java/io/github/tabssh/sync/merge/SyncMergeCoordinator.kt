package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.PendingSyncConflictCodec
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.data.ApplyResult
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.log.SyncLogManager
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.SyncDataPackage

/**
 * Orchestrates the full §9.6 three-way merge on top of the existing apply path.
 *
 * The four merge-tracked entity types (ConnectionProfile, StoredKey,
 * ThemeDefinition, HostKeyEntry) go through base/local/remote merge; every other
 * entity type, plus preferences, tombstones, secrets and the dashboard config,
 * keeps its existing last-write-wins behaviour via `SyncDataApplier.applyAll`.
 *
 * Hybrid rationale: `applyAll` does far more than the four merge entities
 * (natural-key dedup, group UUID remap, tombstone suppression, ~14 other tables,
 * Keystore secrets, dashboard). Replacing it wholesale with `applyMergeResult`
 * would silently drop all of that. Instead `applyAll` runs on a *remainder*
 * package with the four merge lists stripped, and the four types are reconciled
 * separately here.
 */
class SyncMergeCoordinator(private val context: Context) {

    private val database = TabSSHDatabase.getDatabase(context)
    private val applier = SyncDataApplier(context)
    private val mergeEngine = MergeEngine()
    private val syncLogManager = SyncLogManager(context)
    private val resolver = ConflictResolver(context, database, syncLogManager)
    private val snapshotStore = SyncBaseSnapshotStore(context)
    private val preferenceManager = PreferenceManager(context)
    private val pendingSyncConflictDao = database.pendingSyncConflictDao()

    /**
     * Result of a merge pass, for logging/telemetry by the caller.
     */
    data class MergeOutcome(
        val totalConflicts: Int,
        val deferredConflicts: Int
    )

    /**
     * Raised when a merge cannot complete safely. The caller must treat this as
     * a failed sync: nothing may be uploaded and the base snapshot must not
     * advance, otherwise the next sync would three-way merge against an
     * ancestor that never actually matched the local database.
     */
    class MergeAbortedException(message: String) : Exception(message)

    /**
     * Apply a downloaded remote package to the local DB via three-way merge.
     *
     * @param remote the decrypted remote sync package
     * @param password sync password; when null the base snapshot is skipped and
     *        the merge degrades to last-write-wins (behaviour is still correct,
     *        just without ancestor-aware conflict detection)
     * @param resolveConflicts foreground hook that shows the resolution dialog
     *        and returns the user's decisions. Null in headless/WorkManager mode,
     *        where a dialog cannot be shown.
     *
     * CancellationException is intentionally never caught here — structured
     * concurrency (WorkManager cancel, activity teardown) must propagate.
     */
    suspend fun merge(
        remote: SyncDataPackage,
        password: String?,
        resolveConflicts: (suspend (List<Conflict>) -> List<ConflictResolution>)? = null
    ): MergeOutcome {
        // 1. Apply everything except the four merge-tracked types. This runs the
        //    full applyAll machinery (groups + remap, tombstones, secrets,
        //    dashboard, all last-write-wins tables, preferences).
        val remainder = remote.copy(
            connections = emptyList(),
            keys = emptyList(),
            themes = emptyList(),
            hostKeys = emptyList()
        )
        // A failed remainder apply used to be discarded, so a sync in which
        // every row failed to write still reported success and still advanced
        // the base snapshot, corrupting the ancestor for every later merge.
        when (val remainderResult = applier.applyAll(remainder)) {
            is ApplyResult.Error -> throw MergeAbortedException(
                "Failed to apply remote data: ${remainderResult.message}"
            )
            is ApplyResult.Success -> Unit
        }

        // 2. Load the shared ancestor. Absent on first sync -> empty maps ->
        //    MergeEngine degrades to last-write-wins with no false conflicts.
        val base = password?.let { snapshotStore.load(it) }

        // 3. Rebuild the same group UUID remap applyAll uses, then rewrite the
        //    remote connections' groupId into local UUID space so merged rows
        //    reference the surviving local group, not the remote one.
        val groupRemap = buildGroupRemap(remote.groups)
        val remoteConnections = remote.connections.map { conn ->
            val mapped = conn.groupId?.let { groupRemap[it] ?: it }
            if (mapped != conn.groupId) conn.copy(groupId = mapped) else conn
        }

        // 4. Collect local state after the remainder apply so the merge sees the
        //    current DB (the remainder never touches these four types, but
        //    collecting afterwards keeps the ordering unambiguous).
        val localConnections = database.connectionDao().getAllConnectionsList()
        val localKeys = database.keyDao().getAllKeysList()
        val localThemes = database.themeDao().getAllThemesList()
        val localHostKeys = database.hostKeyDao().getAllHostKeysList()

        // 5. Three-way merge per entity type.
        val connResult = mergeEngine.mergeConnections(
            base?.connections?.associateBy { it.id } ?: emptyMap(),
            localConnections,
            remoteConnections
        )
        val keyResult = mergeEngine.mergeKeys(
            base?.keys?.associateBy { it.keyId } ?: emptyMap(),
            localKeys,
            remote.keys
        )
        val themeResult = mergeEngine.mergeThemes(
            base?.themes?.associateBy { it.themeId } ?: emptyMap(),
            localThemes,
            remote.themes
        )
        val hostKeyResult = mergeEngine.mergeHostKeys(
            base?.hostKeys?.associateBy { it.id } ?: emptyMap(),
            localHostKeys,
            remote.hostKeys
        )

        val conflicts = connResult.conflicts + keyResult.conflicts +
            themeResult.conflicts + hostKeyResult.conflicts

        // 6. Decide up front whether conflicts will be deferred, because that
        //    changes what may be written in this pass. MergeEngine puts the
        //    timestamp winner of a conflicted row into `updated`; applying that
        //    and *then* deferring overwrote the losing side's data before the
        //    user ever saw the conflict, so "keep local" could no longer
        //    restore what the user actually had. Defer now means defer.
        val willDefer = conflicts.isNotEmpty() &&
            resolveConflicts == null &&
            !preferenceManager.isAutoResolveConflictsEnabled()
        // Auto-resolve can only decide conflicts that are auto-resolvable AND
        // have a clear timestamp winner (autoResolveConflicts returns nothing
        // for host-key mismatches, DELETED_MODIFIED, and exact ties). Those
        // leftovers are deferred exactly like manual-mode conflicts, so they
        // must also be excluded from the apply below — otherwise the merge
        // engine's timestamp winner overwrites data the user never chose.
        val autoMode = conflicts.isNotEmpty() &&
            resolveConflicts == null &&
            preferenceManager.isAutoResolveConflictsEnabled()
        val autoUnresolvable = if (autoMode) {
            conflicts.filter { !it.autoResolvable || it.localTimestamp == it.remoteTimestamp }
        } else {
            emptyList()
        }
        val conflictedIds = if (willDefer) {
            conflicts.map { it.entityId }.toSet()
        } else {
            autoUnresolvable.map { it.entityId }.toSet()
        }

        // 7. Apply the auto-merged (non-conflicting) results. Preferences were
        //    already applied by applyAll above, so pass an empty map here.
        when (
            val mergeApply = applier.applyMergeResult(
                connResult.withoutIds(conflictedIds) { it.id },
                keyResult.withoutIds(conflictedIds) { it.keyId },
                themeResult.withoutIds(conflictedIds) { it.themeId },
                hostKeyResult.withoutIds(conflictedIds) { it.id },
                emptyMap()
            )
        ) {
            is ApplyResult.Error -> throw MergeAbortedException(
                "Failed to apply merged data: ${mergeApply.message}"
            )
            is ApplyResult.Success -> Unit
        }

        // 8. Resolve any conflicts.
        var deferredConflicts = 0
        if (conflicts.isNotEmpty()) {
            when {
                // Foreground: ask the user, then apply their explicit choices.
                resolveConflicts != null -> {
                    val resolutions = resolveConflicts(conflicts)
                    if (resolutions.isNotEmpty()) {
                        resolver.applyResolutions(resolutions)
                    }
                }
                // Headless with auto-resolve on: timestamp-based auto resolution.
                // Conflicts it cannot decide are persisted and deferred to the
                // next foreground open instead of being silently dropped.
                preferenceManager.isAutoResolveConflictsEnabled() -> {
                    val resolutions = resolver.autoResolveConflicts(conflicts)
                    if (resolutions.isNotEmpty()) {
                        resolver.applyResolutions(resolutions, auto = true)
                    }
                    if (autoUnresolvable.isNotEmpty()) {
                        deferredConflicts = autoUnresolvable.size
                        pendingSyncConflictDao.insertAll(
                            autoUnresolvable.map { PendingSyncConflictCodec.fromConflict(it) }
                        )
                        for (conflict in autoUnresolvable) {
                            syncLogManager.recordDeferred(conflict)
                        }
                    }
                }
                // Headless with auto-resolve off: never force-resolve in the
                // background. Persist each conflict durably (survives process
                // death) and defer it to the next foreground open — neither
                // side is applied, so no data is destroyed or silently kept.
                // The peer re-detects the same divergence on its side, so
                // nothing is lost, only postponed until a human can choose.
                else -> {
                    deferredConflicts = conflicts.size
                    pendingSyncConflictDao.insertAll(conflicts.map { PendingSyncConflictCodec.fromConflict(it) })
                    for (conflict in conflicts) {
                        syncLogManager.recordDeferred(conflict)
                    }
                }
            }
        }

        // 9. Persist the reconciled state as the next base snapshot — but only
        //    when nothing was deferred. A deferred conflict means the local row
        //    is deliberately still divergent from remote; snapshotting it as
        //    the common ancestor would make the next merge read "local
        //    unchanged, remote changed" and quietly apply the remote side the
        //    user was never asked about.
        if (password != null && deferredConflicts == 0) {
            val snapshot = SyncBaseSnapshot(
                connections = database.connectionDao().getAllConnectionsList(),
                keys = database.keyDao().getAllKeysList(),
                themes = database.themeDao().getAllThemesList(),
                hostKeys = database.hostKeyDao().getAllHostKeysList()
            )
            snapshotStore.save(snapshot, password)
        }

        return MergeOutcome(totalConflicts = conflicts.size, deferredConflicts = deferredConflicts)
    }

    /**
     * Match incoming remote groups to local rows by natural key (name, parent),
     * mirroring applyAll's remap so merged connections point at surviving groups.
     */
    private suspend fun buildGroupRemap(groups: List<ConnectionGroup>): Map<String, String> {
        val remap = mutableMapOf<String, String>()
        for (g in groups) {
            val existing = database.connectionGroupDao().findByNaturalKey(g.name, g.parentId, g.id)
            if (existing != null && existing.id != g.id) {
                remap[g.id] = existing.id
            }
        }
        return remap
    }
}

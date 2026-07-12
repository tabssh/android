package io.github.tabssh.sync.data

import android.content.Context
import androidx.preference.PreferenceManager as AndroidPreferenceManager
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.keys.KeyStorage
import io.github.tabssh.crypto.storage.SecurePasswordManager
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.SyncTombstone
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.models.MergeResult
import io.github.tabssh.sync.tombstone.TombstoneRecorder
import io.github.tabssh.sync.models.MergeStrategy
import io.github.tabssh.sync.models.SyncDataPackage
import io.github.tabssh.utils.logging.Logger
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Applies synced data to local database
 */
class SyncDataApplier {

    companion object {
        private const val TAG = "SyncDataApplier"
    }

    private val context: Context
    private val database: TabSSHDatabase
    private val preferenceManager: PreferenceManager

    // Credential managers — resolved from Application singleton so that
    // Keystore-backed secrets in the sync payload are restored on this device.
    private val app: TabSSHApplication?
        get() = context.applicationContext as? TabSSHApplication

    // Simple constructor for SAF sync
    constructor(context: Context) {
        this.context = context
        this.database = TabSSHDatabase.getDatabase(context)
        this.preferenceManager = PreferenceManager(context)
    }

    // Full constructor for advanced use
    constructor(
        context: Context,
        database: TabSSHDatabase,
        preferenceManager: PreferenceManager
    ) {
        this.context = context
        this.database = database
        this.preferenceManager = preferenceManager
    }

    /**
     * Apply all data from a sync package (replaces local data)
     */
    suspend fun applyAll(data: SyncDataPackage): ApplyResult = withContext(Dispatchers.IO) {
        try {
            var appliedCount = 0

            // Self-healing pass — collapse any pre-existing duplicate rows the
            // user already accumulated before this fix landed. Cheap when there
            // are none; O(n) scan when there are. Runs outside the sync
            // transaction so the cleanup commits even if the payload apply
            // fails downstream.
            val collapsedGroups = collapseExistingDuplicateGroups()
            val collapsedConns  = collapseExistingDuplicateConnections()
            if (collapsedGroups > 0 || collapsedConns > 0) {
                Logger.i(TAG, "Pre-apply dedup collapsed $collapsedGroups groups and $collapsedConns connections")
            }

            // Group UUID remap — populated as remote groups are matched to
            // local rows by (name, parent_id). Used when inserting incoming
            // connections so their group_id points at the surviving local
            // group UUID, not the remote one.
            val groupIdRemap = mutableMapOf<String, String>()

            // H6 reverse half — a local tombstone suppresses re-adding an
            // incoming row that an out-of-date peer still carries (3-device /
            // re-download resurrection). Keyed by (entityType, entityKey) ->
            // deletedAt. An incoming row strictly newer than our delete is a
            // legitimate resurrection: it proceeds and its stale tombstone is
            // cleared so we stop re-suppressing and re-propagating the delete.
            val localTombstones = database.syncTombstoneDao().getAll()
                .associate { (it.entityType to it.entityKey) to it.deletedAt }
                .toMutableMap()
            suspend fun suppressed(type: String, key: String, incomingTs: Long?): Boolean {
                val deletedAt = localTombstones[type to key] ?: return false
                if (incomingTs != null && incomingTs > deletedAt) {
                    database.syncTombstoneDao().clear(type, key)
                    localTombstones.remove(type to key)
                    return false
                }
                return true
            }

            database.withTransaction {
            // Apply groups FIRST — connection.groupId references depend on
            // the remap this loop builds.
            data.groups.forEach { g ->
                try {
                    if (suppressed(TombstoneRecorder.GROUP, g.id, g.modifiedAt)) return@forEach
                    val existing = database.connectionGroupDao()
                        .findByNaturalKey(g.name, g.parentId, g.id)
                    if (existing != null) {
                        groupIdRemap[g.id] = existing.id
                        val merged = mergeGroupFields(existing, g)
                        if (merged != existing) {
                            database.connectionGroupDao().updateGroup(merged)
                        }
                    } else {
                        database.connectionGroupDao().insertGroup(g)
                    }
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply group: ${g.name}", e)
                }
            }

            // Apply connections — natural-key match on (host, port, username)
            // collapses records that were created independently on two devices
            // with different UUIDs.
            data.connections.forEach { connection ->
                try {
                    if (suppressed(TombstoneRecorder.CONNECTION, connection.id, connection.modifiedAt)) return@forEach
                    val remappedGroupId = connection.groupId?.let { groupIdRemap[it] ?: it }
                    val incoming = if (remappedGroupId != connection.groupId)
                        connection.copy(groupId = remappedGroupId)
                    else connection
                    val existing = database.connectionDao()
                        .findDuplicate(incoming.host, incoming.port, incoming.username, incoming.id)
                    if (existing != null) {
                        val merged = mergeConnectionFields(existing, incoming)
                        database.connectionDao().updateConnection(merged)
                    } else {
                        database.connectionDao().insertConnection(incoming)
                    }
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply connection: ${connection.name}", e)
                }
            }

            // Apply keys
            data.keys.forEach { key ->
                try {
                    if (suppressed(TombstoneRecorder.KEY, key.keyId, key.modifiedAt)) return@forEach
                    database.keyDao().insertKey(key)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply key: ${key.name}", e)
                }
            }

            // Apply themes
            data.themes.forEach { theme ->
                try {
                    if (suppressed(TombstoneRecorder.THEME, theme.themeId, theme.modifiedAt)) return@forEach
                    database.themeDao().insertTheme(theme)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply theme: ${theme.name}", e)
                }
            }

            // Apply host keys
            data.hostKeys.forEach { hostKey ->
                try {
                    if (suppressed(TombstoneRecorder.HOST_KEY, hostKey.id, hostKey.modifiedAt)) return@forEach
                    database.hostKeyDao().insertHostKey(hostKey)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply host key: ${hostKey.hostname}", e)
                }
            }

            // Apply preferences
            appliedCount += applyPreferences(data.preferences)

            // Wave 5.3 — apply workspaces (last-write-wins via REPLACE).
            data.workspaces.forEach { ws ->
                try {
                    if (suppressed(TombstoneRecorder.WORKSPACE, ws.id, ws.modifiedAt)) return@forEach
                    database.workspaceDao().upsert(ws)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply workspace: ${ws.name}", e)
                }
            }

            // Wave 5.4 — snippets / identities / groups, REPLACE on PK conflict.
            data.snippets.forEach { s ->
                try {
                    if (suppressed(TombstoneRecorder.SNIPPET, s.id, s.modifiedAt)) return@forEach
                    database.snippetDao().insertSnippet(s)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply snippet: ${s.name}", e)
                }
            }
            data.identities.forEach { id ->
                try {
                    if (suppressed(TombstoneRecorder.IDENTITY, id.id, id.modifiedAt)) return@forEach
                    database.identityDao().insert(id)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply identity: ${id.name}", e)
                }
            }
            // (Groups already applied at the top of the transaction so the
            // connection loop above could see the natural-key remap.)

            // Wave 7.1 — hypervisors / trusted_certificates, REPLACE on PK conflict.
            data.hypervisors.forEach { h ->
                try {
                    if (suppressed(TombstoneRecorder.HYPERVISOR, TombstoneRecorder.naturalKey(h), null)) return@forEach
                    database.hypervisorDao().upsertForSync(h)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply hypervisor: ${h.name}", e)
                }
            }
            data.certificates.forEach { c ->
                try {
                    if (suppressed(TombstoneRecorder.CERTIFICATE, c.id, null)) return@forEach
                    database.certificateDao().insertCertificate(c)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply certificate: ${c.fingerprint}", e)
                }
            }

            // Wave 11 — macros / monitor_slots, REPLACE on PK conflict.
            data.macros.forEach { m ->
                try {
                    if (suppressed(TombstoneRecorder.MACRO, m.id, m.modifiedAt)) return@forEach
                    database.macroDao().insertMacro(m)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply macro: ${m.id}", e)
                }
            }
            data.monitorSlots.forEach { slot ->
                try {
                    if (suppressed(TombstoneRecorder.MONITOR_SLOT, slot.id, null)) return@forEach
                    database.monitorSlotDao().insertOrReplace(slot)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply monitor slot: ${slot.id}", e)
                }
            }

            // Audit 2026-05-16 — hypervisor account metadata. Password
            // remains Keystore-bound on each device, not transferred.
            // Cross-device Long-PK collision risk is the same as
            // HypervisorProfile; documented in AI.md §9.4.
            data.hypervisorAccounts.forEach { a ->
                try {
                    if (suppressed(TombstoneRecorder.HYPERVISOR_ACCOUNT, TombstoneRecorder.naturalKey(a), a.modifiedAt)) return@forEach
                    val existing = database.hypervisorAccountDao().getById(a.id)
                    if (existing == null) {
                        database.hypervisorAccountDao().insert(a)
                    } else {
                        database.hypervisorAccountDao().update(a)
                    }
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply hypervisor account: ${a.name}", e)
                }
            }

            // Wave 13 — VNC hosts (last-write-wins REPLACE on UUID PK).
            data.vncHosts.forEach { h ->
                try {
                    if (suppressed(TombstoneRecorder.VNC_HOST, h.id, h.modifiedAt)) return@forEach
                    database.vncHostDao().insert(h)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply VNC host: ${h.name}", e)
                }
            }
            // Wave 13 — VNC identity metadata (password not in row; Keystore-bound on each device).
            data.vncIdentities.forEach { vi ->
                try {
                    if (suppressed(TombstoneRecorder.VNC_IDENTITY, vi.id, vi.modifiedAt)) return@forEach
                    database.vncIdentityDao().insert(vi)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply VNC identity: ${vi.name}", e)
                }
            }

            // Wave 14 — cloud provider account metadata. Token comes through the
            // secrets mechanism (cloud_token_{id}) and is applied outside the tx.
            data.cloudAccounts.forEach { ca ->
                try {
                    if (suppressed(TombstoneRecorder.CLOUD_ACCOUNT, ca.id, ca.modifiedAt)) return@forEach
                    database.cloudAccountDao().upsert(ca)
                    appliedCount++
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply cloud account: ${ca.name}", e)
                }
            }

            } // end withTransaction

            // H6 — apply remote tombstones (delete propagation). A peer that
            // deleted a row ships its tombstone in the payload; without this
            // the upsert-union above resurrects the row on the next sync. Runs
            // after the upsert tx so a same-payload edit is already written and
            // its modifiedAt participates in the last-write-wins comparison.
            appliedCount += applyTombstones(data.tombstones)

            // Apply credentials outside the Room transaction — Keystore
            // AES-GCM encrypt/decrypt is I/O and must not run inside a DB tx.
            if (data.secrets.isNotEmpty()) {
                applySecrets(data.secrets)
            } else {
                Logger.d(TAG, "No secrets in sync payload (pre-v14 or empty device)")
            }

            // Dashboard config lives in SharedPreferences outside the Room DB.
            if (data.dashboardConfig.isNotEmpty()) {
                appliedCount += applyDashboardConfig(data.dashboardConfig)
            }

            Logger.i(TAG, "Applied $appliedCount items from sync data")
            ApplyResult.Success(appliedCount)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply sync data", e)
            ApplyResult.Error("Failed to apply sync data: ${e.message}")
        }
    }

    /**
     * H6 — apply the tombstones carried in a sync payload so remote deletes
     * propagate to this device.
     *
     * Per tombstone, the local row (resolved by its stable cross-device key)
     * is deleted UNLESS a strictly-newer local copy exists — last-write-wins:
     * a local edit made after the peer's delete keeps the row and the tombstone
     * is cleared (resurrection). Three entities carry no modifiedAt
     * (HypervisorProfile, TrustedCertificate, MonitorSlot); for those an
     * incoming tombstone wins unconditionally — documented ceiling in AI.md.
     *
     * When the delete stands, the tombstone is persisted into this device's own
     * sync_tombstones via recordIfAbsent so a third device learns of the delete
     * on the next hop — without this a tombstone dies after one propagation.
     */
    private suspend fun applyTombstones(tombstones: List<SyncTombstone>): Int {
        if (tombstones.isEmpty()) return 0
        val dao = database.syncTombstoneDao()

        // Natural-key lookup maps for the two Long-PK entities — built once and
        // only when the payload actually carries such a tombstone.
        val hypervisorsByKey =
            if (tombstones.any { it.entityType == TombstoneRecorder.HYPERVISOR })
                database.hypervisorDao().getAllList()
                    .associateBy { TombstoneRecorder.naturalKey(it) }
            else emptyMap()
        val accountsByKey =
            if (tombstones.any { it.entityType == TombstoneRecorder.HYPERVISOR_ACCOUNT })
                database.hypervisorAccountDao().getAllAccountsList()
                    .associateBy { TombstoneRecorder.naturalKey(it) }
            else emptyMap()

        var deleted = 0
        database.withTransaction {
            tombstones.forEach { t ->
                try {
                    // localWins == a strictly-newer local copy survives the
                    // delete; otherwise the row (if any) is removed here.
                    val localWins: Boolean = when (t.entityType) {
                        TombstoneRecorder.CONNECTION -> {
                            val row = database.connectionDao().getConnectionById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.connectionDao().deleteConnectionById(t.entityKey); false }
                        }
                        TombstoneRecorder.GROUP -> {
                            val row = database.connectionGroupDao().getGroupById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.connectionGroupDao().deleteGroupById(t.entityKey); false }
                        }
                        TombstoneRecorder.KEY -> {
                            val row = database.keyDao().getKeyById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.keyDao().deleteKeyById(t.entityKey); false }
                        }
                        TombstoneRecorder.THEME -> {
                            val row = database.themeDao().getThemeById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.themeDao().deleteThemeById(t.entityKey); false }
                        }
                        TombstoneRecorder.HOST_KEY -> {
                            val row = database.hostKeyDao().getHostKeyById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.hostKeyDao().deleteHostKeyById(t.entityKey); false }
                        }
                        TombstoneRecorder.WORKSPACE -> {
                            val row = database.workspaceDao().getById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.workspaceDao().deleteById(t.entityKey); false }
                        }
                        TombstoneRecorder.SNIPPET -> {
                            val row = database.snippetDao().getSnippetById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.snippetDao().deleteSnippetById(t.entityKey); false }
                        }
                        TombstoneRecorder.IDENTITY -> {
                            val row = database.identityDao().getIdentityById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.identityDao().deleteById(t.entityKey); false }
                        }
                        TombstoneRecorder.MACRO -> {
                            val row = database.macroDao().getMacroById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.macroDao().deleteMacro(row); false }
                        }
                        TombstoneRecorder.VNC_HOST -> {
                            val row = database.vncHostDao().getById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.vncHostDao().deleteById(t.entityKey); false }
                        }
                        TombstoneRecorder.VNC_IDENTITY -> {
                            val row = database.vncIdentityDao().getById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.vncIdentityDao().deleteById(t.entityKey); false }
                        }
                        TombstoneRecorder.CLOUD_ACCOUNT -> {
                            val row = database.cloudAccountDao().getById(t.entityKey)
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.cloudAccountDao().deleteById(t.entityKey); false }
                        }
                        TombstoneRecorder.HYPERVISOR_ACCOUNT -> {
                            val row = accountsByKey[t.entityKey]
                            if (row != null && row.modifiedAt > t.deletedAt) true
                            else { if (row != null) database.hypervisorAccountDao().delete(row); false }
                        }
                        // Timestamp-less entities — no last-write-wins signal, so
                        // an incoming tombstone always wins (documented ceiling).
                        TombstoneRecorder.HYPERVISOR -> {
                            val row = hypervisorsByKey[t.entityKey]
                            if (row != null) database.hypervisorDao().delete(row)
                            false
                        }
                        TombstoneRecorder.CERTIFICATE -> {
                            val row = database.certificateDao().getCertificate(t.entityKey)
                            if (row != null) database.certificateDao().deleteCertificate(row)
                            false
                        }
                        TombstoneRecorder.MONITOR_SLOT -> {
                            val row = database.monitorSlotDao().getById(t.entityKey)
                            if (row != null) database.monitorSlotDao().delete(row)
                            false
                        }
                        else -> false
                    }
                    if (localWins) {
                        // Local edit outlived the peer's delete — drop the
                        // tombstone so the surviving row is not re-deleted.
                        dao.clear(t.entityType, t.entityKey)
                    } else {
                        // Persist for transitive propagation to a third device.
                        dao.recordIfAbsent(t)
                        deleted++
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to apply tombstone ${t.entityType}/${t.entityKey}: ${e.message}")
                }
            }
        }
        if (deleted > 0) Logger.i(TAG, "Applied $deleted remote tombstone(s)")
        return deleted
    }

    /**
     * Restore Keystore-backed credentials from the sync secrets map.
     *
     * Entries whose key starts with "ssh_key_" are base64-encoded JSch bytes
     * and are stored via [KeyStorage.importKeyFromBackup]. All other entries
     * are password/token strings stored via [SecurePasswordManager.storePassword].
     */
    private suspend fun applySecrets(secrets: Map<String, String>) {
        val pm: SecurePasswordManager? = app?.securePasswordManager
        val ks: KeyStorage? = app?.keyStorage
        if (pm == null) Logger.w(TAG, "SecurePasswordManager unavailable — Keystore-backed secrets will not be restored")
        if (ks == null) Logger.w(TAG, "KeyStorage unavailable — SSH key bytes will not be restored")
        var passwordCount = 0
        var keyCount = 0
        secrets.forEach { (alias, value) ->
            if (value.isEmpty()) return@forEach
            try {
                if (alias.startsWith("ssh_key_")) {
                    if (ks != null) {
                        val keyId = alias.removePrefix("ssh_key_")
                        val bytes = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
                        ks.importKeyFromBackup(keyId, bytes)
                        keyCount++
                    }
                } else if (alias.startsWith("conn_pw_")) {
                    // Connection passwords live in PreferenceManager SharedPreferences,
                    // not SecurePasswordManager — route them to the correct store.
                    val connId = alias.removePrefix("conn_pw_")
                    preferenceManager.setConnectionPassword(connId, value)
                    passwordCount++
                } else {
                    if (pm != null) {
                        pm.storePassword(alias, value,
                            SecurePasswordManager.StorageLevel.ENCRYPTED)
                        passwordCount++
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to restore secret $alias: ${e.message}")
            }
        }
        Logger.i(TAG, "Restored $passwordCount passwords and $keyCount SSH keys from sync")
    }

    /**
     * Apply merge result to database
     */
    suspend fun applyMergeResult(
        connectionResult: MergeResult<ConnectionProfile>,
        keyResult: MergeResult<StoredKey>,
        themeResult: MergeResult<ThemeDefinition>,
        hostKeyResult: MergeResult<HostKeyEntry>,
        preferences: Map<String, JsonElement>
    ): ApplyResult = withContext(Dispatchers.IO) {
        try {
            var appliedCount = 0

            // Wrap entity writes in a single Room transaction. Without this,
            // a partial failure (DB I/O error, cancellation) midway through
            // applyConnections / applyKeys / applyThemes / applyHostKeys
            // would commit some rows and abort others, leaving the local
            // DB inconsistent with the remote sync state. Preferences live
            // in SharedPreferences (not the Room DB) so the call to
            // applyPreferences is intentionally outside the transaction.
            database.withTransaction {
                appliedCount += applyConnections(connectionResult)
                appliedCount += applyKeys(keyResult)
                appliedCount += applyThemes(themeResult)
                appliedCount += applyHostKeys(hostKeyResult)
            }
            appliedCount += applyPreferences(preferences)

            Logger.d(TAG, "Applied $appliedCount sync changes successfully")

            ApplyResult.Success(appliedCount)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply sync data", e)
            ApplyResult.Error("Failed to apply sync data: ${e.message}")
        }
    }

    /**
     * Apply connections
     */
    private suspend fun applyConnections(result: MergeResult<ConnectionProfile>): Int {
        var count = 0

        result.added.forEach { connection ->
            try {
                // Same natural-key guard as applyAll(). MergeEngine keys by
                // UUID PK, so a remote row that semantically matches a local
                // one but has a different UUID slips through as "added" — we
                // catch it here and merge instead of inserting a duplicate.
                val existing = database.connectionDao()
                    .findDuplicate(connection.host, connection.port, connection.username, connection.id)
                if (existing != null) {
                    val merged = mergeConnectionFields(existing, connection)
                    database.connectionDao().updateConnection(merged)
                    Logger.d(TAG, "Merged connection into existing: ${connection.name}")
                } else {
                    database.connectionDao().insertConnection(connection)
                    Logger.d(TAG, "Added connection: ${connection.name}")
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add connection: ${connection.name}", e)
            }
        }

        result.updated.forEach { connection ->
            try {
                database.connectionDao().updateConnection(connection)
                count++
                Logger.d(TAG, "Updated connection: ${connection.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update connection: ${connection.name}", e)
            }
        }

        result.deleted.forEach { connectionId ->
            try {
                database.connectionDao().deleteConnectionById(connectionId)
                // Clean up orphan soft-FK references left by this connection.
                database.monitorSlotDao().deleteByConnectionId(connectionId)
                database.hypervisorDao().clearLinkedConnectionId(connectionId)
                count++
                Logger.d(TAG, "Deleted connection: $connectionId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete connection: $connectionId", e)
            }
        }

        return count
    }

    /**
     * Apply keys
     */
    private suspend fun applyKeys(result: MergeResult<StoredKey>): Int {
        var count = 0

        result.added.forEach { key ->
            try {
                database.keyDao().insertKey(key)
                count++
                Logger.d(TAG, "Added key: ${key.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add key: ${key.name}", e)
            }
        }

        result.updated.forEach { key ->
            try {
                database.keyDao().updateKey(key)
                count++
                Logger.d(TAG, "Updated key: ${key.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update key: ${key.name}", e)
            }
        }

        result.deleted.forEach { keyId ->
            try {
                database.keyDao().deleteKeyById(keyId)
                count++
                Logger.d(TAG, "Deleted key: $keyId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete key: $keyId", e)
            }
        }

        return count
    }

    /**
     * Apply themes
     */
    private suspend fun applyThemes(result: MergeResult<ThemeDefinition>): Int {
        var count = 0

        result.added.forEach { theme ->
            try {
                database.themeDao().insertTheme(theme)
                count++
                Logger.d(TAG, "Added theme: ${theme.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add theme: ${theme.name}", e)
            }
        }

        result.updated.forEach { theme ->
            try {
                database.themeDao().updateTheme(theme)
                count++
                Logger.d(TAG, "Updated theme: ${theme.name}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update theme: ${theme.name}", e)
            }
        }

        result.deleted.forEach { themeId ->
            try {
                database.themeDao().deleteThemeById(themeId)
                count++
                Logger.d(TAG, "Deleted theme: $themeId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete theme: $themeId", e)
            }
        }

        return count
    }

    /**
     * Apply host keys
     */
    private suspend fun applyHostKeys(result: MergeResult<HostKeyEntry>): Int {
        var count = 0

        result.added.forEach { hostKey ->
            try {
                database.hostKeyDao().insertHostKey(hostKey)
                count++
                Logger.d(TAG, "Added host key: ${hostKey.hostname}:${hostKey.port}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add host key: ${hostKey.hostname}:${hostKey.port}", e)
            }
        }

        result.updated.forEach { hostKey ->
            try {
                database.hostKeyDao().updateHostKey(hostKey)
                count++
                Logger.d(TAG, "Updated host key: ${hostKey.hostname}:${hostKey.port}")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to update host key: ${hostKey.hostname}:${hostKey.port}", e)
            }
        }

        result.deleted.forEach { hostKeyId ->
            try {
                database.hostKeyDao().deleteHostKeyById(hostKeyId)
                count++
                Logger.d(TAG, "Deleted host key: $hostKeyId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete host key: $hostKeyId", e)
            }
        }

        return count
    }

    /**
     * Apply preferences from JsonElement map
     */
    private fun applyPreferences(preferences: Map<String, JsonElement>): Int {
        var count = 0

        try {
            val general = (preferences["general"] as? JsonObject)?.toAnyMap()
            general?.let {
                count += applyGeneralPreferences(it)
            }

            val security = (preferences["security"] as? JsonObject)?.toAnyMap()
            security?.let {
                count += applySecurityPreferences(it)
            }

            val terminal = (preferences["terminal"] as? JsonObject)?.toAnyMap()
            terminal?.let {
                count += applyTerminalPreferences(it)
            }

            val ui = (preferences["ui"] as? JsonObject)?.toAnyMap()
            ui?.let {
                count += applyUIPreferences(it)
            }

            val connection = (preferences["connection"] as? JsonObject)?.toAnyMap()
            connection?.let {
                count += applyConnectionPreferences(it)
            }

            val keyboard = (preferences["keyboard"] as? JsonObject)?.toAnyMap()
            keyboard?.let {
                count += applyKeyboardPreferences(it)
            }

            val notifications = (preferences["notifications"] as? JsonObject)?.toAnyMap()
            notifications?.let {
                count += applyNotificationPreferences(it)
            }

            val monitoring = (preferences["monitoring"] as? JsonObject)?.toAnyMap()
            monitoring?.let {
                count += applyMonitoringPreferences(it)
            }

            // Apply sync configuration (frequency, wifi-only, what-to-sync toggles).
            // Device-specific state (enabled flag, file URI, password) is intentionally
            // excluded — each device manages its own sync location independently.
            val sync = (preferences["sync"] as? JsonObject)?.toAnyMap()
            sync?.let {
                count += applySyncPreferences(it)
            }
            val multiplexer = (preferences["multiplexer"] as? JsonObject)?.toAnyMap()
            multiplexer?.let {
                count += applyMultiplexerPreferences(it)
            }
            val accessibility = (preferences["accessibility"] as? JsonObject)?.toAnyMap()
            accessibility?.let {
                count += applyAccessibilityPreferences(it)
            }
            val paste = (preferences["paste"] as? JsonObject)?.toAnyMap()
            paste?.let {
                count += applyPastePreferences(it)
            }
            val proxy = (preferences["proxy"] as? JsonObject)?.toAnyMap()
            proxy?.let {
                count += applyProxyPreferences(it)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply preferences", e)
        }

        return count
    }

    private fun applySyncPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "frequency"        -> preferenceManager.setSyncFrequency(when (value) {
                        is String -> value; is Number -> value.toString(); else -> "hourly"
                    })
                    "wifiOnly"         -> preferenceManager.setSyncWifiOnly(value as Boolean)
                    "onChangeEnabled"  -> preferenceManager.setSyncOnChangeEnabled(value as Boolean)
                    "syncConnections"  -> preferenceManager.setSyncConnectionsEnabled(value as Boolean)
                    "syncKeys"         -> preferenceManager.setSyncKeysEnabled(value as Boolean)
                    "syncIdentities"   -> preferenceManager.setSyncIdentitiesEnabled(value as Boolean)
                    "syncSnippets"     -> preferenceManager.setSyncSnippetsEnabled(value as Boolean)
                    "syncSettings"          -> preferenceManager.setSyncSettingsEnabled(value as Boolean)
                    "syncThemes"            -> preferenceManager.setSyncThemesEnabled(value as Boolean)
                    "syncHostKeys"          -> preferenceManager.setSyncHostKeysEnabled(value as Boolean)
                    "syncGroups"            -> preferenceManager.setSyncGroupsEnabled(value as Boolean)
                    "syncWorkspaces"        -> preferenceManager.setSyncWorkspacesEnabled(value as Boolean)
                    "syncMacros"            -> preferenceManager.setSyncMacrosEnabled(value as Boolean)
                    "syncMonitorSlots"      -> preferenceManager.setSyncMonitorSlotsEnabled(value as Boolean)
                    "syncHypervisors"       -> preferenceManager.setSyncHypervisorsEnabled(value as Boolean)
                    "syncHypervisorAccounts" -> preferenceManager.setSyncHypervisorAccountsEnabled(value as Boolean)
                    "syncVncHosts"          -> preferenceManager.setSyncVncHostsEnabled(value as Boolean)
                    "syncVncIdentities"     -> preferenceManager.setSyncVncIdentitiesEnabled(value as Boolean)
                    "syncCloudAccounts"     -> preferenceManager.setSyncCloudAccountsEnabled(value as Boolean)
                    "syncCertificates"      -> preferenceManager.setSyncCertificatesEnabled(value as Boolean)
                    "syncDashboard"         -> preferenceManager.setSyncDashboardEnabled(value as Boolean)
                    "autoResolve"           -> preferenceManager.setAutoResolveConflicts(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply sync preference: $key", e)
            }
        }
        return count
    }

    private fun applyMultiplexerPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        try {
            val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
            prefs.forEach { (key, value) ->
                try {
                    when (key) {
                        "gestureEnabled" -> defaultPrefs.edit()
                            .putBoolean("enable_custom_gestures", value as Boolean).apply()
                        "gestureType"    -> defaultPrefs.edit()
                            .putString("gesture_multiplexer_type", when (value) {
                                is String -> value; is Number -> value.toString(); else -> "tmux"
                            }).apply()
                        "prefixTmux"    -> preferenceManager.setMultiplexerPrefix("tmux",   when (value) {
                            is String -> value; is Number -> value.toString(); else -> "C-b"
                        })
                        "prefixScreen"  -> preferenceManager.setMultiplexerPrefix("screen", when (value) {
                            is String -> value; is Number -> value.toString(); else -> "C-a"
                        })
                        "prefixZellij"  -> preferenceManager.setMultiplexerPrefix("zellij", when (value) {
                            is String -> value; is Number -> value.toString(); else -> "C-g"
                        })
                    }
                    count++
                } catch (e: Exception) {
                    Logger.e(TAG, "Failed to apply multiplexer preference: $key", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to apply multiplexer preferences", e)
        }
        return count
    }

    private fun applyAccessibilityPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "highContrast"      -> preferenceManager.setHighContrastMode(value as Boolean)
                    "largeTouchTargets" -> preferenceManager.setLargeTouchTargets(value as Boolean)
                    "screenReader"      -> preferenceManager.setScreenReaderEnabled(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply accessibility preference: $key", e)
            }
        }
        return count
    }

    private fun applyPastePreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "service"        -> preferenceManager.setPasteService(value as String)
                    "microbinUrl"    -> preferenceManager.setPasteMicrobinUrl(value as String)
                    "lenpasteUrl"    -> preferenceManager.setPasteLenpasteUrl(value as String)
                    "stikkedUrl"     -> preferenceManager.setPasteStikkedUrl(value as String)
                    "pastebinApiKey" -> preferenceManager.setPastebinApiKey(value as String)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply paste preference: $key", e)
            }
        }
        return count
    }

    private fun applyProxyPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "enabled"     -> preferenceManager.setProxyEnabled(value as Boolean)
                    "type"        -> preferenceManager.setProxyType(value as String)
                    "host"        -> preferenceManager.setProxyHost(value as String)
                    "port"        -> preferenceManager.setProxyPort((value as Number).toInt())
                    "username"    -> preferenceManager.setProxyUsername(value as String)
                    "password"    -> preferenceManager.setProxyPassword(value as String)
                    "bypassHosts" -> {
                        val raw = when (value) {
                            is String -> value
                            is Number -> value.toString()
                            else      -> ""
                        }
                        if (raw.isNotEmpty()) {
                            val sep = if ('\n' in raw) "\n" else ","
                            preferenceManager.setProxyBypassHosts(
                                raw.split(sep).filter { it.isNotEmpty() }
                            )
                        }
                    }
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply proxy preference: $key", e)
            }
        }
        return count
    }

    /**
     * Convert JsonObject to Map<String, Any>
     */
    private fun JsonObject.toAnyMap(): Map<String, Any> {
        return this.mapValues { (_, value) ->
            when (value) {
                is JsonPrimitive -> {
                    value.booleanOrNull ?: value.intOrNull ?: value.doubleOrNull ?: value.content
                }
                is JsonObject -> value.toAnyMap()
                else -> value.toString()
            }
        }
    }

    private fun applyGeneralPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "autoBackup" -> preferenceManager.setAutoBackupEnabled(value as Boolean)
                    "backupFrequency" -> preferenceManager.setBackupFrequency(value as String)
                    "startupBehavior" -> preferenceManager.setStartupBehavior(value as String)
                    "language" -> preferenceManager.setLanguage(value as String)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply general preference: $key", e)
            }
        }
        return count
    }

    private fun applySecurityPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "passwordStorageLevel" -> {
                        val level = when (value) {
                            is Number -> value.toString()
                            is String -> value
                            else -> "encrypted"
                        }
                        preferenceManager.setPasswordStorageLevel(level)
                    }
                    "requireBiometric" -> preferenceManager.setRequireBiometricForSensitive(value as Boolean)
                    "strictHostKeyChecking" -> preferenceManager.setStrictHostKeyChecking(value as Boolean)
                    "clearClipboardTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 60
                        }
                        preferenceManager.setClearClipboardTimeout(timeout)
                    }
                    "autoLockEnabled" -> preferenceManager.setAutoLockOnBackground(value as Boolean)
                    "lockTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 300
                        }
                        preferenceManager.setAutoLockTimeout(timeout)
                    }
                    "passwordTTLHours" -> {
                        val hours = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 24
                        }
                        preferenceManager.setPasswordTTLHours(hours)
                    }
                    "preventScreenshots" -> preferenceManager.setPreventScreenshots(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply security preference: $key", e)
            }
        }
        return count
    }

    private fun applyTerminalPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "theme" -> preferenceManager.setTerminalTheme(value as String)
                    "fontSize" -> {
                        val size = when (value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloat()
                            else -> 14f
                        }
                        preferenceManager.setFontSize(size)
                    }
                    "fontFamily" -> preferenceManager.setFontFamily(value as String)
                    "cursorStyle" -> preferenceManager.setCursorStyle(value as String)
                    "cursorBlink" -> preferenceManager.setCursorBlinkEnabled(value as Boolean)
                    "scrollbackLines" -> {
                        val lines = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 1000
                        }
                        preferenceManager.setScrollbackLines(lines)
                    }
                    "terminalBell" -> preferenceManager.setBellNotificationEnabled(value as Boolean)
                    "lineSpacing" -> {
                        val spacing = when (value) {
                            is Number -> value.toFloat()
                            is String -> value.toFloat()
                            else -> 1.2f
                        }
                        preferenceManager.setLineSpacing(spacing)
                    }
                    "reverseScroll" -> preferenceManager.setReverseScrollDirection(value as Boolean)
                    "bellVibrate"   -> preferenceManager.setBellVibrate(value as Boolean)
                    "bellVisual"    -> preferenceManager.setBellVisual(value as Boolean)
                    "wordWrap"      -> preferenceManager.setWordWrap(value as Boolean)
                    "copyOnSelect"  -> preferenceManager.setCopyOnSelect(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply terminal preference: $key", e)
            }
        }
        return count
    }

    private fun applyUIPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "maxTabs" -> {
                        val tabs = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 10
                        }
                        preferenceManager.setMaxTabs(tabs)
                    }
                    "confirmTabClose" -> preferenceManager.setConfirmTabClose(value as Boolean)
                    "appTheme" -> preferenceManager.setAppTheme(value as String)
                    "dynamicColors" -> preferenceManager.setDynamicColors(value as Boolean)
                    "showFunctionKeys" -> preferenceManager.setShowFunctionKeys(value as Boolean)
                    "fullscreenMode"   -> preferenceManager.setFullscreenMode(value as Boolean)
                    "keepScreenOn"     -> preferenceManager.setKeepScreenOn(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply UI preference: $key", e)
            }
        }
        return count
    }

    private fun applyConnectionPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "defaultUsername" -> preferenceManager.setDefaultUsername(value as String)
                    "defaultPort" -> {
                        val port = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 22
                        }
                        preferenceManager.setDefaultPort(port)
                    }
                    "connectTimeout" -> {
                        val timeout = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 15
                        }
                        preferenceManager.setConnectTimeout(timeout)
                    }
                    "autoReconnect" -> preferenceManager.setAutoReconnect(value as Boolean)
                    "compression" -> preferenceManager.setCompressionEnabled(value as Boolean)
                    "serverAliveIntervalSec" -> {
                        val sec = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toInt()
                            else -> 60
                        }
                        preferenceManager.setServerAliveIntervalSec(sec)
                    }
                    "x11ForwardingDefault"   -> preferenceManager.setX11ForwardingDefault(value as Boolean)
                    "agentForwardingDefault" -> preferenceManager.setAgentForwardingDefault(value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply connection preference: $key", e)
            }
        }
        return count
    }

    private fun applyKeyboardPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "rowCount" -> {
                        val rows = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: return@forEach
                            else -> return@forEach
                        }
                        preferenceManager.setKeyboardRowCount(rows)
                    }
                    "layoutVersion" -> {
                        val version = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: return@forEach
                            else -> return@forEach
                        }
                        preferenceManager.setKeyboardLayoutVersion(version)
                    }
                    "layoutCustomized" -> preferenceManager.setKeyboardLayoutCustomized(value as Boolean)
                    "layoutJson" -> preferenceManager.setKeyboardLayoutJson(value as String)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply keyboard preference: $key", e)
            }
        }
        return count
    }

    private fun applyNotificationPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val editor = defaultPrefs.edit()
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "notifications_enabled",
                    "show_connection_notifications",
                    "show_error_notifications",
                    "show_file_transfer_notifications",
                    "notification_vibrate" -> editor.putBoolean(key, value as Boolean)
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply notification preference: $key", e)
            }
        }
        editor.apply()
        return count
    }

    private fun applyMonitoringPreferences(prefs: Map<String, Any>): Int {
        var count = 0
        val defaultPrefs = AndroidPreferenceManager.getDefaultSharedPreferences(context)
        val editor = defaultPrefs.edit()
        prefs.forEach { (key, value) ->
            try {
                when (key) {
                    "monitoring_enabled",
                    "monitoring_run_in_battery_saver",
                    "monitoring_notify_down",
                    "monitoring_notify_recovery" -> editor.putBoolean(key, value as Boolean)
                    // ListPreference value — stored as String, but toAnyMap() may have
                    // parsed a numeric string like "60" as Int. Coerce back to String.
                    "monitoring_alert_cooldown_minutes" -> editor.putString(key, when (value) {
                        is String -> value
                        is Number -> value.toInt().toString()
                        else      -> "60"
                    })
                    // SeekBarPreference values — must be stored as Int to avoid
                    // ClassCastException when the Preference UI inflates.
                    "monitoring_default_cpu_threshold",
                    "monitoring_default_memory_threshold",
                    "monitoring_default_disk_threshold" -> {
                        val intVal = when (value) {
                            is Number -> value.toInt()
                            is String -> value.toIntOrNull() ?: 0
                            else -> 0
                        }
                        editor.putInt(key, intVal)
                    }
                }
                count++
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to apply monitoring preference: $key", e)
            }
        }
        editor.apply()
        return count
    }

    /**
     * Write dashboard groups/host-membership keys into the `multi_host_dashboard`
     * SharedPreferences file.  Existing keys are overwritten (last-write-wins,
     * matching the behaviour of every other sync entity).  Returns the count of
     * keys written.
     */
    private fun applyDashboardConfig(config: Map<String, String>): Int {
        return try {
            val sp = context.getSharedPreferences("multi_host_dashboard", android.content.Context.MODE_PRIVATE)
            val editor = sp.edit()
            config.forEach { (k, v) -> editor.putString(k, v) }
            editor.apply()
            Logger.d(TAG, "Applied ${config.size} dashboard config keys")
            config.size
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to apply dashboard config: ${e.message}")
            0
        }
    }

    /**
     * Merge an incoming connection into an existing local row when the pair
     * matches by (host, port, username). Rules:
     *   - keep the local UUID (`id`) so foreign references stay intact
     *   - sum `connectionCount` — every device tracked its own local runs
     *     independently, so the true total across devices is the sum
     *   - take max of `lastConnected`, `lastSyncedAt`, `syncVersion`, `modifiedAt`
     *   - for every other user-visible field, prefer whichever side has the
     *     newer `modifiedAt` (last-write-wins, matching MergeEngine semantics)
     */
    private fun mergeConnectionFields(local: ConnectionProfile, remote: ConnectionProfile): ConnectionProfile {
        val remoteNewer = remote.modifiedAt > local.modifiedAt
        val winner = if (remoteNewer) remote else local
        return winner.copy(
            id              = local.id,
            host            = local.host,
            port            = local.port,
            username        = local.username,
            connectionCount = local.connectionCount + remote.connectionCount,
            lastConnected   = maxOf(local.lastConnected,  remote.lastConnected),
            lastSyncedAt    = maxOf(local.lastSyncedAt,   remote.lastSyncedAt),
            syncVersion     = maxOf(local.syncVersion,    remote.syncVersion),
            modifiedAt      = maxOf(local.modifiedAt,     remote.modifiedAt),
            createdAt       = minOf(local.createdAt,      remote.createdAt),
        )
    }

    /**
     * Merge two ConnectionGroup rows that matched by (name, parent_id).
     * Local UUID survives; sortOrder/isCollapsed/icon/color follow the
     * newer `modifiedAt`. createdAt keeps the earlier value.
     */
    private fun mergeGroupFields(local: ConnectionGroup, remote: ConnectionGroup): ConnectionGroup {
        val remoteNewer = remote.modifiedAt > local.modifiedAt
        val winner = if (remoteNewer) remote else local
        return winner.copy(
            id           = local.id,
            name         = local.name,
            parentId     = local.parentId,
            createdAt    = minOf(local.createdAt, remote.createdAt),
            modifiedAt   = maxOf(local.modifiedAt, remote.modifiedAt),
            lastSyncedAt = maxOf(local.lastSyncedAt, remote.lastSyncedAt),
            syncVersion  = maxOf(local.syncVersion, remote.syncVersion),
        )
    }

    /**
     * One-time in-place cleanup of any duplicate connection rows already
     * present in the local DB from a pre-fix sync sweep. Groups rows by
     * (lower(host), port, lower(username)), keeps the row with the smallest
     * `createdAt` (earliest survivor — its UUID is what foreign refs likely
     * point at), aggregates counters into it, deletes the others.
     *
     * Returns the number of rows removed. Called at the top of every
     * applyAll() — cheap when there are no duplicates, self-healing when
     * the user hasn't opened a sync sweep since the pre-fix corruption.
     */
    private suspend fun collapseExistingDuplicateConnections(): Int {
        var removed = 0
        val all = database.connectionDao().getAllConnectionsList()
        val groups = all.groupBy { Triple(it.host.trim().lowercase(), it.port, it.username.trim().lowercase()) }
        database.withTransaction {
            groups.values.forEach { rows ->
                if (rows.size <= 1) return@forEach
                val survivor = rows.minByOrNull { it.createdAt } ?: rows.first()
                val duplicates = rows.filter { it.id != survivor.id }
                var merged = survivor
                duplicates.forEach { dup -> merged = mergeConnectionFields(merged, dup) }
                if (merged != survivor) database.connectionDao().updateConnection(merged)
                duplicates.forEach { dup ->
                    database.connectionDao().deleteConnectionById(dup.id)
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * Same shape as [collapseExistingDuplicateConnections] but for
     * ConnectionGroup, keyed on (lower(trim(name)), parent_id). Before
     * deleting a duplicate group we repoint any connections whose
     * `group_id` points at that duplicate UUID over to the survivor's
     * UUID so no connection ends up orphaned.
     */
    private suspend fun collapseExistingDuplicateGroups(): Int {
        var removed = 0
        val all = database.connectionGroupDao().getAllGroupsList()
        val groups = all.groupBy { it.name.trim().lowercase() to (it.parentId ?: "") }
        database.withTransaction {
            groups.values.forEach { rows ->
                if (rows.size <= 1) return@forEach
                val survivor = rows.minByOrNull { it.createdAt } ?: rows.first()
                val duplicates = rows.filter { it.id != survivor.id }
                var merged = survivor
                duplicates.forEach { dup -> merged = mergeGroupFields(merged, dup) }
                if (merged != survivor) database.connectionGroupDao().updateGroup(merged)
                duplicates.forEach { dup ->
                    database.connectionGroupDao().repointConnectionsToGroup(dup.id, survivor.id)
                    database.connectionGroupDao().deleteGroupById(dup.id)
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * Import single connection
     */
    suspend fun importConnection(connection: ConnectionProfile, strategy: MergeStrategy): Boolean {
        return try {
            when (strategy) {
                MergeStrategy.KEEP_LOCAL -> false
                MergeStrategy.KEEP_REMOTE, MergeStrategy.MERGE -> {
                    database.connectionDao().insertConnection(connection)
                    Logger.d(TAG, "Imported connection: ${connection.name}")
                    true
                }
                MergeStrategy.KEEP_BOTH -> {
                    val newConnection = connection.copy(id = java.util.UUID.randomUUID().toString())
                    database.connectionDao().insertConnection(newConnection)
                    Logger.d(TAG, "Imported duplicate connection: ${newConnection.name}")
                    true
                }
                MergeStrategy.SKIP -> false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import connection", e)
            false
        }
    }

    /**
     * Import single key
     */
    suspend fun importKey(key: StoredKey, strategy: MergeStrategy): Boolean {
        return try {
            when (strategy) {
                MergeStrategy.KEEP_LOCAL -> false
                MergeStrategy.KEEP_REMOTE, MergeStrategy.MERGE -> {
                    database.keyDao().insertKey(key)
                    Logger.d(TAG, "Imported key: ${key.name}")
                    true
                }
                MergeStrategy.KEEP_BOTH -> {
                    val newKey = key.copy(keyId = java.util.UUID.randomUUID().toString())
                    database.keyDao().insertKey(newKey)
                    Logger.d(TAG, "Imported duplicate key: ${newKey.name}")
                    true
                }
                MergeStrategy.SKIP -> false
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to import key", e)
            false
        }
    }
}

/**
 * Result of apply operation
 */
sealed class ApplyResult {
    data class Success(val appliedCount: Int) : ApplyResult()
    data class Error(val message: String) : ApplyResult()
}

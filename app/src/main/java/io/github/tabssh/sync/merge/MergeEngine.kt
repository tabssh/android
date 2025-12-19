package io.github.tabssh.sync.merge

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.sync.models.*
import io.github.tabssh.utils.logging.Logger
import java.security.MessageDigest

/**
 * Implements intelligent 3-way merge algorithm for sync data
 */
class MergeEngine {

    companion object {
        private const val TAG = "MergeEngine"
    }

    /**
     * Merge connection profiles
     */
    fun mergeConnections(
        base: Map<String, ConnectionProfile>,
        local: List<ConnectionProfile>,
        remote: List<ConnectionProfile>
    ): MergeResult<ConnectionProfile> {
        val merged = mutableListOf<ConnectionProfile>()
        val conflicts = mutableListOf<Conflict>()
        val deleted = mutableListOf<String>()
        val added = mutableListOf<ConnectionProfile>()
        val updated = mutableListOf<ConnectionProfile>()

        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val allIds = (localMap.keys + remoteMap.keys).toSet()

        for (id in allIds) {
            val baseConn = base[id]
            val localConn = localMap[id]
            val remoteConn = remoteMap[id]

            when {
                localConn != null && remoteConn != null -> {
                    val mergedConn = mergeConnection(baseConn, localConn, remoteConn, conflicts)
                    merged.add(mergedConn)
                    if (baseConn == null) {
                        added.add(mergedConn)
                    } else if (mergedConn != baseConn) {
                        updated.add(mergedConn)
                    }
                }
                localConn != null && remoteConn == null -> {
                    if (baseConn != null) {
                        conflicts.add(createDeletedModifiedConflict("connection", id, localConn, true))
                        merged.add(localConn)
                    } else {
                        merged.add(localConn)
                        added.add(localConn)
                    }
                }
                localConn == null && remoteConn != null -> {
                    if (baseConn != null) {
                        conflicts.add(createDeletedModifiedConflict("connection", id, remoteConn, false))
                        merged.add(remoteConn)
                    } else {
                        merged.add(remoteConn)
                        added.add(remoteConn)
                    }
                }
                else -> {
                    if (baseConn != null) {
                        deleted.add(id)
                    }
                }
            }
        }

        Logger.d(TAG, "Merged ${merged.size} connections, ${conflicts.size} conflicts")
        return MergeResult(merged, conflicts, deleted, added, updated)
    }

    /**
     * Merge single connection
     */
    private fun mergeConnection(
        base: ConnectionProfile?,
        local: ConnectionProfile,
        remote: ConnectionProfile,
        conflicts: MutableList<Conflict>
    ): ConnectionProfile {
        if (base == null) {
            return if (local.modifiedAt > remote.modifiedAt) local else remote
        }

        if (local == remote) return local

        val fieldConflicts = detectConnectionConflicts(base, local, remote)

        if (fieldConflicts.isEmpty()) {
            return mergeConnectionFields(local, remote)
        }

        fieldConflicts.forEach { fieldConflict ->
            conflicts.add(
                Conflict(
                    entityType = "connection",
                    entityId = local.id,
                    conflictType = ConflictType.FIELD_MODIFIED_BOTH_SIDES,
                    field = fieldConflict.field,
                    localValue = fieldConflict.localValue,
                    remoteValue = fieldConflict.remoteValue,
                    baseValue = fieldConflict.baseValue,
                    localTimestamp = local.modifiedAt,
                    remoteTimestamp = remote.modifiedAt,
                    autoResolvable = local.modifiedAt != remote.modifiedAt,
                    description = "Field '${fieldConflict.field}' modified on both devices"
                )
            )
        }

        return if (local.modifiedAt >= remote.modifiedAt) local else remote
    }

    /**
     * Detect conflicts in connection fields
     */
    private fun detectConnectionConflicts(
        base: ConnectionProfile,
        local: ConnectionProfile,
        remote: ConnectionProfile
    ): List<FieldConflict> {
        val conflicts = mutableListOf<FieldConflict>()

        if (base.name != local.name && base.name != remote.name && local.name != remote.name) {
            conflicts.add(FieldConflict("name", base.name, local.name, remote.name,
                local.modifiedAt, remote.modifiedAt))
        }

        if (base.host != local.host && base.host != remote.host && local.host != remote.host) {
            conflicts.add(FieldConflict("host", base.host, local.host, remote.host,
                local.modifiedAt, remote.modifiedAt))
        }

        if (base.port != local.port && base.port != remote.port && local.port != remote.port) {
            conflicts.add(FieldConflict("port", base.port, local.port, remote.port,
                local.modifiedAt, remote.modifiedAt))
        }

        if (base.username != local.username && base.username != remote.username && local.username != remote.username) {
            conflicts.add(FieldConflict("username", base.username, local.username, remote.username,
                local.modifiedAt, remote.modifiedAt))
        }

        if (base.authType != local.authType && base.authType != remote.authType && local.authType != remote.authType) {
            conflicts.add(FieldConflict("authType", base.authType, local.authType, remote.authType,
                local.modifiedAt, remote.modifiedAt))
        }

        return conflicts
    }

    /**
     * Merge connection fields without conflicts
     */
    private fun mergeConnectionFields(
        local: ConnectionProfile,
        remote: ConnectionProfile
    ): ConnectionProfile {
        return local.copy(
            connectionCount = local.connectionCount + remote.connectionCount,
            lastConnected = maxOf(local.lastConnected, remote.lastConnected),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt),
            syncVersion = maxOf(local.syncVersion, remote.syncVersion)
        )
    }

    /**
     * Merge SSH keys
     */
    fun mergeKeys(
        base: Map<String, StoredKey>,
        local: List<StoredKey>,
        remote: List<StoredKey>
    ): MergeResult<StoredKey> {
        val merged = mutableListOf<StoredKey>()
        val conflicts = mutableListOf<Conflict>()
        val deleted = mutableListOf<String>()
        val added = mutableListOf<StoredKey>()
        val updated = mutableListOf<StoredKey>()

        val localMap = local.associateBy { it.keyId }
        val remoteMap = remote.associateBy { it.keyId }
        val allIds = (localMap.keys + remoteMap.keys).toSet()

        for (id in allIds) {
            val baseKey = base[id]
            val localKey = localMap[id]
            val remoteKey = remoteMap[id]

            when {
                localKey != null && remoteKey != null -> {
                    val mergedKey = mergeKey(baseKey, localKey, remoteKey, conflicts)
                    merged.add(mergedKey)
                    if (baseKey == null) {
                        added.add(mergedKey)
                    } else if (mergedKey != baseKey) {
                        updated.add(mergedKey)
                    }
                }
                localKey != null && remoteKey == null -> {
                    if (baseKey != null) {
                        conflicts.add(createDeletedModifiedConflict("key", id, localKey, true))
                    }
                    merged.add(localKey)
                    if (baseKey == null) added.add(localKey)
                }
                localKey == null && remoteKey != null -> {
                    if (baseKey != null) {
                        conflicts.add(createDeletedModifiedConflict("key", id, remoteKey, false))
                    }
                    merged.add(remoteKey)
                    if (baseKey == null) added.add(remoteKey)
                }
                else -> {
                    if (baseKey != null) deleted.add(id)
                }
            }
        }

        Logger.d(TAG, "Merged ${merged.size} keys, ${conflicts.size} conflicts")
        return MergeResult(merged, conflicts, deleted, added, updated)
    }

    /**
     * Merge single key
     */
    private fun mergeKey(
        base: StoredKey?,
        local: StoredKey,
        remote: StoredKey,
        conflicts: MutableList<Conflict>
    ): StoredKey {
        if (base == null) {
            return if (local.modifiedAt > remote.modifiedAt) local else remote
        }

        if (local == remote) return local

        if (local.fingerprint != remote.fingerprint) {
            conflicts.add(
                Conflict(
                    entityType = "key",
                    entityId = local.keyId,
                    conflictType = ConflictType.FIELD_MODIFIED_BOTH_SIDES,
                    field = "fingerprint",
                    localValue = local.fingerprint,
                    remoteValue = remote.fingerprint,
                    baseValue = base.fingerprint,
                    localTimestamp = local.modifiedAt,
                    remoteTimestamp = remote.modifiedAt,
                    autoResolvable = false,
                    description = "Key fingerprint mismatch - different keys"
                )
            )
        }

        return if (local.modifiedAt >= remote.modifiedAt) local else remote
    }

    /**
     * Merge themes
     */
    fun mergeThemes(
        base: Map<String, ThemeDefinition>,
        local: List<ThemeDefinition>,
        remote: List<ThemeDefinition>
    ): MergeResult<ThemeDefinition> {
        val merged = mutableListOf<ThemeDefinition>()
        val conflicts = mutableListOf<Conflict>()
        val deleted = mutableListOf<String>()
        val added = mutableListOf<ThemeDefinition>()
        val updated = mutableListOf<ThemeDefinition>()

        val localMap = local.associateBy { it.themeId }
        val remoteMap = remote.associateBy { it.themeId }
        val allIds = (localMap.keys + remoteMap.keys).toSet()

        for (id in allIds) {
            val baseTheme = base[id]
            val localTheme = localMap[id]
            val remoteTheme = remoteMap[id]

            when {
                localTheme != null && remoteTheme != null -> {
                    val mergedTheme = mergeTheme(baseTheme, localTheme, remoteTheme, conflicts)
                    merged.add(mergedTheme)
                    if (baseTheme == null) {
                        added.add(mergedTheme)
                    } else if (mergedTheme != baseTheme) {
                        updated.add(mergedTheme)
                    }
                }
                localTheme != null && remoteTheme == null -> {
                    merged.add(localTheme)
                    if (baseTheme == null) added.add(localTheme)
                    else if (baseTheme != localTheme) updated.add(localTheme)
                }
                localTheme == null && remoteTheme != null -> {
                    merged.add(remoteTheme)
                    if (baseTheme == null) added.add(remoteTheme)
                    else if (baseTheme != remoteTheme) updated.add(remoteTheme)
                }
                else -> {
                    if (baseTheme != null) deleted.add(id)
                }
            }
        }

        Logger.d(TAG, "Merged ${merged.size} themes, ${conflicts.size} conflicts")
        return MergeResult(merged, conflicts, deleted, added, updated)
    }

    /**
     * Merge single theme
     */
    private fun mergeTheme(
        base: ThemeDefinition?,
        local: ThemeDefinition,
        remote: ThemeDefinition,
        conflicts: MutableList<Conflict>
    ): ThemeDefinition {
        if (base == null) {
            return if (local.modifiedAt > remote.modifiedAt) local else remote
        }

        if (local == remote) return local

        return local.copy(
            usageCount = local.usageCount + remote.usageCount,
            lastModified = maxOf(local.lastModified, remote.lastModified),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt)
        )
    }

    /**
     * Merge host keys
     */
    fun mergeHostKeys(
        base: Map<String, HostKeyEntry>,
        local: List<HostKeyEntry>,
        remote: List<HostKeyEntry>
    ): MergeResult<HostKeyEntry> {
        val merged = mutableListOf<HostKeyEntry>()
        val conflicts = mutableListOf<Conflict>()
        val deleted = mutableListOf<String>()
        val added = mutableListOf<HostKeyEntry>()
        val updated = mutableListOf<HostKeyEntry>()

        val localMap = local.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val allIds = (localMap.keys + remoteMap.keys).toSet()

        for (id in allIds) {
            val baseHostKey = base[id]
            val localHostKey = localMap[id]
            val remoteHostKey = remoteMap[id]

            when {
                localHostKey != null && remoteHostKey != null -> {
                    val mergedHostKey = mergeHostKey(baseHostKey, localHostKey, remoteHostKey, conflicts)
                    merged.add(mergedHostKey)
                    if (baseHostKey == null) {
                        added.add(mergedHostKey)
                    } else if (mergedHostKey != baseHostKey) {
                        updated.add(mergedHostKey)
                    }
                }
                localHostKey != null && remoteHostKey == null -> {
                    merged.add(localHostKey)
                    if (baseHostKey == null) added.add(localHostKey)
                }
                localHostKey == null && remoteHostKey != null -> {
                    merged.add(remoteHostKey)
                    if (baseHostKey == null) added.add(remoteHostKey)
                }
                else -> {
                    if (baseHostKey != null) deleted.add(id)
                }
            }
        }

        Logger.d(TAG, "Merged ${merged.size} host keys, ${conflicts.size} conflicts")
        return MergeResult(merged, conflicts, deleted, added, updated)
    }

    /**
     * Merge single host key
     */
    private fun mergeHostKey(
        base: HostKeyEntry?,
        local: HostKeyEntry,
        remote: HostKeyEntry,
        conflicts: MutableList<Conflict>
    ): HostKeyEntry {
        if (base == null) {
            return if (local.modifiedAt > remote.modifiedAt) local else remote
        }

        if (local == remote) return local

        if (local.fingerprint != remote.fingerprint) {
            conflicts.add(
                Conflict(
                    entityType = "host_key",
                    entityId = local.id,
                    conflictType = ConflictType.FIELD_MODIFIED_BOTH_SIDES,
                    field = "fingerprint",
                    localValue = local.fingerprint,
                    remoteValue = remote.fingerprint,
                    baseValue = base.fingerprint,
                    localTimestamp = local.modifiedAt,
                    remoteTimestamp = remote.modifiedAt,
                    autoResolvable = false,
                    description = "Host key changed - potential MITM attack"
                )
            )
        }

        return local.copy(
            firstSeen = minOf(local.firstSeen, remote.firstSeen),
            lastVerified = maxOf(local.lastVerified, remote.lastVerified),
            modifiedAt = maxOf(local.modifiedAt, remote.modifiedAt)
        )
    }

    /**
     * Merge preferences
     */
    fun mergePreferences(
        base: Map<String, Any>,
        local: Map<String, Any>,
        remote: Map<String, Any>
    ): MergeResult<Map<String, Any>> {
        val merged = mutableMapOf<String, Any>()
        val conflicts = mutableListOf<Conflict>()

        val allKeys = (local.keys + remote.keys).toSet()

        for (key in allKeys) {
            val baseValue = base[key]
            val localValue = local[key]
            val remoteValue = remote[key]

            when {
                localValue != null && remoteValue != null -> {
                    if (localValue == remoteValue) {
                        merged[key] = localValue
                    } else {
                        conflicts.add(
                            Conflict(
                                entityType = "preference",
                                entityId = key,
                                conflictType = ConflictType.PREFERENCE_DIVERGED,
                                field = key,
                                localValue = localValue,
                                remoteValue = remoteValue,
                                baseValue = baseValue,
                                autoResolvable = true,
                                description = "Preference '$key' has different values"
                            )
                        )
                        merged[key] = remoteValue
                    }
                }
                localValue != null -> merged[key] = localValue
                remoteValue != null -> merged[key] = remoteValue
            }
        }

        Logger.d(TAG, "Merged ${merged.size} preferences, ${conflicts.size} conflicts")
        return MergeResult(listOf(merged), conflicts)
    }

    /**
     * Create deleted/modified conflict
     */
    private fun <T> createDeletedModifiedConflict(
        entityType: String,
        entityId: String,
        entity: T,
        deletedRemotely: Boolean
    ): Conflict {
        return Conflict(
            entityType = entityType,
            entityId = entityId,
            conflictType = ConflictType.DELETED_MODIFIED,
            localValue = if (deletedRemotely) entity else null,
            remoteValue = if (deletedRemotely) null else entity,
            autoResolvable = false,
            description = if (deletedRemotely) {
                "Deleted on remote but modified locally"
            } else {
                "Deleted locally but modified on remote"
            }
        )
    }

    /**
     * Calculate hash for entity
     */
    fun calculateHash(data: Any): String {
        val dataString = data.toString()
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(dataString.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to calculate hash", e)
            ""
        }
    }
}

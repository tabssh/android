package io.github.tabssh.sync.models

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition

/**
 * Result of a sync operation
 */
data class SyncResult(
    val success: Boolean,
    val message: String,
    val conflicts: List<Conflict> = emptyList(),
    val syncedItemCounts: SyncItemCounts? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val error: Exception? = null
) {
    fun hasConflicts(): Boolean = conflicts.isNotEmpty()
}

/**
 * Counts of synced items
 */
data class SyncItemCounts(
    val connections: Int = 0,
    val keys: Int = 0,
    val themes: Int = 0,
    val preferences: Int = 0,
    val hostKeys: Int = 0
) {
    fun total(): Int = connections + keys + themes + preferences + hostKeys
}

/**
 * Metadata for a sync operation
 */
data class SyncMetadata(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val appVersion: String,
    val syncTimestamp: Long,
    val syncVersion: Long,
    val formatVersion: Int = 2,
    val encryptionVersion: Int = 1,
    val itemCounts: SyncItemCounts
)

/**
 * Complete sync file data structure
 */
data class SyncFileData(
    val metadata: SyncMetadata,
    val connections: List<ConnectionProfile>,
    val keys: List<StoredKey>,
    val themes: List<ThemeDefinition>,
    val preferences: Map<String, Any>,
    val hostKeys: List<HostKeyEntry>,
    val syncBase: SyncBase
)

/**
 * Base snapshot for 3-way merge
 */
data class SyncBase(
    val connectionHashes: Map<String, String> = emptyMap(),
    val keyHashes: Map<String, String> = emptyMap(),
    val themeHashes: Map<String, String> = emptyMap(),
    val hostKeyHashes: Map<String, String> = emptyMap()
)

/**
 * Package of data to sync
 */
data class SyncDataPackage(
    val connections: List<ConnectionProfile> = emptyList(),
    val keys: List<StoredKey> = emptyList(),
    val themes: List<ThemeDefinition> = emptyList(),
    val preferences: Map<String, Any> = emptyMap(),
    val hostKeys: List<HostKeyEntry> = emptyList(),
    val metadata: SyncMetadata
)

/**
 * Encrypted data container
 */
data class EncryptedData(
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val salt: ByteArray,
    val authTag: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptedData

        if (!ciphertext.contentEquals(other.ciphertext)) return false
        if (!iv.contentEquals(other.iv)) return false
        if (!salt.contentEquals(other.salt)) return false
        if (authTag != null) {
            if (other.authTag == null) return false
            if (!authTag.contentEquals(other.authTag)) return false
        } else if (other.authTag != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + iv.contentHashCode()
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + (authTag?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Sync trigger types
 */
enum class SyncTrigger {
    MANUAL,
    ON_LAUNCH,
    ON_CHANGE,
    SCHEDULED
}

/**
 * Merge strategy options
 */
enum class MergeStrategy {
    MERGE,
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH,
    SKIP
}

/**
 * Conflict types
 */
enum class ConflictType {
    FIELD_MODIFIED_BOTH_SIDES,
    DELETED_MODIFIED,
    CREATED_DUPLICATE,
    PREFERENCE_DIVERGED
}

/**
 * A conflict between local and remote data
 */
data class Conflict(
    val entityType: String,
    val entityId: String,
    val conflictType: ConflictType,
    val field: String? = null,
    val localValue: Any? = null,
    val remoteValue: Any? = null,
    val baseValue: Any? = null,
    val localTimestamp: Long = 0,
    val remoteTimestamp: Long = 0,
    val autoResolvable: Boolean = false,
    val description: String = ""
) {
    fun getResolutionOptions(): List<ConflictResolutionOption> {
        return when (conflictType) {
            ConflictType.FIELD_MODIFIED_BOTH_SIDES -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE,
                ConflictResolutionOption.SKIP
            )
            ConflictType.DELETED_MODIFIED -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE
            )
            ConflictType.CREATED_DUPLICATE -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE,
                ConflictResolutionOption.KEEP_BOTH
            )
            ConflictType.PREFERENCE_DIVERGED -> listOf(
                ConflictResolutionOption.KEEP_LOCAL,
                ConflictResolutionOption.KEEP_REMOTE
            )
        }
    }
}

/**
 * Conflict resolution options
 */
enum class ConflictResolutionOption {
    KEEP_LOCAL,
    KEEP_REMOTE,
    KEEP_BOTH,
    SKIP
}

/**
 * Result of a conflict resolution
 */
data class ConflictResolution(
    val conflict: Conflict,
    val resolution: ConflictResolutionOption,
    val applyToAll: Boolean = false
)

/**
 * Result of a merge operation
 */
data class MergeResult<T>(
    val merged: List<T>,
    val conflicts: List<Conflict> = emptyList(),
    val deleted: List<String> = emptyList(),
    val added: List<T> = emptyList(),
    val updated: List<T> = emptyList()
) {
    fun hasConflicts(): Boolean = conflicts.isNotEmpty()

    fun isSuccessful(): Boolean = conflicts.isEmpty()
}

/**
 * Sync status information
 */
data class SyncStatus(
    val enabled: Boolean,
    val lastSyncTime: Long,
    val lastSyncResult: SyncResult?,
    val isSyncing: Boolean,
    val accountEmail: String?,
    val deviceId: String,
    val pendingConflictCount: Int = 0
) {
    fun hasNeverSynced(): Boolean = lastSyncTime == 0L

    fun getLastSyncDescription(): String {
        if (hasNeverSynced()) return "Never synced"

        val now = System.currentTimeMillis()
        val diff = now - lastSyncTime

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minutes ago"
            diff < 86400_000 -> "${diff / 3600_000} hours ago"
            diff < 604800_000 -> "${diff / 86400_000} days ago"
            else -> "${diff / 604800_000} weeks ago"
        }
    }
}

/**
 * Complete merge result combining all entity types
 */
data class CompleteMergeResult(
    val connectionResult: MergeResult<io.github.tabssh.storage.database.entities.ConnectionProfile>,
    val keyResult: MergeResult<io.github.tabssh.storage.database.entities.StoredKey>,
    val themeResult: MergeResult<io.github.tabssh.storage.database.entities.ThemeDefinition>,
    val hostKeyResult: MergeResult<io.github.tabssh.storage.database.entities.HostKeyEntry>,
    val preferences: Map<String, Any>,
    val conflicts: List<Conflict>
) {
    fun hasConflicts(): Boolean = conflicts.isNotEmpty()
}

/**
 * Remote sync file information
 */
data class RemoteSyncFile(
    val fileId: String,
    val fileName: String,
    val deviceId: String,
    val modifiedTime: Long,
    val size: Long
)

/**
 * Sync progress information
 */
data class SyncProgress(
    val stage: SyncStage,
    val currentItem: Int = 0,
    val totalItems: Int = 0,
    val message: String = ""
) {
    fun getPercentage(): Int {
        return if (totalItems > 0) {
            ((currentItem.toFloat() / totalItems) * 100).toInt()
        } else {
            0
        }
    }
}

/**
 * Stages of sync operation
 */
enum class SyncStage {
    IDLE,
    AUTHENTICATING,
    COLLECTING_DATA,
    ENCRYPTING,
    UPLOADING,
    DOWNLOADING,
    DECRYPTING,
    MERGING,
    RESOLVING_CONFLICTS,
    APPLYING_CHANGES,
    COMPLETED,
    FAILED
}

/**
 * Field-level conflict information
 */
data class FieldConflict(
    val field: String,
    val baseValue: Any?,
    val localValue: Any?,
    val remoteValue: Any?,
    val localTimestamp: Long = 0,
    val remoteTimestamp: Long = 0
)

/**
 * Sync configuration
 */
data class SyncConfiguration(
    val enabled: Boolean = false,
    val wifiOnly: Boolean = true,
    val syncFrequencyMinutes: Int = 60,
    val syncConnections: Boolean = true,
    val syncKeys: Boolean = true,
    val syncSettings: Boolean = true,
    val syncThemes: Boolean = true,
    val autoResolveConflicts: Boolean = true,
    val requiresCharging: Boolean = false,
    val batteryNotLowRequired: Boolean = true
)

/**
 * Device information for sync
 */
data class DeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String
)

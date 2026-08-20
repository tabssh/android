package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictType
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Durable record of a conflict that a headless (WorkManager) sync deferred
 * instead of resolving. Persisted so the exact conflict — not just a
 * "something is pending" boolean — survives process death and can be shown
 * to the user via [io.github.tabssh.ui.dialogs.ConflictResolutionDialog] the
 * next time the app is foregrounded.
 *
 * Only entity types with real 3-way merge (connection/key/theme/host_key)
 * ever produce a [Conflict], so [localEntityJson]/[remoteEntityJson] are
 * always one of [ConnectionProfile], [StoredKey], [ThemeDefinition], or
 * [HostKeyEntry] — none of which carry secret columns (secrets are
 * Keystore-only), so it is safe to persist their full JSON here.
 */
@Entity(
    tableName = "pending_sync_conflicts",
    indices = [Index("entity_type"), Index("created_at")]
)
data class PendingSyncConflict(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "entity_type")
    val entityType: String,

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "conflict_type")
    val conflictType: String,

    @ColumnInfo(name = "field")
    val field: String? = null,

    /** Display-only rendering of the conflicting local value. */
    @ColumnInfo(name = "local_value_text")
    val localValueText: String? = null,

    /** Display-only rendering of the conflicting remote value. */
    @ColumnInfo(name = "remote_value_text")
    val remoteValueText: String? = null,

    /** JSON of the full local entity, typed per [entityType] — see
     *  [PendingSyncConflictCodec]. Used to reconstruct [Conflict.localEntity]
     *  for [io.github.tabssh.sync.merge.ConflictResolver]. */
    @ColumnInfo(name = "local_entity_json")
    val localEntityJson: String? = null,

    /** JSON of the full remote entity — see [localEntityJson]. */
    @ColumnInfo(name = "remote_entity_json")
    val remoteEntityJson: String? = null,

    @ColumnInfo(name = "local_timestamp")
    val localTimestamp: Long = 0,

    @ColumnInfo(name = "remote_timestamp")
    val remoteTimestamp: Long = 0,

    @ColumnInfo(name = "description")
    val description: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Converts a [Conflict] to/from its durable [PendingSyncConflict] row.
 * Kept as free functions rather than methods on the entity so the Room
 * model has no compile-time dependency on the sync package's merge types.
 */
object PendingSyncConflictCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromConflict(conflict: Conflict): PendingSyncConflict = PendingSyncConflict(
        entityType = conflict.entityType,
        entityId = conflict.entityId,
        conflictType = conflict.conflictType.name,
        field = conflict.field,
        localValueText = conflict.localValue?.toString(),
        remoteValueText = conflict.remoteValue?.toString(),
        localEntityJson = encodeEntity(conflict.entityType, conflict.localEntity),
        remoteEntityJson = encodeEntity(conflict.entityType, conflict.remoteEntity),
        localTimestamp = conflict.localTimestamp,
        remoteTimestamp = conflict.remoteTimestamp,
        description = conflict.description
    )

    fun toConflict(row: PendingSyncConflict): Conflict = Conflict(
        entityType = row.entityType,
        entityId = row.entityId,
        conflictType = ConflictType.valueOf(row.conflictType),
        field = row.field,
        localValue = row.localValueText,
        remoteValue = row.remoteValueText,
        localEntity = decodeEntity(row.entityType, row.localEntityJson),
        remoteEntity = decodeEntity(row.entityType, row.remoteEntityJson),
        localTimestamp = row.localTimestamp,
        remoteTimestamp = row.remoteTimestamp,
        autoResolvable = false,
        description = row.description
    )

    private fun encodeEntity(entityType: String, entity: Any?): String? {
        if (entity == null) return null
        return when (entityType) {
            "connection" -> (entity as? ConnectionProfile)?.let { json.encodeToString(ConnectionProfile.serializer(), it) }
            "key" -> (entity as? StoredKey)?.let { json.encodeToString(StoredKey.serializer(), it) }
            "theme" -> (entity as? ThemeDefinition)?.let { json.encodeToString(ThemeDefinition.serializer(), it) }
            "host_key" -> (entity as? HostKeyEntry)?.let { json.encodeToString(HostKeyEntry.serializer(), it) }
            else -> null
        }
    }

    private fun decodeEntity(entityType: String, raw: String?): Any? {
        if (raw == null) return null
        return try {
            when (entityType) {
                "connection" -> json.decodeFromString(ConnectionProfile.serializer(), raw)
                "key" -> json.decodeFromString(StoredKey.serializer(), raw)
                "theme" -> json.decodeFromString(ThemeDefinition.serializer(), raw)
                "host_key" -> json.decodeFromString(HostKeyEntry.serializer(), raw)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

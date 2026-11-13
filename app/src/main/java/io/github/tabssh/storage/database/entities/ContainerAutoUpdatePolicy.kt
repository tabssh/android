package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * App-driven auto-update policy for one container or one compose stack
 * service on a [ContainerHost] — registry digest checks + pull/recreate,
 * no host agent.
 */
@Entity(
    tableName = "container_auto_update_policies",
    indices = [
        Index("container_host_id"),
        Index("registry_credential_id")
    ]
)
@Serializable
data class ContainerAutoUpdatePolicy(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Reference to the owning ContainerHost.id (FK-by-convention). */
    @ColumnInfo(name = "container_host_id")
    val containerHostId: Long,

    @ColumnInfo(name = "container_name_or_stack_name")
    val containerNameOrStackName: String,

    /**
     * "container" (standalone container), "stack_service" (compose stack service) or "stack"
     * (an override-only row for a whole compose project; it is never checked itself, its
     * overrides apply to every container of the project).
     */
    @ColumnInfo(name = "scope")
    val scope: String,

    /** Legacy flag, superseded by [enabledOverride] (migration 29→30). Never read. */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** Image/stack tier of the update settings: checks on/off. NULL = inherit host/global. */
    @ColumnInfo(name = "enabled_override")
    val enabledOverride: Boolean? = null,

    /** Image/stack tier of the update settings: apply unattended. NULL = inherit host/global. */
    @ColumnInfo(name = "auto_apply_override")
    val autoApplyOverride: Boolean? = null,

    /** Legacy flag, superseded by [autoApplyOverride] (migration 29→30). Never read. */
    @ColumnInfo(name = "auto_recreate_on_update")
    val autoRecreateOnUpdate: Boolean = false,

    /** Optional RegistryCredential.id for private registries. NULL = anonymous pulls. */
    @ColumnInfo(name = "registry_credential_id")
    val registryCredentialId: Long? = null,

    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long = 0,

    /** Registry digest observed on the most recent check. NULL = never checked. */
    @ColumnInfo(name = "last_digest_seen")
    val lastDigestSeen: String? = null,

    /** Digest of a newer image seen upstream but not yet applied. NULL = up to date. */
    @ColumnInfo(name = "pending_update_digest")
    val pendingUpdateDigest: String? = null,

    /** Last local modification time, used for sync last-write-wins comparisons. */
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = 0
)

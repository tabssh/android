package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Compose stack metadata for a [DockerHost].
 *
 * The remote files under [remotePath] (typically
 * `{composeBasePath}/{name}/compose.yaml`) are the source of truth;
 * Room stores metadata plus a last-fetched status cache only.
 */
@Entity(
    tableName = "compose_stacks",
    indices = [
        Index("docker_host_id")
    ]
)
@Serializable
data class ComposeStack(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Reference to the owning DockerHost.id (FK-by-convention). */
    @ColumnInfo(name = "docker_host_id")
    val dockerHostId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    /** Remote stack directory on the Docker host, e.g. `{composeBasePath}/{name}`. */
    @ColumnInfo(name = "remote_path")
    val remotePath: String,

    @ColumnInfo(name = "auto_update_enabled")
    val autoUpdateEnabled: Boolean = false,

    /** Last-fetched aggregate status cache, e.g. "running(3/3)". NULL = never fetched. */
    @ColumnInfo(name = "last_known_status")
    val lastKnownStatus: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** Last local modification time, used for sync last-write-wins comparisons.
     *  Distinct from [updatedAt], which tracks the last-fetched status cache. */
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = 0
)

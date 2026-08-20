package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Single-container run config metadata for a [ContainerHost].
 *
 * Same shape as [ComposeStack], but [remotePath] lives under
 * `{runConfigBasePath}/{name}/` and the file is a run config
 * ([configFormat] "run_yaml") rather than a compose file. Remote files
 * are the source of truth; Room stores metadata + status cache only.
 */
@Entity(
    tableName = "single_container_configs",
    indices = [
        Index("container_host_id")
    ]
)
@Serializable
data class SingleContainerConfig(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Reference to the owning ContainerHost.id (FK-by-convention). */
    @ColumnInfo(name = "container_host_id")
    val containerHostId: Long,

    @ColumnInfo(name = "name")
    val name: String,

    /** Remote config directory on the Docker host, e.g. `{runConfigBasePath}/{name}`. */
    @ColumnInfo(name = "remote_path")
    val remotePath: String,

    /** Only format currently written; TEXT so future formats need no schema change. */
    @ColumnInfo(name = "config_format")
    val configFormat: String = "run_yaml",

    @ColumnInfo(name = "auto_update_enabled")
    val autoUpdateEnabled: Boolean = false,

    /** Last-fetched container status cache, e.g. "running". NULL = never fetched. */
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

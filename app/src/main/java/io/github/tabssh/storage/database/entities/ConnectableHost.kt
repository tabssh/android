package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * ConnectableHost — an internal-only cross-feature lookup row unifying the
 * sources of terminal-capable (SSH/Telnet/Mosh) hosts: Hosts-tab
 * [ConnectionProfile] rows, direct [TelnetHost] rows, live Cloud Account
 * instances, and [ContainerHost] endpoints (SSH to the machine running the
 * container engine). It exists so
 * Panes (and later Cluster Commands) can offer one stable, queryable ID
 * space across both sources without merging the Hosts and Cloud Accounts
 * tabs into one visible list — this table is never rendered as its own
 * user-facing list.
 *
 * For a connection-profile-backed or [TelnetHost]-backed row, [id] IS the
 * source row's own id (1:1 reuse, simplifies refresh/dedup). For a
 * cloud-instance-backed row,
 * [id] is `"cloud:${cloudAccountId}:${instanceId}"`. For a
 * container-host-backed row, [id] is [ContainerHost.ephemeralProfileId]
 * (`"container_host_${id}"`) — the same alias the container feature already
 * uses for its ephemeral profiles, so credential lookups stay unified. Cloud instances are
 * NEVER auto-imported into [ConnectionProfile] — [hostPreview] is display
 * text only, never used as connection truth; launch-time connection for a
 * cloud-instance member re-fetches the live IP and builds an ephemeral,
 * unsaved [ConnectionProfile] instead.
 *
 * Populated purely by pull/refresh (see `ConnectableHostRegistry`), never
 * by hooking `ConnectionDao` write call sites.
 */
@Serializable
@Entity(tableName = "connectable_hosts")
data class ConnectableHost(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** One of the SOURCE_* constants in the companion object. */
    @ColumnInfo(name = "source_type")
    val sourceType: String,

    /** Non-null only for [SOURCE_CLOUD_INSTANCE] rows. */
    @ColumnInfo(name = "cloud_account_id")
    val cloudAccountId: String? = null,

    /**
     * Non-null only for [SOURCE_CLOUD_INSTANCE] rows — needed at launch time
     * to re-fetch the live IP via the right `CloudProvider` client.
     */
    @ColumnInfo(name = "instance_id")
    val instanceId: String? = null,

    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Cached "user@host:port" (connection-profile rows) or region/IP display
     * text (cloud-instance rows) — display only, never connection truth.
     */
    @ColumnInfo(name = "host_preview")
    val hostPreview: String,

    /** "ssh" or "telnet" — mosh is [ConnectionProfile.moshMode], not a separate protocol. */
    @ColumnInfo(name = "protocol", defaultValue = "'ssh'")
    val protocol: String = "ssh",

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SOURCE_CONNECTION_PROFILE = "connection_profile"
        const val SOURCE_CLOUD_INSTANCE = "cloud_instance"
        const val SOURCE_TELNET_HOST = "telnet_host"
        const val SOURCE_CONTAINER_HOST = "container_host"
    }
}

package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Wave 5.1 — A cloud account is a saved credential for one inventory source
 * (DigitalOcean / Hetzner / Linode / etc.). The credential value is NOT
 * stored here — only the metadata. The actual token / secret is encrypted
 * via [io.github.tabssh.crypto.storage.SecurePasswordManager] under the
 * key `cloud_token_${id}`.
 *
 * Privacy:
 *  - Only the inventory query is made against the cloud. No telemetry.
 *  - Connections imported from a cloud account are tagged with
 *    `cloud_source = "<provider>:<accountName>"` so users can filter or
 *    bulk-delete imports cleanly.
 */
@Serializable
@Entity(tableName = "cloud_accounts")
data class CloudAccount(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Provider tag — `digitalocean`, `hetzner`, `linode`, `vultr`, etc.
     * One of [CloudProviderType.tag].
     */
    @ColumnInfo(name = "provider")
    val provider: String,

    /** Whether the account is enabled for refresh runs. */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    /** Last refresh time in epoch ms (0 = never). */
    @ColumnInfo(name = "last_refresh_at")
    val lastRefreshAt: Long = 0,

    /** Most recent number of imported / matched droplets. */
    @ColumnInfo(name = "last_count")
    val lastCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
)

package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Direct Telnet host — split out of `connections` (ConnectionProfile) by
 * `MIGRATION_24_25` so telnet no longer shares SSH/Mosh's much larger schema.
 * Only the metadata SSH and telnet genuinely share is kept here; SSH-only
 * concerns (identity/key, mosh mode, jump host, port forwarding) stay on
 * ConnectionProfile.
 *
 * Password, when saved, is stored in Android Keystore under the bare host
 * [id] — the same convention ConnectionProfile uses — so the connect-time
 * "ephemeral ConnectionProfile" synthesized from a TelnetHost row (see
 * `TabTerminalActivity.connectPaneMember`) can retrieve it transparently.
 */
@Serializable
@Entity(
    tableName = "telnet_hosts",
    indices = [
        Index("group_id")
    ]
)
data class TelnetHost(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int = 23,

    @ColumnInfo(name = "username")
    val username: String,

    /** Whether a password was saved to the Keystore for this host. */
    @ColumnInfo(name = "save_password")
    val savePassword: Boolean = false,

    /** FK to connection_groups — optional folder assignment. */
    @ColumnInfo(name = "group_id")
    val groupId: String? = null,

    /** Comma-separated tags, mirroring Snippet.tags. */
    @ColumnInfo(name = "tags")
    val tags: String = "",

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    /** ARGB color tag (0 = none). */
    @ColumnInfo(name = "color_tag")
    val colorTag: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
) {
    fun getDisplayName(): String = if (name.isNotBlank()) name else "$username@$host:$port"
}

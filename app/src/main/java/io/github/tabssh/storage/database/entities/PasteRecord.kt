package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One paste this device created: where it went, its link and when. It carries NO secret column;
 * a per-paste delete token lives in the Keystore under `paste_delete_token_${id}` and the
 * Cairnbin owner token under a per-server alias (see PreferenceManager).
 */
@Entity(
    tableName = "paste_records",
    indices = [Index(value = ["server_url", "remote_id"], unique = true)]
)
data class PasteRecord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Paste service id (see PasteServices), e.g. "cairnbin". */
    @ColumnInfo(name = "service")
    val service: String,

    /** Base URL of the server that holds the paste, without a trailing slash. */
    @ColumnInfo(name = "server_url")
    val serverUrl: String,

    /** The server's id for the paste; the last URL segment when the service gives none. */
    @ColumnInfo(name = "remote_id")
    val remoteId: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "title")
    val title: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** How the server copy can be deleted: [DELETE_OWNER], [DELETE_TOKEN] or [DELETE_NONE]. */
    @ColumnInfo(name = "delete_kind")
    val deleteKind: String = DELETE_NONE
) {
    companion object {
        /** The server's Cairnbin owner token (one per server) authorises the delete. */
        const val DELETE_OWNER = "owner"

        /** A token issued for this one paste authorises the delete. */
        const val DELETE_TOKEN = "token"

        /** The service offers no delete; the record can only leave the app list. */
        const val DELETE_NONE = "none"
    }
}

package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * VNC identity — stores credentials used for VNC authentication.
 * Password is stored in Android Keystore under key `vnc_identity_${id}`
 * via SecurePasswordManager; it is never held in this table.
 */
@Entity(tableName = "vnc_identities")
data class VncIdentity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Used only for VeNCrypt Plain sub-types. Null for VNC password auth. */
    @ColumnInfo(name = "username")
    val username: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long
)

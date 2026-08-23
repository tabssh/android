package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * PaneGroup — a saved, named, relaunchable set of up to 6 terminal
 * connections tiled into one grid inside a single terminal-tab-strip slot
 * (the "Panes" feature). Terminal-only (SSH/Telnet/Mosh) — VNC/SPICE are not
 * eligible members.
 *
 * [memberHostIds] is ordered — order is grid fill order, editable by the
 * user in the group editor. Its values are `ConnectableHost.id`s, not raw
 * `ConnectionProfile.id`s — for a connection-profile-backed member these are
 * equal (1:1 reuse), and for a cloud-instance-backed member the id is
 * `"cloud:<accountId>:<instanceId>"`. [layout] is an opaque, serialized grid
 * geometry string (pane count plus per-pane row/col/span) owned entirely by
 * the Panes UI layer; this entity does not interpret it.
 */
@Serializable
@Entity(tableName = "pane_groups")
data class PaneGroup(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "layout")
    val layout: String = "",

    @ColumnInfo(name = "member_host_ids")
    val memberHostIds: List<String> = emptyList(),

    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
)

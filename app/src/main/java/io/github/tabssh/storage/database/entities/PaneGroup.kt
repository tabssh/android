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
/**
 * One tiling-window-manager "window" slot within a [PaneGroup] — the
 * per-window config saved alongside the group so it can be replayed on
 * every open. [hostId] may repeat across windows in the same group (the
 * same host opened twice with different working directories) — order in
 * [PaneGroup.windows] is the grid fill order, not [hostId] uniqueness.
 * [workingDir] (optional) is `cd`'d into after connect via the session's
 * `postConnectScript`. [customTitle] (optional) overrides this window's
 * tile label.
 */
@Serializable
data class PaneWindowConfig(
    val hostId: String,
    val workingDir: String? = null,
    val customTitle: String? = null
)

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

    // Legacy flat member list — superseded by [windows], kept only as a
    // migration-compat read path (see [resolvedWindows]). Never written to
    // by new code; do not drop the column (SQLite can't cheaply drop
    // columns and the migration protocol forbids destructive changes).
    @ColumnInfo(name = "member_host_ids")
    val memberHostIds: List<String> = emptyList(),

    // Ordered per-window configs (host + optional working dir + optional
    // custom title). Added in DB version 18 as an additive column; may be
    // empty on rows written before this field existed — see
    // [resolvedWindows] for the legacy-compat synthesis path.
    @ColumnInfo(name = "windows", defaultValue = "[]")
    val windows: List<PaneWindowConfig> = emptyList(),

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /**
     * The effective per-window config list: [windows] if non-empty,
     * otherwise synthesized from the legacy [memberHostIds] (each host
     * becomes one window with no working dir / no custom title). Callers
     * should always read through this rather than [windows] directly, so
     * rows written before the [windows] column existed keep working.
     */
    fun resolvedWindows(): List<PaneWindowConfig> =
        windows.ifEmpty { memberHostIds.map { PaneWindowConfig(hostId = it) } }
}

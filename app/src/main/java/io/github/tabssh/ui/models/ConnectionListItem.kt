package io.github.tabssh.ui.models

import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile

/**
 * Sealed class representing items in the connection list
 * Can be either a group header or a connection
 */
sealed class ConnectionListItem {

    /**
     * Group header item with expand/collapse state
     */
    data class GroupHeader(
        val group: ConnectionGroup,
        val connectionCount: Int,
        val isExpanded: Boolean = !group.isCollapsed
    ) : ConnectionListItem() {
        fun getDisplayText(): String {
            return "${group.getDisplayName()} ($connectionCount)"
        }
    }

    /**
     * Connection item (can belong to a group or be ungrouped)
     */
    data class Connection(
        val profile: ConnectionProfile,
        val isInGroup: Boolean = profile.groupId != null,
        val indentLevel: Int = if (profile.groupId != null) 1 else 0
    ) : ConnectionListItem()

    /**
     * Special "Ungrouped" header for connections without a group
     */
    data class UngroupedHeader(
        val connectionCount: Int,
        val isExpanded: Boolean = true
    ) : ConnectionListItem() {
        fun getDisplayText(): String {
            return "Ungrouped ($connectionCount)"
        }
    }
}

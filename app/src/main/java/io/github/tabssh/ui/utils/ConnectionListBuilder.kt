package io.github.tabssh.ui.utils

import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.ui.models.ConnectionListItem

/**
 * Utility class for building grouped connection lists
 */
object ConnectionListBuilder {

    /**
     * Build a list of ConnectionListItems from groups and connections
     *
     * @param groups List of all connection groups
     * @param connections List of all connections
     * @param groupExpansionState Map of group IDs to expansion state (default: expanded)
     * @param showUngrouped Whether to show ungrouped connections (default: true)
     * @param ungroupedExpanded Whether ungrouped section is expanded (default: true)
     * @return List of ConnectionListItems ready for adapter
     */
    fun buildGroupedList(
        groups: List<ConnectionGroup>,
        connections: List<ConnectionProfile>,
        groupExpansionState: Map<String, Boolean> = emptyMap(),
        showUngrouped: Boolean = true,
        ungroupedExpanded: Boolean = true
    ): List<ConnectionListItem> {
        val result = mutableListOf<ConnectionListItem>()

        // Group connections by group_id
        val connectionsByGroup = connections.groupBy { it.groupId }

        // Add each group and its connections
        for (group in groups.sortedBy { it.sortOrder }) {
            val groupConnections = connectionsByGroup[group.id] ?: emptyList()
            val isExpanded = groupExpansionState[group.id] ?: !group.isCollapsed

            // Add group header
            result.add(
                ConnectionListItem.GroupHeader(
                    group = group,
                    connectionCount = groupConnections.size,
                    isExpanded = isExpanded
                )
            )

            // Add connections if expanded
            if (isExpanded) {
                groupConnections.forEach { connection ->
                    result.add(
                        ConnectionListItem.Connection(
                            profile = connection,
                            isInGroup = true,
                            indentLevel = 1
                        )
                    )
                }
            }
        }

        // Add ungrouped connections
        if (showUngrouped) {
            val ungroupedConnections = connectionsByGroup[null] ?: emptyList()
            if (ungroupedConnections.isNotEmpty()) {
                // Add ungrouped header
                result.add(
                    ConnectionListItem.UngroupedHeader(
                        connectionCount = ungroupedConnections.size,
                        isExpanded = ungroupedExpanded
                    )
                )

                // Add ungrouped connections if expanded
                if (ungroupedExpanded) {
                    ungroupedConnections.forEach { connection ->
                        result.add(
                            ConnectionListItem.Connection(
                                profile = connection,
                                isInGroup = false,
                                indentLevel = 1
                            )
                        )
                    }
                }
            }
        }

        return result
    }

    /**
     * Build a flat list of connections (no grouping)
     * Used when grouping is disabled or for search results
     */
    fun buildFlatList(connections: List<ConnectionProfile>): List<ConnectionListItem> {
        return connections.map { connection ->
            ConnectionListItem.Connection(
                profile = connection,
                isInGroup = false,
                indentLevel = 0
            )
        }
    }

    /**
     * Filter grouped list by search query
     * Returns a flat list of matching connections
     */
    fun filterByQuery(
        connections: List<ConnectionProfile>,
        query: String
    ): List<ConnectionListItem> {
        if (query.isBlank()) return emptyList()

        val filtered = connections.filter { connection ->
            connection.name.contains(query, ignoreCase = true) ||
            connection.host.contains(query, ignoreCase = true) ||
            connection.username.contains(query, ignoreCase = true) ||
            connection.getDisplayName().contains(query, ignoreCase = true)
        }

        return buildFlatList(filtered)
    }
}

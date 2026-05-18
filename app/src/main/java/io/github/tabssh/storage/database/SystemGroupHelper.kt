package io.github.tabssh.storage.database

import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.first

object SystemGroupHelper {
    private const val TAG = "SystemGroupHelper"

    /**
     * Returns the ID of the system group with the given [groupType], creating it if it
     * does not yet exist. Must be called from a coroutine on a background dispatcher.
     *
     * @param db          The open database instance
     * @param groupType   One of "vm_hosts" or "cloud"
     * @param displayName Human-readable name used when creating the group
     * @param icon        Icon identifier stored on the group (e.g. "vm", "cloud")
     */
    suspend fun getOrCreateSystemGroupId(
        db: TabSSHDatabase,
        groupType: String,
        displayName: String,
        icon: String
    ): String {
        // Try to find existing system group of this type
        val existing = db.connectionGroupDao().getAllGroups().first()
            .firstOrNull { it.groupType == groupType }
        if (existing != null) {
            Logger.d(TAG, "Found existing system group '$groupType': ${existing.id}")
            return existing.id
        }
        // Create it — sort_order 99999 pushes it to the bottom of any sorted list
        val group = ConnectionGroup(
            name = displayName,
            icon = icon,
            groupType = groupType,
            sortOrder = 99999
        )
        db.connectionGroupDao().insertGroup(group)
        Logger.i(TAG, "Created system group '$groupType' (id=${group.id})")
        return group.id
    }
}

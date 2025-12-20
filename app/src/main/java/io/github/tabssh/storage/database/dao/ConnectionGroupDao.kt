package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ConnectionGroup
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for connection groups/folders
 */
@Dao
interface ConnectionGroupDao {

    /**
     * Get all connection groups
     */
    @Query("SELECT * FROM connection_groups ORDER BY sort_order ASC, name ASC")
    fun getAllGroups(): Flow<List<ConnectionGroup>>

    /**
     * Get all root-level groups (no parent)
     */
    @Query("SELECT * FROM connection_groups WHERE parent_id IS NULL ORDER BY sort_order ASC, name ASC")
    fun getRootGroups(): Flow<List<ConnectionGroup>>

    /**
     * Get child groups of a specific parent group
     */
    @Query("SELECT * FROM connection_groups WHERE parent_id = :parentId ORDER BY sort_order ASC, name ASC")
    fun getChildGroups(parentId: String): Flow<List<ConnectionGroup>>

    /**
     * Get a specific group by ID
     */
    @Query("SELECT * FROM connection_groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: String): ConnectionGroup?

    /**
     * Insert a new group
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: ConnectionGroup): Long

    /**
     * Insert multiple groups
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(groups: List<ConnectionGroup>)

    /**
     * Update an existing group
     */
    @Update
    suspend fun updateGroup(group: ConnectionGroup)

    /**
     * Delete a group
     */
    @Delete
    suspend fun deleteGroup(group: ConnectionGroup)

    /**
     * Delete a group by ID
     */
    @Query("DELETE FROM connection_groups WHERE id = :groupId")
    suspend fun deleteGroupById(groupId: String)

    /**
     * Update group expansion state
     */
    @Query("UPDATE connection_groups SET is_collapsed = :isCollapsed WHERE id = :groupId")
    suspend fun updateGroupCollapsedState(groupId: String, isCollapsed: Boolean)

    /**
     * Update group sort order
     */
    @Query("UPDATE connection_groups SET sort_order = :sortOrder WHERE id = :groupId")
    suspend fun updateGroupSortOrder(groupId: String, sortOrder: Int)

    /**
     * Count connections in a specific group
     */
    @Query("SELECT COUNT(*) FROM connections WHERE group_id = :groupId")
    suspend fun getConnectionCountInGroup(groupId: String): Int

    /**
     * Get all group IDs (for validation)
     */
    @Query("SELECT id FROM connection_groups")
    suspend fun getAllGroupIds(): List<String>

    /**
     * Check if a group exists
     */
    @Query("SELECT EXISTS(SELECT 1 FROM connection_groups WHERE id = :groupId)")
    suspend fun groupExists(groupId: String): Boolean

    /**
     * Get group by name (for duplicate checking)
     */
    @Query("SELECT * FROM connection_groups WHERE name = :name LIMIT 1")
    suspend fun getGroupByName(name: String): ConnectionGroup?

    /**
     * Clear all groups (for backup/restore)
     */
    @Query("DELETE FROM connection_groups")
    suspend fun clearAllGroups()
}

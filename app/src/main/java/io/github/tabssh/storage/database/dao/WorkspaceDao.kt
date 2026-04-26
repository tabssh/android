package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.tabssh.storage.database.entities.Workspace
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspaces ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Workspace>>

    @Query("SELECT * FROM workspaces ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<Workspace>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): Workspace?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: Workspace)

    @Update
    suspend fun update(workspace: Workspace)

    @Delete
    suspend fun delete(workspace: Workspace)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String)
}

package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.PaneGroup
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for saved Panes groups (up to 6 terminal connections
 * tiled together and relaunched as one terminal-tab-strip slot).
 */
@Dao
interface PaneGroupDao {

    @Query("SELECT * FROM pane_groups ORDER BY sort_order, name")
    fun getAll(): Flow<List<PaneGroup>>

    @Query("SELECT * FROM pane_groups ORDER BY sort_order, name")
    suspend fun getAllList(): List<PaneGroup>

    @Query("SELECT * FROM pane_groups WHERE id = :id")
    suspend fun getById(id: String): PaneGroup?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(paneGroup: PaneGroup): Long

    @Update
    suspend fun update(paneGroup: PaneGroup)

    @Delete
    suspend fun delete(paneGroup: PaneGroup)

    @Query("DELETE FROM pane_groups WHERE id = :id")
    suspend fun deleteById(id: String)
}

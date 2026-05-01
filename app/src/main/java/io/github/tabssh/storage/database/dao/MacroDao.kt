package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.Macro
import kotlinx.coroutines.flow.Flow

/**
 * Issue #173 — DAO for recordable macros (raw byte sequences captured
 * from the terminal output stream and replayable verbatim).
 */
@Dao
interface MacroDao {

    @Query("SELECT * FROM macros ORDER BY usage_count DESC, name ASC")
    fun getAllMacros(): Flow<List<Macro>>

    @Query("SELECT * FROM macros ORDER BY name ASC")
    suspend fun getAllMacrosList(): List<Macro>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun getMacroById(id: String): Macro?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMacro(macro: Macro)

    @Update
    suspend fun updateMacro(macro: Macro)

    @Delete
    suspend fun deleteMacro(macro: Macro)

    @Query("UPDATE macros SET usage_count = usage_count + 1, modified_at = :now WHERE id = :id")
    suspend fun incrementUsageCount(id: String, now: Long = System.currentTimeMillis())
}

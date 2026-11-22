package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.tabssh.storage.database.entities.PasteRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface PasteRecordDao {

    @Query("SELECT * FROM paste_records ORDER BY created_at DESC")
    fun observeAll(): Flow<List<PasteRecord>>

    @Query("SELECT * FROM paste_records ORDER BY created_at DESC")
    suspend fun getAllList(): List<PasteRecord>

    @Query("SELECT * FROM paste_records WHERE server_url = :serverUrl AND delete_kind = :kind")
    suspend fun getByServerAndKind(serverUrl: String, kind: String): List<PasteRecord>

    /** Returns the new row id, or -1 when the server already has a record for that paste id. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: PasteRecord): Long

    @Query("DELETE FROM paste_records WHERE id = :id")
    suspend fun deleteById(id: Long)
}

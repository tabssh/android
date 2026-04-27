package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.tabssh.storage.database.entities.CloudAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudAccountDao {

    @Query("SELECT * FROM cloud_accounts ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CloudAccount>>

    @Query("SELECT * FROM cloud_accounts ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<CloudAccount>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CloudAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: CloudAccount)

    @Update
    suspend fun update(account: CloudAccount)

    @Delete
    suspend fun delete(account: CloudAccount)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun deleteById(id: String)
}

package io.github.tabssh.storage.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.tabssh.storage.database.entities.Domain
import kotlinx.coroutines.flow.Flow

@Dao
interface DomainDao {

    @Query("SELECT * FROM domains ORDER BY domain_name ASC")
    fun getAll(): Flow<List<Domain>>

    @Query("SELECT * FROM domains ORDER BY domain_name ASC")
    suspend fun getAllList(): List<Domain>

    @Query("SELECT * FROM domains WHERE id = :id")
    suspend fun getById(id: String): Domain?

    @Query("SELECT * FROM domains WHERE expiration_date IS NOT NULL")
    suspend fun getAllWithExpiration(): List<Domain>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(domain: Domain)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(domains: List<Domain>)

    @Update
    suspend fun update(domain: Domain)

    @Delete
    suspend fun delete(domain: Domain)

    @Query("DELETE FROM domains WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM domains")
    suspend fun getCount(): Int
}

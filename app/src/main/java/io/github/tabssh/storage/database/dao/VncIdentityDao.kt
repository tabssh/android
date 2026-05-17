package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.VncIdentity
import kotlinx.coroutines.flow.Flow

@Dao
interface VncIdentityDao {

    @Query("SELECT * FROM vnc_identities ORDER BY name ASC")
    fun getAllIdentities(): Flow<List<VncIdentity>>

    @Query("SELECT * FROM vnc_identities ORDER BY name ASC")
    suspend fun getAllIdentitiesList(): List<VncIdentity>

    @Query("SELECT * FROM vnc_identities WHERE id = :id")
    suspend fun getById(id: String): VncIdentity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(identity: VncIdentity)

    @Update
    suspend fun update(identity: VncIdentity)

    @Delete
    suspend fun delete(identity: VncIdentity)

    @Query("DELETE FROM vnc_identities WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM vnc_identities")
    suspend fun getCount(): Int
}

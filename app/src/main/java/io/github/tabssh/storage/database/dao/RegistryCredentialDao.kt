package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.RegistryCredential
import kotlinx.coroutines.flow.Flow

@Dao
interface RegistryCredentialDao {

    @Query("SELECT * FROM registry_credentials ORDER BY registry_host ASC")
    fun getAllCredentials(): Flow<List<RegistryCredential>>

    @Query("SELECT * FROM registry_credentials ORDER BY registry_host ASC")
    suspend fun getAllList(): List<RegistryCredential>

    @Query("SELECT * FROM registry_credentials WHERE id = :id")
    suspend fun getById(id: Long): RegistryCredential?

    @Query("SELECT * FROM registry_credentials WHERE registry_host = :registryHost")
    suspend fun getByRegistryHost(registryHost: String): List<RegistryCredential>

    @Insert
    suspend fun insert(credential: RegistryCredential): Long

    @Update
    suspend fun update(credential: RegistryCredential)

    @Delete
    suspend fun delete(credential: RegistryCredential)

    @Query("DELETE FROM registry_credentials WHERE id = :id")
    suspend fun deleteById(id: Long)
}

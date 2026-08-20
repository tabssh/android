package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerAutoUpdatePolicyDao {

    @Query("SELECT * FROM container_auto_update_policies WHERE container_host_id = :hostId ORDER BY container_name_or_stack_name ASC")
    fun getPoliciesForHost(hostId: Long): Flow<List<ContainerAutoUpdatePolicy>>

    /** Enabled policies across all hosts — the periodic update-check worker's work list. */
    @Query("SELECT * FROM container_auto_update_policies WHERE enabled = 1")
    suspend fun getEnabledList(): List<ContainerAutoUpdatePolicy>

    /** Every policy regardless of [ContainerAutoUpdatePolicy.enabled] — backup export and sync collection. */
    @Query("SELECT * FROM container_auto_update_policies")
    suspend fun getAllList(): List<ContainerAutoUpdatePolicy>

    @Query("SELECT * FROM container_auto_update_policies WHERE id = :id")
    suspend fun getById(id: Long): ContainerAutoUpdatePolicy?

    @Insert
    suspend fun insert(policy: ContainerAutoUpdatePolicy): Long

    @Update
    suspend fun update(policy: ContainerAutoUpdatePolicy)

    @Delete
    suspend fun delete(policy: ContainerAutoUpdatePolicy)

    @Query("DELETE FROM container_auto_update_policies WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Cascade-by-convention — call from the ContainerHost delete path. */
    @Query("DELETE FROM container_auto_update_policies WHERE container_host_id = :hostId")
    suspend fun deleteForHost(hostId: Long)

    @Query("UPDATE container_auto_update_policies SET last_checked_at = :timestamp, last_digest_seen = :digest WHERE id = :id")
    suspend fun updateCheckResult(id: Long, timestamp: Long, digest: String?)

    @Query("UPDATE container_auto_update_policies SET pending_update_digest = :digest WHERE id = :id")
    suspend fun updatePendingUpdateDigest(id: Long, digest: String?)

    /**
     * Orphan-safe credential delete — nullify registry_credential_id for all
     * policies that reference the deleted RegistryCredential. Call alongside
     * registryCredentialDao.deleteById() so no policy row is left with a
     * dangling registry_credential_id.
     */
    @Query("UPDATE container_auto_update_policies SET registry_credential_id = NULL WHERE registry_credential_id = :credentialId")
    suspend fun clearRegistryCredentialId(credentialId: Long)
}

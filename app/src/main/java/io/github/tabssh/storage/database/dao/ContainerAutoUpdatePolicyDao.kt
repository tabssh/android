package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerAutoUpdatePolicyDao {

    @Query("SELECT * FROM container_auto_update_policies WHERE container_host_id = :hostId ORDER BY container_name_or_stack_name ASC")
    fun getPoliciesForHost(hostId: Long): Flow<List<ContainerAutoUpdatePolicy>>

    /** Policies whose own override does not switch checks off (NULL inherits, 1 forces on). */
    @Query("SELECT * FROM container_auto_update_policies WHERE enabled_override IS NOT 0")
    suspend fun getEnabledList(): List<ContainerAutoUpdatePolicy>

    /** Containers with a newer image waiting, on every host (stack-scope rows hold settings only). */
    @Query("SELECT * FROM container_auto_update_policies WHERE pending_update_digest IS NOT NULL AND scope != 'stack' AND container_host_id IN (SELECT id FROM container_hosts)")
    fun observePending(): Flow<List<ContainerAutoUpdatePolicy>>

    /** One-shot form of [observePending]. */
    @Query("SELECT * FROM container_auto_update_policies WHERE pending_update_digest IS NOT NULL AND scope != 'stack' AND container_host_id IN (SELECT id FROM container_hosts)")
    suspend fun getPendingList(): List<ContainerAutoUpdatePolicy>

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
     * Removes every pending-update flag on a host and returns how many were removed. Used when the host
     * cannot be reached or listed: an update that cannot be confirmed is not left on the list.
     */
    @Query("""UPDATE container_auto_update_policies SET pending_update_digest = NULL
        WHERE container_host_id = :hostId AND scope != 'stack' AND pending_update_digest IS NOT NULL""")
    suspend fun clearPendingForHost(hostId: Long): Int

    /**
     * Clears the pending flag on EVERY row for this container, not just one by id. Nothing stops two
     * rows for the same container existing (a sync merge, two checks racing to create it), and the
     * list shows every flagged row, so clearing one left the other listed as still pending.
     */
    @Query("""UPDATE container_auto_update_policies SET pending_update_digest = NULL
        WHERE container_host_id = :hostId AND container_name_or_stack_name = :name AND scope != 'stack'""")
    suspend fun clearPendingForContainer(hostId: Long, name: String)

    /**
     * Orphan-safe credential delete — nullify registry_credential_id for all
     * policies that reference the deleted RegistryCredential. Call alongside
     * registryCredentialDao.deleteById() so no policy row is left with a
     * dangling registry_credential_id.
     */
    @Query("UPDATE container_auto_update_policies SET registry_credential_id = NULL WHERE registry_credential_id = :credentialId")
    suspend fun clearRegistryCredentialId(credentialId: Long)
}

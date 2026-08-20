package io.github.tabssh.crypto.storage

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for custom-endpoint container host passwords —
 * keeps them in `SecurePasswordManager` (Keystore-backed prefs). The
 * `container_hosts` table deliberately has NO secret column.
 *
 * Keystore alias namespace: `container_host_${id}` (AI.md PART 6
 * `{type}_{entityId}`). The alias deliberately equals
 * [ContainerHost.ephemeralProfileId] so SSHConnection's standard
 * `retrievePassword(profile.id)` password lookup resolves it with no
 * container-specific branch in the auth path.
 */
object ContainerHostPasswordStore {
    private const val TAG = "ContainerHostPwdStore"
    private const val KEY_PREFIX = ContainerHost.ALIAS_PREFIX

    private fun aliasFor(id: Long): String = "$KEY_PREFIX$id"

    /**
     * Get the stored password for this container host. Returns null if
     * nothing is stored — the SSH layer then falls back to prompting.
     */
    suspend fun retrieve(context: Context, id: Long): String? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext null
            try {
                app.securePasswordManager.retrievePassword(aliasFor(id))
            } catch (e: Exception) {
                Logger.w(TAG, "retrieve($id) threw", e)
                null
            }
        }

    /** Persist a (possibly new) password for a container host. Returns Keystore-write success. */
    suspend fun store(context: Context, id: Long, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext false
            try {
                app.securePasswordManager.storePassword(
                    aliasFor(id),
                    password,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            } catch (e: Exception) {
                Logger.w(TAG, "store($id) threw", e)
                false
            }
        }

    /**
     * Delete the stored password — call from every container host delete
     * path (and when a host switches from custom endpoint to a saved
     * connection) so a future row id collision can't leak the previous
     * owner's password. No-op for missing aliases; logs (but does not
     * throw) on Keystore exceptions.
     */
    suspend fun clear(context: Context, id: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        try {
            app.securePasswordManager.clearPassword(aliasFor(id))
        } catch (e: Exception) {
            Logger.w(TAG, "clear($id) threw", e)
        }
    }
}

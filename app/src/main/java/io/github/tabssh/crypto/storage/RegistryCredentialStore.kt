package io.github.tabssh.crypto.storage

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for container-registry secrets (basic-auth
 * password or bearer token) — keeps them in `SecurePasswordManager`
 * (Keystore-backed prefs). The `registry_credentials` table deliberately
 * has NO secret column, so unlike `HypervisorPasswordStore` there is no
 * legacy plaintext to lazily migrate.
 *
 * Keystore alias namespace: `registry_credential_${id}`. Mirrors the
 * hypervisor pattern (`hypervisor_${id}`) so future audit greps find
 * both with the same regex shape.
 */
object RegistryCredentialStore {
    private const val TAG = "RegistryCredStore"
    private const val KEY_PREFIX = "registry_credential_"

    private fun aliasFor(id: Long): String = "$KEY_PREFIX$id"

    /**
     * Get the stored secret for this registry credential. Returns null if
     * nothing is stored — caller decides whether to prompt (or, for
     * authType="anonymous", to skip auth entirely).
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

    /** Persist a (possibly new) secret for a registry credential. Returns Keystore-write success. */
    suspend fun store(context: Context, id: Long, secret: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext false
            try {
                app.securePasswordManager.storePassword(
                    aliasFor(id),
                    secret,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            } catch (e: Exception) {
                Logger.w(TAG, "store($id) threw", e)
                false
            }
        }

    /**
     * Delete the stored secret — call from every registry-credential delete
     * path so a future row id collision can't leak the previous owner's
     * secret. No-op for missing aliases; logs (but does not throw) on
     * Keystore exceptions.
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

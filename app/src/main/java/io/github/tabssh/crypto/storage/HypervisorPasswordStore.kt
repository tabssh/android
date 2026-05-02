package io.github.tabssh.crypto.storage

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for hypervisor (Proxmox / XCP-ng / Xen Orchestra
 * / VMware) credentials — keeps them in `SecurePasswordManager` (Keystore-
 * backed prefs) instead of the plaintext `hypervisors.password` column
 * the entity inherited from earlier versions.
 *
 * Migration policy is **lazy**:
 *   * `retrieve(profile)` checks the Keystore first. On hit → return.
 *   * On miss, falls back to `profile.password` and — if non-empty — copies
 *     it into the Keystore AND clears the DB column. Old rows therefore
 *     migrate the moment they're next read; new rows never carry plaintext.
 *   * `store(id, password)` writes to the Keystore and clears the DB
 *     column unconditionally; a partial failure leaves the user able to
 *     re-enter the password on a subsequent edit.
 *
 * Keystore alias namespace: `hypervisor_${id}`. Mirrors the cloud-account
 * pattern (`cloud_token_${id}`) so future audit greps find both with the
 * same regex shape.
 */
object HypervisorPasswordStore {
    private const val TAG = "HypervisorPwdStore"
    private const val KEY_PREFIX = "hypervisor_"

    private fun aliasFor(id: Long): String = "$KEY_PREFIX$id"

    /**
     * Get the current password for this hypervisor. Always tries the
     * Keystore first; falls back to the (legacy) DB column and lazily
     * migrates if the Keystore was empty. Returns `""` if nothing is
     * stored anywhere — caller decides whether to prompt.
     */
    suspend fun retrieve(context: Context, profile: HypervisorProfile): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext profile.password
            val pm = app.securePasswordManager
            val alias = aliasFor(profile.id)

            // Keystore-first.
            val fromKeystore = try { pm.retrievePassword(alias) } catch (e: Exception) {
                Logger.w(TAG, "retrievePassword($alias) threw — falling back to DB", e)
                null
            }
            if (!fromKeystore.isNullOrEmpty()) return@withContext fromKeystore

            // Legacy fallback. If the row still has plaintext, migrate it now.
            val legacy = profile.password
            if (legacy.isNotEmpty()) {
                try {
                    val ok = pm.storePassword(alias, legacy, SecurePasswordManager.StorageLevel.ENCRYPTED)
                    if (ok) {
                        // Clear the plaintext column on the same row so future
                        // dumps of /data/data/.../databases/* don't reveal it.
                        try {
                            app.database.hypervisorDao().update(profile.copy(password = ""))
                        } catch (e: Exception) {
                            Logger.w(TAG, "Failed to clear legacy password column for id=${profile.id}", e)
                        }
                        Logger.i(TAG, "Migrated hypervisor password to Keystore for id=${profile.id}")
                    } else {
                        Logger.w(TAG, "storePassword($alias) returned false — leaving DB column intact for retry")
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "Lazy-migration storePassword($alias) threw", e)
                }
                return@withContext legacy
            }

            ""
        }

    /**
     * Persist a (possibly new) password for a hypervisor. Always writes
     * to the Keystore; clears the DB column to avoid the on-disk plaintext
     * exposure the audit flagged. Returns true if Keystore write succeeded.
     */
    suspend fun store(context: Context, id: Long, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext false
            val pm = app.securePasswordManager
            val alias = aliasFor(id)
            val ok = try {
                pm.storePassword(alias, password, SecurePasswordManager.StorageLevel.ENCRYPTED)
            } catch (e: Exception) {
                Logger.w(TAG, "storePassword($alias) threw", e)
                false
            }
            // Whether Keystore succeeded or not, blank the DB column —
            // the previous plaintext was the security concern, and a
            // failed Keystore write means the user has to re-enter on
            // the next edit, which is a recoverable UX issue.
            try {
                val current = app.database.hypervisorDao().getById(id)
                if (current != null && current.password.isNotEmpty()) {
                    app.database.hypervisorDao().update(current.copy(password = ""))
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to clear DB password column for id=$id", e)
            }
            ok
        }

    /** Delete the stored password — call from hypervisor delete paths. */
    fun clear(context: Context, id: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        try {
            app.securePasswordManager.clearPassword(aliasFor(id))
        } catch (e: Exception) {
            Logger.w(TAG, "clearPassword threw", e)
        }
    }
}

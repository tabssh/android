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
    private const val ACCOUNT_KEY_PREFIX = "hypervisor_account_"

    private fun aliasFor(id: Long): String = "$KEY_PREFIX$id"
    private fun accountAliasFor(id: Long): String = "$ACCOUNT_KEY_PREFIX$id"

    /**
     * Resolved credentials a hypervisor client uses for one connection.
     * Either an account-backed reuse, or the per-host inline fields.
     */
    data class Credentials(
        val username: String,
        val password: String,
        val realm: String?
    )

    /**
     * Resolve the effective username/password/realm for a hypervisor
     * profile, accounting for an optional linked `HypervisorAccount`.
     *
     * Resolution rules (mirrors the kdoc on `HypervisorProfile.accountId`):
     *   * `profile.accountId == null` (or account row missing):
     *     fall back to per-host `username` + Keystore password under
     *     `hypervisor_${profile.id}` + per-host `realm`.
     *   * `profile.accountId != null` (and account exists):
     *     use `account.username` + Keystore password under
     *     `hypervisor_account_${account.id}`. Realm: profile.realm wins
     *     if non-blank (per-host override), else account.realm.
     *
     * Lazy migration of legacy plaintext on the per-host path is
     * preserved — `retrieve(profile)` is still called in that branch.
     */
    suspend fun resolveCredentials(context: Context, profile: HypervisorProfile): Credentials =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext Credentials(profile.username, profile.password, profile.realm)
            val accountId = profile.accountId
            if (accountId != null) {
                val account = try {
                    app.database.hypervisorAccountDao().getById(accountId)
                } catch (e: Exception) {
                    Logger.w(TAG, "hypervisorAccountDao.getById($accountId) threw — falling back to per-host", e)
                    null
                }
                if (account != null) {
                    val pw = retrieveAccountPassword(context, account.id) ?: ""
                    val realm = profile.realm?.takeIf { it.isNotBlank() } ?: account.realm
                    return@withContext Credentials(account.username, pw, realm)
                }
                Logger.w(TAG, "accountId=$accountId set but row not found — using per-host inline credentials")
            }
            Credentials(profile.username, retrieve(context, profile), profile.realm)
        }

    /** Fetch a stored account password from the Keystore. Returns null
     *  if nothing is stored — caller may want to prompt. */
    suspend fun retrieveAccountPassword(context: Context, accountId: Long): String? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext null
            try {
                app.securePasswordManager.retrievePassword(accountAliasFor(accountId))
            } catch (e: Exception) {
                Logger.w(TAG, "retrieveAccountPassword($accountId) threw", e)
                null
            }
        }

    /** Persist an account password to the Keystore. Returns Keystore-write success. */
    suspend fun storeAccountPassword(context: Context, accountId: Long, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext false
            try {
                app.securePasswordManager.storePassword(
                    accountAliasFor(accountId),
                    password,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            } catch (e: Exception) {
                Logger.w(TAG, "storeAccountPassword($accountId) threw", e)
                false
            }
        }

    /** Delete the Keystore-stored account password — call from account delete. */
    fun clearAccountPassword(context: Context, accountId: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        try {
            app.securePasswordManager.clearPassword(accountAliasFor(accountId))
        } catch (e: Exception) {
            Logger.w(TAG, "clearAccountPassword($accountId) threw", e)
        }
    }

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

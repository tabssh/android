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
    private const val OCI_KEY_ACCOUNT_PREFIX  = "oci_private_key_account_"
    private const val OCI_PASS_ACCOUNT_PREFIX = "oci_passphrase_account_"

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
     * Phase 1 cert pinning — TOFU persistence helper. Called by every
     * hypervisor manager activity right after a successful authenticate()
     * with the value from `client.getCapturedCertSha256()`. Writes to
     * the DB only when:
     *   * the client actually captured a SHA (i.e. verifySsl=true and
     *     no prior pin), AND
     *   * the row currently has no pin OR a different pin
     *     (handles the "user clicked Forget pin and reconnected" path).
     *
     * No-op for verifySsl=false connects (capturedSha will be null) and
     * for connects where the pin already matched (capturedSha is also
     * null because the trust manager didn't write to it).
     */
    suspend fun persistCapturedPinIfAny(
        context: Context,
        profile: HypervisorProfile,
        capturedSha: String?
    ) = withContext(Dispatchers.IO) {
        val sha = capturedSha?.takeIf { it.isNotBlank() } ?: return@withContext
        if (sha.equals(profile.pinnedCertSha256, ignoreCase = true)) return@withContext
        val app = context.applicationContext as? TabSSHApplication ?: return@withContext
        try {
            app.database.hypervisorDao().updatePinnedCertSha256(profile.id, sha)
            Logger.i(TAG, "TOFU pinned ${profile.name} (id=${profile.id}) → SHA-256:$sha")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to persist captured pin for ${profile.name}", e)
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

    /**
     * Drop the Keystore aliases an OCI hypervisor uses
     * (`oci_private_key_${id}` and `oci_passphrase_${id}`). Mirror of
     * [clear] for OCI's API-key auth — call from the delete path so a
     * future row id collision can't leak the previous owner's PEM.
     * No-op for missing aliases; logs (but does not throw) on Keystore
     * exceptions.
     */
    fun clearOciSecrets(context: Context, id: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        val pm = app.securePasswordManager
        val aliases = listOf("oci_private_key_$id", "oci_passphrase_$id")
        for (alias in aliases) {
            try {
                pm.clearPassword(alias)
            } catch (e: Exception) {
                Logger.w(TAG, "clearOciSecrets($alias) threw", e)
            }
        }
    }

    // ─── OCI account-keyed Keystore operations ───────────────────────────────
    // PEM private key and passphrase stored under `oci_private_key_account_${id}`
    // / `oci_passphrase_account_${id}` — account-scoped rather than profile-
    // scoped so one identity can serve multiple OCI hypervisor profiles.

    /** Persist an OCI API private key PEM for an account. */
    suspend fun storeOciAccountKey(context: Context, accountId: Long, pem: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext false
            try {
                app.securePasswordManager.storePassword(
                    "$OCI_KEY_ACCOUNT_PREFIX$accountId", pem,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            } catch (e: Exception) {
                Logger.w(TAG, "storeOciAccountKey($accountId) threw", e)
                false
            }
        }

    /**
     * Retrieve an OCI API private key PEM for an account. Lazily migrates
     * the legacy profile-keyed alias (`oci_private_key_${legacyProfileId}`)
     * to the account alias on first hit so older installs upgrade silently.
     */
    suspend fun retrieveOciAccountKey(
        context: Context,
        accountId: Long,
        legacyProfileId: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? TabSSHApplication ?: return@withContext null
        val pm = app.securePasswordManager
        val current = try {
            pm.retrievePassword("$OCI_KEY_ACCOUNT_PREFIX$accountId")
        } catch (_: Exception) { null }
        if (!current.isNullOrBlank()) return@withContext current
        // Lazy migration from legacy profile-keyed alias
        if (legacyProfileId != null) {
            val legacy = try {
                pm.retrievePassword("oci_private_key_$legacyProfileId")
            } catch (_: Exception) { null }
            if (!legacy.isNullOrBlank()) {
                try {
                    pm.storePassword(
                        "$OCI_KEY_ACCOUNT_PREFIX$accountId", legacy,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                    pm.clearPassword("oci_private_key_$legacyProfileId")
                    Logger.i(TAG, "Migrated OCI key profile $legacyProfileId → account $accountId")
                } catch (e: Exception) {
                    Logger.w(TAG, "OCI key lazy-migration threw for account $accountId", e)
                }
                return@withContext legacy
            }
        }
        null
    }

    /** Persist an OCI API key passphrase for an account. */
    suspend fun storeOciAccountPassphrase(context: Context, accountId: Long, passphrase: String): Boolean =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext false
            try {
                app.securePasswordManager.storePassword(
                    "$OCI_PASS_ACCOUNT_PREFIX$accountId", passphrase,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
            } catch (e: Exception) {
                Logger.w(TAG, "storeOciAccountPassphrase($accountId) threw", e)
                false
            }
        }

    /**
     * Retrieve an OCI API key passphrase. Lazy-migrates from the legacy
     * profile-keyed alias (`oci_passphrase_${legacyProfileId}`).
     */
    suspend fun retrieveOciAccountPassphrase(
        context: Context,
        accountId: Long,
        legacyProfileId: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? TabSSHApplication ?: return@withContext null
        val pm = app.securePasswordManager
        val current = try {
            pm.retrievePassword("$OCI_PASS_ACCOUNT_PREFIX$accountId")
        } catch (_: Exception) { null }
        if (!current.isNullOrBlank()) return@withContext current
        if (legacyProfileId != null) {
            val legacy = try {
                pm.retrievePassword("oci_passphrase_$legacyProfileId")
            } catch (_: Exception) { null }
            if (!legacy.isNullOrBlank()) {
                try {
                    pm.storePassword(
                        "$OCI_PASS_ACCOUNT_PREFIX$accountId", legacy,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    )
                    pm.clearPassword("oci_passphrase_$legacyProfileId")
                } catch (e: Exception) {
                    Logger.w(TAG, "OCI passphrase lazy-migration threw for account $accountId", e)
                }
                return@withContext legacy
            }
        }
        null
    }

    /** Delete all Keystore entries for an OCI account. Call from the delete path. */
    fun clearOciAccountSecrets(context: Context, accountId: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        val pm = app.securePasswordManager
        listOf("$OCI_KEY_ACCOUNT_PREFIX$accountId", "$OCI_PASS_ACCOUNT_PREFIX$accountId").forEach { alias ->
            try { pm.clearPassword(alias) } catch (e: Exception) {
                Logger.w(TAG, "clearOciAccountSecrets($alias) threw", e)
            }
        }
    }
}

package io.github.tabssh.crypto.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for hypervisor (Proxmox / XCP-ng / Xen Orchestra
 * / VMware) credentials — keeps them in `SecurePasswordManager` (Keystore-
 * backed prefs). The `hypervisors` table has no password column at all as
 * of schema v14; nothing here ever writes a secret to the database.
 *
 * The only legacy path left is the **carry-over table**. `MIGRATION_13_14`
 * could not reach the Keystore from inside a Room migration, so instead of
 * discarding the plaintext it moved every still-populated value into
 * [TabSSHDatabase.HYPERVISOR_PASSWORD_CARRYOVER_TABLE]. [sweepLegacyPlaintext]
 * runs at application startup and drains that table into the Keystore,
 * dropping it once empty; [retrieve] drains the single row it needs on a
 * Keystore miss in case a connect beats the sweep. A row whose Keystore
 * write fails survives to the next attempt, so nothing is ever lost.
 *
 * Keystore alias namespace: `hypervisor_${id}`. Mirrors the cloud-account
 * pattern (`cloud_token_${id}`) so future audit greps find both with the
 * same regex shape.
 */
object HypervisorPasswordStore {
    private const val TAG = "HypervisorPwdStore"
    private const val CARRYOVER = TabSSHDatabase.HYPERVISOR_PASSWORD_CARRYOVER_TABLE
    private const val KEY_PREFIX = "hypervisor_"
    private const val ACCOUNT_KEY_PREFIX = "hypervisor_account_"
    const val OCI_KEY_ACCOUNT_PREFIX  = "oci_private_key_account_"
    const val OCI_PASS_ACCOUNT_PREFIX = "oci_passphrase_account_"

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
     * Carry-over draining on the per-host path is preserved —
     * `retrieve(profile)` is still called in that branch.
     */
    suspend fun resolveCredentials(context: Context, profile: HypervisorProfile): Credentials =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext Credentials(profile.username, "", profile.realm)
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
    suspend fun clearAccountPassword(context: Context, accountId: Long) {
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
     *   * the client actually captured a SHA — first-connect TOFU in
     *     either mode (verifySsl=true prompts or system-CA-vets; off
     *     pins the first-seen cert silently), or a user-approved pin
     *     update after a cert change, AND
     *   * the row currently has no pin OR a different pin
     *     (handles the "user clicked Forget pin and reconnected" path).
     *
     * No-op for connects where the pin already matched (capturedSha is
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
     * Keystore first; on a miss, drains this row's v13→v14 carry-over entry
     * (if the startup sweep has not reached it yet) and returns that.
     * Returns `""` if nothing is stored anywhere — caller decides whether
     * to prompt.
     */
    suspend fun retrieve(context: Context, profile: HypervisorProfile): String =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication
                ?: return@withContext ""
            val pm = app.securePasswordManager
            val alias = aliasFor(profile.id)

            // Keystore-first.
            val fromKeystore = try { pm.retrievePassword(alias) } catch (e: Exception) {
                Logger.w(TAG, "retrievePassword($alias) threw — trying the carry-over table", e)
                null
            }
            if (!fromKeystore.isNullOrEmpty()) return@withContext fromKeystore

            val db = try {
                app.database.openHelper.writableDatabase
            } catch (e: Exception) {
                Logger.w(TAG, "Could not open the database to drain the carry-over table", e)
                return@withContext ""
            }
            drainCarryover(db, pm, profile.id)[profile.id] ?: ""
        }

    /**
     * Persist a (possibly new) password for a hypervisor. Writes to the
     * Keystore and discards any carry-over entry still held for this id,
     * so a stale plaintext can never outlive the value the user just set.
     * Returns true if the Keystore write succeeded.
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
            discardCarryover(app, id)
            ok
        }

    /**
     * Startup sweep: drain the v13→v14 carry-over table into the Keystore
     * instead of waiting for each row's next [retrieve], then drop the table
     * once it is empty. Runs on every cold start; on a database that never
     * carried plaintext across the upgrade the table does not exist and this
     * costs one `sqlite_master` probe.
     *
     * Keystore wins: if an alias already holds a password (the user updated
     * it since the plaintext was written), the carried-over value is dropped,
     * not copied over the newer secret. A failed Keystore write leaves the
     * row intact for the next sweep or for [retrieve].
     */
    suspend fun sweepLegacyPlaintext(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? TabSSHApplication ?: return@withContext
        val db = try {
            app.database.openHelper.writableDatabase
        } catch (e: Exception) {
            Logger.w(TAG, "Legacy plaintext sweep could not open the database", e)
            return@withContext
        }
        drainCarryover(db, app.securePasswordManager)
        Unit
    }

    /**
     * Move carried-over plaintext into the Keystore. With [onlyId] set, only
     * that row is considered and the table is left in place; otherwise every
     * row is drained and the table is dropped once empty.
     *
     * Returns the effective password for each id it successfully resolved, so
     * [retrieve] can serve the value it just migrated without a second read.
     * Rows that fail are left untouched for the next attempt.
     */
    internal suspend fun drainCarryover(
        db: SupportSQLiteDatabase,
        pm: SecurePasswordManager,
        onlyId: Long? = null
    ): Map<Long, String> {
        val resolved = mutableMapOf<Long, String>()
        if (!carryoverExists(db)) return resolved

        val pending = mutableListOf<Pair<Long, String>>()
        try {
            val cursor = if (onlyId != null) {
                db.query(
                    "SELECT `id`, `password` FROM `$CARRYOVER` WHERE `id` = ?",
                    arrayOf<Any>(onlyId)
                )
            } else {
                db.query("SELECT `id`, `password` FROM `$CARRYOVER`")
            }
            cursor.use {
                while (it.moveToNext()) {
                    val password = it.getString(1) ?: ""
                    if (password.isNotEmpty()) pending.add(it.getLong(0) to password)
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Could not read the carry-over table", e)
            return resolved
        }

        for ((id, password) in pending) {
            val alias = aliasFor(id)
            try {
                val existing = try { pm.retrievePassword(alias) } catch (e: Exception) {
                    Logger.w(TAG, "Carry-over retrievePassword($alias) threw — leaving row for retry", e)
                    continue
                }
                val effective = if (!existing.isNullOrEmpty()) {
                    // Keystore already holds a newer secret — discard the carried value.
                    existing
                } else {
                    val ok = pm.storePassword(alias, password, SecurePasswordManager.StorageLevel.ENCRYPTED)
                    if (!ok) {
                        Logger.w(TAG, "Carry-over storePassword($alias) returned false — leaving row for retry")
                        continue
                    }
                    password
                }
                db.execSQL("DELETE FROM `$CARRYOVER` WHERE `id` = ?", arrayOf<Any>(id))
                resolved[id] = effective
                Logger.i(TAG, "Moved carried-over hypervisor password into the Keystore for id=$id")
            } catch (e: Exception) {
                Logger.w(TAG, "Carry-over migration failed for hypervisor id=$id — leaving row for retry", e)
            }
        }

        if (onlyId == null) dropCarryoverIfEmpty(db)
        return resolved
    }

    /** True when the v13→v14 carry-over table is still present on this database. */
    private fun carryoverExists(db: SupportSQLiteDatabase): Boolean =
        try {
            db.query(
                "SELECT `name` FROM `sqlite_master` WHERE `type` = 'table' AND `name` = ?",
                arrayOf<Any>(CARRYOVER)
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Logger.w(TAG, "Carry-over table probe failed", e)
            false
        }

    /** Drop the carry-over table once the last row has been migrated. */
    private fun dropCarryoverIfEmpty(db: SupportSQLiteDatabase) {
        try {
            val remaining = db.query("SELECT COUNT(*) FROM `$CARRYOVER`").use {
                if (it.moveToFirst()) it.getLong(0) else 0L
            }
            if (remaining == 0L) db.execSQL("DROP TABLE IF EXISTS `$CARRYOVER`")
        } catch (e: Exception) {
            Logger.w(TAG, "Could not drop the drained carry-over table", e)
        }
    }

    /** Forget any carried-over plaintext for [id] without migrating it. */
    private fun discardCarryover(app: TabSSHApplication, id: Long) {
        try {
            val db = app.database.openHelper.writableDatabase
            if (!carryoverExists(db)) return
            db.execSQL("DELETE FROM `$CARRYOVER` WHERE `id` = ?", arrayOf<Any>(id))
            dropCarryoverIfEmpty(db)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to discard carried-over plaintext for id=$id", e)
        }
    }

    /** Delete the stored password — call from hypervisor delete paths. */
    suspend fun clear(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? TabSSHApplication ?: return@withContext
        try {
            app.securePasswordManager.clearPassword(aliasFor(id))
        } catch (e: Exception) {
            Logger.w(TAG, "clearPassword threw", e)
        }
        discardCarryover(app, id)
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
     * Retrieve an OCI API private key PEM for an account.
     *
     * The account alias is the only alias. Installs that still held the
     * profile-keyed predecessor were moved forward once at startup by
     * [LegacySecretMigrations.migrateProfileKeyedOciSecrets]; there is no
     * legacy fallback here.
     */
    suspend fun retrieveOciAccountKey(context: Context, accountId: Long): String? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext null
            try {
                app.securePasswordManager.retrievePassword("$OCI_KEY_ACCOUNT_PREFIX$accountId")
            } catch (e: Exception) {
                Logger.w(TAG, "retrieveOciAccountKey($accountId) threw", e)
                null
            }
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

    /** Retrieve an OCI API key passphrase for an account. */
    suspend fun retrieveOciAccountPassphrase(context: Context, accountId: Long): String? =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext as? TabSSHApplication ?: return@withContext null
            try {
                app.securePasswordManager.retrievePassword("$OCI_PASS_ACCOUNT_PREFIX$accountId")
            } catch (e: Exception) {
                Logger.w(TAG, "retrieveOciAccountPassphrase($accountId) threw", e)
                null
            }
        }

    /** Delete all Keystore entries for an OCI account. Call from the delete path. */
    suspend fun clearOciAccountSecrets(context: Context, accountId: Long) {
        val app = context.applicationContext as? TabSSHApplication ?: return
        val pm = app.securePasswordManager
        listOf("$OCI_KEY_ACCOUNT_PREFIX$accountId", "$OCI_PASS_ACCOUNT_PREFIX$accountId").forEach { alias ->
            try { pm.clearPassword(alias) } catch (e: Exception) {
                Logger.w(TAG, "clearOciAccountSecrets($alias) threw", e)
            }
        }
    }
}

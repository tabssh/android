package io.github.tabssh.crypto.storage

import android.content.SharedPreferences
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.utils.logging.Logger

/**
 * One-time forward migrations that move secrets out of storage shapes the app
 * no longer writes and into the current Keystore-backed homes.
 *
 * Each migration is idempotent and additive-first: the current-format copy is
 * written and verified before the legacy copy is deleted, so an interrupted
 * run can never lose a password. Once a migration has run there is no legacy
 * read path left anywhere in the app — the old shape is gone, not shadowed.
 */
object LegacySecretMigrations {

    private const val TAG = "LegacySecretMigrations"

    /**
     * Legacy plaintext connection-password key in the default SharedPreferences
     * file. Only the backup/sync layer ever wrote it; the runtime path has
     * always used [SecurePasswordManager] under the bare connection id.
     */
    const val LEGACY_CONNECTION_PASSWORD_PREFIX = "password_"

    /** Legacy profile-keyed OCI API key alias (predates the account-scoped form). */
    const val LEGACY_OCI_KEY_PREFIX = "oci_private_key_"

    /** Legacy profile-keyed OCI passphrase alias. */
    const val LEGACY_OCI_PASS_PREFIX = "oci_passphrase_"

    /**
     * Legacy container-host alias prefix, from when the feature was Docker-only.
     * The current namespace is [ContainerHost.ALIAS_PREFIX].
     */
    const val LEGACY_CONTAINER_HOST_PREFIX = "docker_host_"

    /**
     * Move every plaintext `password_{connectionId}` value out of the default
     * SharedPreferences file and into the Keystore under the bare connection
     * id — the alias the runtime SSH path already reads.
     *
     * A connection that already has a Keystore secret keeps it: the Keystore
     * copy is authoritative because it is what actually authenticates, and the
     * plaintext copy could only ever be a stale backup-layer echo of it. The
     * plaintext key is removed only after the Keystore write reports success,
     * so a failed write leaves the value in place for the next launch to retry.
     *
     * @return true when no legacy key remains, i.e. the caller may set its
     *         "migration done" flag and never call this again.
     */
    suspend fun migratePlaintextConnectionPasswords(
        prefs: SharedPreferences,
        passwordManager: SecurePasswordManager
    ): Boolean {
        val legacyKeys = prefs.all.keys.filter {
            it.startsWith(LEGACY_CONNECTION_PASSWORD_PREFIX) &&
                it.length > LEGACY_CONNECTION_PASSWORD_PREFIX.length
        }
        if (legacyKeys.isEmpty()) return true

        val removable = mutableListOf<String>()
        var migrated = 0
        var failed = 0
        for (key in legacyKeys) {
            val connectionId = key.removePrefix(LEGACY_CONNECTION_PASSWORD_PREFIX)
            val plaintext = try {
                prefs.getString(key, null)
            } catch (e: ClassCastException) {
                Logger.w(TAG, "Legacy connection password key is not a String — dropping it")
                null
            }
            if (plaintext.isNullOrEmpty()) {
                removable.add(key)
                continue
            }
            if (passwordManager.hasStoredPassword(connectionId)) {
                removable.add(key)
                continue
            }
            val stored = passwordManager.storePassword(
                connectionId,
                plaintext,
                SecurePasswordManager.StorageLevel.ENCRYPTED
            )
            if (stored) {
                removable.add(key)
                migrated++
            } else {
                failed++
            }
        }

        if (removable.isNotEmpty()) {
            val editor = prefs.edit()
            removable.forEach { editor.remove(it) }
            editor.commit()
        }
        Logger.i(
            TAG,
            "Connection password migration: $migrated moved to Keystore, " +
                "${removable.size - migrated} stale keys dropped, $failed deferred"
        )
        return failed == 0
    }

    /**
     * Move profile-keyed OCI API-key secrets onto the account-scoped aliases.
     *
     * The account-scoped form ([HypervisorAccount] + `oci_private_key_account_*`)
     * replaced the profile-keyed one; nothing has written the profile-keyed
     * aliases for some time, but a dev-build install can still hold them. For
     * each OCI profile that has a legacy key and no account link, this creates
     * the matching [HypervisorAccount] from the profile's own OCI columns,
     * links the profile to it, re-stores both secrets under the account
     * aliases, and only then clears the legacy ones.
     *
     * @return true when no legacy OCI alias remains.
     */
    suspend fun migrateProfileKeyedOciSecrets(
        database: TabSSHDatabase,
        passwordManager: SecurePasswordManager
    ): Boolean {
        val profiles = database.hypervisorDao().getAllList()
        var complete = true
        for (profile in profiles) {
            val legacyKeyAlias = "$LEGACY_OCI_KEY_PREFIX${profile.id}"
            val legacyPassAlias = "$LEGACY_OCI_PASS_PREFIX${profile.id}"
            val hasLegacyKey = passwordManager.hasStoredPassword(legacyKeyAlias)
            val hasLegacyPass = passwordManager.hasStoredPassword(legacyPassAlias)
            if (!hasLegacyKey && !hasLegacyPass) continue

            try {
                val pem = if (hasLegacyKey) passwordManager.retrievePassword(legacyKeyAlias) else null
                val passphrase = if (hasLegacyPass) passwordManager.retrievePassword(legacyPassAlias) else null

                val accountId = profile.accountId ?: database.hypervisorAccountDao().insert(
                    HypervisorAccount(
                        name = profile.name,
                        username = profile.username,
                        realm = profile.realm,
                        authType = "oci_api_key",
                        ociTenancyOcid = profile.ociTenancyOcid,
                        ociUserOcid = profile.ociUserOcid,
                        ociRegion = profile.ociRegion,
                        ociFingerprint = profile.ociFingerprint,
                        ociCompartmentOcid = profile.ociCompartmentOcid
                    )
                )

                var ok = true
                if (!pem.isNullOrEmpty()) {
                    ok = passwordManager.storePassword(
                        "${HypervisorPasswordStore.OCI_KEY_ACCOUNT_PREFIX}$accountId",
                        pem,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    ) && ok
                }
                if (!passphrase.isNullOrEmpty()) {
                    ok = passwordManager.storePassword(
                        "${HypervisorPasswordStore.OCI_PASS_ACCOUNT_PREFIX}$accountId",
                        passphrase,
                        SecurePasswordManager.StorageLevel.ENCRYPTED
                    ) && ok
                }
                if (!ok) {
                    complete = false
                    continue
                }

                if (profile.accountId != accountId) {
                    database.hypervisorDao().update(profile.copy(accountId = accountId, modifiedAt = System.currentTimeMillis()))
                }
                passwordManager.clearPassword(legacyKeyAlias)
                passwordManager.clearPassword(legacyPassAlias)
                Logger.i(TAG, "Migrated profile-keyed OCI secrets to account $accountId")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to migrate OCI secrets for hypervisor ${profile.id}", e)
                complete = false
            }
        }
        return complete
    }

    /**
     * Move custom-endpoint container-host passwords from the Docker-only alias
     * namespace `docker_host_{id}` onto the engine-agnostic
     * [ContainerHost.ALIAS_PREFIX] namespace `container_host_{id}`.
     *
     * Aliases are enumerated from the Keystore-backed store rather than from
     * the `container_hosts` table so a secret whose row was already removed is
     * cleaned up too instead of lingering forever. An alias that already exists
     * under the new name wins — it is what the runtime auth path reads — and
     * the legacy copy is simply dropped. The legacy alias is cleared only after
     * the new write reports success, so a Keystore failure defers that entry to
     * the next launch instead of losing the password.
     *
     * @return true when no legacy `docker_host_` alias remains.
     */
    suspend fun migrateContainerHostAliases(passwordManager: SecurePasswordManager): Boolean {
        val legacyAliases = passwordManager.storedAliases().filter {
            it.startsWith(LEGACY_CONTAINER_HOST_PREFIX) &&
                it.length > LEGACY_CONTAINER_HOST_PREFIX.length
        }
        if (legacyAliases.isEmpty()) return true

        var migrated = 0
        var dropped = 0
        var complete = true
        for (legacyAlias in legacyAliases) {
            val id = legacyAlias.removePrefix(LEGACY_CONTAINER_HOST_PREFIX)
            val newAlias = "${ContainerHost.ALIAS_PREFIX}$id"
            try {
                if (passwordManager.hasStoredPassword(newAlias)) {
                    passwordManager.clearPassword(legacyAlias)
                    dropped++
                    continue
                }
                val secret = passwordManager.retrievePassword(legacyAlias)
                if (secret.isNullOrEmpty()) {
                    passwordManager.clearPassword(legacyAlias)
                    dropped++
                    continue
                }
                val stored = passwordManager.storePassword(
                    newAlias,
                    secret,
                    SecurePasswordManager.StorageLevel.ENCRYPTED
                )
                if (stored) {
                    passwordManager.clearPassword(legacyAlias)
                    migrated++
                } else {
                    complete = false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to migrate container host alias $legacyAlias", e)
                complete = false
            }
        }
        Logger.i(
            TAG,
            "Container host alias migration: $migrated renamed, $dropped stale aliases dropped, " +
                "complete=$complete"
        )
        return complete
    }
}

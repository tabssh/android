package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Container registry credential metadata for auto-update digest checks.
 *
 * Deliberately carries NO secret column — the password/token lives in the
 * Keystore under `registry_credential_${id}` via `RegistryCredentialStore`
 * (mirrors the `HypervisorPasswordStore` alias pattern).
 */
@Entity(tableName = "registry_credentials")
@Serializable
data class RegistryCredential(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** Registry host, e.g. "docker.io", "ghcr.io", "registry.example.com:5000". */
    @ColumnInfo(name = "registry_host")
    val registryHost: String,

    /** Registry username. Empty for authType="anonymous" or pure-token registries. */
    @ColumnInfo(name = "username")
    val username: String = "",

    /** "anonymous", "basic", "token" - TEXT so future auth styles need no schema change. */
    @ColumnInfo(name = "auth_type")
    val authType: String = "basic",

    /** Last local modification time, used for sync last-write-wins comparisons. */
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = 0
)

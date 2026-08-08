package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Docker host managed over an existing SSH connection.
 *
 * Transport is HYBRID (see PLAN.AI.md): Docker Engine REST API over an SSH
 * unix-socket forward of [socketPath] (primary), with automatic fallback to
 * `docker … --format '{{json .}}'` CLI over SSH exec. [transportMode] pins
 * one tier or leaves selection automatic.
 *
 * [composeBasePath] and [runConfigBasePath] are REMOTE directories on the
 * Docker host; `$USER`/`$HOME` expand on the remote shell, and every remote
 * write is preceded by `mkdir -p`.
 */
@Entity(
    tableName = "docker_hosts",
    indices = [
        Index("linked_connection_id")
    ]
)
@Serializable
data class DockerHost(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    /** Reference to existing SSH connection (ConnectionProfile.id). NULL = custom endpoint. */
    @ColumnInfo(name = "linked_connection_id")
    val linkedConnectionId: String? = null,

    /** Custom SSH endpoint hostname — used when no saved connection is linked. */
    @ColumnInfo(name = "custom_host")
    val customHost: String? = null,

    @ColumnInfo(name = "custom_port")
    val customPort: Int? = null,

    @ColumnInfo(name = "custom_username")
    val customUsername: String? = null,

    /** "password", "key", or "identity" — auth method for the custom endpoint. */
    @ColumnInfo(name = "custom_auth_type")
    val customAuthType: String? = null,

    /** StoredKey.keyId when [customAuthType] is "key". */
    @ColumnInfo(name = "custom_key_id")
    val customKeyId: String? = null,

    /** Identity.id when [customAuthType] is "identity". */
    @ColumnInfo(name = "custom_identity_id")
    val customIdentityId: String? = null,

    @ColumnInfo(name = "socket_path")
    val socketPath: String = "/var/run/docker.sock",

    /** "auto", "api_streamlocal", "api_stdio", "cli_exec". */
    @ColumnInfo(name = "transport_mode")
    val transportMode: String = "auto",

    /** Explicit path to the remote `docker` binary. NULL = resolve via the remote PATH. */
    @ColumnInfo(name = "docker_cli_path")
    val dockerCliPath: String? = null,

    /** "auto", "plugin" (docker compose), "standalone" (docker-compose). */
    @ColumnInfo(name = "compose_invocation")
    val composeInvocation: String = "auto",

    /** Engine API version pin, e.g. "1.43". NULL = negotiate via unversioned GET /version. */
    @ColumnInfo(name = "pinned_api_version")
    val pinnedApiVersion: String? = null,

    /** Remote base dir for compose stacks — `{composeBasePath}/{name}/compose.yaml`. */
    @ColumnInfo(name = "compose_base_path")
    val composeBasePath: String = "/srv/\$USER/tabssh/docker/compose",

    /** Remote base dir for single-container run configs — `{runConfigBasePath}/{name}/`. */
    @ColumnInfo(name = "run_config_base_path")
    val runConfigBasePath: String = "/srv/\$USER/tabssh/docker/docker",

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    /** True when this host connects via its own endpoint instead of a saved connection. */
    fun usesCustomEndpoint(): Boolean = linkedConnectionId == null && !customHost.isNullOrBlank()

    /**
     * Id of the ephemeral ConnectionProfile built for a custom endpoint.
     * Doubles as the Keystore alias for the custom-endpoint password
     * (namespace `docker_host_{id}` per AI.md PART 6), so SSHConnection's
     * standard retrievePassword(profile.id) lookup finds it unmodified.
     */
    fun ephemeralProfileId(): String = "docker_host_$id"
}

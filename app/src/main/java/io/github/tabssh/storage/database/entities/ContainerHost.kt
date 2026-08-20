package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.tabssh.containers.ContainerEngine
import kotlinx.serialization.Serializable

/**
 * Container host managed over an existing SSH connection.
 *
 * [engine] selects the dialect — Docker, Incus, Podman or LXC/LXD. Transport
 * is HYBRID regardless of engine: the engine's REST API over an SSH
 * unix-socket forward of [socketPath] (primary), with automatic fallback to
 * the engine's CLI over SSH exec. [transportMode] pins one tier or leaves
 * selection automatic.
 *
 * [socketPath] blank means "probe this engine's default locations"; a value
 * overrides them and may also be a `tcp://` or `ssh://` endpoint.
 *
 * [composeBasePath] and [runConfigBasePath] are REMOTE directories on the
 * container host; `$USER`/`$HOME` expand on the remote shell, and every
 * remote write is preceded by `mkdir -p`.
 */
@Entity(
    tableName = "container_hosts",
    indices = [
        Index("linked_connection_id")
    ]
)
@Serializable
data class ContainerHost(
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

    /**
     * Which engine this host runs. Stored as [ContainerEngine.id] so an
     * unrecognised value degrades to Docker instead of failing to read the row.
     */
    @ColumnInfo(name = "engine", defaultValue = "docker")
    val engine: String = ContainerEngine.DEFAULT.id,

    /**
     * Socket override. Blank = probe [ContainerEngine.defaultSocketPaths] for
     * this host's engine. Also accepts `tcp://host:port` and `ssh://user@host`.
     */
    @ColumnInfo(name = "socket_path", defaultValue = "")
    val socketPath: String = "",

    /** "auto", "api_streamlocal", "api_stdio", "cli_exec". */
    @ColumnInfo(name = "transport_mode")
    val transportMode: String = "auto",

    /**
     * Explicit path to the remote engine binary (`docker`, `incus`, `podman`,
     * `lxc`). NULL = resolve [ContainerEngine.cliBinary] via the remote PATH.
     */
    @ColumnInfo(name = "engine_cli_path")
    val engineCliPath: String? = null,

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

    /** Per-host image-update-check opt-out — false skips this host entirely. */
    @ColumnInfo(name = "update_check_enabled", defaultValue = "1")
    val updateCheckEnabled: Boolean = true,

    /** Per-host check interval override in hours. NULL = global default (twice daily). */
    @ColumnInfo(name = "update_check_interval_hours")
    val updateCheckIntervalHours: Int? = null,

    /** Millis timestamp of the last completed update check for this host. */
    @ColumnInfo(name = "last_update_check", defaultValue = "0")
    val lastUpdateCheck: Long = 0,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** Last local modification time, used for sync last-write-wins comparisons. */
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = 0
) {
    /** True when this host connects via its own endpoint instead of a saved connection. */
    fun usesCustomEndpoint(): Boolean = linkedConnectionId == null && !customHost.isNullOrBlank()

    /** Typed view of [engine]; an unknown stored id resolves to Docker. */
    fun engineType(): ContainerEngine = ContainerEngine.fromId(engine)

    /**
     * Remote CLI binary to invoke for this host: the explicit override when set,
     * otherwise the engine's own binary name resolved through the remote PATH.
     */
    fun cliBinary(): String =
        engineCliPath?.takeIf { it.isNotBlank() } ?: engineType().cliBinary

    /**
     * Socket locations to try, in order. A blank [socketPath] means "probe the
     * engine's defaults"; an explicit value is the only candidate, because a
     * user who typed a path does not want a silent fallback to a different
     * daemon. May be a `tcp://` or `ssh://` endpoint rather than a unix path.
     */
    fun socketCandidates(): List<String> =
        socketPath.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: engineType().defaultSocketPaths

    /** True when [socketPath] names a network endpoint instead of a unix socket. */
    fun usesNetworkEndpoint(): Boolean =
        socketPath.startsWith("tcp://") || socketPath.startsWith("ssh://")

    companion object {
        /** Keystore alias namespace for custom-endpoint SSH passwords. */
        const val ALIAS_PREFIX = "container_host_"
    }

    /**
     * Id of the ephemeral ConnectionProfile built for a custom endpoint.
     * Doubles as the Keystore alias for the custom-endpoint password
     * (namespace `container_host_{id}` per AI.md PART 6), so SSHConnection's
     * standard retrievePassword(profile.id) lookup finds it unmodified.
     */
    fun ephemeralProfileId(): String = ALIAS_PREFIX + id
}

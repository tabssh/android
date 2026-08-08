package io.github.tabssh.docker

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransport
import io.github.tabssh.docker.transport.SocketRelay
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.docker.transport.TransportCapabilityDetector
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.ssh.forwarding.PortForwardingManager
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide registry of live Docker host sessions (PLAN.AI.md step 21).
 *
 * A [DockerSession] bundles everything the Docker UI needs for one host —
 * the SSH connection, exec runner, and the detected [DockerTransport] — and
 * is cached per host id so DockerHostManagerActivity, ContainerDetailActivity,
 * and the editors share one transport instead of re-detecting per screen
 * (transports are not parcelable, so screens look sessions up by host id).
 *
 * Acquisition is serialized with a [Mutex] so two screens racing on the same
 * host cannot open duplicate relays.
 */
object DockerSessionManager {

    private const val TAG = "DockerSessionManager"

    /** Everything a Docker UI screen needs for one host. */
    data class DockerSession(
        val host: DockerHost,
        val profile: ConnectionProfile,
        val connection: SSHConnection,
        val runner: SshExecRunner,
        /** Persisted DockerHost.transportMode value of the winning tier. */
        val mode: String,
        val transport: DockerTransport,
        /** Relay backing an API-tier transport; null for cli_exec. */
        val relay: SocketRelay?
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, DockerSession>()

    /** The cached live session for [hostId], or null when absent or dead. */
    fun cached(hostId: Long): DockerSession? =
        sessions[hostId]?.takeIf { it.connection.isConnected() }

    /**
     * Acquire (or reuse) a session for [hostId]. [force] re-runs transport
     * detection even when the host has a pinned tier and drops any cached
     * session first. Reuses a live SSHConnection from the app-wide
     * SSHSessionManager when one exists.
     */
    suspend fun acquire(
        app: TabSSHApplication,
        hostId: Long,
        force: Boolean = false
    ): DockerResult<DockerSession> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = sessions[hostId]
            if (existing != null && !force && existing.connection.isConnected()) {
                return@withContext DockerResult.Success(existing)
            }
            if (existing != null) {
                closeQuietly(existing)
                sessions.remove(hostId)
            }

            val dao = app.database.dockerHostDao()
            val host = dao.getById(hostId)
                ?: return@withContext DockerResult.NotFound("Docker host not found", "id=$hostId")
            val linkedId = host.linkedConnectionId
            val profile = if (linkedId != null) {
                app.database.connectionDao().getConnectionById(linkedId)
                    ?: return@withContext DockerResult.NotFound(
                        "The linked SSH connection no longer exists", linkedId
                    )
            } else {
                resolveCustomProfile(app, host)
                    ?: return@withContext DockerResult.Error(
                        "No SSH connection is linked to this Docker host"
                    )
            }

            val connection = app.sshSessionManager.getConnection(profile.id)
                ?.takeIf { it.isConnected() }
                ?: app.sshSessionManager.connectToServer(profile)
                ?: return@withContext DockerResult.TransportUnavailable(
                    "Could not open the SSH connection for this Docker host",
                    profile.name
                )

            val runner = SshExecRunner { connection.jschSession() }
            val portForwardingManager = PortForwardingManager(connection)
            val detector = TransportCapabilityDetector(dao)
            when (val detected = detector.detect(host, runner, portForwardingManager, force)) {
                is DockerResult.Success -> {
                    val session = DockerSession(
                        host = host,
                        profile = profile,
                        connection = connection,
                        runner = runner,
                        mode = detected.value.mode,
                        transport = detected.value.transport,
                        relay = detected.value.relay
                    )
                    sessions[hostId] = session
                    dao.updateLastConnected(hostId, System.currentTimeMillis())
                    Logger.i(TAG, "acquired docker session for host $hostId via ${session.mode}")
                    DockerResult.Success(session)
                }
                is DockerResult.PermissionDenied -> detected
                is DockerResult.NotFound -> detected
                is DockerResult.TransportUnavailable -> detected
                is DockerResult.Error -> detected
            }
        }
    }

    /**
     * Build the ephemeral, never-persisted ConnectionProfile for a
     * custom-endpoint Docker host. Its id equals the Keystore alias of the
     * stored password (`docker_host_{id}`), so password auth resolves
     * through SSHConnection's standard retrievePassword(profile.id) path;
     * identity auth resolves through the identity_{id} alias like any
     * saved connection. Because the profile is not in the connections
     * table, exec tabs opened from it are automatically excluded from
     * recents, connection stats, and session persistence — Docker hosts
     * are a separate domain, like hypervisors. Returns null when the
     * custom endpoint is incomplete.
     */
    suspend fun resolveCustomProfile(
        app: TabSSHApplication,
        host: DockerHost
    ): ConnectionProfile? {
        val endpoint = host.customHost?.takeIf { it.isNotBlank() } ?: return null
        val username = host.customUsername?.takeIf { it.isNotBlank() } ?: return null
        val identity = if (host.customAuthType == "identity") {
            host.customIdentityId?.let { app.database.identityDao().getIdentityById(it) }
        } else null
        val authType = when (host.customAuthType) {
            "key" -> AuthType.PUBLIC_KEY
            "identity" -> identity?.authType ?: AuthType.PASSWORD
            else -> AuthType.PASSWORD
        }
        return ConnectionProfile(
            id = host.ephemeralProfileId(),
            name = host.name.ifBlank { endpoint },
            host = endpoint,
            port = host.customPort ?: 22,
            username = username,
            authType = authType.name,
            keyId = host.customKeyId?.takeIf { host.customAuthType == "key" },
            identityId = identity?.id,
            multiplexerMode = "OFF"
        )
    }

    /** Close and forget the session for [hostId]; the SSH connection stays up. */
    suspend fun release(hostId: Long) = mutex.withLock {
        sessions.remove(hostId)?.let { closeQuietly(it) }
    }

    /** Best-effort transport close — a dead relay must never crash release. */
    private suspend fun closeQuietly(session: DockerSession) {
        try {
            session.transport.close()
        } catch (e: Exception) {
            Logger.w(TAG, "transport close failed for host ${session.host.id}: ${e.message}")
        }
    }
}

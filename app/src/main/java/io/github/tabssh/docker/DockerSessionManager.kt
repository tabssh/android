package io.github.tabssh.docker

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.DockerTransport
import io.github.tabssh.docker.transport.SocketRelay
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.docker.transport.TransportCapabilityDetector
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide registry of live Docker host sessions.
 *
 * A [DockerSession] bundles everything the Docker UI needs for one host —
 * the SSH connection, exec runner, and the detected [DockerTransport] — and
 * is cached per host id so DockerHostManagerActivity, ContainerDetailActivity,
 * and the editors share one transport instead of re-detecting per screen
 * (transports are not parcelable, so screens look sessions up by host id).
 *
 * Scale model: at most one SSH connection per host, opened on demand.
 * Acquisition is serialized PER HOST (a slow connect to one host never
 * blocks another), the cache is LRU-capped at [MAX_OPEN_SESSIONS], idle
 * sessions are disconnected after [IDLE_TIMEOUT_MS] by a background sweep,
 * and dead sessions are evicted (relays closed) instead of lingering.
 * Eviction decisions live in [DockerSessionPolicy] for unit testability.
 */
object DockerSessionManager {

    private const val TAG = "DockerSessionManager"

    /** Max concurrently open docker sessions; least-recently-used live sessions beyond this are evicted. */
    const val MAX_OPEN_SESSIONS = 16

    /** A cached session unused this long is closed (its monitoring-only SSH connection too). */
    const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    /** How often the background sweep re-checks the cache while sessions exist. */
    private const val SWEEP_INTERVAL_MS = 60 * 1000L

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

    // Access-ordered so iteration yields least-recently-used first (LRU).
    // All map access is guarded by the monitor lock (short, non-suspending).
    private val sessions = LinkedHashMap<Long, DockerSession>(16, 0.75f, true)
    private val lastUsed = mutableMapOf<Long, Long>()
    private val hostLocks = mutableMapOf<Long, Mutex>()
    private val lock = Any()

    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sweepJob: Job? = null

    // Held only for eviction cleanup (closing monitoring SSH connections);
    // set on every acquire, never used before one.
    @Volatile
    private var appRef: TabSSHApplication? = null

    /** Per-host acquire lock — two screens racing on one host share one connect. */
    private fun hostLock(hostId: Long): Mutex = synchronized(lock) {
        hostLocks.getOrPut(hostId) { Mutex() }
    }

    /** The cached live session for [hostId], or null when absent or dead. */
    fun cached(hostId: Long): DockerSession? = synchronized(lock) {
        sessions[hostId]?.takeIf { it.connection.isConnected() }
            ?.also { lastUsed[hostId] = System.currentTimeMillis() }
    }

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
    ): DockerResult<DockerSession> = hostLock(hostId).withLock {
        withContext(Dispatchers.IO) {
            appRef = app
            evictStale(app, keepHostId = hostId)

            val existing = synchronized(lock) { sessions[hostId] }
            if (existing != null && !force && existing.connection.isConnected()) {
                synchronized(lock) { lastUsed[hostId] = System.currentTimeMillis() }
                Logger.d(TAG, "reusing cached docker session for host $hostId via ${existing.mode}")
                return@withContext DockerResult.Success(existing)
            }
            if (existing != null) {
                synchronized(lock) {
                    sessions.remove(hostId)
                    lastUsed.remove(hostId)
                }
                closeSession(app, existing, reason = if (force) "forced re-detect" else "dead session")
            }

            Logger.i(TAG, "acquiring docker session for host $hostId (force=$force)")
            val dao = app.database.dockerHostDao()
            val host = dao.getById(hostId)
                ?: run {
                    Logger.w(TAG, "acquire failed: docker host $hostId not in database")
                    return@withContext DockerResult.NotFound("Docker host not found", "id=$hostId")
                }
            val linkedId = host.linkedConnectionId
            val profile = if (linkedId != null) {
                app.database.connectionDao().getConnectionById(linkedId)
                    ?: run {
                        Logger.w(TAG, "acquire failed: linked connection missing for host $hostId")
                        return@withContext DockerResult.NotFound(
                            "The linked SSH connection no longer exists", linkedId
                        )
                    }
            } else {
                resolveCustomProfile(app, host)
                    ?: run {
                        Logger.w(TAG, "acquire failed: host $hostId has no linked connection or custom endpoint")
                        return@withContext DockerResult.Error(
                            "No SSH connection is linked to this Docker host"
                        )
                    }
            }

            // connectForMonitoring, not connectToServer: docker-owned connections are
            // infrastructure plumbing — they must not surface as active SSH sessions,
            // start the foreground service, or fire session alerts. A live user
            // terminal session to the same profile is still reused as-is above.
            Logger.d(TAG, "opening SSH connection for docker host $hostId")
            val connection = app.sshSessionManager.getConnection(profile.id)
                ?.takeIf { it.isConnected() }
                ?: app.sshSessionManager.connectForMonitoring(profile)
                ?: run {
                    Logger.w(TAG, "acquire failed: SSH connection could not be opened for host $hostId")
                    return@withContext DockerResult.TransportUnavailable(
                        "Could not open the SSH connection for this Docker host",
                        profile.name
                    )
                }

            val runner = SshExecRunner { connection.jschSession() }
            val detector = TransportCapabilityDetector(dao)
            Logger.d(TAG, "detecting docker transport for host $hostId (force=$force)")
            when (val detected = detector.detect(host, runner, force)) {
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
                    synchronized(lock) {
                        sessions[hostId] = session
                        lastUsed[hostId] = System.currentTimeMillis()
                    }
                    ensureSweeper()
                    dao.updateLastConnected(hostId, System.currentTimeMillis())
                    Logger.i(TAG, "acquired docker session for host $hostId via ${session.mode}")
                    DockerResult.Success(session)
                }
                is DockerResult.PermissionDenied -> {
                    Logger.w(TAG, "transport detection denied for host $hostId: ${detected.message}")
                    detected
                }
                is DockerResult.NotFound -> {
                    Logger.w(TAG, "transport detection not-found for host $hostId: ${detected.message}")
                    detected
                }
                is DockerResult.TransportUnavailable -> {
                    Logger.w(TAG, "transport unavailable for host $hostId: ${detected.message}")
                    detected
                }
                is DockerResult.Error -> {
                    Logger.w(TAG, "transport detection error for host $hostId: ${detected.message}")
                    detected
                }
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

    /** Close and forget the session for [hostId]; docker-owned monitoring SSH connections close too. */
    suspend fun release(app: TabSSHApplication, hostId: Long) {
        hostLock(hostId).withLock {
            val removed = synchronized(lock) {
                lastUsed.remove(hostId)
                sessions.remove(hostId)
            }
            removed?.let { closeSession(app, it, reason = "released") }
        }
    }

    /**
     * One eviction pass: dead sessions, idle sessions, and LRU overflow
     * beyond [MAX_OPEN_SESSIONS]. Victims are closed outside the monitor
     * lock; [keepHostId] is exempt from capacity eviction only.
     */
    private suspend fun evictStale(app: TabSSHApplication, keepHostId: Long? = null) {
        val victims = synchronized(lock) {
            val entries = sessions.map { (id, session) ->
                DockerSessionPolicy.CacheEntry(
                    hostId = id,
                    lastUsedAt = lastUsed[id] ?: 0L,
                    connected = session.connection.isConnected()
                )
            }
            val ids = DockerSessionPolicy.selectVictims(
                entries, System.currentTimeMillis(), MAX_OPEN_SESSIONS, IDLE_TIMEOUT_MS, keepHostId
            )
            ids.mapNotNull { id ->
                lastUsed.remove(id)
                sessions.remove(id)
            }
        }
        for (session in victims) {
            Logger.i(TAG, "evicting docker session for host ${session.host.id} (dead/idle/LRU)")
            closeSession(app, session, reason = "evicted")
        }
    }

    /** Background sweep — runs while sessions exist so idle hosts disconnect without UI traffic. */
    private fun ensureSweeper() {
        synchronized(lock) {
            if (sweepJob?.isActive == true) return
            sweepJob = sweepScope.launch {
                Logger.d(TAG, "session sweep started")
                while (isActive) {
                    delay(SWEEP_INTERVAL_MS)
                    val app = appRef ?: continue
                    evictStale(app)
                    val empty = synchronized(lock) { sessions.isEmpty() }
                    if (empty) break
                }
                Logger.d(TAG, "session sweep stopped — no open sessions")
            }
        }
    }

    /**
     * Close a session's transport (and relay), then disconnect its SSH
     * connection when docker owns it — monitoring-only and not shared with
     * any other cached session. User terminal connections are never touched.
     */
    private suspend fun closeSession(app: TabSSHApplication, session: DockerSession, reason: String) {
        try {
            session.transport.close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "transport close failed for host ${session.host.id}: ${e.message}")
        }
        val shared = synchronized(lock) {
            sessions.values.any { it.profile.id == session.profile.id }
        }
        if (session.connection.isMonitoringOnly && !shared) {
            Logger.d(TAG, "closing monitoring SSH connection for host ${session.host.id} ($reason)")
            try {
                app.sshSessionManager.closeConnection(session.profile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "monitoring connection close failed for host ${session.host.id}: ${e.message}")
            }
        } else {
            Logger.d(TAG, "docker session closed for host ${session.host.id} ($reason); SSH connection kept")
        }
    }
}

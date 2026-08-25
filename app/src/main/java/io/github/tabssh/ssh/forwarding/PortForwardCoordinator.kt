package io.github.tabssh.ssh.forwarding

import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.auth.AuthType
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.ForwardType
import io.github.tabssh.storage.database.entities.PortForward
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * App-level coordinator that turns a saved [PortForward] rule into a live
 * tunnel on a real SSH session, and back off again.
 *
 * Responsibilities the per-session [PortForwardingManager] does NOT own:
 *  - Resolve the SSH endpoint: either a saved [ConnectionProfile]
 *    (`connectionId`) or a manually entered host that becomes an ephemeral,
 *    non-persisted profile authenticated by a saved Identity.
 *  - Reuse one SSH session (and one manager) per distinct endpoint, so many
 *    forwards to the same server share a single connection.
 *  - Map [PortForward] rules to running tunnels for start/stop/status.
 *  - Bulk-start every enabled auto-start rule on boot / app launch.
 *
 * There is one instance per app process (see [TabSSHApplication]).
 */
class PortForwardCoordinator(private val app: TabSSHApplication) {

    @Suppress("unused")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** One [PortForwardingManager] per endpoint key (see [endpointKey]). */
    private val managers = ConcurrentHashMap<String, PortForwardingManager>()

    /** PortForward.id -> the running tunnel it created. */
    private data class Running(val endpointKey: String, val tunnelId: String)
    private val running = ConcurrentHashMap<String, Running>()

    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Item 44 — running [PortForward] ids, kept in sync not only from
     * [start]/[stop] but also when a tunnel ends on its own (SSH session
     * drop, remote refusing the forward) via the [PortForwardingListener]
     * registered on each manager in [obtainOrCreateManager]. UI observers
     * (`PortForwardingActivity.observeForwards`) `combine()` this with the
     * DAO Flow so a forward that silently died shows as stopped without
     * waiting for an unrelated DB write to trigger a re-render.
     */
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    private fun updateRunningIds() {
        _runningIds.value = running.keys.toSet()
    }

    /**
     * Item 44 — remove whichever forward owns [tunnelId] from [running] when
     * its tunnel ends outside of an explicit [stop] call. Safe to also fire
     * from stop()'s own teardown path: by then the id is already removed, so
     * the lookup below finds nothing and this is a no-op.
     */
    private fun handleTunnelEnded(tunnelId: String) {
        val pfId = running.entries.find { it.value.tunnelId == tunnelId }?.key ?: return
        running.remove(pfId)
        updateRunningIds()
    }

    /** [managers.getOrPut] plus wiring the close/failure listener once per endpoint. */
    private fun obtainOrCreateManager(key: String, connection: SSHConnection): PortForwardingManager =
        managers.getOrPut(key) {
            PortForwardingManager(connection).also { manager ->
                manager.addListener(object : PortForwardingListener {
                    override fun onTunnelStopped(tunnel: Tunnel) = handleTunnelEnded(tunnel.id)
                    override fun onTunnelError(tunnel: Tunnel, error: String) = handleTunnelEnded(tunnel.id)
                })
            }
        }

    /**
     * Start the tunnel for [pf]. Reuses an existing session to the same
     * endpoint when possible. Returns [Result.success] once the tunnel is
     * created (auto-started), or [Result.failure] with a user-facing reason.
     */
    suspend fun start(pf: PortForward): Result<Unit> {
        if (!pf.enabled) {
            return Result.failure(IllegalStateException("Forward is disabled"))
        }
        if (running.containsKey(pf.id)) {
            return Result.success(Unit)
        }
        return try {
            val profile = resolveProfile(pf)
                ?: return Result.failure(
                    IllegalStateException("No SSH endpoint — pick a connection or enter a host")
                )

            val key = endpointKey(pf)
            val connection = obtainConnection(key, profile)
                ?: return Result.failure(
                    IllegalStateException("SSH connection failed for ${profile.host}")
                )

            val manager = obtainOrCreateManager(key, connection)

            val tunnel = when (pf.forwardType) {
                ForwardType.LOCAL -> manager.createLocalForward(
                    localPort = pf.effectiveLocalPort,
                    remoteHost = pf.hostIp,
                    remotePort = pf.remotePort,
                    autoStart = true
                )
                ForwardType.REMOTE -> manager.createRemoteForward(
                    remotePort = pf.remotePort,
                    localHost = pf.hostIp,
                    localPort = pf.localPort,
                    autoStart = true
                )
                ForwardType.DYNAMIC -> manager.createDynamicForward(
                    localPort = pf.localPort,
                    autoStart = true
                )
            }

            // Item 44 — createXForward() never throws on a bind/start failure,
            // it reports it via the returned Tunnel's state instead; only a
            // genuinely ACTIVE tunnel is "running".
            if (tunnel.state != TunnelState.ACTIVE) {
                return Result.failure(
                    IllegalStateException(tunnel.lastError ?: "Failed to start tunnel")
                )
            }

            running[pf.id] = Running(key, tunnel.id)
            updateRunningIds()
            Logger.i("PortForwardCoordinator", "Started ${pf.forwardType} forward '${pf.name}': ${pf.getSummary()}")
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancellation is scope teardown, not a start failure — wrapping it
            // in Result.failure would leave the caller's coroutine looking alive.
            throw e
        } catch (e: Exception) {
            Logger.e("PortForwardCoordinator", "Failed to start forward '${pf.name}'", e)
            Result.failure(e)
        }
    }

    /**
     * Stop the tunnel for [pf] (by id). Also drops the shared session when it
     * has no remaining tunnels, so a stopped forward doesn't leak an idle SSH
     * connection + its foreground notification.
     */
    suspend fun stop(pfId: String) {
        val handle = running.remove(pfId) ?: return
        updateRunningIds()
        val manager = managers[handle.endpointKey] ?: return
        try {
            manager.removeTunnel(handle.tunnelId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("PortForwardCoordinator", "Error stopping tunnel: ${e.message}")
        }
        // If no other running forward uses this endpoint, tear the session down.
        val stillUsed = running.values.any { it.endpointKey == handle.endpointKey }
        if (!stillUsed) {
            managers.remove(handle.endpointKey)?.cleanup()
            app.sshSessionManager.getConnection(handle.endpointKey)?.let {
                app.sshSessionManager.closeConnection(handle.endpointKey)
            }
        }
    }

    /** True when [pfId] currently has a live tunnel. */
    fun isRunning(pfId: String): Boolean = running.containsKey(pfId)

    /** Start every forward that is both enabled and marked auto-start. */
    suspend fun startAllAutoStart() {
        val rules = app.database.portForwardDao().getAutoStartEnabled()
        Logger.i("PortForwardCoordinator", "Auto-starting ${rules.size} port forward(s)")
        for (pf in rules) {
            start(pf).onFailure {
                Logger.w("PortForwardCoordinator", "Auto-start of '${pf.name}' failed: ${it.message}")
            }
        }
    }

    /** Stop all tunnels and drop all sessions this coordinator owns. */
    fun stopAll() {
        running.clear()
        updateRunningIds()
        managers.values.forEach { it.cleanup() }
        managers.clear()
    }

    // --- internals ---

    /**
     * A stable key identifying the SSH endpoint, so forwards to the same
     * server reuse one session. Saved connections key on their profile id;
     * manual endpoints key on host/port/username/identity.
     */
    private fun endpointKey(pf: PortForward): String {
        return if (pf.usesSavedConnection) {
            pf.connectionId!!
        } else {
            "pf-ephemeral:${pf.sshHost}:${pf.sshPort}:${pf.sshUsername}:${pf.identityId}"
        }
    }

    /**
     * Build the [ConnectionProfile] used to open the session. For a saved
     * connection this is the stored profile. For a manual endpoint this is an
     * ephemeral, non-persisted profile whose id equals the endpoint key (so
     * [SSHSessionManager.getConnection] can find and reuse it) and whose auth
     * is taken from the selected Identity.
     */
    private suspend fun resolveProfile(pf: PortForward): ConnectionProfile? {
        if (pf.usesSavedConnection) {
            return app.database.connectionDao().getConnectionById(pf.connectionId!!)
        }
        val host = pf.sshHost?.takeIf { it.isNotBlank() } ?: return null
        val username = pf.sshUsername?.takeIf { it.isNotBlank() } ?: return null
        val identity = pf.identityId?.let { app.database.identityDao().getIdentityById(it) }
        return ConnectionProfile(
            id = endpointKey(pf),
            name = pf.name.ifBlank { "$username@$host" },
            host = host,
            port = pf.sshPort,
            username = username,
            identityId = identity?.id,
            authType = (identity?.authType ?: AuthType.PASSWORD).name
        )
    }

    /**
     * Return a live session for [profile], reusing an existing one when it is
     * still connected. [profile.id] equals [key] for both saved and ephemeral
     * profiles, keeping the session-manager map and our manager map aligned.
     */
    private suspend fun obtainConnection(key: String, profile: ConnectionProfile): SSHConnection? {
        app.sshSessionManager.getConnection(key)?.let { existing ->
            if (existing.isConnected()) return existing
        }
        return app.sshSessionManager.connectToServer(profile)
    }
}

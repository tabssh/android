package io.github.tabssh.docker.transport

import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import io.github.tabssh.ssh.forwarding.PortForwardingManager
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.random.Random

/**
 * Bridges TCP on 127.0.0.1 (for OkHttp) to the remote Docker unix socket.
 *
 * Tier a — streamlocal: a local [ServerSocket] on an ephemeral loopback port;
 * each accepted connection opens a `direct-streamlocal@openssh.com` channel
 * ([ChannelDirectStreamLocal], shipped natively by the mwiede jsch fork — see
 * IDEA.md "JSch direct-streamlocal decision") to [DockerHost.socketPath] and
 * pipes both directions.
 *
 * Tier b — socat bridge: a remote `socat TCP-LISTEN:{port},bind=127.0.0.1 …
 * UNIX-CONNECT:{socketPath}` (nc fifo fallback) spawned over a long-lived
 * exec channel, plus an ordinary TCP local forward via
 * [PortForwardingManager]. The bridge PID is tracked and killed on [close].
 */
class SocketRelay(
    private val host: DockerHost,
    private val execRunner: SshExecRunner,
    private val portForwardingManager: PortForwardingManager
) {

    private companion object {
        private const val TAG = "SocketRelay"
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
        private const val BRIDGE_SETTLE_MS = 500L
        private const val BRIDGE_PORT_ATTEMPTS = 3
        private const val PIPE_BUFFER_SIZE = 32 * 1024
    }

    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Tier a state. */
    private var serverSocket: ServerSocket? = null

    /** In-flight tier-a relays — force-closed by [close]; see [RelayHandle]. */
    private val activeRelays = Collections.synchronizedSet(mutableSetOf<RelayHandle>())

    /** Tier b state. */
    private var bridgeChannel: ChannelExec? = null
    private var bridgePid: String? = null
    private var bridgeTunnelId: String? = null

    /** The local 127.0.0.1 port OkHttp should target, or null before open. */
    @Volatile
    var localPort: Int? = null
        private set

    // ── Tier a: direct-streamlocal ──────────────────────────────────────────

    /**
     * Start the streamlocal relay and return the local port. A probe channel
     * is opened first so sshd-side denial surfaces here instead of on the
     * first HTTP request. JSch reports any server-side denial only as the
     * generic "channel is not opened." — that is mapped to the
     * AllowTcpForwarding/AllowStreamLocalForwarding remediation hint.
     */
    suspend fun openStreamLocal(): Int = withContext(Dispatchers.IO) {
        check(localPort == null) { "SocketRelay already open" }
        probeStreamLocal()
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        serverSocket = server
        relayScope.launch { acceptLoop(server) }
        val port = server.localPort
        localPort = port
        Logger.i(TAG, "streamlocal relay listening on 127.0.0.1:$port -> ${host.socketPath}")
        port
    }

    /** Open and immediately close one streamlocal channel to verify support. */
    private fun probeStreamLocal() {
        openStreamLocalChannel().channel.disconnect()
    }

    /** A connected streamlocal channel with its pre-connect streams. */
    private class StreamLocalConnection(
        val channel: ChannelDirectStreamLocal,
        val input: InputStream,
        val output: OutputStream
    )

    /**
     * One in-flight relay's closable resources. [relayConnection] blocks in
     * plain stream reads, so scope cancellation alone cannot stop it —
     * [forceClose] tears down the socket and channel to unblock those reads.
     */
    private class RelayHandle(private val client: Socket) {
        @Volatile
        var channel: ChannelDirectStreamLocal? = null

        fun forceClose() {
            try {
                channel?.disconnect()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Open one connected streamlocal channel to the Docker socket. */
    private fun openStreamLocalChannel(): StreamLocalConnection {
        val sess = execRunner.requireSession()
        val ch = try {
            sess.openChannel("direct-streamlocal@openssh.com") as ChannelDirectStreamLocal
        } catch (e: Exception) {
            throw TransportUnavailableException(
                DockerTransportMessages.STREAMLOCAL_DENIED_REMEDIATION,
                detail = e.message
            )
        }
        return try {
            ch.socketPath = host.socketPath
            // JSch requires the streams to be obtained BEFORE connect().
            val input = ch.inputStream
            val output = ch.outputStream
            ch.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            StreamLocalConnection(ch, input, output)
        } catch (e: Exception) {
            try {
                ch.disconnect()
            } catch (_: Exception) {
            }
            // "channel is not opened." is JSch's only signal for a server-side
            // denial (AllowTcpForwarding no / AllowStreamLocalForwarding no).
            throw TransportUnavailableException(
                DockerTransportMessages.STREAMLOCAL_DENIED_REMEDIATION,
                detail = e.message
            )
        }
    }

    /** Accept loop — one coroutine per client connection. */
    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!server.isClosed) Logger.w(TAG, "accept failed: ${e.message}")
                break
            }
            relayScope.launch { relayConnection(client) }
        }
    }

    /** Pipe one accepted TCP connection over a fresh streamlocal channel. */
    private fun relayConnection(client: Socket) {
        val handle = RelayHandle(client)
        activeRelays.add(handle)
        var conn: StreamLocalConnection? = null
        try {
            client.tcpNoDelay = true
            val opened = openStreamLocalChannel()
            conn = opened
            handle.channel = opened.channel
            val toRemote = Thread { pipe(client.getInputStream(), opened.output) }
            toRemote.isDaemon = true
            toRemote.start()
            // Remote→local runs on this coroutine's thread; when the channel
            // EOFs the socket is closed below, which also unblocks toRemote.
            pipe(opened.input, client.getOutputStream())
            toRemote.join(1000)
        } catch (e: Exception) {
            Logger.d(TAG, "relay connection ended: ${e.message}")
        } finally {
            activeRelays.remove(handle)
            try {
                conn?.channel?.disconnect()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Copy [input] to [output] until EOF, flushing every chunk. */
    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        try {
            var n = input.read(buf)
            while (n >= 0) {
                if (n > 0) {
                    output.write(buf, 0, n)
                    output.flush()
                }
                n = input.read(buf)
            }
        } catch (_: Exception) {
            // Stream teardown mid-copy is the normal close path.
        }
    }

    // ── Tier b: remote socat/nc bridge + TCP local forward ─────────────────

    /**
     * Spawn the remote unix→TCP bridge and a matching local TCP forward.
     * Returns the local port. The bridge process PID is captured from the
     * spawned shell (`echo $$; exec socat …` keeps the PID stable) and the
     * process is killed in [close].
     */
    suspend fun openSocatBridge(): Int = withContext(Dispatchers.IO) {
        check(localPort == null) { "SocketRelay already open" }
        val tool = detectBridgeTool()
        var lastError: String? = null
        repeat(BRIDGE_PORT_ATTEMPTS) {
            val remotePort = Random.nextInt(30000, 60000)
            val started = startBridgeProcess(tool, remotePort)
            if (started == null) {
                val tunnel = portForwardingManager.createLocalForward(0, "127.0.0.1", remotePort)
                val local = tunnel.actualLocalPort ?: tunnel.localPort
                if (tunnel.isActive() && local > 0) {
                    bridgeTunnelId = tunnel.id
                    localPort = local
                    Logger.i(
                        TAG,
                        "socat bridge up: 127.0.0.1:$local -> remote:$remotePort -> ${host.socketPath} (pid=$bridgePid)"
                    )
                    return@withContext local
                }
                lastError = tunnel.lastError ?: "local forward failed"
                stopBridgeProcess()
                portForwardingManager.removeTunnel(tunnel.id)
            } else {
                lastError = started
            }
        }
        throw TransportUnavailableException(
            DockerTransportMessages.SOCKET_MISSING,
            detail = lastError
        )
    }

    /** Resolve socat, falling back to nc. */
    private suspend fun detectBridgeTool(): String {
        val probe = execRunner.run("command -v socat || command -v nc")
        val tool = probe.stdout.trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (!probe.isSuccess || tool.isEmpty()) {
            throw TransportUnavailableException(
                DockerTransportMessages.BRIDGE_TOOL_MISSING,
                detail = probe.stderr.trim()
            )
        }
        return tool
    }

    /**
     * Start the bridge on [remotePort]; returns null on success or an error
     * string. The exec channel stays open for the bridge's lifetime.
     */
    private suspend fun startBridgeProcess(tool: String, remotePort: Int): String? {
        val sock = SshExecRunner.shQuote(host.socketPath)
        // The detected tool path comes from remote `command -v` output — quote
        // it like every other interpolated value.
        val toolQ = SshExecRunner.shQuote(tool)
        // `echo $$; exec socat` — the shell PID survives the exec, so the
        // first stdout line is the bridge PID we later kill. The nc variant
        // keeps the loop shell as the tracked PID; single-connection at a
        // time is a documented degradation (OkHttp reuses one connection).
        val script = if (tool.endsWith("socat")) {
            "echo \$\$; exec $toolQ TCP-LISTEN:$remotePort,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:$sock"
        } else {
            "echo \$\$; F=\$(mktemp -u); mkfifo \"\$F\"; trap 'rm -f \"\$F\"' EXIT; " +
                "while :; do $toolQ -l 127.0.0.1 $remotePort < \"\$F\" | $toolQ -U $sock > \"\$F\"; done"
        }
        val sess = execRunner.requireSession()
        val channel = sess.openChannel("exec") as ChannelExec
        return try {
            channel.setCommand("sh -c ${SshExecRunner.shQuote(script)}")
            channel.setInputStream(null)
            val stdout = channel.inputStream
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            // First line is the PID; poll-read it with cancellation support.
            val pid = readPidLine(stdout, channel)
            // Give the listener a moment; a bind failure exits immediately.
            delay(BRIDGE_SETTLE_MS)
            if (channel.isClosed) {
                channel.disconnect()
                return "bridge exited immediately (port $remotePort busy or socket unreachable)"
            }
            if (pid.isEmpty()) {
                channel.disconnect()
                return "bridge did not report a PID"
            }
            bridgeChannel = channel
            bridgePid = pid
            null
        } catch (e: Exception) {
            try {
                channel.disconnect()
            } catch (_: Exception) {
            }
            e.message ?: "bridge spawn failed"
        }
    }

    /** Read the first stdout line (the PID) without blocking cancellation. */
    private suspend fun readPidLine(stdout: InputStream, channel: ChannelExec): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            while (stdout.available() > 0) {
                val c = stdout.read()
                if (c < 0 || c == '\n'.code) return sb.toString().trim()
                sb.append(c.toChar())
            }
            if (channel.isClosed) return sb.toString().trim()
            delay(50)
        }
        return sb.toString().trim()
    }

    /** Kill the tracked remote bridge process and drop its channel. */
    private suspend fun stopBridgeProcess() {
        val pid = bridgePid
        if (pid != null && pid.matches(Regex("[0-9]+"))) {
            try {
                execRunner.run("kill $pid 2>/dev/null")
            } catch (e: Exception) {
                Logger.d(TAG, "bridge kill failed: ${e.message}")
            }
        }
        bridgePid = null
        try {
            bridgeChannel?.disconnect()
        } catch (_: Exception) {
        }
        bridgeChannel = null
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Tear down whichever tier is active. Safe to call twice. */
    suspend fun close() = withContext(Dispatchers.IO) {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        // Blocking pipe reads don't observe scope cancellation — force-close
        // every in-flight relay so its threads unblock and exit.
        synchronized(activeRelays) { activeRelays.toList() }.forEach { it.forceClose() }
        activeRelays.clear()
        bridgeTunnelId?.let {
            try {
                portForwardingManager.removeTunnel(it)
            } catch (e: Exception) {
                Logger.d(TAG, "tunnel removal failed: ${e.message}")
            }
        }
        bridgeTunnelId = null
        stopBridgeProcess()
        relayScope.cancel()
        localPort = null
        Logger.d(TAG, "relay closed")
    }
}

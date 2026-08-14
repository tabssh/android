package io.github.tabssh.docker.transport

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectStreamLocal
import com.jcraft.jsch.ChannelExec
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.net.SocketFactory

/**
 * Bridges TCP on 127.0.0.1 (for OkHttp) to the remote Docker unix socket.
 *
 * Tier a — streamlocal: a local [ServerSocket] on an ephemeral loopback port;
 * each accepted connection opens a `direct-streamlocal@openssh.com` channel
 * ([ChannelDirectStreamLocal], shipped natively by the mwiede jsch fork — see
 * IDEA.md "JSch direct-streamlocal decision") to [DockerHost.socketPath] and
 * pipes both directions.
 *
 * Tier b — dial-stdio: the same local [ServerSocket]/accept-loop shape, but
 * each accepted connection opens a fresh `exec` channel running
 * `docker system dial-stdio` (the same mechanism the official Docker CLI
 * uses for `DOCKER_HOST=ssh://` contexts) and pipes the client socket to the
 * channel's stdin/stdout. No remote listener process, no PID tracking — the
 * exec channel dies with its connection.
 *
 * The relay listens on loopback, but loopback is shared by every app on the
 * device — so every accepted connection must first prove it is the intended
 * caller. [token] is a fresh [SecureRandom] value generated per relay
 * instance; a client must send it as a fixed-length preamble before any
 * relayed bytes ([authenticateClient]). Mismatch or a stalled/short preamble
 * drops the connection immediately, before the (expensive) remote channel is
 * even opened. [RelayTokenSocketFactory] is the dial-side counterpart —
 * consumers construct their OkHttpClient with it so the token is written as
 * the connection's first bytes automatically.
 */
class SocketRelay(
    private val host: DockerHost,
    private val execRunner: SshExecRunner
) {

    companion object {
        private const val TAG = "SocketRelay"
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
        private const val PIPE_BUFFER_SIZE = 32 * 1024
        private const val DEFAULT_SOCKET_PATH = "/var/run/docker.sock"

        /** Per-session auth token length — SecureRandom, ≥32 bytes (AI.md PART 6). */
        private const val TOKEN_LENGTH_BYTES = 32

        /**
         * Bound on how long the accept path waits for a client to finish
         * sending its token preamble, so a stalled or hostile local
         * connection can't tie up a relay coroutine indefinitely.
         */
        private const val PREAMBLE_READ_TIMEOUT_MS = 3_000

        /**
         * Build the `docker system dial-stdio` invocation for [dockerBin] and
         * [socketPath]. Pure decision logic pulled out of [probeDialStdio] so
         * it is unit-testable without an SSH session: when [socketPath] is
         * blank or equal to [DEFAULT_SOCKET_PATH] the docker CLI's own
         * default context is used unmodified; otherwise the command is
         * prefixed with a shell-quoted `DOCKER_HOST=unix://{socketPath}`.
         */
        internal fun buildDialStdioCommand(dockerBin: String, socketPath: String): String {
            val prefix = if (socketPath.isNotBlank() && socketPath != DEFAULT_SOCKET_PATH) {
                "DOCKER_HOST=unix://${SshExecRunner.shQuote(socketPath)} "
            } else {
                ""
            }
            return "$prefix$dockerBin system dial-stdio"
        }
    }

    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Shared accept-loop state (both tiers use one [ServerSocket]).
     * Written on the opening coroutine and read by [close] from whichever
     * thread tears the session down, so the reference must be volatile.
     */
    @Volatile
    private var serverSocket: ServerSocket? = null

    /** In-flight relays — force-closed by [close]; see [RelayHandle]. */
    private val activeRelays = Collections.synchronizedSet(mutableSetOf<RelayHandle>())

    /**
     * Resolved `docker system dial-stdio` command, cached after
     * [probeDialStdio]. Written during probing and read by every relay
     * coroutine that opens a channel, hence volatile.
     */
    @Volatile
    private var dialStdioCommand: String? = null

    /** The local 127.0.0.1 port OkHttp should target, or null before open. */
    @Volatile
    var localPort: Int? = null
        private set

    /**
     * Per-session preamble token every client must send first. Never log
     * this value — it is the relay's only local authentication (AI.md PART
     * 6: "Logger sanitizes... never log raw tokens").
     */
    val token: ByteArray = ByteArray(TOKEN_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

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
        startAcceptLoop { openStreamLocalChannel() }
    }

    /** Open and immediately close one streamlocal channel to verify support. */
    private fun probeStreamLocal() {
        openStreamLocalChannel().channel.disconnect()
    }

    /** Open one connected streamlocal channel to the Docker socket. */
    private fun openStreamLocalChannel(): OpenedChannel {
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
            OpenedChannel(ch, input, output)
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

    // ── Tier b: docker system dial-stdio ────────────────────────────────────

    /**
     * Start the dial-stdio relay and return the local port. A probe run of
     * `docker system dial-stdio --help` verifies the remote docker CLI
     * supports it (Docker 18.09+) before the accept loop opens, mirroring
     * [openStreamLocal]'s up-front probe.
     */
    suspend fun openDialStdio(): Int = withContext(Dispatchers.IO) {
        check(localPort == null) { "SocketRelay already open" }
        dialStdioCommand = probeDialStdio()
        startAcceptLoop { openDialStdioChannel() }
    }

    /**
     * Resolve the remote `docker system dial-stdio` invocation and verify
     * support with `--help`. [DockerHost.socketPath] is respected: when it is
     * not the daemon default, `DOCKER_HOST=unix://{socketPath}` is prefixed
     * so dial-stdio targets the configured socket instead of the docker
     * CLI's own default context.
     */
    private suspend fun probeDialStdio(): String {
        // Same "explicit path or PATH lookup" convention as RemoteExecOps.dockerBin,
        // interpolated the same way (unquoted — an admin-configured binary path,
        // not untrusted input).
        val dockerBin = host.dockerCliPath?.takeIf { it.isNotBlank() } ?: "docker"
        val command = buildDialStdioCommand(dockerBin, host.socketPath)
        val probe = execRunner.run("$command --help")
        if (!probe.isSuccess) {
            throw TransportUnavailableException(
                DockerTransportMessages.DIAL_STDIO_UNSUPPORTED,
                detail = probe.stderr.trim().ifEmpty { probe.stdout.trim() }
            )
        }
        return command
    }

    /** Open one exec channel running the probed dial-stdio command. */
    private fun openDialStdioChannel(): OpenedChannel {
        val command = dialStdioCommand
            ?: throw TransportUnavailableException(DockerTransportMessages.DIAL_STDIO_UNSUPPORTED)
        val sess = execRunner.requireSession()
        val ch = try {
            sess.openChannel("exec") as ChannelExec
        } catch (e: Exception) {
            throw TransportUnavailableException(
                DockerTransportMessages.DIAL_STDIO_UNSUPPORTED,
                detail = e.message
            )
        }
        return try {
            ch.setCommand(command)
            // JSch requires the streams to be obtained BEFORE connect().
            val input = ch.inputStream
            val output = ch.outputStream
            ch.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            OpenedChannel(ch, input, output)
        } catch (e: Exception) {
            try {
                ch.disconnect()
            } catch (_: Exception) {
            }
            throw TransportUnavailableException(
                DockerTransportMessages.DIAL_STDIO_UNSUPPORTED,
                detail = e.message
            )
        }
    }

    // ── Shared accept loop / pipe machinery ─────────────────────────────────

    /** A connected channel (either tier) with its pre-connect streams. */
    private class OpenedChannel(
        val channel: Channel,
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
        var channel: Channel? = null

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

    /** Start the local listener and accept loop, returning the bound port. */
    private fun startAcceptLoop(opener: () -> OpenedChannel): Int {
        // Bind explicitly to IPv4 127.0.0.1 — every consumer (probeApiVersion,
        // EngineApiTransport baseUrl) dials the literal "127.0.0.1", but
        // getLoopbackAddress() returns ::1 on IPv6-preferring devices (e.g.
        // IPv6-only mobile carriers), leaving the relay listening on IPv6
        // loopback only and every probe failing with connection refused.
        val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        serverSocket = server
        relayScope.launch { acceptLoop(server, opener) }
        val port = server.localPort
        localPort = port
        Logger.i(TAG, "relay listening on ${server.inetAddress.hostAddress}:$port -> ${host.socketPath}")
        return port
    }

    /** Accept loop — one coroutine per client connection. */
    private fun acceptLoop(server: ServerSocket, opener: () -> OpenedChannel) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (!server.isClosed) Logger.w(TAG, "accept failed: ${e.message}")
                break
            }
            relayScope.launch { relayConnection(client, opener) }
        }
    }

    /**
     * Read and verify the fixed-length token preamble. A short read
     * timeout is set for the duration of this check only — reset before
     * the caller proceeds to the (potentially long-lived) relayed pipe.
     * [MessageDigest.isEqual] gives constant-time comparison (AI.md PART
     * 6: "Constant-time comparison for any secret verification").
     */
    internal fun authenticateClient(client: Socket): Boolean {
        return try {
            client.soTimeout = PREAMBLE_READ_TIMEOUT_MS
            val received = ByteArray(token.size)
            val input = client.getInputStream()
            var offset = 0
            while (offset < received.size) {
                val n = input.read(received, offset, received.size - offset)
                if (n < 0) return false
                offset += n
            }
            client.soTimeout = 0
            MessageDigest.isEqual(received, token)
        } catch (e: Exception) {
            Logger.d(TAG, "relay preamble check failed: ${e.message}")
            false
        }
    }

    /** Pipe one accepted TCP connection over a fresh channel from [opener]. */
    private fun relayConnection(client: Socket, opener: () -> OpenedChannel) {
        client.tcpNoDelay = true
        if (!authenticateClient(client)) {
            Logger.w(TAG, "relay connection dropped: token preamble missing or invalid")
            try {
                client.close()
            } catch (_: Exception) {
            }
            return
        }
        val handle = RelayHandle(client)
        activeRelays.add(handle)
        var conn: OpenedChannel? = null
        try {
            val opened = opener()
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
        } catch (e: Exception) {
            // Stream teardown mid-copy is the normal close path, but a
            // failure here silently breaks the relay data path — log it
            // so a dead relay isn't mistaken for an idle one.
            Logger.d(TAG, "pipe copy ended: ${e.message}")
        }
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
        // every in-flight relay (streamlocal channels and dial-stdio exec
        // channels alike) so its threads unblock and exit.
        synchronized(activeRelays) { activeRelays.toList() }.forEach { it.forceClose() }
        activeRelays.clear()
        relayScope.cancel()
        localPort = null
        dialStdioCommand = null
        Logger.d(TAG, "relay closed")
    }
}

/**
 * Dial-side counterpart of [SocketRelay]'s token preamble check. Every
 * [Socket] this factory produces writes [token] as the connection's first
 * bytes the moment [Socket.connect] succeeds — before the caller (e.g.
 * OkHttp) gets the socket back and writes anything else. Build an
 * OkHttpClient with `.socketFactory(RelayTokenSocketFactory(relay.token))`
 * to authenticate every request the client makes against that relay.
 *
 * OkHttp connects direct (non-proxied) sockets via the no-arg [createSocket]
 * followed by an explicit [Socket.connect] call, so only that path is
 * exercised in practice; the host/port overloads below replicate the same
 * unconnected-then-connect sequence so the factory is correct for any other
 * caller too.
 */
class RelayTokenSocketFactory(private val token: ByteArray) : SocketFactory() {

    override fun createSocket(): Socket = TokenPrefixingSocket(token)

    override fun createSocket(host: String, port: Int): Socket =
        TokenPrefixingSocket(token).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        TokenPrefixingSocket(token).apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        TokenPrefixingSocket(token).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        TokenPrefixingSocket(token).apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }

    /** A [Socket] that writes [token] immediately after [connect] succeeds. */
    private class TokenPrefixingSocket(private val token: ByteArray) : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(endpoint, timeout)
            getOutputStream().write(token)
            getOutputStream().flush()
        }
    }
}

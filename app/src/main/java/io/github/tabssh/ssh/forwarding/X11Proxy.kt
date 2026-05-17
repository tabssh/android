package io.github.tabssh.ssh.forwarding

import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.github.tabssh.utils.logging.Logger
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Local TCP proxy that bridges JSch's X11 channel forwarding to an X display
 * server running on the Android device.
 *
 * ## How it fits into the X11 forwarding pipeline
 *
 * When `session.setX11Host(host) / setX11Port(port)` is configured and a
 * channel has `setXForwarding(true)`, JSch opens a TCP connection to
 * `host:port` every time the remote SSH daemon sends an `x11` channel-open
 * request (i.e. every time the remote side opens a new X client window).
 *
 * [X11Proxy] binds a [ServerSocket] on `localhost:0` (OS-assigned ephemeral
 * port). [port] is read after [start] and passed to JSch via
 * `session.setX11Port(proxy.port)`. Each accepted connection is relayed to
 * whichever X server is reachable:
 *
 * 1. **Termux:X11** — connects to the Unix-domain socket at
 *    `/data/data/com.termux.x11/files/tmp/.X11-unix/X0` (display :0).
 * 2. **XServer XSDL** — falls back to `127.0.0.1:6000` (TCP display :0).
 * 3. **Neither** — the accepted socket is closed immediately and [onNoServer]
 *    is invoked **at most once per proxy instance** so the caller can show a
 *    one-time user message without flooding the UI.
 *
 * ## Thread model
 *
 * All work runs on daemon threads in an unbounded-but-short-lived cached
 * thread pool:
 *  - One "accept" thread spun up in [start].
 *  - Two relay threads per accepted connection (in→out, out→in).
 *
 * All threads are interrupted / torn down when [stop] is called or the
 * [ServerSocket] is closed.
 *
 * ## Lifecycle
 *
 * Call [start] once before configuring JSch. Call [stop] in
 * [SSHConnection.disconnect] to release the server socket and all relay
 * threads. A stopped proxy cannot be restarted — create a new instance.
 */
class X11Proxy(
    private val onNoServer: () -> Unit
) {
    companion object {
        private const val TAG = "X11Proxy"
        private const val TERMUX_X11_SOCKET = "/data/data/com.termux.x11/files/tmp/.X11-unix/X0"
        private const val XSDL_HOST = "127.0.0.1"
        private const val XSDL_PORT = 6000
        private const val XSERVER_CONNECT_TIMEOUT_MS = 500
        private const val RELAY_BUFFER_SIZE = 8192
    }

    /** The localhost port JSch should connect to. 0 until [start] is called. */
    @Volatile
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var noServerNotified = false

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply {
            isDaemon = true
            name = "x11-proxy"
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Bind the server socket and begin accepting connections.
     * Must be called before reading [port].
     * Throws [java.io.IOException] if the socket cannot be bound.
     */
    fun start() {
        val ss = ServerSocket(0)  // port 0 = OS assigns
        serverSocket = ss
        port = ss.localPort
        running = true
        Logger.i(TAG, "X11 proxy listening on localhost:$port")
        executor.submit(::acceptLoop)
    }

    /**
     * Stop accepting connections, close the server socket, and shut down all
     * relay threads. Idempotent — safe to call multiple times.
     */
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing X11 proxy server socket: ${e.message}")
        } finally {
            serverSocket = null
            port = 0
        }
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Logger.i(TAG, "X11 proxy stopped")
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running) {
            try {
                val client = ss.accept()
                executor.submit { handleClient(client) }
            } catch (e: Exception) {
                if (running) {
                    Logger.w(TAG, "X11 proxy accept error: ${e.message}")
                }
                // If running == false the socket was closed intentionally; exit loop
                break
            }
        }
    }

    // ── Per-connection relay ──────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        val xServer = connectToXServer()
        if (xServer == null) {
            Logger.w(TAG, "No X server reachable — dropping X11 channel")
            if (!noServerNotified) {
                noServerNotified = true
                onNoServer()
            }
            try { client.close() } catch (_: Exception) {}
            return
        }

        Logger.d(TAG, "Relaying X11 channel: ${client.remoteSocketAddress} → X server")
        try {
            relay(client.inputStream, xServer.outputStream)
            relay(xServer.inputStream, client.outputStream)
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { xServer.close() } catch (_: Exception) {}
        }
    }

    // ── X server detection ────────────────────────────────────────────────────

    /**
     * Try Termux:X11 first (Unix socket), then XServer XSDL (TCP :6000).
     * Returns a [Closeable] that exposes [InputStream] and [OutputStream],
     * or null if neither is reachable.
     */
    private fun connectToXServer(): XServerConnection? {
        // 1. Termux:X11 (Unix domain socket)
        if (File(TERMUX_X11_SOCKET).exists()) {
            try {
                val localSocket = LocalSocket()
                localSocket.connect(
                    LocalSocketAddress(TERMUX_X11_SOCKET, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                Logger.i(TAG, "Connected to Termux:X11 Unix socket")
                return LocalSocketConnection(localSocket)
            } catch (e: Exception) {
                Logger.w(TAG, "Termux:X11 socket exists but connect failed: ${e.message}")
            }
        }

        // 2. XServer XSDL (TCP)
        try {
            val tcp = Socket()
            tcp.connect(java.net.InetSocketAddress(XSDL_HOST, XSDL_PORT), XSERVER_CONNECT_TIMEOUT_MS)
            Logger.i(TAG, "Connected to XServer XSDL on $XSDL_HOST:$XSDL_PORT")
            return TcpSocketConnection(tcp)
        } catch (e: Exception) {
            Logger.d(TAG, "XServer XSDL not reachable: ${e.message}")
        }

        return null
    }

    // ── Stream relay ──────────────────────────────────────────────────────────

    /**
     * Spawn a daemon thread that copies [input] → [output] until EOF or error.
     */
    private fun relay(input: InputStream, output: OutputStream) {
        executor.submit {
            try {
                val buf = ByteArray(RELAY_BUFFER_SIZE)
                var n = input.read(buf)
                while (n >= 0) {
                    output.write(buf, 0, n)
                    output.flush()
                    n = input.read(buf)
                }
            } catch (_: Exception) {
                // EOF or stream closed — normal relay termination
            }
        }
    }

    // ── Internal connection wrappers ──────────────────────────────────────────

    private interface XServerConnection : java.io.Closeable {
        val inputStream: InputStream
        val outputStream: OutputStream
    }

    private class TcpSocketConnection(private val socket: Socket) : XServerConnection {
        override val inputStream: InputStream get() = socket.inputStream
        override val outputStream: OutputStream get() = socket.outputStream
        override fun close() = socket.close()
    }

    private class LocalSocketConnection(private val socket: LocalSocket) : XServerConnection {
        override val inputStream: InputStream get() = socket.inputStream
        override val outputStream: OutputStream get() = socket.outputStream
        override fun close() = socket.close()
    }
}

/**
 * Thrown via [SSHConnection]'s error listener when X11 forwarding is enabled
 * but no X display server is found on the device. The message is user-facing.
 */
class X11NoServerException : Exception(
    "No X server found on this device. Install Termux:X11 or XServer XSDL, " +
    "start it before connecting, and re-connect with X11 forwarding enabled."
)

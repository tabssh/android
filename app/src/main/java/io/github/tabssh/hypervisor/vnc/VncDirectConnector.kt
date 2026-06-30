package io.github.tabssh.hypervisor.vnc

import android.content.Context
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.hypervisor.console.ConsoleWebSocketClient
import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Establishes a direct TCP connection to a [VncHost] and constructs an
 * [RfbClient] ready to start the RFB handshake.
 *
 * The caller is responsible for:
 *   1. Starting the [RfbClient] on a background thread.
 *   2. Closing the [Socket] when the session ends.
 */
object VncDirectConnector {
    private const val TAG = "VncDirectConnector"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val SO_TIMEOUT_MS = 30_000

    /**
     * Connect to [host] and return an [RfbClient] + the underlying [Socket].
     *
     * @param password    VNC password; may be null for None security or when
     *                    credentials come from an attached [VncIdentity].
     * @param username    VeNCrypt Plain username (only for vencrypt_*_plain sub-types).
     * @param consoleMode When true, configures RfbClient for text-console mode:
     *                    exclusive ClientInit, console-friendly encodings, and
     *                    ExtendedDesktopSize support.  Direct VNC connections are
     *                    always console mode per the VNC-as-console architecture.
     */
    suspend fun connect(
        host: VncHost,
        password: String?,
        username: String? = null,
        context: Context,
        consoleMode: Boolean = true
    ): Pair<RfbClient, Socket> = withContext(Dispatchers.IO) {
        val effectivePort = host.effectivePort
        Logger.d(TAG, "Connecting to ${host.host}:$effectivePort (security=${host.securityType} consoleMode=$consoleMode)")

        val socket = Socket()
        try {
            socket.soTimeout = SO_TIMEOUT_MS
            socket.connect(InetSocketAddress(host.host, effectivePort), CONNECT_TIMEOUT_MS)

            val rfbClient = RfbClient(
                inputStream = socket.inputStream,
                outputStream = socket.outputStream,
                vncPassword = password,
                rawSocket = socket,
                tlsHost = host.host,
                tlsPort = effectivePort,
                tlsVerify = host.tlsVerify,
                vncUsername = username,
                consoleMode = consoleMode
            )
            Logger.d(TAG, "Socket connected; RfbClient constructed for ${host.name}")
            Pair(rfbClient, socket)
        } catch (e: Throwable) {
            // If connect() or the RfbClient constructor throws after the
            // Socket was allocated, the caller never receives it and cannot
            // close it. Close here to prevent file-descriptor leaks on every
            // failed VNC connect attempt.
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Connect to a VNC server tunnelled over a (typically TLS-secured)
     * WebSocket — websockify, noVNC-style endpoints, or any RFB-bearing
     * `ws://` / `wss://` URL.
     *
     * RFB carries its own framing inside binary WebSocket frames; text
     * frames are treated as protocol noise and dropped by
     * [ConsoleWebSocketClient].  TLS (when `wss://`) is owned by OkHttp,
     * so VeNCrypt TLS upgrade is intentionally NOT wired up here — the
     * RFB stream we return is already on a secure transport.
     *
     * The caller owns lifecycle: start the [RfbClient] on a background
     * thread, and call [ConsoleWebSocketClient.disconnect] when the
     * session ends.  Returns [Pair] of `(RfbClient, ConsoleWebSocketClient)`
     * mirroring the (RfbClient, Socket) shape of [connect].
     */
    suspend fun connectWss(
        url: String,
        password: String?,
        headers: Map<String, String> = emptyMap(),
        username: String? = null,
        verifySsl: Boolean = false,
        pinnedCertSha256: String? = null,
        displayHost: String = "",
        displayPort: Int = 0,
        consoleMode: Boolean = false,
        @Suppress("UNUSED_PARAMETER") context: Context
    ): Pair<RfbClient, ConsoleWebSocketClient> = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Connecting RFB-over-WSS to $displayHost:$displayPort consoleMode=$consoleMode")
        val ws = ConsoleWebSocketClient(
            verifySsl = verifySsl,
            protocol = ConsoleWebSocketClient.ConsoleProtocol.RFB_WSS,
            pinnedCertSha256 = pinnedCertSha256,
            displayHost = displayHost,
            displayPort = displayPort
        )
        val ok = ws.connect(url = url, headers = headers, listener = null)
        if (!ok) {
            throw IllegalStateException("ConsoleWebSocketClient.connect returned false for $displayHost:$displayPort")
        }
        // ConsoleWebSocketClient creates the piped streams synchronously inside
        // connect() (before returning), so getInputStream / getOutputStream are
        // already non-null here — the RFB handshake will block on the input
        // side until the server sends bytes, which is exactly what we want.
        val input = ws.getInputStream()
            ?: run { ws.disconnect(); throw IllegalStateException("WSS input stream unavailable") }
        val output = ws.getOutputStream()
            ?: run { ws.disconnect(); throw IllegalStateException("WSS output stream unavailable") }
        val rfb = RfbClient(
            inputStream = input,
            outputStream = output,
            vncPassword = password,
            // No raw socket: TLS is owned by OkHttp, VeNCrypt TLS-upgrade unavailable.
            rawSocket = null,
            tlsHost = displayHost.takeIf { it.isNotEmpty() },
            tlsPort = displayPort,
            tlsVerify = verifySsl,
            vncUsername = username,
            consoleMode = consoleMode
        )
        Logger.d(TAG, "RFB-over-WSS established to $displayHost:$displayPort")
        Pair(rfb, ws)
    }
}

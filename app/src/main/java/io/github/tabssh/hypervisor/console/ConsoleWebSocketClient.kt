package io.github.tabssh.hypervisor.console

import io.github.tabssh.utils.logging.Logger
import okhttp3.*
import okio.ByteString
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebSocket client for hypervisor VM console connections.
 * Provides InputStream/OutputStream interface compatible with TermuxBridge.
 *
 * Supports:
 * - Proxmox VE termproxy/vncproxy WebSocket
 * - XCP-ng/XenServer console WebSocket
 * - Xen Orchestra console WebSocket
 * - VMware console (if available)
 */
class ConsoleWebSocketClient(
    private val verifySsl: Boolean = false,
    private val protocol: ConsoleProtocol = ConsoleProtocol.PROXMOX_TERM,
    private val pinnedCertSha256: String? = null,
    /** Display-only — used by the cert-prompt dialogs to show
     *  "Server: $host:$port" in the body text. The actual WS URL
     *  passed to connect() is the source of truth for routing. */
    private val displayHost: String = "",
    private val displayPort: Int = 0
) {
    companion object {
        private const val TAG = "ConsoleWebSocket"
        // OkHttp pings happen at the WS protocol layer; Proxmox's termproxy
        // doesn't see them, so its idle-inactivity timer (~10 s) still fires.
        // We keep WS pings frequent for transport health AND emit an
        // app-layer Proxmox keepalive every 5 s — see startKeepalive().
        private const val PING_INTERVAL_SECONDS = 10L
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 0L // No timeout for console
        private const val PROXMOX_KEEPALIVE_INTERVAL_MS = 5_000L

        /**
         * Returns true when `text` matches the Proxmox termproxy error that
         * indicates the VM has no serial port configured. Proxmox may send
         * this either as a plain text frame or inside a "0:N:MSG" data
         * envelope, so we check the raw string in both contexts.
         *
         * The two-part check for "unable to find" + "serial" is intentional:
         * the actual Proxmox message is "Unable to find a serial interface",
         * and a single-phrase check like "unable to find serial" misses it
         * because the article "a" sits between "find" and "serial".
         */
        fun isProxmoxSerialError(text: String): Boolean {
            val lower = text.lowercase()
            return (lower.contains("unable to find") && lower.contains("serial")) ||
                lower.contains("serial interface not") ||
                lower.contains("no serial")
        }
    }

    enum class ConsoleProtocol {
        PROXMOX_TERM,  // Proxmox termproxy - uses "0:LENGTH:MSG" format
        PROXMOX_VNC,   // Proxmox vncproxy - RFB protocol
        XCPNG,         // XCP-ng console - raw bytes
        XO,            // Xen Orchestra - raw bytes
        VMWARE,        // VMware VMRC - raw bytes
        // Generic RFB tunnelled over a plain WebSocket (e.g. websockify,
        // noVNC-style ws://host:port/websockify, or any VNC server fronted
        // by a binary WS upgrader). Identical wire semantics to PROXMOX_VNC
        // (text frames are protocol noise, binary frames are raw RFB), kept
        // separate so logs and any future per-vendor quirks stay clean.
        RFB_WSS
    }

    // WebSocket connection
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient

    // Piped streams for bridging WebSocket to InputStream/OutputStream
    private var inputPipeOut: PipedOutputStream? = null
    private var inputPipeIn: PipedInputStream? = null
    private var outputPipeIn: PipedInputStream? = null
    private var outputPipeOut: PipedOutputStream? = null

    // Connection state.
    // @Volatile: read from the keepalive Thread's loop and written from
    // OkHttp's WebSocket callback thread + the disconnect path; without
    // it the keepalive thread can observe a stale `true` after disconnect
    // and emit one extra send before noticing.
    @Volatile private var isConnected = false
    private var connectionListener: ConsoleConnectionListener? = null

    // Terminal size for resize messages
    private var terminalCols: Int = 80
    private var terminalRows: Int = 24

    // App-layer keepalive thread (Issue #52). Proxmox's termproxy closes
    // idle WebSockets after ~10 s; OkHttp's protocol-layer pings don't
    // count as activity. We periodically resend the most-recent resize
    // message — termproxy treats that as live traffic and keeps the
    // connection open.
    private var keepaliveThread: Thread? = null

    // Proxmox first-frame auth (`<userid>:<termproxyTicket>\n`). Set via
    // setProxmoxAuthFrame() before connect(). Sent inside onOpen — without
    // it, termproxy/vncterm closes the WebSocket within ~10s (the user
    // sees a "Software caused connection abort" failure mid-session).
    private var proxmoxAuthFrame: String? = null

    fun setProxmoxAuthFrame(userid: String, ticket: String) {
        proxmoxAuthFrame = "$userid:$ticket\n"
    }

    /**
     * Single-flight failure latch so we don't fire `onError` /
     * `close()` twice if multiple sends fail back-to-back. Reset to
     * false in `connect()`.
     */
    @Volatile private var sendFailureFired = false

    // Set true by disconnect() so the inevitable post-close EOF/onFailure
    // from OkHttp's reader thread doesn't get reported up as a real error.
    // Without this, a clean user-initiated disconnect on Android 16 crashes
    // the activity: WebSocket close → reader EOF → connectionListener.onError
    // → VMConsoleActivity.showError → AlertDialog.show on a finishing
    // activity → BadTokenException.
    @Volatile private var userInitiatedClose = false

    /**
     * Wrapper around `webSocket.send(...)` that turns a `false` return
     * (queue full / socket already closed) into a real connection-lost
     * signal — previously the return value was logged-and-forgotten so
     * user keystrokes silently vanished and the UI showed a session
     * that looked alive but couldn't transmit.
     *
     * On `false` while we still believe we're connected:
     *   1. mark `isConnected = false` so the output-reader loop exits,
     *   2. fire `connectionListener.onError(...)` exactly once,
     *   3. ask the socket to close so the OkHttp listener fires its
     *      own onClosed callback and `cleanup()` runs there.
     *
     * Returns the original Boolean so callers that want to react
     * (e.g. the keepalive thread breaking out of its loop) can do so.
     */
    private fun attemptSend(payload: String, label: String): Boolean {
        val ws = webSocket ?: return false
        val sent = ws.send(payload)
        if (!sent) handleSendFailure(label, "${payload.length} chars")
        return sent
    }

    private fun attemptSend(payload: ByteString, label: String): Boolean {
        val ws = webSocket ?: return false
        val sent = ws.send(payload)
        if (!sent) handleSendFailure(label, "${payload.size} bytes")
        return sent
    }

    private fun handleSendFailure(label: String, sizeStr: String) {
        if (!isConnected) return
        if (sendFailureFired) return
        sendFailureFired = true
        Logger.w(TAG, "WebSocket send REJECTED ($label, $sizeStr) — connection lost; closing")
        isConnected = false
        try {
            connectionListener?.onError(
                java.io.IOException("WebSocket rejected $label send — connection lost")
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Listener.onError threw", e)
        }
        try { webSocket?.close(1011, "send rejected") } catch (_: Exception) {}
    }

    /** Phase 1 TLS pin holder — caller reads via getCapturedCertSha256
     *  after a successful connect to persist a TOFU capture. */
    private val capturedPin = io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.CapturedPin()
    fun getCapturedCertSha256(): String? = capturedPin.sha256

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)

        io.github.tabssh.crypto.tls.HypervisorTrustManagerFactory.installTrust(
            builder, verifySsl, pinnedCertSha256, capturedPin, displayHost, displayPort
        )

        client = builder.build()
    }

    /**
     * Connect to WebSocket console
     * @param url WebSocket URL (wss://...)
     * @param headers Additional headers (auth tokens, tickets)
     * @param listener Connection event listener
     */
    fun connect(
        url: String,
        headers: Map<String, String> = emptyMap(),
        listener: ConsoleConnectionListener? = null
    ): Boolean {
        connectionListener = listener
        sendFailureFired = false

        try {
            // Create piped streams for bidirectional communication
            inputPipeOut = PipedOutputStream()
            inputPipeIn = PipedInputStream(inputPipeOut, 65536) // 64KB buffer
            outputPipeOut = PipedOutputStream()
            outputPipeIn = PipedInputStream(outputPipeOut, 65536)

            // Build request with headers
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }
            // Proxmox vncwebsocket explicitly validates "Sec-WebSocket-Protocol: binary"
            // in its upgrade handler (pveproxy AnyEvent::HTTP::WebSocket) and closes the
            // connection with an error if it is absent.  Other hypervisors that use binary
            // WebSocket frames (XCP-ng XenAPI console, Xen Orchestra, VMware) also expect
            // binary-mode framing, so we advertise the subprotocol for all non-text paths.
            // PROXMOX_TERM uses text frames ("0:LEN:MSG" envelope) and must NOT send this.
            if (protocol != ConsoleProtocol.PROXMOX_TERM) {
                requestBuilder.addHeader("Sec-WebSocket-Protocol", "binary")
            }
            val request = requestBuilder.build()

            Logger.i(TAG, "Connecting to console: $url")

            // Create WebSocket connection
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "Console WebSocket connected")
                    isConnected = true

                    // Proxmox vncterm/termproxy expects the first WS frame
                    // to be `<userid>:<termproxyTicket>\n`. Send it BEFORE
                    // anything else (incl. keepalive) so the server accepts
                    // the session.
                    proxmoxAuthFrame?.let { authFrame ->
                        // Don't go through attemptSend here — `webSocket`
                        // is the local param, not our field; field is set
                        // synchronously by newWebSocket() but we're
                        // already inside its onOpen so the field assign
                        // may not have happened yet on this thread. Use
                        // the local handle and surface failure manually.
                        val sent = webSocket.send(authFrame)
                        Logger.d(TAG, "Sent Proxmox auth frame, accepted=$sent")
                        if (!sent) handleSendFailure("auth", "${authFrame.length} chars")
                    }

                    connectionListener?.onConnected()

                    // Start output reader thread (user input -> WebSocket)
                    startOutputReader()

                    // Start app-layer keepalive for Proxmox termproxy.
                    if (protocol == ConsoleProtocol.PROXMOX_TERM) {
                        startProxmoxKeepalive()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Logger.d(TAG, "Received text: ${text.length} chars: '${text.take(100)}'")
                    try {
                        val actualData = when (protocol) {
                            ConsoleProtocol.PROXMOX_TERM -> {
                                // Parse Proxmox format: "0:LENGTH:MSG"
                                if (text.startsWith("0:")) {
                                    val parts = text.split(":", limit = 3)
                                    if (parts.size == 3) {
                                        val msg = parts[2]
                                        Logger.d(TAG, "Proxmox data message: length=${parts[1]}, data='${msg.take(50)}'")
                                        // Proxmox sends "unable to find serial interface" as
                                        // a data frame when the VM has no serial port
                                        // configured. Treat it as a fatal error rather than
                                        // writing garbage to the terminal. The caller should
                                        // add a serial port (socket) and restart the VM, OR
                                        // switch to VNC mode.
                                        if (isProxmoxSerialError(msg)) {
                                            Logger.w(TAG, "Proxmox serial console error: $msg")
                                            isConnected = false
                                            connectionListener?.onSerialConsoleUnavailable()
                                            return@onMessage
                                        }
                                        msg
                                    } else {
                                        Logger.w(TAG, "Malformed Proxmox message: $text")
                                        text
                                    }
                                } else {
                                    // Might be a ping response or a plain-text error frame.
                                    // Check for the serial error here too — some Proxmox
                                    // versions send it without the "0:N:" envelope.
                                    if (isProxmoxSerialError(text)) {
                                        Logger.w(TAG, "Proxmox serial error (plain frame): $text")
                                        isConnected = false
                                        connectionListener?.onSerialConsoleUnavailable()
                                        return@onMessage
                                    }
                                    Logger.d(TAG, "Non-data Proxmox message: $text")
                                    return@onMessage
                                }
                            }
                            ConsoleProtocol.PROXMOX_VNC, ConsoleProtocol.RFB_WSS -> {
                                // RFB is a binary-only protocol. Any text frame on an
                                // RFB-bearing WebSocket is protocol-level signalling
                                // (auth acknowledgements, websockify status, etc.) that
                                // must never enter the RFB byte stream — writing it to
                                // the pipe would corrupt the RFB handshake and produce
                                // a permanently black screen.
                                Logger.d(TAG, "$protocol: ignoring text frame (RFB is binary-only): '${text.take(80)}'")
                                return@onMessage
                            }
                            else -> text // Raw text for other protocols
                        }

                        val bytesWritten = actualData.toByteArray(Charsets.UTF_8)
                        inputPipeOut?.write(bytesWritten)
                        inputPipeOut?.flush()
                        Logger.d(TAG, "Wrote ${bytesWritten.size} bytes to terminal")
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to input pipe", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Logger.d(TAG, "Received binary: ${bytes.size} bytes")
                    try {
                        // For Proxmox termproxy, check if the binary frame is a
                        // serial-unavailable error before writing to the terminal.
                        // Some Proxmox builds send this error as a binary frame
                        // rather than a text frame, causing raw bytes like
                        // "0KUnable to find a serial interface" to appear on-screen.
                        // We check both the full payload and the payload with the
                        // first byte (Proxmox frame-type byte) stripped.
                        if (protocol == ConsoleProtocol.PROXMOX_TERM) {
                            val text = bytes.utf8()
                            val stripped = if (bytes.size > 1) text.drop(1) else text
                            if (isProxmoxSerialError(text) || isProxmoxSerialError(stripped)) {
                                Logger.w(TAG, "Proxmox serial error (binary frame): $text")
                                isConnected = false
                                connectionListener?.onSerialConsoleUnavailable()
                                return
                            }
                        }
                        // PROXMOX_VNC: binary frames are raw RFB protocol bytes.
                        // They flow through the pipe to RfbClient unchanged;
                        // RfbClient owns the protocol handshake and decode loop.
                        inputPipeOut?.write(bytes.toByteArray())
                        inputPipeOut?.flush()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing binary to input pipe", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.w(TAG, "Console WebSocket closing: code=$code reason='$reason'")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.w(TAG, "Console WebSocket closed: code=$code reason='$reason'")
                    isConnected = false
                    if (userInitiatedClose) {
                        // We initiated this — don't fire onDisconnected; the
                        // activity is on its way down.
                        cleanup()
                        return
                    }
                    // Notify with detailed reason
                    val detailedReason = if (reason.isBlank()) "Connection closed (code $code)" else reason
                    connectionListener?.onDisconnected(detailedReason)
                    cleanup()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isConnected = false
                    if (userInitiatedClose) {
                        // EOFException after a local close is expected — OkHttp's
                        // reader thread sees EOF when we tear down the socket.
                        Logger.d(TAG, "WebSocket EOF after user-initiated disconnect (expected)")
                        cleanup()
                        return
                    }
                    val errorMsg = "WebSocket failure: ${t.message}" +
                        if (response != null) " (HTTP ${response.code})" else ""
                    Logger.e(TAG, errorMsg, t)
                    connectionListener?.onError(t)
                    cleanup()
                }
            })

            return true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to connect", e)
            connectionListener?.onError(e)
            return false
        }
    }

    /**
     * Start a low-frequency keepalive thread for Proxmox termproxy.
     * Issue #52: termproxy kills idle WebSockets after ~10 s of no
     * application-layer traffic. We resend the most-recent resize
     * message every 5 s — it's a no-op for the terminal but counts as
     * a live message on the server.
     */
    private fun startProxmoxKeepalive() {
        keepaliveThread?.interrupt()
        keepaliveThread = Thread {
            try {
                while (isConnected) {
                    Thread.sleep(PROXMOX_KEEPALIVE_INTERVAL_MS)
                    if (!isConnected) break
                    val msg = "1:$terminalCols:$terminalRows:"
                    val sent = attemptSend(msg, "keepalive")
                    Logger.d(TAG, "Proxmox keepalive sent=$sent")
                    // attemptSend has already triggered the disconnect
                    // path on `false`; bail explicitly so we don't sleep
                    // for another 5 s before noticing.
                    if (!sent) break
                }
            } catch (e: InterruptedException) {
                Logger.d(TAG, "Keepalive thread interrupted")
            } catch (e: Exception) {
                Logger.w(TAG, "Keepalive thread error", e)
            }
        }.apply {
            name = "ConsoleProxmoxKeepalive"
            isDaemon = true
            start()
        }
    }

    /**
     * Start thread to read from output pipe and send to WebSocket
     */
    private fun startOutputReader() {
        Thread {
            val buffer = ByteArray(4096)
            try {
                while (isConnected) {
                    val bytesRead = outputPipeIn?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        sendToWebSocket(data)
                    } else if (bytesRead < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Logger.e(TAG, "Error reading from output pipe", e)
                }
            }
        }.apply {
            name = "ConsoleOutputReader"
            isDaemon = true
            start()
        }
    }

    /**
     * Send data to WebSocket with protocol-specific formatting
     */
    private fun sendToWebSocket(data: ByteArray) {
        when (protocol) {
            ConsoleProtocol.PROXMOX_TERM -> {
                // Proxmox termproxy format: "0:LENGTH:MSG"
                // LENGTH is byte length, MSG is the actual bytes as string
                val msg = String(data, Charsets.ISO_8859_1) // Use ISO_8859_1 to preserve binary data
                val packet = "0:${data.size}:$msg"
                val bytesCodes = data.map { it.toInt() and 0xFF }
                Logger.d(TAG, "Proxmox format: sending ${data.size} bytes: $bytesCodes")
                Logger.d(TAG, "Proxmox packet: '$packet' (${packet.length} chars)")
                val sent = attemptSend(packet, "user-input")
                Logger.d(TAG, "WebSocket send result: $sent")
            }
            else -> {
                // Other protocols: send raw bytes
                Logger.d(TAG, "Raw format: sending ${data.size} bytes")
                attemptSend(ByteString.of(*data), "user-input")
            }
        }
    }

    /**
     * Get InputStream for reading console output (from VM)
     */
    fun getInputStream(): InputStream? = inputPipeIn

    /**
     * Get OutputStream for writing console input (to VM)
     */
    fun getOutputStream(): OutputStream? = outputPipeOut

    /**
     * Send text directly to console
     */
    fun sendText(text: String) {
        if (isConnected) {
            attemptSend(text, "sendText")
        }
    }

    /**
     * Send binary data directly to console
     */
    fun sendBytes(data: ByteArray) {
        if (isConnected) {
            attemptSend(ByteString.of(*data), "sendBytes")
        }
    }

    /**
     * Send terminal resize notification (Proxmox termproxy format: "1:COLS:ROWS:")
     */
    fun sendResize(cols: Int, rows: Int) {
        terminalCols = cols
        terminalRows = rows

        if (isConnected && protocol == ConsoleProtocol.PROXMOX_TERM) {
            val resizeMsg = "1:$cols:$rows:"
            val sent = attemptSend(resizeMsg, "resize")
            Logger.d(TAG, "Sent resize: $cols x $rows (accepted=$sent)")
        }
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        Logger.i(TAG, "Disconnecting console WebSocket")
        userInitiatedClose = true
        isConnected = false
        keepaliveThread?.interrupt()
        keepaliveThread = null
        webSocket?.close(1000, "User disconnected")
        cleanup()
    }

    private fun cleanup() {
        try {
            inputPipeOut?.close()
            inputPipeIn?.close()
            outputPipeOut?.close()
            outputPipeIn?.close()
        } catch (e: Exception) {
            Logger.w(TAG, "Error closing pipes", e)
        }
        inputPipeOut = null
        inputPipeIn = null
        outputPipeOut = null
        outputPipeIn = null
        webSocket = null
    }
}

/**
 * Listener for console connection events
 */
interface ConsoleConnectionListener {
    fun onConnected()
    fun onDisconnected(reason: String)
    fun onError(error: Throwable)
    /** Called when Proxmox termproxy reports the VM has no serial interface. */
    fun onSerialConsoleUnavailable() {}
}

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
    private val verifySsl: Boolean = false
) {
    companion object {
        private const val TAG = "ConsoleWebSocket"
        private const val PING_INTERVAL_SECONDS = 30L
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 0L // No timeout for console
    }

    // WebSocket connection
    private var webSocket: WebSocket? = null
    private val client: OkHttpClient

    // Piped streams for bridging WebSocket to InputStream/OutputStream
    private var inputPipeOut: PipedOutputStream? = null
    private var inputPipeIn: PipedInputStream? = null
    private var outputPipeIn: PipedInputStream? = null
    private var outputPipeOut: PipedOutputStream? = null

    // Connection state
    private var isConnected = false
    private var connectionListener: ConsoleConnectionListener? = null

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)

        if (!verifySsl) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

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
            val request = requestBuilder.build()

            Logger.i(TAG, "Connecting to console: $url")

            // Create WebSocket connection
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.i(TAG, "Console WebSocket connected")
                    isConnected = true
                    connectionListener?.onConnected()

                    // Start output reader thread (user input -> WebSocket)
                    startOutputReader()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Logger.d(TAG, "Received text: ${text.length} chars")
                    try {
                        inputPipeOut?.write(text.toByteArray(Charsets.UTF_8))
                        inputPipeOut?.flush()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing to input pipe", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    Logger.d(TAG, "Received binary: ${bytes.size} bytes")
                    try {
                        inputPipeOut?.write(bytes.toByteArray())
                        inputPipeOut?.flush()
                    } catch (e: Exception) {
                        Logger.e(TAG, "Error writing binary to input pipe", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "Console WebSocket closing: $code $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Logger.i(TAG, "Console WebSocket closed: $code $reason")
                    isConnected = false
                    connectionListener?.onDisconnected(reason)
                    cleanup()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.e(TAG, "Console WebSocket failure", t)
                    isConnected = false
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
                        webSocket?.send(ByteString.of(*data))
                        Logger.d(TAG, "Sent $bytesRead bytes to WebSocket")
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
            webSocket?.send(text)
        }
    }

    /**
     * Send binary data directly to console
     */
    fun sendBytes(data: ByteArray) {
        if (isConnected) {
            webSocket?.send(ByteString.of(*data))
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
        isConnected = false
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
}

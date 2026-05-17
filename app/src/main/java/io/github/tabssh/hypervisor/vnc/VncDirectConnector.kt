package io.github.tabssh.hypervisor.vnc

import android.content.Context
import io.github.tabssh.TabSSHApplication
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
     * @param password  VNC password; may be null for None security or when
     *                  credentials come from an attached [VncIdentity].
     * @param username  VeNCrypt Plain username (only for vencrypt_*_plain sub-types).
     */
    suspend fun connect(
        host: VncHost,
        password: String?,
        username: String? = null,
        context: Context
    ): Pair<RfbClient, Socket> = withContext(Dispatchers.IO) {
        val effectivePort = host.effectivePort
        Logger.d(TAG, "Connecting to ${host.host}:$effectivePort (security=${host.securityType})")

        val socket = Socket()
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
            vncUsername = username
        )
        Logger.d(TAG, "Socket connected; RfbClient constructed for ${host.name}")
        Pair(rfbClient, socket)
    }
}

package io.github.tabssh.hypervisor.vnc

import io.github.tabssh.hypervisor.console.rfb.RfbConstants
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Answers one question before a `vnc://` link is opened: will this server
 * demand a password?
 *
 * A `vnc://` URI often carries no credential, and there is no saved host
 * behind it to pull one from. Asking the user for a password unconditionally
 * would be wrong (most console servers offered by a hypervisor use security
 * type None), and letting the session fail mid-handshake would put the error
 * in a tab the launching activity has already walked away from. So the
 * launcher performs this short probe first: version handshake, read the
 * offered security types, close.
 *
 * The probe deliberately stops before choosing a security type — it never
 * authenticates, never sends a credential, and never reaches ClientInit, so
 * it cannot disturb a server that only permits one session at a time beyond
 * the connection it opens and immediately closes.
 *
 * The decision mirrors [io.github.tabssh.hypervisor.console.rfb.RfbClient]'s
 * own preference order (VeNCrypt, then None, then VNC auth): a password is
 * only needed when neither VeNCrypt nor None is on offer.
 */
object VncAuthProbe {
    private const val TAG = "VncAuthProbe"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val SO_TIMEOUT_MS = 10_000

    /** Length of the RFB ProtocolVersion greeting, "RFB 003.008\n". */
    private const val VERSION_MSG_LEN = 12

    /**
     * Connect to `host:port`, run [requiresPassword] over the socket, and
     * close it again.
     *
     * Returns false on any I/O or protocol failure: the probe is an
     * optimisation, and a server we could not reach here will produce a
     * proper, user-visible error on the real connect attempt a moment later.
     */
    suspend fun requiresPassword(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            socket.soTimeout = SO_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            requiresPassword(socket.inputStream, socket.outputStream)
        } catch (e: Exception) {
            Logger.d(TAG, "Auth probe failed for $host:$port (${e.javaClass.simpleName}); assuming no password")
            false
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.d(TAG, "Probe socket close failed: ${e.message}")
            }
        }
    }

    /**
     * Stream-level probe, split out so it can be unit-tested without a
     * socket.
     *
     * Reads the server's ProtocolVersion, echoes the highest version both
     * sides understand, then reads the security types the server offers and
     * decides whether VNC-auth is the only usable option.
     *
     * Every field is bounded: the greeting is a fixed 12 bytes and the type
     * count is a single unsigned byte, so a hostile server cannot make this
     * allocate or block indefinitely (the caller's socket timeout bounds the
     * reads themselves).
     */
    fun requiresPassword(input: InputStream, output: OutputStream): Boolean {
        val din = DataInputStream(input)

        val greeting = ByteArray(VERSION_MSG_LEN)
        din.readFully(greeting)
        val text = String(greeting, Charsets.US_ASCII)
        if (!text.startsWith("RFB ")) return false

        val minor = text.substring(8, 11).toIntOrNull() ?: return false
        val negotiated = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        output.write("RFB 003.00$negotiated\n".toByteArray(Charsets.US_ASCII))
        output.flush()

        // RFB 3.3: the server dictates a single security type as a U32.
        if (negotiated == 3) {
            return din.readInt() == RfbConstants.SECURITY_VNC_AUTH
        }

        val count = din.readUnsignedByte()
        // A zero count means the server rejected the connection and a reason
        // string follows; the real connect attempt will surface it.
        if (count == 0) return false

        val types = ByteArray(count)
        din.readFully(types)
        val offered = types.map { it.toInt() and 0xFF }

        return !offered.contains(RfbConstants.SECURITY_VENCRYPT) &&
            !offered.contains(RfbConstants.SECURITY_NONE) &&
            offered.contains(RfbConstants.SECURITY_VNC_AUTH)
    }
}

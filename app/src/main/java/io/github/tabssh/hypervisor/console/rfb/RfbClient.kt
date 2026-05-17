package io.github.tabssh.hypervisor.console.rfb

import io.github.tabssh.hypervisor.console.rfb.PixelFormat.Companion.toBytes
import io.github.tabssh.utils.logging.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * RFB 3.8 client state machine.
 *
 * Runs entirely on a dedicated background thread. Callers post pointer/key
 * events from any thread via the send* methods (internally synchronized on
 * the output stream).
 *
 * Lifecycle:
 *   1. Construct with the transport streams (from ConsoleWebSocketClient).
 *   2. Set [listener] before calling [start].
 *   3. [start] spawns the reader thread; returns immediately.
 *   4. [stop] tears down the thread and closes streams.
 *
 * The framebuffer is an ARGB_8888 IntArray maintained here and exposed
 * to the listener via [RfbListener.onFramebufferUpdate] with the dirty
 * rectangle — the listener (VncView) copies the updated region.
 */
class RfbClient(
    private val inputStream: InputStream,
    private val outputStream: OutputStream,
    /** VNC password for SECURITY_VNC_AUTH / VeNCrypt Vnc/Plain sub-types; null means None-only connections. */
    private val vncPassword: String? = null,
    /** Underlying TCP socket; required for VeNCrypt TLS upgrade (stream replacement). */
    private val rawSocket: Socket? = null,
    /** Hostname used for TLS SNI during VeNCrypt handshake. */
    private val tlsHost: String? = null,
    /** Port used for TLS SNI hint (informational); not sent over the wire. */
    private val tlsPort: Int = 5900,
    /** Whether to verify the server certificate during VeNCrypt TLS upgrade. */
    private val tlsVerify: Boolean = false,
    /** Username for VeNCrypt Plain sub-types. */
    private val vncUsername: String? = null,
    /**
     * When true, operate in text-console mode:
     *  - ClientInit shared-flag = 0 (exclusive access)
     *  - Advertise only console-friendly encodings (Raw, CopyRect, no Tight/ZRLE)
     *  - Support ExtendedDesktopSize for client-initiated resize
     */
    val consoleMode: Boolean = false
) {
    companion object {
        private const val TAG = "RfbClient"
        private const val RFB_VERSION = "RFB 003.008\n"

        /**
         * Encodings advertised to the server in full GUI mode, in preference order.
         * Servers pick the first one they support.
         */
        private val PREFERRED_ENCODINGS = intArrayOf(
            RfbConstants.ENC_TIGHT,        // TigerVNC default
            RfbConstants.ENC_ZRLE,         // Proxmox preferred
            RfbConstants.ENC_ZLIB,
            RfbConstants.ENC_HEXTILE,
            RfbConstants.ENC_COPY_RECT,
            RfbConstants.ENC_RRE,
            RfbConstants.ENC_RAW,
            RfbConstants.ENC_DESKTOP_SIZE,
            RfbConstants.ENC_CURSOR,
            RfbConstants.ENC_LAST_RECT
        )

        /**
         * Encodings for text-console mode.  Compression encodings (Tight, ZRLE,
         * Zlib) are omitted so the Raw byte path is deterministic — important for
         * parsing VT100/ANSI output from text-mode framebuffers.
         * ExtendedDesktopSize is included to enable client-initiated resize via
         * SetDesktopSize (RFB message type 251).
         */
        private val CONSOLE_ENCODINGS = intArrayOf(
            RfbConstants.ENC_RAW,
            RfbConstants.ENC_COPY_RECT,
            RfbConstants.ENC_DESKTOP_SIZE,
            RfbConstants.ENC_EXTENDED_DESKTOP_SIZE,
            RfbConstants.ENC_CURSOR,
            RfbConstants.ENC_LAST_RECT
        )
    }

    var listener: RfbListener? = null

    private var din = DataInputStream(inputStream.buffered(65536))
    private var dout = DataOutputStream(outputStream)
    private val outLock = Any()        // guards all writes to dout

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null

    // Framebuffer state (mutated only on reader thread; shared to listener via callbacks)
    private var fbWidth = 0
    private var fbHeight = 0
    private var framebuffer = IntArray(0)
    private lateinit var decoder: RfbDecoder
    private lateinit var pixelFormat: PixelFormat

    // Software cursor (optional; composited by VncView)
    var cursorPixels: IntArray? = null
        private set
    var cursorMask: ByteArray? = null
        private set
    var cursorW = 0; private set
    var cursorH = 0; private set
    var cursorHotX = 0; private set
    var cursorHotY = 0; private set

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Start the RFB handshake + event loop on a background thread. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        readerThread = Thread(::runProtocol, "RfbClient-reader").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Tear down the reader thread and close the transport streams. */
    fun stop() {
        running.set(false)
        readerThread?.interrupt()
        readerThread = null
        try { inputStream.close() } catch (_: Exception) {}
        try { outputStream.close() } catch (_: Exception) {}
        decoder.reset()
    }

    // ── Protocol state machine ────────────────────────────────────────────

    private fun runProtocol() {
        try {
            handshake()
            authenticate()
            serverInit()
            sendSetPixelFormat()
            sendSetEncodings()
            sendFullUpdateRequest()
            eventLoop()
        } catch (e: InterruptedException) {
            Logger.d(TAG, "RFB reader interrupted")
        } catch (e: Exception) {
            if (running.get()) {
                Logger.e(TAG, "RFB protocol error", e)
                listener?.onError("VNC connection lost: ${e.message ?: e.javaClass.simpleName}")
            }
        } finally {
            running.set(false)
        }
    }

    // ── Handshake ────────────────────────────────────────────────────────

    private fun handshake() {
        // Server sends "RFB XXX.YYY\n"
        val serverVersion = ByteArray(12)
        din.readFully(serverVersion)
        val verStr = String(serverVersion, Charsets.US_ASCII)
        Logger.i(TAG, "Server RFB version: ${verStr.trim()}")
        // We respond with 3.8 regardless of what the server offered
        synchronized(outLock) {
            dout.write(RFB_VERSION.toByteArray(Charsets.US_ASCII))
            dout.flush()
        }
    }

    // ── Security ─────────────────────────────────────────────────────────

    private fun authenticate() {
        val numTypes = din.readUnsignedByte()
        if (numTypes == 0) {
            val reasonLen = din.readInt()
            val reason = ByteArray(reasonLen)
            din.readFully(reason)
            throw Exception("Server rejected connection: ${String(reason)}")
        }
        val types = ByteArray(numTypes)
        din.readFully(types)
        Logger.d(TAG, "Security types offered: ${types.map { it.toInt() and 0xFF }}")

        // Prefer VeNCrypt (when rawSocket is available), then None, then VNC Auth.
        val chosen = when {
            types.contains(RfbConstants.SECURITY_VENCRYPT.toByte()) && rawSocket != null ->
                RfbConstants.SECURITY_VENCRYPT
            types.contains(RfbConstants.SECURITY_NONE.toByte()) -> RfbConstants.SECURITY_NONE
            types.contains(RfbConstants.SECURITY_VNC_AUTH.toByte()) && vncPassword != null ->
                RfbConstants.SECURITY_VNC_AUTH
            types.contains(RfbConstants.SECURITY_VNC_AUTH.toByte()) ->
                throw Exception(
                    "Server requires VNC password authentication but no password was provided."
                )
            else -> throw Exception("No supported security type in ${types.map { it.toInt() and 0xFF }}")
        }
        Logger.d(TAG, "Chose security type: $chosen")
        synchronized(outLock) { dout.writeByte(chosen); dout.flush() }

        when (chosen) {
            RfbConstants.SECURITY_NONE -> Unit // SecurityResult follows immediately

            RfbConstants.SECURITY_VNC_AUTH -> {
                // Read 16-byte DES challenge, encrypt with bit-reversed password key.
                val challenge = ByteArray(16)
                din.readFully(challenge)
                val response = vncDesEncrypt(vncPassword!!, challenge)
                synchronized(outLock) { dout.write(response); dout.flush() }
            }

            RfbConstants.SECURITY_VENCRYPT -> {
                authenticateVeNCrypt()
                // VeNCrypt security result is 1 byte (0=OK), not 4 bytes.
                val result = din.readUnsignedByte()
                if (result != 0) throw Exception("VeNCrypt authentication failed")
                Logger.d(TAG, "VeNCrypt authentication OK")
                return // skip the 4-byte SecurityResult block below
            }
        }

        // SecurityResult (u32): 0 = OK, anything else = failure with reason string
        val result = din.readInt()
        if (result != 0) {
            val reasonLen = din.readInt()
            val reason = ByteArray(reasonLen); din.readFully(reason)
            throw Exception("Authentication failed: ${String(reason)}")
        }
        Logger.d(TAG, "Authentication OK")
    }

    /**
     * VeNCrypt handshake (security type 19, RFB 3.8 extension).
     * Negotiates a TLS-based sub-type, upgrades the stream, then performs
     * secondary auth (None / VNC DES challenge / Plain credentials).
     * On return, [din] and [dout] are already rewrapped around the TLS socket.
     */
    private fun authenticateVeNCrypt() {
        // Version exchange: server sends major (u8) + minor (u8)
        val major = din.readUnsignedByte()
        val minor = din.readUnsignedByte()
        Logger.d(TAG, "VeNCrypt version offered: $major.$minor")
        if (major != 0 || minor != 2) {
            throw Exception("Unsupported VeNCrypt version $major.$minor (expected 0.2)")
        }
        // Client sends back 0x00 0x02 (the only version we support)
        synchronized(outLock) { dout.writeByte(0); dout.writeByte(2); dout.flush() }

        // Server sends OK byte (1=accepted, 0=rejected)
        val versionOk = din.readUnsignedByte()
        if (versionOk != 1) throw Exception("Server rejected VeNCrypt version")

        // Server sends sub-type count (u8) then list of u32 sub-types
        val subTypeCount = din.readUnsignedByte()
        val subTypes = IntArray(subTypeCount) { din.readInt() }
        Logger.d(TAG, "VeNCrypt sub-types offered: ${subTypes.toList()}")

        // Preference order: X509None > TLSNone > X509Vnc > TLSVnc > X509Plain > TLSPlain
        val preferenceOrder = listOf(
            RfbConstants.VENCRYPT_X509_NONE,
            RfbConstants.VENCRYPT_TLS_NONE,
            RfbConstants.VENCRYPT_X509_VNC,
            RfbConstants.VENCRYPT_TLS_VNC,
            RfbConstants.VENCRYPT_X509_PLAIN,
            RfbConstants.VENCRYPT_TLS_PLAIN
        )
        val chosen = preferenceOrder.firstOrNull { subTypes.contains(it) }
            ?: throw Exception("No supported VeNCrypt sub-type in ${subTypes.toList()}")
        Logger.d(TAG, "Chose VeNCrypt sub-type: $chosen")

        // Send chosen sub-type as u32
        synchronized(outLock) { dout.writeInt(chosen); dout.flush() }

        // Server sends accepted byte (1=yes)
        val accepted = din.readUnsignedByte()
        if (accepted != 1) throw Exception("Server rejected VeNCrypt sub-type $chosen")

        // Upgrade to TLS
        val sslSocket = upgradeTls()

        // Replace streams — all subsequent RFB traffic goes over TLS
        synchronized(outLock) {
            din = DataInputStream(sslSocket.inputStream.buffered(65536))
            dout = DataOutputStream(sslSocket.outputStream)
        }

        // Secondary authentication
        when (chosen) {
            RfbConstants.VENCRYPT_TLS_NONE,
            RfbConstants.VENCRYPT_X509_NONE -> {
                // No secondary auth; SecurityResult follows
            }

            RfbConstants.VENCRYPT_TLS_VNC,
            RfbConstants.VENCRYPT_X509_VNC -> {
                // VNC DES challenge auth
                val challenge = ByteArray(16)
                din.readFully(challenge)
                val response = vncDesEncrypt(
                    vncPassword ?: throw Exception("VNC password required for VeNCrypt VNC auth"),
                    challenge
                )
                synchronized(outLock) { dout.write(response); dout.flush() }
            }

            RfbConstants.VENCRYPT_TLS_PLAIN,
            RfbConstants.VENCRYPT_X509_PLAIN -> {
                // Plain credentials: 4-byte username length + bytes, 4-byte password length + bytes
                val user = (vncUsername ?: "").toByteArray(Charsets.UTF_8)
                val pass = (vncPassword ?: "").toByteArray(Charsets.UTF_8)
                synchronized(outLock) {
                    dout.writeInt(user.size)
                    dout.write(user)
                    dout.writeInt(pass.size)
                    dout.write(pass)
                    dout.flush()
                }
            }
        }
    }

    /** Wrap [rawSocket] with TLS. Uses a permissive trust manager when [tlsVerify] is false. */
    private fun upgradeTls(): SSLSocket {
        val socket = rawSocket ?: throw Exception("No raw socket for TLS upgrade")
        val trustManagers: Array<TrustManager> = if (tlsVerify) {
            // Use the platform default trust store
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            tmf.init(null as java.security.KeyStore?)
            tmf.trustManagers
        } else {
            // Accept all certificates (caller opted out of verification)
            arrayOf(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            })
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustManagers, null)
        val sf = ctx.socketFactory
        val sslSocket = sf.createSocket(socket, tlsHost ?: socket.inetAddress.hostAddress, tlsPort, true) as SSLSocket
        sslSocket.useClientMode = true
        if (tlsHost != null) {
            val params = sslSocket.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(tlsHost))
            sslSocket.sslParameters = params
        }
        sslSocket.startHandshake()
        Logger.d(TAG, "VeNCrypt TLS handshake complete; cipher=${sslSocket.session.cipherSuite}")
        return sslSocket
    }

    /**
     * Encrypt a 16-byte VNC DES challenge.
     *
     * VNC DES uses a non-standard key schedule where the bits within each byte
     * of the password are reversed before passing to DES. The password is
     * zero-padded or truncated to 8 bytes. The challenge is split into two
     * 8-byte blocks each encrypted independently (ECB mode, no padding).
     */
    private fun vncDesEncrypt(password: String, challenge: ByteArray): ByteArray {
        // Build 8-byte key: take up to 8 chars, zero-pad, reverse bits in each byte
        val raw = password.toByteArray(Charsets.ISO_8859_1)
        val key = ByteArray(8) { i -> if (i < raw.size) reverseBits(raw[i]) else 0 }

        val keySpec = javax.crypto.spec.DESKeySpec(key)
        val keyFactory = javax.crypto.SecretKeyFactory.getInstance("DES")
        val secretKey = keyFactory.generateSecret(keySpec)
        val cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
        return cipher.doFinal(challenge)
    }

    /** Reverse the bit order within a single byte (VNC DES key requirement). */
    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xFF
        var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x ushr 1 }
        return r.toByte()
    }

    // ── Server init ──────────────────────────────────────────────────────

    private fun serverInit() {
        // ClientInit: shared flag.  Console mode requests exclusive access (0)
        // so the text session is not corrupted by another viewer's pointer events.
        synchronized(outLock) { dout.writeByte(if (consoleMode) 0 else 1); dout.flush() }

        // ServerInit: width, height, pixel-format (16 bytes), name-length, name
        fbWidth = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        val serverFmt = PixelFormat.readFrom(din)
        Logger.i(TAG, "Server framebuffer: ${fbWidth}×$fbHeight bpp=${serverFmt.bitsPerPixel}/depth=${serverFmt.depth}")

        val nameLen = din.readInt()
        val nameBytes = ByteArray(nameLen); din.readFully(nameBytes)
        val name = String(nameBytes, Charsets.UTF_8)
        Logger.i(TAG, "Desktop name: $name")

        framebuffer = IntArray(fbWidth * fbHeight)
        pixelFormat = PixelFormat.PREFERRED
        decoder = RfbDecoder(pixelFormat)

        listener?.onConnected(fbWidth, fbHeight, name, framebuffer)
    }

    // ── Client messages ───────────────────────────────────────────────────

    private fun sendSetPixelFormat() {
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_SET_PIXEL_FORMAT)
            dout.write(ByteArray(3))                       // 3 bytes padding
            dout.write(PixelFormat.PREFERRED.toBytes())
            dout.flush()
        }
    }

    private fun sendSetEncodings() {
        val encodings = if (consoleMode) CONSOLE_ENCODINGS else PREFERRED_ENCODINGS
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_SET_ENCODINGS)
            dout.writeByte(0)                              // padding
            dout.writeShort(encodings.size)
            for (enc in encodings) dout.writeInt(enc)
            dout.flush()
        }
    }

    /**
     * Request the server to resize its framebuffer (RFB message type 251,
     * SetDesktopSize extension).  Only meaningful when [consoleMode] is true
     * and [RfbConstants.ENC_EXTENDED_DESKTOP_SIZE] was advertised in
     * [sendSetEncodings].  The server acknowledges via an
     * [RfbConstants.ENC_EXTENDED_DESKTOP_SIZE] pseudo-rectangle.
     */
    fun sendSetDesktopSize(width: Int, height: Int) {
        synchronized(outLock) {
            dout.writeByte(251)        // SetDesktopSize message type
            dout.writeByte(0)          // padding
            dout.writeShort(width)
            dout.writeShort(height)
            dout.writeShort(1)         // number of screens
            dout.writeShort(0)         // padding
            // Screen descriptor: id, x, y, width, height, flags
            dout.writeInt(0)           // screen id
            dout.writeShort(0)         // x-position
            dout.writeShort(0)         // y-position
            dout.writeShort(width)     // width
            dout.writeShort(height)    // height
            dout.writeInt(0)           // flags
            dout.flush()
        }
        Logger.d(TAG, "SetDesktopSize sent: ${width}×$height")
    }

    private fun sendFullUpdateRequest() {
        sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
    }

    private fun sendUpdateRequest(x: Int, y: Int, w: Int, h: Int, incremental: Boolean) {
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_FRAMEBUFFER_UPDATE_REQUEST)
            dout.writeByte(if (incremental) 1 else 0)
            dout.writeShort(x); dout.writeShort(y)
            dout.writeShort(w); dout.writeShort(h)
            dout.flush()
        }
    }

    // ── Event loop ────────────────────────────────────────────────────────

    private fun eventLoop() {
        while (running.get()) {
            when (val msgType = din.readUnsignedByte()) {
                RfbConstants.S2C_FRAMEBUFFER_UPDATE -> handleFramebufferUpdate()
                RfbConstants.S2C_SET_COLOUR_MAP_ENTRIES -> skipColourMap()
                RfbConstants.S2C_BELL -> listener?.onBell()
                RfbConstants.S2C_SERVER_CUT_TEXT -> handleServerCutText()
                else -> {
                    Logger.w(TAG, "Unknown server message type $msgType — cannot resync, aborting")
                    throw Exception("Unknown RFB message type $msgType")
                }
            }
        }
    }

    private fun handleFramebufferUpdate() {
        din.skipBytes(1) // padding
        val numRects = din.readUnsignedShort()

        // numRects == 0xFFFF means "unlimited"; stop on ENC_LAST_RECT instead
        var i = 0
        while (i < numRects || numRects == 0xFFFF) {
            val rx = din.readUnsignedShort()
            val ry = din.readUnsignedShort()
            val rw = din.readUnsignedShort()
            val rh = din.readUnsignedShort()
            val encoding = din.readInt()

            when (encoding) {
                RfbConstants.ENC_LAST_RECT -> break // no data; update is complete
                RfbConstants.ENC_DESKTOP_SIZE -> {
                    // Server-side resize: rx/ry hold the new dimensions
                    Logger.i(TAG, "DesktopSize pseudo-rect: ${rx}×$ry (was ${fbWidth}×$fbHeight)")
                    fbWidth = rx; fbHeight = ry
                    framebuffer = IntArray(fbWidth * fbHeight)
                    listener?.onDesktopResize(fbWidth, fbHeight, framebuffer)
                    sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
                }
                RfbConstants.ENC_EXTENDED_DESKTOP_SIZE -> {
                    // Client-initiated resize acknowledgement (or server-side change).
                    // Payload: 2-byte numScreens + 2-byte padding + 16 bytes per screen.
                    // rx = reason (0=server, 1=this client, 2=other client)
                    // ry = status (0=OK, 1=prohibited, 2=out of resources, 3=invalid)
                    // rw/rh = new framebuffer dimensions (valid when ry==0)
                    val numScreens = din.readUnsignedShort()
                    din.skipBytes(2)               // padding
                    din.skipBytes(numScreens * 16) // screen descriptors (id, x, y, w, h, flags)
                    if (ry == 0 && rw > 0 && rh > 0) {
                        Logger.i(TAG, "ExtendedDesktopSize: ${rw}×$rh (reason=$rx)")
                        fbWidth = rw; fbHeight = rh
                        framebuffer = IntArray(fbWidth * fbHeight)
                        listener?.onDesktopResize(fbWidth, fbHeight, framebuffer)
                        sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
                    } else if (ry != 0) {
                        Logger.w(TAG, "ExtendedDesktopSize rejected: reason=$rx status=$ry")
                    }
                }
                RfbConstants.ENC_CURSOR -> {
                    // Software cursor: rw×rh cursor image + bitmask
                    handleCursor(rx, ry, rw, rh)
                }
                else -> {
                    if (rw > 0 && rh > 0) {
                        decoder.decodeRect(din, framebuffer, fbWidth, rx, ry, rw, rh, encoding)
                        listener?.onFramebufferUpdate(rx, ry, rw, rh, framebuffer)
                    }
                }
            }
            i++
        }

        // Throttle the next incremental update request to ~60 FPS max.
        // Without this the loop spins as fast as the network allows: each
        // FramebufferUpdate triggers an immediate request, which triggers
        // another update, etc. The resulting pipe writes (to feed the
        // terminal bridge) outpace the consumer when the activity is in the
        // background, filling the pipe buffer and blocking the I/O thread.
        if (running.get()) {
            Thread.sleep(16) // ~60 FPS ceiling
            sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = true)
        }
    }

    private fun handleCursor(hotX: Int, hotY: Int, w: Int, h: Int) {
        val pixBytes = w * h * pixelFormat.bytesPerPixel
        val maskBytes = ((w + 7) / 8) * h
        val pixBuf = ByteArray(pixBytes)
        val maskBuf = ByteArray(maskBytes)
        if (pixBytes > 0) din.readFully(pixBuf)
        if (maskBytes > 0) din.readFully(maskBuf)

        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            pixels[i] = pixelFormat.toArgb(pixBuf, i * pixelFormat.bytesPerPixel)
        }
        cursorW = w; cursorH = h
        cursorHotX = hotX; cursorHotY = hotY
        cursorPixels = pixels
        cursorMask = maskBuf
        listener?.onCursorUpdate(hotX, hotY, w, h, pixels, maskBuf)
    }

    private fun skipColourMap() {
        din.skipBytes(1) // padding
        din.skipBytes(2) // first colour
        val numColors = din.readUnsignedShort()
        din.skipBytes(numColors * 6) // 3 × u16 per colour
    }

    private fun handleServerCutText() {
        din.skipBytes(3) // padding
        val len = din.readInt()
        if (len > 0) {
            val bytes = ByteArray(len); din.readFully(bytes)
            listener?.onClipboardText(String(bytes, Charsets.ISO_8859_1))
        }
    }

    // ── Client → Server input events ─────────────────────────────────────

    /**
     * Send a pointer event. [buttonMask] is a bitmask of [RfbConstants.BTN_*].
     * [x] and [y] are framebuffer-space coordinates.
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_POINTER_EVENT)
            dout.writeByte(buttonMask)
            dout.writeShort(x.coerceIn(0, fbWidth - 1))
            dout.writeShort(y.coerceIn(0, fbHeight - 1))
            dout.flush()
        }
    }

    /**
     * Send a key event. [keysym] is an X11 keysym (see [RfbConstants.KEY_*]).
     */
    fun sendKeyEvent(keysym: Long, down: Boolean) {
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_KEY_EVENT)
            dout.writeByte(if (down) 1 else 0)
            dout.writeShort(0)                            // padding
            dout.writeInt(keysym.toInt())
            dout.flush()
        }
    }

    /** Send ClientCutText so clipboard paste works from device → VM. */
    fun sendClipboardText(text: String) {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_CLIENT_CUT_TEXT)
            dout.write(ByteArray(3))                      // padding
            dout.writeInt(bytes.size)
            dout.write(bytes)
            dout.flush()
        }
    }

    // ── Framebuffer dimensions (safe to read from any thread) ─────────────

    val width: Int get() = fbWidth
    val height: Int get() = fbHeight
}

/**
 * Callbacks fired on the RfbClient reader thread.
 * Implementations must switch to the main thread for UI operations.
 */
interface RfbListener {
    /**
     * Called once after ServerInit. [framebuffer] is the shared pixel array;
     * the listener should keep a reference — it is updated in place on each
     * [onFramebufferUpdate] call.
     */
    fun onConnected(width: Int, height: Int, name: String, framebuffer: IntArray)

    /**
     * Called after each FramebufferUpdate is decoded. The dirty region is
     * [x],[y] .. [x]+[w],[y]+[h] within [framebuffer].
     */
    fun onFramebufferUpdate(x: Int, y: Int, w: Int, h: Int, framebuffer: IntArray)

    /** Server requested a framebuffer resize. New [framebuffer] is provided. */
    fun onDesktopResize(width: Int, height: Int, framebuffer: IntArray)

    /** Server sent an audio bell event. */
    fun onBell() {}

    /** Server clipboard text arrived. */
    fun onClipboardText(text: String) {}

    /** Software cursor image updated. */
    fun onCursorUpdate(hotX: Int, hotY: Int, w: Int, h: Int,
                       pixels: IntArray, mask: ByteArray) {}

    /** Fatal protocol error. */
    fun onError(message: String)

    /** Connection closed cleanly. */
    fun onDisconnected(reason: String) {}
}

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
         * Encodings advertised to the server, in preference order.
         *
         * Both GUI and console-mode connections use this list.  The only
         * behavioural difference between the two modes is the ClientInit
         * shared-flag (0 = exclusive for [consoleMode], 1 = shared otherwise).
         *
         * ENC_EXTENDED_DESKTOP_SIZE is always advertised so the server knows
         * we understand client-initiated resize.  We do NOT send
         * SetDesktopSize immediately on connect; instead we wait for the
         * server to send an unsolicited ExtendedDesktopSize rect (reason=0)
         * which confirms it supports the extension.  See [sendSetDesktopSize].
         */
        private val PREFERRED_ENCODINGS = intArrayOf(
            // ── Pixel encodings (preference order) ───────────────────────────
            RfbConstants.ENC_TIGHT,                    // TigerVNC / libvirt default
            RfbConstants.ENC_ZRLE,                     // Proxmox preferred
            RfbConstants.ENC_ZLIB,
            RfbConstants.ENC_HEXTILE,
            RfbConstants.ENC_CORRE,                    // compact RRE fallback
            RfbConstants.ENC_COPY_RECT,
            RfbConstants.ENC_RRE,
            RfbConstants.ENC_RAW,
            // ── Desktop resize ────────────────────────────────────────────────
            RfbConstants.ENC_EXTENDED_DESKTOP_SIZE,    // standard -308 (IANA)
            RfbConstants.ENC_QEMU_EXTENDED_DESKTOP_SIZE, // QEMU alias -52
            RfbConstants.ENC_DESKTOP_SIZE,             // legacy server-side resize
            // ── Cursor ───────────────────────────────────────────────────────
            RfbConstants.ENC_CURSOR_WITH_ALPHA,        // RGBA cursor
            RfbConstants.ENC_CURSOR,                   // 1-bit-mask cursor
            RfbConstants.ENC_XCURSOR,                  // X11 1-bit cursor (legacy)
            RfbConstants.ENC_POINTER_POS,              // pointer position updates
            // ── Control flow / sync ──────────────────────────────────────────
            RfbConstants.ENC_FENCE,                    // consume fence messages
            RfbConstants.ENC_LAST_RECT,                // end-of-update marker
            RfbConstants.ENC_CONTINUOUS_UPDATES,       // advertise CU support
            // ── Metadata ─────────────────────────────────────────────────────
            RfbConstants.ENC_DESKTOP_NAME,             // desktop name changes
            RfbConstants.ENC_LED_STATE,                // QEMU LED state (pseudo-rect)
        )
    }

    var listener: RfbListener? = null

    /**
     * When false, [sendSetDesktopSize] is a no-op. Set to false for servers
     * (e.g. Proxmox vncproxy) that close the WebSocket after rejecting a
     * SetDesktopSize request with ExtendedDesktopSize status=3. Automatically
     * flipped to false when the server returns a non-zero status code for a
     * client-initiated resize so we never hammer a server that can't resize.
     */
    var canRequestResize: Boolean = true

    /**
     * Set to true the first time the server sends an unsolicited
     * ExtendedDesktopSize rectangle (reason=0, status=0).  Until this flag
     * is set, [sendSetDesktopSize] is a no-op — sending SetDesktopSize before
     * the server signals support causes QEMU to reject it and close the
     * connection (observed: reason=1 status=3 → EOF).
     */
    private var serverSupportsExtendedDesktopSize = false

    /**
     * Set to true when we just received an ExtendedDesktopSize rejection.
     * When the server closes the connection in that window (QEMU behaviour),
     * we treat it as a clean disconnect rather than a protocol error so the
     * UI does not show a red error banner.
     */
    @Volatile private var pendingResizeRejection = false

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
        // decoder is only initialized after serverInit() completes; guard against
        // stop() being called during or before the handshake (e.g. user backs out
        // before the connection completes) to prevent UninitializedPropertyAccessException.
        if (::decoder.isInitialized) decoder.reset()
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
                if (pendingResizeRejection &&
                    (e is java.io.EOFException || e is java.io.IOException)) {
                    // Server closed the connection immediately after rejecting our
                    // SetDesktopSize request (QEMU behaviour).  This is not a
                    // protocol error from the user's perspective — the session
                    // simply ended because the server doesn't support resize.
                    Logger.i(TAG, "Server closed after resize rejection — treating as clean disconnect")
                    listener?.onDisconnected("Server closed after resize rejection")
                } else {
                    Logger.e(TAG, "RFB protocol error", e)
                    listener?.onError("VNC connection lost: ${e.message ?: e.javaClass.simpleName}")
                }
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
        val encodings = PREFERRED_ENCODINGS
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_SET_ENCODINGS)
            dout.writeByte(0)                              // padding
            dout.writeShort(encodings.size)
            for (enc in encodings) dout.writeInt(enc)
            dout.flush()
        }
    }

    /**
     * Request the server to resize its framebuffer (RFB extension, message
     * type 251 / [RfbConstants.C2S_SET_DESKTOP_SIZE]).
     *
     * Only sent after the server has confirmed support by sending an
     * unsolicited ExtendedDesktopSize rectangle (reason=0) — i.e. after
     * [serverSupportsExtendedDesktopSize] is true.  Sending unconditionally
     * causes servers that do not support it (or QEMU before it emits the
     * initial rect) to reject the request and close the connection.
     *
     * The server acknowledges via an ENC_EXTENDED_DESKTOP_SIZE pseudo-rect.
     */
    fun sendSetDesktopSize(width: Int, height: Int) {
        if (!serverSupportsExtendedDesktopSize) {
            Logger.d(TAG, "SetDesktopSize deferred — waiting for server capability signal")
            return
        }
        if (!canRequestResize) {
            Logger.d(TAG, "SetDesktopSize suppressed — canRequestResize=false")
            return
        }
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
                // ── RFC 6143 core messages ──────────────────────────────────
                RfbConstants.S2C_FRAMEBUFFER_UPDATE      -> handleFramebufferUpdate()
                RfbConstants.S2C_SET_COLOUR_MAP_ENTRIES  -> skipColourMap()
                RfbConstants.S2C_BELL                    -> listener?.onBell()
                RfbConstants.S2C_SERVER_CUT_TEXT         -> handleServerCutText()
                // ── UltraVNC / PalmVNC resize (type 4 / 15) ────────────────
                RfbConstants.S2C_ULTRAVNC_RESIZE         -> handleUltraVncResize()
                15                                       -> skipPalmVncResize()
                // ── TigerVNC extensions ─────────────────────────────────────
                RfbConstants.S2C_END_OF_CONTINUOUS_UPDATES -> {
                    // Zero payload; just acknowledges end of continuous-update stream.
                    Logger.d(TAG, "EndOfContinuousUpdates")
                }
                RfbConstants.S2C_FENCE                   -> handleServerFence()
                // ── xvp power-control extension (type 250) ──────────────────
                RfbConstants.S2C_XVP                     -> handleXvp()
                // ── gii General Input Interface (type 253) ──────────────────
                RfbConstants.S2C_GII                     -> handleGii()
                // ── QEMU extended messages (type 255) ───────────────────────
                RfbConstants.S2C_QEMU_EXT                -> handleQemuExt()
                // ── QEMU/vendor notification (type 0xE0 = 224) ──────────────
                // Observed on QEMU-based VPS hosts (e.g. SSDnodes).  Appears
                // once per session immediately after the first FramebufferUpdate
                // that contains the EDS rejection + vendor pseudo-encoding flood.
                // The exact QEMU sub-system that generates this type is
                // unidentified; empirically it carries zero payload — treating it
                // as such keeps the stream in sync and allows the session to live.
                // If this assumption is wrong, the next read will desync and log
                // a different unknown-type error, at which point the payload
                // structure can be determined from that log.
                0xE0                                     -> Logger.d(TAG, "Vendor msg 0xE0 (224) — zero payload, continuing")
                // ── Unknown ─────────────────────────────────────────────────
                else                                     -> handleUnknownServerMessage(msgType)
            }
        }
    }

    /**
     * RFB server messages carry no length prefix — there is no safe way to skip
     * an unknown message's payload without knowing its exact size.  Any message
     * type that reaches this handler is one we have no formula for; proceeding
     * would read from an unknown stream position, producing garbage or hanging.
     *
     * If this fires on a real session, the message type should be looked up in
     * the IANA RFB registry and a proper handler added to [eventLoop].
     *
     * Note: 0xB9 (185) and 0xCC (204) observed on QEMU/Proxmox are NOT genuine
     * top-level messages — they are bytes from unread FramebufferUpdate payloads
     * caused by stream desync.  The root cause was QEMU sending ExtendedDesktopSize
     * with encoding 0xFFFFFFCC (-52) instead of the standard -308; that is now
     * handled explicitly in [handleFramebufferUpdate].
     *
     * 0x80 (128) seen from libvirt QEMU sessions is likewise NOT genuine — it is
     * a stray byte from a Tight rectangle whose payload was not consumed because
     * the old-style palette filterId (≥ 0x80) was unrecognised.  That is now
     * handled in [RfbDecoder.decodeTight]; fixing the Tight decoder prevents 0x80
     * from ever reaching this handler.
     *
     * 0xE0 (224) IS a genuine top-level message from some QEMU-based VPS hosts
     * (observed on SSDnodes).  It is handled as zero-payload in [eventLoop] to
     * keep the session alive rather than closing it here.
     */
    private fun handleUnknownServerMessage(msgType: Int) {
        val hex = msgType.toString(16).uppercase().padStart(2, '0')
        Logger.e(TAG, "Unrecognised server message type $msgType (0x$hex) — closing session to avoid stream desync")
        running.set(false)
        throw java.io.IOException("Unknown RFB server message type 0x$hex")
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
                RfbConstants.ENC_EXTENDED_DESKTOP_SIZE,
                RfbConstants.ENC_QEMU_EXTENDED_DESKTOP_SIZE -> {
                    // Both -308 (standard IANA) and -52 (QEMU internal alias 0xFFFFFFCC) use
                    // identical wire format.  QEMU sends -52 regardless of which value the
                    // client advertised; both must be handled to prevent stream desync.
                    //
                    // Payload: U8 numScreens + 3 bytes padding + 16 bytes per screen.
                    //   Screen: U32 id + U16 x + U16 y + U16 w + U16 h + U32 flags = 16 bytes
                    // rx = reason: 0=server-initiated, 1=this client, 2=other client
                    // ry = status: 0=OK, 1=prohibited, 2=out of resources, 3=invalid layout
                    // rw/rh = new framebuffer dimensions (only valid when ry==0)
                    // numScreens is U8 (1 byte) per rfbproto spec, NOT U16.
                    // The old U16 read accidentally consumed 2 bytes (num+1 padding byte),
                    // misreading the screen count and leaving the stream mis-aligned.
                    val numScreens = din.readUnsignedByte()
                    din.skipBytes(3)               // 3 bytes padding (total 4 incl. numScreens)
                    din.skipBytes(numScreens * 16) // screen descriptors: U32 id + U16 x + U16 y + U16 w + U16 h + U32 flags
                    if (ry == 0) {
                        pendingResizeRejection = false
                        if (rw > 0 && rh > 0) {
                            Logger.i(TAG, "ExtendedDesktopSize: ${rw}×$rh (reason=$rx status=OK)")
                            val sizeChanged = rw != fbWidth || rh != fbHeight
                            fbWidth = rw; fbHeight = rh
                            if (sizeChanged) {
                                // Dimensions changed: allocate a fresh framebuffer, notify the
                                // view, and request a full (non-incremental) repaint.
                                // When the size is unchanged (QEMU/Proxmox sends EDS as a
                                // capability signal in every FramebufferUpdate response), we
                                // skip the non-incremental FBUR — the incremental request at
                                // the bottom of handleFramebufferUpdate() drives the next frame,
                                // avoiding the feedback loop that produced ~50 EDS per second.
                                framebuffer = IntArray(fbWidth * fbHeight)
                                listener?.onDesktopResize(fbWidth, fbHeight, framebuffer)
                                sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
                            }
                        }
                        if (rx == 0 && !serverSupportsExtendedDesktopSize) {
                            // Server-initiated first announcement → server supports EDS.
                            // Fire the callback so VncConsoleChannel can send the desired size.
                            serverSupportsExtendedDesktopSize = true
                            Logger.i(TAG, "Server confirmed ExtendedDesktopSize support")
                            listener?.onExtendedDesktopSizeReady()
                        } else if (rx == 0) {
                            serverSupportsExtendedDesktopSize = true
                        }
                    } else {
                        Logger.w(TAG, "ExtendedDesktopSize rejected: reason=$rx status=$ry — disabling resize")
                        canRequestResize = false
                        pendingResizeRejection = true
                        // Some servers (QEMU ≤ 9.x) close the connection after sending the
                        // rejection.  pendingResizeRejection lets runProtocol() treat the
                        // resulting EOF as a clean disconnect rather than a protocol error.
                    }
                }
                RfbConstants.ENC_CURSOR -> {
                    // Software cursor (RichCursor): rw×rh pixels + 1-bit mask
                    handleCursor(rx, ry, rw, rh)
                }
                RfbConstants.ENC_CURSOR_WITH_ALPHA -> {
                    // RGBA cursor with pre-multiplied alpha (4 bytes per pixel).
                    handleCursorWithAlpha(rx, ry, rw, rh)
                }
                RfbConstants.ENC_XCURSOR -> {
                    // Legacy X11 XCursor: 6-byte color header + two 1-bit masks.
                    // Obsolete format; consume the data but do not render.
                    if (rw > 0 && rh > 0) {
                        din.skipBytes(6) // primary + secondary colors (2×RGB)
                        val maskBytes = ((rw + 7) / 8) * rh
                        din.skipBytes(maskBytes) // AND mask
                        din.skipBytes(maskBytes) // XOR mask
                    }
                    Logger.d(TAG, "XCursor ${rw}×$rh (not rendered)")
                }
                RfbConstants.ENC_POINTER_POS -> {
                    // Pointer position update: no payload, rx/ry = pointer coords.
                    Logger.d(TAG, "PointerPos: ($rx,$ry)")
                }
                RfbConstants.ENC_FENCE -> {
                    // Inline fence inside FramebufferUpdate: U32 flags + U8 length + data.
                    // Echo back so the server unblocks its update queue.
                    handleInlineFence()
                }
                RfbConstants.ENC_DESKTOP_NAME -> {
                    // DesktopName: U32 name-length + UTF-8 name bytes.
                    val nameLen = din.readInt()
                    if (nameLen > 0) {
                        val nameBytes = ByteArray(nameLen)
                        din.readFully(nameBytes)
                        Logger.d(TAG, "DesktopName: ${String(nameBytes, Charsets.UTF_8)}")
                    }
                }
                RfbConstants.ENC_LED_STATE -> {
                    // QEMU LED state: 1 byte bitmask (bit2=Caps, bit1=Num, bit0=Scroll).
                    // Delivered as a 1×1 pseudo-rect in FramebufferUpdate.
                    val ledState = din.readUnsignedByte()
                    Logger.d(TAG, "LedState: 0x${ledState.toString(16).uppercase().padStart(2, '0')}")
                }
                in RfbConstants.ENC_COMPRESS_LEVEL_0..RfbConstants.ENC_COMPRESS_LEVEL_9 -> {
                    // Compression level hint (-256 to -247): zero payload.
                    Logger.d(TAG, "CompressionLevel hint: $encoding")
                }
                in RfbConstants.ENC_QUALITY_LEVEL_0..RfbConstants.ENC_QUALITY_LEVEL_9 -> {
                    // JPEG quality level hint (-32 to -23): zero payload.
                    Logger.d(TAG, "QualityLevel hint: $encoding")
                }
                RfbConstants.ENC_CONTINUOUS_UPDATES -> {
                    // ContinuousUpdates capability advertisement: zero payload.
                    Logger.d(TAG, "ContinuousUpdates capability signal")
                }
                else -> {
                    val hexEnc = (encoding.toLong() and 0xFFFFFFFFL).toString(16).uppercase()
                    // Only the encodings that RfbDecoder.decodeRect() explicitly
                    // handles carry real pixel data. Everything else — negative
                    // pseudo-encodings, QEMU vendor encodings such as 0x6000
                    // (24576), or any other unrecognised value — is treated as a
                    // capability-signal advertisement with zero payload.
                    // Calling the decoder on an unrecognised encoding with fake
                    // dimensions (e.g. 16384×8192) would compute a 512 MB skip
                    // that blocks the reader thread forever.
                    if (encoding in RfbDecoder.PIXEL_ENCODINGS && rw > 0 && rh > 0) {
                        Logger.d(TAG, "Decoding rect enc=0x$hexEnc at ($rx,$ry) ${rw}×$rh")
                        decoder.decodeRect(din, framebuffer, fbWidth, rx, ry, rw, rh, encoding)
                        listener?.onFramebufferUpdate(rx, ry, rw, rh, framebuffer)
                    } else if (encoding in RfbDecoder.PIXEL_ENCODINGS) {
                        // Known pixel encoding but zero-dimension rect — no payload.
                        Logger.d(TAG, "Zero-dim rect enc=0x$hexEnc at ($rx,$ry) ${rw}×$rh — skipping")
                    } else {
                        // Unknown vendor / pseudo-encoding — assume zero payload.
                        Logger.d(TAG, "Unknown/vendor encoding 0x$hexEnc at ($rx,$ry) ${rw}×$rh — zero payload, skipping")
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
        when {
            len > 0 -> {
                // Standard clipboard text (ISO 8859-1)
                val bytes = ByteArray(len); din.readFully(bytes)
                listener?.onClipboardText(String(bytes, Charsets.ISO_8859_1))
            }
            len < 0 -> {
                // Extended clipboard (rfbproto §7.6.4 extension): abs(len) bytes of structured data.
                // Format: U32 flags + optional sub-fields per flag.
                // We consume the entire payload and log the flags; no clipboard action taken.
                val extLen = -len  // always positive; safe since Int.MIN_VALUE is not a valid length
                if (extLen >= 4) {
                    val flags = din.readInt()
                    Logger.d(TAG, "ExtendedClipboard flags=0x${flags.toString(16).uppercase()} extLen=$extLen")
                    if (extLen > 4) din.skipBytes(extLen - 4)
                } else {
                    din.skipBytes(extLen)
                }
            }
            // len == 0: empty cut-text; nothing to do
        }
    }

    /**
     * Consume a QEMU extended server message (type 255 / 0xFF).
     *
     * Wire format (type byte already consumed by event loop):
     *   U8  sub-type
     *   sub-type-specific payload
     *
     * Only sub-type 1 (Audio) is defined for server→client.
     * Sub-types 0 (extended key event) and 2 (pointer motion) are
     * client→server only and must never appear here.
     *
     * IMPORTANT: sub-type is 1 byte (U8), NOT 2 bytes.  Reading it as U16
     * consumed the first byte of the audio op field, desyncing the stream.
     */
    private fun handleQemuExt() {
        val subType = din.readUnsignedByte() // U8, not U16
        when (subType) {
            RfbConstants.QEMU_EXT_AUDIO -> {
                // Audio stream control.
                // Op values (U16):
                //   0 = stream end   (no extra payload)
                //   1 = stream begin (U8 format + U8 nchannels + U32 freq = 6 bytes)
                //   2 = audio data   (U32 length + length bytes of PCM)
                val op = din.readUnsignedShort()
                when (op) {
                    RfbConstants.QEMU_AUDIO_END -> {
                        /* stream end — no extra payload */
                    }
                    RfbConstants.QEMU_AUDIO_BEGIN -> {
                        din.skipBytes(1) // U8: sample format
                        din.skipBytes(1) // U8: number of channels
                        din.skipBytes(4) // U32: frequency (Hz)
                    }
                    RfbConstants.QEMU_AUDIO_DATA -> {
                        val len = din.readInt()
                        var remaining = len.toLong()
                        while (remaining > 0) remaining -= din.skip(remaining)
                    }
                    else -> Logger.w(TAG, "Unknown QEMU audio op $op")
                }
            }
            else -> {
                // Sub-types 0 and 2 are client-only; any other value is unknown.
                // Cannot skip safely without knowing the payload size — close.
                Logger.w(TAG, "Unexpected QEMU ext sub-type $subType — closing session")
                running.set(false)
                throw java.io.IOException("Unexpected QEMU ext sub-type $subType")
            }
        }
    }

    /**
     * Handle a top-level ServerFence message (type 248).
     *
     * Format (after message type byte):
     *   3 bytes padding · u32 flags · u8 length · length bytes data
     *
     * QEMU/Proxmox sends a ServerFence with the SyncNext flag set before the
     * first FramebufferUpdate.  Until the client echoes it back with a
     * ClientFence, the server holds the update queue — this is why the screen
     * stays permanently black: the first real FramebufferUpdate is queued
     * behind the fence and never arrives.
     *
     * We respond by echoing the SAME flags.  Per the Fence extension spec,
     * the client must echo the server's flags verbatim (only the Request bit,
     * bit 31, should be cleared — but server-sent ServerFence messages never
     * set it, so the reply is always flags as-is).  If we clear SyncNext
     * (bit 2) before replying, QEMU/Proxmox does not recognise the response
     * as a valid acknowledgement and keeps the update queue blocked forever.
     */
    private fun handleServerFence() {
        din.skipBytes(3) // padding
        val flags = din.readInt()
        val len = din.readUnsignedByte()
        val data = if (len > 0) ByteArray(len).also { din.readFully(it) } else ByteArray(0)
        Logger.d(TAG, "ServerFence flags=0x${flags.toString(16).uppercase()} len=$len")

        // Echo verbatim — clear only the Request bit (0x80000000) which the
        // server never sets in a ServerFence but which we must not reflect
        // as a request back to the server.  All other bits (BlockBefore,
        // BlockAfter, SyncNext) must be preserved so the server knows which
        // fence this reply belongs to and unblocks its update queue.
        val replyFlags = flags and 0x7FFFFFFF
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_CLIENT_FENCE)
            dout.writeByte(0); dout.writeByte(0); dout.writeByte(0) // padding
            dout.writeInt(replyFlags)
            dout.writeByte(len)
            if (len > 0) dout.write(data)
            dout.flush()
        }
        Logger.d(TAG, "ClientFence replied flags=0x${replyFlags.toString(16).uppercase()}")
    }

    /**
     * Handle an inline Fence pseudo-rect inside a FramebufferUpdate.
     *
     * Format (after the 10-byte rect header):
     *   u32 flags · u8 length · length bytes data
     *
     * Same echo-verbatim rule as [handleServerFence]: preserve all flag bits
     * except the Request bit (31) so the server can match this reply to its
     * outstanding fence and unblock further updates.
     */
    private fun handleInlineFence() {
        val flags = din.readInt()
        val len = din.readUnsignedByte()
        val data = if (len > 0) ByteArray(len).also { din.readFully(it) } else ByteArray(0)
        Logger.d(TAG, "Inline Fence pseudo-rect flags=0x${flags.toString(16).uppercase()} len=$len")

        val replyFlags = flags and 0x7FFFFFFF // clear Request bit only
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_CLIENT_FENCE)
            dout.writeByte(0); dout.writeByte(0); dout.writeByte(0)
            dout.writeInt(replyFlags)
            dout.writeByte(len)
            if (len > 0) dout.write(data)
            dout.flush()
        }
    }

    /**
     * Handle a CursorWithAlpha pseudo-rect (-314).
     *
     * Payload: [w]×[h]×4 bytes, pre-multiplied RGBA (R, G, B, A byte order).
     * Hotspot is carried in the rectangle's x/y fields ([hotX], [hotY]).
     * A synthetic 1-bit mask is derived from the alpha channel so the same
     * [RfbListener.onCursorUpdate] contract as [handleCursor] is maintained.
     */
    private fun handleCursorWithAlpha(hotX: Int, hotY: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val count = w * h
        val buf = ByteArray(count * 4)
        din.readFully(buf)
        val pixels = IntArray(count)
        for (i in 0 until count) {
            val base = i * 4
            val r = buf[base    ].toInt() and 0xFF
            val g = buf[base + 1].toInt() and 0xFF
            val b = buf[base + 2].toInt() and 0xFF
            val a = buf[base + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        // Synthesise a 1-bit mask from alpha (non-zero alpha = visible pixel).
        val stride = (w + 7) / 8
        val mask = ByteArray(stride * h)
        for (row in 0 until h) {
            for (col in 0 until w) {
                if ((pixels[row * w + col] ushr 24) > 0) {
                    mask[row * stride + col / 8] =
                        (mask[row * stride + col / 8].toInt() or (0x80 ushr (col % 8))).toByte()
                }
            }
        }
        cursorW = w; cursorH = h
        cursorHotX = hotX; cursorHotY = hotY
        cursorPixels = pixels
        cursorMask = mask
        listener?.onCursorUpdate(hotX, hotY, w, h, pixels, mask)
    }

    /**
     * Handle an UltraVNC ResizeFrameBuffer message (type 4).
     *
     * Payload (after the type byte already consumed by event loop):
     *   U8 padding · U16 width · U16 height
     */
    private fun handleUltraVncResize() {
        din.skipBytes(1) // padding
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        if (w > 0 && h > 0) {
            Logger.i(TAG, "UltraVNC ResizeFrameBuffer: ${w}×$h")
            fbWidth = w; fbHeight = h
            framebuffer = IntArray(fbWidth * fbHeight)
            listener?.onDesktopResize(fbWidth, fbHeight, framebuffer)
            sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
        }
    }

    /**
     * Consume a PalmVNC 2.0 resize message (type 15 / 0x0F).
     *
     * Payload: U16 width · U16 height (4 bytes).
     * PalmVNC is obsolete; we consume the data silently.
     */
    private fun skipPalmVncResize() {
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        Logger.d(TAG, "PalmVNC type-15 resize: ${w}×$h (ignored)")
    }

    /**
     * Consume an xvp power-control server message (type 250 / 0xFA).
     *
     * Payload (after the type byte):
     *   U8 padding · U8 version · U8 code
     *
     * xvp codes: 1=fail, 2=init, 3=shutdown, 4=reboot, 5=reset.
     * We log and discard — power-control responses do not affect the display.
     */
    private fun handleXvp() {
        din.skipBytes(1) // padding
        val version = din.readUnsignedByte()
        val code    = din.readUnsignedByte()
        Logger.d(TAG, "xvp message: version=$version code=$code (power control, not handled)")
    }

    /**
     * Consume a gii (General Input Interface) server message (type 253 / 0xFD).
     *
     * Payload (after the type byte):
     *   U8  subtype/endian — bit 7: 0 = big-endian length, 1 = little-endian length
     *   2 bytes  EU16 length of remaining payload (endian per bit 7 above)
     *   length bytes  sub-message payload
     *
     * gii is used to describe input device capabilities; we consume the data
     * so the stream stays aligned.
     */
    private fun handleGii() {
        val header    = din.readUnsignedByte()
        val bigEndian = (header and 0x80) == 0
        val b0 = din.readUnsignedByte()
        val b1 = din.readUnsignedByte()
        val payloadLen = if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
        if (payloadLen > 0) din.skipBytes(payloadLen)
        Logger.d(TAG, "gii message: subtype=0x${(header and 0x7F).toString(16)} len=$payloadLen (not handled)")
    }

    // ── Client → Server input events ─────────────────────────────────────

    /**
     * Send a pointer event. [buttonMask] is a bitmask of [RfbConstants.BTN_*].
     * [x] and [y] are framebuffer-space coordinates.
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        // Guard: fbWidth/fbHeight are 0 until serverInit() completes. coerceIn(0, -1)
        // throws IllegalArgumentException, which would crash the caller's thread.
        if (fbWidth <= 0 || fbHeight <= 0) return
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
        Logger.d(TAG, "KeyEvent keysym=0x${keysym.toString(16)} down=$down")
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

    /**
     * The server has confirmed it supports client-initiated resize by sending
     * an unsolicited ExtendedDesktopSize rectangle (reason=0).  Callers that
     * want to request a specific size (e.g. [VncConsoleChannel]) should call
     * [RfbClient.sendSetDesktopSize] here.  Default implementation is a no-op.
     */
    fun onExtendedDesktopSizeReady() {}
}

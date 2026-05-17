package io.github.tabssh.hypervisor.console.rfb

import io.github.tabssh.hypervisor.console.rfb.PixelFormat.Companion.toBytes
import io.github.tabssh.utils.logging.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
    private val outputStream: OutputStream
) {
    companion object {
        private const val TAG = "RfbClient"
        private const val RFB_VERSION = "RFB 003.008\n"

        /**
         * Encodings advertised to the server, in preference order.
         * Servers pick the first one they support.
         */
        private val PREFERRED_ENCODINGS = intArrayOf(
            RfbConstants.ENC_ZRLE,
            RfbConstants.ENC_HEXTILE,
            RfbConstants.ENC_COPY_RECT,
            RfbConstants.ENC_RRE,
            RfbConstants.ENC_RAW,
            RfbConstants.ENC_DESKTOP_SIZE,
            RfbConstants.ENC_CURSOR
        )
    }

    var listener: RfbListener? = null

    private val din = DataInputStream(inputStream.buffered(65536))
    private val dout = DataOutputStream(outputStream)
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

        // Prefer None (ticket-authenticated proxies), fall back to VNC Auth
        val chosen = when {
            types.contains(RfbConstants.SECURITY_NONE.toByte()) -> RfbConstants.SECURITY_NONE
            types.contains(RfbConstants.SECURITY_VNC_AUTH.toByte()) -> RfbConstants.SECURITY_VNC_AUTH
            else -> throw Exception("No supported security type in ${types.toList()}")
        }
        Logger.d(TAG, "Chose security type: $chosen")
        synchronized(outLock) { dout.writeByte(chosen); dout.flush() }

        when (chosen) {
            RfbConstants.SECURITY_NONE -> Unit // server sends SecurityResult immediately
            RfbConstants.SECURITY_VNC_AUTH -> {
                // Read 16-byte challenge; no password available in this context
                // (Proxmox vncproxy uses None; VMware/other can override via subclass).
                // If reached here without a password, bail with a clear message.
                val challenge = ByteArray(16)
                din.readFully(challenge)
                Logger.w(TAG, "VNC Auth challenge received but no password provider is set")
                throw Exception(
                    "VNC password authentication required but not supported in this context.\n" +
                    "Configure the VM to use a ticket-based or passwordless VNC session."
                )
            }
        }

        // SecurityResult (u32): 0 = OK
        val result = din.readInt()
        if (result != 0) {
            if (chosen == RfbConstants.SECURITY_NONE) return // RFB 3.8 None: no result
            val reasonLen = din.readInt()
            val reason = ByteArray(reasonLen); din.readFully(reason)
            throw Exception("Authentication failed: ${String(reason)}")
        }
        Logger.d(TAG, "Authentication OK")
    }

    // ── Server init ──────────────────────────────────────────────────────

    private fun serverInit() {
        // ClientInit: shared flag (1 = allow other viewers)
        synchronized(outLock) { dout.writeByte(1); dout.flush() }

        // ServerInit: width, height, pixel-format (16 bytes), name-length, name
        fbWidth = din.readUnsignedShort()
        fbHeight = din.readUnsignedShort()
        val serverFmt = PixelFormat.readFrom(din)
        Logger.i(TAG, "Server framebuffer: ${fbWidth}×$fbHeight, format=$serverFmt")

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
        synchronized(outLock) {
            dout.writeByte(RfbConstants.C2S_SET_ENCODINGS)
            dout.writeByte(0)                              // padding
            dout.writeShort(PREFERRED_ENCODINGS.size)
            for (enc in PREFERRED_ENCODINGS) dout.writeInt(enc)
            dout.flush()
        }
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
        Logger.d(TAG, "FramebufferUpdate: $numRects rects")

        for (i in 0 until numRects) {
            val rx = din.readUnsignedShort()
            val ry = din.readUnsignedShort()
            val rw = din.readUnsignedShort()
            val rh = din.readUnsignedShort()
            val encoding = din.readInt()

            when (encoding) {
                RfbConstants.ENC_DESKTOP_SIZE -> {
                    // Server-side resize: rx/ry hold the new dimensions
                    Logger.i(TAG, "DesktopSize pseudo-rect: ${rx}×$ry (was ${fbWidth}×$fbHeight)")
                    fbWidth = rx; fbHeight = ry
                    framebuffer = IntArray(fbWidth * fbHeight)
                    listener?.onDesktopResize(fbWidth, fbHeight, framebuffer)
                    sendUpdateRequest(0, 0, fbWidth, fbHeight, incremental = false)
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
        }

        // Request next incremental update immediately
        if (running.get()) {
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

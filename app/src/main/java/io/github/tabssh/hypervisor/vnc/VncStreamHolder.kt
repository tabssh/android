package io.github.tabssh.hypervisor.vnc

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * Static slot that transfers a live VNC stream from a producer activity
 * (e.g. LibvirtManagerActivity) to VMConsoleActivity.
 *
 * Android Intents cannot carry Java I/O streams, so this singleton acts as a
 * hand-off point. The producer writes the streams before launching the
 * consumer activity; the consumer reads and clears them in onCreate.
 *
 * Thread safety: all access is synchronized on the companion object.
 */
object VncStreamHolder {
    @Volatile private var _inputStream: InputStream? = null
    @Volatile private var _outputStream: OutputStream? = null
    /** Non-null when the streams come from a plain TCP socket that must be
     *  closed alongside the streams when the console session ends. */
    @Volatile private var _socket: Socket? = null

    /**
     * Store streams before starting VMConsoleActivity. If a previous producer
     * left streams here that were never consumed (e.g. user backed out of the
     * launch before the consumer's `take()` ran), close the stale set first
     * — otherwise the prior Socket fd and stream descriptors leak until
     * process death.
     */
    @Synchronized
    fun set(inputStream: InputStream, outputStream: OutputStream, socket: Socket? = null) {
        try { _inputStream?.close() } catch (_: Exception) {}
        try { _outputStream?.close() } catch (_: Exception) {}
        try { _socket?.close() } catch (_: Exception) {}
        _inputStream = inputStream
        _outputStream = outputStream
        _socket = socket
    }

    /**
     * Retrieve and clear the stored streams.
     * Returns null if no streams are available (caller should handle gracefully).
     */
    @Synchronized
    fun take(): Triple<InputStream, OutputStream, Socket?>? {
        val ins = _inputStream ?: return null
        val out = _outputStream ?: return null
        val sock = _socket
        _inputStream = null
        _outputStream = null
        _socket = null
        return Triple(ins, out, sock)
    }

    /** True if streams are waiting to be consumed. */
    @Synchronized
    fun hasStreams(): Boolean = _inputStream != null
}

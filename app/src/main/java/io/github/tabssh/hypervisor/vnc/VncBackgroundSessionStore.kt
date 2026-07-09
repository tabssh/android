package io.github.tabssh.hypervisor.vnc

import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.utils.logging.Logger
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry of VNC sessions parked while the app is backgrounded.
 *
 * Direct-VNC ([RfbClient]) sessions are normally owned by VMConsoleActivity and
 * torn down in its onStop/onDestroy. When a VncHost opts into
 * `keepAliveInBackground`, the activity instead hands its live-but-paused
 * [RfbClient] to this singleton on background and reclaims it on return. Because
 * this object lives in the application process (not the activity), the socket
 * and its daemon reader thread survive activity destruction — as long as the
 * process itself is kept alive, which is the job of `VncKeepAliveService`
 * (a foreground service that holds wake/wifi locks while [isEmpty] is false).
 *
 * The RfbClient must be [RfbClient.pause]d before parking so no framebuffer
 * updates are requested while unattended, and its listener detached so a late
 * update cannot touch the destroyed activity's views.
 *
 * Thread safety: backed by a [ConcurrentHashMap]; keys are VncHost ids.
 */
object VncBackgroundSessionStore {

    private const val TAG = "VncBgSessionStore"

    /**
     * A VNC session held open in the background.
     *
     * @property key VncHost id — the same id VMConsoleActivity receives via
     *   `EXTRA_VNC_HOST_ID`, used to match a returning activity to its session.
     * @property vmName Display name, for the keep-alive notification.
     * @property rfbClient The live, paused RFB client (socket + reader thread).
     * @property socket The underlying TCP socket, closed on final teardown.
     * @property disableResize Whether the client had client-initiated resize
     *   suppressed, so a re-attach restores the same state.
     */
    data class ParkedSession(
        val key: String,
        val vmName: String,
        val rfbClient: RfbClient,
        val socket: Socket?,
        val disableResize: Boolean
    )

    private val sessions = ConcurrentHashMap<String, ParkedSession>()

    /** Park a paused session, replacing (and discarding) any prior one for the same key. */
    fun park(session: ParkedSession) {
        sessions.put(session.key, session)?.let { previous ->
            if (previous.rfbClient !== session.rfbClient) {
                Logger.w(TAG, "Replacing an already-parked session for ${session.key}; discarding old")
                discardInternal(previous)
            }
        }
        Logger.d(TAG, "Parked VNC session ${session.key} (${sessions.size} total)")
    }

    /** Remove and return the session for [key], transferring ownership back to the caller. */
    fun take(key: String): ParkedSession? =
        sessions.remove(key)?.also {
            Logger.d(TAG, "Reclaimed VNC session $key (${sessions.size} remain)")
        }

    /** True if a session is parked for [key]. */
    fun contains(key: String): Boolean = sessions.containsKey(key)

    /** True when no sessions are parked — the keep-alive service may stop. */
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Number of parked sessions. */
    val count: Int get() = sessions.size

    /** Fully tear down and forget the session for [key] (e.g. user closed it). */
    fun discard(key: String) {
        sessions.remove(key)?.let { discardInternal(it) }
    }

    /** Fully tear down and forget every parked session. */
    fun discardAll() {
        val snapshot = sessions.values.toList()
        sessions.clear()
        snapshot.forEach { discardInternal(it) }
    }

    private fun discardInternal(session: ParkedSession) {
        try { session.rfbClient.stop() } catch (_: Exception) {}
        try { session.socket?.close() } catch (_: Exception) {}
        Logger.d(TAG, "Discarded VNC session ${session.key}")
    }
}

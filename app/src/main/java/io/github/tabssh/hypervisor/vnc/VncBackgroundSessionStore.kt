package io.github.tabssh.hypervisor.vnc

import io.github.tabssh.hypervisor.console.rfb.RfbClient
import io.github.tabssh.utils.logging.Logger
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped registry of VNC sessions parked while the app is backgrounded.
 *
 * Direct-VNC ([RfbClient]) sessions are Application-scoped, owned by `TabManager`
 * for the lifetime of their tab. When a tab's `VncHost` opts into
 * `keepAliveInBackground` (or the tab is an ephemeral VNC/console tab, which
 * always opts in), `TabManager.parkBackgroundSessions()` hands the live-but-paused
 * [RfbClient] to this singleton in `TabTerminalActivity.onStop()` and reclaims it
 * in `onResume()`. Because this object lives in the application process (not the
 * activity), the socket and its daemon reader thread survive activity
 * destruction — as long as the process itself is kept alive, which is the job of
 * `VncKeepAliveService` (a foreground service that holds wake/wifi locks while
 * [isEmpty] is false).
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
     * @property key The tab's store key (VncHost id for persisted `VncTab`s, or
     *   the tab's own id for ephemeral `VncTab`s/`ConsoleTab`s), used by
     *   `TabManager.reclaimBackgroundSessions()` to match a returning tab to
     *   its parked session.
     * @property vmName Display name, for the keep-alive notification.
     * @property rfbClient The live, paused RFB client (socket + reader thread).
     * @property socket The underlying TCP socket, closed on final teardown.
     * @property disableResize Whether the client had client-initiated resize
     *   suppressed, so a re-attach restores the same state.
     * @property parkedAtMillis Wall-clock time the session was parked, used by
     *   [sweepIdle] to fully suspend sessions that have sat untouched too long.
     */
    data class ParkedSession(
        val key: String,
        val vmName: String,
        val rfbClient: RfbClient,
        val socket: Socket?,
        val disableResize: Boolean,
        val parkedAtMillis: Long
    )

    /**
     * Default idle window before a parked session is fully suspended (socket
     * closed, wake/wifi locks released) rather than held open forever. Balances
     * "swipe/app-switch doesn't reconnect" against not burning battery and data
     * on a session nobody has looked at in a while.
     */
    const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

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

    /**
     * Tear down and forget every session parked for at least [maxAgeMillis],
     * measured against [nowMillis]. Called periodically by
     * [io.github.tabssh.services.VncKeepAliveService] — a paused session parked
     * longer than the idle window isn't saving the user anything (nothing is
     * displayed while backgrounded) but keeps a wake lock, a WiFi lock, and a
     * TCP socket alive, so it's fully closed and left to reconnect fresh on the
     * next `onResume()`.
     *
     * @return the keys that were swept, for logging.
     */
    fun sweepIdle(nowMillis: Long, maxAgeMillis: Long = DEFAULT_IDLE_TIMEOUT_MS): List<String> {
        val expired = sessions.entries
            .filter { nowMillis - it.value.parkedAtMillis >= maxAgeMillis }
            .map { it.key }
        expired.forEach { key -> sessions.remove(key)?.let { discardInternal(it) } }
        return expired
    }

    private fun discardInternal(session: ParkedSession) {
        try { session.rfbClient.stop() } catch (_: Exception) {}
        try { session.socket?.close() } catch (_: Exception) {}
        Logger.d(TAG, "Discarded VNC session ${session.key}")
    }
}

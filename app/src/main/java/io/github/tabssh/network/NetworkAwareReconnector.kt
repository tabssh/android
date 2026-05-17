package io.github.tabssh.network

import io.github.tabssh.network.detection.NetworkDetector
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Protocol-agnostic reconnection coordinator with network awareness.
 *
 * ## Behaviour
 * - **Network available, connection drops** — schedules a reconnect with
 *   exponential backoff (5 s → 10 s → 20 s → … → 5 min cap).
 * - **Network unavailable, connection drops** — pauses immediately; no
 *   reconnect timer fires. Retries do not accumulate while offline.
 * - **Network comes back** — wakes immediately (delay = 0) and resets the
 *   backoff counter. Network recovery is the fastest possible trigger.
 * - **5-minute poll fallback** — in case an `onAvailable` callback is
 *   missed (e.g. device woke from deep sleep after the callback window),
 *   a background loop checks every 5 minutes whether the network is now
 *   available and the reconnector is still waiting.
 * - **User-initiated connect** — calling [onUserInitiated] cancels any
 *   pending retry timer and clears the pause flag so the user is never
 *   blocked by a backoff delay they didn't ask for.
 *
 * ## Usage
 * ```kotlin
 * // SSH
 * val reconnector = NetworkAwareReconnector(
 *     networkDetector = app.networkDetector,
 *     scope = connectionScope,
 *     tag = "SSHConnection/${profile.host}",
 *     reconnect = { connect() }
 * )
 * reconnector.start()
 *
 * // VNC
 * val reconnector = NetworkAwareReconnector(
 *     networkDetector = app.networkDetector,
 *     scope = consoleScope,
 *     tag = "VNC/${host}",
 *     reconnect = { connectConsole() }
 * )
 * reconnector.start()
 * ```
 *
 * Call [onConnectionLost] when the transport drops unexpectedly,
 * [onConnectionRestored] when it comes back up, [onUserInitiated] when
 * the user explicitly taps "connect", and [cancel] when the connection
 * is intentionally closed for good.
 */
class NetworkAwareReconnector(
    private val networkDetector: NetworkDetector,
    private val scope: CoroutineScope,
    private val tag: String,
    /** Called to attempt a reconnect. Should handle its own connection
     *  handshake; does NOT need to catch [kotlinx.coroutines.CancellationException]. */
    private val reconnect: suspend () -> Unit,
) {
    /** Backoff attempt counter — reset to 0 whenever the network returns
     *  or a connection is successfully restored. */
    private var attempts = 0

    /** True while the network is known to be unavailable. No timer runs
     *  in this state; we wait for [networkDetector] to signal recovery. */
    @Volatile private var paused = false

    /** True once [cancel] has been called — stops all activity. */
    @Volatile private var cancelled = false

    private var reconnectJob: Job? = null
    private var networkObserverJob: Job? = null
    private var pollJob: Job? = null

    companion object {
        /** Maximum reconnect delay in ms (5 minutes). */
        private const val MAX_DELAY_MS = 300_000L
        /** Base delay in ms for the first retry (5 seconds). */
        private const val BASE_DELAY_MS = 5_000L
        /** Fallback poll interval for missed network callbacks (5 minutes). */
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L
    }

    /**
     * Start the network observer and poll loop.
     * Call once after creating the reconnector.
     */
    fun start() {
        startNetworkObserver()
        startPollLoop()
    }

    /**
     * Call when the connection drops unexpectedly (e.g. SSH disconnect,
     * VNC WebSocket close, Telnet EOF).
     *
     * - If the network is available: schedules a reconnect with exponential
     *   backoff.
     * - If the network is unavailable: enters a paused state; the network
     *   observer will wake the reconnector when the link returns.
     */
    fun onConnectionLost() {
        if (cancelled) return
        reconnectJob?.cancel()
        reconnectJob = null

        if (networkDetector.networkState.value.isConnected) {
            scheduleBackoffReconnect()
        } else {
            Logger.i(tag, "Network unavailable — pausing reconnect until link returns")
            paused = true
        }
    }

    /**
     * Call when the connection is successfully established (or re-established).
     * Resets the backoff counter so the next drop starts from 5 s again.
     */
    fun onConnectionRestored() {
        attempts = 0
        paused = false
        reconnectJob?.cancel()
        reconnectJob = null
        Logger.d(tag, "Connection restored — backoff counter reset")
    }

    /**
     * Call when the **user** explicitly initiates a connection (e.g. taps
     * "Connect" or selects a host). Cancels any pending backoff timer and
     * clears the paused flag so the user-driven connect is never delayed.
     * The actual [reconnect] call is the responsibility of the caller.
     */
    fun onUserInitiated() {
        if (cancelled) return
        reconnectJob?.cancel()
        reconnectJob = null
        paused = false
        Logger.d(tag, "User-initiated connect — backoff timer cleared")
    }

    /**
     * Stop all activity permanently. Call when the connection is intentionally
     * closed (e.g. user taps "Disconnect" or the Activity/Fragment is destroyed).
     */
    fun cancel() {
        cancelled = true
        reconnectJob?.cancel()
        networkObserverJob?.cancel()
        pollJob?.cancel()
        reconnectJob = null
        networkObserverJob = null
        pollJob = null
        Logger.d(tag, "NetworkAwareReconnector cancelled")
    }

    // ── private ─────────────────────────────────────────────────────────────

    /** Schedule a reconnect attempt after an exponentially increasing delay. */
    private fun scheduleBackoffReconnect() {
        if (cancelled) return
        attempts++
        // Delay: 5 s, 10 s, 20 s, 40 s, 80 s, 160 s, then cap at 5 min.
        // attempts-1 so the first retry (attempts=1) gets BASE_DELAY_MS * 1.
        val delayMs = minOf(BASE_DELAY_MS shl minOf(attempts - 1, 6), MAX_DELAY_MS)
        Logger.i(tag, "Scheduling reconnect attempt $attempts in ${delayMs / 1000}s")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!cancelled) {
                Logger.i(tag, "Firing reconnect attempt $attempts")
                reconnect()
            }
        }
    }

    /**
     * Observe [NetworkDetector.networkState]. When the link transitions from
     * down to up AND we are in the paused state, kick off a reconnect immediately
     * with the backoff counter reset.
     */
    private fun startNetworkObserver() {
        networkObserverJob?.cancel()
        networkObserverJob = scope.launch {
            var wasConnected = networkDetector.networkState.value.isConnected
            networkDetector.networkState.collect { state ->
                val isNowConnected = state.isConnected
                if (!wasConnected && isNowConnected && paused && !cancelled) {
                    Logger.i(tag, "Network restored — reconnecting immediately (backoff reset)")
                    paused = false
                    attempts = 0  // fresh start after a network outage
                    reconnectJob?.cancel()
                    reconnectJob = scope.launch {
                        if (!cancelled) reconnect()
                    }
                }
                wasConnected = isNowConnected
            }
        }
    }

    /**
     * 5-minute fallback poll. Fires a reconnect if we are still in the
     * paused state but the network is now available — covers the case where
     * the [onAvailable] callback was missed (deep-sleep wake, API < N, etc.).
     */
    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && !cancelled) {
                delay(POLL_INTERVAL_MS)
                if (paused && networkDetector.networkState.value.isConnected && !cancelled) {
                    Logger.i(tag, "Poll: network available but paused — reconnecting")
                    paused = false
                    attempts = 0
                    reconnect()
                }
            }
        }
    }
}

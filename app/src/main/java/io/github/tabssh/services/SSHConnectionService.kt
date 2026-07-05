package io.github.tabssh.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.ssh.connection.SessionManagerListener
import io.github.tabssh.ssh.connection.ConnectionState
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.*

/**
 * Background service to maintain SSH connections
 */
class SSHConnectionService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var app: TabSSHApplication

    private var activeConnections = 0
    private var sessionListener: SessionManagerListener? = null

    // PARTIAL_WAKE_LOCK strategy — screen-aware to save battery:
    //
    // Screen ON  → indefinite acquire(): CPU stays fully awake while the
    //              user is interacting with the terminal.
    //
    // Screen OFF → timed acquire(timeout): the lock auto-releases after
    //              BACKGROUND_WAKE_WINDOW_MS, letting the CPU sleep. A
    //              background coroutine (backgroundWakeCycleJob) re-acquires
    //              a fresh timed lock every BACKGROUND_WAKE_CYCLE_MS so that
    //              JSch's keep-alive timer — which runs on a Java Timer thread
    //              that fires as soon as the CPU wakes — can send its
    //              SSH_MSG_GLOBAL_REQUEST before the NAT table expires.
    //
    // Zero connections → released entirely; service self-stops shortly after.
    private var wakeLock: PowerManager.WakeLock? = null

    // True when the device display is interactive (screen on / locked but lit).
    // Initialised from PowerManager.isInteractive() in onCreate so the first
    // acquireWakeLock() call after a process restart uses the right mode.
    @Volatile private var isScreenOn = true

    // Coroutine that drives the screen-off keep-alive wake cycle.
    // Null when screen is on or there are no active connections.
    private var backgroundWakeCycleJob: Job? = null

    // Per-host notification bookkeeping. Android requires a foreground
    // service to keep at least one ongoing notification while alive
    // ("the FG anchor") — we pick one of the per-host notifications and
    // call startForeground(id, notif). When that host disconnects we
    // swap the anchor to another live host (if any), otherwise we stop
    // the service.
    @Volatile private var fgAnchorProfileId: String? = null

    // Tracks profile ids we've already auto-finished as "disconnected"
    // so a duplicate state-change event doesn't re-post.
    private val disconnectedProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // The single active monitoring coroutine. Stored so that a second
    // onStartCommand(ACTION_START_SERVICE) call (which can happen if the
    // app sends multiple startForegroundService() requests before the first
    // one is processed) replaces the old loop rather than stacking on top
    // of it.
    private var monitoringJob: Job? = null

    companion object {
        // Placeholder notification ID — used only for the transient "Starting SSH
        // session…" notification before the first per-host notification is live.
        // Matches NotificationHelper.NOTIFICATION_ID_SERVICE (1001) so the service
        // anchor stays consistent across the one place that references it by name.
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "SSHConnectionService"

        // Duration of each CPU-awake window in background mode. Must be long
        // enough for JSch's Timer thread to wake, detect the overdue interval,
        // and transmit SSH_MSG_GLOBAL_REQUEST before we release the lock again.
        // 15 s gives ~10x the time JSch actually needs (< 100 ms in practice).
        private const val BACKGROUND_WAKE_WINDOW_MS = 15_000L

        // Monitoring-loop delay while the screen is on vs off.
        // 30 s on-screen keeps notifications and health checks snappy.
        // 90 s off-screen reduces unnecessary CPU wake-ups; the keep-alive
        // cycle (backgroundWakeCycleJob) ensures the connection stays alive
        // independently of this loop.
        private const val MONITORING_INTERVAL_FOREGROUND_MS = 30_000L
        private const val MONITORING_INTERVAL_BACKGROUND_MS = 90_000L

        const val ACTION_START_SERVICE = "io.github.tabssh.START_SERVICE"
        const val ACTION_STOP_SERVICE  = "io.github.tabssh.STOP_SERVICE"
        
        fun startService(context: Context) {
            val intent = Intent(context, SSHConnectionService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, SSHConnectionService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
    
    // Receiver for ACTION_SCREEN_OFF / ACTION_SCREEN_ON. These two intents
    // are not deliverable via a manifest receiver — they MUST be registered
    // at runtime on a running Context, which makes the service the right home.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON  -> onScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        Logger.d("SSHConnectionService", "Service created")

        app = application as TabSSHApplication

        // Seed isScreenOn from the current display state so the first
        // acquireWakeLock() call after a process restart (which can happen
        // while the screen is already off) chooses the right mode.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        isScreenOn = pm.isInteractive

        // Register before any session callbacks arrive so screen-off events
        // during onCreate are not missed.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        // NotificationHelper.createNotificationChannels() is called once at
        // application start (TabSSHApplication.onCreate). No duplicate channel
        // creation needed here — the private "ssh_connections" channel has been
        // removed; the placeholder notification now uses CHANNEL_SERVICE.
        // Sweep any per-host notifications that are stale from a previous
        // service lifetime (e.g. process killed by OOM without onDestroy).
        sweepPerHostNotifications(cancelAll = false)
        setupSessionManagerListener()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d("SSHConnectionService", "onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundService()
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        // NOT_STICKY: if the system kills this service (OOM, force-stop, etc.)
        // there is nothing to restore — all SSH sessions are dead. The app
        // restarts the service itself via startService() on the next connect.
        // START_STICKY caused the service to revive with stale "Connected to"
        // notifications still in the shade and a spurious "Starting SSH
        // session…" placeholder on every process restart.
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()

        Logger.d("SSHConnectionService", "Service destroyed")

        // Cancel all per-host notifications before tearing down — prevents
        // stale "Connected to …" entries lingering in the notification shade
        // after a graceful service stop.
        sweepPerHostNotifications(cancelAll = true)

        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        backgroundWakeCycleJob?.cancel()
        backgroundWakeCycleJob = null

        serviceScope.cancel()
        releaseWakeLock()
        // Don't tear down the manager here — its lifecycle is the
        // Application's, not the service's. The service is allowed to
        // stop and restart (e.g. when there are zero active sessions),
        // and cancelling the manager's scope here breaks every future
        // connect() in the process.
        sessionListener?.let { app.sshSessionManager.removeListener(it) }
        sessionListener = null
    }
    
    private fun startForegroundService() {
        // Acquire the appropriate wake lock immediately — before any session
        // event fires. Without a lock the CPU can idle during the connecting /
        // authenticating phase (aggressive power management on many OEMs can
        // cause TCP handshake or JSch kex to time out on screen-off).
        if (isScreenOn) acquireWakeLockIndefinite() else ensureBackgroundWakeCycleRunning()

        // The foreground-service contract requires *some* notification
        // to be live before startForeground returns. If we already have
        // an active connection, anchor on it; otherwise post a transient
        // placeholder (cleared as soon as the first session connects).
        val activeProfile = try {
            app.sshSessionManager.getActiveConnections().firstOrNull()
        } catch (_: Exception) { null }

        if (activeProfile != null) {
            val notif = io.github.tabssh.utils.NotificationHelper.buildHostStatusNotification(
                this, activeProfile.profile, activeProfile.connectionState.value, activeProfile.terminalTitle
            )
            val id = io.github.tabssh.utils.NotificationHelper.perHostNotificationId(activeProfile.profile.id)
            startForeground(id, notif)
            fgAnchorProfileId = activeProfile.profile.id
        } else {
            // Placeholder anchor, swapped out on first onConnectionEstablished.
            startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        }

        Logger.i("SSHConnectionService", "Started foreground service")

        // Start connection monitoring. Cancel any pre-existing loop first —
        // multiple onStartCommand(ACTION_START_SERVICE) calls must not stack
        // concurrent monitoring coroutines (symptom: maintenance fires every
        // ~100 ms instead of every 30 s, wake-lock log floods logcat).
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            startConnectionMonitoring()
        }
    }

    /**
     * Transient placeholder used as the FG-service anchor when the
     * service starts before any session has connected. Swapped out the
     * moment a per-host notification is available.
     *
     * Uses [NotificationHelper.CHANNEL_SERVICE] — the same channel that
     * NotificationHelper manages — instead of a private duplicate channel.
     */
    private fun buildPlaceholderNotification(): Notification {
        val tapTarget = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, io.github.tabssh.utils.NotificationHelper.CHANNEL_SERVICE)
            .setContentTitle("TabSSH")
            .setContentText("Starting SSH session…")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // Safety net: if the service dies before a real session connects
            // (e.g. OOM kill after a cold start), this placeholder auto-clears
            // after 5 minutes so it doesn't linger forever.
            .setTimeoutAfter(5 * 60 * 1000L)
            .build()
    }

    /**
     * Render or update the per-host status notification for [profileId]
     * on the silent channel. Also keeps the foreground-service anchor
     * pointed at a live host (swaps if the current anchor disconnected,
     * adopts on the first connect).
     *
     * `disconnectingState` is true when this is a terminal "Disconnected"
     * render — the notification flips to the auto-cleared variant and
     * we *don't* leave it as the FG anchor (Android won't auto-clear an
     * ongoing FG notification while service is alive).
     */
    private fun renderHostNotification(profileId: String, disconnectingState: Boolean = false) {
        val conn = app.sshSessionManager.getConnection(profileId)
        if (conn == null) {
            // Session was already removed from the manager (race between the
            // listener callback and the session teardown).  If this is the
            // disconnect render, still cancel the notification so it doesn't
            // linger in the shade.
            if (disconnectingState) {
                val nid = io.github.tabssh.utils.NotificationHelper.perHostNotificationId(profileId)
                getSystemService(NotificationManager::class.java).cancel(nid)
                // If this was the FG anchor, detach so Android releases the pin.
                if (fgAnchorProfileId == profileId) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    }
                    fgAnchorProfileId = null
                }
            }
            return
        }
        val state = conn.connectionState.value
        val effectiveState = if (disconnectingState)
            ConnectionState.DISCONNECTED else state
        val cleanExit = (conn.getShellExitStatus() == 0)

        // Terminal title comes from Termux's OSC 0 parsing — falls back
        // to the schema-with-port if the shell hasn't set one yet.
        val title = conn.terminalTitle

        val notif = io.github.tabssh.utils.NotificationHelper.buildHostStatusNotification(
            this, conn.profile, effectiveState, title, cleanExit
        )
        val nid = io.github.tabssh.utils.NotificationHelper.perHostNotificationId(profileId)

        // FG-anchor management: if the live anchor is this profile, the
        // FG notification IS this notification — call startForeground to
        // refresh the OS-side reference. If we're disconnecting and this
        // is the anchor, swap to another live host first (or release FG
        // entirely if no other host is alive).
        if (!disconnectingState && state == ConnectionState.CONNECTED) {
            if (fgAnchorProfileId == null || fgAnchorProfileId == profileId) {
                startForeground(nid, notif)
                // If the FG anchor was previously the placeholder (null →
                // NOTIFICATION_ID 1001 on the legacy channel), cancel it
                // explicitly — Android won't remove the old anchor
                // notification when startForeground is called with a
                // different id.
                if (fgAnchorProfileId == null) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                fgAnchorProfileId = profileId
                return
            }
        }
        if (disconnectingState && fgAnchorProfileId == profileId) {
            val nextLive = app.sshSessionManager.getActiveConnections()
                .firstOrNull { it.profile.id != profileId &&
                               it.connectionState.value == ConnectionState.CONNECTED }
            if (nextLive != null) {
                val nextNotif = io.github.tabssh.utils.NotificationHelper.buildHostStatusNotification(
                    this, nextLive.profile, nextLive.connectionState.value, nextLive.terminalTitle
                )
                val nextId = io.github.tabssh.utils.NotificationHelper.perHostNotificationId(nextLive.profile.id)
                startForeground(nextId, nextNotif)
                fgAnchorProfileId = nextLive.profile.id
            } else {
                // No live host to anchor on — detach FG so the timeout-
                // after-30s on the disconnect notification can take effect.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                fgAnchorProfileId = null
            }
        }

        // Final post for the per-host notification (CONNECTED update,
        // CONNECTING refresh, ERROR, or the terminal DISCONNECTED).
        val nm = getSystemService(NotificationManager::class.java)

        // For DISCONNECTED: cancel the existing notification (which may
        // still carry the "managed by foreground service" flag from when
        // it was the startForeground anchor), then post a fresh one.
        // This prevents Android from ignoring setTimeoutAfter/setAutoCancel
        // on what it still considers an ongoing foreground notification.
        if (disconnectingState) {
            nm.cancel(nid)
        }
        nm.notify(nid, notif)
    }
    
    private fun setupSessionManagerListener() {
        val listener = object : SessionManagerListener {
            override fun onConnectionEstablished(profileId: String) {
                updateConnectionCount()
                disconnectedProfiles.remove(profileId)
                // Increment the per-profile connection count and last-connected timestamp.
                // updateLastConnected uses a single atomic SQL UPDATE (count+1) so there
                // is no read-modify-write race under concurrent sessions.
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        app.database.connectionDao().updateLastConnected(profileId)
                    } catch (e: Exception) {
                        Logger.w(TAG, "Failed to update connection stats for $profileId", e)
                    }
                }
                serviceScope.launch(Dispatchers.Main) {
                    renderHostNotification(profileId, disconnectingState = false)
                    val conn = app.sshSessionManager.getConnection(profileId)
                    if (conn != null) {
                        // Connect alert — only fires for ALWAYS mode
                        // since this isn't an error.
                        io.github.tabssh.utils.NotificationHelper.maybeAlertForHost(
                            this@SSHConnectionService,
                            conn.profile,
                            "Connected to ${conn.profile.host}",
                            isError = false
                        )
                    }
                }
            }

            override fun onConnectionClosed(profileId: String) {
                if (!disconnectedProfiles.add(profileId)) return
                serviceScope.launch(Dispatchers.Main) {
                    val conn = app.sshSessionManager.getConnection(profileId)
                    val isError = conn?.let {
                        val s = it.getShellExitStatus()
                        s != 0  // -1 (drop) or non-zero (abnormal exit)
                    } ?: true
                    // Render the auto-clearing "Disconnected" status,
                    // swapping FG anchor away if needed.
                    renderHostNotification(profileId, disconnectingState = true)
                    if (conn != null) {
                        io.github.tabssh.utils.NotificationHelper.maybeAlertForHost(
                            this@SSHConnectionService,
                            conn.profile,
                            if (isError) "Disconnected (error)" else "Disconnected",
                            isError = isError
                        )
                    }
                    updateConnectionCount()
                }
            }

            override fun onConnectionStateChanged(profileId: String, state: ConnectionState) {
                updateConnectionCount()
                serviceScope.launch(Dispatchers.Main) {
                    // Monitoring-only connections are invisible to the notification
                    // layer.  connectForMonitoring() sets isMonitoringOnly = true;
                    // connectToServer() clears it.  Checking here prevents the
                    // spurious "Connected to host:port-ssh" notification that would
                    // otherwise appear whenever a dashboard or HostDetailActivity
                    // session connects while SSHConnectionService is already running.
                    val isMonitoring = app.sshSessionManager.getConnection(profileId)?.isMonitoringOnly == true
                    if (isMonitoring) return@launch
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            disconnectedProfiles.remove(profileId)
                            renderHostNotification(profileId, disconnectingState = false)
                        }
                        ConnectionState.CONNECTING,
                        ConnectionState.ERROR -> {
                            renderHostNotification(profileId, disconnectingState = false)
                        }
                        ConnectionState.DISCONNECTED -> {
                            // onConnectionClosed is only fired by explicit
                            // SSHSessionManager.closeConnection() calls, NOT by
                            // natural remote disconnects (EOF, network drop, etc.).
                            // Both paths land here via onConnectionStateChanged, so
                            // we must update the notification here too. de-dup via
                            // disconnectedProfiles so we don't double-render if
                            // both onConnectionClosed AND this branch fire.
                            if (disconnectedProfiles.add(profileId)) {
                                renderHostNotification(profileId, disconnectingState = true)
                                updateConnectionCount()
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onAllConnectionsClosed() {
                updateConnectionCount()
                if (activeConnections == 0) {
                    serviceScope.launch {
                        // Give the per-host disconnect notifications their
                        // 30s auto-clear window before tearing the service
                        // down (which would otherwise nuke them).
                        delay(31_000)
                        if (activeConnections == 0) stopSelf()
                    }
                }
            }
        }
        sessionListener = listener
        app.sshSessionManager.addListener(listener)
    }

    private fun updateConnectionCount() {
        // Count active tabs rather than raw SSH sessions so that mosh tabs
        // (whose underlying SSH session may be closed after handoff) are
        // still counted as live connections. A tab is "live" as long as its
        // connectionState is CONNECTED — that covers both pure SSH and mosh.
        activeConnections = app.tabManager.getAllTabs()
            .count { it.connectionState.value == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED }

        if (activeConnections == 0) {
            // Last session disconnected. Don't tear the service down
            // immediately — the per-host "Disconnected" notifications
            // need their 30s timeout-after to actually display. The
            // delayed stop is scheduled in onAllConnectionsClosed; we
            // just release the wake lock here.
            backgroundWakeCycleJob?.cancel()
            backgroundWakeCycleJob = null
            releaseWakeLock()
            return
        }

        // At least one live session. Wake-lock mode depends on screen state:
        //  Screen on  → indefinite PARTIAL_WAKE_LOCK (user is interacting)
        //  Screen off → background cycle manages timed wake windows; don't
        //               acquire an indefinite lock here — it would undo the
        //               battery savings. Ensure the cycle is running.
        if (isScreenOn) {
            acquireWakeLockIndefinite()
        } else {
            ensureBackgroundWakeCycleRunning()
        }

        Logger.d("SSHConnectionService", "Active connections: $activeConnections")
    }

    // ── Screen-state callbacks ────────────────────────────────────────────────

    private fun onScreenOff() {
        isScreenOn = false
        if (activeConnections == 0) return
        // Switch from indefinite wake lock to the battery-efficient background
        // cycle. Release first so the device can actually sleep between cycles.
        releaseWakeLock()
        ensureBackgroundWakeCycleRunning()
        Logger.d(TAG, "Screen off — switched to background keepalive wake cycle")
    }

    private fun onScreenOn() {
        isScreenOn = true
        // Cancel the background cycle; transition back to indefinite wake lock.
        backgroundWakeCycleJob?.cancel()
        backgroundWakeCycleJob = null
        if (activeConnections > 0) {
            // Release first in case a timed wake lock from the last background
            // window is still held — timed + indefinite don't mix cleanly.
            releaseWakeLock()
            acquireWakeLockIndefinite()
        }
        Logger.d(TAG, "Screen on — switched to indefinite wake lock")
    }

    /**
     * Starts (or no-ops if already running) the background keep-alive wake
     * cycle used when the screen is off.
     *
     * Cycle structure:
     *  1. Acquire a timed PARTIAL_WAKE_LOCK for [BACKGROUND_WAKE_WINDOW_MS].
     *  2. While the lock is held the CPU is awake; JSch's internal Timer
     *     thread fires its overdue SSH_MSG_GLOBAL_REQUEST immediately.
     *  3. After the window the timed lock auto-expires and the CPU can sleep.
     *  4. Sleep for (keepaliveInterval − window) before the next cycle.
     *
     * Net effect: CPU awake ~15 s out of every ~60 s → ≈75 % battery saving
     * versus holding the lock indefinitely, while the SSH session stays alive.
     */
    private fun ensureBackgroundWakeCycleRunning() {
        if (backgroundWakeCycleJob?.isActive == true) return
        val keepaliveMs = try {
            app.preferencesManager.getServerAliveIntervalMs()
        } catch (_: Exception) { 60_000L }
        // Sleep between wake windows. Floor at 30 s; keepalive interval minus
        // the window so the total cycle equals roughly the keepalive interval.
        val sleepMs = maxOf(keepaliveMs - BACKGROUND_WAKE_WINDOW_MS, 30_000L)
        Logger.d(TAG, "Background wake cycle: ${BACKGROUND_WAKE_WINDOW_MS / 1000}s on / ${sleepMs / 1000}s sleep")
        backgroundWakeCycleJob = serviceScope.launch {
            while (isActive && !isScreenOn && activeConnections > 0) {
                acquireTimedWakeLock(BACKGROUND_WAKE_WINDOW_MS)
                delay(BACKGROUND_WAKE_WINDOW_MS)
                // Timed lock auto-releases after BACKGROUND_WAKE_WINDOW_MS;
                // also clear our reference so releaseWakeLock() stays clean.
                releaseWakeLock()
                // Sleep — CPU can enter low-power state during this window
                // because neither wake lock nor active coroutine requires it.
                // The foreground service keeps the process alive (Doze-exempt),
                // so this delay fires reliably when the sleep window expires.
                if (isActive && !isScreenOn && activeConnections > 0) delay(sleepMs)
            }
        }
    }

    // ── Wake lock helpers ─────────────────────────────────────────────────────

    /**
     * Acquire an indefinite PARTIAL_WAKE_LOCK. Used while the screen is on.
     * Idempotent: no-op if already held.
     */
    private fun acquireWakeLockIndefinite() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabSSH:SshSession")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            Logger.i(TAG, "Wake lock acquired (indefinite)")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Acquire a timed PARTIAL_WAKE_LOCK that auto-releases after [timeoutMs].
     * Used by the background wake cycle. Replaces any existing held lock so
     * the timeout is always [timeoutMs] from now.
     */
    private fun acquireTimedWakeLock(timeoutMs: Long) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabSSH:SshKeepAlive")
            wl.setReferenceCounted(false)
            wl.acquire(timeoutMs)
            wakeLock = wl
            Logger.d(TAG, "Wake lock acquired (timed ${timeoutMs / 1000}s)")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire timed wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock?.takeIf { it.isHeld } ?: return
            wl.release()
            Logger.i(TAG, "Wake lock released")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }
    
    private suspend fun startConnectionMonitoring() {
        Logger.d("SSHConnectionService", "Starting connection monitoring")

        while (serviceScope.isActive) {
            try {
                // Perform connection maintenance
                app.sshSessionManager.performMaintenance()

                // Update connection count
                updateConnectionCount()

                // Check for network changes and handle reconnections
                handleNetworkChanges()

                // Heartbeat-refresh all active per-host notifications. Each
                // nm.notify() call resets the setTimeoutAfter clock, so the
                // safety-net timeout only fires if this loop stops running
                // (i.e. service was killed without onDestroy).
                withContext(Dispatchers.Main) { refreshAllHostNotifications() }

                // Wait before next check. Screen off → 90 s is sufficient
                // since the background wake cycle handles keepalives
                // independently. Screen on → 30 s for snappy health checks.
                delay(if (isScreenOn) MONITORING_INTERVAL_FOREGROUND_MS else MONITORING_INTERVAL_BACKGROUND_MS)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal coroutine cancellation — propagate so the loop exits cleanly.
                throw e
            } catch (e: Exception) {
                Logger.e("SSHConnectionService", "Error in connection monitoring", e)
                delay(60_000L)
            }
        }
    }

    /**
     * Re-post the status notification for every currently-connected host.
     * This acts as a heartbeat that resets the [setTimeoutAfter] clock on
     * the CONNECTED/CONNECTING notifications. Must be called on the main
     * thread (Android notification API is safe from any thread, but
     * [renderHostNotification] accesses [fgAnchorProfileId] which is
     * `@Volatile` and written only on Main).
     *
     * Also refreshes the SSH session group summary so its count stays
     * accurate after sessions connect or disconnect.
     */
    private fun refreshAllHostNotifications() {
        try {
            val activeConns = app.sshSessionManager.getActiveConnections()
            // Exclude monitoring-only connections — they don't own a session
            // notification and must not refresh (or create) one here.
            val terminalConns = activeConns.filter { !it.isMonitoringOnly }
            terminalConns.forEach { conn ->
                val s = conn.connectionState.value
                if (s == ConnectionState.CONNECTED || s == ConnectionState.CONNECTING) {
                    renderHostNotification(conn.profile.id, disconnectingState = false)
                }
            }
            val connectedCount = terminalConns.count { it.connectionState.value == ConnectionState.CONNECTED }
            io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(this, connectedCount)
        } catch (e: Exception) {
            Logger.w("SSHConnectionService", "Failed to refresh host notifications", e)
        }
    }
    
    /**
     * Cancel stale per-host notifications that no longer correspond to a
     * live SSH session.
     *
     * On API 23+ we can enumerate the app's active notifications and filter
     * to the per-host id range `[10_000, 100_000)`, keeping only the ids
     * that belong to currently-connected sessions (unless [cancelAll] is
     * true, in which case all are cancelled — used from [onDestroy]).
     *
     * On older APIs we can't list active notifications. [cancelAll]=true
     * cancels every id derived from known active connections; [cancelAll]=false
     * is a no-op (the [setTimeoutAfter] safety net covers those devices).
     */
    private fun sweepPerHostNotifications(cancelAll: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val liveIds: Set<Int> = if (cancelAll) emptySet() else {
                try {
                    app.sshSessionManager.getActiveConnections()
                        .map { io.github.tabssh.utils.NotificationHelper.perHostNotificationId(it.profile.id) }
                        .toSet()
                } catch (_: Exception) { emptySet() }
            }
            nm.activeNotifications
                .filter { it.id in 10_000..99_999 && it.id !in liveIds }
                .forEach { nm.cancel(it.id) }
        } else {
            // Pre-M: cancel only what we know about.
            if (cancelAll) {
                try {
                    app.sshSessionManager.getActiveConnections().forEach { conn ->
                        nm.cancel(io.github.tabssh.utils.NotificationHelper.perHostNotificationId(conn.profile.id))
                    }
                } catch (_: Exception) { /* nothing */ }
            }
        }
        // Keep the SSH group summary in sync: cancel it when sweeping all,
        // refresh it when sweeping stale only (some may still be live).
        if (cancelAll) {
            io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(this, 0)
        } else {
            try {
                val connectedCount = app.sshSessionManager.getActiveConnections()
                    .count { it.connectionState.value == ConnectionState.CONNECTED }
                io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(this, connectedCount)
            } catch (_: Exception) { /* non-fatal */ }
        }
    }

    /**
     * Health-check every active SSH connection.
     *
     * This runs every 30 s from [startConnectionMonitoring]. It calls
     * [SSHConnection.triggerReconnectIfDead] on each non-monitoring connection,
     * which:
     *   - Detects sessions that died silently (screen lock → WiFi sleep →
     *     keepalive timeout, NAT expiry, remote EOF) without the disconnect
     *     bubbling back through [handleConnectionError].
     *   - Transitions the connection state to DISCONNECTED so the notification
     *     stops showing "Connected" for a dead session.
     *   - Arms [NetworkAwareReconnector] so the reconnect fires with exponential
     *     backoff and network gating — the same path a normal error-path drop uses.
     */
    private suspend fun handleNetworkChanges() {
        val connections = try {
            app.sshSessionManager.getActiveConnections()
        } catch (e: Exception) {
            Logger.w(TAG, "handleNetworkChanges: failed to get active connections", e)
            return
        }
        for (conn in connections) {
            if (conn.isMonitoringOnly) continue
            try {
                conn.triggerReconnectIfDead()
            } catch (e: Exception) {
                Logger.w(TAG, "handleNetworkChanges: probe failed for ${conn.profile.host}", e)
            }
        }
    }
}
package io.github.tabssh.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

    // Partial wake lock held while we have at least one live SSH session.
    // Without it, when the screen turns off the OS aggressively suspends
    // the network stack and the JSch session keepalives miss their slot,
    // dropping the connection. With PARTIAL_WAKE_LOCK the CPU stays awake
    // (the screen still turns off — that's PROXIMITY/SCREEN locks, not
    // this one). Released when activeConnections drops to zero.
    private var wakeLock: PowerManager.WakeLock? = null

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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ssh_connections"
        private const val CHANNEL_NAME = "SSH Connections"
        
        const val ACTION_START_SERVICE = "io.github.tabssh.START_SERVICE"
        const val ACTION_STOP_SERVICE = "io.github.tabssh.STOP_SERVICE"
        
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
    
    override fun onCreate() {
        super.onCreate()

        Logger.d("SSHConnectionService", "Service created")

        app = application as TabSSHApplication

        createNotificationChannel()
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
        
        return START_STICKY // Restart if killed by system
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()

        Logger.d("SSHConnectionService", "Service destroyed")

        // Cancel all per-host notifications before tearing down — prevents
        // stale "Connected to …" entries lingering in the notification shade
        // after a graceful service stop.
        sweepPerHostNotifications(cancelAll = true)
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

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent SSH connections"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Transient placeholder used as the FG-service anchor when the
     * service starts before any session has connected. Swapped out the
     * moment a per-host notification is available. Same channel as the
     * legacy aggregate notification (low importance, silent).
     */
    private fun buildPlaceholderNotification(): Notification {
        val tapTarget = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
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
        val conn = app.sshSessionManager.getConnection(profileId) ?: return
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
                            // Handled by onConnectionClosed (which de-dups
                            // via disconnectedProfiles).
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
        val stats = app.sshSessionManager.getConnectionStatistics()
        activeConnections = stats.connectedConnections

        if (activeConnections == 0) {
            // Last session disconnected. Don't tear the service down
            // immediately — the per-host "Disconnected" notifications
            // need their 30s timeout-after to actually display. The
            // delayed stop is scheduled in onAllConnectionsClosed; we
            // just release the wake lock here.
            releaseWakeLock()
            return
        }

        // We have at least one live session — keep the CPU awake so SSH
        // keepalives don't miss their slot when the screen turns off.
        acquireWakeLock()

        Logger.d("SSHConnectionService", "Active connections: $activeConnections")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabSSH:SshSession")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            Logger.i("SSHConnectionService", "Wake lock acquired")
        } catch (e: Exception) {
            Logger.w("SSHConnectionService", "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            val wl = wakeLock?.takeIf { it.isHeld } ?: return
            wl.release()
            Logger.i("SSHConnectionService", "Wake lock released")
        } catch (e: Exception) {
            Logger.w("SSHConnectionService", "Failed to release wake lock", e)
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

                // Wait before next check
                delay(30_000L)

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
     */
    private fun refreshAllHostNotifications() {
        try {
            app.sshSessionManager.getActiveConnections().forEach { conn ->
                val s = conn.connectionState.value
                if (s == ConnectionState.CONNECTED || s == ConnectionState.CONNECTING) {
                    renderHostNotification(conn.profile.id, disconnectingState = false)
                }
            }
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
    }

    private suspend fun handleNetworkChanges() {
        // This would implement network change detection and reconnection logic
        // For now, just log that we're checking
        Logger.d("SSHConnectionService", "Checking network state")
        
        // In a full implementation, this would:
        // 1. Check network connectivity
        // 2. Detect network changes (WiFi to mobile, etc.)
        // 3. Trigger reconnections if needed
        // 4. Handle connection failures due to network issues
    }
}
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
import android.net.wifi.WifiManager
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

    // WiFi lock — keeps the WiFi radio out of power-saving mode while any
    // SSH session is active. PARTIAL_WAKE_LOCK keeps the CPU awake but does
    // not prevent the WiFi radio from sleeping; TCP connections drop when
    // the radio sleeps even if the CPU is awake. The WiFi lock is held
    // continuously (not cycled) because WiFi sleep/wake transitions take
    // hundreds of milliseconds — longer than a keepalive window — meaning
    // the radio could sleep between wake cycles and the TCP connection
    // would drop before the next keepalive fires.
    // Uses WIFI_MODE_FULL_LOW_LATENCY on API 29+ (best for interactive SSH;
    // when screen is off it degrades gracefully to WIFI_MODE_FULL).
    // Uses WIFI_MODE_FULL_HIGH_PERF on older APIs (canonical VoIP pattern).
    private var wifiLock: WifiManager.WifiLock? = null

    // True when the device display is interactive (screen on / locked but lit).
    // Initialised from PowerManager.isInteractive() in onCreate so the first
    // acquireWakeLock() call after a process restart uses the right mode.
    @Volatile private var isScreenOn = true

    // Coroutine that drives the screen-off keep-alive wake cycle.
    // Null when screen is on or there are no active connections.
    private var backgroundWakeCycleJob: Job? = null

    // Per-tab notification bookkeeping. Android requires a foreground
    // service to keep at least one ongoing notification while alive
    // ("the FG anchor") — we pick one of the per-tab notifications and
    // call startForeground(id, notif). When that tab closes/disconnects
    // we swap the anchor to another live tab (if any), otherwise we stop
    // the service.
    @Volatile private var fgAnchorTabId: String? = null

    // Tracks tab ids we've already rendered as "disconnected" so a
    // duplicate state-change event doesn't re-post the notification.
    private val disconnectedTabs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Tracks profile ids whose session-level disconnect alert already
    // fired, so a duplicate onConnectionClosed doesn't re-alert.
    private val disconnectedProfiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // TabManagerListener that drives the per-tab notifications — one shade
    // entry per tab (four tabs = four notifications, even to one host).
    private var tabListener: io.github.tabssh.ui.tabs.TabManagerListener? = null

    // Graphical (VNC/console) session tracking. The TabManagerListener's
    // state callback is SSH-typed, so graphical tabs are observed directly:
    // one watcher on allTabsFlow manages a per-tab connectionState collector
    // for every Tab.Vnc/Tab.Console, mirroring the shade behaviour SSH tabs
    // get — persistent "Connected" row with a Disconnect action, auto-
    // clearing "Disconnected" row, heartbeat refresh.
    private var graphicalWatchJob: Job? = null
    private val graphicalStateObservers = mutableMapOf<String, Job>()

    // De-dup set mirroring [disconnectedTabs] for graphical tabs.
    private val disconnectedGraphicalTabs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // The single active monitoring coroutine. Stored so that a second
    // onStartCommand(ACTION_START_SERVICE) call (which can happen if the
    // app sends multiple startForegroundService() requests before the first
    // one is processed) replaces the old loop rather than stacking on top
    // of it.
    private var monitoringJob: Job? = null

    companion object {
        // Placeholder notification ID — used only for the transient "Starting SSH
        // session…" notification before the first per-tab notification is live.
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
        // Sweep any per-tab notifications that are stale from a previous
        // service lifetime (e.g. process killed by OOM without onDestroy).
        sweepPerTabNotifications(cancelAll = false)
        setupSessionManagerListener()
        setupTabManagerListener()
        setupGraphicalTabWatcher()
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

        // Cancel all per-tab notifications before tearing down — prevents
        // stale "Connected to …" entries lingering in the notification shade
        // after a graceful service stop.
        sweepPerTabNotifications(cancelAll = true)

        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        backgroundWakeCycleJob?.cancel()
        backgroundWakeCycleJob = null

        serviceScope.cancel()
        releaseWakeLock()
        releaseWifiLock()
        // Don't tear down the manager here — its lifecycle is the
        // Application's, not the service's. The service is allowed to
        // stop and restart (e.g. when there are zero active sessions),
        // and cancelling the manager's scope here breaks every future
        // connect() in the process.
        sessionListener?.let { app.sshSessionManager.removeListener(it) }
        sessionListener = null
        tabListener?.let { app.tabManager.removeListener(it) }
        tabListener = null
        graphicalWatchJob?.cancel()
        graphicalWatchJob = null
        graphicalStateObservers.values.forEach { it.cancel() }
        graphicalStateObservers.clear()
    }
    
    private fun startForegroundService() {
        // Acquire the appropriate wake lock immediately — before any session
        // event fires. Without a lock the CPU can idle during the connecting /
        // authenticating phase (aggressive power management on many OEMs can
        // cause TCP handshake or JSch kex to time out on screen-off).
        if (isScreenOn) acquireWakeLockIndefinite() else ensureBackgroundWakeCycleRunning()
        // WiFi lock ensures the radio stays out of power-saving mode from the
        // moment the service starts so the TCP handshake is never stalled by
        // the radio waking up.
        acquireWifiLock()

        // The foreground-service contract requires *some* notification
        // to be live before startForeground returns. If we already have
        // an active tab, anchor on it; otherwise post a transient
        // placeholder (cleared as soon as the first session connects).
        val activeTab = try {
            app.tabManager.getAllTabs().firstOrNull {
                it.connectionState.value == ConnectionState.CONNECTED ||
                it.connectionState.value == ConnectionState.CONNECTING
            }
        } catch (_: Exception) { null }

        if (activeTab != null) {
            val notif = io.github.tabssh.utils.NotificationHelper.buildTabStatusNotification(
                this, activeTab.profile, activeTab.tabId,
                activeTab.connectionState.value, tabTitleOf(activeTab)
            )
            val id = io.github.tabssh.utils.NotificationHelper.perTabNotificationId(activeTab.tabId)
            startForeground(id, notif)
            fgAnchorTabId = activeTab.tabId
        } else {
            // No live SSH tab — a live graphical (VNC/console) tab can
            // anchor the FG service just as well (pure-VNC sessions start
            // the service with zero SSH tabs).
            val graphicalTab = graphicalLiveTabs().firstOrNull()
            if (graphicalTab != null) {
                anchorOnGraphicalTab(graphicalTab)
            } else {
                // Placeholder anchor, swapped out on first tab connect.
                startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
            }
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
     * moment a per-tab notification is available.
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
     * The tab's OSC/terminal-set title, or null when it's still the
     * profile display name (so the notification's content line doesn't
     * repeat the title line).
     */
    private fun tabTitleOf(tab: io.github.tabssh.ui.tabs.SSHTab): String? =
        tab.getDisplayTitle().takeIf { it.isNotBlank() && it != tab.profile.getDisplayName() }

    /**
     * Exit-status-based clean/error classification for a tab's disconnect
     * render. Mosh tabs may have a null [SSHTab.connection] after handoff;
     * fall back to the session manager's connection for the profile. When
     * neither is available, treat as clean (no evidence of an error).
     */
    private fun tabCleanExit(tab: io.github.tabssh.ui.tabs.SSHTab): Boolean {
        val exit = tab.connection?.getShellExitStatus()
            ?: app.sshSessionManager.getConnection(tab.profile.id)?.getShellExitStatus()
        return exit == null || exit == 0
    }

    /** Detach the FG notification pin without stopping the service. */
    private fun detachForeground() {
        stopForeground(STOP_FOREGROUND_DETACH)
        fgAnchorTabId = null
    }

    /**
     * Swap the FG anchor from [closingTabId] to another live tab, or
     * detach FG entirely if no other tab is connected. Must run on Main.
     */
    private fun swapAnchorAwayFrom(closingTabId: String) {
        if (fgAnchorTabId != closingTabId) return
        val nextLive = app.tabManager.getAllTabs()
            .firstOrNull { it.tabId != closingTabId &&
                           it.connectionState.value == ConnectionState.CONNECTED }
        if (nextLive != null) {
            val nextNotif = io.github.tabssh.utils.NotificationHelper.buildTabStatusNotification(
                this, nextLive.profile, nextLive.tabId,
                nextLive.connectionState.value, tabTitleOf(nextLive)
            )
            val nextId = io.github.tabssh.utils.NotificationHelper.perTabNotificationId(nextLive.tabId)
            startForeground(nextId, nextNotif)
            fgAnchorTabId = nextLive.tabId
            return
        }
        // No live SSH tab — try a live graphical (VNC/console) tab before
        // giving up the FG anchor.
        val nextGraphical = graphicalLiveTabs().firstOrNull { it.tabId != closingTabId }
        if (nextGraphical != null) {
            anchorOnGraphicalTab(nextGraphical)
        } else {
            // No live tab to anchor on — detach FG so the timeout-
            // after-30s on the disconnect notification can take effect.
            detachForeground()
        }
    }

    /** Live (CONNECTED) graphical tabs, in unified-list order. */
    private fun graphicalLiveTabs(): List<io.github.tabssh.ui.tabs.Tab> = try {
        app.tabManager.getAllTabsSealed().filter { entry ->
            when (entry) {
                is io.github.tabssh.ui.tabs.Tab.Ssh -> false
                is io.github.tabssh.ui.tabs.Tab.Vnc ->
                    entry.vncTab.connectionState.value == ConnectionState.CONNECTED
                is io.github.tabssh.ui.tabs.Tab.Console ->
                    entry.consoleTab.connectionState.value == ConnectionState.CONNECTED
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Display title + protocol label + current state for a graphical tab. */
    private fun graphicalTabInfo(
        tab: io.github.tabssh.ui.tabs.Tab
    ): Triple<String, String, ConnectionState>? = when (tab) {
        is io.github.tabssh.ui.tabs.Tab.Ssh -> null
        is io.github.tabssh.ui.tabs.Tab.Vnc -> Triple(
            tab.vncTab.getDisplayTitle(), "vnc", tab.vncTab.connectionState.value
        )
        is io.github.tabssh.ui.tabs.Tab.Console -> Triple(
            tab.consoleTab.getDisplayTitle(), "console", tab.consoleTab.connectionState.value
        )
    }

    /** startForeground() on a graphical tab's status notification. */
    private fun anchorOnGraphicalTab(tab: io.github.tabssh.ui.tabs.Tab) {
        val (title, protocol, state) = graphicalTabInfo(tab) ?: return
        val notif = io.github.tabssh.utils.NotificationHelper.buildGraphicalTabStatusNotification(
            this, tab.tabId, title, protocol, state
        )
        val id = io.github.tabssh.utils.NotificationHelper.perTabNotificationId(tab.tabId)
        startForeground(id, notif)
        fgAnchorTabId = tab.tabId
    }

    /**
     * Render or update the per-tab status notification for [tab] on the
     * silent channel. Also keeps the foreground-service anchor pointed at
     * a live tab (swaps if the current anchor disconnected, adopts on the
     * first connect). Must run on Main ([fgAnchorTabId] discipline).
     *
     * `disconnectingState` is true when this is a terminal "Disconnected"
     * render — the notification flips to the auto-cleared variant and
     * we *don't* leave it as the FG anchor (Android won't auto-clear an
     * ongoing FG notification while service is alive).
     */
    private fun renderTabNotification(
        tab: io.github.tabssh.ui.tabs.SSHTab,
        disconnectingState: Boolean = false
    ) {
        val state = tab.connectionState.value
        val effectiveState = if (disconnectingState)
            ConnectionState.DISCONNECTED else state

        val notif = io.github.tabssh.utils.NotificationHelper.buildTabStatusNotification(
            this, tab.profile, tab.tabId, effectiveState, tabTitleOf(tab), tabCleanExit(tab)
        )
        val nid = io.github.tabssh.utils.NotificationHelper.perTabNotificationId(tab.tabId)

        // FG-anchor management: if the live anchor is this tab, the FG
        // notification IS this notification — call startForeground to
        // refresh the OS-side reference. If we're disconnecting and this
        // is the anchor, swap to another live tab first (or release FG
        // entirely if no other tab is alive).
        if (!disconnectingState && state == ConnectionState.CONNECTED) {
            if (fgAnchorTabId == null || fgAnchorTabId == tab.tabId) {
                startForeground(nid, notif)
                // If the FG anchor was previously the placeholder (null →
                // NOTIFICATION_ID 1001 on the legacy channel), cancel it
                // explicitly — Android won't remove the old anchor
                // notification when startForeground is called with a
                // different id.
                if (fgAnchorTabId == null) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                fgAnchorTabId = tab.tabId
                return
            }
        }
        if (disconnectingState) {
            swapAnchorAwayFrom(tab.tabId)
        }

        // Final post for the per-tab notification (CONNECTED update,
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

    /**
     * Graphical-tab counterpart of [renderTabNotification]: render or
     * update the per-tab status notification for a Vnc/Console tab, with
     * the same FG-anchor adopt/swap discipline. Must run on Main.
     */
    private fun renderGraphicalTabNotification(
        tab: io.github.tabssh.ui.tabs.Tab,
        disconnectingState: Boolean = false
    ) {
        val (title, protocol, liveState) = graphicalTabInfo(tab) ?: return
        val effectiveState = if (disconnectingState) ConnectionState.DISCONNECTED else liveState

        val notif = io.github.tabssh.utils.NotificationHelper.buildGraphicalTabStatusNotification(
            this, tab.tabId, title, protocol, effectiveState
        )
        val nid = io.github.tabssh.utils.NotificationHelper.perTabNotificationId(tab.tabId)

        if (!disconnectingState && liveState == ConnectionState.CONNECTED) {
            if (fgAnchorTabId == null || fgAnchorTabId == tab.tabId) {
                startForeground(nid, notif)
                if (fgAnchorTabId == null) {
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                }
                fgAnchorTabId = tab.tabId
                return
            }
        }
        if (disconnectingState) {
            swapAnchorAwayFrom(tab.tabId)
        }

        val nm = getSystemService(NotificationManager::class.java)
        // Same FG-flag reset as the SSH variant: cancel before re-posting
        // the auto-clearing DISCONNECTED render.
        if (disconnectingState) {
            nm.cancel(nid)
        }
        nm.notify(nid, notif)
    }

    /**
     * Observe [io.github.tabssh.ui.tabs.TabManager.allTabsFlow] and keep a
     * per-tab connectionState collector alive for every graphical
     * (Vnc/Console) tab — the notification driver those tabs don't get
     * from the SSH-typed [io.github.tabssh.ui.tabs.TabManagerListener].
     *
     * Has-been-connected gating mirrors TabManager's SSH observer: a
     * brand-new tab starts at DISCONNECTED, which must not render a
     * "Disconnected" row.
     */
    private fun setupGraphicalTabWatcher() {
        graphicalWatchJob = serviceScope.launch(Dispatchers.Main) {
            app.tabManager.allTabsFlow.collect { tabs ->
                // Drop observers + notifications for tabs that no longer exist.
                val live = tabs.map { it.tabId }.toSet()
                (graphicalStateObservers.keys - live).toList().forEach { id ->
                    graphicalStateObservers.remove(id)?.cancel()
                    swapAnchorAwayFrom(id)
                    io.github.tabssh.utils.NotificationHelper.cancelTabNotification(
                        this@SSHConnectionService, id
                    )
                    disconnectedGraphicalTabs.remove(id)
                    updateConnectionCount()
                    maybeScheduleStopIfIdle()
                }
                tabs.forEach { entry ->
                    if (entry.tabId in graphicalStateObservers) return@forEach
                    val stateFlow = when (entry) {
                        is io.github.tabssh.ui.tabs.Tab.Ssh -> return@forEach
                        is io.github.tabssh.ui.tabs.Tab.Vnc -> entry.vncTab.connectionState
                        is io.github.tabssh.ui.tabs.Tab.Console -> entry.consoleTab.connectionState
                    }
                    graphicalStateObservers[entry.tabId] = serviceScope.launch(Dispatchers.Main) {
                        var hasBeenConnected = false
                        stateFlow.collect { state ->
                            when (state) {
                                ConnectionState.CONNECTED -> {
                                    hasBeenConnected = true
                                    disconnectedGraphicalTabs.remove(entry.tabId)
                                    renderGraphicalTabNotification(entry, disconnectingState = false)
                                    updateConnectionCount()
                                }
                                ConnectionState.DISCONNECTED -> {
                                    if (hasBeenConnected &&
                                        disconnectedGraphicalTabs.add(entry.tabId)) {
                                        renderGraphicalTabNotification(entry, disconnectingState = true)
                                        updateConnectionCount()
                                        maybeScheduleStopIfIdle()
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Self-stop path for graphical-only sessions: SSH sessions stop the
     * service via onAllConnectionsClosed, which VNC/SPICE never fire.
     * Same 31s grace as that path so the auto-clearing "Disconnected"
     * rows get their timeout window before the service dies.
     */
    private fun maybeScheduleStopIfIdle() {
        if (activeConnections > 0) return
        serviceScope.launch {
            delay(31_000)
            if (activeConnections == 0) stopSelf()
        }
    }

    private fun setupSessionManagerListener() {
        // Session-level listener: DB stats, audible alerts, and service
        // self-stop. Notification RENDERING is driven by the tab listener
        // (setupTabManagerListener) — one notification per tab, so N tabs
        // to the same host produce N shade entries. Monitoring-only
        // sessions never own a tab, so they are naturally invisible to
        // the notification layer.
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
                        // -1 (drop) or non-zero (abnormal exit)
                        s != 0
                    } ?: true
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
                // Per-tab notifications follow SSHTab.connectionState (via
                // the TabManagerListener below), not the session state —
                // a session-level CONNECTED with two tabs open must render
                // two notifications, and a session drop cascades into each
                // tab's own DISCONNECTED transition. Only the counters and
                // wake/WiFi-lock bookkeeping react here.
                updateConnectionCount()
                if (state == ConnectionState.CONNECTED) {
                    disconnectedProfiles.remove(profileId)
                }
            }

            override fun onAllConnectionsClosed() {
                updateConnectionCount()
                if (activeConnections == 0) {
                    serviceScope.launch {
                        // Give the per-tab disconnect notifications their
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

    private fun setupTabManagerListener() {
        // Tab-level listener: the source of truth for the per-tab shade
        // entries. TabManager's per-tab state observer already suppresses
        // the initial DISCONNECTED replay (a brand-new tab starts at
        // DISCONNECTED), so every DISCONNECTED that arrives here is a real
        // post-connect disconnect.
        val listener = object : io.github.tabssh.ui.tabs.TabManagerListener {
            override fun onTabConnectionStateChanged(
                tab: io.github.tabssh.ui.tabs.SSHTab,
                state: ConnectionState
            ) {
                updateConnectionCount()
                serviceScope.launch(Dispatchers.Main) {
                    when (state) {
                        ConnectionState.CONNECTED -> {
                            disconnectedTabs.remove(tab.tabId)
                            renderTabNotification(tab, disconnectingState = false)
                        }
                        ConnectionState.CONNECTING,
                        ConnectionState.ERROR -> {
                            renderTabNotification(tab, disconnectingState = false)
                        }
                        ConnectionState.DISCONNECTED -> {
                            // De-dup: both an explicit disconnect and the
                            // state-flow cascade can land here; render the
                            // auto-clearing "Disconnected" variant once.
                            if (disconnectedTabs.add(tab.tabId)) {
                                renderTabNotification(tab, disconnectingState = true)
                                updateConnectionCount()
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onTabClosed(tab: io.github.tabssh.ui.tabs.SSHTab, index: Int) {
                // Notification cleanup on tab close/exit: the tab is gone,
                // so its shade entry must go immediately — no lingering
                // "Connected to …" rows (the heartbeat can no longer see
                // this tab, and setTimeoutAfter alone would leave the stale
                // entry up for as long as 20 minutes).
                updateConnectionCount()
                serviceScope.launch(Dispatchers.Main) {
                    swapAnchorAwayFrom(tab.tabId)
                    io.github.tabssh.utils.NotificationHelper.cancelTabNotification(
                        this@SSHConnectionService, tab.tabId
                    )
                    disconnectedTabs.remove(tab.tabId)
                    val connectedCount = app.tabManager.getAllTabs()
                        .count { it.connectionState.value == ConnectionState.CONNECTED } +
                        graphicalLiveTabs().size
                    io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(
                        this@SSHConnectionService, connectedCount
                    )
                }
            }
        }
        tabListener = listener
        app.tabManager.addListener(listener)
    }

    private fun updateConnectionCount() {
        // Count active tabs rather than raw SSH sessions so that mosh tabs
        // (whose underlying SSH session may be closed after handoff) are
        // still counted as live connections. A tab is "live" as long as its
        // connectionState is CONNECTED — that covers both pure SSH and mosh.
        // Graphical (VNC/console) tabs count too: a pure-VNC session must
        // keep the service (and its wake/WiFi locks) alive exactly like an
        // SSH one, or the socket dies the moment the screen blanks.
        activeConnections = app.tabManager.getAllTabs()
            .count { it.connectionState.value == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED } +
            graphicalLiveTabs().size

        if (activeConnections == 0) {
            // Last session disconnected. Don't tear the service down
            // immediately — the per-tab "Disconnected" notifications
            // need their 30s timeout-after to actually display. The
            // delayed stop is scheduled in onAllConnectionsClosed; we
            // just release both locks here.
            backgroundWakeCycleJob?.cancel()
            backgroundWakeCycleJob = null
            releaseWakeLock()
            releaseWifiLock()
            return
        }

        // At least one live session — ensure the WiFi radio stays awake.
        acquireWifiLock()

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

    // ── WiFi lock helpers ─────────────────────────────────────────────────────

    /**
     * Acquire a WiFi lock to keep the radio out of power-saving mode.
     *
     * Uses [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] on API 29+ — optimal for
     * interactive SSH; degrades to WIFI_MODE_FULL automatically when the screen
     * is off. On older APIs uses [WifiManager.WIFI_MODE_FULL_HIGH_PERF], the
     * canonical VoIP/SSH pattern for keeping the radio fully awake.
     *
     * Idempotent: no-op if already held.
     */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wl = wm.createWifiLock(mode, "TabSSH:SshWifi")
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
            Logger.i(TAG, "WiFi lock acquired")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire WiFi lock", e)
        }
    }

    /**
     * Release the WiFi lock, allowing the radio to enter power-saving mode.
     * Called when all connections close or the service is destroyed.
     */
    private fun releaseWifiLock() {
        try {
            val wl = wifiLock?.takeIf { it.isHeld } ?: return
            wl.release()
            Logger.i(TAG, "WiFi lock released")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to release WiFi lock", e)
        } finally {
            wifiLock = null
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

                // Heartbeat-refresh all active per-tab notifications. Each
                // nm.notify() call resets the setTimeoutAfter clock, so the
                // safety-net timeout only fires if this loop stops running
                // (i.e. service was killed without onDestroy).
                withContext(Dispatchers.Main) { refreshAllTabNotifications() }

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
     * Re-post the status notification for every currently-connected tab.
     * This acts as a heartbeat that resets the [setTimeoutAfter] clock on
     * the CONNECTED/CONNECTING notifications. Must be called on the main
     * thread (Android notification API is safe from any thread, but
     * [renderTabNotification] accesses [fgAnchorTabId] which is
     * `@Volatile` and written only on Main).
     *
     * Also sweeps orphaned per-tab notifications (shade entries whose tab
     * no longer exists) and refreshes the SSH session group summary so
     * its count stays accurate after tabs open, close, or disconnect.
     */
    private fun refreshAllTabNotifications() {
        try {
            val tabs = app.tabManager.getAllTabs()
            tabs.forEach { tab ->
                val s = tab.connectionState.value
                if (s == ConnectionState.CONNECTED || s == ConnectionState.CONNECTING) {
                    renderTabNotification(tab, disconnectingState = false)
                }
            }
            // Graphical tabs share the same heartbeat so their 20-minute
            // safety-net timeout keeps getting reset while the service lives.
            graphicalLiveTabs().forEach { tab ->
                renderGraphicalTabNotification(tab, disconnectingState = false)
            }
            // Orphan sweep — belt-and-braces cleanup for any per-tab
            // notification whose tab vanished without an onTabClosed
            // cancel (e.g. process restart with a stale shade entry).
            sweepPerTabNotifications(cancelAll = false)
        } catch (e: Exception) {
            Logger.w("SSHConnectionService", "Failed to refresh tab notifications", e)
        }
    }

    /**
     * Cancel stale per-tab notifications that no longer correspond to a
     * live tab.
     *
     * On API 23+ we can enumerate the app's active notifications and filter
     * to the per-tab id range `[10_000, 100_000)`, keeping only the ids
     * that belong to currently-open tabs (unless [cancelAll] is true, in
     * which case all are cancelled — used from [onDestroy]).
     *
     * On older APIs we can't list active notifications. [cancelAll]=true
     * cancels every id derived from known open tabs; [cancelAll]=false
     * is a no-op (the [setTimeoutAfter] safety net covers those devices).
     */
    private fun sweepPerTabNotifications(cancelAll: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        // Sealed list: graphical (VNC/console) tabs share the per-tab id
        // range, so an SSH-only live set would sweep their notifications
        // away on every heartbeat.
        val tabs = try { app.tabManager.getAllTabsSealed() } catch (_: Exception) { emptyList() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val liveIds: Set<Int> = if (cancelAll) emptySet() else {
                tabs.map { io.github.tabssh.utils.NotificationHelper.perTabNotificationId(it.tabId) }
                    .toSet()
            }
            nm.activeNotifications
                .filter { it.id in 10_000..99_999 && it.id !in liveIds }
                .forEach { nm.cancel(it.id) }
        } else {
            // Pre-M: cancel only what we know about.
            if (cancelAll) {
                tabs.forEach { tab ->
                    nm.cancel(io.github.tabssh.utils.NotificationHelper.perTabNotificationId(tab.tabId))
                }
            }
        }
        // Keep the sessions group summary in sync: cancel it when sweeping
        // all, refresh it when sweeping stale only (some may still be live).
        if (cancelAll) {
            io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(this, 0)
        } else {
            val connectedCount = app.tabManager.getAllTabs()
                .count { it.connectionState.value == ConnectionState.CONNECTED } +
                graphicalLiveTabs().size
            io.github.tabssh.utils.NotificationHelper.postSshGroupSummary(this, connectedCount)
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

package io.github.tabssh.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.tabssh.R
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.utils.logging.Logger

/**
 * Notification Helper
 * Manages all app notifications with proper channels for Android 8+.
 *
 * Channel layout:
 * ┌─ ssh_service_v2       — FG service anchor / placeholder (low, silent)
 * ├─ ssh_silent_v3        — per-host SSH session status (low, silent)
 * ├─ ssh_alerts_v3        — per-host SSH events (high, audible, per-profile gated)
 * ├─ file_transfer_v2     — SFTP progress (low, silent)
 * ├─ errors_v2            — connection errors / auth failures (high, audible)
 * ├─ host_monitoring_v1   — host down/recovery alerts (high, audible)
 * └─ host_metrics_v1      — CPU/mem/disk threshold breaches (default, silent)
 *
 * Notification group keys:
 * - GROUP_SSH_SESSIONS   — groups all per-host SSH session notifications
 * - GROUP_MONITORING     — groups all monitoring (down/recovery/metric) alerts
 */
object NotificationHelper {

    // ── Notification IDs ──────────────────────────────────────────────────────

    const val NOTIFICATION_ID_SERVICE = 1001
    const val NOTIFICATION_ID_FILE_TRANSFER = 3001
    const val NOTIFICATION_ID_ERROR = 4001

    // Group summary IDs — must be outside the per-host (10_000–99_999) and
    // monitoring (200_000–289_999) ranges so they never collide.
    private const val NOTIFICATION_ID_SSH_GROUP = 1000
    private const val NOTIFICATION_ID_MONITORING_GROUP = 199_999

    // Per-host notifications occupy a dedicated id range. The id is
    // derived from `profile.id.hashCode()` so it's stable across the
    // app's lifetime for any given profile (and so update/cancel can
    // target the right notification without a separate lookup table).
    // Keeps the value positive and outside the constant ids above.
    fun perHostNotificationId(profileId: String): Int {
        return 10_000 + ((profileId.hashCode().toLong() and 0x7FFFFFFFL) % 90_000).toInt()
    }

    // ── Notification group keys ───────────────────────────────────────────────

    // Android notification grouping: child notifications carry setGroup(key);
    // one summary notification carries setGroup(key) + setGroupSummary(true).
    // The OS collapses children under the summary in the notification shade
    // when there are 4+ children (behaviour varies by launcher).
    private const val GROUP_SSH_SESSIONS = "tabssh_ssh_sessions"
    private const val GROUP_MONITORING   = "tabssh_monitoring"

    // ── Notification channel IDs ──────────────────────────────────────────────

    // Channels are bumped to _v2/_v3 after prior releases to force
    // Android to apply new channel defaults (cached on first creation).

    // Exposed so SSHConnectionService can reference it for its placeholder
    // notification without creating a duplicate private channel.
    internal const val CHANNEL_SERVICE = "ssh_service_v2"

    private const val CHANNEL_FILE_TRANSFER = "file_transfer_v2"
    private const val CHANNEL_ERROR       = "errors_v2"

    // Per-host status (persistent, silent). Default channel for the
    // ongoing per-session notification — never beeps or vibrates.
    private const val CHANNEL_SSH_SILENT  = "ssh_silent_v3"

    // Per-host alert (one-shot, audible). Used only when the connection
    // profile's notif_sound_mode / notif_vibrate_mode allow it. The
    // posted notification carries setTimeoutAfter so the OS auto-clears
    // it after a brief window.
    private const val CHANNEL_SSH_ALERTS  = "ssh_alerts_v3"

    // Background host monitoring — down/recovery alerts.
    internal const val CHANNEL_HOST_MONITORING = "host_monitoring_v1"

    // Background host metric threshold alerts (CPU/mem/disk).
    internal const val CHANNEL_HOST_METRICS = "host_metrics_v1"
    
    /**
     * Create all notification channels (Android 8+)
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // ── Session Service ───────────────────────────────────────────────────
            // Transient foreground-service anchor. Posted as a silent placeholder
            // ("Starting SSH session…") before the first real per-host notification
            // is live. Swapped out immediately on first connect; users rarely see
            // this beyond the first half-second of a new connection.
            // Importance LOW: must not surface as a heads-up banner or make noise.
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Session Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Transient foreground-service anchor while a session is starting"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // ── File Transfers ────────────────────────────────────────────────────
            // Progress and completion notifications for SFTP uploads and downloads.
            // Ongoing while transfer is in flight; auto-cleared on completion.
            // Importance LOW: progress updates should not interrupt the user.
            val fileTransferChannel = NotificationChannel(
                CHANNEL_FILE_TRANSFER,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SFTP file upload and download progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // ── Connection Errors ─────────────────────────────────────────────────
            // Fired when a connection attempt fails (auth error, network timeout,
            // host key mismatch, etc.). High importance so the user is not left
            // wondering why their session never opened.
            // Importance HIGH: these are actionable failures that need attention.
            val errorChannel = NotificationChannel(
                CHANNEL_ERROR,
                "Connection Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SSH connection failures and authentication errors"
                setShowBadge(true)
                enableVibration(true)
            }

            // ── Active Sessions ───────────────────────────────────────────────────
            // Persistent per-host status row ("Connected to host:port-ssh") for
            // every active terminal session. Always present while a session is live;
            // auto-cleared 30 s after disconnect. Never beeps or vibrates — the user
            // controls the foreground service through this notification, not the other
            // way around. Monitoring-only connections (dashboard / HostDetail) are
            // intentionally excluded and will never appear here.
            // Importance LOW: status rows must never interrupt the user.
            val sshSilent = NotificationChannel(
                CHANNEL_SSH_SILENT,
                "Active Sessions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing status for each open SSH terminal session"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // ── Session Alerts ────────────────────────────────────────────────────
            // One-shot audible/vibrating alert for terminal session events (connect,
            // disconnect, unexpected drop). Only fires when the per-connection
            // notif_sound_mode / notif_vibrate_mode setting allows it — defaults to
            // NEVER so most users won't hear anything from this channel.
            // Auto-cleared after 30 s so it doesn't accumulate in the shade.
            // Importance HIGH: the user explicitly opted in, so the alert must be
            // able to beep and vibrate.
            val sshAlerts = NotificationChannel(
                CHANNEL_SSH_ALERTS,
                "Session Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sound or vibration alerts for SSH session connect/disconnect events"
                setShowBadge(true)
                enableVibration(true)
            }

            // ── Host Monitoring Alerts ────────────────────────────────────────────
            // Down and recovery alerts for hosts configured in the Multi-host
            // Dashboard. Fires when: a host fails its TCP probe for the first time
            // (notifyHostDown), repeatedly fails after the cooldown window
            // (notifyHostStillDown), or recovers (notifyHostRecovered).
            // Only fires when MonitorSlot.alertOnDown / alertOnRecovery is true.
            // Importance HIGH: an outage alert should surface immediately.
            val hostMonitoring = NotificationChannel(
                CHANNEL_HOST_MONITORING,
                "Host Monitoring Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a monitored host goes down or comes back online"
                setShowBadge(true)
                enableVibration(true)
            }

            // ── Performance Alerts ────────────────────────────────────────────────
            // Threshold-breach alerts for CPU, memory, disk, and load average.
            // Fires only when MonitorSlot.enablePerformanceChecks is true AND a live
            // SSH session is already open (no new connections from background).
            // Quieter than the down/recovery channel — sustained high resource usage
            // is worth knowing about but is not the same urgency as a full outage.
            // Importance DEFAULT: shows in the shade but does not interrupt.
            val hostMetrics = NotificationChannel(
                CHANNEL_HOST_METRICS,
                "Performance Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when CPU, memory, disk, or load exceed configured thresholds"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            }

            // Remove the legacy ssh_connection_v2 channel that was registered in an
            // earlier release but never posted to. Its role is covered by
            // ssh_silent_v3 (status) and ssh_alerts_v3 (events). Deleting it removes
            // the dead entry from Android Settings → App notifications.
            notificationManager.deleteNotificationChannel("ssh_connection_v2")

            notificationManager.createNotificationChannels(listOf(
                serviceChannel,
                fileTransferChannel,
                errorChannel,
                sshSilent,
                sshAlerts,
                hostMonitoring,
                hostMetrics
            ))

            Logger.d("NotificationHelper", "Registered 7 notification channels: Session Service, Active Sessions, Session Alerts, File Transfers, Connection Errors, Host Monitoring Alerts, Performance Alerts")
        }
    }

    /**
     * Build the persistent per-host status notification for a profile.
     * Always lives on [CHANNEL_SSH_SILENT]. Caller is responsible for
     * posting (so the SSHConnectionService can use the same Notification
     * object as the foreground anchor via startForeground).
     *
     * All SSH session notifications carry [GROUP_SSH_SESSIONS] so Android
     * can collapse them under the group summary notification.
     *
     * Schema (matches user spec):
     *   - state CONNECTED, title present:  "Connected to {host}:{title}"
     *   - state CONNECTED, no title:       "Connected to {host}:{port}-{ssh|mosh}"
     *   - state CONNECTING:                "Connecting to {host}:{port}…"
     *   - state ERROR:                     "Connection error: {host}:{port}"
     *   - state DISCONNECTED:              "Disconnected from {host}"
     *
     * CONNECTED notifications also include a "Disconnect" action that
     * launches [ConfirmDisconnectActivity] for a user-confirmed close.
     */
    fun buildHostStatusNotification(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        state: io.github.tabssh.ssh.connection.ConnectionState,
        terminalTitle: String?,
        cleanExit: Boolean = true
    ): Notification {
        // Determine the connection type label for the port suffix.
        // Mosh takes precedence over the protocol column (a mosh connection
        // is still SSH underneath but presents as "mosh" to the user).
        val protocol = when {
            profile.moshMode == "on" -> "mosh"
            profile.protocol.equals("telnet", ignoreCase = true) -> "telnet"
            else -> "ssh"
        }
        val title = terminalTitle?.takeIf { it.isNotBlank() }
        // Use the user-facing connection name so the notification reads
        // "Connected to prod server" instead of "Connected to 1.2.3.4:22".
        // getDisplayName() returns `name` if set, otherwise username@host:port.
        val displayName = profile.getDisplayName()
        val (contentTitle, contentText) = when (state) {
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTED ->
                // Show the shell title (OSC 0/2) when available so the user
                // sees e.g. "prod server — bash" after a title-setting shell.
                displayName to (if (title != null) "$title ($protocol)" else protocol)
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTING ->
                displayName to "Connecting…"
            io.github.tabssh.ssh.connection.ConnectionState.ERROR ->
                displayName to "Connection error"
            io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED ->
                displayName to (if (cleanExit) "Disconnected" else "Disconnected (error)")
            else ->
                displayName to protocol
        }

        val tapTarget = Intent(context, io.github.tabssh.ui.activities.TabTerminalActivity::class.java)
            .apply {
                putExtra(io.github.tabssh.ui.activities.TabTerminalActivity.EXTRA_CONNECTION_PROFILE_ID, profile.id)
                putExtra(io.github.tabssh.ui.activities.TabTerminalActivity.EXTRA_AUTO_CONNECT, false)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            profile.id.hashCode(),
            tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = when (state) {
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTED -> R.drawable.ic_connected
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTING -> R.drawable.ic_notification
            io.github.tabssh.ssh.connection.ConnectionState.ERROR -> R.drawable.ic_error
            io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED -> R.drawable.ic_disconnect
            else -> R.drawable.ic_notification
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_SSH_SILENT)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(icon)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            // All SSH session notifications share one Android notification group
            // so they collapse together under the group summary.
            .setGroup(GROUP_SSH_SESSIONS)
            .setOngoing(state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED ||
                        state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTING)

        // "Disconnect" action — only shown for live CONNECTED sessions.
        // Launches ConfirmDisconnectActivity (transparent dialog) so the
        // user gets an explicit confirmation before the connection is torn down.
        if (state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED) {
            val disconnectPi = buildDisconnectPendingIntent(context, profile.id)
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_disconnect,
                    "Disconnect",
                    disconnectPi
                ).build()
            )
        }

        // For DISCONNECTED, auto-clear after 30s so the user doesn't have
        // to swipe a stale "Disconnected from …" row.
        if (state == io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED) {
            builder.setTimeoutAfter(30_000L)
            builder.setAutoCancel(true)
        }

        // Safety-net for CONNECTED / CONNECTING: if the service is killed
        // by the OOM killer without running onDestroy(), these ongoing
        // notifications would otherwise linger in the shade forever.
        // The SSHConnectionService heartbeat (every 30s) resets this clock
        // via nm.notify(), so the timeout only fires if the service is
        // actually gone. 20 minutes is ~40 missed heartbeats — ample margin
        // while still not forcing users to manually dismiss stale entries.
        if (state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED ||
            state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTING) {
            builder.setTimeoutAfter(20 * 60 * 1000L)
        }

        return builder.build()
    }

    /**
     * Build the PendingIntent for the "Disconnect" notification action.
     * Launches [ConfirmDisconnectActivity] — a transparent dialog — so the
     * user confirms before the SSH connection is torn down.
     */
    private fun buildDisconnectPendingIntent(context: Context, profileId: String): PendingIntent {
        val intent = Intent(context, io.github.tabssh.ui.activities.ConfirmDisconnectActivity::class.java).apply {
            putExtra(io.github.tabssh.ui.activities.ConfirmDisconnectActivity.EXTRA_PROFILE_ID, profileId)
            // FLAG_ACTIVITY_NEW_TASK required when starting an Activity from a
            // non-Activity context (notification action fires from the OS).
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Use a request code distinct from the tap PendingIntent so Android
        // does not accidentally reuse the wrong cached intent.
        return PendingIntent.getActivity(
            context,
            profileId.hashCode() xor 0x1000,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Post the per-host status notification. Safe to call repeatedly
     * for the same profile — Android coalesces updates by id.
     */
    fun showHostStatus(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        state: io.github.tabssh.ssh.connection.ConnectionState,
        terminalTitle: String?,
        cleanExit: Boolean = true
    ): Notification {
        val notification = buildHostStatusNotification(context, profile, state, terminalTitle, cleanExit)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(perHostNotificationId(profile.id), notification)
        return notification
    }

    /**
     * Cancel the per-host status notification (no animation, no
     * disconnect-message — just gone). Use for explicit cleanup like
     * when a profile is deleted while connected. Normal disconnect
     * goes through showHostStatus(DISCONNECTED) which auto-clears.
     */
    fun cancelHostNotification(context: Context, profileId: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(perHostNotificationId(profileId))
    }

    /**
     * Post (or update) the SSH session group summary notification.
     *
     * Android collapses all [GROUP_SSH_SESSIONS] child notifications under
     * this summary in the notification shade once there are multiple active
     * sessions. The summary itself shows a count and taps into MainActivity.
     *
     * Called by [SSHConnectionService] after every per-host notification
     * render so the count stays accurate. When [connectedCount] is 0 the
     * summary is cancelled instead of posted.
     */
    fun postSshGroupSummary(context: Context, connectedCount: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (connectedCount <= 0) {
            nm.cancel(NOTIFICATION_ID_SSH_GROUP)
            return
        }
        val sessionWord = if (connectedCount == 1) "session" else "sessions"
        val tapIntent = Intent(context, io.github.tabssh.ui.activities.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = NotificationCompat.Builder(context, CHANNEL_SSH_SILENT)
            .setContentTitle("TabSSH")
            .setContentText("$connectedCount active SSH $sessionWord")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setGroup(GROUP_SSH_SESSIONS)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setTimeoutAfter(20 * 60 * 1000L)  // same safety-net as child notifications
            .build()
        nm.notify(NOTIFICATION_ID_SSH_GROUP, summary)
    }

    /**
     * Per-profile alert mode. Mirrors the int values stored on
     * `ConnectionProfile.notifSoundMode` / `notifVibrateMode`.
     */
    enum class AlertMode { NEVER, ALWAYS, ON_ERROR;
        companion object {
            fun fromInt(v: Int): AlertMode = when (v) {
                1 -> ALWAYS
                2 -> ON_ERROR
                else -> NEVER
            }
        }
    }

    /**
     * Optionally fire an audible/vibrating alert for a host event. Stays
     * out of the user's way: posts a *separate* one-shot on the alerts
     * channel, with a short timeout so it auto-clears. The persistent
     * status notification on the silent channel is unaffected.
     *
     * `isError` controls the ON_ERROR gating — true for non-zero exit
     * status, network drop, auth failure; false for clean exit / connect.
     */
    fun maybeAlertForHost(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        message: String,
        isError: Boolean
    ) {
        val soundMode = AlertMode.fromInt(profile.notifSoundMode)
        val vibMode = AlertMode.fromInt(profile.notifVibrateMode)
        val soundOn = when (soundMode) {
            AlertMode.NEVER -> false
            AlertMode.ALWAYS -> true
            AlertMode.ON_ERROR -> isError
        }
        val vibOn = when (vibMode) {
            AlertMode.NEVER -> false
            AlertMode.ALWAYS -> true
            AlertMode.ON_ERROR -> isError
        }
        if (!soundOn && !vibOn) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapTarget = Intent(context, io.github.tabssh.ui.activities.TabTerminalActivity::class.java)
            .apply {
                putExtra(io.github.tabssh.ui.activities.TabTerminalActivity.EXTRA_CONNECTION_PROFILE_ID, profile.id)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        val pendingIntent = PendingIntent.getActivity(
            context,
            profile.id.hashCode() xor 0x5A,  // distinct request code from the silent variant
            tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_SSH_ALERTS)
            .setContentTitle(profile.getDisplayName())
            .setContentText(message)
            .setSmallIcon(if (isError) R.drawable.ic_error else R.drawable.ic_connected)
            .setContentIntent(pendingIntent)
            .setCategory(if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000L)

        // The Android channel-importance model means sound + vibration
        // come from the channel by default. We respect the user's per-
        // profile overrides by explicitly suppressing whichever wasn't
        // requested. (Channel is HIGH so both default to ON; we mute the
        // unwanted one to honour the per-profile pref.)
        if (!soundOn) builder.setSound(null)
        if (!vibOn) builder.setVibrate(longArrayOf(0))

        // Use a derived id so the alert doesn't collide with the silent
        // status notification's id.
        val alertId = perHostNotificationId(profile.id) xor 0x40000
        nm.notify(alertId, builder.build())
    }
    
    /**
     * Show connection error notification
     */
    fun showConnectionError(context: Context, serverName: String, errorMessage: String) {
        // Check if error notifications are enabled
        val prefManager = PreferenceManager(context)
        if (!prefManager.showErrorNotifications()) {
            Logger.d("NotificationHelper", "Error notifications disabled, skipping")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ERROR)
            .setContentTitle("Failed to connect to $serverName")
            .setContentText(errorMessage)
            .setSmallIcon(R.drawable.ic_error)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setStyle(NotificationCompat.BigTextStyle().bigText(errorMessage))
            // Auto-clear after 5 minutes — connection errors are actionable
            // in the moment but become noise once the session is retried or
            // abandoned. AutoCancel only fires on tap; the timeout handles
            // the case where the user never opens the notification.
            .setTimeoutAfter(5 * 60 * 1000L)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ERROR, notification)
        Logger.d("NotificationHelper", "Showed connection error notification")
    }
    
    /**
     * Show file transfer progress notification
     */
    fun showFileTransferProgress(
        context: Context,
        notificationId: Int,
        fileName: String,
        bytesTransferred: Long,
        totalBytes: Long,
        isUpload: Boolean = true
    ): Notification? {
        // Check if file transfer notifications are enabled
        val prefManager = PreferenceManager(context)
        if (!prefManager.showFileTransferNotifications()) {
            Logger.d("NotificationHelper", "File transfer notifications disabled, skipping")
            return null
        }

        val action = if (isUpload) "Uploading" else "Downloading"
        val progress = if (totalBytes > 0) {
            ((bytesTransferred * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else 0

        val notification = NotificationCompat.Builder(context, CHANNEL_FILE_TRANSFER)
            .setContentTitle("$action $fileName")
            .setContentText("$progress% complete (${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)})")
            .setSmallIcon(if (isUpload) R.drawable.ic_upload else R.drawable.ic_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setAutoCancel(false)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)

        return notification
    }
    
    /**
     * Format bytes to human-readable string
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
    
    /**
     * Show file transfer complete notification
     */
    fun showFileTransferComplete(
        context: Context,
        notificationId: Int,
        fileName: String,
        isUpload: Boolean = true,
        success: Boolean = true
    ) {
        // Check if file transfer notifications are enabled
        val prefManager = PreferenceManager(context)
        if (!prefManager.showFileTransferNotifications()) {
            Logger.d("NotificationHelper", "File transfer notifications disabled, skipping")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val action = if (isUpload) "Upload" else "Download"
        val title = if (success) {
            "$action complete"
        } else {
            "$action failed"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_FILE_TRANSFER)
            .setContentTitle(title)
            .setContentText(fileName)
            .setSmallIcon(if (success) R.drawable.ic_file else R.drawable.ic_error)
            .setAutoCancel(true)
            .setPriority(if (success) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .build()

        notificationManager.notify(notificationId, notification)
        Logger.d("NotificationHelper", "Showed file transfer complete notification: $fileName")
    }
    
    /**
     * Cancel a specific notification
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
    
    /**
     * Cancel all notifications
     */
    fun cancelAllNotifications(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    // ── Host monitoring notifications ─────────────────────────────────────────

    /**
     * Post a "host is down" alert for a monitored connection.
     * Opens [MultiHostDashboardActivity] on tap.
     */
    fun notifyHostDown(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = dashboardPendingIntent(context)
        val n = NotificationCompat.Builder(context, CHANNEL_HOST_MONITORING)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle("Host unreachable: ${profile.getDisplayName()}")
            .setContentText("${profile.host}:${profile.port} is not responding")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setGroup(GROUP_MONITORING)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "down"), n)
        postMonitoringGroupSummary(context, nm)
    }

    /**
     * Post a "host recovered" alert for a monitored connection.
     */
    fun notifyHostRecovered(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel any lingering "down" notification first.
        nm.cancel(monitoringNotificationId(profile.id, "down"))
        val pi = dashboardPendingIntent(context)
        val n = NotificationCompat.Builder(context, CHANNEL_HOST_MONITORING)
            .setSmallIcon(R.drawable.ic_connected)
            .setContentTitle("Host recovered: ${profile.getDisplayName()}")
            .setContentText("${profile.host}:${profile.port} is back online")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_MONITORING)
            .setTimeoutAfter(120_000L)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "up"), n)
        postMonitoringGroupSummary(context, nm)
    }

    /**
     * Post a "still down" repeat alert respecting the configured cooldown.
     * Only called by [HostAvailabilityWorker] after cooldown has elapsed.
     */
    fun notifyHostStillDown(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        consecutiveFailures: Int
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = dashboardPendingIntent(context)
        val n = NotificationCompat.Builder(context, CHANNEL_HOST_MONITORING)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle("Host still unreachable: ${profile.getDisplayName()}")
            .setContentText("${profile.host}:${profile.port} — $consecutiveFailures consecutive failures")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setGroup(GROUP_MONITORING)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "down"), n)
        postMonitoringGroupSummary(context, nm)
    }

    /**
     * Post a metric threshold breach alert (CPU/memory/disk/load).
     * Uses the quieter [CHANNEL_HOST_METRICS] channel to avoid alarm fatigue.
     */
    fun notifyMetricThreshold(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        metric: String,
        currentValue: String,
        threshold: String
    ) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = dashboardPendingIntent(context)
        val n = NotificationCompat.Builder(context, CHANNEL_HOST_METRICS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${profile.getDisplayName()}: $metric threshold exceeded")
            .setContentText("$metric is $currentValue (threshold: $threshold)")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setGroup(GROUP_MONITORING)
            .setTimeoutAfter(300_000L)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "metric_$metric"), n)
        postMonitoringGroupSummary(context, nm)
    }

    /** Stable notification ID for a given profile + alert type pair. */
    private fun monitoringNotificationId(profileId: String, type: String): Int {
        val base = 200_000
        return base + (((profileId + type).hashCode().toLong() and 0x7FFFFFFFL) % 90_000).toInt()
    }

    /**
     * Post (or refresh) the monitoring notification group summary.
     *
     * All monitoring alerts ([notifyHostDown], [notifyHostStillDown],
     * [notifyHostRecovered], [notifyMetricThreshold]) carry [GROUP_MONITORING]
     * and call this after posting their child. Android automatically dismisses
     * the summary when all children are dismissed — we never need to cancel it
     * manually; it is always posted alongside a new child.
     */
    private fun postMonitoringGroupSummary(context: Context, nm: NotificationManager) {
        val pi = dashboardPendingIntent(context)
        val summary = NotificationCompat.Builder(context, CHANNEL_HOST_MONITORING)
            .setContentTitle("TabSSH Monitoring")
            .setContentText("Host monitoring alerts")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setGroup(GROUP_MONITORING)
            .setGroupSummary(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID_MONITORING_GROUP, summary)
    }

    private fun dashboardPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, io.github.tabssh.ui.activities.MultiHostDashboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

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
 * Manages all app notifications with proper channels for Android 8+
 * 
 * Notification Types:
 * - Connection status (connect/disconnect/error)
 * - File transfer progress (SFTP uploads/downloads)
 * - Error notifications (connection failures, auth errors)
 * - Background service (persistent SSH connections)
 */
object NotificationHelper {
    
    // Notification IDs
    const val NOTIFICATION_ID_SERVICE = 1001
const val NOTIFICATION_ID_FILE_TRANSFER = 3001
    const val NOTIFICATION_ID_ERROR = 4001

    // Per-host notifications occupy a dedicated id range. The id is
    // derived from `profile.id.hashCode()` so it's stable across the
    // app's lifetime for any given profile (and so update/cancel can
    // target the right notification without a separate lookup table).
    // Keeps the value positive and outside the constant ids above.
    fun perHostNotificationId(profileId: String): Int {
        return 10_000 + ((profileId.hashCode().toLong() and 0x7FFFFFFFL) % 90_000).toInt()
    }

    // Notification Channels — bumped to _v3 for the per-host work so
    // any cached channel config from earlier installs gets replaced.
    // Android caches first-create channel config and silently ignores
    // updates to a live channel.
    private const val CHANNEL_SERVICE = "ssh_service_v2"
    private const val CHANNEL_CONNECTION = "ssh_connection_v2"
    private const val CHANNEL_FILE_TRANSFER = "file_transfer_v2"
    private const val CHANNEL_ERROR = "errors_v2"

    // Per-host status (persistent, silent). Default channel for the
    // ongoing per-session notification — never beeps or vibrates.
    private const val CHANNEL_SSH_SILENT = "ssh_silent_v3"

    // Per-host alert (one-shot, audible). Used only when the connection
    // profile's notif_sound_mode / notif_vibrate_mode allow it. The
    // posted notification carries setTimeoutAfter so the OS auto-clears
    // it after a brief window.
    private const val CHANNEL_SSH_ALERTS = "ssh_alerts_v3"

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
            
            // Service Channel - Low priority, no sound
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "SSH Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent SSH connections running in background"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            // Connection Channel — silent. Routine connect / disconnect
            // updates shouldn't beep or vibrate. Errors live on the ERROR
            // channel which keeps sound + vibration.
            val connectionChannel = NotificationChannel(
                CHANNEL_CONNECTION,
                "Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SSH connection status updates"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            // File Transfer Channel - High priority, progress updates
            val fileTransferChannel = NotificationChannel(
                CHANNEL_FILE_TRANSFER,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SFTP file upload/download progress"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            // Error Channel - High priority, sound + vibration
            val errorChannel = NotificationChannel(
                CHANNEL_ERROR,
                "Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Connection errors and failures"
                setShowBadge(true)
                enableVibration(true)
            }
            
            // Per-host silent channel — the persistent live status for
            // every active SSH session (`Connected to host:title`, etc.).
            // Importance LOW so the OS doesn't surface it as a heads-up
            // banner; sound and vibration explicitly off.
            val sshSilent = NotificationChannel(
                CHANNEL_SSH_SILENT,
                "SSH Sessions (silent)",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Per-host SSH session status (silent)"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            // Per-host alert channel — only used when a connection's
            // notif_sound_mode / notif_vibrate_mode allow it. Lives at
            // IMPORTANCE_HIGH so the posted alert can actually beep /
            // vibrate; real attentional cost is bounded by setTimeoutAfter.
            val sshAlerts = NotificationChannel(
                CHANNEL_SSH_ALERTS,
                "SSH Sessions (alerts)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "SSH session events that should sound or vibrate"
                setShowBadge(true)
                enableVibration(true)
            }

            // Host monitoring — down/recovery alerts. HIGH so they surface
            // as heads-up banners; sound + vibration on by default.
            val hostMonitoring = NotificationChannel(
                CHANNEL_HOST_MONITORING,
                "Host Monitoring",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a monitored host goes down or recovers"
                setShowBadge(true)
                enableVibration(true)
            }

            // Metric threshold — CPU/memory/disk over threshold. DEFAULT so
            // it doesn't intrude as aggressively as a full outage alert.
            val hostMetrics = NotificationChannel(
                CHANNEL_HOST_METRICS,
                "Host Metrics",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when CPU, memory, or disk exceed configured thresholds"
                setShowBadge(true)
                enableVibration(false)
                setSound(null, null)
            }

            notificationManager.createNotificationChannels(listOf(
                serviceChannel,
                connectionChannel,
                fileTransferChannel,
                errorChannel,
                sshSilent,
                sshAlerts,
                hostMonitoring,
                hostMetrics
            ))

            Logger.d("NotificationHelper", "Created 8 notification channels")
        }
    }

    /**
     * Build the persistent per-host status notification for a profile.
     * Always lives on [CHANNEL_SSH_SILENT]. Caller is responsible for
     * posting (so the SSHConnectionService can use the same Notification
     * object as the foreground anchor via startForeground).
     *
     * Schema (matches user spec):
     *   - state CONNECTED, title present:  "Connected to {host}:{title}"
     *   - state CONNECTED, no title:       "Connected to {host}:{port}-{ssh|mosh}"
     *   - state CONNECTING:                "Connecting to {host}:{port}…"
     *   - state ERROR:                     "Connection error: {host}:{port}"
     *   - state DISCONNECTED:              "Disconnected from {host}"
     */
    fun buildHostStatusNotification(
        context: Context,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        state: io.github.tabssh.ssh.connection.ConnectionState,
        terminalTitle: String?,
        cleanExit: Boolean = true
    ): Notification {
        val protocol = if (profile.useMosh) "mosh" else "ssh"
        val title = terminalTitle?.takeIf { it.isNotBlank() }
        val (contentTitle, contentText) = when (state) {
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTED ->
                "TabSSH" to (
                    if (title != null) "Connected to ${profile.host}:$title"
                    else                "Connected to ${profile.host}:${profile.port}-$protocol"
                )
            io.github.tabssh.ssh.connection.ConnectionState.CONNECTING ->
                "TabSSH" to "Connecting to ${profile.host}:${profile.port}…"
            io.github.tabssh.ssh.connection.ConnectionState.ERROR ->
                "TabSSH" to "Connection error: ${profile.host}:${profile.port}"
            io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED ->
                "TabSSH" to (
                    if (cleanExit) "Disconnected from ${profile.host}"
                    else            "Disconnected from ${profile.host} (error)"
                )
            else ->
                "TabSSH" to "${profile.host}:${profile.port}"
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
            .setOngoing(state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTED ||
                        state == io.github.tabssh.ssh.connection.ConnectionState.CONNECTING)

        // For DISCONNECTED, auto-clear after 30s so the user doesn't have
        // to swipe a stale "Disconnected from …" row.
        if (state == io.github.tabssh.ssh.connection.ConnectionState.DISCONNECTED) {
            builder.setTimeoutAfter(30_000L)
            builder.setAutoCancel(true)
        }

        return builder.build()
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
            .build()
        nm.notify(monitoringNotificationId(profile.id, "down"), n)
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
            .setTimeoutAfter(120_000L)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "up"), n)
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
            .build()
        nm.notify(monitoringNotificationId(profile.id, "down"), n)
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
            .setTimeoutAfter(300_000L)
            .build()
        nm.notify(monitoringNotificationId(profile.id, "metric_$metric"), n)
    }

    /** Stable notification ID for a given profile + alert type pair. */
    private fun monitoringNotificationId(profileId: String, type: String): Int {
        val base = 200_000
        return base + ((profileId + type).hashCode().toLong() and 0x7FFFFFFFL % 90_000).toInt()
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

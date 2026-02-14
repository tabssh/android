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
    const val NOTIFICATION_ID_CONNECTION = 2001
    const val NOTIFICATION_ID_FILE_TRANSFER = 3001
    const val NOTIFICATION_ID_ERROR = 4001
    
    // Notification Channels
    private const val CHANNEL_SERVICE = "ssh_service"
    private const val CHANNEL_CONNECTION = "ssh_connection"
    private const val CHANNEL_FILE_TRANSFER = "file_transfer"
    private const val CHANNEL_ERROR = "errors"
    
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
            
            // Connection Channel - Default priority
            val connectionChannel = NotificationChannel(
                CHANNEL_CONNECTION,
                "Connection Status",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "SSH connection status updates"
                setShowBadge(true)
                enableVibration(true)
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
            
            notificationManager.createNotificationChannels(listOf(
                serviceChannel,
                connectionChannel,
                fileTransferChannel,
                errorChannel
            ))
            
            Logger.d("NotificationHelper", "Created 4 notification channels")
        }
    }
    
    /**
     * Show connection success notification
     */
    fun showConnectionSuccess(context: Context, serverName: String, username: String) {
        // Check if connection notifications are enabled
        val prefManager = PreferenceManager(context)
        if (!prefManager.showConnectionNotifications()) {
            Logger.d("NotificationHelper", "Connection notifications disabled, skipping")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setContentTitle("Connected to $serverName")
            .setContentText("Logged in as $username")
            .setSmallIcon(R.drawable.ic_connected)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_CONNECTION, notification)
        Logger.d("NotificationHelper", "Showed connection success notification")
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
     * Show disconnection notification
     */
    fun showDisconnected(context: Context, serverName: String, reason: String? = null) {
        // Check if connection notifications are enabled
        val prefManager = PreferenceManager(context)
        if (!prefManager.showConnectionNotifications()) {
            Logger.d("NotificationHelper", "Connection notifications disabled, skipping")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (reason != null) {
            "Disconnected: $reason"
        } else {
            "Disconnected"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setContentTitle("Disconnected from $serverName")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_disconnect)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        notificationManager.notify(NOTIFICATION_ID_CONNECTION, notification)
        Logger.d("NotificationHelper", "Showed disconnection notification")
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
}

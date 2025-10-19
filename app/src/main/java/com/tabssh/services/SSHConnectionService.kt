package com.tabssh.services

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
import androidx.core.app.NotificationCompat
import com.tabssh.R
import com.tabssh.TabSSHApplication
import com.tabssh.ssh.connection.SessionManagerListener
import com.tabssh.ssh.connection.ConnectionState
import com.tabssh.ui.activities.MainActivity
import com.tabssh.utils.logging.Logger
import kotlinx.coroutines.*

/**
 * Background service to maintain SSH connections
 */
class SSHConnectionService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var app: TabSSHApplication
    
    private var activeConnections = 0
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ssh_connections"
        private const val CHANNEL_NAME = "SSH Connections"
        
        const val ACTION_START_SERVICE = "com.tabssh.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.tabssh.STOP_SERVICE"
        
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
        
        serviceScope.cancel()
        app.sshSessionManager.cleanup()
    }
    
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        Logger.i("SSHConnectionService", "Started foreground service")
        
        // Start connection monitoring
        serviceScope.launch {
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
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Action buttons for notification
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SSHConnectionService::class.java).apply { action = ACTION_STOP_SERVICE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val contentText = when (activeConnections) {
            0 -> "Ready for SSH connections"
            1 -> "1 active SSH connection"
            else -> "$activeConnections active SSH connections"
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TabSSH")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // CRITICAL: Keeps service running in background
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setAutoCancel(false) // Prevent accidental dismissal
        
        // Add action button to disconnect all
        if (activeConnections > 0) {
            builder.addAction(
                R.drawable.ic_disconnect,
                "Disconnect All",
                disconnectIntent
            )
        }
        
        // Add connection status details
        if (activeConnections > 0) {
            val bigTextStyle = NotificationCompat.BigTextStyle()
                .setBigContentTitle("TabSSH - $activeConnections Active")
                .bigText("SSH connections running in background.\n\nTap to open terminal interface.")
            builder.setStyle(bigTextStyle)
        }
        
        return builder.build()
    }
    
    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun setupSessionManagerListener() {
        app.sshSessionManager.addListener(object : SessionManagerListener {
            override fun onConnectionEstablished(profileId: String) {
                updateConnectionCount()
            }
            
            override fun onConnectionClosed(profileId: String) {
                updateConnectionCount()
            }
            
            override fun onConnectionStateChanged(profileId: String, state: ConnectionState) {
                updateConnectionCount()
            }
            
            override fun onAllConnectionsClosed() {
                updateConnectionCount()
                // Consider stopping service if no connections remain
                if (activeConnections == 0) {
                    serviceScope.launch {
                        delay(30000) // Wait 30 seconds before stopping
                        if (activeConnections == 0) {
                            stopSelf()
                        }
                    }
                }
            }
        })
    }
    
    private fun updateConnectionCount() {
        val stats = app.sshSessionManager.getConnectionStatistics()
        activeConnections = stats.connectedConnections
        
        // Update notification
        serviceScope.launch(Dispatchers.Main) {
            updateNotification()
        }
        
        Logger.d("SSHConnectionService", "Active connections: $activeConnections")
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
                
                // Wait before next check
                delay(30000) // Check every 30 seconds
                
            } catch (e: Exception) {
                Logger.e("SSHConnectionService", "Error in connection monitoring", e)
                delay(60000) // Wait longer on error
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
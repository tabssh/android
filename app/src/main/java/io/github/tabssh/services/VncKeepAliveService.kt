package io.github.tabssh.services

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.tabssh.R
import io.github.tabssh.hypervisor.vnc.VncBackgroundSessionStore
import io.github.tabssh.ui.activities.MainActivity
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.logging.Logger

/**
 * Foreground service that keeps the process alive while one or more VNC
 * sessions are parked in [VncBackgroundSessionStore].
 *
 * A direct-VNC [io.github.tabssh.hypervisor.console.rfb.RfbClient] is normally
 * owned by VMConsoleActivity and dies with it. When a VncHost opts into
 * `keepAliveInBackground`, the activity pauses the client, hands it to
 * [VncBackgroundSessionStore], and starts this service so the OS keeps the
 * process (and therefore the socket + reader thread) alive across activity
 * destruction, app-switch, screen-off, and Doze.
 *
 * This is deliberately leaner than [SSHConnectionService]: a parked RFB client
 * is paused (no framebuffer-update requests outstanding), so there is nothing
 * to monitor or keep alive at the protocol layer beyond preventing the OS from
 * killing the process and letting the WiFi radio sleep. We hold a single
 * indefinite [PowerManager.PARTIAL_WAKE_LOCK] and a [WifiManager.WifiLock] for
 * the whole time any session is parked, mirroring the SSH service's lock names.
 */
class VncKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        private const val TAG = "VncKeepAliveService"

        // Distinct from NOTIFICATION_ID_SERVICE (1001, SSH FG anchor) and
        // NOTIFICATION_ID_NO_NETWORK (1002). Reserved low-range id for the
        // VNC keep-alive foreground anchor.
        private const val NOTIFICATION_ID = 1003

        const val ACTION_START_SERVICE = "io.github.tabssh.VNC_START_SERVICE"
        const val ACTION_STOP_SERVICE = "io.github.tabssh.VNC_STOP_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, VncKeepAliveService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VncKeepAliveService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Nothing parked → no reason to run. Can happen if the last session
        // was reclaimed before this start request was processed.
        if (VncBackgroundSessionStore.isEmpty) {
            Logger.d(TAG, "No parked VNC sessions — stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        acquireWifiLock()
        Logger.i(TAG, "VNC keep-alive foreground service started (${VncBackgroundSessionStore.count} session(s))")

        // NOT_STICKY: if the OS kills the process the parked sessions die with
        // it — there is nothing to restore. The activity restarts the service
        // on its next background/park.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(TAG, "Service destroyed")
        releaseWakeLock()
        releaseWifiLock()
    }

    private fun buildNotification(): Notification {
        val tapTarget = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapTarget,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val count = VncBackgroundSessionStore.count
        val text = if (count == 1) {
            "Holding 1 VNC session open in the background"
        } else {
            "Holding $count VNC sessions open in the background"
        }
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_SERVICE)
            .setContentTitle("TabSSH")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TabSSH:VncSession")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            Logger.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire wake lock", e)
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
            val wl = wm.createWifiLock(mode, "TabSSH:VncWifi")
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
            Logger.i(TAG, "WiFi lock acquired")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to acquire WiFi lock", e)
        }
    }

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
}

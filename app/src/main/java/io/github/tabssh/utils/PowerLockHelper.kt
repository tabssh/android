package io.github.tabssh.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import io.github.tabssh.utils.logging.Logger

/**
 * Shared WakeLock/WifiLock acquire-release lifecycle for foreground services
 * that need to keep the CPU and WiFi radio awake while a session is active.
 * [io.github.tabssh.services.SSHConnectionService] and
 * [io.github.tabssh.services.VncKeepAliveService] previously duplicated this
 * logic with identical semantics — same lock levels/flags, same WiFi lock
 * mode selection, same idempotent acquire/release discipline — differing
 * only in the lock tag names and the log tag used for diagnostics (AI.md
 * PART 7 § Reuse Before Creating).
 */
class PowerLockHelper(
    private val context: Context,
    private val logTag: String,
    private val wakeLockTag: String,
    private val wifiLockTag: String,
    // Distinct tag for acquireTimedWakeLock() — defaults to [wakeLockTag],
    // but SSHConnectionService historically used a separate name
    // ("TabSSH:SshKeepAlive") to distinguish indefinite vs. timed holds in
    // dumpsys/battery-historian output; preserved verbatim for that caller.
    private val timedWakeLockTag: String = wakeLockTag
) {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Acquire an indefinite PARTIAL_WAKE_LOCK. Idempotent: no-op if already held.
     */
    fun acquireWakeLockIndefinite() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
            Logger.i(logTag, "Wake lock acquired (indefinite)")
        } catch (e: Exception) {
            Logger.w(logTag, "Failed to acquire wake lock", e)
        }
    }

    /**
     * Acquire a timed PARTIAL_WAKE_LOCK that auto-releases after [timeoutMs].
     * Replaces any existing held lock so the timeout is always [timeoutMs] from now.
     */
    fun acquireTimedWakeLock(timeoutMs: Long) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, timedWakeLockTag)
            wl.setReferenceCounted(false)
            wl.acquire(timeoutMs)
            wakeLock = wl
            Logger.d(logTag, "Wake lock acquired (timed ${timeoutMs / 1000}s)")
        } catch (e: Exception) {
            Logger.w(logTag, "Failed to acquire timed wake lock", e)
        }
    }

    fun releaseWakeLock() {
        try {
            val wl = wakeLock?.takeIf { it.isHeld } ?: return
            wl.release()
            Logger.i(logTag, "Wake lock released")
        } catch (e: Exception) {
            Logger.w(logTag, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    /**
     * Acquire a WiFi lock to keep the radio out of power-saving mode.
     *
     * Uses [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] on API 29+ — optimal for
     * interactive sessions; degrades to WIFI_MODE_FULL automatically when the
     * screen is off. On older APIs uses [WifiManager.WIFI_MODE_FULL_HIGH_PERF],
     * the canonical VoIP/SSH pattern for keeping the radio fully awake.
     *
     * Idempotent: no-op if already held.
     */
    @Suppress("DEPRECATION")
    fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wl = wm.createWifiLock(mode, wifiLockTag)
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
            Logger.i(logTag, "WiFi lock acquired")
        } catch (e: Exception) {
            Logger.w(logTag, "Failed to acquire WiFi lock", e)
        }
    }

    /**
     * Release the WiFi lock, allowing the radio to enter power-saving mode.
     */
    fun releaseWifiLock() {
        try {
            val wl = wifiLock?.takeIf { it.isHeld } ?: return
            wl.release()
            Logger.i(logTag, "WiFi lock released")
        } catch (e: Exception) {
            Logger.w(logTag, "Failed to release WiFi lock", e)
        } finally {
            wifiLock = null
        }
    }
}

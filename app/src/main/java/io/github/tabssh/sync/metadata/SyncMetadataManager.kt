package io.github.tabssh.sync.metadata

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import io.github.tabssh.BuildConfig
import io.github.tabssh.sync.models.DeviceInfo
import io.github.tabssh.sync.models.SyncItemCounts
import io.github.tabssh.sync.models.SyncMetadata
import io.github.tabssh.utils.logging.Logger
import java.security.MessageDigest
import java.util.UUID

/**
 * Manages sync metadata including device ID, timestamps, and versioning
 */
class SyncMetadataManager(private val context: Context) {

    companion object {
        private const val TAG = "SyncMetadataManager"
        private const val PREFS_NAME = "sync_metadata"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_SYNC_VERSION = "sync_version"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_LAST_SUCCESSFUL_SYNC = "last_successful_sync"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Get or create device ID
     */
    fun getDeviceId(): String {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)

        if (deviceId == null) {
            deviceId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            Logger.d(TAG, "Generated new device ID: $deviceId")
        }

        return deviceId
    }

    /**
     * Get device name
     */
    fun getDeviceName(): String {
        var deviceName = prefs.getString(KEY_DEVICE_NAME, null)

        if (deviceName == null) {
            deviceName = generateDeviceName()
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply()
        }

        return deviceName
    }

    /**
     * Set custom device name
     */
    fun setDeviceName(name: String) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
        Logger.d(TAG, "Device name updated: $name")
    }

    /**
     * Get current sync version
     */
    fun getSyncVersion(): Long {
        return prefs.getLong(KEY_SYNC_VERSION, 0)
    }

    /**
     * Increment and return new sync version
     */
    fun incrementSyncVersion(): Long {
        val newVersion = getSyncVersion() + 1
        prefs.edit().putLong(KEY_SYNC_VERSION, newVersion).apply()
        Logger.d(TAG, "Sync version incremented to: $newVersion")
        return newVersion
    }

    /**
     * Get last sync timestamp
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0)
    }

    /**
     * Update last sync timestamp
     */
    fun updateLastSyncTime(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, timestamp).apply()
    }

    /**
     * Get last successful sync timestamp
     */
    fun getLastSuccessfulSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SUCCESSFUL_SYNC, 0)
    }

    /**
     * Update last successful sync timestamp
     */
    fun updateLastSuccessfulSyncTime(timestamp: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SUCCESSFUL_SYNC, timestamp).apply()
    }

    /**
     * Create sync metadata for current device
     */
    fun createSyncMetadata(itemCounts: SyncItemCounts): SyncMetadata {
        return SyncMetadata(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            deviceModel = getDeviceModel(),
            appVersion = getAppVersion(),
            syncTimestamp = System.currentTimeMillis(),
            syncVersion = incrementSyncVersion(),
            itemCounts = itemCounts
        )
    }

    /**
     * Get device information
     */
    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            deviceId = getDeviceId(),
            deviceName = getDeviceName(),
            deviceModel = getDeviceModel(),
            androidVersion = getAndroidVersion(),
            appVersion = getAppVersion()
        )
    }

    /**
     * Generate unique device ID
     */
    private fun generateDeviceId(): String {
        val uuid = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val deviceInfo = "${Build.MANUFACTURER}-${Build.MODEL}-$timestamp"

        return createHash("$uuid-$deviceInfo").take(32)
    }

    /**
     * Generate default device name
     */
    private fun generateDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL

        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Get device model
     */
    private fun getDeviceModel(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /**
     * Get Android version
     */
    private fun getAndroidVersion(): String {
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    /**
     * Get app version
     */
    private fun getAppVersion(): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Create SHA256 hash of input
     */
    private fun createHash(input: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to create hash", e)
            UUID.randomUUID().toString().replace("-", "")
        }
    }

    /**
     * Reset all sync metadata (for testing or factory reset)
     */
    fun resetMetadata() {
        prefs.edit().clear().apply()
        Logger.d(TAG, "Sync metadata reset")
    }

    /**
     * Check if device has ever synced
     */
    fun hasNeverSynced(): Boolean {
        return getLastSyncTime() == 0L
    }

    /**
     * Get time since last sync in milliseconds
     */
    fun getTimeSinceLastSync(): Long {
        val lastSync = getLastSyncTime()
        return if (lastSync == 0L) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() - lastSync
        }
    }

    /**
     * Check if sync is due based on frequency
     */
    fun isSyncDue(frequencyMinutes: Int): Boolean {
        if (hasNeverSynced()) return true

        val timeSinceSync = getTimeSinceLastSync()
        val frequencyMillis = frequencyMinutes * 60 * 1000L

        return timeSinceSync >= frequencyMillis
    }

    /**
     * Get formatted last sync time description
     */
    fun getLastSyncDescription(): String {
        val lastSync = getLastSyncTime()

        if (lastSync == 0L) {
            return "Never synced"
        }

        val diff = System.currentTimeMillis() - lastSync

        return when {
            diff < 60_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000} minute${if (diff / 60_000 > 1) "s" else ""} ago"
            diff < 86400_000 -> "${diff / 3600_000} hour${if (diff / 3600_000 > 1) "s" else ""} ago"
            diff < 604800_000 -> "${diff / 86400_000} day${if (diff / 86400_000 > 1) "s" else ""} ago"
            else -> "${diff / 604800_000} week${if (diff / 604800_000 > 1) "s" else ""} ago"
        }
    }
}

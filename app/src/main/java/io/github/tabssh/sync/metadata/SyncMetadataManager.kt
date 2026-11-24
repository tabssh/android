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
            Logger.w("SyncMetadataManager", "getAppVersion failed: ${e.javaClass.simpleName}: ${e.message}")
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
}

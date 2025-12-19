package io.github.tabssh.sync

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.gson.Gson
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.sync.executor.WebDAVSyncExecutor
import io.github.tabssh.sync.merge.ConflictResolver
import io.github.tabssh.sync.merge.MergeEngine
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.sync.models.*
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Unified sync manager that supports multiple backends with automatic fallback
 * Supports: Google Drive (default) and WebDAV (for degoogled devices)
 */
class UnifiedSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedSyncManager"
        private const val SYNC_FILE_VERSION = 2

        // Backend types
        const val BACKEND_GOOGLE_DRIVE = "google_drive"
        const val BACKEND_WEBDAV = "webdav"
    }

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    private val gson = Gson()

    private val metadataManager = SyncMetadataManager(context)
    private val encryptor = SyncEncryptor()
    private val dataCollector = SyncDataCollector(context, database, preferenceManager, metadataManager)
    private val dataApplier = SyncDataApplier(context, database, preferenceManager)
    private val mergeEngine = MergeEngine()
    private val conflictResolver = ConflictResolver(context, database)

    // Lazy initialization - only created if needed
    private var googleDriveSyncManager: GoogleDriveSyncManager? = null
    private var webdavExecutor: WebDAVSyncExecutor? = null

    private val _syncStatus = MutableStateFlow(createInitialStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(SyncProgress(SyncStage.IDLE))
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private var syncPassword: String? = null

    /**
     * Check if Google Play Services is available
     */
    fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val resultCode = apiAvailability.isGooglePlayServicesAvailable(context)
            resultCode == ConnectionResult.SUCCESS
        } catch (e: Exception) {
            Logger.w(TAG, "Google Play Services check failed: ${e.message}")
            false
        }
    }

    /**
     * Get selected sync backend
     */
    private fun getSelectedBackend(): String {
        val backend = preferenceManager.getSyncBackend()

        // Automatic fallback for degoogled devices
        if (backend == BACKEND_GOOGLE_DRIVE && !isGooglePlayServicesAvailable()) {
            Logger.i(TAG, "Google Play Services not available, falling back to WebDAV")
            preferenceManager.setSyncBackend(BACKEND_WEBDAV)
            return BACKEND_WEBDAV
        }

        return backend
    }

    /**
     * Initialize sync backend
     */
    private suspend fun initializeBackend(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            when (getSelectedBackend()) {
                BACKEND_GOOGLE_DRIVE -> {
                    if (!isGooglePlayServicesAvailable()) {
                        return@withContext Result.failure(
                            Exception("Google Play Services not available. Please switch to WebDAV backend.")
                        )
                    }

                    if (googleDriveSyncManager == null) {
                        googleDriveSyncManager = GoogleDriveSyncManager(context)
                    }

                    Result.success(Unit)
                }

                BACKEND_WEBDAV -> {
                    if (webdavExecutor == null) {
                        val serverUrl = preferenceManager.getWebDAVServerUrl()
                        val username = preferenceManager.getWebDAVUsername()
                        val password = preferenceManager.getWebDAVPassword()
                        val syncFolder = preferenceManager.getWebDAVSyncFolder()

                        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                            return@withContext Result.failure(
                                Exception("WebDAV configuration incomplete. Please configure server URL, username, and password.")
                            )
                        }

                        webdavExecutor = WebDAVSyncExecutor(
                            context = context,
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            syncFolder = syncFolder
                        )
                    }

                    // Initialize WebDAV connection
                    webdavExecutor!!.initialize()
                }

                else -> Result.failure(Exception("Unknown sync backend: ${getSelectedBackend()}"))
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize sync backend", e)
            Result.failure(e)
        }
    }

    /**
     * Perform complete sync operation
     */
    suspend fun performSync(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncResult =
        withContext(Dispatchers.IO) {
            Logger.d(TAG, "Starting sync: trigger=$trigger, backend=${getSelectedBackend()}")

            if (syncPassword == null) {
                return@withContext SyncResult(
                    success = false,
                    message = "Sync password not configured"
                )
            }

            // Initialize backend
            val initResult = initializeBackend()
            if (initResult.isFailure) {
                return@withContext SyncResult(
                    success = false,
                    message = "Backend initialization failed: ${initResult.exceptionOrNull()?.message}"
                )
            }

            updateProgress(SyncStage.COLLECTING_DATA, 0, 6, "Collecting local data...")

            try {
                when (getSelectedBackend()) {
                    BACKEND_GOOGLE_DRIVE -> performGoogleDriveSync()
                    BACKEND_WEBDAV -> performWebDAVSync()
                    else -> SyncResult(success = false, message = "Unknown backend")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Sync failed", e)

                updateProgress(SyncStage.FAILED, 0, 0, "Sync failed: ${e.message}")

                SyncResult(
                    success = false,
                    message = "Sync failed: ${e.message}",
                    error = e
                )
            }
        }

    /**
     * Perform Google Drive sync
     */
    private suspend fun performGoogleDriveSync(): SyncResult {
        val manager = googleDriveSyncManager
            ?: return SyncResult(success = false, message = "Google Drive not initialized")

        manager.setSyncPassword(syncPassword!!)
        return manager.performSync()
    }

    /**
     * Perform WebDAV sync
     */
    private suspend fun performWebDAVSync(): SyncResult {
        val executor = webdavExecutor
            ?: return SyncResult(success = false, message = "WebDAV not initialized")

        val localData = dataCollector.collectAllSyncData()

        updateProgress(SyncStage.ENCRYPTING, 1, 6, "Encrypting data...")

        val encryptedLocal = encryptSyncData(localData, syncPassword!!)

        updateProgress(SyncStage.UPLOADING, 2, 6, "Uploading to WebDAV server...")

        val uploadResult = executor.uploadSyncFile(metadataManager.getDeviceId(), encryptedLocal)
        if (uploadResult.isFailure) {
            return SyncResult(
                success = false,
                message = "Upload failed: ${uploadResult.exceptionOrNull()?.message}"
            )
        }

        updateProgress(SyncStage.DOWNLOADING, 3, 6, "Downloading from other devices...")

        val syncFiles = executor.listSyncFiles().getOrElse {
            return SyncResult(
                success = false,
                message = "Failed to list sync files: ${it.message}"
            )
        }

        val remoteFiles = mutableMapOf<String, ByteArray>()
        for (syncFile in syncFiles) {
            if (syncFile.deviceId != metadataManager.getDeviceId()) {
                val downloadResult = executor.downloadSyncFile(syncFile.deviceId)
                if (downloadResult.isSuccess) {
                    remoteFiles[syncFile.deviceId] = downloadResult.getOrThrow()
                }
            }
        }

        updateProgress(SyncStage.MERGING, 4, 6, "Merging data...")

        val mergeResult = mergeAllData(localData, remoteFiles)

        if (mergeResult.hasConflicts()) {
            val autoResolved = conflictResolver.autoResolveConflicts(mergeResult.conflicts)

            if (autoResolved.size < mergeResult.conflicts.size) {
                updateProgress(SyncStage.RESOLVING_CONFLICTS, 5, 6,
                    "${mergeResult.conflicts.size - autoResolved.size} conflicts require manual resolution")

                return SyncResult(
                    success = false,
                    message = "Sync conflicts require manual resolution",
                    conflicts = mergeResult.conflicts.filter { !it.autoResolvable }
                )
            }

            conflictResolver.applyResolutions(autoResolved)
        }

        updateProgress(SyncStage.APPLYING_CHANGES, 5, 6, "Applying changes...")

        dataApplier.applyMergeResult(
            mergeResult.connectionResult,
            mergeResult.keyResult,
            mergeResult.themeResult,
            mergeResult.hostKeyResult,
            mergeResult.preferences
        )

        metadataManager.updateLastSuccessfulSyncTime()

        updateProgress(SyncStage.COMPLETED, 6, 6, "Sync completed successfully")

        updateSyncStatus()

        Logger.d(TAG, "WebDAV sync completed successfully")

        return SyncResult(
            success = true,
            message = "Sync completed successfully",
            syncedItemCounts = localData.metadata.itemCounts
        )
    }

    /**
     * Test WebDAV connection
     */
    suspend fun testWebDAVConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val serverUrl = preferenceManager.getWebDAVServerUrl()
            val username = preferenceManager.getWebDAVUsername()
            val password = preferenceManager.getWebDAVPassword()
            val syncFolder = preferenceManager.getWebDAVSyncFolder()

            if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
                return@withContext Result.failure(
                    Exception("Please configure server URL, username, and password first")
                )
            }

            val executor = WebDAVSyncExecutor(
                context = context,
                serverUrl = serverUrl,
                username = username,
                password = password,
                syncFolder = syncFolder
            )

            executor.testConnection()
        } catch (e: Exception) {
            Logger.e(TAG, "WebDAV connection test failed", e)
            Result.failure(e)
        }
    }

    /**
     * Merge data from all sources
     */
    private suspend fun mergeAllData(
        localData: SyncDataPackage,
        remoteFiles: Map<String, ByteArray>
    ): CompleteMergeResult {
        val allConnections = mutableListOf(localData.connections)
        val allKeys = mutableListOf(localData.keys)
        val allThemes = mutableListOf(localData.themes)
        val allHostKeys = mutableListOf(localData.hostKeys)
        val allPreferences = mutableListOf(localData.preferences)

        for ((deviceId, encryptedData) in remoteFiles) {
            try {
                val decrypted = decryptSyncData(encryptedData, syncPassword!!)
                val remoteData = deserializeSyncData(decrypted)

                allConnections.add(remoteData.connections)
                allKeys.add(remoteData.keys)
                allThemes.add(remoteData.themes)
                allHostKeys.add(remoteData.hostKeys)
                allPreferences.add(remoteData.preferences)
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to process data from device: $deviceId", e)
            }
        }

        val baseConnections = emptyMap<String, io.github.tabssh.storage.database.entities.ConnectionProfile>()
        val baseKeys = emptyMap<String, io.github.tabssh.storage.database.entities.StoredKey>()
        val baseThemes = emptyMap<String, io.github.tabssh.storage.database.entities.ThemeDefinition>()
        val baseHostKeys = emptyMap<String, io.github.tabssh.storage.database.entities.HostKeyEntry>()
        val basePreferences = emptyMap<String, Any>()

        val mergedConnections = allConnections.flatten()
        val mergedKeys = allKeys.flatten()
        val mergedThemes = allThemes.flatten()
        val mergedHostKeys = allHostKeys.flatten()
        val mergedPreferences = allPreferences.firstOrNull() ?: emptyMap()

        val connectionResult = mergeEngine.mergeConnections(baseConnections, localData.connections, mergedConnections)
        val keyResult = mergeEngine.mergeKeys(baseKeys, localData.keys, mergedKeys)
        val themeResult = mergeEngine.mergeThemes(baseThemes, localData.themes, mergedThemes)
        val hostKeyResult = mergeEngine.mergeHostKeys(baseHostKeys, localData.hostKeys, mergedHostKeys)
        val preferenceResult = mergeEngine.mergePreferences(basePreferences, localData.preferences, mergedPreferences)

        val allConflicts = mutableListOf<Conflict>()
        allConflicts.addAll(connectionResult.conflicts)
        allConflicts.addAll(keyResult.conflicts)
        allConflicts.addAll(themeResult.conflicts)
        allConflicts.addAll(hostKeyResult.conflicts)
        allConflicts.addAll(preferenceResult.conflicts)

        return CompleteMergeResult(
            connectionResult = connectionResult,
            keyResult = keyResult,
            themeResult = themeResult,
            hostKeyResult = hostKeyResult,
            preferences = preferenceResult.merged.firstOrNull() ?: emptyMap(),
            conflicts = allConflicts
        )
    }

    /**
     * Encrypt sync data
     */
    private fun encryptSyncData(data: SyncDataPackage, password: String): ByteArray {
        val json = serializeSyncData(data)
        val compressed = compressData(json)
        val encrypted = encryptor.encryptSyncFile(compressed, password)
        return encryptor.serializeEncryptedData(encrypted)
    }

    /**
     * Decrypt sync data
     */
    private fun decryptSyncData(encryptedData: ByteArray, password: String): ByteArray {
        val encrypted = encryptor.deserializeEncryptedData(encryptedData)
        val compressed = encryptor.decryptSyncFile(encrypted, password)
        return decompressData(compressed)
    }

    /**
     * Serialize sync data to JSON
     */
    private fun serializeSyncData(data: SyncDataPackage): ByteArray {
        val syncFileData = SyncFileData(
            metadata = data.metadata,
            connections = data.connections,
            keys = data.keys,
            themes = data.themes,
            preferences = data.preferences,
            hostKeys = data.hostKeys,
            syncBase = SyncBase()
        )
        val json = gson.toJson(syncFileData)
        return json.toByteArray()
    }

    /**
     * Deserialize sync data from JSON
     */
    private fun deserializeSyncData(data: ByteArray): SyncDataPackage {
        val json = String(data)
        val syncFileData = gson.fromJson(json, SyncFileData::class.java)
        return SyncDataPackage(
            connections = syncFileData.connections,
            keys = syncFileData.keys,
            themes = syncFileData.themes,
            preferences = syncFileData.preferences,
            hostKeys = syncFileData.hostKeys,
            metadata = syncFileData.metadata
        )
    }

    /**
     * Compress data with GZIP
     */
    private fun compressData(data: ByteArray): ByteArray {
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { it.write(data) }
        return outputStream.toByteArray()
    }

    /**
     * Decompress GZIP data
     */
    private fun decompressData(compressed: ByteArray): ByteArray {
        val inputStream = GZIPInputStream(ByteArrayInputStream(compressed))
        return inputStream.readBytes()
    }

    /**
     * Set sync password
     */
    fun setSyncPassword(password: String) {
        if (!encryptor.isPasswordStrong(password)) {
            throw IllegalArgumentException("Password does not meet strength requirements")
        }
        this.syncPassword = password
        preferenceManager.setSyncPasswordSet(true)
    }

    /**
     * Clear sync password
     */
    fun clearSyncPassword() {
        this.syncPassword = null
        preferenceManager.setSyncPasswordSet(false)
    }

    /**
     * Check if sync is enabled
     */
    fun isSyncEnabled(): Boolean {
        return preferenceManager.isSyncEnabled() && syncPassword != null
    }

    /**
     * Enable sync
     */
    suspend fun enableSync(password: String) {
        setSyncPassword(password)
        preferenceManager.setSyncEnabled(true)
        updateSyncStatus()
    }

    /**
     * Disable sync
     */
    suspend fun disableSync() {
        preferenceManager.setSyncEnabled(false)
        clearSyncPassword()
        updateSyncStatus()
    }

    /**
     * Get sync status
     */
    fun getSyncStatus(): SyncStatus = _syncStatus.value

    /**
     * Update sync status
     */
    private fun updateSyncStatus() {
        _syncStatus.value = SyncStatus(
            enabled = isSyncEnabled(),
            lastSyncTime = metadataManager.getLastSyncTime(),
            lastSyncResult = null,
            isSyncing = _syncProgress.value.stage != SyncStage.IDLE && _syncProgress.value.stage != SyncStage.COMPLETED,
            accountEmail = googleDriveSyncManager?.getAuthManager()?.getAccountEmail(),
            deviceId = metadataManager.getDeviceId()
        )
    }

    /**
     * Update sync progress
     */
    private fun updateProgress(stage: SyncStage, current: Int = 0, total: Int = 0, message: String = "") {
        _syncProgress.value = SyncProgress(stage, current, total, message)
    }

    /**
     * Create initial status
     */
    private fun createInitialStatus(): SyncStatus {
        return SyncStatus(
            enabled = false,
            lastSyncTime = 0,
            lastSyncResult = null,
            isSyncing = false,
            accountEmail = null,
            deviceId = metadataManager.getDeviceId()
        )
    }

    /**
     * Get authentication manager (Google Drive only)
     */
    fun getGoogleDriveAuthManager() = googleDriveSyncManager?.getAuthManager()
}

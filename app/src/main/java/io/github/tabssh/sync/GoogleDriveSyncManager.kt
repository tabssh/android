package io.github.tabssh.sync

import android.content.Context
import com.google.gson.Gson
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.preferences.PreferenceManager
import io.github.tabssh.sync.auth.DriveAuthenticationManager
import io.github.tabssh.sync.data.SyncDataApplier
import io.github.tabssh.sync.data.SyncDataCollector
import io.github.tabssh.sync.encryption.SyncEncryptor
import io.github.tabssh.sync.executor.SyncExecutor
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
 * Main orchestrator for Google Drive sync operations
 */
class GoogleDriveSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveSyncManager"
        private const val SYNC_FILE_VERSION = 2
    }

    private val database = TabSSHDatabase.getDatabase(context)
    private val preferenceManager = PreferenceManager(context)
    private val gson = Gson()

    private val authManager = DriveAuthenticationManager(context)
    private val metadataManager = SyncMetadataManager(context)
    private val encryptor = SyncEncryptor()
    private val syncExecutor = SyncExecutor(authManager, metadataManager)
    private val dataCollector = SyncDataCollector(context, database, preferenceManager, metadataManager)
    private val dataApplier = SyncDataApplier(context, database, preferenceManager)
    private val mergeEngine = MergeEngine()
    private val conflictResolver = ConflictResolver(context, database)

    private val _syncStatus = MutableStateFlow(createInitialStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _syncProgress = MutableStateFlow(SyncProgress(SyncStage.IDLE))
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private var syncPassword: String? = null

    init {
        authManager.initialize()
    }

    /**
     * Perform complete sync operation
     */
    suspend fun performSync(trigger: SyncTrigger = SyncTrigger.MANUAL): SyncResult =
        withContext(Dispatchers.IO) {
            Logger.d(TAG, "Starting sync: trigger=$trigger")

            if (!authManager.isAuthenticated()) {
                return@withContext SyncResult(
                    success = false,
                    message = "Not authenticated - please sign in to Google Drive"
                )
            }

            if (syncPassword == null) {
                return@withContext SyncResult(
                    success = false,
                    message = "Sync password not configured"
                )
            }

            updateProgress(SyncStage.COLLECTING_DATA, 0, 6, "Collecting local data...")

            try {
                val localData = dataCollector.collectAllSyncData()

                updateProgress(SyncStage.ENCRYPTING, 1, 6, "Encrypting data...")

                val encryptedLocal = encryptSyncData(localData, syncPassword!!)

                updateProgress(SyncStage.UPLOADING, 2, 6, "Uploading to Google Drive...")

                syncExecutor.uploadSyncFile(encryptedLocal, metadataManager.getDeviceId())

                updateProgress(SyncStage.DOWNLOADING, 3, 6, "Downloading from other devices...")

                val remoteFiles = syncExecutor.downloadAllDeviceFiles()

                updateProgress(SyncStage.MERGING, 4, 6, "Merging data...")

                val mergeResult = mergeAllData(localData, remoteFiles)

                if (mergeResult.hasConflicts()) {
                    val autoResolved = conflictResolver.autoResolveConflicts(mergeResult.conflicts)

                    if (autoResolved.size < mergeResult.conflicts.size) {
                        updateProgress(SyncStage.RESOLVING_CONFLICTS, 5, 6,
                            "${mergeResult.conflicts.size - autoResolved.size} conflicts require manual resolution")

                        return@withContext SyncResult(
                            success = false,
                            message = "Sync conflicts require manual resolution",
                            conflicts = mergeResult.conflicts.filter { !it.autoResolvable }
                        )
                    }

                    conflictResolver.applyResolutions(autoResolved)
                }

                updateProgress(SyncStage.APPLYING_CHANGES, 5, 6, "Applying changes...")

                val applyResult = dataApplier.applyMergeResult(
                    mergeResult.connectionResult,
                    mergeResult.keyResult,
                    mergeResult.themeResult,
                    mergeResult.hostKeyResult,
                    mergeResult.preferences
                )

                metadataManager.updateLastSuccessfulSyncTime()

                updateProgress(SyncStage.COMPLETED, 6, 6, "Sync completed successfully")

                updateSyncStatus()

                Logger.d(TAG, "Sync completed successfully")

                SyncResult(
                    success = true,
                    message = "Sync completed successfully",
                    syncedItemCounts = localData.metadata.itemCounts
                )
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
        return preferenceManager.isSyncEnabled() && authManager.isAuthenticated() && syncPassword != null
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
            accountEmail = authManager.getAccountEmail(),
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
     * Get authentication manager
     */
    fun getAuthManager(): DriveAuthenticationManager = authManager
    
    /**
     * Clear all remote sync data from Google Drive
     */
    suspend fun clearRemoteData() = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Clearing remote sync data")
        
        if (!authManager.isAuthenticated()) {
            throw IllegalStateException("Not authenticated")
        }
        
        try {
            val deviceId = metadataManager.getDeviceId()
            syncExecutor.deleteSyncFile(deviceId)
            Logger.i(TAG, "Remote sync data cleared successfully")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to clear remote data", e)
            throw e
        }
    }
    
    /**
     * Force upload of local data to Google Drive (overwrite remote)
     */
    suspend fun forceUpload(): Boolean = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Force uploading local data")
        
        if (!authManager.isAuthenticated()) {
            Logger.e(TAG, "Cannot force upload: not authenticated")
            return@withContext false
        }
        
        if (syncPassword == null) {
            Logger.e(TAG, "Cannot force upload: sync password not set")
            return@withContext false
        }
        
        try {
            updateProgress(SyncStage.COLLECTING_DATA, 0, 3, "Collecting local data...")
            
            val localData = dataCollector.collectAllSyncData()
            
            updateProgress(SyncStage.ENCRYPTING, 1, 3, "Encrypting data...")
            
            val encryptedData = encryptSyncData(localData, syncPassword!!)
            
            updateProgress(SyncStage.UPLOADING, 2, 3, "Uploading to Google Drive...")
            
            syncExecutor.uploadSyncFile(encryptedData, metadataManager.getDeviceId())
            
            updateProgress(SyncStage.COMPLETED, 3, 3, "Upload complete")
            
            preferenceManager.setLastSyncTime(System.currentTimeMillis())
            
            Logger.i(TAG, "Force upload completed successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Logger.e(TAG, "Force upload failed", e)
            updateProgress(SyncStage.ERROR, 0, 0, "Upload failed: ${e.message}")
            return@withContext false
        }
    }
    
    /**
     * Force download of remote data from Google Drive (overwrite local)
     */
    suspend fun forceDownload(): Boolean = withContext(Dispatchers.IO) {
        Logger.d(TAG, "Force downloading remote data")
        
        if (!authManager.isAuthenticated()) {
            Logger.e(TAG, "Cannot force download: not authenticated")
            return@withContext false
        }
        
        if (syncPassword == null) {
            Logger.e(TAG, "Cannot force download: sync password not set")
            return@withContext false
        }
        
        try {
            updateProgress(SyncStage.DOWNLOADING, 0, 3, "Downloading from Google Drive...")
            
            val deviceId = metadataManager.getDeviceId()
            val encryptedData = syncExecutor.downloadSyncFile(deviceId)
            
            if (encryptedData == null) {
                Logger.w(TAG, "No remote data found for this device")
                updateProgress(SyncStage.ERROR, 0, 0, "No remote data found")
                return@withContext false
            }
            
            updateProgress(SyncStage.DECRYPTING, 1, 3, "Decrypting data...")
            
            val decryptedData = decryptSyncData(encryptedData, syncPassword!!)
            val remoteData = deserializeSyncData(decryptedData)
            
            updateProgress(SyncStage.APPLYING, 2, 3, "Applying remote data...")
            
            // For force download, create merge results that will replace local data
            val connectionResult = io.github.tabssh.sync.models.MergeResult(
                merged = remoteData.connections,
                conflicts = emptyList()
            )
            
            val keyResult = io.github.tabssh.sync.models.MergeResult(
                merged = remoteData.keys,
                conflicts = emptyList()
            )
            
            val themeResult = io.github.tabssh.sync.models.MergeResult(
                merged = remoteData.themes,
                conflicts = emptyList()
            )
            
            val hostKeyResult = io.github.tabssh.sync.models.MergeResult(
                merged = remoteData.hostKeys,
                conflicts = emptyList()
            )
            
            dataApplier.applyMergeResult(
                connectionResult,
                keyResult,
                themeResult,
                hostKeyResult,
                remoteData.preferences
            )
            
            updateProgress(SyncStage.COMPLETED, 3, 3, "Download complete")
            
            preferenceManager.setLastSyncTime(System.currentTimeMillis())
            
            Logger.i(TAG, "Force download completed successfully")
            return@withContext true
            
        } catch (e: Exception) {
            Logger.e(TAG, "Force download failed", e)
            updateProgress(SyncStage.ERROR, 0, 0, "Download failed: ${e.message}")
            return@withContext false
        }
    }
}

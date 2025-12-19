package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.ConflictResolutionOption
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves merge conflicts based on user decisions
 */
class ConflictResolver(
    private val context: Context,
    private val database: TabSSHDatabase
) {

    companion object {
        private const val TAG = "ConflictResolver"
    }

    /**
     * Apply conflict resolutions
     */
    suspend fun applyResolutions(resolutions: List<ConflictResolution>): ApplyResolutionsResult =
        withContext(Dispatchers.IO) {
            var successCount = 0
            val errors = mutableListOf<String>()

            for (resolution in resolutions) {
                try {
                    applyResolution(resolution)
                    successCount++
                    Logger.d(TAG, "Applied resolution for ${resolution.conflict.entityType}:${resolution.conflict.entityId}")
                } catch (e: Exception) {
                    val error = "Failed to apply resolution for ${resolution.conflict.entityType}:${resolution.conflict.entityId}: ${e.message}"
                    errors.add(error)
                    Logger.e(TAG, error, e)
                }
            }

            Logger.d(TAG, "Applied $successCount/${resolutions.size} resolutions")

            ApplyResolutionsResult(
                successCount = successCount,
                totalCount = resolutions.size,
                errors = errors
            )
        }

    /**
     * Apply single resolution
     */
    private suspend fun applyResolution(resolution: ConflictResolution) {
        val conflict = resolution.conflict

        when (conflict.entityType) {
            "connection" -> applyConnectionResolution(conflict, resolution.resolution)
            "key" -> applyKeyResolution(conflict, resolution.resolution)
            "theme" -> applyThemeResolution(conflict, resolution.resolution)
            "host_key" -> applyHostKeyResolution(conflict, resolution.resolution)
            "preference" -> applyPreferenceResolution(conflict, resolution.resolution)
            else -> Logger.w(TAG, "Unknown entity type: ${conflict.entityType}")
        }
    }

    /**
     * Apply connection resolution
     */
    private suspend fun applyConnectionResolution(
        conflict: Conflict,
        option: ConflictResolutionOption
    ) {
        when (option) {
            ConflictResolutionOption.KEEP_LOCAL -> {
                val local = conflict.localValue as? ConnectionProfile
                if (local != null) {
                    database.connectionDao().updateConnection(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteValue as? ConnectionProfile
                if (remote != null) {
                    database.connectionDao().updateConnection(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localValue as? ConnectionProfile
                val remote = conflict.remoteValue as? ConnectionProfile
                if (local != null && remote != null) {
                    val duplicateRemote = remote.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "${remote.name} (remote)"
                    )
                    database.connectionDao().insertConnection(duplicateRemote)
                }
            }
            ConflictResolutionOption.SKIP -> {
                // Do nothing
            }
        }
    }

    /**
     * Apply key resolution
     */
    private suspend fun applyKeyResolution(
        conflict: Conflict,
        option: ConflictResolutionOption
    ) {
        when (option) {
            ConflictResolutionOption.KEEP_LOCAL -> {
                val local = conflict.localValue as? StoredKey
                if (local != null) {
                    database.keyDao().updateKey(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteValue as? StoredKey
                if (remote != null) {
                    database.keyDao().updateKey(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localValue as? StoredKey
                val remote = conflict.remoteValue as? StoredKey
                if (local != null && remote != null) {
                    val duplicateRemote = remote.copy(
                        keyId = java.util.UUID.randomUUID().toString(),
                        name = "${remote.name} (remote)"
                    )
                    database.keyDao().insertKey(duplicateRemote)
                }
            }
            ConflictResolutionOption.SKIP -> {
                // Do nothing
            }
        }
    }

    /**
     * Apply theme resolution
     */
    private suspend fun applyThemeResolution(
        conflict: Conflict,
        option: ConflictResolutionOption
    ) {
        when (option) {
            ConflictResolutionOption.KEEP_LOCAL -> {
                val local = conflict.localValue as? ThemeDefinition
                if (local != null) {
                    database.themeDao().updateTheme(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteValue as? ThemeDefinition
                if (remote != null) {
                    database.themeDao().updateTheme(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localValue as? ThemeDefinition
                val remote = conflict.remoteValue as? ThemeDefinition
                if (local != null && remote != null) {
                    val duplicateRemote = remote.copy(
                        themeId = "${remote.themeId}_remote",
                        name = "${remote.name} (remote)"
                    )
                    database.themeDao().insertTheme(duplicateRemote)
                }
            }
            ConflictResolutionOption.SKIP -> {
                // Do nothing
            }
        }
    }

    /**
     * Apply host key resolution
     */
    private suspend fun applyHostKeyResolution(
        conflict: Conflict,
        option: ConflictResolutionOption
    ) {
        when (option) {
            ConflictResolutionOption.KEEP_LOCAL -> {
                val local = conflict.localValue as? HostKeyEntry
                if (local != null) {
                    database.hostKeyDao().updateHostKey(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteValue as? HostKeyEntry
                if (remote != null) {
                    database.hostKeyDao().updateHostKey(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                // Not applicable for host keys - use newer
                val local = conflict.localValue as? HostKeyEntry
                val remote = conflict.remoteValue as? HostKeyEntry
                if (local != null && remote != null) {
                    val newer = if (local.modifiedAt >= remote.modifiedAt) local else remote
                    database.hostKeyDao().updateHostKey(newer)
                }
            }
            ConflictResolutionOption.SKIP -> {
                // Do nothing
            }
        }
    }

    /**
     * Apply preference resolution
     */
    private suspend fun applyPreferenceResolution(
        conflict: Conflict,
        option: ConflictResolutionOption
    ) {
        // Preferences handled via PreferenceManager
        // Resolution applied during sync data application
        Logger.d(TAG, "Preference resolution: ${conflict.field} -> $option")
    }

    /**
     * Auto-resolve conflicts where possible
     */
    fun autoResolveConflicts(conflicts: List<Conflict>): List<ConflictResolution> {
        return conflicts.mapNotNull { conflict ->
            if (conflict.autoResolvable) {
                val resolution = when {
                    conflict.localTimestamp > conflict.remoteTimestamp ->
                        ConflictResolutionOption.KEEP_LOCAL
                    conflict.localTimestamp < conflict.remoteTimestamp ->
                        ConflictResolutionOption.KEEP_REMOTE
                    else -> null
                }

                resolution?.let {
                    ConflictResolution(conflict, it, applyToAll = false)
                }
            } else {
                null
            }
        }
    }

    /**
     * Get conflicts by entity type
     */
    fun getConflictsByType(conflicts: List<Conflict>): Map<String, List<Conflict>> {
        return conflicts.groupBy { it.entityType }
    }

    /**
     * Get conflict count by type
     */
    fun getConflictCountByType(conflicts: List<Conflict>): Map<String, Int> {
        return conflicts.groupBy { it.entityType }
            .mapValues { it.value.size }
    }

    /**
     * Check if conflict requires manual resolution
     */
    fun requiresManualResolution(conflict: Conflict): Boolean {
        return !conflict.autoResolvable
    }
}

/**
 * Result of applying resolutions
 */
data class ApplyResolutionsResult(
    val successCount: Int,
    val totalCount: Int,
    val errors: List<String>
) {
    fun isFullySuccessful(): Boolean = successCount == totalCount
    fun hasErrors(): Boolean = errors.isNotEmpty()
}

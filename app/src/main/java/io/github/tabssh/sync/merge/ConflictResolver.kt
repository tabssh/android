package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.entities.ThemeDefinition
import io.github.tabssh.sync.log.SyncLogManager
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.ConflictResolutionOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolves merge conflicts based on user decisions. Every applied resolution
 * is recorded in the dedicated Sync Log ([SyncLogManager]) — conflict detail
 * (entity type/id, description) must never reach [io.github.tabssh.utils.logging.Logger].
 */
class ConflictResolver(
    private val context: Context,
    private val database: TabSSHDatabase,
    private val syncLogManager: SyncLogManager = SyncLogManager(context)
) {

    /**
     * Apply conflict resolutions.
     *
     * @param auto true when these resolutions were chosen by the headless
     *        timestamp-based auto-resolver rather than an explicit user
     *        choice; recorded as "auto-merged" in the Sync Log instead of
     *        the specific option, since no human decided it.
     */
    suspend fun applyResolutions(resolutions: List<ConflictResolution>, auto: Boolean = false): ApplyResolutionsResult =
        withContext(Dispatchers.IO) {
            var successCount = 0
            val errors = mutableListOf<String>()

            for (resolution in resolutions) {
                try {
                    applyResolution(resolution)
                    successCount++
                    if (auto) {
                        syncLogManager.recordAutoMerged(resolution.conflict)
                    } else {
                        syncLogManager.recordResolution(resolution.conflict, resolution.resolution)
                    }
                } catch (e: Exception) {
                    errors.add("${resolution.conflict.entityType}: ${e.message}")
                }
            }

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
            else -> Unit
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
                val local = conflict.localEntity as? ConnectionProfile
                if (local != null) {
                    database.connectionDao().updateConnection(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteEntity as? ConnectionProfile
                if (remote != null) {
                    database.connectionDao().updateConnection(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localEntity as? ConnectionProfile
                val remote = conflict.remoteEntity as? ConnectionProfile
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
                val local = conflict.localEntity as? StoredKey
                if (local != null) {
                    database.keyDao().updateKey(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteEntity as? StoredKey
                if (remote != null) {
                    database.keyDao().updateKey(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localEntity as? StoredKey
                val remote = conflict.remoteEntity as? StoredKey
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
                val local = conflict.localEntity as? ThemeDefinition
                if (local != null) {
                    database.themeDao().updateTheme(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteEntity as? ThemeDefinition
                if (remote != null) {
                    database.themeDao().updateTheme(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                val local = conflict.localEntity as? ThemeDefinition
                val remote = conflict.remoteEntity as? ThemeDefinition
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
                val local = conflict.localEntity as? HostKeyEntry
                if (local != null) {
                    database.hostKeyDao().updateHostKey(local)
                }
            }
            ConflictResolutionOption.KEEP_REMOTE -> {
                val remote = conflict.remoteEntity as? HostKeyEntry
                if (remote != null) {
                    database.hostKeyDao().updateHostKey(remote)
                }
            }
            ConflictResolutionOption.KEEP_BOTH -> {
                // A host can only have one trusted fingerprint — the picker
                // never offers KEEP_BOTH for host_key (see
                // Conflict.getResolutionOptions()). Defensive no-op in case a
                // stale resolution object reaches this path anyway.
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
        // Preferences handled via PreferenceManager; resolution applied
        // during sync data application. This branch is currently
        // unreachable — MergeEngine.mergePreferences() has no caller — kept
        // as a defensive no-op since Conflict.getResolutionOptions() still
        // models PREFERENCE_DIVERGED as part of the public conflict surface.
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

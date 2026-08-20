package io.github.tabssh.sync.models

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Coverage for [Conflict.preselectedResolution] (the picker's LWW default):
 * the newer side wins, and a tie (including both-zero, e.g. legacy rows
 * that predate `modified_at`) defaults to keep-local — matching
 * [io.github.tabssh.sync.merge.ConflictResolver.autoResolveConflicts]'s own
 * comparison exactly, so headless auto-resolve and the interactive
 * picker's default never disagree on the same conflict.
 */
class ConflictPreselectionTest {

    private fun conflictWith(localTimestamp: Long, remoteTimestamp: Long) = Conflict(
        entityType = "connection",
        entityId = "c1",
        conflictType = ConflictType.FIELD_MODIFIED_BOTH_SIDES,
        localTimestamp = localTimestamp,
        remoteTimestamp = remoteTimestamp
    )

    @Test
    fun `newer remote wins`() {
        val conflict = conflictWith(localTimestamp = 1_000L, remoteTimestamp = 2_000L)
        assertEquals(ConflictResolutionOption.KEEP_REMOTE, conflict.preselectedResolution())
    }

    @Test
    fun `newer local wins`() {
        val conflict = conflictWith(localTimestamp = 2_000L, remoteTimestamp = 1_000L)
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, conflict.preselectedResolution())
    }

    @Test
    fun `equal timestamps default to keep-local`() {
        val conflict = conflictWith(localTimestamp = 1_500L, remoteTimestamp = 1_500L)
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, conflict.preselectedResolution())
    }

    @Test
    fun `both-zero timestamps default to keep-local`() {
        val conflict = conflictWith(localTimestamp = 0L, remoteTimestamp = 0L)
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, conflict.preselectedResolution())
    }
}

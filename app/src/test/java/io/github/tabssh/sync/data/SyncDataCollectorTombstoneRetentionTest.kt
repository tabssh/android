package io.github.tabssh.sync.data

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Verifies the tombstone retention window and purge cutoff math.
 * The window must comfortably exceed the longest realistic device-offline
 * gap (90+ days) so a purged delete can never be resurrected by a peer
 * that was offline for less than the window.
 */
class SyncDataCollectorTombstoneRetentionTest {

    @Test
    fun `retention window is at least 90 days`() {
        val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000
        assertTrue(
            SyncDataCollector.TOMBSTONE_RETENTION_MS >= ninetyDaysMs,
            "Retention must comfortably exceed the longest device-offline period"
        )
    }

    @Test
    fun `purge cutoff is now minus the retention window`() {
        val nowMs = 1_700_000_000_000L
        assertEquals(
            nowMs - SyncDataCollector.TOMBSTONE_RETENTION_MS,
            SyncDataCollector.tombstonePurgeCutoff(nowMs)
        )
    }

    @Test
    fun `tombstone deleted just inside the window survives the cutoff`() {
        val nowMs = 1_700_000_000_000L
        val deletedAt = nowMs - SyncDataCollector.TOMBSTONE_RETENTION_MS + 1
        // purgeOlderThan deletes rows with deleted_at < cutoff, so this row must be at or above it.
        assertTrue(deletedAt >= SyncDataCollector.tombstonePurgeCutoff(nowMs))
    }

    @Test
    fun `tombstone deleted before the window falls below the cutoff`() {
        val nowMs = 1_700_000_000_000L
        val deletedAt = nowMs - SyncDataCollector.TOMBSTONE_RETENTION_MS - 1
        assertTrue(deletedAt < SyncDataCollector.tombstonePurgeCutoff(nowMs))
    }
}

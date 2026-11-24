package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.MonitorSlot
import kotlinx.coroutines.flow.Flow

@Dao
interface MonitorSlotDao {

    // ── Queries ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM monitor_slots ORDER BY connection_id")
    fun getAllSlots(): Flow<List<MonitorSlot>>

    @Query("SELECT * FROM monitor_slots WHERE enabled = 1 ORDER BY connection_id")
    suspend fun getEnabledSlots(): List<MonitorSlot>

    @Query("SELECT * FROM monitor_slots WHERE connection_id = :connectionId LIMIT 1")
    suspend fun getByConnectionId(connectionId: String): MonitorSlot?

    @Query("SELECT * FROM monitor_slots WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MonitorSlot?

    // ── Write ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(slot: MonitorSlot)

    @Update
    suspend fun update(slot: MonitorSlot)

    @Delete
    suspend fun delete(slot: MonitorSlot)

    @Query("DELETE FROM monitor_slots WHERE connection_id = :connectionId")
    suspend fun deleteByConnectionId(connectionId: String)

    // ── State helpers (called by HostAvailabilityWorker) ─────────────────────

    @Query("""UPDATE monitor_slots SET
        last_checked_at          = :now,
        is_currently_down        = :isDown,
        last_seen_up             = CASE WHEN :isDown = 0 THEN :now ELSE last_seen_up END,
        consecutive_failures     = CASE WHEN :isDown = 1 THEN consecutive_failures + 1 ELSE 0 END,
        last_notified_down_at    = CASE WHEN :stampDown = 1 THEN :now ELSE last_notified_down_at END,
        last_notified_up_at      = CASE WHEN :stampUp   = 1 THEN :now ELSE last_notified_up_at   END
        WHERE id = :slotId""")
    suspend fun updateProbeResult(
        slotId: String,
        now: Long,
        isDown: Boolean,
        stampDown: Boolean,
        stampUp: Boolean
    )

    /**
     * Stamps "last seen up" and "last checked" from a live session that just returned metrics, and
     * clears a stale down flag: a host answering over a live session is not down. Skipped while the
     * slot is already up and stamped within [minGapMs], so a steady metrics loop does not rewrite
     * the row (and trigger a sync) each tick.
     */
    @Query("""UPDATE monitor_slots SET
        last_seen_up         = :now,
        last_checked_at      = :now,
        is_currently_down    = 0,
        consecutive_failures = 0
        WHERE connection_id = :connectionId
          AND (is_currently_down = 1 OR last_seen_up < :now - :minGapMs)""")
    suspend fun markSeenUp(connectionId: String, now: Long, minGapMs: Long = 300_000L)
}

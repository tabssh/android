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
}

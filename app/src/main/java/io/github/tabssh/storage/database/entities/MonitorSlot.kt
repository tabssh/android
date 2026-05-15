package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Per-host background monitoring configuration and runtime state.
 *
 * One row per monitored connection. The background [HostAvailabilityWorker]
 * reads all enabled rows, TCP-probes each host, updates state, and posts
 * notifications when reachability or metric thresholds change.
 *
 * Two-tier monitoring model:
 *  - Background (always): TCP connect to [ConnectionProfile.host]:[port] —
 *    cheap, no SSH handshake, battery-friendly even at hundreds of hosts.
 *  - Foreground (opt-in): full SSH MetricsCollector run to check CPU/memory/
 *    disk thresholds — only executed when [enablePerformanceChecks] is true
 *    and the worker happens to run while the app is not in extreme battery-
 *    saver mode.
 *
 * Cooldown: once a "down" notification fires, [lastNotifiedDownAt] is stamped
 * and the next alert is suppressed until [alertCooldownMinutes] have passed.
 * This prevents notification storms for flapping hosts.
 *
 * DB: added in v32 (migration from v31).
 */
@Serializable
@Entity(tableName = "monitor_slots")
data class MonitorSlot(

    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    /** FK to [ConnectionProfile.id]. No Room foreign-key constraint intentionally —
     *  we want a soft link so deleting a profile doesn't cascade and lose config. */
    @ColumnInfo(name = "connection_id")
    val connectionId: String,

    /** Master switch. When false the worker skips this row entirely. */
    @ColumnInfo(name = "enabled")
    val enabled: Boolean = true,

    // ── Alert toggles ────────────────────────────────────────────────────────

    /** Post a notification when TCP probe fails after previously succeeding. */
    @ColumnInfo(name = "alert_on_down")
    val alertOnDown: Boolean = true,

    /** Post a notification when host becomes reachable after being down. */
    @ColumnInfo(name = "alert_on_recovery")
    val alertOnRecovery: Boolean = true,

    // ── Performance thresholds (null = disabled) ─────────────────────────────
    // These apply only when enablePerformanceChecks = true.

    /** Alert if CPU% exceeds this value (1–100). */
    @ColumnInfo(name = "cpu_threshold")
    val cpuThreshold: Int? = null,

    /** Alert if memory used% exceeds this value (1–100). */
    @ColumnInfo(name = "memory_threshold")
    val memoryThreshold: Int? = null,

    /** Alert if disk used% exceeds this value (1–100). */
    @ColumnInfo(name = "disk_threshold")
    val diskThreshold: Int? = null,

    /** Alert if 1-minute load average exceeds this value. */
    @ColumnInfo(name = "load_threshold")
    val loadThreshold: Float? = null,

    /** When true, the background worker runs a full SSH metrics check
     *  (CPU/mem/disk) in addition to the TCP availability probe. Consumes
     *  more battery; off by default. */
    @ColumnInfo(name = "enable_performance_checks")
    val enablePerformanceChecks: Boolean = false,

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Desired check interval. WorkManager enforces a minimum of 15 min;
     *  values below that are rounded up. Stored in minutes. */
    @ColumnInfo(name = "check_interval_minutes")
    val checkIntervalMinutes: Int = 15,

    /** Minimum minutes between repeated "still down" notifications for the
     *  same host. Prevents notification storms on sustained outages. */
    @ColumnInfo(name = "alert_cooldown_minutes")
    val alertCooldownMinutes: Int = 60,

    // ── Runtime state (written by the worker, not by the user) ───────────────

    /** Epoch-ms of the most recent probe attempt (up or down). */
    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long = 0,

    /** Epoch-ms of the most recent successful probe. */
    @ColumnInfo(name = "last_seen_up")
    val lastSeenUp: Long = 0,

    /** Epoch-ms of the most recent "host is down" notification. */
    @ColumnInfo(name = "last_notified_down_at")
    val lastNotifiedDownAt: Long = 0,

    /** Epoch-ms of the most recent "host recovered" notification. */
    @ColumnInfo(name = "last_notified_up_at")
    val lastNotifiedUpAt: Long = 0,

    /** True while the most recent probe failed. */
    @ColumnInfo(name = "is_currently_down")
    val isCurrentlyDown: Boolean = false,

    /** Number of consecutive failed probes since the last success.
     *  Resets to 0 on the first successful probe. */
    @ColumnInfo(name = "consecutive_failures")
    val consecutiveFailures: Int = 0
)

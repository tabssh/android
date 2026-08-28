package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A tracked VPS/hosting instance, mirroring the grouped-by-tenant layout of
 * ~/Documents/VPS.md so import and export can round-trip that file: each
 * tenant/provider groups a set of hosts, each with hostname, IPv4/IPv6,
 * specs, an optional linked domain, a renewal date/cycle/price, and a free
 * description. Renewal date is kept both as a best-effort parsed timestamp
 * and as the original raw text, because the source file mixes exact dates
 * ("May 15, 2027"), yearly-no-year recurrences ("June 7th, yearly"), free
 * tiers ("Free/Never") and unknowns ("TBD") that don't all reduce to a date.
 */
@Serializable
@Entity(tableName = "vps_hosts")
data class VpsHost(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "tenant")
    val tenant: String,

    @ColumnInfo(name = "hostname")
    val hostname: String,

    @ColumnInfo(name = "ipv4")
    val ipv4: String? = null,

    @ColumnInfo(name = "ipv6")
    val ipv6: String? = null,

    @ColumnInfo(name = "specs")
    val specs: String? = null,

    @ColumnInfo(name = "linked_domain")
    val linkedDomain: String? = null,

    /** Original renewal-date text as it appeared in VPS.md, e.g. "May 15, 2027", "June 7th, yearly", "Free/Never", "TBD". */
    @ColumnInfo(name = "renewal_raw")
    val renewalRaw: String? = null,

    /** Best-effort epoch millis parse of renewalRaw's next occurrence, or null when it isn't a concrete/derivable date (Free/Never, TBD). */
    @ColumnInfo(name = "renewal_date")
    val renewalDate: Long? = null,

    @ColumnInfo(name = "billing_cycle")
    val billingCycle: String? = null,

    @ColumnInfo(name = "price")
    val price: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Days before renewalDate to fire the renewal reminder notification. */
    @ColumnInfo(name = "reminder_days_before", defaultValue = "7")
    val reminderDaysBefore: Int = 7,

    /** Epoch millis of the last reminder notification fired for the currently-set renewalDate, to avoid re-notifying daily. */
    @ColumnInfo(name = "last_reminder_sent_at")
    val lastReminderSentAt: Long? = null,

    /**
     * Epoch millis of when the user answered "no" to the overdue-renewal
     * confirmation prompt ("was this renewed?") — null while the host is
     * active/unconfirmed. A canceled host is kept (not deleted immediately)
     * so the user has a grace window to notice/undo, then swept away once
     * it has been canceled for 30+ days (see `VpsTrackerActivity`'s
     * stale-cancellation sweep).
     */
    @ColumnInfo(name = "canceled_at")
    val canceledAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long
)

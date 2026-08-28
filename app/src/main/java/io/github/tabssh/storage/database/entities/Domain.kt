package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A tracked domain registration, mirroring the columns of the NameCheap
 * "Domain List" CSV export (~/Documents/Domain_List.csv) exactly so import
 * and export can round-trip that file: Domain Name, Domain privacy
 * protection status, Domain status at NC, Domain auto-renew status, Domain
 * expiration date.
 */
@Serializable
@Entity(tableName = "domains")
data class Domain(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "domain_name")
    val domainName: String,

    @ColumnInfo(name = "privacy_protection")
    val privacyProtection: String,

    @ColumnInfo(name = "status_at_registrar")
    val statusAtRegistrar: String,

    @ColumnInfo(name = "auto_renew")
    val autoRenew: String,

    /** Epoch millis (UTC midnight) parsed from the CSV's "MMM dd yyyy" expiration date, or null if unset/unparseable. */
    @ColumnInfo(name = "expiration_date")
    val expirationDate: Long? = null,

    /** Days before expirationDate to fire the renewal reminder notification. */
    @ColumnInfo(name = "reminder_days_before", defaultValue = "7")
    val reminderDaysBefore: Int = 7,

    /** Epoch millis of the last reminder notification fired for the currently-set expirationDate, to avoid re-notifying daily. */
    @ColumnInfo(name = "last_reminder_sent_at")
    val lastReminderSentAt: Long? = null,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    /**
     * Epoch millis of when the user answered "no" to the overdue-renewal
     * confirmation prompt ("was this renewed?") — null while the domain is
     * active/unconfirmed. A canceled domain is kept (not deleted
     * immediately) so the user has a grace window to notice/undo, then
     * swept away once it has been canceled for 30+ days (see
     * `DomainTrackerActivity`'s stale-cancellation sweep).
     */
    @ColumnInfo(name = "canceled_at")
    val canceledAt: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long
)

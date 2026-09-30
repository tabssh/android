package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Schema declaration for the one-time legacy password handoff table.
 *
 * New passwords never enter this table. MIGRATION_13_14 can only carry legacy
 * plaintext out of the old schema; HypervisorPasswordStore moves each value
 * into Android Keystore storage and deletes the row. The empty table remains
 * declared so Room can validate upgraded databases before app startup runs.
 */
@Entity(tableName = "hypervisor_password_carryover")
data class HypervisorPasswordCarryover(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,

    @ColumnInfo(name = "password")
    val password: String
)

package io.github.tabssh.storage.database

import android.content.Context
import androidx.room.*
import androidx.room.RoomDatabase.JournalMode
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.tabssh.storage.database.dao.*
import io.github.tabssh.storage.database.entities.*
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.utils.logging.Logger

/**
 * Main Room database for TabSSH.
 *
 * Current version: 3.
 * Versions 1 and 2 never shipped with persisted user data (alpha installs were
 * wiped on every upgrade before v3 stabilised). To avoid an IllegalStateException
 * at first open on any leftover v1/v2 install, those versions use
 * `fallbackToDestructiveMigrationFrom(1, 2)` — they will be re-created on first
 * open after upgrade. From v3 onward every version bump MUST register a real
 * Migration object via addMigrations(); never add additional destructive
 * fallbacks to that list once real user data is in play.
 */
@Database(
    entities = [
        ConnectionProfile::class,
        StoredKey::class,
        HostKeyEntry::class,
        TabSession::class,
        ThemeDefinition::class,
        TrustedCertificate::class,
        SyncState::class,
        ConnectionGroup::class,
        Snippet::class,
        Identity::class,
        AuditLogEntry::class,
        HypervisorProfile::class,
        Workspace::class,
        CloudAccount::class,
        Macro::class,
        io.github.tabssh.storage.database.entities.HypervisorAccount::class,
        MonitorSlot::class,
        VncHost::class,
        VncIdentity::class
    ],
    version = 4,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TabSSHDatabase : RoomDatabase() {

    abstract fun connectionDao(): ConnectionDao
    abstract fun keyDao(): KeyDao
    abstract fun hostKeyDao(): HostKeyDao
    abstract fun tabSessionDao(): TabSessionDao
    abstract fun themeDao(): ThemeDao
    abstract fun certificateDao(): CertificateDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun snippetDao(): SnippetDao
    abstract fun identityDao(): IdentityDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun hypervisorDao(): HypervisorDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun macroDao(): MacroDao
    abstract fun hypervisorAccountDao(): io.github.tabssh.storage.database.dao.HypervisorAccountDao
    abstract fun monitorSlotDao(): MonitorSlotDao
    abstract fun vncHostDao(): VncHostDao
    abstract fun vncIdentityDao(): VncIdentityDao

    companion object {
        @Volatile
        private var INSTANCE: TabSSHDatabase? = null

        const val DATABASE_NAME = "tabssh_database"

        /**
         * v3 → v4: add `vnc_hosts.keep_alive_in_background` (per-host opt-in to
         * keep the VNC session alive while the app is backgrounded). Additive
         * ADD COLUMN with a NOT NULL DEFAULT 0 — no data transform, existing
         * rows default to the current drop-on-background behavior.
         *
         * NOTE: this migration is not yet covered by a MigrationTestHelper test;
         * add one (v3 → v4) so the schema change is verified on real upgrades.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE vnc_hosts ADD COLUMN keep_alive_in_background " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getDatabase(context: Context): TabSSHDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TabSSHDatabase::class.java,
                    DATABASE_NAME
                )
                .addCallback(DatabaseCallback())
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Pre-v3 alpha installs were wiped on every upgrade — see kdoc.
                // Real migrations must be added here for every bump from v3 onward.
                .fallbackToDestructiveMigrationFrom(1, 2)
                .addMigrations(MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Enable FK enforcement so CASCADE deletes on TabSession and AuditLogEntry
                // (declared via @ForeignKey) actually fire at the SQLite level.
                // Safe for existing rows — FK checks only run on new writes, not on reads.
                db.execSQL("PRAGMA foreign_keys = ON")
            }
        }

        // Requires explicit opt-in to prevent accidental wipes.
        // Call sites must pass confirmed = true after showing the user a
        // destructive-action confirmation dialog.
        suspend fun clearAllData(context: Context, confirmed: Boolean = false) {
            require(confirmed) { "clearAllData called without confirmation — pass confirmed = true" }
            val db = getDatabase(context)
            db.clearAllTables()
        }
    }

    suspend fun performMaintenance() {
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        tabSessionDao().deleteOldInactiveSessions(cutoffTime)
        openHelper.writableDatabase.execSQL("VACUUM")
        Logger.d("TabSSHDatabase", "Database maintenance completed")
    }

    suspend fun getDatabaseStats(): DatabaseStats {
        return DatabaseStats(
            connectionCount = connectionDao().getConnectionCount(),
            keyCount = keyDao().getKeyCount(),
            hostKeyCount = hostKeyDao().getHostKeyCount(),
            sessionCount = tabSessionDao().getActiveSessionCount(),
            themeCount = themeDao().getThemeCount()
        )
    }
}

data class DatabaseStats(
    val connectionCount: Int = 0,
    val keyCount: Int = 0,
    val hostKeyCount: Int = 0,
    val sessionCount: Int = 0,
    val themeCount: Int = 0
)

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
 * Current version: 6.
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
        VncIdentity::class,
        SyncTombstone::class,
        SyncShadow::class
    ],
    version = 6,
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
    abstract fun syncTombstoneDao(): SyncTombstoneDao
    abstract fun syncShadowDao(): SyncShadowDao

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

        /**
         * v4 → v5 (H6 — soft-delete tombstones): add two additive tables so
         * deletions propagate across devices instead of being resurrected by the
         * next peer upload.
         *
         * `sync_tombstones` records deleted synced entities (type + stable key +
         * deletedAt + deviceId) and travels in the sync payload. `sync_shadow`
         * is local-only bookkeeping for the diff-at-collect backstop. Both are
         * new tables — no existing row is touched and no existing query changes.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                        "`entity_type` TEXT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, " +
                        "`deleted_at` INTEGER NOT NULL, " +
                        "`device_id` TEXT NOT NULL, " +
                        "PRIMARY KEY(`entity_type`, `entity_key`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_shadow` (" +
                        "`entity_type` TEXT NOT NULL, " +
                        "`entity_key` TEXT NOT NULL, " +
                        "PRIMARY KEY(`entity_type`, `entity_key`))"
                )
            }
        }

        /**
         * v5 → v6 (VNC-tab-swipe integration, step 1 — see TODO.AI.md): let a
         * `tab_sessions` row represent either an SSH tab or a VNC tab.
         *
         * `connection_id` was `NOT NULL` with a CASCADE FK to `connections`;
         * SQLite can't ALTER a column to nullable or add a FK in place, so the
         * table is rebuilt: new nullable `connection_id`, new nullable
         * `vnc_host_id` FK to `vnc_hosts`, new `tab_kind` discriminator
         * (`'SSH'`/`'VNC'`). Every existing row is SSH-only (VNC tabs didn't
         * exist before this migration), so the copy sets `tab_kind = 'SSH'`
         * and `vnc_host_id = NULL` unconditionally — no data loss, no
         * ambiguity to resolve.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tab_sessions_new` (" +
                        "`session_id` TEXT NOT NULL, `tab_id` TEXT NOT NULL, " +
                        "`connection_id` TEXT, `vnc_host_id` TEXT, " +
                        "`tab_kind` TEXT NOT NULL DEFAULT 'SSH', `title` TEXT NOT NULL, " +
                        "`is_active` INTEGER NOT NULL, `terminal_content` TEXT NOT NULL, " +
                        "`cursor_row` INTEGER NOT NULL, `cursor_col` INTEGER NOT NULL, " +
                        "`scroll_position` INTEGER NOT NULL, `working_directory` TEXT NOT NULL, " +
                        "`environment_vars` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "`last_activity` INTEGER NOT NULL, `session_state` TEXT NOT NULL, " +
                        "`terminal_rows` INTEGER NOT NULL, `terminal_cols` INTEGER NOT NULL, " +
                        "`font_size` REAL NOT NULL, `connection_state` TEXT NOT NULL, " +
                        "`last_error` TEXT, `has_unread_output` INTEGER NOT NULL, " +
                        "`unread_lines` INTEGER NOT NULL, `tab_order` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`session_id`), " +
                        "FOREIGN KEY(`connection_id`) REFERENCES `connections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`vnc_host_id`) REFERENCES `vnc_hosts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `tab_sessions_new` (" +
                        "session_id, tab_id, connection_id, vnc_host_id, tab_kind, title, " +
                        "is_active, terminal_content, cursor_row, cursor_col, scroll_position, " +
                        "working_directory, environment_vars, created_at, last_activity, " +
                        "session_state, terminal_rows, terminal_cols, font_size, " +
                        "connection_state, last_error, has_unread_output, unread_lines, tab_order) " +
                        "SELECT session_id, tab_id, connection_id, NULL, 'SSH', title, " +
                        "is_active, terminal_content, cursor_row, cursor_col, scroll_position, " +
                        "working_directory, environment_vars, created_at, last_activity, " +
                        "session_state, terminal_rows, terminal_cols, font_size, " +
                        "connection_state, last_error, has_unread_output, unread_lines, tab_order " +
                        "FROM `tab_sessions`"
                )
                db.execSQL("DROP TABLE `tab_sessions`")
                db.execSQL("ALTER TABLE `tab_sessions_new` RENAME TO `tab_sessions`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tab_sessions_connection_id` ON `tab_sessions` (`connection_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tab_sessions_vnc_host_id` ON `tab_sessions` (`vnc_host_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tab_sessions_tab_id` ON `tab_sessions` (`tab_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tab_sessions_is_active` ON `tab_sessions` (`is_active`)")
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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

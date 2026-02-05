package io.github.tabssh.storage.database

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.tabssh.storage.database.dao.*
import io.github.tabssh.storage.database.entities.*
import io.github.tabssh.utils.logging.Logger

/**
 * Main Room database for TabSSH
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
        HypervisorProfile::class
    ],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class TabSSHDatabase : RoomDatabase() {
    
    abstract fun connectionDao(): ConnectionDao
    abstract fun keyDao(): KeyDao
    abstract fun hostKeyDao(): HostKeyDao
    abstract fun tabSessionDao(): TabSessionDao
    abstract fun themeDao(): ThemeDao
    abstract fun trustedCertDao(): TrustedCertDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun snippetDao(): SnippetDao
    abstract fun identityDao(): IdentityDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun hypervisorDao(): HypervisorDao
    
    abstract fun hostKeyDao(): HostKeyDao
    abstract fun tabSessionDao(): TabSessionDao
    abstract fun themeDao(): ThemeDao
    abstract fun certificateDao(): CertificateDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun connectionGroupDao(): ConnectionGroupDao
    abstract fun snippetDao(): SnippetDao
    abstract fun identityDao(): IdentityDao
    abstract fun auditLogDao(): AuditLogDao
    
    companion object {
        @Volatile
        private var INSTANCE: TabSSHDatabase? = null

        const val DATABASE_NAME = "tabssh_database"

        /**
         * Migration from version 1 to 2: Add sync support
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add sync columns to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN last_synced_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE connections ADD COLUMN sync_version INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE connections ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE connections ADD COLUMN sync_device_id TEXT NOT NULL DEFAULT ''")

                // Add sync columns to stored_keys table
                database.execSQL("ALTER TABLE stored_keys ADD COLUMN last_synced_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE stored_keys ADD COLUMN sync_version INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE stored_keys ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE stored_keys ADD COLUMN sync_device_id TEXT NOT NULL DEFAULT ''")

                // Add sync columns to themes table
                database.execSQL("ALTER TABLE themes ADD COLUMN last_synced_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE themes ADD COLUMN sync_version INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE themes ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE themes ADD COLUMN sync_device_id TEXT NOT NULL DEFAULT ''")

                // Add sync columns to host_keys table
                database.execSQL("ALTER TABLE host_keys ADD COLUMN last_synced_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE host_keys ADD COLUMN sync_version INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE host_keys ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE host_keys ADD COLUMN sync_device_id TEXT NOT NULL DEFAULT ''")

                // Create sync_state table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_state (
                        id TEXT PRIMARY KEY NOT NULL,
                        entity_type TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        last_synced_at INTEGER NOT NULL DEFAULT 0,
                        sync_version INTEGER NOT NULL DEFAULT 0,
                        device_id TEXT NOT NULL,
                        sync_hash TEXT NOT NULL,
                        conflict_status TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        UNIQUE(entity_type, entity_id)
                    )
                """.trimIndent())

                // Create indexes for better query performance
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state_type_time ON sync_state(entity_type, last_synced_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state_conflicts ON sync_state(conflict_status) WHERE conflict_status IS NOT NULL")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_connections_modified ON connections(modified_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_keys_modified ON stored_keys(modified_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_themes_modified ON themes(modified_at)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_host_keys_modified ON host_keys(modified_at)")

                Logger.d("TabSSHDatabase", "Migration from version 1 to 2 completed successfully")
            }
        }

        /**
         * Migration from version 2 to 3: Add connection groups/folders
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create connection_groups table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS connection_groups (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        parent_id TEXT,
                        icon TEXT,
                        color TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        is_collapsed INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        modified_at INTEGER NOT NULL DEFAULT 0,
                        last_synced_at INTEGER NOT NULL DEFAULT 0,
                        sync_version INTEGER NOT NULL DEFAULT 0,
                        sync_device_id TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                // Create indexes for better query performance
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_parent ON connection_groups(parent_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_groups_sort ON connection_groups(sort_order)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_connections_group ON connections(group_id)")

                Logger.d("TabSSHDatabase", "Migration from version 2 to 3 completed successfully")
            }
        }

        /**
         * Migration from version 3 to 4: Add snippets library
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create snippets table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS snippets (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        command TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT 'General',
                        tags TEXT NOT NULL DEFAULT '',
                        usage_count INTEGER NOT NULL DEFAULT 0,
                        is_favorite INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        modified_at INTEGER NOT NULL DEFAULT 0,
                        last_synced_at INTEGER NOT NULL DEFAULT 0,
                        sync_version INTEGER NOT NULL DEFAULT 0,
                        sync_device_id TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())

                // Create indexes for better query performance
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_snippets_category ON snippets(category)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_snippets_favorite ON snippets(is_favorite) WHERE is_favorite = 1")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_snippets_usage ON snippets(usage_count)")

                Logger.d("TabSSHDatabase", "Migration from version 3 to 4 completed successfully")
            }
        }

        /**
         * Migration from version 4 to 5: Add proxy/jump host support
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add proxy/jump host fields to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN proxy_username TEXT")
                database.execSQL("ALTER TABLE connections ADD COLUMN proxy_auth_type TEXT")
                database.execSQL("ALTER TABLE connections ADD COLUMN proxy_key_id TEXT")

                Logger.d("TabSSHDatabase", "Migration from version 4 to 5 completed successfully")
            }
        }
        
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create identities table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS identities (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        username TEXT NOT NULL,
                        auth_type TEXT NOT NULL,
                        key_id TEXT,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL,
                        last_synced_at INTEGER,
                        sync_version INTEGER NOT NULL DEFAULT 1,
                        sync_device_id TEXT
                    )
                """.trimIndent())
                
                // Add identity_id field to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN identity_id TEXT")
                
                Logger.d("TabSSHDatabase", "Migration from version 5 to 6 completed successfully")
            }
        }
        
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add X11 forwarding field to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN x11_forwarding INTEGER NOT NULL DEFAULT 0")
                
                Logger.d("TabSSHDatabase", "Migration from version 6 to 7 completed successfully")
            }
        }
        
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create audit log table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id TEXT PRIMARY KEY NOT NULL,
                        connection_id TEXT NOT NULL,
                        session_id TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        event_type TEXT NOT NULL,
                        command TEXT,
                        output TEXT,
                        exit_code INTEGER,
                        user TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        size_bytes INTEGER NOT NULL DEFAULT 0,
                        metadata TEXT,
                        FOREIGN KEY(connection_id) REFERENCES connections(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                
                // Create indexes for performance
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_connection ON audit_log(connection_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log(timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_audit_log_session ON audit_log(session_id)")
                
                Logger.d("TabSSHDatabase", "Migration from version 7 to 8 completed successfully")
            }
        }
        
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add port knock fields to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN port_knock_enabled INTEGER") // null = use global
                database.execSQL("ALTER TABLE connections ADD COLUMN port_knock_sequence TEXT")
                database.execSQL("ALTER TABLE connections ADD COLUMN port_knock_delay_ms INTEGER NOT NULL DEFAULT 100")
                
                Logger.d("TabSSHDatabase", "Migration from version 8 to 9 completed successfully")
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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Database callback for initialization
         */
        private class DatabaseCallback : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                // Database created - will populate with built-in themes via ThemeManager
            }
            
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Database opened - perform any maintenance
            }
        }
        
        /**
         * Clear all data (for testing or factory reset)
         */
        suspend fun clearAllData(context: Context) {
            val db = getDatabase(context)
            db.clearAllTables()
        }
        
        /**
         * Export database for backup
         */
        fun exportDatabase(context: Context): String? {
            return try {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (dbFile.exists()) {
                    dbFile.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    /**
     * Perform database maintenance
     */
    suspend fun performMaintenance() {
        // Clean up old inactive sessions (older than 30 days)
        val cutoffTime = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        tabSessionDao().deleteOldInactiveSessions(cutoffTime)

        // Vacuum database to reclaim space
        openHelper.writableDatabase.execSQL("VACUUM")

        Logger.d("TabSSHDatabase", "Database maintenance completed")
    }
    
    /**
     * Get database statistics
     */
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

/**
 * Database statistics data class
 */
data class DatabaseStats(
    val connectionCount: Int,
    val keyCount: Int,
    val hostKeyCount: Int,
    val sessionCount: Int,
    val themeCount: Int
)

/**
 * Type converters for Room database
 */

        // Migration 9 -> 10: Add hypervisor management
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create hypervisors table
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS hypervisors (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        password TEXT NOT NULL,
                        realm TEXT,
                        verify_ssl INTEGER NOT NULL DEFAULT 0,
                        notes TEXT,
                        last_connected INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
                
                Logger.i("Database", "Migration 9->10: Added hypervisors table")
            }
        }

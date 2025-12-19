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
        SyncState::class
    ],
    version = 2,
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

        fun getDatabase(context: Context): TabSSHDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TabSSHDatabase::class.java,
                    DATABASE_NAME
                )
                .addCallback(DatabaseCallback())
                .addMigrations(
                    MIGRATION_1_2
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

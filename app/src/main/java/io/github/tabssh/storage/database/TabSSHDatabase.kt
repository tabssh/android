package io.github.tabssh.storage.database

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.tabssh.storage.database.dao.*
import io.github.tabssh.storage.database.entities.*
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.utils.logging.Logger

/**
 * Main Room database for TabSSH.
 *
 * Pre-release: version is 1. No migration chain — any existing alpha install
 * is wiped via fallbackToDestructiveMigration().
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
    version = 3,
    exportSchema = false
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

        fun getDatabase(context: Context): TabSSHDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TabSSHDatabase::class.java,
                    DATABASE_NAME
                )
                .addCallback(DatabaseCallback())
                .fallbackToDestructiveMigration(dropAllTables = true)
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
            }
        }

        suspend fun clearAllData(context: Context) {
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

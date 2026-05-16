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
        HypervisorProfile::class,
        Workspace::class,
        CloudAccount::class,
        Macro::class,
        io.github.tabssh.storage.database.entities.HypervisorAccount::class,
        MonitorSlot::class
    ],
    version = 33,
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
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                    MIGRATION_27_28,
                    MIGRATION_28_29,
                    MIGRATION_29_30,
                    MIGRATION_30_31,
                    MIGRATION_31_32,
                    MIGRATION_32_33
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
        
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add use_mosh column to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN use_mosh INTEGER NOT NULL DEFAULT 0")
                Logger.i("Database", "Migration 10->11: Added use_mosh field to connections")
            }
        }
        
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add is_xen_orchestra column to hypervisors table
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN is_xen_orchestra INTEGER NOT NULL DEFAULT 0")
                Logger.i("Database", "Migration 11->12: Added is_xen_orchestra field to hypervisors")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add multiplexer support fields to connections table
                database.execSQL("ALTER TABLE connections ADD COLUMN multiplexer_mode TEXT NOT NULL DEFAULT 'OFF'")
                database.execSQL("ALTER TABLE connections ADD COLUMN multiplexer_session_name TEXT")
                Logger.i("Database", "Migration 12->13: Added multiplexer_mode and multiplexer_session_name fields to connections")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add post-connect script and font size override fields
                database.execSQL("ALTER TABLE connections ADD COLUMN post_connect_script TEXT")
                database.execSQL("ALTER TABLE connections ADD COLUMN font_size_override INTEGER")
                Logger.i("Database", "Migration 13->14: Added post_connect_script and font_size_override fields to connections")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add linked_connection_id field to hypervisors table for importing existing hosts
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN linked_connection_id TEXT")
                Logger.i("Database", "Migration 14->15: Added linked_connection_id field to hypervisors")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add password field to identities table for storing credentials
                database.execSQL("ALTER TABLE identities ADD COLUMN password TEXT")
                Logger.i("Database", "Migration 15->16: Added password field to identities")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add API type override field to hypervisors table
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN api_type_override TEXT NOT NULL DEFAULT 'auto'")
                Logger.i("Database", "Migration 16->17: Added api_type_override field to hypervisors")
            }
        }

        /**
         * v17 → v18 — Wave 1 parity batch.
         * - env_vars: per-connection environment variables (multi-line KEY=value)
         * - agent_forwarding: per-connection SSH agent forwarding toggle
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE connections ADD COLUMN env_vars TEXT")
                database.execSQL("ALTER TABLE connections ADD COLUMN agent_forwarding INTEGER NOT NULL DEFAULT 0")
                Logger.i("Database", "Migration 17->18: Added env_vars + agent_forwarding to connections")
            }
        }

        /**
         * v18 → v19 — Wave 2.2 SSH certificate auth.
         * - certificate: OpenSSH user cert (full *-cert.pub line). Optional.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE stored_keys ADD COLUMN certificate TEXT")
                Logger.i("Database", "Migration 18->19: Added certificate to stored_keys")
            }
        }

        /**
         * v19 → v20 — Wave 2.3 Telnet protocol.
         * - protocol: "ssh" (default) or "telnet" on connections.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE connections ADD COLUMN protocol TEXT NOT NULL DEFAULT 'ssh'")
                Logger.i("Database", "Migration 19->20: Added protocol to connections")
            }
        }

        /**
         * v21 → v22 — Wave 3.1 Per-host color tags.
         * - color_tag: ARGB int on connections (0 = no tag).
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE connections ADD COLUMN color_tag INTEGER NOT NULL DEFAULT 0")
                Logger.i("Database", "Migration 21->22: Added color_tag to connections")
            }
        }

        /**
         * v23 → v24 — Issue #37 RemoteCommand support.
         * - `connections.remote_command TEXT` — when non-NULL, the connection
         *   opens a `ChannelExec` with this command (PTY allocated) instead
         *   of `ChannelShell`. Lets hosts like shell.sourceforge.net that
         *   need an explicit `create` to spawn a shell actually work.
         * - Existing rows get NULL → keeps current behaviour (login shell).
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE connections ADD COLUMN remote_command TEXT")
                Logger.i("Database", "Migration 23->24: Added remote_command to connections")
            }
        }

        /** v24 → v25 — Issue #6: per-host IP mode (auto/ipv4/ipv6). */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE connections ADD COLUMN ip_mode TEXT NOT NULL DEFAULT 'auto'"
                )
                Logger.i("Database", "Migration 24->25: Added ip_mode to connections")
            }
        }

        /** v25 → v26 — Issue #173: recordable macros table. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS macros (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        sequence_b64 TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL,
                        usage_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                Logger.i("Database", "Migration 25->26: Added macros table")
            }
        }

        /**
         * v26 → v27 — Reusable hypervisor accounts.
         *
         * New `hypervisor_accounts` table: shared username/realm
         * credentials that multiple `HypervisorProfile` rows can point
         * at (the new `hypervisors.account_id` FK column). Password
         * lives in `SecurePasswordManager` under
         * `hypervisor_account_${id}`, NOT in this table — same pattern
         * as `cloud_accounts` (cloud_token_${id}) and the per-host
         * `hypervisor_${id}` introduced in commit `ae2c613a`.
         *
         * `hypervisors.account_id` is nullable; existing rows migrate as
         * NULL and keep using their inline `username` + Keystore
         * password. Switching a host to use an account is opt-in via
         * the dropdown in HypervisorEditActivity.
         */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hypervisor_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        username TEXT NOT NULL,
                        realm TEXT,
                        created_at INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "ALTER TABLE hypervisors ADD COLUMN account_id INTEGER"
                )
                Logger.i("Database", "Migration 26->27: Added hypervisor_accounts + hypervisors.account_id")
            }
        }

        /**
         * v27 → v28 — Phase 1 hypervisor cert pinning. Adds the
         * `pinned_cert_sha256` column on `hypervisors`. NULL on every
         * existing row → next connect with verifySsl=true captures
         * (TOFU) and persists; subsequent connects enforce match via
         * HypervisorTrustManagerFactory.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE hypervisors ADD COLUMN pinned_cert_sha256 TEXT"
                )
                Logger.i("Database", "Migration 27->28: Added hypervisors.pinned_cert_sha256")
            }
        }

        /**
         * v28 → v29 — OCI hypervisor support (Phase 1, data layer only).
         * Purely additive: 1 discriminator column (`auth_type`, defaulted to
         * "password" so every existing row keeps its current semantics) and
         * 5 nullable OCI-only columns. Existing rows look identical to
         * pre-migration; no UI exposes OCI yet (Phase 6 will).
         *
         * Secrets (PEM private key + optional passphrase) live in
         * SecurePasswordManager under `oci_private_key_${id}` /
         * `oci_passphrase_${id}` — never in this table.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE hypervisors ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'password'"
                )
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN oci_tenancy_ocid TEXT")
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN oci_user_ocid TEXT")
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN oci_region TEXT")
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN oci_fingerprint TEXT")
                database.execSQL("ALTER TABLE hypervisors ADD COLUMN oci_compartment_ocid TEXT")
                Logger.i("Database", "Migration 28->29: Added auth_type + 5 OCI columns to hypervisors")
            }
        }

        /**
         * v29 → v30 — Per-host notification alert prefs. Two new columns
         * on `connections` for sound + vibrate modes (0=NEVER, 1=ALWAYS,
         * 2=ON_ERROR). The persistent per-host status notification always
         * stays on the silent channel; these prefs gate a separate one-shot
         * alert on `ssh_alerts` channel when the matching state event fires
         * (default disconnect, error exit, etc.).
         */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE connections ADD COLUMN notif_sound_mode INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE connections ADD COLUMN notif_vibrate_mode INTEGER NOT NULL DEFAULT 0"
                )
                Logger.i("Database", "Migration 29->30: Added notif_sound_mode + notif_vibrate_mode to connections")
            }
        }

        /**
         * v30 → v31 — OCI instance SSH binding.
         * Adds `oci_instance_id` to `connections` so the OCI Manager can store
         * and retrieve per-instance SSH settings (username, port, auth method,
         * key) without requiring the user to reconfigure on every connect.
         */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE connections ADD COLUMN oci_instance_id TEXT"
                )
                Logger.i("Database", "Migration 30->31: Added oci_instance_id to connections")
            }
        }

        /**
         * v31 → v32 — Background host monitoring (MonitorSlot).
         * - New `monitor_slots` table: per-host TCP availability + metric
         *   threshold configuration and runtime state.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS monitor_slots (
                        id TEXT NOT NULL PRIMARY KEY,
                        connection_id TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        alert_on_down INTEGER NOT NULL DEFAULT 1,
                        alert_on_recovery INTEGER NOT NULL DEFAULT 1,
                        cpu_threshold INTEGER,
                        memory_threshold INTEGER,
                        disk_threshold INTEGER,
                        load_threshold REAL,
                        enable_performance_checks INTEGER NOT NULL DEFAULT 0,
                        check_interval_minutes INTEGER NOT NULL DEFAULT 15,
                        alert_cooldown_minutes INTEGER NOT NULL DEFAULT 60,
                        last_checked_at INTEGER NOT NULL DEFAULT 0,
                        last_seen_up INTEGER NOT NULL DEFAULT 0,
                        last_notified_down_at INTEGER NOT NULL DEFAULT 0,
                        last_notified_up_at INTEGER NOT NULL DEFAULT 0,
                        is_currently_down INTEGER NOT NULL DEFAULT 0,
                        consecutive_failures INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                Logger.i("Database", "Migration 31->32: Created monitor_slots table")
            }
        }

        /**
         * v32 → v33 — OCI credentials move from `hypervisors` to
         * `hypervisor_accounts`.
         *
         * - Adds `auth_type`, `oci_tenancy_ocid`, `oci_user_ocid`,
         *   `oci_region`, `oci_fingerprint`, `oci_compartment_ocid` columns
         *   to `hypervisor_accounts`.
         * - For every existing OCI `hypervisors` row, creates a
         *   `hypervisor_accounts` row with the copied OCI fields and links
         *   the hypervisor to it via `account_id`.
         * - Keystore entries (`oci_private_key_${profileId}`) migrate lazily
         *   on first access via `HypervisorPasswordStore.retrieveOciAccountKey`.
         * - The deprecated OCI columns on `hypervisors` are left in place
         *   (SQLite < 3.35 does not support DROP COLUMN) but are no longer
         *   the source of truth after this migration.
         */
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Step 1 — add OCI credential columns to hypervisor_accounts.
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN auth_type TEXT NOT NULL DEFAULT 'password'"
                )
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN oci_tenancy_ocid TEXT"
                )
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN oci_user_ocid TEXT"
                )
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN oci_region TEXT"
                )
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN oci_fingerprint TEXT"
                )
                database.execSQL(
                    "ALTER TABLE hypervisor_accounts ADD COLUMN oci_compartment_ocid TEXT"
                )

                // Step 2 — for every OCI hypervisor profile that has no account
                // linked yet, create a HypervisorAccount row and link it.
                val cursor = database.query(
                    "SELECT id, name, oci_tenancy_ocid, oci_user_ocid, oci_region, " +
                    "oci_fingerprint, oci_compartment_ocid FROM hypervisors " +
                    "WHERE auth_type = 'oci_api_key' AND account_id IS NULL"
                )
                cursor.use {
                    val now = System.currentTimeMillis()
                    while (cursor.moveToNext()) {
                        val profileId = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                        val name      = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "OCI"
                        val tenancy   = cursor.getString(cursor.getColumnIndexOrThrow("oci_tenancy_ocid"))
                        val user      = cursor.getString(cursor.getColumnIndexOrThrow("oci_user_ocid"))
                        val region    = cursor.getString(cursor.getColumnIndexOrThrow("oci_region"))
                        val fp        = cursor.getString(cursor.getColumnIndexOrThrow("oci_fingerprint"))
                        val comp      = cursor.getString(cursor.getColumnIndexOrThrow("oci_compartment_ocid"))
                        database.execSQL(
                            "INSERT INTO hypervisor_accounts " +
                            "(name, username, auth_type, oci_tenancy_ocid, oci_user_ocid, " +
                            " oci_region, oci_fingerprint, oci_compartment_ocid, " +
                            " created_at, modified_at) " +
                            "VALUES (?, '', 'oci_api_key', ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(name, tenancy, user, region, fp, comp, now, now)
                        )
                        val newAccountId = database.query("SELECT last_insert_rowid()").use { c ->
                            c.moveToFirst(); c.getLong(0)
                        }
                        database.execSQL(
                            "UPDATE hypervisors SET account_id = ? WHERE id = ?",
                            arrayOf(newAccountId, profileId)
                        )
                        Logger.i("Database", "Migration 32->33: linked OCI profile $profileId → account $newAccountId")
                    }
                }
                Logger.i("Database", "Migration 32->33: OCI credentials promoted to hypervisor_accounts")
            }
        }

        /**
         * v22 → v23 — Wave 5.1 Cloud accounts (DigitalOcean inventory etc.).
         * - new `cloud_accounts` table; tokens encrypted via SecurePasswordManager
         *   under `cloud_token_${id}`, NOT stored in this table.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cloud_accounts (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        last_refresh_at INTEGER NOT NULL DEFAULT 0,
                        last_count INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                Logger.i("Database", "Migration 22->23: Created cloud_accounts table")
            }
        }

        /**
         * v20 → v21 — Wave 2.5 Workspaces (named tab groups).
         * - new `workspaces` table.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS workspaces (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        connection_ids TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        modified_at INTEGER NOT NULL,
                        last_synced_at INTEGER NOT NULL DEFAULT 0,
                        sync_version INTEGER NOT NULL DEFAULT 0,
                        sync_device_id TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
                Logger.i("Database", "Migration 20->21: Created workspaces table")
            }
        }

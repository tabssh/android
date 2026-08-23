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
 * Current version: 21.
 * Versions 1 and 2 never shipped to real users, so v3 is the effective schema
 * baseline and no fallback path exists for them. Every version bump from v3
 * onward MUST register a real Migration object via addMigrations(); destructive
 * fallbacks are forbidden in every variant.
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
        SyncShadow::class,
        PortForward::class,
        ContainerHost::class,
        ComposeStack::class,
        SingleContainerConfig::class,
        ContainerAutoUpdatePolicy::class,
        RegistryCredential::class,
        NetworkRoute::class,
        PendingSyncConflict::class,
        SyncLogEntry::class,
        Domain::class,
        VpsHost::class,
        PaneGroup::class,
        ConnectableHost::class
    ],
    version = 22,
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
    abstract fun portForwardDao(): PortForwardDao
    abstract fun containerHostDao(): ContainerHostDao
    abstract fun composeStackDao(): ComposeStackDao
    abstract fun singleContainerConfigDao(): SingleContainerConfigDao
    abstract fun containerAutoUpdatePolicyDao(): ContainerAutoUpdatePolicyDao
    abstract fun registryCredentialDao(): RegistryCredentialDao
    abstract fun networkRouteDao(): NetworkRouteDao
    abstract fun pendingSyncConflictDao(): PendingSyncConflictDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun domainDao(): DomainDao
    abstract fun vpsHostDao(): VpsHostDao
    abstract fun paneGroupDao(): PaneGroupDao
    abstract fun connectableHostDao(): ConnectableHostDao

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

        /**
         * v6 → v7: add `connections.multiplexer_override` — per-connection
         * PRE-key multiplexer pin set via the long-press picker (null = auto,
         * "tmux"/"screen"/"zellij" = pinned type, "off" = PRE key disabled).
         * Additive nullable ADD COLUMN, no data transform; existing rows
         * default to NULL (auto-detect, the pre-v7 behavior).
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE connections ADD COLUMN multiplexer_override TEXT"
                )
            }
        }

        /**
         * v7 → v8: add the `port_forwards` table — saved, persistent SSH
         * port-forward rules (standalone feature). New table only; no data
         * transform. DDL matches Room's generated schema for PortForward
         * exactly (column order, NOT NULL flags, and the two indices).
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `port_forwards` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`connection_id` TEXT, " +
                        "`ssh_host` TEXT, " +
                        "`ssh_port` INTEGER NOT NULL, " +
                        "`ssh_username` TEXT, " +
                        "`identity_id` TEXT, " +
                        "`host_ip` TEXT NOT NULL, " +
                        "`remote_port` INTEGER NOT NULL, " +
                        "`local_port` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`auto_start` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_port_forwards_connection_id` " +
                        "ON `port_forwards` (`connection_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_port_forwards_identity_id` " +
                        "ON `port_forwards` (`identity_id`)"
                )
            }
        }

        /**
         * v8 → v9: add the five Docker-feature tables (docker_hosts,
         * compose_stacks, single_container_configs,
         * container_auto_update_policies, registry_credentials) — Docker
         * host management data model. New tables only;
         * no data transform. DDL matches Room's generated schema exactly
         * (column order, NOT NULL flags, and the five indices). All
         * references between the tables are FK-by-convention, mirroring
         * hypervisors.linked_connection_id. registry_credentials has no
         * secret column — secrets live in the Keystore via
         * RegistryCredentialStore.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `docker_hosts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`linked_connection_id` TEXT, " +
                        "`socket_path` TEXT NOT NULL, " +
                        "`transport_mode` TEXT NOT NULL, " +
                        "`docker_cli_path` TEXT, " +
                        "`compose_invocation` TEXT NOT NULL, " +
                        "`pinned_api_version` TEXT, " +
                        "`compose_base_path` TEXT NOT NULL, " +
                        "`run_config_base_path` TEXT NOT NULL, " +
                        "`notes` TEXT, " +
                        "`last_connected` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_docker_hosts_linked_connection_id` " +
                        "ON `docker_hosts` (`linked_connection_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `compose_stacks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`docker_host_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`remote_path` TEXT NOT NULL, " +
                        "`auto_update_enabled` INTEGER NOT NULL, " +
                        "`last_known_status` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_compose_stacks_docker_host_id` " +
                        "ON `compose_stacks` (`docker_host_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `single_container_configs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`docker_host_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`remote_path` TEXT NOT NULL, " +
                        "`config_format` TEXT NOT NULL, " +
                        "`auto_update_enabled` INTEGER NOT NULL, " +
                        "`last_known_status` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_single_container_configs_docker_host_id` " +
                        "ON `single_container_configs` (`docker_host_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `container_auto_update_policies` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`docker_host_id` INTEGER NOT NULL, " +
                        "`container_name_or_stack_name` TEXT NOT NULL, " +
                        "`scope` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`auto_recreate_on_update` INTEGER NOT NULL, " +
                        "`registry_credential_id` INTEGER, " +
                        "`last_checked_at` INTEGER NOT NULL, " +
                        "`last_digest_seen` TEXT, " +
                        "`pending_update_digest` TEXT)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_container_auto_update_policies_docker_host_id` " +
                        "ON `container_auto_update_policies` (`docker_host_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_container_auto_update_policies_registry_credential_id` " +
                        "ON `container_auto_update_policies` (`registry_credential_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `registry_credentials` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`registry_host` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`auth_type` TEXT NOT NULL)"
                )
            }
        }

        /**
         * v9 → v10: add the six nullable custom-endpoint columns to
         * docker_hosts (custom_host/port/username/auth_type/key_id/
         * identity_id) so a Docker host can use its own SSH endpoint
         * instead of a saved connection. Additive only; no data
         * transform. The custom-endpoint password is Keystore-only
         * (ContainerHostPasswordStore) — no secret column.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_host` TEXT")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_port` INTEGER")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_username` TEXT")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_auth_type` TEXT")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_key_id` TEXT")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `custom_identity_id` TEXT")
            }
        }

        /**
         * v10 → v11: per-host image-update-check override on docker_hosts —
         * update_check_enabled (default on), update_check_interval_hours
         * (NULL = global twice-daily default), and last_update_check (the
         * worker's per-host due-time bookkeeping). Additive only.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `update_check_enabled` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `update_check_interval_hours` INTEGER")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `last_update_check` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v11 → v12 (Routing & Forwarding): add the `network_routes` table —
         * reusable proxy / SSH-jump-host definitions — and a nullable
         * `connections.route_id` FK-by-convention column pointing at a route
         * (null = inherit global default, "DIRECT" = force direct).
         *
         * Additive only. The one-time transform that copies each connection's
         * legacy inline `proxy_*` columns into a generated network_routes row
         * and sets its route_id runs in app code
         * (TabSSHApplication.migrateInlineProxiesToRoutes), mirroring the
         * prefix-key migration pattern — the raw SQL here stays purely
         * schema-level so it needs no in-SQLite UUID generation. DDL matches
         * Room's generated schema for NetworkRoute exactly (column order,
         * NOT NULL flags, and the two indices).
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `network_routes` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`host` TEXT, " +
                        "`port` INTEGER NOT NULL, " +
                        "`username` TEXT, " +
                        "`auth_type` TEXT, " +
                        "`key_id` TEXT, " +
                        "`connection_id` TEXT, " +
                        "`built_in_tor` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_network_routes_connection_id` " +
                        "ON `network_routes` (`connection_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_network_routes_key_id` " +
                        "ON `network_routes` (`key_id`)"
                )
                db.execSQL("ALTER TABLE `connections` ADD COLUMN `route_id` TEXT")
            }
        }

        /**
         * v12 -> v13: adds `modified_at` (sync last-write-wins timestamp) to
         * the eight entities that didn't already have one, plus the two new
         * tables backing real conflict detection and a dedicated Sync Log
         * (`pending_sync_conflicts`, `sync_log`). DDL for the new tables
         * matches Room's generated schema for [PendingSyncConflict] and
         * [SyncLogEntry] exactly (column order, NOT NULL flags, indices).
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `hypervisors` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `trusted_certificates` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `monitor_slots` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `docker_hosts` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `registry_credentials` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `compose_stacks` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `single_container_configs` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `container_auto_update_policies` ADD COLUMN `modified_at` INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_sync_conflicts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`entity_type` TEXT NOT NULL, " +
                        "`entity_id` TEXT NOT NULL, " +
                        "`conflict_type` TEXT NOT NULL, " +
                        "`field` TEXT, " +
                        "`local_value_text` TEXT, " +
                        "`remote_value_text` TEXT, " +
                        "`local_entity_json` TEXT, " +
                        "`remote_entity_json` TEXT, " +
                        "`local_timestamp` INTEGER NOT NULL, " +
                        "`remote_timestamp` INTEGER NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_sync_conflicts_entity_type` " +
                        "ON `pending_sync_conflicts` (`entity_type`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_pending_sync_conflicts_created_at` " +
                        "ON `pending_sync_conflicts` (`created_at`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_log` (" +
                        "`id` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`device_id` TEXT NOT NULL, " +
                        "`device_name` TEXT NOT NULL, " +
                        "`entity_type` TEXT NOT NULL, " +
                        "`entity_id` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`resolution` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_log_timestamp` " +
                        "ON `sync_log` (`timestamp`)"
                )
            }
        }

        /**
         * Name of the transient staging table MIGRATION_13_14 hands the legacy
         * `hypervisors.password` values to. Not a Room entity — it exists only
         * on databases that actually carried plaintext across the v14 upgrade,
         * and `HypervisorPasswordStore.sweepLegacyPlaintext` drops it as soon
         * as the last row has been moved into the Keystore.
         */
        const val HYPERVISOR_PASSWORD_CARRYOVER_TABLE = "hypervisor_password_carryover"

        /**
         * v13 -> v14: removes the plaintext `hypervisors.password` column
         * (PART 0/PART 6: "DB columns never hold secrets").
         *
         * SQLite before 3.35 has no `DROP COLUMN` and minSdk is 24, so the
         * table is recreated: new table without the column, copy every other
         * column verbatim, drop, rename, recreate both indices. The recreated
         * DDL matches Room's generated schema for [HypervisorProfile] exactly.
         *
         * A Room migration cannot reach the Android Keystore (no Context, and
         * a Keystore failure inside the migration transaction would leave the
         * database unopenable). So the migration does not destroy the secrets
         * it removes: every still-plaintext value is first copied into
         * [HYPERVISOR_PASSWORD_CARRYOVER_TABLE], and
         * `HypervisorPasswordStore.sweepLegacyPlaintext` — already invoked
         * during application startup — moves each row into the Keystore and
         * deletes it, dropping the table once empty. A row whose Keystore
         * write fails simply survives to the next attempt, so no stored
         * hypervisor password can be lost.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `$HYPERVISOR_PASSWORD_CARRYOVER_TABLE` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`password` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO `$HYPERVISOR_PASSWORD_CARRYOVER_TABLE` (`id`, `password`) " +
                        "SELECT `id`, `password` FROM `hypervisors` " +
                        "WHERE `password` IS NOT NULL AND `password` != ''"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `hypervisors_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`host` TEXT NOT NULL, " +
                        "`port` INTEGER NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`realm` TEXT, " +
                        "`verify_ssl` INTEGER NOT NULL, " +
                        "`pinned_cert_sha256` TEXT, " +
                        "`api_type_override` TEXT NOT NULL, " +
                        "`linked_connection_id` TEXT, " +
                        "`account_id` INTEGER, " +
                        "`notes` TEXT, " +
                        "`last_connected` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`auth_type` TEXT NOT NULL, " +
                        "`oci_tenancy_ocid` TEXT, " +
                        "`oci_user_ocid` TEXT, " +
                        "`oci_region` TEXT, " +
                        "`oci_fingerprint` TEXT, " +
                        "`oci_compartment_ocid` TEXT, " +
                        "`ssh_identity_id` TEXT, " +
                        "`modified_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `hypervisors_new` (" +
                        "`id`, `name`, `type`, `host`, `port`, `username`, `realm`, `verify_ssl`, " +
                        "`pinned_cert_sha256`, `api_type_override`, `linked_connection_id`, " +
                        "`account_id`, `notes`, `last_connected`, `created_at`, `auth_type`, " +
                        "`oci_tenancy_ocid`, `oci_user_ocid`, `oci_region`, `oci_fingerprint`, " +
                        "`oci_compartment_ocid`, `ssh_identity_id`, `modified_at`) " +
                        "SELECT `id`, `name`, `type`, `host`, `port`, `username`, `realm`, `verify_ssl`, " +
                        "`pinned_cert_sha256`, `api_type_override`, `linked_connection_id`, " +
                        "`account_id`, `notes`, `last_connected`, `created_at`, `auth_type`, " +
                        "`oci_tenancy_ocid`, `oci_user_ocid`, `oci_region`, `oci_fingerprint`, " +
                        "`oci_compartment_ocid`, `ssh_identity_id`, `modified_at` FROM `hypervisors`"
                )
                db.execSQL("DROP TABLE `hypervisors`")
                db.execSQL("ALTER TABLE `hypervisors_new` RENAME TO `hypervisors`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hypervisors_account_id` " +
                        "ON `hypervisors` (`account_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_hypervisors_linked_connection_id` " +
                        "ON `hypervisors` (`linked_connection_id`)"
                )
            }
        }

        /**
         * v14 -> v15: the container feature stops being Docker-only.
         *
         * `docker_hosts` becomes `container_hosts` and gains an `engine`
         * column (`docker`, `incus`, `podman`, `lxd`); `docker_cli_path`
         * becomes `engine_cli_path`; and the three owned tables rename their
         * `docker_host_id` foreign key to `container_host_id`. A host row that
         * runs Incus must not be reachable through a column called
         * `docker_host_id`, so this is a rename, not an alias.
         *
         * minSdk 24 predates `ALTER TABLE ... RENAME COLUMN` (SQLite 3.25), so
         * every rename here is a full table recreate: build the replacement,
         * copy every column by name, drop the original, rename, recreate the
         * indices. Table renames themselves use `ALTER TABLE ... RENAME TO`,
         * which has always existed.
         *
         * `socket_path` keeps whatever the row already had. Existing rows all
         * hold an explicit Docker socket, which stays correct for a Docker
         * host; only newly added hosts get the blank "probe this engine's
         * defaults" value.
         *
         * No secret is touched: `container_hosts` has no secret column, and the
         * Keystore alias rename (`docker_host_{id}` -> `container_host_{id}`)
         * happens outside Room in ContainerHostPasswordStore, which a Room
         * migration cannot reach.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `container_hosts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`linked_connection_id` TEXT, " +
                        "`custom_host` TEXT, " +
                        "`custom_port` INTEGER, " +
                        "`custom_username` TEXT, " +
                        "`custom_auth_type` TEXT, " +
                        "`custom_key_id` TEXT, " +
                        "`custom_identity_id` TEXT, " +
                        "`engine` TEXT NOT NULL DEFAULT 'docker', " +
                        "`socket_path` TEXT NOT NULL DEFAULT '', " +
                        "`transport_mode` TEXT NOT NULL, " +
                        "`engine_cli_path` TEXT, " +
                        "`compose_invocation` TEXT NOT NULL, " +
                        "`pinned_api_version` TEXT, " +
                        "`compose_base_path` TEXT NOT NULL, " +
                        "`run_config_base_path` TEXT NOT NULL, " +
                        "`update_check_enabled` INTEGER NOT NULL DEFAULT 1, " +
                        "`update_check_interval_hours` INTEGER, " +
                        "`last_update_check` INTEGER NOT NULL DEFAULT 0, " +
                        "`notes` TEXT, " +
                        "`last_connected` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `container_hosts` (" +
                        "`id`, `name`, `linked_connection_id`, `custom_host`, `custom_port`, " +
                        "`custom_username`, `custom_auth_type`, `custom_key_id`, " +
                        "`custom_identity_id`, `engine`, `socket_path`, `transport_mode`, " +
                        "`engine_cli_path`, `compose_invocation`, `pinned_api_version`, " +
                        "`compose_base_path`, `run_config_base_path`, `update_check_enabled`, " +
                        "`update_check_interval_hours`, `last_update_check`, `notes`, " +
                        "`last_connected`, `created_at`, `modified_at`) " +
                        "SELECT `id`, `name`, `linked_connection_id`, `custom_host`, `custom_port`, " +
                        "`custom_username`, `custom_auth_type`, `custom_key_id`, " +
                        "`custom_identity_id`, 'docker', `socket_path`, `transport_mode`, " +
                        "`docker_cli_path`, `compose_invocation`, `pinned_api_version`, " +
                        "`compose_base_path`, `run_config_base_path`, `update_check_enabled`, " +
                        "`update_check_interval_hours`, `last_update_check`, `notes`, " +
                        "`last_connected`, `created_at`, `modified_at` FROM `docker_hosts`"
                )
                db.execSQL("DROP TABLE `docker_hosts`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_container_hosts_linked_connection_id` " +
                        "ON `container_hosts` (`linked_connection_id`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `compose_stacks_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`container_host_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`remote_path` TEXT NOT NULL, " +
                        "`auto_update_enabled` INTEGER NOT NULL, " +
                        "`last_known_status` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `compose_stacks_new` (" +
                        "`id`, `container_host_id`, `name`, `remote_path`, `auto_update_enabled`, " +
                        "`last_known_status`, `created_at`, `updated_at`, `modified_at`) " +
                        "SELECT `id`, `docker_host_id`, `name`, `remote_path`, `auto_update_enabled`, " +
                        "`last_known_status`, `created_at`, `updated_at`, `modified_at` " +
                        "FROM `compose_stacks`"
                )
                db.execSQL("DROP TABLE `compose_stacks`")
                db.execSQL("ALTER TABLE `compose_stacks_new` RENAME TO `compose_stacks`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_compose_stacks_container_host_id` " +
                        "ON `compose_stacks` (`container_host_id`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `single_container_configs_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`container_host_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`remote_path` TEXT NOT NULL, " +
                        "`config_format` TEXT NOT NULL, " +
                        "`auto_update_enabled` INTEGER NOT NULL, " +
                        "`last_known_status` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `single_container_configs_new` (" +
                        "`id`, `container_host_id`, `name`, `remote_path`, `config_format`, " +
                        "`auto_update_enabled`, `last_known_status`, `created_at`, `updated_at`, " +
                        "`modified_at`) " +
                        "SELECT `id`, `docker_host_id`, `name`, `remote_path`, `config_format`, " +
                        "`auto_update_enabled`, `last_known_status`, `created_at`, `updated_at`, " +
                        "`modified_at` FROM `single_container_configs`"
                )
                db.execSQL("DROP TABLE `single_container_configs`")
                db.execSQL(
                    "ALTER TABLE `single_container_configs_new` RENAME TO `single_container_configs`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_single_container_configs_container_host_id` " +
                        "ON `single_container_configs` (`container_host_id`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `container_auto_update_policies_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`container_host_id` INTEGER NOT NULL, " +
                        "`container_name_or_stack_name` TEXT NOT NULL, " +
                        "`scope` TEXT NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, " +
                        "`auto_recreate_on_update` INTEGER NOT NULL, " +
                        "`registry_credential_id` INTEGER, " +
                        "`last_checked_at` INTEGER NOT NULL, " +
                        "`last_digest_seen` TEXT, " +
                        "`pending_update_digest` TEXT, " +
                        "`modified_at` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `container_auto_update_policies_new` (" +
                        "`id`, `container_host_id`, `container_name_or_stack_name`, `scope`, " +
                        "`enabled`, `auto_recreate_on_update`, `registry_credential_id`, " +
                        "`last_checked_at`, `last_digest_seen`, `pending_update_digest`, " +
                        "`modified_at`) " +
                        "SELECT `id`, `docker_host_id`, `container_name_or_stack_name`, `scope`, " +
                        "`enabled`, `auto_recreate_on_update`, `registry_credential_id`, " +
                        "`last_checked_at`, `last_digest_seen`, `pending_update_digest`, " +
                        "`modified_at` FROM `container_auto_update_policies`"
                )
                db.execSQL("DROP TABLE `container_auto_update_policies`")
                db.execSQL(
                    "ALTER TABLE `container_auto_update_policies_new` " +
                        "RENAME TO `container_auto_update_policies`"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_container_auto_update_policies_container_host_id` " +
                        "ON `container_auto_update_policies` (`container_host_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_container_auto_update_policies_registry_credential_id` " +
                        "ON `container_auto_update_policies` (`registry_credential_id`)"
                )
            }
        }

        /**
         * v15 -> v16: add the `domains` and `vps_hosts` tables for the Domain
         * Tracker and VPS Hosting Tracker features. Both are additive-only
         * new tables — no existing table is touched.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `domains` (" +
                        "`id` TEXT NOT NULL, " +
                        "`domain_name` TEXT NOT NULL, " +
                        "`privacy_protection` TEXT NOT NULL, " +
                        "`status_at_registrar` TEXT NOT NULL, " +
                        "`auto_renew` TEXT NOT NULL, " +
                        "`expiration_date` INTEGER, " +
                        "`reminder_days_before` INTEGER NOT NULL DEFAULT 7, " +
                        "`last_reminder_sent_at` INTEGER, " +
                        "`notes` TEXT, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vps_hosts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`tenant` TEXT NOT NULL, " +
                        "`hostname` TEXT NOT NULL, " +
                        "`ipv4` TEXT, " +
                        "`ipv6` TEXT, " +
                        "`specs` TEXT, " +
                        "`linked_domain` TEXT, " +
                        "`renewal_raw` TEXT, " +
                        "`renewal_date` INTEGER, " +
                        "`billing_cycle` TEXT, " +
                        "`price` TEXT, " +
                        "`description` TEXT, " +
                        "`reminder_days_before` INTEGER NOT NULL DEFAULT 7, " +
                        "`last_reminder_sent_at` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pane_groups` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`layout` TEXT NOT NULL, " +
                        "`member_host_ids` TEXT NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `connectable_hosts` (" +
                        "`id` TEXT NOT NULL, " +
                        "`source_type` TEXT NOT NULL, " +
                        "`cloud_account_id` TEXT, " +
                        "`instance_id` TEXT, " +
                        "`name` TEXT NOT NULL, " +
                        "`host_preview` TEXT NOT NULL, " +
                        "`protocol` TEXT NOT NULL DEFAULT 'ssh', " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive only — legacy `member_host_ids` stays in place as
                // a compat read path (PaneGroup.resolvedWindows()); no data
                // loss, no destructive migration.
                db.execSQL("ALTER TABLE `pane_groups` ADD COLUMN `windows` TEXT")
            }
        }

        // Fixes a schema bug shipped in MIGRATION_17_18: `windows` was added
        // as a nullable column with no default, but the PaneGroup entity
        // declares it NOT NULL (List<PaneWindowConfig> = emptyList()) — this
        // mismatch fails Room's schema validation with an
        // IllegalStateException on every app launch for any device that
        // already ran 17->18. `sort_order` has the same class of gap: its
        // CREATE TABLE (MIGRATION_16_17) always had `DEFAULT 0`, but the
        // entity never declared a matching @ColumnInfo default. SQLite
        // cannot ALTER a column's NOT NULL/default in place, so this rebuilds
        // `pane_groups` with the corrected schema, preserving every existing
        // row (any null `windows` becomes '[]', matching
        // PaneGroup.resolvedWindows()'s empty-list fallback).
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `pane_groups_new` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`layout` TEXT NOT NULL, " +
                        "`member_host_ids` TEXT NOT NULL, " +
                        "`windows` TEXT NOT NULL DEFAULT '[]', " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `pane_groups_new` " +
                        "(`id`, `name`, `layout`, `member_host_ids`, `windows`, `sort_order`, `created_at`, `modified_at`) " +
                        "SELECT `id`, `name`, `layout`, `member_host_ids`, COALESCE(`windows`, '[]'), `sort_order`, `created_at`, `modified_at` " +
                        "FROM `pane_groups`"
                )
                db.execSQL("DROP TABLE `pane_groups`")
                db.execSQL("ALTER TABLE `pane_groups_new` RENAME TO `pane_groups`")
            }
        }

        // v19 -> v20: no-op at the SQL level. 04304ea27cc8 added @ColumnInfo
        // defaultValue annotations to 7 entities (ConnectableHost, Domain,
        // MonitorSlot, RegistryCredential, TrustedCertificate, VncHost,
        // VpsHost) without bumping the version, on the reasoning that the
        // on-disk defaults already matched. That reasoning was wrong: Room's
        // identity-hash check is computed from the entity annotations
        // themselves, not just the resulting SQL, so any device already on
        // v19 (with the pre-fix identity hash baked in) fails Room's
        // "forgot to update the version number" validation on next launch
        // after updating to a build with the corrected annotations, even
        // though the actual table structure never changed. This migration
        // exists purely to re-anchor the identity hash to a new version
        // number — there is nothing to execute.
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Intentionally empty — see comment above.
            }
        }

        // v20 -> v21: MIGRATION_19_20's "no-op, just re-anchor the identity
        // hash" reasoning turned out to be only half right. On real devices
        // (confirmed via a crash report: "Migration didn't properly handle:
        // trusted_certificates", Expected `modified_at` defaultValue = '0',
        // Found = 'undefined'), the DEFAULT clause on a column added via
        // `ALTER TABLE ... ADD COLUMN ... DEFAULT 0` is not always reflected
        // back by `PRAGMA table_info` on-device, even though the ALTER SQL
        // itself is correct — so the actual on-disk schema for these 7
        // tables (all ALTER-column-added in MIGRATION_12_13/14_15) can
        // genuinely lack the default Room's entity annotations now require,
        // not just carry a stale identity hash. A no-op migration cannot fix
        // a real on-disk mismatch, so each affected table is fully recreated
        // here (SQLite has no ALTER COLUMN, same technique as
        // MIGRATION_13_14): new table with the DEFAULT baked into its CREATE
        // TABLE statement (which PRAGMA table_info always reads correctly,
        // unlike ALTER-added defaults), copy every row verbatim, drop, and
        // rename back. Column lists and indices verified against
        // app/schemas/.../20.json (Room's own exported expected schema) so
        // the recreated DDL matches exactly.
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `trusted_certificates_new` (" +
                        "`id` TEXT NOT NULL, `hostname` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, `algorithm` TEXT NOT NULL, `certificate_data` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, `issuer` TEXT NOT NULL, `serial_number` TEXT NOT NULL, " +
                        "`not_before` INTEGER NOT NULL, `not_after` INTEGER NOT NULL, `expires_at` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `last_used` INTEGER NOT NULL, `trust_level` TEXT NOT NULL, " +
                        "`is_self_signed` INTEGER NOT NULL, `is_ca_signed` INTEGER NOT NULL, `key_usage` TEXT, " +
                        "`extended_key_usage` TEXT, `subject_alternative_names` TEXT, `verification_status` TEXT NOT NULL, " +
                        "`notes` TEXT, `modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `trusted_certificates_new` SELECT " +
                        "`id`, `hostname`, `port`, `fingerprint`, `algorithm`, `certificate_data`, `subject`, " +
                        "`issuer`, `serial_number`, `not_before`, `not_after`, `expires_at`, `created_at`, " +
                        "`last_used`, `trust_level`, `is_self_signed`, `is_ca_signed`, `key_usage`, " +
                        "`extended_key_usage`, `subject_alternative_names`, `verification_status`, `notes`, " +
                        "`modified_at` FROM `trusted_certificates`"
                )
                db.execSQL("DROP TABLE `trusted_certificates`")
                db.execSQL("ALTER TABLE `trusted_certificates_new` RENAME TO `trusted_certificates`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_trusted_certificates_hostname` " +
                        "ON `trusted_certificates` (`hostname`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_certificates_fingerprint` " +
                        "ON `trusted_certificates` (`fingerprint`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_trusted_certificates_hostname_port` " +
                        "ON `trusted_certificates` (`hostname`, `port`)"
                )

                db.execSQL(
                    "CREATE TABLE `monitor_slots_new` (" +
                        "`id` TEXT NOT NULL, `connection_id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                        "`alert_on_down` INTEGER NOT NULL, `alert_on_recovery` INTEGER NOT NULL, " +
                        "`cpu_threshold` INTEGER, `memory_threshold` INTEGER, `disk_threshold` INTEGER, " +
                        "`load_threshold` REAL, `enable_performance_checks` INTEGER NOT NULL, " +
                        "`check_interval_minutes` INTEGER NOT NULL, `alert_cooldown_minutes` INTEGER NOT NULL, " +
                        "`last_checked_at` INTEGER NOT NULL, `last_seen_up` INTEGER NOT NULL, " +
                        "`last_notified_down_at` INTEGER NOT NULL, `last_notified_up_at` INTEGER NOT NULL, " +
                        "`is_currently_down` INTEGER NOT NULL, `consecutive_failures` INTEGER NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `monitor_slots_new` SELECT " +
                        "`id`, `connection_id`, `enabled`, `alert_on_down`, `alert_on_recovery`, `cpu_threshold`, " +
                        "`memory_threshold`, `disk_threshold`, `load_threshold`, `enable_performance_checks`, " +
                        "`check_interval_minutes`, `alert_cooldown_minutes`, `last_checked_at`, `last_seen_up`, " +
                        "`last_notified_down_at`, `last_notified_up_at`, `is_currently_down`, " +
                        "`consecutive_failures`, `modified_at` FROM `monitor_slots`"
                )
                db.execSQL("DROP TABLE `monitor_slots`")
                db.execSQL("ALTER TABLE `monitor_slots_new` RENAME TO `monitor_slots`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_monitor_slots_connection_id` " +
                        "ON `monitor_slots` (`connection_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_monitor_slots_enabled` " +
                        "ON `monitor_slots` (`enabled`)"
                )

                db.execSQL(
                    "CREATE TABLE `vnc_hosts_new` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, " +
                        "`display_number` INTEGER, `identity_id` TEXT, `security_type` TEXT NOT NULL, " +
                        "`tls_verify` INTEGER NOT NULL, `pinned_cert_sha256` TEXT, " +
                        "`keep_alive_in_background` INTEGER NOT NULL DEFAULT 0, `group_id` TEXT, " +
                        "`color_tag` INTEGER NOT NULL, `notes` TEXT, `last_connected` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `vnc_hosts_new` SELECT " +
                        "`id`, `name`, `host`, `port`, `display_number`, `identity_id`, `security_type`, " +
                        "`tls_verify`, `pinned_cert_sha256`, `keep_alive_in_background`, `group_id`, `color_tag`, " +
                        "`notes`, `last_connected`, `created_at`, `modified_at` FROM `vnc_hosts`"
                )
                db.execSQL("DROP TABLE `vnc_hosts`")
                db.execSQL("ALTER TABLE `vnc_hosts_new` RENAME TO `vnc_hosts`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vnc_hosts_identity_id` " +
                        "ON `vnc_hosts` (`identity_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vnc_hosts_group_id` " +
                        "ON `vnc_hosts` (`group_id`)"
                )

                db.execSQL(
                    "CREATE TABLE `registry_credentials_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `registry_host` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, `auth_type` TEXT NOT NULL, " +
                        "`modified_at` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO `registry_credentials_new` " +
                        "(`id`, `registry_host`, `username`, `auth_type`, `modified_at`) SELECT " +
                        "`id`, `registry_host`, `username`, `auth_type`, `modified_at` FROM `registry_credentials`"
                )
                db.execSQL("DROP TABLE `registry_credentials`")
                db.execSQL("ALTER TABLE `registry_credentials_new` RENAME TO `registry_credentials`")

                db.execSQL(
                    "CREATE TABLE `domains_new` (" +
                        "`id` TEXT NOT NULL, `domain_name` TEXT NOT NULL, `privacy_protection` TEXT NOT NULL, " +
                        "`status_at_registrar` TEXT NOT NULL, `auto_renew` TEXT NOT NULL, `expiration_date` INTEGER, " +
                        "`reminder_days_before` INTEGER NOT NULL DEFAULT 7, `last_reminder_sent_at` INTEGER, " +
                        "`notes` TEXT, `created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `domains_new` SELECT " +
                        "`id`, `domain_name`, `privacy_protection`, `status_at_registrar`, `auto_renew`, " +
                        "`expiration_date`, `reminder_days_before`, `last_reminder_sent_at`, `notes`, " +
                        "`created_at`, `modified_at` FROM `domains`"
                )
                db.execSQL("DROP TABLE `domains`")
                db.execSQL("ALTER TABLE `domains_new` RENAME TO `domains`")

                db.execSQL(
                    "CREATE TABLE `vps_hosts_new` (" +
                        "`id` TEXT NOT NULL, `tenant` TEXT NOT NULL, `hostname` TEXT NOT NULL, `ipv4` TEXT, " +
                        "`ipv6` TEXT, `specs` TEXT, `linked_domain` TEXT, `renewal_raw` TEXT, `renewal_date` INTEGER, " +
                        "`billing_cycle` TEXT, `price` TEXT, `description` TEXT, " +
                        "`reminder_days_before` INTEGER NOT NULL DEFAULT 7, `last_reminder_sent_at` INTEGER, " +
                        "`created_at` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `vps_hosts_new` SELECT " +
                        "`id`, `tenant`, `hostname`, `ipv4`, `ipv6`, `specs`, `linked_domain`, `renewal_raw`, " +
                        "`renewal_date`, `billing_cycle`, `price`, `description`, `reminder_days_before`, " +
                        "`last_reminder_sent_at`, `created_at`, `modified_at` FROM `vps_hosts`"
                )
                db.execSQL("DROP TABLE `vps_hosts`")
                db.execSQL("ALTER TABLE `vps_hosts_new` RENAME TO `vps_hosts`")

                db.execSQL(
                    "CREATE TABLE `connectable_hosts_new` (" +
                        "`id` TEXT NOT NULL, `source_type` TEXT NOT NULL, `cloud_account_id` TEXT, " +
                        "`instance_id` TEXT, `name` TEXT NOT NULL, `host_preview` TEXT NOT NULL, " +
                        "`protocol` TEXT NOT NULL DEFAULT 'ssh', `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `connectable_hosts_new` SELECT " +
                        "`id`, `source_type`, `cloud_account_id`, `instance_id`, `name`, `host_preview`, " +
                        "`protocol`, `updated_at` FROM `connectable_hosts`"
                )
                db.execSQL("DROP TABLE `connectable_hosts`")
                db.execSQL("ALTER TABLE `connectable_hosts_new` RENAME TO `connectable_hosts`")
            }
        }

        /**
         * v21 -> v22: adds `PaneGroup.splitDirection` — additive column,
         * consulted only when a group has exactly 2 resolved windows
         * ("horizontal" side-by-side default, or "vertical" stacked).
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `pane_groups` ADD COLUMN `split_direction` TEXT NOT NULL DEFAULT 'horizontal'"
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
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
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

}

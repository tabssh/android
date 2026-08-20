package io.github.tabssh.storage.database

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for MIGRATION_14_15: `docker_hosts` is recreated as `container_hosts` with a new
 * `engine` column (backfilled to 'docker') and `docker_cli_path` renamed to `engine_cli_path`;
 * `compose_stacks`, `single_container_configs` and `container_auto_update_policies` are each
 * recreated with `docker_host_id` renamed to `container_host_id`; every index is recreated
 * under its new name. Mirrors the raw-SQL, migration-object-under-test style of
 * [Migration12To13Test], exercising [TabSSHDatabase.MIGRATION_14_15] directly against a
 * pre-migration schema matching the committed `14.json` export, since no `15.json` schema
 * export exists in this build environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Migration14To15Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_14_15_test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_14_15_test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `docker_hosts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`linked_connection_id` TEXT, " +
                            "`custom_host` TEXT, " +
                            "`custom_port` INTEGER, " +
                            "`custom_username` TEXT, " +
                            "`custom_auth_type` TEXT, " +
                            "`custom_key_id` TEXT, " +
                            "`custom_identity_id` TEXT, " +
                            "`socket_path` TEXT NOT NULL, " +
                            "`transport_mode` TEXT NOT NULL, " +
                            "`docker_cli_path` TEXT, " +
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
                        "CREATE INDEX `index_docker_hosts_linked_connection_id` " +
                            "ON `docker_hosts` (`linked_connection_id`)"
                    )
                    db.execSQL(
                        "CREATE TABLE `compose_stacks` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`docker_host_id` INTEGER NOT NULL, " +
                            "`name` TEXT NOT NULL, " +
                            "`remote_path` TEXT NOT NULL, " +
                            "`auto_update_enabled` INTEGER NOT NULL, " +
                            "`last_known_status` TEXT, " +
                            "`created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER NOT NULL, " +
                            "`modified_at` INTEGER NOT NULL)"
                    )
                    db.execSQL(
                        "CREATE INDEX `index_compose_stacks_docker_host_id` " +
                            "ON `compose_stacks` (`docker_host_id`)"
                    )
                    db.execSQL(
                        "CREATE TABLE `single_container_configs` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`docker_host_id` INTEGER NOT NULL, " +
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
                        "CREATE INDEX `index_single_container_configs_docker_host_id` " +
                            "ON `single_container_configs` (`docker_host_id`)"
                    )
                    db.execSQL(
                        "CREATE TABLE `container_auto_update_policies` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`docker_host_id` INTEGER NOT NULL, " +
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
                        "CREATE INDEX `index_container_auto_update_policies_docker_host_id` " +
                            "ON `container_auto_update_policies` (`docker_host_id`)"
                    )
                    db.execSQL(
                        "CREATE INDEX `index_container_auto_update_policies_registry_credential_id` " +
                            "ON `container_auto_update_policies` (`registry_credential_id`)"
                    )

                    db.execSQL(
                        "INSERT INTO `docker_hosts` (" +
                            "`id`, `name`, `linked_connection_id`, `custom_host`, `custom_port`, " +
                            "`custom_username`, `custom_auth_type`, `custom_key_id`, " +
                            "`custom_identity_id`, `socket_path`, `transport_mode`, " +
                            "`docker_cli_path`, `compose_invocation`, `pinned_api_version`, " +
                            "`compose_base_path`, `run_config_base_path`, `update_check_enabled`, " +
                            "`update_check_interval_hours`, `last_update_check`, `notes`, " +
                            "`last_connected`, `created_at`, `modified_at`) VALUES (" +
                            "1, 'h1', 'conn1', NULL, NULL, NULL, NULL, NULL, NULL, " +
                            "'/var/run/docker.sock', 'auto', '/usr/bin/docker', 'auto', NULL, " +
                            "'/srv/tabssh/compose', '/srv/tabssh/runconfig', 1, 6, 0, NULL, " +
                            "0, 100, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO `compose_stacks` (" +
                            "`id`, `docker_host_id`, `name`, `remote_path`, `auto_update_enabled`, " +
                            "`last_known_status`, `created_at`, `updated_at`, `modified_at`) VALUES (" +
                            "10, 1, 'stack1', '/srv/stack1', 1, 'running', 100, 100, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO `single_container_configs` (" +
                            "`id`, `docker_host_id`, `name`, `remote_path`, `config_format`, " +
                            "`auto_update_enabled`, `last_known_status`, `created_at`, `updated_at`, " +
                            "`modified_at`) VALUES (" +
                            "20, 1, 'ctr1', '/srv/ctr1', 'RUN_ARGS', 1, 'running', 100, 100, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO `container_auto_update_policies` (" +
                            "`id`, `docker_host_id`, `container_name_or_stack_name`, `scope`, " +
                            "`enabled`, `auto_recreate_on_update`, `registry_credential_id`, " +
                            "`last_checked_at`, `last_digest_seen`, `pending_update_digest`, " +
                            "`modified_at`) VALUES (" +
                            "30, 1, 'stack1', 'STACK', 1, 0, NULL, 0, NULL, NULL, 0)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
    }

    private fun tableNames(): List<String> {
        val names = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) names.add(it.getString(nameIndex))
        }
        return names
    }

    private fun indexNames(): List<String> {
        val names = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) names.add(it.getString(nameIndex))
        }
        return names
    }

    private fun columnNames(table: String): List<String> {
        val cursor = db.query("PRAGMA table_info(`$table`)")
        val names = mutableListOf<String>()
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) names.add(it.getString(nameIndex))
        }
        return names
    }

    @Test
    fun `renames docker_hosts to container_hosts, backfills engine, preserves the seeded row`() {
        TabSSHDatabase.MIGRATION_14_15.migrate(db)

        assertFalse(tableNames().contains("docker_hosts"), "docker_hosts must not exist after 14->15")
        assertTrue(tableNames().contains("container_hosts"), "container_hosts must exist after 14->15")

        val cols = columnNames("container_hosts")
        assertTrue(cols.contains("engine"), "engine column must exist")
        assertTrue(cols.contains("socket_path"), "socket_path column must exist")
        assertTrue(cols.contains("engine_cli_path"), "engine_cli_path must exist")
        assertFalse(cols.contains("docker_cli_path"), "docker_cli_path must not exist")

        val cursor = db.query(
            "SELECT id, name, engine, socket_path, engine_cli_path FROM container_hosts WHERE id = 1"
        )
        cursor.use {
            assertTrue(it.moveToFirst(), "seeded row must survive the rebuild")
            assertEquals(1L, it.getLong(0))
            assertEquals("h1", it.getString(1))
            assertEquals("docker", it.getString(2))
            assertEquals("/var/run/docker.sock", it.getString(3))
            assertEquals("/usr/bin/docker", it.getString(4))
        }
    }

    @Test
    fun `recreates dependent tables with container_host_id and preserves referencing rows`() {
        TabSSHDatabase.MIGRATION_14_15.migrate(db)

        for (table in listOf(
            "compose_stacks", "single_container_configs", "container_auto_update_policies"
        )) {
            val cols = columnNames(table)
            assertTrue(cols.contains("container_host_id"), "$table must have container_host_id")
            assertFalse(cols.contains("docker_host_id"), "$table must not have docker_host_id")
        }

        db.query("SELECT id, container_host_id, name FROM compose_stacks WHERE id = 10").use {
            assertTrue(it.moveToFirst(), "compose_stacks row must survive")
            assertEquals(1L, it.getLong(1))
            assertEquals("stack1", it.getString(2))
        }
        db.query("SELECT id, container_host_id, name FROM single_container_configs WHERE id = 20").use {
            assertTrue(it.moveToFirst(), "single_container_configs row must survive")
            assertEquals(1L, it.getLong(1))
            assertEquals("ctr1", it.getString(2))
        }
        db.query(
            "SELECT id, container_host_id, container_name_or_stack_name " +
                "FROM container_auto_update_policies WHERE id = 30"
        ).use {
            assertTrue(it.moveToFirst(), "container_auto_update_policies row must survive")
            assertEquals(1L, it.getLong(1))
            assertEquals("stack1", it.getString(2))
        }
    }

    @Test
    fun `recreates every index under its new name`() {
        TabSSHDatabase.MIGRATION_14_15.migrate(db)

        val names = indexNames()
        assertTrue(names.contains("index_container_hosts_linked_connection_id"))
        assertTrue(names.contains("index_compose_stacks_container_host_id"))
        assertTrue(names.contains("index_single_container_configs_container_host_id"))
        assertTrue(names.contains("index_container_auto_update_policies_container_host_id"))
        assertTrue(names.contains("index_container_auto_update_policies_registry_credential_id"))

        assertFalse(names.contains("index_docker_hosts_linked_connection_id"))
        assertFalse(names.contains("index_compose_stacks_docker_host_id"))
        assertFalse(names.contains("index_single_container_configs_docker_host_id"))
        assertFalse(names.contains("index_container_auto_update_policies_docker_host_id"))
    }
}

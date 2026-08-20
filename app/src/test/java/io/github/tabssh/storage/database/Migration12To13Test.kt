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
import kotlin.test.assertTrue

/**
 * Coverage for MIGRATION_12_13: the eight blind-upsert entities that were
 * missing `modified_at` gain the column with a safe `DEFAULT 0` backfill,
 * and the two new conflict-tracking tables (`pending_sync_conflicts`,
 * `sync_log`) are created with their indices. Mirrors the raw-SQL,
 * migration-object-under-test style of [InlineProxyRouteMigrationTest],
 * exercising [TabSSHDatabase.MIGRATION_12_13] directly against a minimal
 * pre-migration schema rather than through Room's schema-json validation,
 * since no `13.json` schema export exists in this build environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Migration12To13Test {

    private val migratedTables = listOf(
        "hypervisors",
        "trusted_certificates",
        "monitor_slots",
        "docker_hosts",
        "registry_credentials",
        "compose_stacks",
        "single_container_configs",
        "container_auto_update_policies"
    )

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_12_13_test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_12_13_test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (table in migratedTables) {
                        db.execSQL("CREATE TABLE `$table` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    }
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

    private fun columnNames(table: String): List<String> {
        val cursor = db.query("PRAGMA table_info(`$table`)")
        val names = mutableListOf<String>()
        cursor.use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) {
                names.add(it.getString(nameIndex))
            }
        }
        return names
    }

    @Test
    fun `adds modified_at with default 0 to all eight blind-upsert entities`() {
        TabSSHDatabase.MIGRATION_12_13.migrate(db)

        for (table in migratedTables) {
            assertTrue(
                columnNames(table).contains("modified_at"),
                "$table is missing modified_at after migration"
            )
        }

        db.execSQL("INSERT INTO `hypervisors` (`id`) VALUES ('h1')")
        val cursor = db.query("SELECT modified_at FROM `hypervisors` WHERE id = 'h1'")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

    @Test
    fun `creates pending_sync_conflicts and sync_log tables with indices`() {
        TabSSHDatabase.MIGRATION_12_13.migrate(db)

        val tableNames = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) tableNames.add(it.getString(nameIndex))
        }
        assertTrue(tableNames.contains("pending_sync_conflicts"))
        assertTrue(tableNames.contains("sync_log"))

        val indexNames = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) indexNames.add(it.getString(nameIndex))
        }
        assertTrue(indexNames.contains("index_pending_sync_conflicts_entity_type"))
        assertTrue(indexNames.contains("index_pending_sync_conflicts_created_at"))
        assertTrue(indexNames.contains("index_sync_log_timestamp"))

        assertEquals(
            listOf(
                "id", "entity_type", "entity_id", "conflict_type", "field",
                "local_value_text", "remote_value_text", "local_entity_json",
                "remote_entity_json", "local_timestamp", "remote_timestamp",
                "description", "created_at"
            ),
            columnNames("pending_sync_conflicts")
        )
        assertEquals(
            listOf(
                "id", "timestamp", "device_id", "device_name", "entity_type",
                "entity_id", "description", "resolution"
            ),
            columnNames("sync_log")
        )
    }
}

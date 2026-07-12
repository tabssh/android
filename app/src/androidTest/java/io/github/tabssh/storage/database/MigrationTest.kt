package io.github.tabssh.storage.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

/**
 * Room migration tests for TabSSHDatabase.
 *
 * Coverage: v3 → v4 → v5 (all schema JSONs present in app/schemas/).
 *
 * Each single-step test verifies that the migration SQL runs without
 * error and that Room's schema validator agrees the resulting DB
 * matches the declared entity snapshot. The chain test exercises both
 * migrations in sequence.
 *
 * Pre-v3 installs (v1, v2) are wiped by fallbackToDestructiveMigrationFrom
 * and have no schema JSON, so they are intentionally not covered here.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDbName = "tabssh_migration_test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TabSSHDatabase::class.java
    )

    // -------------------------------------------------------------------------
    // v3 → v4: keep_alive_in_background column added to vnc_hosts
    // -------------------------------------------------------------------------

    @Test
    fun migrate3To4_columnAdded() {
        helper.createDatabase(testDbName, 3).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 4, true,
            TabSSHDatabase.MIGRATION_3_4
        )
        db.use {
            val c = it.query("PRAGMA table_info(vnc_hosts)")
            val cols = mutableListOf<String>()
            c.use { while (c.moveToNext()) cols.add(c.getString(1)) }
            assertTrue(
                "keep_alive_in_background must exist in vnc_hosts after 3→4",
                "keep_alive_in_background" in cols
            )
        }
    }

    // -------------------------------------------------------------------------
    // v4 → v5: sync_tombstones and sync_shadow tables created (H6 soft-delete)
    // -------------------------------------------------------------------------

    @Test
    fun migrate4To5_syncTablesCreated() {
        helper.createDatabase(testDbName, 4).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 5, true,
            TabSSHDatabase.MIGRATION_4_5
        )
        db.use {
            val tables = mutableListOf<String>()
            val c = it.query(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                    "AND name IN ('sync_tombstones','sync_shadow')"
            )
            c.use { while (c.moveToNext()) tables.add(c.getString(0)) }
            assertTrue("sync_tombstones must exist after 4→5", "sync_tombstones" in tables)
            assertTrue("sync_shadow must exist after 4→5", "sync_shadow" in tables)
        }
    }

    // -------------------------------------------------------------------------
    // Full chain v3 → v5 — exercises every migration in sequence
    // -------------------------------------------------------------------------

    @Test
    fun migrateChain3To5() {
        helper.createDatabase(testDbName, 3).close()
        helper.runMigrationsAndValidate(
            testDbName, 5, true,
            TabSSHDatabase.MIGRATION_3_4,
            TabSSHDatabase.MIGRATION_4_5
        ).close()
    }
}

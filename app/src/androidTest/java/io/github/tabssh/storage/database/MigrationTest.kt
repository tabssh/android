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
 * Coverage: v3 → v4 → v5 → v6 (all schema JSONs present in app/schemas/).
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
    // v5 → v6: tab_sessions rebuilt for VNC-tab-swipe integration —
    // connection_id becomes nullable, vnc_host_id + tab_kind added, and an
    // existing row survives the rebuild with tab_kind='SSH', vnc_host_id=NULL.
    // -------------------------------------------------------------------------

    @Test
    fun migrate5To6_tabSessionsRebuilt_rowPreserved() {
        helper.createDatabase(testDbName, 5).use { db ->
            db.execSQL(
                "INSERT INTO connections (id, name, host, port, username, " +
                    "protocol, auth_type, save_password, terminal_type, encoding, " +
                    "compression, keep_alive, x11_forwarding, mosh_mode, " +
                    "multiplexer_mode, port_knock_delay_ms, connect_timeout, " +
                    "read_timeout, theme, agent_forwarding, ip_mode, color_tag, " +
                    "sort_order, created_at, last_connected, connection_count, " +
                    "notif_sound_mode, notif_vibrate_mode, last_synced_at, " +
                    "sync_version, modified_at, sync_device_id) VALUES " +
                    "('conn1', 'test', 'host', 22, 'user', 'ssh', 'PASSWORD', " +
                    "0, 'xterm-256color', 'UTF-8', 1, 1, 0, 'auto', 'OFF', " +
                    "100, 15, 30, 'dracula', 0, 'auto', 0, 0, 0, 0, 0, 0, 0, " +
                    "0, 0, 0, '')"
            )
            db.execSQL(
                "INSERT INTO tab_sessions (session_id, tab_id, connection_id, " +
                    "title, is_active, terminal_content, cursor_row, cursor_col, " +
                    "scroll_position, working_directory, environment_vars, " +
                    "created_at, last_activity, session_state, terminal_rows, " +
                    "terminal_cols, font_size, connection_state, last_error, " +
                    "has_unread_output, unread_lines, tab_order) VALUES " +
                    "('sess1', 'tab1', 'conn1', 'Tab 1', 1, '', 0, 0, 0, '/', " +
                    "'{}', 0, 0, 'DISCONNECTED', 24, 80, 14.0, 'DISCONNECTED', " +
                    "NULL, 0, 0, 0)"
            )
        }
        val db = helper.runMigrationsAndValidate(
            testDbName, 6, true,
            TabSSHDatabase.MIGRATION_5_6
        )
        db.use {
            val c = it.query("PRAGMA table_info(tab_sessions)")
            val cols = mutableListOf<String>()
            c.use { while (c.moveToNext()) cols.add(c.getString(1)) }
            assertTrue("vnc_host_id must exist in tab_sessions after 5→6", "vnc_host_id" in cols)
            assertTrue("tab_kind must exist in tab_sessions after 5→6", "tab_kind" in cols)

            val row = it.query(
                "SELECT tab_kind, connection_id, vnc_host_id FROM tab_sessions " +
                    "WHERE session_id = 'sess1'"
            )
            row.use { rc ->
                assertTrue("preserved row must survive the rebuild", rc.moveToFirst())
                assertTrue("existing row must default to tab_kind='SSH'", rc.getString(0) == "SSH")
                assertTrue("connection_id must be preserved", rc.getString(1) == "conn1")
                assertTrue("vnc_host_id must be NULL for a pre-migration SSH row", rc.isNull(2))
            }
        }
    }

    // -------------------------------------------------------------------------
    // Full chain v3 → v6 — exercises every migration in sequence
    // -------------------------------------------------------------------------

    @Test
    fun migrateChain3To6() {
        helper.createDatabase(testDbName, 3).close()
        helper.runMigrationsAndValidate(
            testDbName, 6, true,
            TabSSHDatabase.MIGRATION_3_4,
            TabSSHDatabase.MIGRATION_4_5,
            TabSSHDatabase.MIGRATION_5_6
        ).close()
    }
}

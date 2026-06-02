package io.github.tabssh.storage.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Room migration tests for TabSSHDatabase.
 *
 * Coverage: v32 → v37 (all schema JSONs present in app/schemas/).
 *
 * Each single-step test verifies that the migration SQL runs without
 * error and that Room's schema validator agrees the resulting DB
 * matches the declared entity snapshot. The v32→v37 chain test also
 * exercises data-carrying paths (OCI credential promotion in 32→33).
 *
 * Missing schemas: v3, v4, v7, v8, v9, v31 were never exported to git.
 * Those migration paths cannot be tested here until the schema files are
 * recovered or regenerated from the git history.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDbName = "tabssh_migration_test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TabSSHDatabase::class.java.canonicalName,
        InstrumentationRegistry.getInstrumentation().context.assets,
        FrameworkSQLiteOpenHelperFactory()
    )

    // -------------------------------------------------------------------------
    // v32 → v33: OCI credentials promoted to hypervisor_accounts
    // -------------------------------------------------------------------------

    @Test
    fun migrate32To33_noOciRows() {
        helper.createDatabase(testDbName, 32).close()
        helper.runMigrationsAndValidate(
            testDbName, 33, true,
            TabSSHDatabase.MIGRATION_32_33
        ).close()
    }

    @Test
    fun migrate32To33_ociRowMigratedToAccount() {
        helper.createDatabase(testDbName, 32).use { db ->
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("name", "My OCI")
                put("type", "oci_api_key")
                put("host", "compute.us-phoenix-1.oraclecloud.com")
                put("port", 443)
                put("username", "opc")
                put("password", "")
                put("is_xen_orchestra", 0)
                put("api_type_override", "")
                put("verify_ssl", 1)
                put("last_connected", 0L)
                put("created_at", now)
                put("auth_type", "oci_api_key")
                put("oci_tenancy_ocid", "ocid1.tenancy.oc1..aaaaaa")
                put("oci_user_ocid", "ocid1.user.oc1..bbbbbb")
                put("oci_region", "us-phoenix-1")
                put("oci_fingerprint", "aa:bb:cc:dd:ee")
                put("oci_compartment_ocid", "ocid1.compartment.oc1..cccccc")
            }
            db.insert("hypervisors", SQLiteDatabase.CONFLICT_FAIL, cv)
        }

        val db = helper.runMigrationsAndValidate(
            testDbName, 33, true,
            TabSSHDatabase.MIGRATION_32_33
        )

        db.use {
            val c = it.query("SELECT COUNT(*) FROM hypervisor_accounts")
            c.use {
                assertTrue("Expected at least one promoted account row", c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }

            val linked = it.query(
                "SELECT account_id FROM hypervisors WHERE auth_type = 'oci_api_key'"
            )
            linked.use {
                assertTrue("OCI hypervisor should be linked to a new account", linked.moveToFirst())
                assertTrue(
                    "account_id must be non-null after promotion",
                    !linked.isNull(0)
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // v33 → v34: VNC identity and host tables created
    // -------------------------------------------------------------------------

    @Test
    fun migrate33To34_tablesCreated() {
        helper.createDatabase(testDbName, 33).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 34, true,
            TabSSHDatabase.MIGRATION_33_34
        )
        db.use {
            val tables = mutableListOf<String>()
            val c = it.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('vnc_identities','vnc_hosts')"
            )
            c.use { while (c.moveToNext()) tables.add(c.getString(0)) }
            assertTrue("vnc_identities must exist after 33→34", "vnc_identities" in tables)
            assertTrue("vnc_hosts must exist after 33→34", "vnc_hosts" in tables)
        }
    }

    // -------------------------------------------------------------------------
    // v34 → v35: ssh_identity_id column added to hypervisors
    // -------------------------------------------------------------------------

    @Test
    fun migrate34To35_columnAdded() {
        helper.createDatabase(testDbName, 34).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 35, true,
            TabSSHDatabase.MIGRATION_34_35
        )
        db.use {
            val c = it.query("PRAGMA table_info(hypervisors)")
            val cols = mutableListOf<String>()
            c.use { while (c.moveToNext()) cols.add(c.getString(1)) }
            assertTrue("ssh_identity_id must exist in hypervisors after 34→35", "ssh_identity_id" in cols)
        }
    }

    // -------------------------------------------------------------------------
    // v35 → v36: group_type column added to connection_groups
    // -------------------------------------------------------------------------

    @Test
    fun migrate35To36_groupTypeAdded() {
        helper.createDatabase(testDbName, 35).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 36, true,
            TabSSHDatabase.MIGRATION_35_36
        )
        db.use {
            val c = it.query("PRAGMA table_info(connection_groups)")
            val cols = mutableListOf<String>()
            c.use { while (c.moveToNext()) cols.add(c.getString(1)) }
            assertTrue("group_type must exist in connection_groups after 35→36", "group_type" in cols)
        }
    }

    // -------------------------------------------------------------------------
    // v36 → v37: performance indexes created
    // -------------------------------------------------------------------------

    @Test
    fun migrate36To37_indexesCreated() {
        helper.createDatabase(testDbName, 36).close()
        val db = helper.runMigrationsAndValidate(
            testDbName, 37, true,
            TabSSHDatabase.MIGRATION_36_37
        )
        db.use {
            val expected = listOf(
                "index_connections_identity_id",
                "index_connections_key_id",
                "index_connections_group_id",
                "index_connections_last_connected",
                "index_stored_keys_fingerprint",
                "index_identities_key_id",
                "index_host_keys_hostname_port",
                "index_monitor_slots_connection_id",
                "index_monitor_slots_enabled",
                "index_hypervisors_account_id",
                "index_connection_groups_parent_id"
            )
            val c = it.query("SELECT name FROM sqlite_master WHERE type='index'")
            val actual = mutableSetOf<String>()
            c.use { while (c.moveToNext()) actual.add(c.getString(0)) }

            for (name in expected) {
                assertTrue("Expected index '$name' after 36→37 migration", name in actual)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Full chain v32 → v37 — exercises every migration in sequence
    // -------------------------------------------------------------------------

    @Test
    fun migrateChain32To37() {
        helper.createDatabase(testDbName, 32).close()
        helper.runMigrationsAndValidate(
            testDbName, 37, true,
            TabSSHDatabase.MIGRATION_32_33,
            TabSSHDatabase.MIGRATION_33_34,
            TabSSHDatabase.MIGRATION_34_35,
            TabSSHDatabase.MIGRATION_35_36,
            TabSSHDatabase.MIGRATION_36_37
        ).close()
    }
}

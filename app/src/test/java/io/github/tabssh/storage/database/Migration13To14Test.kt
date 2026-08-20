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
 * Coverage for MIGRATION_13_14: the plaintext `hypervisors.password` column is
 * removed by the create/copy/drop/rename dance (SQLite < 3.35 has no
 * `DROP COLUMN`), every other column and both indices survive, and any
 * still-populated password is handed to the carry-over table instead of being
 * destroyed — `HypervisorPasswordStore.sweepLegacyPlaintext` moves it into the
 * Keystore on the next startup.
 *
 * Mirrors the raw-SQL, migration-object-under-test style of
 * [Migration12To13Test], exercising [TabSSHDatabase.MIGRATION_13_14] directly
 * against a minimal pre-migration schema rather than through Room's schema-json
 * validation, since no `13.json` schema export exists in this build environment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Migration13To14Test {

    private companion object {
        const val V13_HYPERVISORS = "CREATE TABLE `hypervisors` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`type` TEXT NOT NULL, " +
            "`host` TEXT NOT NULL, " +
            "`port` INTEGER NOT NULL, " +
            "`username` TEXT NOT NULL, " +
            "`password` TEXT NOT NULL, " +
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
            "`modified_at` INTEGER NOT NULL DEFAULT 0)"

        val EXPECTED_V14_COLUMNS = listOf(
            "id", "name", "type", "host", "port", "username", "realm", "verify_ssl",
            "pinned_cert_sha256", "api_type_override", "linked_connection_id",
            "account_id", "notes", "last_connected", "created_at", "auth_type",
            "oci_tenancy_ocid", "oci_user_ocid", "oci_region", "oci_fingerprint",
            "oci_compartment_ocid", "ssh_identity_id", "modified_at"
        )
    }

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_13_14_test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_13_14_test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V13_HYPERVISORS)
                    db.execSQL(
                        "CREATE INDEX `index_hypervisors_account_id` " +
                            "ON `hypervisors` (`account_id`)"
                    )
                    db.execSQL(
                        "CREATE INDEX `index_hypervisors_linked_connection_id` " +
                            "ON `hypervisors` (`linked_connection_id`)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(config)
        db = helper.writableDatabase
        seedRows()
    }

    @After
    fun tearDown() {
        helper.close()
    }

    /**
     * Two rows: one that still carries plaintext (id 1) and one that was
     * already migrated to the Keystore and blanked (id 2).
     */
    private fun seedRows() {
        db.execSQL(
            "INSERT INTO `hypervisors` (" +
                "`id`, `name`, `type`, `host`, `port`, `username`, `password`, `realm`, " +
                "`verify_ssl`, `api_type_override`, `last_connected`, `created_at`, " +
                "`auth_type`, `modified_at`) VALUES " +
                "(1, 'pve1', 'PROXMOX', '10.0.0.1', 8006, 'root', 'super-secret', 'pam', " +
                "0, 'auto', 111, 222, 'password', 333), " +
                "(2, 'xcp1', 'XCPNG', '10.0.0.2', 443, 'admin', '', NULL, " +
                "1, 'direct', 0, 444, 'password', 0)"
        )
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
    fun `drops the password column and keeps every other column`() {
        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        val columns = columnNames("hypervisors")
        assertFalse(columns.contains("password"), "password column survived the migration")
        assertEquals(EXPECTED_V14_COLUMNS, columns)
    }

    @Test
    fun `preserves every existing row verbatim`() {
        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        db.query(
            "SELECT `id`, `name`, `type`, `host`, `port`, `username`, `realm`, `verify_ssl`, " +
                "`api_type_override`, `last_connected`, `created_at`, `auth_type`, `modified_at` " +
                "FROM `hypervisors` ORDER BY `id`"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals("pve1", it.getString(1))
            assertEquals("PROXMOX", it.getString(2))
            assertEquals("10.0.0.1", it.getString(3))
            assertEquals(8006, it.getInt(4))
            assertEquals("root", it.getString(5))
            assertEquals("pam", it.getString(6))
            assertEquals(0, it.getInt(7))
            assertEquals("auto", it.getString(8))
            assertEquals(111L, it.getLong(9))
            assertEquals(222L, it.getLong(10))
            assertEquals("password", it.getString(11))
            assertEquals(333L, it.getLong(12))

            assertTrue(it.moveToNext())
            assertEquals(2L, it.getLong(0))
            assertEquals("xcp1", it.getString(1))
            assertEquals(443, it.getInt(4))
            assertEquals(1, it.getInt(7))
            assertTrue(it.isNull(6))

            assertFalse(it.moveToNext())
        }
    }

    @Test
    fun `recreates both hypervisor indices`() {
        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        val indexNames = mutableListOf<String>()
        db.query("SELECT `name` FROM `sqlite_master` WHERE `type` = 'index' AND `tbl_name` = 'hypervisors'").use {
            val nameIndex = it.getColumnIndex("name")
            while (it.moveToNext()) indexNames.add(it.getString(nameIndex))
        }
        assertTrue(indexNames.contains("index_hypervisors_account_id"))
        assertTrue(indexNames.contains("index_hypervisors_linked_connection_id"))
    }

    @Test
    fun `hands still-plaintext passwords to the carry-over table`() {
        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        db.query(
            "SELECT `id`, `password` FROM " +
                "`${TabSSHDatabase.HYPERVISOR_PASSWORD_CARRYOVER_TABLE}` ORDER BY `id`"
        ).use {
            assertTrue(it.moveToFirst(), "the plaintext row was not carried over")
            assertEquals(1L, it.getLong(0))
            assertEquals("super-secret", it.getString(1))
            assertFalse(it.moveToNext(), "the already-blank row should not be carried over")
        }
    }

    @Test
    fun `leaves the carry-over table empty when no row carried plaintext`() {
        db.execSQL("UPDATE `hypervisors` SET `password` = ''")

        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        db.query(
            "SELECT COUNT(*) FROM `${TabSSHDatabase.HYPERVISOR_PASSWORD_CARRYOVER_TABLE}`"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(0L, it.getLong(0))
        }
    }

    @Test
    fun `keeps autoincrement so new rows do not collide with existing ids`() {
        TabSSHDatabase.MIGRATION_13_14.migrate(db)

        db.execSQL(
            "INSERT INTO `hypervisors` (" +
                "`name`, `type`, `host`, `port`, `username`, `verify_ssl`, " +
                "`api_type_override`, `last_connected`, `created_at`, `auth_type`, `modified_at`) " +
                "VALUES ('new', 'VMWARE', '10.0.0.3', 443, 'root', 1, 'auto', 0, 0, 'password', 0)"
        )
        db.query("SELECT MAX(`id`) FROM `hypervisors`").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getLong(0) > 2L, "a recycled id would overwrite an existing hypervisor")
        }
    }
}

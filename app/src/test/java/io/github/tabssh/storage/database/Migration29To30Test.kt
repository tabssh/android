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

/**
 * Coverage for MIGRATION_29_30: the nullable per-host and per-image update overrides are added,
 * and rows that opted out (host `update_check_enabled = 0`, policy `enabled = 0`) or into
 * auto-apply (`auto_recreate_on_update = 1`) keep that behavior while everything else inherits.
 * Only the columns the migration touches are modelled in the pre-migration tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Migration29To30Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_29_30_test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_29_30_test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `container_hosts` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`update_check_enabled` INTEGER NOT NULL DEFAULT 1)"
                    )
                    db.execSQL(
                        "CREATE TABLE `container_auto_update_policies` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`enabled` INTEGER NOT NULL, " +
                            "`auto_recreate_on_update` INTEGER NOT NULL)"
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
        db.close()
        helper.close()
    }

    @Test
    fun `opted-out host keeps the off override and enabled hosts inherit`() {
        db.execSQL("INSERT INTO container_hosts (id, update_check_enabled) VALUES (1, 0), (2, 1)")

        TabSSHDatabase.MIGRATION_29_30.migrate(db)

        db.query("SELECT id, update_enabled_override, update_auto_apply FROM container_hosts ORDER BY id").use { c ->
            c.moveToNext()
            assertEquals(0, c.getInt(1))
            assertEquals(true, c.isNull(2))
            c.moveToNext()
            assertEquals(true, c.isNull(1))
        }
    }

    @Test
    fun `policies keep opt-out and auto-apply and otherwise inherit`() {
        db.execSQL(
            "INSERT INTO container_auto_update_policies (id, enabled, auto_recreate_on_update) " +
                "VALUES (1, 0, 0), (2, 1, 1), (3, 1, 0)"
        )

        TabSSHDatabase.MIGRATION_29_30.migrate(db)

        db.query(
            "SELECT id, enabled_override, auto_apply_override FROM container_auto_update_policies ORDER BY id"
        ).use { c ->
            c.moveToNext()
            assertEquals(0, c.getInt(1))
            assertEquals(true, c.isNull(2))
            c.moveToNext()
            assertEquals(true, c.isNull(1))
            assertEquals(1, c.getInt(2))
            c.moveToNext()
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(2))
        }
    }
}

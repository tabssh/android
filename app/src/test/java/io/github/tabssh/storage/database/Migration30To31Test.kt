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
 * Coverage for MIGRATION_30_31: connections gain the nullable VPS and domain link columns, and
 * every existing row starts on automatic matching (both null). Only the connections columns the
 * migration relies on are modelled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class Migration30To31Test {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration_30_31_test.db")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_30_31_test.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE `connections` (`id` TEXT PRIMARY KEY NOT NULL, `host` TEXT NOT NULL)")
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
    fun `existing connections start on automatic linking`() {
        db.execSQL("INSERT INTO connections (id, host) VALUES ('a', 'example.com')")

        TabSSHDatabase.MIGRATION_30_31.migrate(db)

        db.query("SELECT host, vps_link, domain_link FROM connections").use { c ->
            c.moveToNext()
            assertEquals("example.com", c.getString(0))
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(2))
        }
    }

    @Test
    fun `the new columns accept an explicit none and a chosen id`() {
        db.execSQL("INSERT INTO connections (id, host) VALUES ('a', 'h')")
        TabSSHDatabase.MIGRATION_30_31.migrate(db)

        db.execSQL("UPDATE connections SET vps_link = '', domain_link = 'dom-1' WHERE id = 'a'")

        db.query("SELECT vps_link, domain_link FROM connections").use { c ->
            c.moveToNext()
            assertEquals("", c.getString(0))
            assertEquals("dom-1", c.getString(1))
        }
    }
}

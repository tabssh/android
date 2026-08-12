package io.github.tabssh.storage.database.dao

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Regression coverage for the removed global "Enable PRE Key" toggle
 * migration (Item 10, TabSSHApplication.migrateLegacyPrefixKeyPref): a
 * legacy global `terminal_prefix_key_enabled = false` must become a
 * per-connection `multiplexerOverride = "off"` on every profile that has
 * never had its own override set — explicit overrides (including a pinned
 * multiplexer type) must never be touched, and re-running the migration
 * must be a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConnectionDaoPrefixKeyMigrationTest {

    private lateinit var db: TabSSHDatabase
    private lateinit var dao: ConnectionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, TabSSHDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.connectionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `migration sets off on null-override profiles and leaves explicit overrides untouched`() = runTest {
        val nullOverrideA = ConnectionProfile(name = "a", host = "a.example.com", username = "user")
        val nullOverrideB = ConnectionProfile(name = "b", host = "b.example.com", username = "user")
        val tmuxOverride = ConnectionProfile(
            name = "c", host = "c.example.com", username = "user", multiplexerOverride = "tmux"
        )
        dao.insertConnection(nullOverrideA)
        dao.insertConnection(nullOverrideB)
        dao.insertConnection(tmuxOverride)

        val migratedCount = dao.disablePrefixKeyForProfilesWithNullOverride()
        assertEquals(2, migratedCount)

        assertEquals("off", dao.getConnectionById(nullOverrideA.id)?.multiplexerOverride)
        assertEquals("off", dao.getConnectionById(nullOverrideB.id)?.multiplexerOverride)
        assertEquals("tmux", dao.getConnectionById(tmuxOverride.id)?.multiplexerOverride)

        // Second run: no null-override profiles remain, so it must be a
        // true no-op — nothing flips back and forth.
        val secondRunCount = dao.disablePrefixKeyForProfilesWithNullOverride()
        assertEquals(0, secondRunCount)
        assertEquals("off", dao.getConnectionById(nullOverrideA.id)?.multiplexerOverride)
        assertEquals("tmux", dao.getConnectionById(tmuxOverride.id)?.multiplexerOverride)
    }
}

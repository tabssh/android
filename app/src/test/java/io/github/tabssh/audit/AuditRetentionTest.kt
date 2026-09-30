package io.github.tabssh.audit

import android.app.Application
import androidx.room.Room
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.AuditLogEntry
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.preferences.PreferenceManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AuditRetentionTest {
    private lateinit var db: TabSSHDatabase
    private lateinit var manager: AuditLogManager
    private val profile = ConnectionProfile(name = "audit", host = "host", username = "user")

    @Before
    fun setUp() = runTest {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, TabSSHDatabase::class.java).allowMainThreadQueries().build()
        db.connectionDao().insertConnection(profile)
        val preferences = PreferenceManager(context)
        preferences.setAuditLogEnabled(true)
        preferences.setAuditLogMaxSizeMb(1)
        manager = AuditLogManager(context, db, preferences)
    }

    @After
    fun tearDown() = db.close()

    private fun entry(size: Long = 0, output: String? = null) = AuditLogEntry(
        connectionId = profile.id, sessionId = "session", eventType = AuditLogEntry.EVENT_OUTPUT,
        user = "user", host = "host", port = 22, sizeBytes = size, output = output
    )

    @Test
    fun `large uploads do not delete small audit history including legacy entries`() = runTest {
        db.auditLogDao().insert(entry(size = 2L * 1024 * 1024 * 1024))
        manager.logSftpUpload(profile, "session", "/large.bin", 2L * 1024 * 1024 * 1024)
        manager.checkAndCleanup()
        assertEquals(2, db.auditLogDao().getCount())
        assertTrue(requireNotNull(db.auditLogDao().getTotalSize()) < 4096)
        val upload = db.auditLogDao().getRecent().first { it.eventType == AuditLogEntry.EVENT_SFTP_UPLOAD }
        assertTrue(upload.sizeBytes in 1..4096)
    }

    @Test
    fun `legacy zero sizes and unicode output still count toward retention`() = runTest {
        db.auditLogDao().insertAll(List(150) { entry(output = "界".repeat(4000)) })
        assertTrue(requireNotNull(db.auditLogDao().getTotalSize()) > 1024 * 1024)
        manager.checkAndCleanup()
        assertEquals(50, db.auditLogDao().getCount())
        assertTrue(requireNotNull(db.auditLogDao().getTotalSize()) <= 1024 * 1024)
    }
}

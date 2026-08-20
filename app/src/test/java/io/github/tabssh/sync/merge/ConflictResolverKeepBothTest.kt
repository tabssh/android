package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.dao.ConnectionDao
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.sync.log.SyncLogManager
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolution
import io.github.tabssh.sync.models.ConflictResolutionOption
import io.github.tabssh.sync.models.ConflictType
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Two-device conflict resolution coverage for the connection entity type
 * (§ Requirement 1/2): for a base/local/remote divergence surfaced by
 * [MergeEngine.mergeConnections], each of keep-local / keep-remote /
 * keep-both is applied through [ConflictResolver] exactly as the picker
 * would apply a user's choice, and the outcome lands in the database
 * (or, for keep-both, as a second distinct row) rather than being silently
 * dropped.
 */
class ConflictResolverKeepBothTest {

    private lateinit var context: Context
    private lateinit var database: TabSSHDatabase
    private lateinit var connectionDao: ConnectionDao
    private lateinit var syncLogManager: SyncLogManager
    private lateinit var resolver: ConflictResolver

    private lateinit var base: ConnectionProfile
    private lateinit var local: ConnectionProfile
    private lateinit var remote: ConnectionProfile
    private lateinit var conflict: Conflict

    @Before
    fun setUp() {
        context = mock()
        database = mock()
        connectionDao = mock()
        syncLogManager = mock()
        whenever(database.connectionDao()).thenReturn(connectionDao)
        resolver = ConflictResolver(context, database, syncLogManager)

        base = ConnectionProfile(id = "c1", name = "server", host = "base.example.com", username = "root", modifiedAt = 1_000L)
        local = base.copy(host = "local.example.com", modifiedAt = 2_000L)
        remote = base.copy(host = "remote.example.com", modifiedAt = 3_000L)

        val mergeResult = MergeEngine().mergeConnections(mapOf("c1" to base), listOf(local), listOf(remote))
        assertEquals(1, mergeResult.conflicts.size, "setup must produce exactly one field conflict")
        conflict = mergeResult.conflicts.single()
        assertEquals(ConflictType.FIELD_MODIFIED_BOTH_SIDES, conflict.conflictType)
    }

    @Test
    fun `keep local writes the local entity and records a kept-local log entry`() = runTest {
        resolver.applyResolutions(listOf(ConflictResolution(conflict, ConflictResolutionOption.KEEP_LOCAL)))

        val captor = argumentCaptor<ConnectionProfile>()
        verify(connectionDao).updateConnection(captor.capture())
        assertEquals("local.example.com", captor.firstValue.host)
        verify(connectionDao, never()).insertConnection(any())
        verify(syncLogManager).recordResolution(conflict, ConflictResolutionOption.KEEP_LOCAL)
    }

    @Test
    fun `keep remote writes the remote entity and records a kept-remote log entry`() = runTest {
        resolver.applyResolutions(listOf(ConflictResolution(conflict, ConflictResolutionOption.KEEP_REMOTE)))

        val captor = argumentCaptor<ConnectionProfile>()
        verify(connectionDao).updateConnection(captor.capture())
        assertEquals("remote.example.com", captor.firstValue.host)
        verify(connectionDao, never()).insertConnection(any())
        verify(syncLogManager).recordResolution(conflict, ConflictResolutionOption.KEEP_REMOTE)
    }

    @Test
    fun `keep both inserts a distinct duplicate of the remote entity and records a kept-both log entry`() = runTest {
        resolver.applyResolutions(listOf(ConflictResolution(conflict, ConflictResolutionOption.KEEP_BOTH)))

        val captor = argumentCaptor<ConnectionProfile>()
        verify(connectionDao).insertConnection(captor.capture())
        verify(connectionDao, never()).updateConnection(any())

        val duplicate = captor.firstValue
        assertNotEquals("c1", duplicate.id, "keep-both must not overwrite the local row's id")
        assertEquals("remote.example.com", duplicate.host)
        assertTrue(duplicate.name.contains("remote"), "duplicate should be distinguishable from the local row")
        verify(syncLogManager).recordResolution(conflict, ConflictResolutionOption.KEEP_BOTH)
    }

    @Test
    fun `auto-resolved application logs as auto-merged regardless of chosen side`() = runTest {
        resolver.applyResolutions(
            listOf(ConflictResolution(conflict, ConflictResolutionOption.KEEP_REMOTE)),
            auto = true
        )

        verify(syncLogManager).recordAutoMerged(conflict)
        verify(syncLogManager, never()).recordResolution(any(), any())
    }
}

package io.github.tabssh.sync.merge

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.sync.log.SyncLogManager
import io.github.tabssh.sync.models.Conflict
import io.github.tabssh.sync.models.ConflictResolutionOption
import io.github.tabssh.sync.models.ConflictType
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Newest-wins auto resolution, including ties and edit-versus-delete. */
class ConflictResolverAutoResolveTest {

    private lateinit var resolver: ConflictResolver

    @Before
    fun setUp() {
        resolver = ConflictResolver(mock<Context>(), mock<TabSSHDatabase>(), mock<SyncLogManager>())
    }

    private fun conn(id: String) = ConnectionProfile(id = id, name = "n", host = "h", username = "u")

    private fun fieldConflict(local: Long, remote: Long, auto: Boolean = true) = Conflict(
        entityType = "connection",
        entityId = "c1",
        conflictType = ConflictType.FIELD_MODIFIED_BOTH_SIDES,
        localTimestamp = local,
        remoteTimestamp = remote,
        autoResolvable = auto
    )

    private fun only(c: Conflict) = resolver.autoResolveConflicts(listOf(c)).singleOrNull()?.resolution

    @Test
    fun `newer local wins`() {
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, only(fieldConflict(2_000L, 1_000L)))
    }

    @Test
    fun `newer remote wins`() {
        assertEquals(ConflictResolutionOption.KEEP_REMOTE, only(fieldConflict(1_000L, 2_000L)))
    }

    @Test
    fun `exact tie keeps local instead of deferring`() {
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, only(fieldConflict(1_000L, 1_000L)))
    }

    @Test
    fun `non auto-resolvable field conflict stays unresolved`() {
        assertTrue(resolver.autoResolveConflicts(listOf(fieldConflict(2_000L, 1_000L, auto = false))).isEmpty())
    }

    @Test
    fun `deleted remotely but edited locally keeps local`() {
        val c = Conflict(
            entityType = "connection",
            entityId = "c1",
            conflictType = ConflictType.DELETED_MODIFIED,
            localEntity = conn("c1")
        )
        assertEquals(ConflictResolutionOption.KEEP_LOCAL, only(c))
    }

    @Test
    fun `deleted locally but edited remotely keeps remote`() {
        val c = Conflict(
            entityType = "connection",
            entityId = "c1",
            conflictType = ConflictType.DELETED_MODIFIED,
            remoteEntity = conn("c1")
        )
        assertEquals(ConflictResolutionOption.KEEP_REMOTE, only(c))
    }
}

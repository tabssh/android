package io.github.tabssh.sync.merge

import io.github.tabssh.storage.database.entities.ConnectionProfile
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A row missing on one side is a clean delete when the other side never
 * changed it since the common ancestor; only a real edit-versus-delete is a
 * conflict. Previously every one-sided absence raised a conflict.
 */
class MergeEngineDeletionTest {

    private lateinit var engine: MergeEngine

    @Before
    fun setUp() {
        engine = MergeEngine()
    }

    private fun conn(id: String, host: String = "example.com", modifiedAt: Long = 1_000L) =
        ConnectionProfile(id = id, name = "server", host = host, username = "root", modifiedAt = modifiedAt)

    @Test
    fun `local delete with unchanged remote is not a conflict`() {
        val base = conn("c1")
        val remote = conn("c1", modifiedAt = 5_000L)

        val result = engine.mergeConnections(mapOf("c1" to base), emptyList(), listOf(remote))

        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.merged.isEmpty(), "the deleted row must not be resurrected")
    }

    @Test
    fun `remote delete with unchanged local deletes the local row`() {
        val base = conn("c1")
        val local = conn("c1", modifiedAt = 5_000L)

        val result = engine.mergeConnections(mapOf("c1" to base), listOf(local), emptyList())

        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("c1"), result.deleted)
        assertTrue(result.merged.isEmpty())
    }

    @Test
    fun `remote delete with edited local keeps the row and raises a conflict`() {
        val base = conn("c1")
        val local = conn("c1", host = "edited.example.com", modifiedAt = 5_000L)

        val result = engine.mergeConnections(mapOf("c1" to base), listOf(local), emptyList())

        assertEquals(1, result.conflicts.size)
        assertTrue(result.deleted.isEmpty())
        assertEquals("edited.example.com", result.merged.single().host)
    }

    @Test
    fun `usage counters alone do not make a row count as edited`() {
        val base = conn("c1")
        val local = base.copy(connectionCount = 9, lastConnected = 4_000L)

        val result = engine.mergeConnections(mapOf("c1" to base), listOf(local), emptyList())

        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("c1"), result.deleted)
    }
}

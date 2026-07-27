package io.github.tabssh.sync.merge

import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.sync.models.ConflictType
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure three-way merge tests (§9.6) — no Android dependencies.
 *
 * These exercise the behaviour the persisted base snapshot unlocks: without a
 * base every divergence silently degrades to last-write-wins (no conflict); with
 * a base, a field edited on both sides is surfaced as a conflict instead of one
 * side being lost. Before the base-snapshot layer existed the base was always
 * empty, so the `withBase_*` cases below could never have produced a conflict.
 */
class MergeEngineThreeWayTest {

    private lateinit var engine: MergeEngine

    @Before
    fun setUp() {
        engine = MergeEngine()
    }

    private fun conn(
        id: String,
        name: String = "server",
        host: String = "example.com",
        username: String = "root",
        modifiedAt: Long = 1_000L
    ) = ConnectionProfile(
        id = id,
        name = name,
        host = host,
        username = username,
        modifiedAt = modifiedAt
    )

    @Test
    fun `empty base degrades to last-write-wins with no conflicts`() {
        val local = conn("c1", host = "local.example.com", modifiedAt = 2_000L)
        val remote = conn("c1", host = "remote.example.com", modifiedAt = 1_000L)

        val result = engine.mergeConnections(emptyMap(), listOf(local), listOf(remote))

        assertTrue(result.conflicts.isEmpty(), "empty base must not raise conflicts")
        // Newer local wins.
        assertEquals("local.example.com", result.merged.single().host)
    }

    @Test
    fun `base with divergent host edit on both sides raises a field conflict`() {
        val base = conn("c1", host = "base.example.com")
        val local = conn("c1", host = "local.example.com", modifiedAt = 2_000L)
        val remote = conn("c1", host = "remote.example.com", modifiedAt = 3_000L)

        val result = engine.mergeConnections(
            mapOf("c1" to base),
            listOf(local),
            listOf(remote)
        )

        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts.single()
        assertEquals(ConflictType.FIELD_MODIFIED_BOTH_SIDES, conflict.conflictType)
        assertEquals("host", conflict.field)
        assertEquals("base.example.com", conflict.baseValue)
    }

    @Test
    fun `base with one-sided edit merges cleanly without a conflict`() {
        val base = conn("c1", host = "base.example.com")
        // Local unchanged from base; only remote moved.
        val local = conn("c1", host = "base.example.com", modifiedAt = 1_000L)
        val remote = conn("c1", host = "remote.example.com", modifiedAt = 3_000L)

        val result = engine.mergeConnections(
            mapOf("c1" to base),
            listOf(local),
            listOf(remote)
        )

        assertTrue(result.conflicts.isEmpty(), "one-sided edit is not a conflict")
    }

    @Test
    fun `base present but row deleted locally raises deleted-modified conflict`() {
        val base = conn("c1")
        val remote = conn("c1", host = "remote.example.com", modifiedAt = 3_000L)

        // Local dropped the row; remote still has it and base knew it.
        val result = engine.mergeConnections(
            mapOf("c1" to base),
            emptyList(),
            listOf(remote)
        )

        assertEquals(1, result.conflicts.size)
        assertEquals(ConflictType.DELETED_MODIFIED, result.conflicts.single().conflictType)
    }
}

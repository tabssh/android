package io.github.tabssh.docker

import io.github.tabssh.docker.DockerSessionPolicy.CacheEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Eviction decisions for the Docker session cache — dead, idle, and LRU
 * overflow (see DockerSessionManager.MAX_OPEN_SESSIONS / IDLE_TIMEOUT_MS).
 */
class DockerSessionPolicyTest {

    private val idle = 10 * 60 * 1000L

    private fun entry(id: Long, lastUsedAgo: Long, connected: Boolean = true, now: Long = 1_000_000L) =
        CacheEntry(id, now - lastUsedAgo, connected)

    @Test
    fun `live fresh sessions under cap are kept`() {
        val entries = (1L..4L).map { entry(it, lastUsedAgo = 1000L) }
        assertTrue(DockerSessionPolicy.selectVictims(entries, 1_000_000L, 16, idle).isEmpty())
    }

    @Test
    fun `dead sessions are always evicted`() {
        val entries = listOf(entry(1, 0), entry(2, 0, connected = false))
        assertEquals(setOf(2L), DockerSessionPolicy.selectVictims(entries, 1_000_000L, 16, idle))
    }

    @Test
    fun `idle sessions past the timeout are evicted`() {
        val entries = listOf(entry(1, idle + 1), entry(2, idle - 1))
        assertEquals(setOf(1L), DockerSessionPolicy.selectVictims(entries, 1_000_000L, 16, idle))
    }

    @Test
    fun `session idle exactly at the timeout is evicted`() {
        val entries = listOf(entry(1, idle))
        assertEquals(setOf(1L), DockerSessionPolicy.selectVictims(entries, 1_000_000L, 16, idle))
    }

    @Test
    fun `LRU overflow evicts least recently used first`() {
        // Entries arrive LRU-first; cap 2 must drop the two oldest live ones.
        val entries = listOf(entry(1, 4000), entry(2, 3000), entry(3, 2000), entry(4, 1000))
        assertEquals(setOf(1L, 2L), DockerSessionPolicy.selectVictims(entries, 1_000_000L, 2, idle))
    }

    @Test
    fun `keepHostId is exempt from capacity eviction`() {
        val entries = listOf(entry(1, 4000), entry(2, 3000), entry(3, 2000))
        val victims = DockerSessionPolicy.selectVictims(entries, 1_000_000L, 2, idle, keepHostId = 1L)
        assertEquals(setOf(2L), victims)
    }

    @Test
    fun `keepHostId is still evicted when dead`() {
        val entries = listOf(entry(1, 0, connected = false), entry(2, 0))
        val victims = DockerSessionPolicy.selectVictims(entries, 1_000_000L, 16, idle, keepHostId = 1L)
        assertEquals(setOf(1L), victims)
    }

    @Test
    fun `dead and idle evictions free capacity before LRU kicks in`() {
        // Cap 2: one dead + one idle eviction brings 4 down to 2 — no LRU needed.
        val entries = listOf(
            entry(1, 0, connected = false), entry(2, idle + 1), entry(3, 1000), entry(4, 500)
        )
        assertEquals(setOf(1L, 2L), DockerSessionPolicy.selectVictims(entries, 1_000_000L, 2, idle))
    }

    @Test
    fun `empty cache selects nothing`() {
        assertTrue(DockerSessionPolicy.selectVictims(emptyList(), 0L, 16, idle).isEmpty())
    }
}

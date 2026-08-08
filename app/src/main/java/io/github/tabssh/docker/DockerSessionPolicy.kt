package io.github.tabssh.docker

/**
 * Pure eviction policy for the Docker session cache — separated from
 * [DockerSessionManager] so the LRU/idle/dead decisions are unit-testable
 * on the JVM without Android or SSH dependencies.
 */
object DockerSessionPolicy {

    /** Snapshot of one cached session as seen by the eviction pass. */
    data class CacheEntry(
        val hostId: Long,
        /** Millis timestamp of the last acquire/touch. */
        val lastUsedAt: Long,
        /** False when the underlying SSH connection is no longer up. */
        val connected: Boolean
    )

    /**
     * Decide which cached sessions to evict. [entries] must be ordered
     * least-recently-used first. Evicts every dead entry, every entry idle
     * longer than [idleTimeoutMs], and then the least-recently-used live
     * entries needed to bring the cache down to [maxOpen]. [keepHostId]
     * (the host being acquired right now) is never chosen for capacity
     * eviction but IS evicted when dead, so its stale relay gets closed.
     */
    fun selectVictims(
        entries: List<CacheEntry>,
        now: Long,
        maxOpen: Int,
        idleTimeoutMs: Long,
        keepHostId: Long? = null
    ): Set<Long> {
        val victims = mutableSetOf<Long>()
        for (entry in entries) {
            if (!entry.connected || now - entry.lastUsedAt >= idleTimeoutMs) {
                victims += entry.hostId
            }
        }
        val survivors = entries.filter { it.hostId !in victims }
        var overCap = survivors.size - maxOpen
        if (overCap > 0) {
            for (entry in survivors) {
                if (overCap <= 0) break
                if (entry.hostId == keepHostId) continue
                victims += entry.hostId
                overCap--
            }
        }
        return victims
    }
}

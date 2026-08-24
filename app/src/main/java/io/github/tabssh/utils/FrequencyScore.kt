package io.github.tabssh.utils

import io.github.tabssh.storage.database.entities.ConnectionProfile
import kotlin.math.pow

/**
 * Shared hybrid scoring for the Frequent connections list — a true blend of
 * usage count and recency, not a flat `ORDER BY connection_count`.
 *
 * SQLite has no native exponential function, so this is computed here in
 * Kotlin after fetching all `connection_count > 0` candidates unordered
 * (see ConnectionDao.getFrequentlyUsedConnectionCandidates).
 */
object FrequencyScore {

    /** Days for the recency weight to halve; a reversible, local constant. */
    private const val HALF_LIFE_DAYS = 14.0
    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * `score = connectionCount * 2^(-daysSinceLastConnected / HALF_LIFE_DAYS)`.
     *
     * A host connected to 20 times but not in 60 days scores lower than one
     * connected to only 5 times in the last day — pure count or pure recency
     * alone under- or over-weights one signal on its own.
     */
    fun score(connectionCount: Int, lastConnected: Long, now: Long = System.currentTimeMillis()): Double {
        if (connectionCount <= 0) return 0.0
        val daysSince = (now - lastConnected).coerceAtLeast(0L) / MILLIS_PER_DAY
        return connectionCount * 2.0.pow(-daysSince / HALF_LIFE_DAYS)
    }

    /** Ranks [connections] by [score] descending and returns the top [limit]. */
    fun rank(
        connections: List<ConnectionProfile>,
        limit: Int,
        now: Long = System.currentTimeMillis()
    ): List<ConnectionProfile> =
        connections
            .sortedByDescending { score(it.connectionCount, it.lastConnected, now) }
            .take(limit)
}

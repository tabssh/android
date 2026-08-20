package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [a], [b] and [c] concurrently (not sequentially) and returns their
 * results as a [Triple], or `null` if the whole group does not finish
 * within [timeoutMs].
 *
 * The timeout bounds the *slowest single call*, not their sum: three
 * suspend functions that each honor their own internal timeout can still
 * make a caller wait for all three in sequence if awaited one at a time —
 * this collapses that into one bounded wait so a stalled probe can never
 * leave the caller (e.g. a loading spinner) hanging indefinitely.
 */
suspend fun <A, B, C> loadConcurrently(
    timeoutMs: Long,
    a: suspend () -> A,
    b: suspend () -> B,
    c: suspend () -> C
): Triple<A, B, C>? = withTimeoutOrNull(timeoutMs) {
    coroutineScope {
        val aDeferred = async { a() }
        val bDeferred = async { b() }
        val cDeferred = async { c() }
        Triple(aDeferred.await(), bDeferred.await(), cDeferred.await())
    }
}

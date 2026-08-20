package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves [loadConcurrently] runs its three suspend functions in parallel
 * instead of sequentially, and bounds the whole group to [loadConcurrently]'s
 * timeout instead of letting one stalled call hang the caller forever — the
 * root cause behind the per-host Docker dashboard spinning indefinitely
 * (TODO.AI.md A4): ContainerDashboardFragment used to `await` engineInfo(),
 * engineVersion() and diskUsage() one after another with no overall bound.
 */
class ConcurrentLoadTest {

    @Test
    fun `three calls run in parallel, not summed`() = runTest {
        val result = loadConcurrently(
            timeoutMs = 10_000L,
            a = { delay(3_000L); "a" },
            b = { delay(3_000L); "b" },
            c = { delay(3_000L); "c" }
        )

        assertEquals(Triple("a", "b", "c"), result)
        // Sequential execution would have consumed 9_000ms of virtual time;
        // running concurrently consumes only the slowest single call.
        assertEquals(3_000L, currentTime)
    }

    @Test
    fun `a stalled call times out instead of hanging forever`() = runTest {
        val result = loadConcurrently(
            timeoutMs = 5_000L,
            a = { "fast" },
            b = { delay(Long.MAX_VALUE); "unreachable" },
            c = { "also fast" }
        )

        assertNull(result)
        assertEquals(5_000L, currentTime)
    }
}

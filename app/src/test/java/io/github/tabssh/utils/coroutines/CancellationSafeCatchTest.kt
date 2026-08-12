package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves [catchExceptCancellation] rethrows coroutine cancellation instead
 * of converting it into a caller-visible result, and still calls [onError]
 * for real failures.
 */
class CancellationSafeCatchTest {

    @Test
    fun `cancellation propagates instead of being converted to an error result`() = runTest {
        var onErrorInvoked = false
        val started = CompletableDeferred<Unit>()

        val job = launch {
            catchExceptCancellation(onError = { onErrorInvoked = true; "fallback" }) {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                "unreachable"
            }
        }

        started.await()
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse(onErrorInvoked)
    }

    @Test
    fun `non-cancellation exceptions are still handled by onError`() = runTest {
        val result = catchExceptCancellation(onError = { "handled: ${it.message}" }) {
            throw IllegalStateException("boom")
        }
        assertEquals("handled: boom", result)
    }
}

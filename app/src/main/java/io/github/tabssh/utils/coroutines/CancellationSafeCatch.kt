package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.CancellationException

/**
 * Runs [block] and returns its result. [CancellationException] always
 * propagates unchanged; any other [Exception] is handed to [onError] to
 * produce a fallback result.
 *
 * Use this instead of a bare `catch (e: Exception)` around suspending
 * transport/API calls so that leaving a tab — which cancels the owning
 * coroutine scope via [CancellationException] — is never reported as a
 * user-facing "job failed" error.
 */
inline fun <T> catchExceptCancellation(onError: (Exception) -> T, block: () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }
}

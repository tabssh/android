package io.github.tabssh.utils.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tracks the most recently launched coroutine and cancels it before starting
 * the next one, so only the last-requested load can ever apply its result.
 *
 * Guards against a caller re-entering its load path twice in quick
 * succession — e.g. a Fragment's `sessionFlow` emission racing a
 * `refreshFlow` tick, or a forced re-acquire re-emitting — where two
 * concurrent loads could otherwise land out of order and let a stale result
 * overwrite a fresher one. Cancelling any previous load makes "last
 * requested" also "last applied".
 */
class SingleFlightLoader {

    private var job: Job? = null

    /** Cancels any load already started through this instance, then launches [block] as the new one. */
    fun launchIn(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        val newJob = scope.launch(block = block)
        job = newJob
        return newJob
    }
}

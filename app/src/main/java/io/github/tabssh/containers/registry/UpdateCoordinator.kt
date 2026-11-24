package io.github.tabssh.containers.registry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * One lock per container host, shared by everything that checks or applies updates on it. A check
 * reads the running image and writes the pending flag; an apply replaces that image and clears the
 * flag. Run at the same time, a check that read the old image could re-flag a container the apply had
 * just updated, so the Container Updates list kept showing updates that were already installed.
 */
object UpdateCoordinator {

    private val locks = ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(hostId: Long): Mutex = locks.getOrPut(hostId) { Mutex() }

    /** Runs [block] with exclusive check/apply access to [hostId]; waits for any other holder. */
    suspend fun <T> withHost(hostId: Long, block: suspend () -> T): T = mutexFor(hostId).withLock { block() }
}

package io.github.tabssh.containers.registry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the last update-check cycle actually did per host, so the Updates page can say so instead
 * of showing an old pending list as if it had just been confirmed. In memory only: it describes
 * this process's most recent cycle.
 */
object UpdateCheckStatus {

    enum class Outcome {
        /** Containers were listed and checked against their registries. */
        CHECKED,
        /** No usable SSH session to the host — its list is the last known state, not confirmed. */
        NO_CONNECTION,
        /** Connected, but the container list could not be read. */
        LIST_FAILED,
        /** The device has no validated internet connection, so nothing was checked. */
        NO_NETWORK
    }

    data class HostResult(
        val outcome: Outcome,
        val checkedAt: Long,
        /** Containers whose registry or inspect check failed this cycle. */
        val failedChecks: Int = 0,
        val firstFailure: String? = null
    )

    private val _results = MutableStateFlow<Map<Long, HostResult>>(emptyMap())
    val results: StateFlow<Map<Long, HostResult>> = _results.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun record(hostId: Long, result: HostResult) {
        _results.value = _results.value + (hostId to result)
    }
}

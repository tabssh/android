package io.github.tabssh.hypervisor.console

import io.github.tabssh.utils.logging.Logger

/**
 * A single attempt at obtaining a console handle for a VM.
 *
 * "Console handle" here is deliberately open-ended — the result type [T] is
 * whatever the calling connector needs (a Proxmox `TermProxyResult` + protocol
 * tag, an XCP-ng console URL, a SPICE `.vv` config, …).  Keeping the chain
 * generic means new transports add a strategy without touching this interface.
 *
 * Per the project's silent-fallback rule, a strategy MAY throw to mean
 * "this attempt did not work, try the next one".  The chain logs every
 * intermediate failure at INFO level and only re-raises the last exception
 * after exhausting every strategy.  A strategy MUST NOT call
 * [ConsoleEventListener.onError] itself — only the chain owner knows whether
 * a failure is final.
 *
 * Implementations should be cheap to construct: the chain may instantiate
 * several strategies up front and discard the ones it never runs.
 */
interface ConsoleStrategy<out T> {
    /**
     * Short human-readable label used only in `Logger.i` lines such as
     * `"strategy termproxy failed: serial interface not defined"`.  Keep it
     * lowercase and free of trailing punctuation.
     */
    val name: String

    /**
     * Attempt to resolve a console handle.  Returning normally signals success;
     * throwing any exception signals "fall through to the next strategy".
     *
     * [kotlinx.coroutines.CancellationException] is re-thrown by the chain
     * without being treated as a fallback trigger — implementations should
     * either ignore cancellation or propagate it.
     */
    suspend fun resolve(): T
}

/**
 * Runs an ordered list of [ConsoleStrategy] instances and returns the first
 * one that resolves cleanly.
 *
 * Intermediate failures are logged at INFO with the strategy name and the
 * exception message — never surfaced.  Only when every strategy has thrown
 * does the chain re-raise the last exception so the caller can convert it
 * into a single user-visible error.  This implements the project rule
 * "Fallbacks are not errors … silent until the last fallback fails, then it's
 * an actual error."
 */
class ConsoleStrategyChain<T>(private val strategies: List<ConsoleStrategy<T>>) {
    companion object { private const val TAG = "ConsoleStrategyChain" }

    init {
        require(strategies.isNotEmpty()) { "ConsoleStrategyChain requires at least one strategy" }
    }

    /**
     * Try each strategy in declared order.  Returns the first successful
     * value; rethrows the last exception when every strategy fails.
     */
    suspend fun resolve(): T {
        var lastError: Exception? = null
        for ((index, strategy) in strategies.withIndex()) {
            try {
                val result = strategy.resolve()
                Logger.i(TAG, "strategy ${strategy.name} succeeded (attempt ${index + 1}/${strategies.size})")
                return result
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                Logger.i(TAG, "strategy ${strategy.name} failed: $msg")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("ConsoleStrategyChain: no strategy ran")
    }
}

package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability

/**
 * Raised when an operation is asked for a concept its engine does not have —
 * compose stacks on Incus, disk usage on LXC/LXD. Carries the typed
 * [capability] and [engine] so a caller can react without string matching;
 * [EngineCapabilityGate] converts it to a [ContainerResult] for the suspend
 * surface, while Flow-returning operations throw it into the stream so the
 * collector sees the same typed failure.
 */
class CapabilityUnsupportedException(
    val engine: ContainerEngine,
    val capability: EngineCapability
) : Exception(ContainerTransportMessages.CAPABILITY_UNSUPPORTED) {

    /** The failure as a transport result, with the engine/capability detail. */
    fun asResult(): ContainerResult<Nothing> = ContainerResult.TransportUnavailable(
        ContainerTransportMessages.CAPABILITY_UNSUPPORTED,
        detail = ContainerTransportMessages.capabilityDetail(engine, capability)
    )
}

/**
 * Fail-fast capability check for the transport layer.
 *
 * The point is that a request for something the engine cannot do never reaches
 * the host: no command is built, no socket is dialled, and the caller gets a
 * typed refusal instead of a remote "unknown command" it would have to parse.
 * Hiding the corresponding tabs is a separate, UI-level concern — this gate is
 * the backstop that keeps a stale UI, a deep link or a background worker from
 * issuing the request anyway.
 */
class EngineCapabilityGate(val engine: ContainerEngine) {

    /** True when [engine] has [capability]. */
    fun supports(capability: EngineCapability): Boolean = engine.supports(capability)

    /**
     * The refusal for [capability], or null when the engine has it — so a
     * caller reads as `gate.reject(CAP) ?: doTheWork()`.
     */
    fun reject(capability: EngineCapability): ContainerResult<Nothing>? =
        if (engine.supports(capability)) null else refusal(capability).asResult()

    /** The typed exception for [capability], for Flow and streaming paths. */
    fun refusal(capability: EngineCapability): CapabilityUnsupportedException =
        CapabilityUnsupportedException(engine, capability)

    /** Throw unless [engine] has [capability]. */
    fun require(capability: EngineCapability) {
        if (!engine.supports(capability)) throw refusal(capability)
    }
}

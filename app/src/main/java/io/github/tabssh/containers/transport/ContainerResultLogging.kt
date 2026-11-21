package io.github.tabssh.containers.transport

import io.github.tabssh.utils.logging.Logger

/**
 * One-line, log-safe description of a failed [ContainerResult]: the failure kind, its
 * message and the first part of the engine's own error text. Request arguments are never
 * included; the Logger masks credential patterns in what remains.
 */
internal fun ContainerResult<*>.failureSummary(): String {
    val (kind, message, detail) = when (this) {
        is ContainerResult.Success -> return "success"
        is ContainerResult.PermissionDenied -> Triple("permission denied", message, detail)
        is ContainerResult.NotFound -> Triple("not found", message, detail)
        is ContainerResult.EngineNotInstalled -> Triple("engine not installed", message, detail)
        is ContainerResult.TransportUnavailable -> Triple("transport unavailable", message, detail)
        is ContainerResult.Error -> Triple("error", message, detail)
    }
    val text = detail?.trim()?.takeIf { it.isNotEmpty() }?.take(DETAIL_LOG_CHARS)
    return if (text == null) "$kind: $message" else "$kind: $message | $text"
}

/** Logs a failed result as a warning under [tag] and returns it unchanged. */
internal fun <T> ContainerResult<T>.logFailure(tag: String, context: String): ContainerResult<T> {
    if (this !is ContainerResult.Success) Logger.w(tag, "$context failed: ${failureSummary()}")
    return this
}

private const val DETAIL_LOG_CHARS = 300

package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger

/**
 * Where a container engine's API actually lives, once the per-host override
 * has been classified and (for unix sockets) probed on the remote host.
 *
 * [ContainerHost.socketPath] is a single string that may be blank (probe the
 * engine's own defaults), an absolute unix path, `tcp://host:port`, or
 * `ssh://user@host`; those four cases behave nothing alike at the transport
 * layer, so they become distinct types here instead of string checks spread
 * across the relay, the ladder and the CLI tier.
 */
sealed class ContainerEndpoint {

    /** A unix socket on the SSH host, forwarded with direct-streamlocal. */
    data class UnixSocket(val path: String) : ContainerEndpoint()

    /**
     * A TCP endpoint reachable FROM the SSH host, forwarded with direct-tcpip.
     * The address is resolved on the remote side, so `tcp://127.0.0.1:2375`
     * means the container host's own loopback, not the device's.
     */
    data class TcpForward(val remoteHost: String, val remotePort: Int) : ContainerEndpoint()

    /**
     * A nested `ssh://user@host` target. The engine CLI on the SSH host makes
     * this hop itself, so it is usable only through tiers that go through that
     * CLI (dial-stdio and cli_exec), never through a socket forward.
     */
    data class NestedSsh(val target: String) : ContainerEndpoint()
}

/** The three endpoint shapes, decidable from the stored string alone. */
enum class ContainerEndpointKind { UNIX, TCP, SSH }

/**
 * Resolves a host's engine endpoint once per session and caches the answer.
 *
 * A blank [ContainerHost.socketPath] means "probe this engine's default
 * locations in order and take the first one that is really a socket"; the
 * probe is a single remote `[ -S ]` sweep ([buildSocketProbeCommand]) rather
 * than one exec per candidate, and its result is cached for the life of this
 * instance — the detector creates one resolver per session and hands it to
 * every tier, so no request ever re-probes.
 *
 * `tcp://` and `ssh://` overrides are never probed with `-S`: they are not
 * unix sockets. Both are Docker-API concepts, so they are rejected up front
 * for Incus and LXC/LXD, which address remote daemons through their own
 * remote/certificate model instead.
 */
class EngineSocketResolver(
    private val host: ContainerHost,
    private val runner: SshExecRunner
) {

    companion object {
        private const val TAG = "EngineSocketResolver"
        private const val PROBE_TIMEOUT_MS = 20_000L

        /** Classify a stored [socketPath] without touching the network. */
        fun classify(socketPath: String): ContainerEndpointKind = when {
            socketPath.startsWith("tcp://") -> ContainerEndpointKind.TCP
            socketPath.startsWith("ssh://") -> ContainerEndpointKind.SSH
            else -> ContainerEndpointKind.UNIX
        }

        /**
         * Parse a `tcp://host:port` override. IPv6 literals use the URL form
         * `tcp://[::1]:2375`. Returns null when the authority is malformed or
         * the port is missing or out of range — the caller turns that into a
         * user-visible error rather than guessing a port.
         */
        fun parseTcp(socketPath: String): ContainerEndpoint.TcpForward? {
            val authority = socketPath.removePrefix("tcp://").trim()
            if (authority.isEmpty()) return null
            val hostPart: String
            val portPart: String
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                if (close < 0 || authority.getOrNull(close + 1) != ':') return null
                hostPart = authority.substring(1, close)
                portPart = authority.substring(close + 2)
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon <= 0) return null
                hostPart = authority.substring(0, colon)
                portPart = authority.substring(colon + 1)
            }
            val port = portPart.toIntOrNull() ?: return null
            if (hostPart.isEmpty() || port !in 1..65535) return null
            return ContainerEndpoint.TcpForward(hostPart, port)
        }

        /** Parse an `ssh://user@host` override into its bare target. */
        fun parseSshTarget(socketPath: String): String? =
            socketPath.removePrefix("ssh://").trim().takeIf { it.isNotEmpty() }

        /**
         * Build the one-shot remote sweep over [candidates].
         *
         * When [trusted] the candidates are [ContainerEngine.defaultSocketPaths]
         * constants, which deliberately contain shell expansions (Podman's
         * rootless socket lives under `/run/user/$(id -u)`), so they are
         * interpolated verbatim; a user-supplied override is single-quoted
         * instead, because it is untrusted input.
         *
         * A candidate that exists but is unreadable does not stop the sweep —
         * a later candidate may still work (rootful Podman denied, rootless
         * fine) — but the first denied path is remembered and reported when no
         * candidate turns out usable, so the permission remediation is not
         * lost behind a bare "not found".
         */
        fun buildSocketProbeCommand(candidates: List<String>, trusted: Boolean): String {
            val list = candidates.joinToString(" ") {
                if (trusted) it else SshExecRunner.shQuote(it)
            }
            return "denied=''; for p in $list; do " +
                "if [ -S \"\$p\" ]; then " +
                "if [ -r \"\$p\" ] && [ -w \"\$p\" ]; then printf 'ok\\n%s\\n' \"\$p\"; exit 0; fi; " +
                "if [ -z \"\$denied\" ]; then denied=\"\$p\"; fi; " +
                "fi; done; " +
                "if [ -n \"\$denied\" ]; then printf 'denied\\n%s\\n' \"\$denied\"; " +
                "else printf 'missing\\n\\n'; fi"
        }

        /**
         * Parse [buildSocketProbeCommand] output: a state line followed by the
         * path it refers to. Anything else is treated as [SocketProbe.Missing]
         * — a shell that answered with something unrecognisable has not proven
         * a socket exists.
         */
        fun parseSocketProbeOutput(stdout: String): SocketProbe {
            val lines = stdout.trim().lines()
            val state = lines.getOrElse(0) { "" }.trim()
            val path = lines.getOrElse(1) { "" }.trim()
            return when {
                state == "ok" && path.isNotEmpty() -> SocketProbe.Ok(path)
                state == "denied" && path.isNotEmpty() -> SocketProbe.Denied(path)
                else -> SocketProbe.Missing
            }
        }
    }

    /** Outcome of the remote socket sweep. */
    sealed class SocketProbe {
        data class Ok(val path: String) : SocketProbe()
        data class Denied(val path: String) : SocketProbe()
        data object Missing : SocketProbe()
    }

    val engine: ContainerEngine = host.engineType()

    /** Endpoint shape for this host — pure, so tier selection needs no probe. */
    val kind: ContainerEndpointKind = classify(host.socketPath)

    // Written by whichever Dispatchers.IO thread resolves first and read by
    // every later tier on possibly another thread.
    @Volatile
    private var cached: ContainerEndpoint? = null

    /**
     * The resolved endpoint, probing the remote host at most once. Failures
     * are not cached: a socket that appears after the engine finishes starting
     * is found by the next call instead of being permanently written off.
     */
    suspend fun resolve(): ContainerResult<ContainerEndpoint> {
        cached?.let { return ContainerResult.Success(it) }
        return when (kind) {
            ContainerEndpointKind.TCP -> resolveTcp()
            ContainerEndpointKind.SSH -> resolveSsh()
            ContainerEndpointKind.UNIX -> resolveUnix()
        }
    }

    private fun resolveTcp(): ContainerResult<ContainerEndpoint> {
        if (!engine.speaksDockerApi) {
            return ContainerResult.TransportUnavailable(
                ContainerTransportMessages.networkEndpointUnsupported(engine),
                detail = host.socketPath
            )
        }
        val parsed = parseTcp(host.socketPath)
            ?: return ContainerResult.Error(
                ContainerTransportMessages.ENDPOINT_MALFORMED,
                detail = host.socketPath
            )
        cached = parsed
        return ContainerResult.Success(parsed)
    }

    private fun resolveSsh(): ContainerResult<ContainerEndpoint> {
        if (!engine.speaksDockerApi) {
            return ContainerResult.TransportUnavailable(
                ContainerTransportMessages.networkEndpointUnsupported(engine),
                detail = host.socketPath
            )
        }
        val target = parseSshTarget(host.socketPath)
            ?: return ContainerResult.Error(
                ContainerTransportMessages.ENDPOINT_MALFORMED,
                detail = host.socketPath
            )
        val endpoint = ContainerEndpoint.NestedSsh(target)
        cached = endpoint
        return ContainerResult.Success(endpoint)
    }

    private suspend fun resolveUnix(): ContainerResult<ContainerEndpoint> {
        val candidates = host.socketCandidates()
        // Blank socketPath = engine defaults, which are trusted constants that
        // may carry a deliberate `$(id -u)`; anything else came from the user.
        val trusted = host.socketPath.isBlank()
        return try {
            val result = runner.run(buildSocketProbeCommand(candidates, trusted), PROBE_TIMEOUT_MS)
            when (val probe = parseSocketProbeOutput(result.stdout)) {
                is SocketProbe.Ok -> {
                    val endpoint = ContainerEndpoint.UnixSocket(probe.path)
                    cached = endpoint
                    Logger.d(TAG, "resolved ${engine.id} socket for host ${host.id}")
                    ContainerResult.Success(endpoint)
                }
                is SocketProbe.Denied -> ContainerResult.PermissionDenied(
                    ContainerTransportMessages.socketPermission(engine),
                    detail = probe.path
                )
                SocketProbe.Missing -> ContainerResult.TransportUnavailable(
                    ContainerTransportMessages.socketMissing(engine),
                    detail = candidates.joinToString(", ")
                )
            }
        } catch (e: TransportUnavailableException) {
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "socket probe failed: ${e.message}")
            // Unavailable rather than Error: a probe that could not run has not
            // proven the engine is unusable, and the CLI tier still gets a turn.
            ContainerResult.TransportUnavailable(
                ContainerTransportMessages.socketMissing(engine),
                detail = e.message
            )
        }
    }
}

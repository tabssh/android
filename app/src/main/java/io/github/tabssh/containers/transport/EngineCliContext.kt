package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.entities.ContainerHost

/**
 * How the remote engine CLI is invoked for one host: which binary to run and
 * which environment assignment (if any) points it at the resolved endpoint.
 *
 * Every engine has its own variable — `DOCKER_HOST` for Docker, `CONTAINER_HOST`
 * for Podman, `INCUS_SOCKET` for Incus, `LXD_SOCKET` for LXC/LXD — and only
 * the Docker-API pair accepts a `tcp://` or `ssh://` value, so the mapping
 * lives here rather than as a `when` inside each transport.
 *
 * The prefix is emitted only when the resolved endpoint differs from the
 * engine's own first default: on an ordinary host the CLI already knows where
 * its daemon is, and overriding it would only add a way to get it wrong.
 */
class EngineCliContext(
    private val host: ContainerHost,
    private val resolver: EngineSocketResolver
) {

    companion object {

        /** The environment variable each engine's CLI reads for its endpoint. */
        fun endpointEnvVar(engine: ContainerEngine): String = when (engine) {
            ContainerEngine.DOCKER -> "DOCKER_HOST"
            ContainerEngine.PODMAN -> "CONTAINER_HOST"
            ContainerEngine.INCUS -> "INCUS_SOCKET"
            ContainerEngine.LXD -> "LXD_SOCKET"
        }

        /**
         * The value that variable takes for [endpoint]. The Docker-API engines
         * use URL syntax (`unix://`, `tcp://`, `ssh://`); Incus and LXC/LXD
         * take a bare socket path, and never reach the network branches — the
         * resolver rejects those overrides for them before this is called.
         */
        fun endpointEnvValue(engine: ContainerEngine, endpoint: ContainerEndpoint): String =
            when (endpoint) {
                is ContainerEndpoint.UnixSocket ->
                    if (engine.speaksDockerApi) "unix://${endpoint.path}" else endpoint.path
                is ContainerEndpoint.TcpForward ->
                    "tcp://${endpoint.remoteHost}:${endpoint.remotePort}"
                is ContainerEndpoint.NestedSsh -> "ssh://${endpoint.target}"
            }

        /**
         * Full `VAR=value ` prefix for a remote command, or an empty string
         * when [endpoint] is the engine's own default socket and the CLI needs
         * no steering. The value is shell-quoted — a socket path can come from
         * the user, and a `tcp://`/`ssh://` authority always does.
         */
        fun buildEnvPrefix(engine: ContainerEngine, endpoint: ContainerEndpoint): String {
            if (endpoint is ContainerEndpoint.UnixSocket &&
                endpoint.path == engine.defaultSocketPaths.first()
            ) {
                return ""
            }
            val value = endpointEnvValue(engine, endpoint)
            return "${endpointEnvVar(engine)}=${SshExecRunner.shQuote(value)} "
        }
    }

    /** The remote binary to invoke — per-host override or the engine's own. */
    val binary: String = host.cliBinary()

    // Resolved once per instance and read from any later Dispatchers.IO thread.
    @Volatile
    private var prefix: String? = null

    /**
     * The environment prefix for every CLI invocation on this host. An endpoint
     * that cannot be resolved yields no prefix rather than an error: the CLI
     * tier's whole point is to work when the socket does not, and the engine's
     * own default context is then the best available answer. Only a resolved
     * endpoint is cached, so a socket that appears once the engine finishes
     * starting is still picked up.
     */
    suspend fun envPrefix(): String {
        prefix?.let { return it }
        val endpoint = resolver.resolve().valueOrNull() ?: return ""
        val resolved = buildEnvPrefix(resolver.engine, endpoint)
        prefix = resolved
        return resolved
    }
}

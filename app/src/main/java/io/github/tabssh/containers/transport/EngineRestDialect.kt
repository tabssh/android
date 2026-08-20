package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.entities.ContainerHost
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The seam between the transport ladder and one engine's REST dialect.
 *
 * Everything above this interface — socket resolution, the SSH relay, tier
 * selection, persistence of the winning tier — is engine-independent. What
 * differs is the wire dialect behind the forwarded socket: Docker and Podman
 * speak the Docker Engine API, while Incus and LXC/LXD speak their own. A new
 * dialect is added by implementing this interface and registering it in
 * [EngineRestDialects]; no tier, relay or detector code changes.
 *
 * [DockerRestDialect] covers Docker and Podman; [IncusRestDialect] covers Incus
 * and LXC/LXD, whose daemons speak the same `/1.0` REST API as each other.
 */
interface EngineRestDialect {

    /** The engine this dialect speaks for. */
    val engine: ContainerEngine

    /**
     * True when `<cli> system dial-stdio` exists for this engine, i.e. whether
     * the api_stdio tier is worth attempting at all. False means the tier is
     * skipped entirely rather than attempted and failed.
     */
    val supportsDialStdio: Boolean

    /**
     * Verify the daemon answers on [baseUrl] and return what it reports.
     * [client] is already configured for the relay (token socket factory,
     * timeouts); the dialect only supplies the path and the parser.
     */
    suspend fun probeVersion(
        baseUrl: String,
        client: OkHttpClient
    ): ContainerResult<ContainerEngineVersion>

    /** Build the REST-tier transport once [relay] is open and verified. */
    fun createTransport(
        host: ContainerHost,
        relay: SocketRelay,
        runner: SshExecRunner
    ): ContainerTransport
}

/**
 * Docker Engine API dialect. Podman ships a Docker-compatible endpoint on its
 * own socket, so one implementation covers both engines and the existing
 * [DockerApiParsers] apply unchanged; only the engine identity differs, which
 * is what selects the right remediation text further up.
 */
class DockerRestDialect(override val engine: ContainerEngine) : EngineRestDialect {

    private companion object {
        /** Cap on the probe's `GET /version` body (the real one is under 1 KiB). */
        private const val MAX_VERSION_BODY_BYTES = 256L * 1024
    }

    override val supportsDialStdio: Boolean = true

    override suspend fun probeVersion(
        baseUrl: String,
        client: OkHttpClient
    ): ContainerResult<ContainerEngineVersion> = try {
        val request = Request.Builder().url("$baseUrl/version").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                ContainerResult.Error("GET /version failed", "HTTP ${response.code}")
            } else {
                // Bounded: whatever answers on the relay port decides this
                // body's size, and /version is a small object.
                DockerApiParsers.parseVersion(response.peekBody(MAX_VERSION_BODY_BYTES).string())
                    ?.let { ContainerResult.Success(it) }
                    ?: ContainerResult.Error("GET /version returned an unparsable body")
            }
        }
    } catch (e: java.io.IOException) {
        ContainerResult.TransportUnavailable("GET /version failed", e.message)
    }

    // The relay's resolver already probed this host's socket; reusing it keeps
    // the transport's CLI-backed operations on the same endpoint without a
    // second sweep.
    override fun createTransport(
        host: ContainerHost,
        relay: SocketRelay,
        runner: SshExecRunner
    ): ContainerTransport = EngineApiTransport(host, relay, runner, relay.resolver)
}

/**
 * Incus / LXC-LXD dialect. Both daemons publish the same `/1.0` API — LXD is
 * the project Incus forked from — so one implementation parameterised by the
 * engine identity covers both; only the remediation text further up differs.
 *
 * Neither binary has a `system dial-stdio` subcommand, so [supportsDialStdio]
 * is false and the ladder skips the api_stdio tier for these engines instead of
 * attempting and failing it.
 */
class IncusRestDialect(override val engine: ContainerEngine) : EngineRestDialect {

    private companion object {
        /** Cap on the probe's `GET /1.0` body (the real one is a few KiB). */
        private const val MAX_VERSION_BODY_BYTES = 256L * 1024
    }

    override val supportsDialStdio: Boolean = false

    override suspend fun probeVersion(
        baseUrl: String,
        client: OkHttpClient
    ): ContainerResult<ContainerEngineVersion> = try {
        val request = Request.Builder().url("$baseUrl/1.0").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                ContainerResult.Error("GET /1.0 failed", "HTTP ${response.code}")
            } else {
                // Bounded: whatever answers on the relay port decides this
                // body's size, and /1.0 is a small object.
                IncusApiParsers.parseServerVersion(response.peekBody(MAX_VERSION_BODY_BYTES).string())
                    ?.let { ContainerResult.Success(it) }
                    ?: ContainerResult.Error("GET /1.0 returned an unparsable body")
            }
        }
    } catch (e: java.io.IOException) {
        ContainerResult.TransportUnavailable("GET /1.0 failed", e.message)
    }

    // The relay's resolver already probed this host's socket; reusing it keeps
    // the transport's CLI-backed operations on the same endpoint without a
    // second sweep.
    override fun createTransport(
        host: ContainerHost,
        relay: SocketRelay,
        runner: SshExecRunner
    ): ContainerTransport = IncusApiTransport(host, relay, runner, relay.resolver)
}

/** Registry of the REST dialects this build can speak. */
object EngineRestDialects {

    /**
     * The dialect for [engine]. Every supported engine has one: the Docker
     * family speaks the Docker Engine API, Incus and LXC/LXD speak `/1.0`.
     */
    fun forEngine(engine: ContainerEngine): EngineRestDialect =
        if (engine.speaksDockerApi) DockerRestDialect(engine) else IncusRestDialect(engine)
}

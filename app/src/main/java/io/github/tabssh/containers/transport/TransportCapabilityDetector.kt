package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.dao.ContainerHostDao
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Engine-driven transport detection. The tiers are:
 *  (a)  direct-streamlocal relay + the engine's version endpoint
 *  (a') direct-tcpip relay + the engine's version endpoint (`tcp://` hosts)
 *  (b)  `<cli> system dial-stdio` relay + the engine's version endpoint
 *  (c)  the engine's CLI over SSH exec
 *
 * Which of those are attempted is decided by [ladderFor] from the engine and
 * the shape of the configured endpoint, not by a fixed list. Tier (b) exists
 * only for engines whose REST dialect advertises the verb — Incus and LXC/LXD
 * never attempt it, so it can never appear in their failure list. Tier (a') is
 * offered only for a `tcp://` endpoint, and tier (a) only for a unix socket.
 *
 * The winning tier is persisted to [ContainerHost.transportMode] via
 * [ContainerHostDao]; a stored API tier short-circuits detection until the user
 * forces a retest ([detect] with force=true) — the tier is never silently
 * downgraded behind the user's back. A stored "cli_exec" (the bottom tier)
 * never short-circuits: the full ladder re-runs on every detect so the host
 * auto-upgrades the moment a better tier starts working, and the per-tier
 * failure reasons are logged each time instead of being hidden behind the
 * pin. Pinning is an allowlist, not an exclusion list: only [PINNABLE_MODES]
 * ever short-circuit detection, and only when the pinned tier is also in this
 * host's computed ladder — so a stored value that is a removed legacy tier
 * name, a corrupt value, or a tier this engine no longer has falls through to
 * full detection exactly like [MODE_AUTO]; the freshly detected tier is
 * persisted over it.
 *
 * Socket resolution runs once per detection and is shared with every tier: one
 * [EngineSocketResolver] probes the engine's candidate paths on the host, and
 * "permission denied" is surfaced as [ContainerResult.PermissionDenied]
 * carrying that engine's own remediation ([ContainerTransportMessages.socketPermission])
 * instead of falling through — the CLI talks to the same socket and would fail
 * identically.
 */
class TransportCapabilityDetector(
    private val dao: ContainerHostDao
) {

    internal companion object {
        private const val TAG = "TransportCapabilityDetector"
        private const val PROBE_TIMEOUT_S = 10L

        const val MODE_AUTO = "auto"
        const val MODE_API_STREAMLOCAL = "api_streamlocal"
        const val MODE_API_TCP = "api_tcp"
        const val MODE_API_STDIO = "api_stdio"
        const val MODE_CLI_EXEC = "cli_exec"

        /**
         * Modes that can short-circuit detection as a stored pin. cli_exec
         * is deliberately excluded (see class doc — it always re-runs the
         * ladder), and this is an allowlist rather than an "everything but
         * auto/cli_exec" exclusion so an unrecognised stored value can never
         * be mistaken for a real pin.
         */
        internal val PINNABLE_MODES = setOf(MODE_API_STREAMLOCAL, MODE_API_TCP, MODE_API_STDIO)

        /** Every mode this detector currently understands, pinnable or not. */
        private val KNOWN_MODES = PINNABLE_MODES + MODE_AUTO + MODE_CLI_EXEC

        /**
         * The tiers worth attempting for [engine] at an endpoint of [kind], in
         * order. Pure decision logic, so the per-engine ladder is testable
         * without a host: a unix socket can be forwarded directly, a `tcp://`
         * address needs the TCP forward instead, and a nested `ssh://` target
         * can only be reached by the remote CLI's own second hop — which is
         * exactly what dial-stdio is. The dial-stdio tier is present only when
         * the engine's REST dialect advertises the verb, so engines without it
         * skip the tier entirely rather than attempting and failing it.
         */
        internal fun ladderFor(
            engine: ContainerEngine,
            kind: ContainerEndpointKind
        ): List<String> {
            val dialStdio = EngineRestDialects.forEngine(engine).supportsDialStdio
            return when (kind) {
                ContainerEndpointKind.UNIX -> buildList {
                    add(MODE_API_STREAMLOCAL)
                    if (dialStdio) add(MODE_API_STDIO)
                    add(MODE_CLI_EXEC)
                }
                ContainerEndpointKind.TCP -> buildList {
                    add(MODE_API_TCP)
                    add(MODE_CLI_EXEC)
                }
                ContainerEndpointKind.SSH -> buildList {
                    if (dialStdio) add(MODE_API_STDIO)
                    add(MODE_CLI_EXEC)
                }
            }
        }
    }

    /** Outcome of a successful detection. */
    data class DetectedTransport(
        /** Persisted ContainerHost.transportMode value. */
        val mode: String,
        /** Ready-to-use transport for the winning tier. */
        val transport: ContainerTransport,
        /** The relay backing an API-tier transport; null for cli_exec. */
        val relay: SocketRelay?
    )

    // No connection reuse: pooled-connection reuse against the SSH socket
    // relay hangs (see EngineApiTransport.client), and a pooled probe
    // connection could even outlive its relay and match a later relay's
    // reused ephemeral port. One fresh connection per probe.
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("Connection", "close").build()
            )
        }
        .build()

    /**
     * Detect (or re-detect with [force]) the best transport tier for [host].
     * A stored API-tier mode is honored unless [force] is set; a stored
     * cli_exec re-runs the full ladder (see class doc). The winning tier is
     * persisted before returning.
     */
    suspend fun detect(
        host: ContainerHost,
        runner: SshExecRunner,
        force: Boolean = false
    ): ContainerResult<DetectedTransport> = withContext(Dispatchers.IO) {
        // A stored cli_exec is the bottom tier — never fast-path it, or the
        // host stays pinned there forever and the API-tier failure reasons
        // are never logged. Re-running the ladder is what auto-upgrades it.
        val storedMode = host.transportMode
        Logger.d(TAG, "detect: host=${host.id} storedMode=$storedMode force=$force")
        if (storedMode !in KNOWN_MODES) {
            Logger.w(
                TAG,
                "detect: host=${host.id} unrecognised stored mode '$storedMode' — " +
                    "treating as $MODE_AUTO and running full detection"
            )
        }

        val engine = host.engineType()
        val kind = EngineSocketResolver.classify(host.socketPath)
        // One resolver for the whole detection: the remote probe runs once and
        // every tier that follows reuses the path it found.
        val resolver = EngineSocketResolver(host, runner)
        val ladder = ladderFor(engine, kind)
        Logger.d(TAG, "detect: host=${host.id} engine=${engine.id} endpoint=$kind ladder=$ladder")

        val pinned = storedMode.takeIf { it in PINNABLE_MODES && it in ladder && !force }
        if (pinned != null) {
            Logger.d(TAG, "detect: host=${host.id} using pinned tier $pinned")
            return@withContext openTier(pinned, host, runner, resolver)
        }

        val failures = mutableListOf<String>()

        // Log each tier's failure the moment it happens — when a lower tier
        // later succeeds, these are the only record of WHY the better tiers
        // were skipped (the collected list is surfaced only on total failure).
        fun recordFailure(mode: String, message: String, detail: String?) {
            val entry = "$mode: $message${detail?.let { " ($it)" } ?: ""}"
            Logger.w(TAG, "tier failed — $entry")
            failures += entry
        }

        // Endpoint resolution is the gate for every API tier: it is what finds
        // the engine's socket, and a permission failure there is terminal
        // because the CLI would hit the same socket with the same user.
        val attemptable = when (val endpoint = resolver.resolve()) {
            is ContainerResult.Success -> ladder
            is ContainerResult.PermissionDenied -> return@withContext endpoint
            // A rejected or malformed tcp://ssh:// override is a configuration
            // error, not a reason to silently fall back to the default context.
            is ContainerResult.NotFound -> return@withContext endpoint
            is ContainerResult.EngineNotInstalled -> return@withContext endpoint
            is ContainerResult.Error -> return@withContext endpoint
            is ContainerResult.TransportUnavailable -> {
                if (kind != ContainerEndpointKind.UNIX) return@withContext endpoint
                // No socket found, but the CLI can still reach a daemon the
                // probe cannot see (a rootless socket outside this user's
                // view, a wrapper on PATH) — so the bottom tier still runs.
                recordFailure("endpoint", endpoint.message, endpoint.detail)
                listOf(MODE_CLI_EXEC)
            }
        }

        for (mode in attemptable) {
            when (val attempt = openTier(mode, host, runner, resolver)) {
                is ContainerResult.Success -> {
                    persist(host, mode)
                    return@withContext attempt
                }
                is ContainerResult.PermissionDenied -> return@withContext attempt
                // The engine's own CLI is absent from the remote host: no
                // lower tier can succeed where this one failed, and the fix is
                // installing it, so this is reported instead of collected.
                is ContainerResult.EngineNotInstalled -> return@withContext attempt
                is ContainerResult.NotFound -> recordFailure(mode, attempt.message, null)
                is ContainerResult.TransportUnavailable ->
                    recordFailure(mode, attempt.message, attempt.detail)
                is ContainerResult.Error ->
                    recordFailure(mode, attempt.message, attempt.detail)
            }
        }

        ContainerResult.TransportUnavailable(
            ContainerTransportMessages.allTiersFailed(engine),
            detail = failures.joinToString("; ")
        )
    }

    /** Open and verify one specific tier. */
    private suspend fun openTier(
        mode: String,
        host: ContainerHost,
        runner: SshExecRunner,
        resolver: EngineSocketResolver
    ): ContainerResult<DetectedTransport> = when (mode) {
        MODE_API_STREAMLOCAL, MODE_API_TCP, MODE_API_STDIO ->
            openApiTier(mode, host, runner, resolver)
        MODE_CLI_EXEC -> verifyCliTier(host, runner, resolver)
        else -> ContainerResult.Error("Unknown transport mode", mode)
    }

    /**
     * Open the relay for [mode], then verify it with the engine's own version
     * request. Everything engine-specific — which path answers, how the body
     * parses, which transport wraps the relay — comes from the dialect, so a
     * new engine's REST client needs no change here.
     */
    private suspend fun openApiTier(
        mode: String,
        host: ContainerHost,
        runner: SshExecRunner,
        resolver: EngineSocketResolver
    ): ContainerResult<DetectedTransport> {
        val dialect = EngineRestDialects.forEngine(host.engineType())
        val relay = SocketRelay(host, runner, resolver)
        return try {
            val port = when (mode) {
                MODE_API_STREAMLOCAL -> relay.openStreamLocal()
                MODE_API_TCP -> relay.openTcpForward()
                else -> relay.openDialStdio()
            }
            when (val probe = probeApiVersion(port, relay.token, dialect)) {
                is ContainerResult.Success -> {
                    Logger.i(TAG, "$mode verified (engine ${probe.value.version}, api ${probe.value.apiVersion})")
                    ContainerResult.Success(
                        DetectedTransport(mode, dialect.createTransport(host, relay, runner), relay)
                    )
                }
                is ContainerResult.PermissionDenied -> {
                    relay.close()
                    probe
                }
                is ContainerResult.NotFound -> {
                    relay.close()
                    probe
                }
                is ContainerResult.EngineNotInstalled -> {
                    relay.close()
                    probe
                }
                is ContainerResult.TransportUnavailable -> {
                    relay.close()
                    probe
                }
                is ContainerResult.Error -> {
                    relay.close()
                    probe
                }
            }
        } catch (e: TransportUnavailableException) {
            relay.close()
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            relay.close()
            ContainerResult.Error("Transport $mode failed", e.message)
        }
    }

    /**
     * The dialect's version request through a relay port, authenticated with
     * [token] — [probeClient] is shared across every host's probes, so the
     * per-relay token socket factory is applied per call via [newBuilder],
     * not baked into the shared client.
     */
    private suspend fun probeApiVersion(
        port: Int,
        token: ByteArray,
        dialect: EngineRestDialect
    ): ContainerResult<ContainerEngineVersion> = withContext(Dispatchers.IO) {
        val tokenClient = probeClient.newBuilder()
            .socketFactory(RelayTokenSocketFactory(token))
            .build()
        dialect.probeVersion("http://127.0.0.1:$port", tokenClient)
    }

    /** Verify the CLI tier with the engine's own version command. */
    private suspend fun verifyCliTier(
        host: ContainerHost,
        runner: SshExecRunner,
        resolver: EngineSocketResolver
    ): ContainerResult<DetectedTransport> {
        val transport = cliTransportFor(host, runner, resolver)
        return when (val version = transport.engineVersion()) {
            is ContainerResult.Success -> {
                Logger.i(TAG, "cli_exec verified (engine ${version.value.version})")
                ContainerResult.Success(DetectedTransport(MODE_CLI_EXEC, transport, null))
            }
            is ContainerResult.PermissionDenied -> version
            is ContainerResult.NotFound -> version
            is ContainerResult.EngineNotInstalled -> version
            is ContainerResult.TransportUnavailable -> version
            is ContainerResult.Error -> version
        }
    }

    /**
     * The CLI-tier transport for this host's engine. The Docker family speaks
     * `--format '{{json .}}'`, Incus and LXC/LXD speak `--format json` and
     * `query`, so each family has its own CLI transport with the same feature
     * coverage; the tier itself is engine-independent.
     */
    private fun cliTransportFor(
        host: ContainerHost,
        runner: SshExecRunner,
        resolver: EngineSocketResolver
    ): ContainerTransport =
        if (host.engineType().speaksDockerApi) CliExecTransport(host, runner, resolver)
        else IncusCliTransport(host, runner, resolver)

    /** Persist the winning tier so reconnects skip detection. */
    private suspend fun persist(host: ContainerHost, mode: String) {
        try {
            dao.update(host.copy(transportMode = mode))
            Logger.i(TAG, "persisted transport tier '$mode' for host ${host.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "failed to persist transport tier: ${e.message}")
        }
    }
}

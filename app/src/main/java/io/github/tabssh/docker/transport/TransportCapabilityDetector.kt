package io.github.tabssh.docker.transport

import io.github.tabssh.storage.database.dao.DockerHostDao
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Three-tier transport detection:
 *  (a) direct-streamlocal relay + unversioned `GET /version`
 *  (b) `docker system dial-stdio` relay + unversioned `GET /version`
 *  (c) `docker version --format '{{json .}}'` over SSH exec
 *
 * The winning tier is persisted to [DockerHost.transportMode] via
 * [DockerHostDao]; a stored API tier short-circuits detection until the user
 * forces a retest ([detect] with force=true) — the tier is never silently
 * downgraded behind the user's back. A stored "cli_exec" (the bottom tier)
 * never short-circuits: the full ladder re-runs on every detect so the host
 * auto-upgrades the moment a better tier starts working, and the per-tier
 * failure reasons are logged each time instead of being hidden behind the
 * pin. Pinning is an allowlist, not an exclusion list: only [PINNABLE_MODES]
 * (the two API tiers) ever short-circuit detection, so any stored value that
 * is not one of the currently-supported tiers — a removed legacy tier name,
 * a corrupt value, anything unrecognised — falls through to full detection
 * exactly like [MODE_AUTO] instead of reaching [openTier] as a real pin; the
 * freshly detected tier is persisted over it.
 *
 * A socket-permission probe runs before the socket tiers: "permission denied"
 * is surfaced as [DockerResult.PermissionDenied] carrying the docker-group
 * remediation text ([DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION])
 * instead of falling through — the CLI talks to the same socket and would
 * fail identically.
 */
class TransportCapabilityDetector(
    private val dao: DockerHostDao
) {

    internal companion object {
        private const val TAG = "TransportCapabilityDetector"
        private const val PROBE_TIMEOUT_S = 10L

        /** Cap on the probe's `GET /version` body (the real one is under 1 KiB). */
        private const val MAX_VERSION_BODY_BYTES = 256L * 1024
        private const val MODE_AUTO = "auto"
        private const val MODE_API_STREAMLOCAL = "api_streamlocal"
        private const val MODE_API_STDIO = "api_stdio"
        private const val MODE_CLI_EXEC = "cli_exec"

        /**
         * Modes that can short-circuit detection as a stored pin. cli_exec
         * is deliberately excluded (see class doc — it always re-runs the
         * ladder), and this is an allowlist rather than an "everything but
         * auto/cli_exec" exclusion so an unrecognised stored value can never
         * be mistaken for a real pin.
         */
        private val PINNABLE_MODES = setOf(MODE_API_STREAMLOCAL, MODE_API_STDIO)

        /** Every mode this detector currently understands, pinnable or not. */
        private val KNOWN_MODES = PINNABLE_MODES + MODE_AUTO + MODE_CLI_EXEC
    }

    /** Outcome of a successful detection. */
    data class DetectedTransport(
        /** Persisted DockerHost.transportMode value. */
        val mode: String,
        /** Ready-to-use transport for the winning tier. */
        val transport: DockerTransport,
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
        host: DockerHost,
        runner: SshExecRunner,
        force: Boolean = false
    ): DockerResult<DetectedTransport> = withContext(Dispatchers.IO) {
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
        val pinned = storedMode.takeIf { it in PINNABLE_MODES && !force }
        if (pinned != null) {
            Logger.d(TAG, "detect: host=${host.id} using pinned tier $pinned")
            return@withContext openTier(pinned, host, runner)
        }

        // Socket-permission gate for the socket-based tiers (a) and (b).
        val socketState = probeSocketAccess(host, runner)
        if (socketState is DockerResult.PermissionDenied) {
            return@withContext socketState
        }
        val socketUsable = socketState is DockerResult.Success && socketState.value

        val failures = mutableListOf<String>()

        // Log each tier's failure the moment it happens — when a lower tier
        // later succeeds, these are the only record of WHY the better tiers
        // were skipped (the collected list is surfaced only on total failure).
        fun recordFailure(mode: String, message: String, detail: String?) {
            val entry = "$mode: $message${detail?.let { " ($it)" } ?: ""}"
            Logger.w(TAG, "tier failed — $entry")
            failures += entry
        }

        if (socketUsable) {
            for (mode in listOf(MODE_API_STREAMLOCAL, MODE_API_STDIO)) {
                when (val attempt = openTier(mode, host, runner)) {
                    is DockerResult.Success -> {
                        persist(host, mode)
                        return@withContext attempt
                    }
                    is DockerResult.PermissionDenied -> return@withContext attempt
                    is DockerResult.NotFound -> recordFailure(mode, attempt.message, null)
                    is DockerResult.TransportUnavailable ->
                        recordFailure(mode, attempt.message, attempt.detail)
                    is DockerResult.Error ->
                        recordFailure(mode, attempt.message, attempt.detail)
                }
            }
        } else if (socketState is DockerResult.Success) {
            recordFailure("socket", DockerTransportMessages.SOCKET_MISSING, null)
        } else {
            recordFailure("socket", "probe failed", null)
        }

        when (val attempt = openTier(MODE_CLI_EXEC, host, runner)) {
            is DockerResult.Success -> {
                persist(host, MODE_CLI_EXEC)
                return@withContext attempt
            }
            is DockerResult.PermissionDenied -> return@withContext attempt
            is DockerResult.NotFound -> recordFailure(MODE_CLI_EXEC, attempt.message, null)
            is DockerResult.TransportUnavailable ->
                recordFailure(MODE_CLI_EXEC, attempt.message, attempt.detail)
            is DockerResult.Error ->
                recordFailure(MODE_CLI_EXEC, attempt.message, attempt.detail)
        }

        DockerResult.TransportUnavailable(
            DockerTransportMessages.ALL_TIERS_FAILED,
            detail = failures.joinToString("; ")
        )
    }

    /** Open and verify one specific tier. */
    private suspend fun openTier(
        mode: String,
        host: DockerHost,
        runner: SshExecRunner
    ): DockerResult<DetectedTransport> = when (mode) {
        MODE_API_STREAMLOCAL -> openApiTier(mode, host, runner)
        MODE_API_STDIO -> openApiTier(mode, host, runner)
        MODE_CLI_EXEC -> verifyCliTier(host, runner)
        else -> DockerResult.Error("Unknown transport mode", mode)
    }

    /** Open a relay (streamlocal or dial-stdio), then verify with GET /version. */
    private suspend fun openApiTier(
        mode: String,
        host: DockerHost,
        runner: SshExecRunner
    ): DockerResult<DetectedTransport> {
        val relay = SocketRelay(host, runner)
        return try {
            val port = if (mode == MODE_API_STREAMLOCAL) {
                relay.openStreamLocal()
            } else {
                relay.openDialStdio()
            }
            when (val probe = probeApiVersion(port, relay.token)) {
                is DockerResult.Success -> {
                    Logger.i(TAG, "$mode verified (engine ${probe.value.version}, api ${probe.value.apiVersion})")
                    DockerResult.Success(
                        DetectedTransport(mode, EngineApiTransport(host, relay, runner), relay)
                    )
                }
                is DockerResult.PermissionDenied -> {
                    relay.close()
                    probe
                }
                is DockerResult.NotFound -> {
                    relay.close()
                    probe
                }
                is DockerResult.TransportUnavailable -> {
                    relay.close()
                    probe
                }
                is DockerResult.Error -> {
                    relay.close()
                    probe
                }
            }
        } catch (e: TransportUnavailableException) {
            relay.close()
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            relay.close()
            DockerResult.Error("Transport $mode failed", e.message)
        }
    }

    /**
     * Unversioned GET /version through a relay port, authenticated with
     * [token] — [probeClient] is shared across every host's probes, so the
     * per-relay token socket factory is applied per call via [newBuilder],
     * not baked into the shared client.
     */
    private suspend fun probeApiVersion(port: Int, token: ByteArray): DockerResult<DockerVersionInfo> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("http://127.0.0.1:$port/version").get().build()
                val tokenClient = probeClient.newBuilder()
                    .socketFactory(RelayTokenSocketFactory(token))
                    .build()
                tokenClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        DockerResult.Error("GET /version failed", "HTTP ${response.code}")
                    } else {
                        // Bounded: whatever answers on the relay port decides
                        // this body's size, and /version is a small object.
                        DockerApiParsers.parseVersion(
                            response.peekBody(MAX_VERSION_BODY_BYTES).string()
                        )
                            ?.let { DockerResult.Success(it) }
                            ?: DockerResult.Error("GET /version returned an unparsable body")
                    }
                }
            } catch (e: java.io.IOException) {
                DockerResult.TransportUnavailable("GET /version failed", e.message)
            }
        }

    /** Verify the CLI tier with `docker version --format '{{json .}}'`. */
    private suspend fun verifyCliTier(
        host: DockerHost,
        runner: SshExecRunner
    ): DockerResult<DetectedTransport> {
        val transport = CliExecTransport(host, runner)
        return when (val version = transport.engineVersion()) {
            is DockerResult.Success -> {
                Logger.i(TAG, "cli_exec verified (engine ${version.value.version})")
                DockerResult.Success(DetectedTransport(MODE_CLI_EXEC, transport, null))
            }
            is DockerResult.PermissionDenied -> version
            is DockerResult.NotFound -> version
            is DockerResult.TransportUnavailable -> version
            is DockerResult.Error -> version
        }
    }

    /**
     * Probe socket accessibility. Success(true) = readable+writable,
     * Success(false) = missing, PermissionDenied = present but inaccessible
     * to the SSH user (docker-group remediation).
     */
    private suspend fun probeSocketAccess(
        host: DockerHost,
        runner: SshExecRunner
    ): DockerResult<Boolean> {
        return try {
            val sock = SshExecRunner.shQuote(host.socketPath)
            val cmd = "if [ -S $sock ]; then " +
                "if [ -r $sock ] && [ -w $sock ]; then echo ok; else echo denied; fi; " +
                "else echo missing; fi"
            val result = runner.run(cmd)
            when (result.stdout.trim()) {
                "ok" -> DockerResult.Success(true)
                "denied" -> DockerResult.PermissionDenied(
                    DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION,
                    detail = host.socketPath
                )
                "missing" -> DockerResult.Success(false)
                else -> DockerResult.Error("Socket probe returned unexpected output",
                    result.stdout.trim().take(200))
            }
        } catch (e: TransportUnavailableException) {
            Logger.w(TAG, "socket probe: transport unavailable: ${e.message}")
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "socket probe failed: ${e.message}")
            DockerResult.Error("Socket probe failed", e.message)
        }
    }

    /** Persist the winning tier so reconnects skip detection. */
    private suspend fun persist(host: DockerHost, mode: String) {
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

package io.github.tabssh.containers.transport

import android.util.Base64
import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * SSH-exec-backed operations shared by BOTH transports: compose commands
 * (compose has no REST API) and remote file primitives. Owns the
 * `$USER`/`$HOME` expansion cache and the base64 write primitive that keeps
 * arbitrary file content safe from shell quoting and heredoc corruption.
 *
 * Compose is a Docker-API-engine concept, so every compose entry point here is
 * gated on [EngineCapability.COMPOSE_STACKS]: on Incus or LXC/LXD the request
 * is refused before a command is built rather than producing a remote "unknown
 * command". The remote file primitives are engine-independent and ungated.
 */
class RemoteExecOps(
    private val runner: SshExecRunner,
    private val host: ContainerHost,
    private val cliContext: EngineCliContext =
        EngineCliContext(host, EngineSocketResolver(host, runner))
) {

    private companion object {
        private const val TAG = "RemoteExecOps"
        private const val COMPOSE_TIMEOUT_MS = 300_000L
        private const val FILE_TIMEOUT_MS = 60_000L
    }

    private val engine: ContainerEngine = host.engineType()

    private val gate = EngineCapabilityGate(engine)

    // @Volatile: these caches are written from whichever Dispatchers.IO thread
    // first resolves them and read from every later call, which may run on a
    // different thread. Without it a stale null (or a partially published
    // ComposeInvocation) is visible to concurrent callers.
    /** Remote `$USER`/`$HOME` values, fetched once per instance. */
    @Volatile private var remoteUser: String? = null
    @Volatile private var remoteHome: String? = null

    /** Compose invocation, detected once per instance. */
    @Volatile private var composeInvocation: ComposeInvocation? = null

    /** The engine binary to invoke — explicit per-host path or PATH lookup. */
    val dockerBin: String
        get() = host.cliBinary()

    // ── Path expansion ──────────────────────────────────────────────────────

    /**
     * Expand `$USER`/`${USER}`/`$HOME`/`${HOME}` in [path] with the REMOTE
     * shell's values. The values are read from the remote once (plain
     * `"$USER"`/`"$HOME"` expansion in a fixed command) and substituted
     * locally, so the path string itself never reaches a remote shell in an
     * expandable position — later uses single-quote it.
     */
    suspend fun expandRemotePath(path: String): ContainerResult<String> {
        if (!path.contains("\$USER") && !path.contains("\$HOME")) {
            return ContainerResult.Success(path)
        }
        try {
            if (remoteUser == null || remoteHome == null) {
                // ${USER:-$(id -un)} covers shells where USER is unset (dash, containers).
                val result = runner.run(
                    "printf '%s\\n%s\\n' \"\${USER:-\$(id -un)}\" \"\$HOME\"",
                    FILE_TIMEOUT_MS
                )
                if (!result.isSuccess) {
                    return DockerCliParsers.classifyFailure(
                        "Failed to resolve remote \$USER/\$HOME",
                        result.stderr,
                        result.stdout,
                        engine
                    )
                }
                val lines = result.stdout.trim().lines()
                remoteUser = lines.getOrElse(0) { "" }.trim()
                remoteHome = lines.getOrElse(1) { "" }.trim()
                Logger.d(TAG, "expandRemotePath: cached remote USER/HOME")
            }
            val expanded = path
                .replace("\${USER}", remoteUser.orEmpty())
                .replace("\$USER", remoteUser.orEmpty())
                .replace("\${HOME}", remoteHome.orEmpty())
                .replace("\$HOME", remoteHome.orEmpty())
            return ContainerResult.Success(expanded)
        } catch (e: TransportUnavailableException) {
            return ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ContainerResult.Error("Failed to expand remote path", e.message)
        }
    }

    // ── Remote files ────────────────────────────────────────────────────────

    /**
     * Read a remote file. Content travels base64-encoded over the exec
     * channel so line endings and binary bytes survive untouched.
     */
    suspend fun readRemoteFile(path: String): ContainerResult<String> = withExpanded(path) { real ->
        val result = runner.run("base64 ${SshExecRunner.shQuote(real)}", FILE_TIMEOUT_MS)
        if (!result.isSuccess) {
            return@withExpanded DockerCliParsers.classifyFailure(
                "Failed to read remote file",
                result.stderr,
                result.stdout,
                engine
            )
        }
        val decoded = try {
            // DEFAULT tolerates the line wrapping remote base64 emits.
            Base64.decode(result.stdout, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return@withExpanded ContainerResult.Error("Remote file transfer corrupted", e.message)
        }
        ContainerResult.Success(String(decoded, Charsets.UTF_8))
    }

    /**
     * Write [content] to a remote file. The parent directory is created with
     * `mkdir -p` first (spec requirement); the payload is base64 in a
     * single-quoted argument — base64 text contains no quote characters, so
     * no shell state can corrupt it and no heredoc is needed.
     */
    suspend fun writeRemoteFile(path: String, content: String): ContainerResult<Unit> =
        withExpanded(path) { real ->
            val parent = real.substringBeforeLast('/', "")
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val mkdir = if (parent.isNotEmpty()) "mkdir -p ${SshExecRunner.shQuote(parent)} && " else ""
            val cmd = mkdir +
                "printf '%s' ${SshExecRunner.shQuote(encoded)} | base64 -d > ${SshExecRunner.shQuote(real)}"
            val result = runner.run(cmd, FILE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "Failed to write remote file",
                    result.stderr,
                    result.stdout,
                    engine
                )
            }
            ContainerResult.Success(Unit)
        }

    /**
     * List a remote directory. `ls -1Ap` prints one entry per line with a
     * trailing `/` on directories — parsed into [RemoteDirEntry] rows.
     */
    suspend fun listRemoteDir(path: String): ContainerResult<List<RemoteDirEntry>> =
        withExpanded(path) { real ->
            val result = runner.run("ls -1Ap ${SshExecRunner.shQuote(real)}", FILE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "Failed to list remote directory",
                    result.stderr,
                    result.stdout,
                    engine
                )
            }
            val entries = result.stdout.lines()
                .filter { it.isNotBlank() }
                .map { line ->
                    RemoteDirEntry(
                        name = line.removeSuffix("/"),
                        isDirectory = line.endsWith("/")
                    )
                }
            ContainerResult.Success(entries)
        }

    // ── Compose ─────────────────────────────────────────────────────────────

    /**
     * Detect the compose invocation. A non-auto ContainerHost.composeInvocation
     * pin short-circuits; otherwise probe the plugin then the standalone
     * binary. Cached per instance.
     */
    suspend fun detectComposeInvocation(): ContainerResult<ComposeInvocation> {
        gate.reject(EngineCapability.COMPOSE_STACKS)?.let { return it }
        composeInvocation?.let { return ContainerResult.Success(it) }
        when (host.composeInvocation) {
            "plugin" -> return ContainerResult.Success(ComposeInvocation.PLUGIN.also { composeInvocation = it })
            "standalone" -> return ContainerResult.Success(ComposeInvocation.STANDALONE.also { composeInvocation = it })
        }
        return try {
            val env = cliContext.envPrefix()
            val plugin = runner.run("$env$dockerBin compose version 2>/dev/null", FILE_TIMEOUT_MS)
            if (plugin.isSuccess) {
                composeInvocation = ComposeInvocation.PLUGIN
                return ContainerResult.Success(ComposeInvocation.PLUGIN)
            }
            val standalone = runner.run("${env}docker-compose version 2>/dev/null", FILE_TIMEOUT_MS)
            if (standalone.isSuccess) {
                composeInvocation = ComposeInvocation.STANDALONE
                return ContainerResult.Success(ComposeInvocation.STANDALONE)
            }
            ContainerResult.TransportUnavailable(ContainerTransportMessages.composeMissing(engine))
        } catch (e: TransportUnavailableException) {
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ContainerResult.Error("Compose detection failed", e.message)
        }
    }

    suspend fun composeUp(stackDir: String): ContainerResult<String> =
        composeCommand(stackDir, "up -d")

    suspend fun composeDown(stackDir: String): ContainerResult<String> =
        composeCommand(stackDir, "down")

    suspend fun composePull(stackDir: String): ContainerResult<String> =
        composeCommand(stackDir, "pull")

    suspend fun composeRestart(stackDir: String): ContainerResult<String> =
        composeCommand(stackDir, "restart")

    suspend fun composePs(stackDir: String): ContainerResult<String> =
        composeCommand(stackDir, "ps --format json")

    /**
     * Follow `compose logs` for a Room-tracked stack directory, optionally
     * scoped to one [service]. Mirrors [CliExecTransport.streamLogs]'s
     * merged-stream convention (`2>&1`).
     */
    fun composeLogs(stackDir: String, service: String? = null, tail: Int = 200): Flow<String> = flow {
        val invocation = resolveInvocationOrNull() ?: return@flow
        val prefix = invocationPrefix(invocation)
        val expanded = when (val exp = expandRemotePath(stackDir)) {
            is ContainerResult.Success -> exp.value
            else -> stackDir
        }
        val svc = service?.let { " ${SshExecRunner.shQuote(it)}" }.orEmpty()
        val cmd = "cd ${SshExecRunner.shQuote(expanded)} && " +
            "$prefix logs --tail $tail --follow$svc 2>&1"
        emitAll(runner.stream(cmd))
    }

    // ── Compose (discovered / untracked projects) ─────────────────────────────

    /** `docker compose ls --all --format json` — every project on the host. */
    suspend fun composeLs(): ContainerResult<List<ComposeLsEntry>> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is ContainerResult.Success -> detected.value
            is ContainerResult.PermissionDenied -> return detected
            is ContainerResult.NotFound -> return detected
            is ContainerResult.EngineNotInstalled -> return detected
            is ContainerResult.TransportUnavailable -> return detected
            is ContainerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return try {
            val result = runner.run("$prefix ls --all --format json", COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return DockerCliParsers.classifyFailure(
                    "compose ls failed", result.stderr, result.stdout, engine
                )
            }
            ContainerResult.Success(DockerCliParsers.parseComposeLs(result.stdout))
        } catch (e: TransportUnavailableException) {
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ContainerResult.Error("compose ls failed", e.message)
        }
    }

    suspend fun composeUpByProject(name: String, configFile: String): ContainerResult<String> =
        composeCommandByProject(name, configFile, "up -d")

    suspend fun composeDownByProject(name: String, configFile: String): ContainerResult<String> =
        composeCommandByProject(name, configFile, "down")

    suspend fun composePullByProject(name: String, configFile: String): ContainerResult<String> =
        composeCommandByProject(name, configFile, "pull")

    suspend fun composeRestartByProject(name: String, configFile: String): ContainerResult<String> =
        composeCommandByProject(name, configFile, "restart")

    suspend fun composePsByProject(name: String, configFile: String): ContainerResult<String> =
        composeCommandByProject(name, configFile, "ps --format json")

    /**
     * Follow `compose logs` for an untracked project, addressed by its
     * `-f <configfile> -p <name>` pair rather than a `cd` into a stack
     * directory — the discovery-only path a Room row was never created for.
     */
    fun composeLogsByProject(
        name: String,
        configFile: String,
        service: String? = null,
        tail: Int = 200
    ): Flow<String> = flow {
        val invocation = resolveInvocationOrNull() ?: return@flow
        val prefix = invocationPrefix(invocation)
        val expanded = when (val exp = expandRemotePath(configFile)) {
            is ContainerResult.Success -> exp.value
            else -> configFile
        }
        val svc = service?.let { " ${SshExecRunner.shQuote(it)}" }.orEmpty()
        val cmd = "$prefix -f ${SshExecRunner.shQuote(expanded)} -p ${SshExecRunner.shQuote(name)} " +
            "logs --tail $tail --follow$svc 2>&1"
        emitAll(runner.stream(cmd))
    }

    /** Run one compose subcommand inside the (expanded) stack directory. */
    private suspend fun composeCommand(stackDir: String, args: String): ContainerResult<String> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is ContainerResult.Success -> detected.value
            is ContainerResult.PermissionDenied -> return detected
            is ContainerResult.NotFound -> return detected
            is ContainerResult.EngineNotInstalled -> return detected
            is ContainerResult.TransportUnavailable -> return detected
            is ContainerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return withExpanded(stackDir) { real ->
            val cmd = "cd ${SshExecRunner.shQuote(real)} && $prefix $args"
            val result = runner.run(cmd, COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "compose $args failed",
                    result.stderr,
                    result.stdout,
                    engine
                )
            }
            // Compose writes human progress to stderr even on success.
            ContainerResult.Success(result.stdout.ifEmpty { result.stderr })
        }
    }

    /**
     * Run one compose subcommand against an untracked project via
     * `-f <configfile> -p <name>` instead of `cd`-ing into a stack directory.
     */
    private suspend fun composeCommandByProject(
        name: String,
        configFile: String,
        args: String
    ): ContainerResult<String> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is ContainerResult.Success -> detected.value
            is ContainerResult.PermissionDenied -> return detected
            is ContainerResult.NotFound -> return detected
            is ContainerResult.EngineNotInstalled -> return detected
            is ContainerResult.TransportUnavailable -> return detected
            is ContainerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return withExpanded(configFile) { real ->
            val cmd = "$prefix -f ${SshExecRunner.shQuote(real)} -p ${SshExecRunner.shQuote(name)} $args"
            val result = runner.run(cmd, COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "compose $args failed",
                    result.stderr,
                    result.stdout,
                    engine
                )
            }
            ContainerResult.Success(result.stdout.ifEmpty { result.stderr })
        }
    }

    /**
     * The compose command prefix for [invocation], carrying the engine's
     * endpoint environment so the plugin and the standalone binary both talk
     * to the same daemon the rest of the transport resolved.
     */
    private suspend fun invocationPrefix(invocation: ComposeInvocation): String {
        val env = cliContext.envPrefix()
        return when (invocation) {
            ComposeInvocation.PLUGIN -> "$env$dockerBin compose"
            ComposeInvocation.STANDALONE -> "${env}docker-compose"
        }
    }

    /**
     * [detectComposeInvocation] for the Flow builders. A capability refusal is
     * thrown into the stream so the collector sees the typed failure; any other
     * failure yields null and the caller completes the flow empty, as before.
     */
    private suspend fun resolveInvocationOrNull(): ComposeInvocation? {
        gate.require(EngineCapability.COMPOSE_STACKS)
        return (detectComposeInvocation() as? ContainerResult.Success)?.value
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Expand [path], then run [block] with the concrete remote path. */
    private suspend fun <T> withExpanded(
        path: String,
        block: suspend (String) -> ContainerResult<T>
    ): ContainerResult<T> {
        val expanded = when (val exp = expandRemotePath(path)) {
            is ContainerResult.Success -> exp.value
            is ContainerResult.PermissionDenied -> return exp
            is ContainerResult.NotFound -> return exp
            is ContainerResult.EngineNotInstalled -> return exp
            is ContainerResult.TransportUnavailable -> return exp
            is ContainerResult.Error -> return exp
        }
        return try {
            block(expanded)
        } catch (e: TransportUnavailableException) {
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ContainerResult.Error("Remote operation failed", e.message)
        }
    }
}

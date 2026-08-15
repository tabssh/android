package io.github.tabssh.docker.transport

import android.util.Base64
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * SSH-exec-backed operations shared by BOTH transports: compose commands
 * (compose has no REST API) and remote file primitives. Owns the
 * `$USER`/`$HOME` expansion cache and the base64 write primitive that keeps
 * arbitrary file content safe from shell quoting and heredoc corruption.
 */
class RemoteExecOps(
    private val runner: SshExecRunner,
    private val host: DockerHost
) {

    private companion object {
        private const val TAG = "RemoteExecOps"
        private const val COMPOSE_TIMEOUT_MS = 300_000L
        private const val FILE_TIMEOUT_MS = 60_000L
    }

    // @Volatile: these caches are written from whichever Dispatchers.IO thread
    // first resolves them and read from every later call, which may run on a
    // different thread. Without it a stale null (or a partially published
    // ComposeInvocation) is visible to concurrent callers.
    /** Remote `$USER`/`$HOME` values, fetched once per instance. */
    @Volatile private var remoteUser: String? = null
    @Volatile private var remoteHome: String? = null

    /** Compose invocation, detected once per instance. */
    @Volatile private var composeInvocation: ComposeInvocation? = null

    /** The docker binary to invoke — explicit per-host path or PATH lookup. */
    val dockerBin: String
        get() = host.dockerCliPath?.takeIf { it.isNotBlank() } ?: "docker"

    // ── Path expansion ──────────────────────────────────────────────────────

    /**
     * Expand `$USER`/`${USER}`/`$HOME`/`${HOME}` in [path] with the REMOTE
     * shell's values. The values are read from the remote once (plain
     * `"$USER"`/`"$HOME"` expansion in a fixed command) and substituted
     * locally, so the path string itself never reaches a remote shell in an
     * expandable position — later uses single-quote it.
     */
    suspend fun expandRemotePath(path: String): DockerResult<String> {
        if (!path.contains("\$USER") && !path.contains("\$HOME")) {
            return DockerResult.Success(path)
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
                        result.stdout
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
            return DockerResult.Success(expanded)
        } catch (e: TransportUnavailableException) {
            return DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return DockerResult.Error("Failed to expand remote path", e.message)
        }
    }

    // ── Remote files ────────────────────────────────────────────────────────

    /**
     * Read a remote file. Content travels base64-encoded over the exec
     * channel so line endings and binary bytes survive untouched.
     */
    suspend fun readRemoteFile(path: String): DockerResult<String> = withExpanded(path) { real ->
        val result = runner.run("base64 ${SshExecRunner.shQuote(real)}", FILE_TIMEOUT_MS)
        if (!result.isSuccess) {
            return@withExpanded DockerCliParsers.classifyFailure(
                "Failed to read remote file",
                result.stderr,
                result.stdout
            )
        }
        val decoded = try {
            // DEFAULT tolerates the line wrapping remote base64 emits.
            Base64.decode(result.stdout, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return@withExpanded DockerResult.Error("Remote file transfer corrupted", e.message)
        }
        DockerResult.Success(String(decoded, Charsets.UTF_8))
    }

    /**
     * Write [content] to a remote file. The parent directory is created with
     * `mkdir -p` first (spec requirement); the payload is base64 in a
     * single-quoted argument — base64 text contains no quote characters, so
     * no shell state can corrupt it and no heredoc is needed.
     */
    suspend fun writeRemoteFile(path: String, content: String): DockerResult<Unit> =
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
                    result.stdout
                )
            }
            DockerResult.Success(Unit)
        }

    /**
     * List a remote directory. `ls -1Ap` prints one entry per line with a
     * trailing `/` on directories — parsed into [RemoteDirEntry] rows.
     */
    suspend fun listRemoteDir(path: String): DockerResult<List<RemoteDirEntry>> =
        withExpanded(path) { real ->
            val result = runner.run("ls -1Ap ${SshExecRunner.shQuote(real)}", FILE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "Failed to list remote directory",
                    result.stderr,
                    result.stdout
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
            DockerResult.Success(entries)
        }

    // ── Compose ─────────────────────────────────────────────────────────────

    /**
     * Detect the compose invocation. A non-auto DockerHost.composeInvocation
     * pin short-circuits; otherwise probe the plugin then the standalone
     * binary. Cached per instance.
     */
    suspend fun detectComposeInvocation(): DockerResult<ComposeInvocation> {
        composeInvocation?.let { return DockerResult.Success(it) }
        when (host.composeInvocation) {
            "plugin" -> return DockerResult.Success(ComposeInvocation.PLUGIN.also { composeInvocation = it })
            "standalone" -> return DockerResult.Success(ComposeInvocation.STANDALONE.also { composeInvocation = it })
        }
        return try {
            val plugin = runner.run("$dockerBin compose version 2>/dev/null", FILE_TIMEOUT_MS)
            if (plugin.isSuccess) {
                composeInvocation = ComposeInvocation.PLUGIN
                return DockerResult.Success(ComposeInvocation.PLUGIN)
            }
            val standalone = runner.run("docker-compose version 2>/dev/null", FILE_TIMEOUT_MS)
            if (standalone.isSuccess) {
                composeInvocation = ComposeInvocation.STANDALONE
                return DockerResult.Success(ComposeInvocation.STANDALONE)
            }
            DockerResult.TransportUnavailable(DockerTransportMessages.COMPOSE_MISSING)
        } catch (e: TransportUnavailableException) {
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error("Compose detection failed", e.message)
        }
    }

    suspend fun composeUp(stackDir: String): DockerResult<String> =
        composeCommand(stackDir, "up -d")

    suspend fun composeDown(stackDir: String): DockerResult<String> =
        composeCommand(stackDir, "down")

    suspend fun composePull(stackDir: String): DockerResult<String> =
        composeCommand(stackDir, "pull")

    suspend fun composeRestart(stackDir: String): DockerResult<String> =
        composeCommand(stackDir, "restart")

    suspend fun composePs(stackDir: String): DockerResult<String> =
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
            is DockerResult.Success -> exp.value
            else -> stackDir
        }
        val svc = service?.let { " ${SshExecRunner.shQuote(it)}" }.orEmpty()
        val cmd = "cd ${SshExecRunner.shQuote(expanded)} && " +
            "$prefix logs --tail $tail --follow$svc 2>&1"
        emitAll(runner.stream(cmd))
    }

    // ── Compose (discovered / untracked projects) ─────────────────────────────

    /** `docker compose ls --all --format json` — every project on the host. */
    suspend fun composeLs(): DockerResult<List<ComposeLsEntry>> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is DockerResult.Success -> detected.value
            is DockerResult.PermissionDenied -> return detected
            is DockerResult.NotFound -> return detected
            is DockerResult.TransportUnavailable -> return detected
            is DockerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return try {
            val result = runner.run("$prefix ls --all --format json", COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return DockerCliParsers.classifyFailure(
                    "compose ls failed", result.stderr, result.stdout
                )
            }
            DockerResult.Success(DockerCliParsers.parseComposeLs(result.stdout))
        } catch (e: TransportUnavailableException) {
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error("compose ls failed", e.message)
        }
    }

    suspend fun composeUpByProject(name: String, configFile: String): DockerResult<String> =
        composeCommandByProject(name, configFile, "up -d")

    suspend fun composeDownByProject(name: String, configFile: String): DockerResult<String> =
        composeCommandByProject(name, configFile, "down")

    suspend fun composePullByProject(name: String, configFile: String): DockerResult<String> =
        composeCommandByProject(name, configFile, "pull")

    suspend fun composeRestartByProject(name: String, configFile: String): DockerResult<String> =
        composeCommandByProject(name, configFile, "restart")

    suspend fun composePsByProject(name: String, configFile: String): DockerResult<String> =
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
            is DockerResult.Success -> exp.value
            else -> configFile
        }
        val svc = service?.let { " ${SshExecRunner.shQuote(it)}" }.orEmpty()
        val cmd = "$prefix -f ${SshExecRunner.shQuote(expanded)} -p ${SshExecRunner.shQuote(name)} " +
            "logs --tail $tail --follow$svc 2>&1"
        emitAll(runner.stream(cmd))
    }

    /** Run one compose subcommand inside the (expanded) stack directory. */
    private suspend fun composeCommand(stackDir: String, args: String): DockerResult<String> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is DockerResult.Success -> detected.value
            is DockerResult.PermissionDenied -> return detected
            is DockerResult.NotFound -> return detected
            is DockerResult.TransportUnavailable -> return detected
            is DockerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return withExpanded(stackDir) { real ->
            val cmd = "cd ${SshExecRunner.shQuote(real)} && $prefix $args"
            val result = runner.run(cmd, COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "compose $args failed",
                    result.stderr,
                    result.stdout
                )
            }
            // Compose writes human progress to stderr even on success.
            DockerResult.Success(result.stdout.ifEmpty { result.stderr })
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
    ): DockerResult<String> {
        val invocation = when (val detected = detectComposeInvocation()) {
            is DockerResult.Success -> detected.value
            is DockerResult.PermissionDenied -> return detected
            is DockerResult.NotFound -> return detected
            is DockerResult.TransportUnavailable -> return detected
            is DockerResult.Error -> return detected
        }
        val prefix = invocationPrefix(invocation)
        return withExpanded(configFile) { real ->
            val cmd = "$prefix -f ${SshExecRunner.shQuote(real)} -p ${SshExecRunner.shQuote(name)} $args"
            val result = runner.run(cmd, COMPOSE_TIMEOUT_MS)
            if (!result.isSuccess) {
                return@withExpanded DockerCliParsers.classifyFailure(
                    "compose $args failed",
                    result.stderr,
                    result.stdout
                )
            }
            DockerResult.Success(result.stdout.ifEmpty { result.stderr })
        }
    }

    /** The `docker compose` / `docker-compose` command prefix for [invocation]. */
    private fun invocationPrefix(invocation: ComposeInvocation): String = when (invocation) {
        ComposeInvocation.PLUGIN -> "$dockerBin compose"
        ComposeInvocation.STANDALONE -> "docker-compose"
    }

    /** [detectComposeInvocation], swallowing failures for Flow builders (no suspend catch there). */
    private suspend fun resolveInvocationOrNull(): ComposeInvocation? =
        (detectComposeInvocation() as? DockerResult.Success)?.value

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Expand [path], then run [block] with the concrete remote path. */
    private suspend fun <T> withExpanded(
        path: String,
        block: suspend (String) -> DockerResult<T>
    ): DockerResult<T> {
        val expanded = when (val exp = expandRemotePath(path)) {
            is DockerResult.Success -> exp.value
            is DockerResult.PermissionDenied -> return exp
            is DockerResult.NotFound -> return exp
            is DockerResult.TransportUnavailable -> return exp
            is DockerResult.Error -> return exp
        }
        return try {
            block(expanded)
        } catch (e: TransportUnavailableException) {
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error("Remote operation failed", e.message)
        }
    }
}

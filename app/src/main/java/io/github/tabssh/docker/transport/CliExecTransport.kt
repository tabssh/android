package io.github.tabssh.docker.transport

import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Fallback transport: every operation runs `docker … --format '{{json .}}'`
 * over SSH exec (modeled on LibvirtApiClient.runCommand via [SshExecRunner]).
 *
 * Degradations vs [EngineApiTransport] (documented per PLAN.AI.md step 13):
 *  - [streamStats] polls `docker stats --no-stream` every ~2 s instead of a
 *    push stream; the loop is cancellable via normal Flow cancellation.
 *  - [pullImage] emits raw `docker pull` output lines as status-only events
 *    (the CLI has no NDJSON progress).
 */
class CliExecTransport(
    private val host: DockerHost,
    private val runner: SshExecRunner
) : DockerTransport {

    private companion object {
        private const val EXEC_TIMEOUT_MS = 60_000L
        private const val ACTION_TIMEOUT_MS = 120_000L
        private const val STATS_POLL_INTERVAL_MS = 2_000L
    }

    private val remoteOps = RemoteExecOps(runner, host)

    /** The docker binary — explicit per-host path or PATH lookup. */
    private val docker: String
        get() = host.dockerCliPath?.takeIf { it.isNotBlank() } ?: "docker"

    private fun q(value: String): String = SshExecRunner.shQuote(value)

    /**
     * Run one docker CLI command; non-zero exit is classified into the
     * matching DockerResult failure, success maps stdout with [transform].
     */
    private suspend fun <T> cli(
        context: String,
        args: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
        transform: (String) -> T
    ): DockerResult<T> {
        return try {
            val result = runner.run("$docker $args", timeoutMs)
            if (!result.isSuccess) {
                DockerCliParsers.classifyFailure(context, result.stderr, result.stdout)
            } else {
                DockerResult.Success(transform(result.stdout))
            }
        } catch (e: TransportUnavailableException) {
            Logger.w("CliExecTransport", "$context: transport unavailable: ${e.message}")
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("CliExecTransport", "$context: ${e.message}")
            DockerResult.Error(context, e.message)
        }
    }

    // ── Containers ───────────────────────────────────────────────────────────

    override suspend fun listContainers(all: Boolean): DockerResult<List<DockerContainerSummary>> =
        cli("Failed to list containers", "ps${if (all) " -a" else ""} --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseContainerLine)
        }

    override suspend fun inspectContainer(id: String): DockerResult<String> =
        cli("Failed to inspect container", "inspect ${q(id)}") { it.trim() }

    override suspend fun containerAction(id: String, action: ContainerAction): DockerResult<Unit> =
        cli("Failed to ${action.verb} container", "${action.verb} ${q(id)}", ACTION_TIMEOUT_MS) { }

    override suspend fun renameContainer(id: String, newName: String): DockerResult<Unit> =
        cli("Failed to rename container", "rename ${q(id)} ${q(newName)}") { }

    override suspend fun removeContainer(id: String, force: Boolean): DockerResult<Unit> =
        cli("Failed to remove container", "rm${if (force) " -f" else ""} ${q(id)}", ACTION_TIMEOUT_MS) { }

    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): DockerResult<Unit> =
        // The CLI tier consumes the argv half of the recreate plan — the
        // "run"…"-d"…"--name" tokens already carry the container name.
        cli("Failed to run container $name", runArgv.joinToString(" ") { q(it) }, ACTION_TIMEOUT_MS) { }

    override fun streamLogs(id: String, tail: Int?): Flow<String> {
        val tailArg = tail?.let { " --tail $it" } ?: ""
        // 2>&1 folds stderr log lines into the same stream, matching the API
        // tier's multiplexed view.
        return runner.stream("$docker logs --follow$tailArg ${q(id)} 2>&1")
    }

    override fun streamStats(id: String): Flow<DockerContainerStats> = flow {
        // Polling loop — cancellable at the delay; documented degradation of
        // the CLI tier vs the API's push stream.
        while (true) {
            val result = runner.run(
                "$docker stats --no-stream --format '{{json .}}' ${q(id)}",
                EXEC_TIMEOUT_MS
            )
            if (result.isSuccess) {
                val line = result.stdout.lineSequence().firstOrNull { it.trim().startsWith("{") }
                if (line != null) {
                    try {
                        emit(DockerCliParsers.parseStatsLine(JSONObject(line.trim())))
                    } catch (_: Exception) {
                        // Skip malformed samples; next poll retries.
                    }
                }
            }
            delay(STATS_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ── Images ───────────────────────────────────────────────────────────────

    override suspend fun listImages(): DockerResult<List<DockerImageSummary>> =
        cli("Failed to list images", "images --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseImageLine)
        }

    override suspend fun inspectImage(ref: String): DockerResult<String> =
        cli("Failed to inspect image", "image inspect ${q(ref)}") { it.trim() }

    override fun pullImage(ref: String): Flow<PullProgressEvent> =
        // CLI pull has no NDJSON progress; each output line becomes a
        // status-only event.
        runner.stream("$docker pull ${q(ref)} 2>&1")
            .map { line -> PullProgressEvent(status = line) }

    override suspend fun removeImage(ref: String, force: Boolean): DockerResult<Unit> =
        cli("Failed to remove image", "rmi${if (force) " -f" else ""} ${q(ref)}", ACTION_TIMEOUT_MS) { }

    override suspend fun pruneImages(): DockerResult<Unit> =
        cli("Failed to prune images", "image prune -f", ACTION_TIMEOUT_MS) { }

    // ── Volumes ──────────────────────────────────────────────────────────────

    override suspend fun listVolumes(): DockerResult<List<DockerVolumeSummary>> =
        cli("Failed to list volumes", "volume ls --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseVolumeLine)
        }

    override suspend fun inspectVolume(name: String): DockerResult<String> =
        cli("Failed to inspect volume", "volume inspect ${q(name)}") { it.trim() }

    override suspend fun createVolume(name: String, driver: String?): DockerResult<Unit> {
        val driverArg = driver?.takeIf { it.isNotBlank() }?.let { " -d ${q(it)}" } ?: ""
        return cli("Failed to create volume", "volume create$driverArg ${q(name)}") { }
    }

    override suspend fun removeVolume(name: String, force: Boolean): DockerResult<Unit> =
        cli("Failed to remove volume", "volume rm${if (force) " -f" else ""} ${q(name)}") { }

    override suspend fun pruneVolumes(): DockerResult<Unit> =
        cli("Failed to prune volumes", "volume prune -f", ACTION_TIMEOUT_MS) { }

    // ── Networks ─────────────────────────────────────────────────────────────

    override suspend fun listNetworks(): DockerResult<List<DockerNetworkSummary>> =
        cli("Failed to list networks", "network ls --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseNetworkLine)
        }

    override suspend fun inspectNetwork(id: String): DockerResult<String> =
        cli("Failed to inspect network", "network inspect ${q(id)}") { it.trim() }

    override suspend fun createNetwork(name: String, driver: String?): DockerResult<Unit> {
        val driverArg = driver?.takeIf { it.isNotBlank() }?.let { " -d ${q(it)}" } ?: ""
        return cli("Failed to create network", "network create$driverArg ${q(name)}") { }
    }

    override suspend fun removeNetwork(id: String): DockerResult<Unit> =
        cli("Failed to remove network", "network rm ${q(id)}") { }

    override suspend fun pruneNetworks(): DockerResult<Unit> =
        cli("Failed to prune networks", "network prune -f", ACTION_TIMEOUT_MS) { }

    // ── Engine ───────────────────────────────────────────────────────────────

    override suspend fun engineInfo(): DockerResult<DockerEngineInfo> =
        cli("Failed to read engine info", "info --format '{{json .}}'") { out ->
            DockerCliParsers.parseCliInfo(out)
        }.flatMapNotNull("Engine info was unparsable")

    override suspend fun engineVersion(): DockerResult<DockerVersionInfo> =
        cli("Failed to read engine version", "version --format '{{json .}}'") { out ->
            DockerCliParsers.parseCliVersion(out)
        }.flatMapNotNull("Engine version was unparsable")

    override suspend fun diskUsage(): DockerResult<DockerDiskUsage> =
        cli("Failed to read disk usage", "system df --format '{{json .}}'") { out ->
            DockerDiskUsage(DockerCliParsers.parseNdjson(out, DockerCliParsers::parseSystemDfLine))
        }

    // ── Compose + remote files (shared RemoteExecOps) ────────────────────────

    override suspend fun composeUp(stackDir: String): DockerResult<String> =
        remoteOps.composeUp(stackDir)

    override suspend fun composeDown(stackDir: String): DockerResult<String> =
        remoteOps.composeDown(stackDir)

    override suspend fun composePull(stackDir: String): DockerResult<String> =
        remoteOps.composePull(stackDir)

    override suspend fun composeRestart(stackDir: String): DockerResult<String> =
        remoteOps.composeRestart(stackDir)

    override suspend fun composePs(stackDir: String): DockerResult<String> =
        remoteOps.composePs(stackDir)

    override fun composeLogs(stackDir: String, service: String?, tail: Int): Flow<String> =
        remoteOps.composeLogs(stackDir, service, tail)

    override suspend fun composeLs(): DockerResult<List<ComposeLsEntry>> =
        remoteOps.composeLs()

    override suspend fun composeUpByProject(name: String, configFile: String): DockerResult<String> =
        remoteOps.composeUpByProject(name, configFile)

    override suspend fun composeDownByProject(name: String, configFile: String): DockerResult<String> =
        remoteOps.composeDownByProject(name, configFile)

    override suspend fun composePullByProject(name: String, configFile: String): DockerResult<String> =
        remoteOps.composePullByProject(name, configFile)

    override suspend fun composeRestartByProject(name: String, configFile: String): DockerResult<String> =
        remoteOps.composeRestartByProject(name, configFile)

    override suspend fun composePsByProject(name: String, configFile: String): DockerResult<String> =
        remoteOps.composePsByProject(name, configFile)

    override fun composeLogsByProject(
        name: String,
        configFile: String,
        service: String?,
        tail: Int
    ): Flow<String> = remoteOps.composeLogsByProject(name, configFile, service, tail)

    override suspend fun detectComposeInvocation(): DockerResult<ComposeInvocation> =
        remoteOps.detectComposeInvocation()

    override suspend fun readRemoteFile(path: String): DockerResult<String> =
        remoteOps.readRemoteFile(path)

    override suspend fun writeRemoteFile(path: String, content: String): DockerResult<Unit> =
        remoteOps.writeRemoteFile(path, content)

    override suspend fun listRemoteDir(path: String): DockerResult<List<RemoteDirEntry>> =
        remoteOps.listRemoteDir(path)

    override suspend fun expandRemotePath(path: String): DockerResult<String> =
        remoteOps.expandRemotePath(path)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override suspend fun close() {
        // Exec channels are per-call; nothing persistent to release.
    }
}

/** Turn a Success(null) parse outcome into a typed Error result. */
private fun <T : Any> DockerResult<T?>.flatMapNotNull(message: String): DockerResult<T> =
    when (this) {
        is DockerResult.Success -> value?.let { DockerResult.Success(it) }
            ?: DockerResult.Error(message)
        is DockerResult.PermissionDenied -> this
        is DockerResult.NotFound -> this
        is DockerResult.TransportUnavailable -> this
        is DockerResult.Error -> this
    }

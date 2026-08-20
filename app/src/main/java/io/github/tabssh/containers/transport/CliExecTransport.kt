package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Fallback transport: every operation runs the engine's own CLI with
 * `--format '{{json .}}'` over SSH exec (modeled on
 * LibvirtApiClient.runCommand via [SshExecRunner]).
 *
 * The binary comes from the host ([ContainerHost.cliBinary]) and the endpoint
 * from [EngineCliContext], so the same class drives every engine; only the
 * output parsers are Docker-specific today.
 *
 * Degradations vs [EngineApiTransport]:
 *  - [streamStats] polls `stats --no-stream` every ~2 s instead of a push
 *    stream; the loop is cancellable via normal Flow cancellation.
 *  - [pullImage] emits raw `pull` output lines as status-only events (the CLI
 *    has no NDJSON progress).
 */
class CliExecTransport(
    private val host: ContainerHost,
    private val runner: SshExecRunner,
    resolver: EngineSocketResolver = EngineSocketResolver(host, runner)
) : ContainerTransport {

    private companion object {
        private const val TAG = "CliExecTransport"
        private const val EXEC_TIMEOUT_MS = 60_000L
        private const val ACTION_TIMEOUT_MS = 120_000L
        private const val STATS_POLL_INTERVAL_MS = 2_000L
    }

    private val engine: ContainerEngine = host.engineType()

    private val gate = EngineCapabilityGate(engine)

    private val cliContext = EngineCliContext(host, resolver)

    private val remoteOps = RemoteExecOps(runner, host, cliContext)

    /** The engine binary — explicit per-host path or PATH lookup. */
    private val docker: String
        get() = host.cliBinary()

    private fun q(value: String): String = SshExecRunner.shQuote(value)

    /**
     * Run one engine CLI command; non-zero exit is classified into the
     * matching ContainerResult failure, success maps stdout with [transform].
     */
    private suspend fun <T> cli(
        context: String,
        args: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
        transform: (String) -> T
    ): ContainerResult<T> {
        // Never log `args`/stdout/stderr verbatim — a container name, env var
        // or --format expression can carry user-entered or daemon-echoed
        // secrets. Only the context label, exit code and timing are safe.
        val startedAt = System.currentTimeMillis()
        return try {
            val result = runner.run("${cliContext.envPrefix()}$docker $args", timeoutMs)
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (!result.isSuccess) {
                Logger.w(TAG, "$context: cli failed exit=${result.exitStatus} elapsedMs=$elapsedMs")
                DockerCliParsers.classifyFailure(context, result.stderr, result.stdout, engine)
            } else {
                Logger.d(TAG, "$context: cli ok elapsedMs=$elapsedMs")
                ContainerResult.Success(transform(result.stdout))
            }
        } catch (e: TransportUnavailableException) {
            Logger.w(TAG, "$context: transport unavailable: ${e.message}")
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "$context: ${e.message}")
            ContainerResult.Error(context, e.message)
        }
    }

    // ── Containers ───────────────────────────────────────────────────────────

    override suspend fun listContainers(all: Boolean): ContainerResult<List<ContainerSummary>> =
        cli("Failed to list containers", "ps${if (all) " -a" else ""} --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseContainerLine)
        }

    override suspend fun inspectContainer(id: String): ContainerResult<String> =
        cli("Failed to inspect container", "inspect ${q(id)}") { it.trim() }

    override suspend fun containerAction(id: String, action: ContainerAction): ContainerResult<Unit> =
        cli("Failed to ${action.verb} container", "${action.verb} ${q(id)}", ACTION_TIMEOUT_MS) { }

    override suspend fun renameContainer(id: String, newName: String): ContainerResult<Unit> =
        cli("Failed to rename container", "rename ${q(id)} ${q(newName)}") { }

    override suspend fun removeContainer(id: String, force: Boolean): ContainerResult<Unit> =
        cli("Failed to remove container", "rm${if (force) " -f" else ""} ${q(id)}", ACTION_TIMEOUT_MS) { }

    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): ContainerResult<Unit> =
        // The CLI tier consumes the argv half of the recreate plan — the
        // "run"…"-d"…"--name" tokens already carry the container name.
        cli("Failed to run container $name", runArgv.joinToString(" ") { q(it) }, ACTION_TIMEOUT_MS) { }

    override fun streamLogs(id: String, tail: Int?): Flow<String> {
        val tailArg = tail?.let { " --tail $it" } ?: ""
        // 2>&1 folds stderr log lines into the same stream, matching the API
        // tier's multiplexed view.
        return flow {
            emitAll(runner.stream("${cliContext.envPrefix()}$docker logs --follow$tailArg ${q(id)} 2>&1"))
        }
    }

    override fun streamStats(id: String): Flow<ContainerStats> = flow {
        // Polling loop — cancellable at the delay; documented degradation of
        // the CLI tier vs the API's push stream.
        while (true) {
            val result = runner.run(
                "${cliContext.envPrefix()}$docker stats --no-stream --format '{{json .}}' ${q(id)}",
                EXEC_TIMEOUT_MS
            )
            if (result.isSuccess) {
                val line = result.stdout.lineSequence().firstOrNull { it.trim().startsWith("{") }
                if (line != null) {
                    try {
                        emit(DockerCliParsers.parseStatsLine(JSONObject(line.trim())))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Skip malformed samples; next poll retries.
                        Logger.w(TAG, "streamStats: unparsable sample (${line.length} chars): ${e.message}")
                    }
                }
            }
            delay(STATS_POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    // ── Images ───────────────────────────────────────────────────────────────

    override suspend fun listImages(): ContainerResult<List<ContainerImageSummary>> =
        cli("Failed to list images", "images --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseImageLine)
        }

    override suspend fun inspectImage(ref: String): ContainerResult<String> =
        cli("Failed to inspect image", "image inspect ${q(ref)}") { it.trim() }

    override fun pullImage(ref: String): Flow<PullProgressEvent> =
        // CLI pull has no NDJSON progress; each output line becomes a
        // status-only event.
        runner.stream("$docker pull ${q(ref)} 2>&1")
            .map { line -> PullProgressEvent(status = line) }

    override suspend fun removeImage(ref: String, force: Boolean): ContainerResult<Unit> =
        cli("Failed to remove image", "rmi${if (force) " -f" else ""} ${q(ref)}", ACTION_TIMEOUT_MS) { }

    override suspend fun pruneImages(): ContainerResult<Unit> =
        cli("Failed to prune images", "image prune -f", ACTION_TIMEOUT_MS) { }

    // ── Volumes ──────────────────────────────────────────────────────────────

    override suspend fun listVolumes(): ContainerResult<List<ContainerVolumeSummary>> =
        cli("Failed to list volumes", "volume ls --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseVolumeLine)
        }

    override suspend fun inspectVolume(name: String): ContainerResult<String> =
        cli("Failed to inspect volume", "volume inspect ${q(name)}") { it.trim() }

    override suspend fun createVolume(name: String, driver: String?): ContainerResult<Unit> {
        val driverArg = driver?.takeIf { it.isNotBlank() }?.let { " -d ${q(it)}" } ?: ""
        return cli("Failed to create volume", "volume create$driverArg ${q(name)}") { }
    }

    override suspend fun removeVolume(name: String, force: Boolean): ContainerResult<Unit> =
        cli("Failed to remove volume", "volume rm${if (force) " -f" else ""} ${q(name)}") { }

    override suspend fun pruneVolumes(): ContainerResult<Unit> =
        cli("Failed to prune volumes", "volume prune -f", ACTION_TIMEOUT_MS) { }

    // ── Networks ─────────────────────────────────────────────────────────────

    override suspend fun listNetworks(): ContainerResult<List<ContainerNetworkSummary>> =
        cli("Failed to list networks", "network ls --format '{{json .}}'") { out ->
            DockerCliParsers.parseNdjson(out, DockerCliParsers::parseNetworkLine)
        }

    override suspend fun inspectNetwork(id: String): ContainerResult<String> =
        cli("Failed to inspect network", "network inspect ${q(id)}") { it.trim() }

    override suspend fun createNetwork(name: String, driver: String?): ContainerResult<Unit> {
        val driverArg = driver?.takeIf { it.isNotBlank() }?.let { " -d ${q(it)}" } ?: ""
        return cli("Failed to create network", "network create$driverArg ${q(name)}") { }
    }

    override suspend fun removeNetwork(id: String): ContainerResult<Unit> =
        cli("Failed to remove network", "network rm ${q(id)}") { }

    override suspend fun pruneNetworks(): ContainerResult<Unit> =
        cli("Failed to prune networks", "network prune -f", ACTION_TIMEOUT_MS) { }

    // ── Snapshots / profiles / projects (not docker CLI concepts) ────────────

    override suspend fun listSnapshots(instance: String): ContainerResult<List<ContainerSnapshotSummary>> =
        gate.refusal(EngineCapability.SNAPSHOTS).asResult()

    override suspend fun createSnapshot(
        instance: String,
        name: String,
        stateful: Boolean
    ): ContainerResult<Unit> = gate.refusal(EngineCapability.SNAPSHOTS).asResult()

    override suspend fun restoreSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.refusal(EngineCapability.SNAPSHOTS).asResult()

    override suspend fun removeSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.refusal(EngineCapability.SNAPSHOTS).asResult()

    override suspend fun listProfiles(): ContainerResult<List<ContainerProfileSummary>> =
        gate.refusal(EngineCapability.PROFILES).asResult()

    override suspend fun inspectProfile(name: String): ContainerResult<String> =
        gate.refusal(EngineCapability.PROFILES).asResult()

    override suspend fun listProjects(): ContainerResult<List<ContainerProjectSummary>> =
        gate.refusal(EngineCapability.PROJECTS).asResult()

    override suspend fun inspectProject(name: String): ContainerResult<String> =
        gate.refusal(EngineCapability.PROJECTS).asResult()

    override val activeProject: String? = null

    override suspend fun selectProject(name: String): ContainerResult<Unit> =
        gate.refusal(EngineCapability.PROJECTS).asResult()

    // ── Engine ───────────────────────────────────────────────────────────────

    override suspend fun engineInfo(): ContainerResult<ContainerEngineInfo> =
        cli("Failed to read engine info", "info --format '{{json .}}'") { out ->
            DockerCliParsers.parseCliInfo(out)
        }.flatMapNotNull("Engine info was unparsable")

    override suspend fun engineVersion(): ContainerResult<ContainerEngineVersion> =
        cli("Failed to read engine version", "version --format '{{json .}}'") { out ->
            DockerCliParsers.parseCliVersion(out)
        }.flatMapNotNull("Engine version was unparsable")

    override suspend fun diskUsage(): ContainerResult<ContainerDiskUsage> =
        gate.reject(EngineCapability.DISK_USAGE)
            ?: cli("Failed to read disk usage", "system df --format '{{json .}}'") { out ->
                ContainerDiskUsage(DockerCliParsers.parseNdjson(out, DockerCliParsers::parseSystemDfLine))
            }

    // ── Compose + remote files (shared RemoteExecOps) ────────────────────────

    override suspend fun composeUp(stackDir: String): ContainerResult<String> =
        remoteOps.composeUp(stackDir)

    override suspend fun composeDown(stackDir: String): ContainerResult<String> =
        remoteOps.composeDown(stackDir)

    override suspend fun composePull(stackDir: String): ContainerResult<String> =
        remoteOps.composePull(stackDir)

    override suspend fun composeRestart(stackDir: String): ContainerResult<String> =
        remoteOps.composeRestart(stackDir)

    override suspend fun composePs(stackDir: String): ContainerResult<String> =
        remoteOps.composePs(stackDir)

    override fun composeLogs(stackDir: String, service: String?, tail: Int): Flow<String> =
        remoteOps.composeLogs(stackDir, service, tail)

    override suspend fun composeLs(): ContainerResult<List<ComposeLsEntry>> =
        remoteOps.composeLs()

    override suspend fun composeUpByProject(name: String, configFile: String): ContainerResult<String> =
        remoteOps.composeUpByProject(name, configFile)

    override suspend fun composeDownByProject(name: String, configFile: String): ContainerResult<String> =
        remoteOps.composeDownByProject(name, configFile)

    override suspend fun composePullByProject(name: String, configFile: String): ContainerResult<String> =
        remoteOps.composePullByProject(name, configFile)

    override suspend fun composeRestartByProject(name: String, configFile: String): ContainerResult<String> =
        remoteOps.composeRestartByProject(name, configFile)

    override suspend fun composePsByProject(name: String, configFile: String): ContainerResult<String> =
        remoteOps.composePsByProject(name, configFile)

    override fun composeLogsByProject(
        name: String,
        configFile: String,
        service: String?,
        tail: Int
    ): Flow<String> = remoteOps.composeLogsByProject(name, configFile, service, tail)

    override suspend fun detectComposeInvocation(): ContainerResult<ComposeInvocation> =
        remoteOps.detectComposeInvocation()

    override suspend fun readRemoteFile(path: String): ContainerResult<String> =
        remoteOps.readRemoteFile(path)

    override suspend fun writeRemoteFile(path: String, content: String): ContainerResult<Unit> =
        remoteOps.writeRemoteFile(path, content)

    override suspend fun listRemoteDir(path: String): ContainerResult<List<RemoteDirEntry>> =
        remoteOps.listRemoteDir(path)

    override suspend fun expandRemotePath(path: String): ContainerResult<String> =
        remoteOps.expandRemotePath(path)

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override suspend fun close() {
        // Exec channels are per-call; nothing persistent to release.
    }
}

/** Turn a Success(null) parse outcome into a typed Error result. */
internal fun <T : Any> ContainerResult<T?>.flatMapNotNull(message: String): ContainerResult<T> =
    when (this) {
        is ContainerResult.Success -> value?.let { ContainerResult.Success(it) }
            ?: ContainerResult.Error(message)
        is ContainerResult.PermissionDenied -> this
        is ContainerResult.NotFound -> this
        is ContainerResult.EngineNotInstalled -> this
        is ContainerResult.TransportUnavailable -> this
        is ContainerResult.Error -> this
    }

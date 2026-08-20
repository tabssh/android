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
 * Fallback transport for Incus and LXC/LXD: every operation runs the engine's
 * own CLI over SSH exec, mirroring [CliExecTransport] for the Docker family.
 *
 * Two command forms cover the whole surface:
 *  - the natural verbs (`list`, `start`, `delete`, `image copy`, …) where both
 *    binaries agree, always with `--format json` so nothing parses columns;
 *  - `query [-X METHOD] [-d DATA] [--wait] <path>` everywhere the two
 *    binaries' verbs diverge (snapshots most of all, where `incus` grew a
 *    `snapshot create` subcommand that `lxc` does not have). `query` speaks
 *    the same REST paths the API tier uses, so one set of parsers serves both
 *    tiers and the two stay at feature parity by construction.
 *
 * Degradations vs [IncusApiTransport]:
 *  - [streamStats] and [streamLogs] poll instead of following, the same way
 *    the Docker CLI tier polls `stats --no-stream`.
 *  - [pullImage] emits raw `image copy` output lines as status-only events.
 */
class IncusCliTransport(
    private val host: ContainerHost,
    private val runner: SshExecRunner,
    resolver: EngineSocketResolver = EngineSocketResolver(host, runner)
) : ContainerTransport {

    private companion object {
        private const val TAG = "IncusCliTransport"
        private const val EXEC_TIMEOUT_MS = 60_000L
        private const val ACTION_TIMEOUT_MS = 120_000L
        private const val POLL_INTERVAL_MS = 2_000L

        /** Root of the only API version these engines have ever published. */
        private const val API_ROOT = "/1.0"

        /** Volume type these engines use for user-created storage volumes. */
        private const val VOLUME_TYPE_CUSTOM = "custom"

        /** Default network type when the caller names none. */
        private const val DEFAULT_NETWORK_TYPE = "bridge"
    }

    private val engine: ContainerEngine = host.engineType()

    private val gate = EngineCapabilityGate(engine)

    private val cliContext = EngineCliContext(host, resolver)

    private val remoteOps = RemoteExecOps(runner, host, cliContext)

    /** The engine binary — `incus` or `lxc`, per-host path or PATH lookup. */
    private val cliBinary: String
        get() = host.cliBinary()

    @Volatile
    private var project: String? = null

    override val activeProject: String?
        get() = project

    private fun q(value: String): String = SshExecRunner.shQuote(value)

    /** `--project` for the natural verbs; empty when no project is selected. */
    private fun projectFlag(): String = project?.let { " --project ${q(it)}" } ?: ""

    /**
     * Carry the active project on a `query` path. The REST layer takes it as a
     * query parameter, which is unambiguous whichever binary is running and
     * whichever version of it understands `--project` on `query`.
     */
    private fun scoped(path: String): String {
        val name = project ?: return path
        val separator = if (path.contains('?')) "&" else "?"
        return "$path${separator}project=$name"
    }

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
        // Never log `args`/stdout/stderr verbatim — an instance name, config
        // key or query body can carry user-entered or daemon-echoed secrets.
        // Only the context label, exit code and timing are safe.
        val startedAt = System.currentTimeMillis()
        return try {
            val result = runner.run("${cliContext.envPrefix()}$cliBinary$args", timeoutMs)
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (!result.isSuccess) {
                Logger.w(TAG, "$context: cli failed exit=${result.exitStatus} elapsedMs=$elapsedMs")
                IncusCliParsers.classifyFailure(context, result.stderr, result.stdout, engine)
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

    /** A verb both binaries share, scoped to the active project. */
    private suspend fun <T> verb(
        context: String,
        args: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
        transform: (String) -> T
    ): ContainerResult<T> = cli(context, "${projectFlag()} $args", timeoutMs, transform)

    /** A read through `query`, whose answer is the bare REST payload. */
    private suspend fun <T> query(
        context: String,
        path: String,
        timeoutMs: Long = EXEC_TIMEOUT_MS,
        transform: (String) -> T
    ): ContainerResult<T> = cli(context, " query ${q(scoped(path))}", timeoutMs, transform)

    /**
     * A mutation through `query`. `--wait` makes the CLI block until the
     * operation the daemon queued has finished, which is the CLI tier's
     * equivalent of the API tier's operation wait — without it the command
     * would return before the change is visible.
     */
    private suspend fun mutate(
        context: String,
        path: String,
        method: String,
        body: JSONObject? = null
    ): ContainerResult<Unit> {
        val data = body?.let { " -d ${q(it.toString())}" } ?: ""
        return cli(
            context,
            " query --wait -X $method$data ${q(scoped(path))}",
            ACTION_TIMEOUT_MS
        ) { }
    }

    // ── Instances ────────────────────────────────────────────────────────────

    override suspend fun listContainers(all: Boolean): ContainerResult<List<ContainerSummary>> =
        verb("Failed to list instances", "list --format json") { out ->
            val instances = IncusCliParsers.parseInstanceList(out)
            if (all) instances else instances.filter { it.state == "running" }
        }

    override suspend fun inspectContainer(id: String): ContainerResult<String> =
        query("Failed to inspect instance", "$API_ROOT/instances/$id") { it.trim() }

    override suspend fun containerAction(id: String, action: ContainerAction): ContainerResult<Unit> =
        when (action) {
            // Freeze and thaw are the two verbs the two binaries spell
            // differently, so they go through the REST path both understand.
            ContainerAction.PAUSE, ContainerAction.UNPAUSE -> mutate(
                "Failed to ${action.verb} instance",
                "$API_ROOT/instances/$id/state",
                "PUT",
                JSONObject()
                    .put("action", if (action == ContainerAction.PAUSE) "freeze" else "unfreeze")
                    .put("timeout", -1)
            )
            // There is no kill verb; a forced stop is the kill.
            ContainerAction.KILL -> verb(
                "Failed to kill instance",
                "stop --force ${q(id)}",
                ACTION_TIMEOUT_MS
            ) { }
            else -> verb(
                "Failed to ${action.verb} instance",
                "${action.verb} ${q(id)}",
                ACTION_TIMEOUT_MS
            ) { }
        }

    override suspend fun renameContainer(id: String, newName: String): ContainerResult<Unit> =
        verb("Failed to rename instance", "rename ${q(id)} ${q(newName)}") { }

    override suspend fun removeContainer(id: String, force: Boolean): ContainerResult<Unit> =
        verb(
            "Failed to remove instance",
            "delete${if (force) " --force" else ""} ${q(id)}",
            ACTION_TIMEOUT_MS
        ) { }

    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): ContainerResult<Unit> {
        // The recreate plan is written in the Docker Engine API's vocabulary;
        // the image reference is the part that carries over, and `launch`
        // creates and starts an instance from an image in one step.
        val image = createBody.optString("Image")
        if (image.isEmpty()) {
            return ContainerResult.Error(
                "Failed to create instance $name",
                "the container config names no image"
            )
        }
        return verb(
            "Failed to create instance $name",
            "launch ${q(image)} ${q(name)}",
            ACTION_TIMEOUT_MS
        ) { }
    }

    /**
     * Follow an instance's console ring buffer.
     *
     * `console --show-log` prints the buffer and exits — there is no follow
     * mode for a log the way `docker logs --follow` has one — so the buffer is
     * polled and only lines that appeared since the previous poll are emitted.
     */
    override fun streamLogs(id: String, tail: Int?): Flow<String> = flow {
        var emitted = 0
        var first = true
        while (true) {
            val log = verb("Failed to read instance log", "console --show-log ${q(id)}") { it }
            if (log is ContainerResult.Success) {
                val lines = log.value.lines().dropLastWhile { it.isEmpty() }
                // A truncated ring buffer shrinks; restart from its new end
                // rather than replaying everything that is still in it.
                if (lines.size < emitted) emitted = 0
                val fresh = lines.drop(emitted)
                emitted = lines.size
                val window = if (first && tail != null && fresh.size > tail) fresh.takeLast(tail) else fresh
                first = false
                window.forEach { emit(it) }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    override fun streamStats(id: String): Flow<ContainerStats> = flow {
        val memLimit = IncusApiParsers.parseMemoryLimit(instanceConfig(id))
        var previous: IncusApiParsers.InstanceStateSample? = null
        var previousAt = 0L
        while (true) {
            val state = query("Failed to read instance state", "$API_ROOT/instances/$id/state") {
                IncusCliParsers.parseInstanceState(it)
            }
            if (state is ContainerResult.Success) {
                val sample = state.value
                if (sample != null) {
                    val now = System.currentTimeMillis()
                    emit(IncusApiParsers.statsFrom(sample, previous, now - previousAt, memLimit))
                    previous = sample
                    previousAt = now
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun instanceConfig(id: String): Map<String, String> =
        query("Failed to read instance config", "$API_ROOT/instances/$id") {
            IncusCliParsers.parseInstanceConfig(it)
        }.valueOrNull().orEmpty()

    // ── Images ───────────────────────────────────────────────────────────────

    override suspend fun listImages(): ContainerResult<List<ContainerImageSummary>> =
        verb("Failed to list images", "image list --format json") { out ->
            IncusCliParsers.parseImageList(out)
        }

    override suspend fun inspectImage(ref: String): ContainerResult<String> =
        query("Failed to inspect image", "$API_ROOT/images/$ref") { it.trim() }

    /**
     * Copy a remote image into the local store. The CLI writes its progress to
     * the terminal rather than to a machine-readable stream, so each output
     * line becomes a status-only event — the same shape the Docker CLI tier
     * produces for `docker pull`.
     */
    override fun pullImage(ref: String): Flow<PullProgressEvent> {
        val alias = ref.substringAfterLast(':').substringAfterLast('/')
        return flow {
            val command = "${cliContext.envPrefix()}$cliBinary${projectFlag()} " +
                "image copy ${q(ref)} local: --alias ${q(alias)} 2>&1"
            emitAll(runner.stream(command))
        }
            .map { PullProgressEvent(status = it) }
            .flowOn(Dispatchers.IO)
    }

    override suspend fun removeImage(ref: String, force: Boolean): ContainerResult<Unit> =
        verb("Failed to remove image", "image delete ${q(ref)}", ACTION_TIMEOUT_MS) { }

    override suspend fun pruneImages(): ContainerResult<Unit> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    // ── Volumes ──────────────────────────────────────────────────────────────

    override suspend fun listVolumes(): ContainerResult<List<ContainerVolumeSummary>> {
        val pools = storagePools()
        if (pools !is ContainerResult.Success) return pools.map { emptyList<ContainerVolumeSummary>() }
        val all = mutableListOf<ContainerVolumeSummary>()
        for ((pool, driver) in pools.value) {
            val volumes = verb(
                "Failed to list storage volumes",
                "storage volume list ${q(pool)} --format json"
            ) { IncusCliParsers.parseVolumeList(it, pool, driver) }
            when (volumes) {
                is ContainerResult.Success -> all += volumes.value
                // A pool that is pending or unreachable on this cluster member
                // must not blank the volumes of every healthy pool.
                else -> Logger.w(TAG, "listVolumes: skipped a pool")
            }
        }
        return ContainerResult.Success(all)
    }

    private suspend fun storagePools(): ContainerResult<Map<String, String>> =
        verb("Failed to list storage pools", "storage list --format json") {
            IncusCliParsers.parseStoragePools(it)
        }

    override suspend fun inspectVolume(name: String): ContainerResult<String> {
        val (pool, volume) = splitVolumeRef(name)
            ?: return ContainerResult.NotFound("Failed to inspect storage volume", name)
        return query(
            "Failed to inspect storage volume",
            "$API_ROOT/storage-pools/$pool/volumes/$VOLUME_TYPE_CUSTOM/$volume"
        ) { it.trim() }
    }

    override suspend fun createVolume(name: String, driver: String?): ContainerResult<Unit> {
        val explicit = splitVolumeRef(name)
        val pool = explicit?.first
            ?: driver?.takeIf { it.isNotBlank() }
            ?: defaultPool()
            ?: return ContainerResult.Error(
                "Failed to create storage volume",
                "this host has no storage pool to create the volume in"
            )
        val volume = explicit?.second ?: name
        return verb(
            "Failed to create storage volume",
            "storage volume create ${q(pool)} ${q(volume)}",
            ACTION_TIMEOUT_MS
        ) { }
    }

    override suspend fun removeVolume(name: String, force: Boolean): ContainerResult<Unit> {
        val (pool, volume) = splitVolumeRef(name)
            ?: return ContainerResult.NotFound("Failed to remove storage volume", name)
        return verb(
            "Failed to remove storage volume",
            "storage volume delete ${q(pool)} ${q(volume)}",
            ACTION_TIMEOUT_MS
        ) { }
    }

    override suspend fun pruneVolumes(): ContainerResult<Unit> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    /**
     * Split a pool-qualified volume name ("default/data"); null when the name
     * carries no pool, which the caller resolves against the host's pools.
     */
    private fun splitVolumeRef(name: String): Pair<String, String>? {
        val separator = name.indexOf('/')
        if (separator <= 0 || separator == name.length - 1) return null
        return name.substring(0, separator) to name.substring(separator + 1)
    }

    private suspend fun defaultPool(): String? = storagePools().valueOrNull()?.keys?.firstOrNull()

    // ── Networks ─────────────────────────────────────────────────────────────

    override suspend fun listNetworks(): ContainerResult<List<ContainerNetworkSummary>> =
        verb("Failed to list networks", "network list --format json") { out ->
            IncusCliParsers.parseNetworkList(out)
        }

    override suspend fun inspectNetwork(id: String): ContainerResult<String> =
        query("Failed to inspect network", "$API_ROOT/networks/$id") { it.trim() }

    override suspend fun createNetwork(name: String, driver: String?): ContainerResult<Unit> {
        val type = driver?.takeIf { it.isNotBlank() } ?: DEFAULT_NETWORK_TYPE
        return verb(
            "Failed to create network",
            "network create ${q(name)} --type ${q(type)}",
            ACTION_TIMEOUT_MS
        ) { }
    }

    override suspend fun removeNetwork(id: String): ContainerResult<Unit> =
        verb("Failed to remove network", "network delete ${q(id)}", ACTION_TIMEOUT_MS) { }

    override suspend fun pruneNetworks(): ContainerResult<Unit> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    // ── Snapshots ────────────────────────────────────────────────────────────

    override suspend fun listSnapshots(instance: String): ContainerResult<List<ContainerSnapshotSummary>> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: query(
            "Failed to list snapshots",
            "$API_ROOT/instances/$instance/snapshots?recursion=1"
        ) { IncusCliParsers.parseSnapshotList(it, instance) }

    override suspend fun createSnapshot(
        instance: String,
        name: String,
        stateful: Boolean
    ): ContainerResult<Unit> = gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
        "Failed to create snapshot",
        "$API_ROOT/instances/$instance/snapshots",
        "POST",
        JSONObject().put("name", name).put("stateful", stateful)
    )

    override suspend fun restoreSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
            "Failed to restore snapshot",
            "$API_ROOT/instances/$instance",
            "PUT",
            JSONObject().put("restore", name)
        )

    override suspend fun removeSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
            "Failed to remove snapshot",
            "$API_ROOT/instances/$instance/snapshots/$name",
            "DELETE"
        )

    // ── Profiles ─────────────────────────────────────────────────────────────

    override suspend fun listProfiles(): ContainerResult<List<ContainerProfileSummary>> =
        gate.reject(EngineCapability.PROFILES) ?: verb(
            "Failed to list profiles",
            "profile list --format json"
        ) { IncusCliParsers.parseProfileList(it) }

    override suspend fun inspectProfile(name: String): ContainerResult<String> =
        gate.reject(EngineCapability.PROFILES) ?: query(
            "Failed to inspect profile",
            "$API_ROOT/profiles/$name"
        ) { it.trim() }

    // ── Projects ─────────────────────────────────────────────────────────────

    override suspend fun listProjects(): ContainerResult<List<ContainerProjectSummary>> =
        gate.reject(EngineCapability.PROJECTS) ?: cli(
            "Failed to list projects",
            " project list --format json"
        ) { IncusCliParsers.parseProjectList(it, project) }

    override suspend fun inspectProject(name: String): ContainerResult<String> =
        gate.reject(EngineCapability.PROJECTS) ?: cli(
            "Failed to inspect project",
            " query ${q("$API_ROOT/projects/$name")}"
        ) { it.trim() }

    override suspend fun selectProject(name: String): ContainerResult<Unit> {
        gate.reject(EngineCapability.PROJECTS)?.let { return it }
        // Confirm the project exists before every later command starts
        // carrying it, so a typo fails here instead of on each tab.
        val verified = cli("Failed to select project", " query ${q("$API_ROOT/projects/$name")}") { }
        if (verified is ContainerResult.Success) project = name
        return verified
    }

    // ── Engine ───────────────────────────────────────────────────────────────

    override suspend fun engineInfo(): ContainerResult<ContainerEngineInfo> {
        val info = cli("Failed to read engine info", " query ${q(API_ROOT)}") {
            IncusCliParsers.parseServerInfo(it)
        }.flatMapNotNull("Engine info was unparsable")
        if (info !is ContainerResult.Success) return info
        // The daemon's own description carries no object counts, so the
        // dashboard's inventory comes from the listings it would load anyway.
        val instances = listContainers(all = true).valueOrNull().orEmpty()
        val images = listImages().valueOrNull().orEmpty()
        return ContainerResult.Success(
            info.value.copy(
                containersTotal = instances.size,
                containersRunning = instances.count { it.state == "running" },
                containersPaused = instances.count { it.state == "paused" },
                containersStopped = instances.count { it.state == "exited" },
                images = images.size
            )
        )
    }

    override suspend fun engineVersion(): ContainerResult<ContainerEngineVersion> =
        cli("Failed to read engine version", " query ${q(API_ROOT)}") {
            IncusCliParsers.parseServerVersion(it)
        }.flatMapNotNull("Engine version was unparsable")

    override suspend fun diskUsage(): ContainerResult<ContainerDiskUsage> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

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

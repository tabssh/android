package io.github.tabssh.containers.transport

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Outcome of a Docker transport operation.
 *
 * Every transport method returns a [ContainerResult] instead of throwing so the
 * caller (UI / workers) can pattern-match on the failure class:
 *  - [PermissionDenied] — the SSH user cannot access the engine's socket or CLI
 *    (remediation is per engine, see
 *    [ContainerTransportMessages.socketPermission]).
 *  - [NotFound] — the referenced object (container/image/volume/network/file)
 *    does not exist on the host.
 *  - [EngineNotInstalled] — the engine's binary/daemon is absent from the host
 *    entirely. Distinct from [TransportUnavailable], which means the engine is
 *    there but this tier cannot reach it (daemon down, forward refused): the
 *    two need different remediation, so they are different reasons.
 *  - [TransportUnavailable] — the transport tier itself is unusable (no relay,
 *    daemon not running, SSH session gone); the caller may retry another tier.
 *  - [Error] — any other engine/CLI failure.
 */
sealed class ContainerResult<out T> {
    data class Success<T>(val value: T) : ContainerResult<T>()

    data class PermissionDenied(
        val message: String,
        val detail: String? = null
    ) : ContainerResult<Nothing>()

    data class NotFound(
        val message: String,
        val detail: String? = null
    ) : ContainerResult<Nothing>()

    data class EngineNotInstalled(
        val message: String,
        val detail: String? = null
    ) : ContainerResult<Nothing>()

    data class TransportUnavailable(
        val message: String,
        val detail: String? = null
    ) : ContainerResult<Nothing>()

    data class Error(
        val message: String,
        val detail: String? = null
    ) : ContainerResult<Nothing>()

    /** Map a successful value, passing failures through unchanged. */
    fun <R> map(transform: (T) -> R): ContainerResult<R> = when (this) {
        is Success -> Success(transform(value))
        is PermissionDenied -> this
        is NotFound -> this
        is EngineNotInstalled -> this
        is TransportUnavailable -> this
        is Error -> this
    }

    /** The success value, or null for any failure. */
    fun valueOrNull(): T? = (this as? Success)?.value
}

/**
 * Internal signal that a transport tier cannot be used at all (as opposed to a
 * per-object failure). Caught at the transport boundary and mapped to
 * [ContainerResult.TransportUnavailable].
 */
class TransportUnavailableException(
    message: String,
    val detail: String? = null
) : Exception(message)

/** Lifecycle verbs accepted by both the Engine API and the docker CLI. */
enum class ContainerAction(val verb: String) {
    START("start"),
    STOP("stop"),
    RESTART("restart"),
    PAUSE("pause"),
    UNPAUSE("unpause"),
    KILL("kill")
}

/** How `docker compose` is invoked on the remote host. */
enum class ComposeInvocation(val commandPrefix: String) {
    /** Compose v2 plugin — `docker compose …`. */
    PLUGIN("docker compose"),
    /** Standalone binary — `docker-compose …`. */
    STANDALONE("docker-compose")
}

/**
 * Transport-agnostic Docker host operations.
 *
 * Implementations:
 *  - [EngineApiTransport] — Docker Engine REST API over an SSH unix-socket
 *    relay ([SocketRelay]); compose and remote-file operations still run over
 *    SSH exec because compose has no REST API.
 *  - [CliExecTransport] — `docker … --format '{{json .}}'` over SSH exec.
 *
 * Remote paths handed to the file operations may contain `$USER`/`$HOME`,
 * which are expanded on the REMOTE shell (see [expandRemotePath]); every
 * remote write is preceded by `mkdir -p` of the parent directory.
 */
interface ContainerTransport {

    // ── Containers ───────────────────────────────────────────────────────────

    /** List containers; [all] includes stopped ones (docker ps -a). */
    suspend fun listContainers(all: Boolean = true): ContainerResult<List<ContainerSummary>>

    /** Full inspect JSON for one container (single JSON object as a string). */
    suspend fun inspectContainer(id: String): ContainerResult<String>

    /** Run a lifecycle [action] (start/stop/restart/pause/unpause/kill). */
    suspend fun containerAction(id: String, action: ContainerAction): ContainerResult<Unit>

    /** Rename a container. */
    suspend fun renameContainer(id: String, newName: String): ContainerResult<Unit>

    /** Remove a container; [force] kills a running one first. */
    suspend fun removeContainer(id: String, force: Boolean = false): ContainerResult<Unit>

    /**
     * Create AND start a container named [name] from a recreate plan
     * (RecreateContainer, ). Each tier consumes its half of
     * the plan: the Engine API posts [createBody] verbatim to
     * `/containers/create?name={name}` and starts the result; the CLI tier
     * runs the [runArgv] `docker run` tokens (which already carry `--name`) —
     * the documented lossier fallback.
     */
    suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): ContainerResult<Unit>

    /**
     * Follow container logs as a Flow of text lines. The underlying stream
     * stays open until the collector cancels. [tail] limits the initial
     * backlog (null = engine default).
     */
    fun streamLogs(id: String, tail: Int? = null): Flow<String>

    /**
     * Live resource statistics as a Flow. The Engine API streams ~1 sample/s;
     * the CLI tier polls `docker stats --no-stream` (documented degradation).
     */
    fun streamStats(id: String): Flow<ContainerStats>

    // ── Images ───────────────────────────────────────────────────────────────

    suspend fun listImages(): ContainerResult<List<ContainerImageSummary>>

    /** Full inspect JSON for one image. */
    suspend fun inspectImage(ref: String): ContainerResult<String>

    /**
     * Pull [ref] with progress events. Engine API tier parses the NDJSON
     * progress stream; the CLI tier emits raw `docker pull` output lines as
     * status-only events.
     */
    fun pullImage(ref: String): Flow<PullProgressEvent>

    suspend fun removeImage(ref: String, force: Boolean = false): ContainerResult<Unit>

    suspend fun pruneImages(): ContainerResult<Unit>

    // ── Volumes ──────────────────────────────────────────────────────────────

    suspend fun listVolumes(): ContainerResult<List<ContainerVolumeSummary>>

    suspend fun inspectVolume(name: String): ContainerResult<String>

    suspend fun createVolume(name: String, driver: String? = null): ContainerResult<Unit>

    suspend fun removeVolume(name: String, force: Boolean = false): ContainerResult<Unit>

    suspend fun pruneVolumes(): ContainerResult<Unit>

    // ── Networks ─────────────────────────────────────────────────────────────

    suspend fun listNetworks(): ContainerResult<List<ContainerNetworkSummary>>

    suspend fun inspectNetwork(id: String): ContainerResult<String>

    suspend fun createNetwork(name: String, driver: String? = null): ContainerResult<Unit>

    suspend fun removeNetwork(id: String): ContainerResult<Unit>

    suspend fun pruneNetworks(): ContainerResult<Unit>

    // ── Snapshots (EngineCapability.SNAPSHOTS) ───────────────────────────────

    /** Snapshots of one instance, newest-first as the engine reports them. */
    suspend fun listSnapshots(instance: String): ContainerResult<List<ContainerSnapshotSummary>>

    /**
     * Snapshot [instance] as [name]. [stateful] additionally captures the
     * running memory state, which the engine refuses when the instance is
     * stopped or CRIU is unavailable.
     */
    suspend fun createSnapshot(
        instance: String,
        name: String,
        stateful: Boolean = false
    ): ContainerResult<Unit>

    /** Roll [instance] back to snapshot [name]. */
    suspend fun restoreSnapshot(instance: String, name: String): ContainerResult<Unit>

    /** Delete snapshot [name] of [instance]. */
    suspend fun removeSnapshot(instance: String, name: String): ContainerResult<Unit>

    // ── Profiles (EngineCapability.PROFILES) ─────────────────────────────────

    suspend fun listProfiles(): ContainerResult<List<ContainerProfileSummary>>

    /** Full profile definition as a single JSON object string. */
    suspend fun inspectProfile(name: String): ContainerResult<String>

    // ── Projects (EngineCapability.PROJECTS) ─────────────────────────────────

    suspend fun listProjects(): ContainerResult<List<ContainerProjectSummary>>

    /** Full project definition as a single JSON object string. */
    suspend fun inspectProject(name: String): ContainerResult<String>

    /**
     * The project every subsequent call on this transport is scoped to, or
     * null on engines without projects. Engines have no server-side "current
     * project" for an API client, so this is transport-local state, applied as
     * `?project=` on the REST tier and `--project` on the CLI tier.
     */
    val activeProject: String?

    /** Scope this transport to [name]; callers reload after it succeeds. */
    suspend fun selectProject(name: String): ContainerResult<Unit>

    // ── Engine ───────────────────────────────────────────────────────────────

    suspend fun engineInfo(): ContainerResult<ContainerEngineInfo>

    suspend fun engineVersion(): ContainerResult<ContainerEngineVersion>

    /** Disk usage rows for the host dashboard (/system/df or `system df`). */
    suspend fun diskUsage(): ContainerResult<ContainerDiskUsage>

    // ── Compose (always SSH exec — compose has no REST API) ─────────────────

    suspend fun composeUp(stackDir: String): ContainerResult<String>

    suspend fun composeDown(stackDir: String): ContainerResult<String>

    suspend fun composePull(stackDir: String): ContainerResult<String>

    suspend fun composeRestart(stackDir: String): ContainerResult<String>

    /** `compose ps --format json` raw output for the stack status view. */
    suspend fun composePs(stackDir: String): ContainerResult<String>

    /**
     * Follow `compose logs` for a Room-tracked stack directory. [service]
     * scopes the stream to one service; null aggregates every service.
     */
    fun composeLogs(stackDir: String, service: String? = null, tail: Int = 200): Flow<String>

    /**
     * Discover every compose project on the host, tracked or not
     * (`docker compose ls --all --format json`). Hosts without compose
     * installed surface as [ContainerResult.TransportUnavailable] — callers
     * treat that as "no external stacks" rather than a hard failure.
     */
    suspend fun composeLs(): ContainerResult<List<ComposeLsEntry>>

    /**
     * Compose lifecycle actions for a project discovered by [composeLs] that
     * has no Room row — addressed by `-f <configFile> -p <name>` rather than
     * a `cd` into a stack directory, matching how [composeUp] et al. build
     * their commands for tracked stacks.
     */
    suspend fun composeUpByProject(name: String, configFile: String): ContainerResult<String>

    suspend fun composeDownByProject(name: String, configFile: String): ContainerResult<String>

    suspend fun composePullByProject(name: String, configFile: String): ContainerResult<String>

    suspend fun composeRestartByProject(name: String, configFile: String): ContainerResult<String>

    suspend fun composePsByProject(name: String, configFile: String): ContainerResult<String>

    /** Follow `compose logs` for an untracked project; see [composeLogs]. */
    fun composeLogsByProject(
        name: String,
        configFile: String,
        service: String? = null,
        tail: Int = 200
    ): Flow<String>

    /**
     * Detect whether the host has the compose plugin (`docker compose`) or
     * the standalone `docker-compose` binary. Result is cached per transport
     * instance; a host-level pin in ContainerHost.composeInvocation short-circuits
     * the probe.
     */
    suspend fun detectComposeInvocation(): ContainerResult<ComposeInvocation>

    // ── Remote files (always SSH exec) ───────────────────────────────────────

    /** Read a remote text file (base64-safe transfer, no quoting corruption). */
    suspend fun readRemoteFile(path: String): ContainerResult<String>

    /**
     * Write [content] to a remote file. The parent directory is created with
     * `mkdir -p` first; content travels base64-encoded so no shell quoting or
     * heredoc corruption is possible.
     */
    suspend fun writeRemoteFile(path: String, content: String): ContainerResult<Unit>

    /** List a remote directory (entries with a directory flag). */
    suspend fun listRemoteDir(path: String): ContainerResult<List<RemoteDirEntry>>

    /**
     * Expand `$USER`/`$HOME` in [path] using the REMOTE shell's values.
     * Values are fetched from the remote once and substituted locally, so the
     * path itself is never evaluated by the remote shell (no injection).
     */
    suspend fun expandRemotePath(path: String): ContainerResult<String>

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Release transport resources (relay sockets, bridge processes). */
    suspend fun close()
}

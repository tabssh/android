package io.github.tabssh.docker.transport

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * Outcome of a Docker transport operation.
 *
 * Every transport method returns a [DockerResult] instead of throwing so the
 * caller (UI / workers) can pattern-match on the failure class:
 *  - [PermissionDenied] — the SSH user cannot access the Docker socket or CLI
 *    (remediation: add the user to the `docker` group, see
 *    [DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION]).
 *  - [NotFound] — the referenced object (container/image/volume/network/file)
 *    does not exist on the host.
 *  - [TransportUnavailable] — the transport tier itself is unusable (no relay,
 *    no `docker` binary, SSH session gone); the caller may retry another tier.
 *  - [Error] — any other engine/CLI failure.
 */
sealed class DockerResult<out T> {
    data class Success<T>(val value: T) : DockerResult<T>()

    data class PermissionDenied(
        val message: String,
        val detail: String? = null
    ) : DockerResult<Nothing>()

    data class NotFound(
        val message: String,
        val detail: String? = null
    ) : DockerResult<Nothing>()

    data class TransportUnavailable(
        val message: String,
        val detail: String? = null
    ) : DockerResult<Nothing>()

    data class Error(
        val message: String,
        val detail: String? = null
    ) : DockerResult<Nothing>()

    /** Map a successful value, passing failures through unchanged. */
    fun <R> map(transform: (T) -> R): DockerResult<R> = when (this) {
        is Success -> Success(transform(value))
        is PermissionDenied -> this
        is NotFound -> this
        is TransportUnavailable -> this
        is Error -> this
    }

    /** The success value, or null for any failure. */
    fun valueOrNull(): T? = (this as? Success)?.value
}

/**
 * Internal signal that a transport tier cannot be used at all (as opposed to a
 * per-object failure). Caught at the transport boundary and mapped to
 * [DockerResult.TransportUnavailable].
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
interface DockerTransport {

    // ── Containers ───────────────────────────────────────────────────────────

    /** List containers; [all] includes stopped ones (docker ps -a). */
    suspend fun listContainers(all: Boolean = true): DockerResult<List<DockerContainerSummary>>

    /** Full inspect JSON for one container (single JSON object as a string). */
    suspend fun inspectContainer(id: String): DockerResult<String>

    /** Run a lifecycle [action] (start/stop/restart/pause/unpause/kill). */
    suspend fun containerAction(id: String, action: ContainerAction): DockerResult<Unit>

    /** Rename a container. */
    suspend fun renameContainer(id: String, newName: String): DockerResult<Unit>

    /** Remove a container; [force] kills a running one first. */
    suspend fun removeContainer(id: String, force: Boolean = false): DockerResult<Unit>

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
    ): DockerResult<Unit>

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
    fun streamStats(id: String): Flow<DockerContainerStats>

    // ── Images ───────────────────────────────────────────────────────────────

    suspend fun listImages(): DockerResult<List<DockerImageSummary>>

    /** Full inspect JSON for one image. */
    suspend fun inspectImage(ref: String): DockerResult<String>

    /**
     * Pull [ref] with progress events. Engine API tier parses the NDJSON
     * progress stream; the CLI tier emits raw `docker pull` output lines as
     * status-only events.
     */
    fun pullImage(ref: String): Flow<PullProgressEvent>

    suspend fun removeImage(ref: String, force: Boolean = false): DockerResult<Unit>

    suspend fun pruneImages(): DockerResult<Unit>

    // ── Volumes ──────────────────────────────────────────────────────────────

    suspend fun listVolumes(): DockerResult<List<DockerVolumeSummary>>

    suspend fun inspectVolume(name: String): DockerResult<String>

    suspend fun createVolume(name: String, driver: String? = null): DockerResult<Unit>

    suspend fun removeVolume(name: String, force: Boolean = false): DockerResult<Unit>

    suspend fun pruneVolumes(): DockerResult<Unit>

    // ── Networks ─────────────────────────────────────────────────────────────

    suspend fun listNetworks(): DockerResult<List<DockerNetworkSummary>>

    suspend fun inspectNetwork(id: String): DockerResult<String>

    suspend fun createNetwork(name: String, driver: String? = null): DockerResult<Unit>

    suspend fun removeNetwork(id: String): DockerResult<Unit>

    suspend fun pruneNetworks(): DockerResult<Unit>

    // ── Engine ───────────────────────────────────────────────────────────────

    suspend fun engineInfo(): DockerResult<DockerEngineInfo>

    suspend fun engineVersion(): DockerResult<DockerVersionInfo>

    /** Disk usage rows for the host dashboard (/system/df or `system df`). */
    suspend fun diskUsage(): DockerResult<DockerDiskUsage>

    // ── Compose (always SSH exec — compose has no REST API) ─────────────────

    suspend fun composeUp(stackDir: String): DockerResult<String>

    suspend fun composeDown(stackDir: String): DockerResult<String>

    suspend fun composePull(stackDir: String): DockerResult<String>

    suspend fun composeRestart(stackDir: String): DockerResult<String>

    /** `compose ps --format json` raw output for the stack status view. */
    suspend fun composePs(stackDir: String): DockerResult<String>

    /**
     * Follow `compose logs` for a Room-tracked stack directory. [service]
     * scopes the stream to one service; null aggregates every service.
     */
    fun composeLogs(stackDir: String, service: String? = null, tail: Int = 200): Flow<String>

    /**
     * Discover every compose project on the host, tracked or not
     * (`docker compose ls --all --format json`). Hosts without compose
     * installed surface as [DockerResult.TransportUnavailable] — callers
     * treat that as "no external stacks" rather than a hard failure.
     */
    suspend fun composeLs(): DockerResult<List<ComposeLsEntry>>

    /**
     * Compose lifecycle actions for a project discovered by [composeLs] that
     * has no Room row — addressed by `-f <configFile> -p <name>` rather than
     * a `cd` into a stack directory, matching how [composeUp] et al. build
     * their commands for tracked stacks.
     */
    suspend fun composeUpByProject(name: String, configFile: String): DockerResult<String>

    suspend fun composeDownByProject(name: String, configFile: String): DockerResult<String>

    suspend fun composePullByProject(name: String, configFile: String): DockerResult<String>

    suspend fun composeRestartByProject(name: String, configFile: String): DockerResult<String>

    suspend fun composePsByProject(name: String, configFile: String): DockerResult<String>

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
     * instance; a host-level pin in DockerHost.composeInvocation short-circuits
     * the probe.
     */
    suspend fun detectComposeInvocation(): DockerResult<ComposeInvocation>

    // ── Remote files (always SSH exec) ───────────────────────────────────────

    /** Read a remote text file (base64-safe transfer, no quoting corruption). */
    suspend fun readRemoteFile(path: String): DockerResult<String>

    /**
     * Write [content] to a remote file. The parent directory is created with
     * `mkdir -p` first; content travels base64-encoded so no shell quoting or
     * heredoc corruption is possible.
     */
    suspend fun writeRemoteFile(path: String, content: String): DockerResult<Unit>

    /** List a remote directory (entries with a directory flag). */
    suspend fun listRemoteDir(path: String): DockerResult<List<RemoteDirEntry>>

    /**
     * Expand `$USER`/`$HOME` in [path] using the REMOTE shell's values.
     * Values are fetched from the remote once and substituted locally, so the
     * path itself is never evaluated by the remote shell (no injection).
     */
    suspend fun expandRemotePath(path: String): DockerResult<String>

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Release transport resources (relay sockets, bridge processes). */
    suspend fun close()
}

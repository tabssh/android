package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST transport for Incus and LXC/LXD over `http://127.0.0.1:{relayPort}`,
 * where [relayPort] is the local end of a [SocketRelay] to the host's daemon
 * socket — the same relay Docker's [EngineApiTransport] uses, because the
 * ladder above the dialect seam is engine-independent.
 *
 * Two things separate this dialect from Docker's:
 *  - every answer arrives inside a `{"type": …, "metadata": …}` envelope, so
 *    reads unwrap before parsing ([IncusApiParsers]);
 *  - every mutation is asynchronous: the daemon answers immediately with an
 *    operation path and performs the work in the background, so a mutation is
 *    only complete once [awaitOperation] has seen that operation finish.
 *
 * Remote-file operations (and the compose refusal these engines return) run
 * over SSH exec through the shared [RemoteExecOps], exactly as they do on the
 * Docker REST tier.
 */
class IncusApiTransport(
    private val host: ContainerHost,
    private val relay: SocketRelay,
    private val runner: SshExecRunner,
    resolver: EngineSocketResolver = EngineSocketResolver(host, runner)
) : ContainerTransport {

    private companion object {
        private const val TAG = "IncusApiTransport"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L

        /** Cap on a single non-streaming response body held in memory (8 MiB). */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** Root of the only API version these engines have ever published. */
        private const val API_ROOT = "/1.0"

        /**
         * Seconds each `/wait` blocks server-side. Short enough that a
         * cancelled coroutine stops waiting promptly, long enough that a
         * normal operation finishes inside a single round trip.
         */
        private const val WAIT_SECONDS = 10

        /** Overall ceiling on one operation, across repeated `/wait` calls. */
        private const val OPERATION_TIMEOUT_MS = 300_000L

        /** Interval between polls of a stats or console stream. */
        private const val POLL_INTERVAL_MS = 2_000L

        /** Interval between polls of an image-pull operation's progress. */
        private const val PULL_POLL_INTERVAL_MS = 1_000L

        /** Volume type these engines use for user-created storage volumes. */
        private const val VOLUME_TYPE_CUSTOM = "custom"

        /** Default network type when the caller names none. */
        private const val DEFAULT_NETWORK_TYPE = "bridge"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }

    private val engine: ContainerEngine = host.engineType()

    private val gate = EngineCapabilityGate(engine)

    private val remoteOps = RemoteExecOps(runner, host, EngineCliContext(host, resolver))

    /**
     * Transport-local project scope. These engines have no server-side
     * "current project" for an API client — the CLI keeps it in its own
     * config — so every scoped request carries it as a query parameter.
     */
    @Volatile
    private var project: String? = null

    override val activeProject: String?
        get() = project

    /**
     * Client for one-shot requests. Connection reuse is disabled for the same
     * reason as on the Docker tier: a pooled connection handed back over the
     * SSH relay hangs until the read timeout, while a fresh connection (its
     * own SSH channel to the daemon socket) always works.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .socketFactory(RelayTokenSocketFactory(relay.token))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("Connection", "close").build()
            )
        }
        // A `/wait` call blocks server-side for WAIT_SECONDS; the read timeout
        // above already covers that with room to spare.
        .build()

    private fun baseUrl(): String {
        val port = relay.localPort
            ?: throw TransportUnavailableException(
                ContainerTransportMessages.allTiersFailed(engine),
                detail = "socket relay is not open"
            )
        return "http://127.0.0.1:$port"
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    /** Read a non-streaming response body, capped at [MAX_BODY_BYTES]. */
    private fun readBoundedBody(response: Response): String {
        val bytes = response.peekBody(MAX_BODY_BYTES).bytes()
        if (bytes.size.toLong() >= MAX_BODY_BYTES) {
            Logger.w(TAG, "response body reached the $MAX_BODY_BYTES byte cap; truncated")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Map an HTTP failure response to the matching ContainerResult. */
    private fun classifyHttp(context: String, response: Response): ContainerResult<Nothing> {
        val body = try {
            readBoundedBody(response)
        } catch (_: Exception) {
            ""
        }
        val detail = (IncusApiParsers.parseError(body)?.message ?: body).trim().take(500)
        return statusToResult(context, response.code, detail)
    }

    /** Shared HTTP/operation status-code taxonomy. */
    private fun statusToResult(context: String, code: Int, detail: String): ContainerResult<Nothing> =
        when (code) {
            404 -> ContainerResult.NotFound(context, detail)
            401, 403 -> ContainerResult.PermissionDenied(
                ContainerTransportMessages.socketPermission(engine), detail
            )
            else -> ContainerResult.Error("$context (HTTP $code)", detail)
        }

    /** Run one request, mapping transport errors and HTTP failures. */
    private suspend fun <T> call(
        context: String,
        build: (String) -> Request,
        parse: (String) -> T
    ): ContainerResult<T> = withContext(Dispatchers.IO) {
        try {
            val request = build(baseUrl())
            client.newCall(request).execute().use { response ->
                val body = readBoundedBody(response)
                if (!response.isSuccessful) {
                    classifyHttp(context, response)
                } else {
                    // A 200 can still carry an `error` envelope on these
                    // engines, so the body decides, not the status line.
                    val error = IncusApiParsers.parseError(body)
                    if (error != null) {
                        statusToResult(context, error.code, error.message)
                    } else {
                        ContainerResult.Success(parse(body))
                    }
                }
            }
        } catch (e: TransportUnavailableException) {
            ContainerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: java.io.IOException) {
            ContainerResult.TransportUnavailable(context, e.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ContainerResult.Error(context, e.message)
        }
    }

    private suspend fun <T> get(context: String, path: String, parse: (String) -> T): ContainerResult<T> =
        call(context, { base -> Request.Builder().url("$base$path").get().build() }, parse)

    /**
     * Append the active project to [path] so every listing and every mutation
     * stays inside the selected scope.
     */
    private fun scoped(path: String): String {
        val name = project ?: return path
        val separator = if (path.contains('?')) "&" else "?"
        return "$path${separator}project=${urlEncode(name)}"
    }

    /** Issue a mutating request and wait for the operation it queues. */
    private suspend fun mutate(
        context: String,
        path: String,
        method: String,
        body: JSONObject? = null
    ): ContainerResult<Unit> {
        val requested = call(
            context,
            { base ->
                val builder = Request.Builder().url("$base$path")
                val payload = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
                when (method) {
                    "POST" -> builder.post(payload ?: EMPTY_BODY)
                    "PUT" -> builder.put(payload ?: EMPTY_BODY)
                    "PATCH" -> builder.patch(payload ?: EMPTY_BODY)
                    else -> if (payload != null) builder.delete(payload) else builder.delete()
                }
                builder.build()
            },
            { it }
        )
        return when (requested) {
            is ContainerResult.Success -> awaitOperation(context, requested.value)
            is ContainerResult.PermissionDenied -> requested
            is ContainerResult.NotFound -> requested
            is ContainerResult.EngineNotInstalled -> requested
            is ContainerResult.TransportUnavailable -> requested
            is ContainerResult.Error -> requested
        }
    }

    /**
     * Block until the operation queued by [body] reaches a terminal state.
     *
     * A sync answer means the daemon already did the work, so there is
     * nothing to wait for. Otherwise the operation path is polled through
     * `GET <operation>/wait?timeout=`, which blocks server-side for
     * [WAIT_SECONDS] and returns the operation as it stands — still Running
     * if it has not finished, which loops until [OPERATION_TIMEOUT_MS].
     * Bounding the server-side block keeps a cancelled coroutine from sitting
     * on a socket for the whole operation.
     */
    private suspend fun awaitOperation(context: String, body: String): ContainerResult<Unit> {
        val operation = IncusApiParsers.operationPath(body) ?: return ContainerResult.Success(Unit)
        val deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val waited = get(context, "$operation/wait?timeout=$WAIT_SECONDS") { it }
            val outcome = when (waited) {
                is ContainerResult.Success -> IncusApiParsers.parseOperation(waited.value)
                is ContainerResult.PermissionDenied -> return waited
                is ContainerResult.NotFound -> return waited
                is ContainerResult.EngineNotInstalled -> return waited
                is ContainerResult.TransportUnavailable -> return waited
                is ContainerResult.Error -> return waited
            }
            if (!outcome.done) continue
            return if (outcome.success) {
                ContainerResult.Success(Unit)
            } else {
                statusToResult(context, outcome.statusCode, outcome.error)
            }
        }
        return ContainerResult.Error(context, "the operation did not finish in time")
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** The `metadata` half of a sync answer, as pretty JSON for inspect views. */
    private fun metadataText(body: String): String =
        IncusApiParsers.syncObject(body)?.toString(2) ?: body.trim()

    // ── Instances ────────────────────────────────────────────────────────────

    override suspend fun listContainers(all: Boolean): ContainerResult<List<ContainerSummary>> =
        get("Failed to list instances", scoped("$API_ROOT/instances?recursion=1")) {
            val instances = IncusApiParsers.parseInstanceList(it)
            // These engines list every instance regardless of state, so the
            // "running only" view is filtered here rather than server-side.
            if (all) instances else instances.filter { row -> row.state == "running" }
        }

    override suspend fun inspectContainer(id: String): ContainerResult<String> =
        get("Failed to inspect instance", scoped("$API_ROOT/instances/${urlEncode(id)}")) {
            metadataText(it)
        }

    override suspend fun containerAction(id: String, action: ContainerAction): ContainerResult<Unit> {
        val body = JSONObject()
            .put("action", stateAction(action))
            .put("timeout", -1)
            .put("force", action == ContainerAction.KILL)
            .put("stateful", false)
        return mutate(
            "Failed to ${action.verb} instance",
            scoped("$API_ROOT/instances/${urlEncode(id)}/state"),
            "PUT",
            body
        )
    }

    /**
     * These engines express pause/unpause as freeze/thaw and have no separate
     * kill verb — a forced stop is the kill.
     */
    private fun stateAction(action: ContainerAction): String = when (action) {
        ContainerAction.START -> "start"
        ContainerAction.STOP -> "stop"
        ContainerAction.RESTART -> "restart"
        ContainerAction.PAUSE -> "freeze"
        ContainerAction.UNPAUSE -> "unfreeze"
        ContainerAction.KILL -> "stop"
    }

    override suspend fun renameContainer(id: String, newName: String): ContainerResult<Unit> =
        mutate(
            "Failed to rename instance",
            scoped("$API_ROOT/instances/${urlEncode(id)}"),
            "POST",
            JSONObject().put("name", newName)
        )

    override suspend fun removeContainer(id: String, force: Boolean): ContainerResult<Unit> {
        // Unlike Docker, delete has no force flag: a running instance must be
        // stopped first, and "already stopped" is not an error worth failing
        // the delete over.
        if (force) containerAction(id, ContainerAction.KILL)
        return mutate(
            "Failed to remove instance",
            scoped("$API_ROOT/instances/${urlEncode(id)}"),
            "DELETE"
        )
    }

    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): ContainerResult<Unit> {
        // The recreate plan is written in the Docker Engine API's vocabulary;
        // the image reference is the part that carries over, and these engines
        // create an instance from an image alias. Everything else in the plan
        // (Docker HostConfig, port bindings) has no counterpart here.
        val image = createBody.optString("Image")
        if (image.isEmpty()) {
            return ContainerResult.Error(
                "Failed to create instance $name",
                "the container config names no image"
            )
        }
        val body = JSONObject()
            .put("name", name)
            .put(
                "source",
                JSONObject()
                    .put("type", "image")
                    .put("alias", image)
            )
        val created = mutate(
            "Failed to create instance $name",
            scoped("$API_ROOT/instances"),
            "POST",
            body
        )
        return when (created) {
            is ContainerResult.Success -> containerAction(name, ContainerAction.START)
            else -> created
        }
    }

    /**
     * Follow an instance's console ring buffer.
     *
     * These engines expose no follow endpoint over REST — the live console is
     * a websocket attach, which is a terminal session rather than a log feed.
     * The console log is therefore polled and only the lines that appeared
     * since the previous poll are emitted, the same documented degradation the
     * CLI tier already applies to stats.
     */
    override fun streamLogs(id: String, tail: Int?): Flow<String> = flow {
        var emitted = 0
        var first = true
        while (true) {
            val console = get(
                "Failed to read instance log",
                scoped("$API_ROOT/instances/${urlEncode(id)}/console")
            ) { it }
            if (console is ContainerResult.Success) {
                val lines = console.value.lines().dropLastWhile { it.isEmpty() }
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

    /**
     * Poll the instance state and convert consecutive readings into rates.
     * The state document carries cumulative counters, so the first sample
     * establishes the baseline and every later one reports the delta.
     */
    override fun streamStats(id: String): Flow<ContainerStats> = flow {
        val memLimit = instanceMemoryLimit(id)
        var previous: IncusApiParsers.InstanceStateSample? = null
        var previousAt = 0L
        while (true) {
            val state = get(
                "Failed to read instance state",
                scoped("$API_ROOT/instances/${urlEncode(id)}/state")
            ) { IncusApiParsers.parseInstanceState(it) }
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

    /** The instance's configured memory ceiling, or 0 when it has none. */
    private suspend fun instanceMemoryLimit(id: String): Long {
        val config = get(
            "Failed to read instance config",
            scoped("$API_ROOT/instances/${urlEncode(id)}")
        ) { body ->
            IncusApiParsers.parseStringMap(IncusApiParsers.syncObject(body)?.optJSONObject("config"))
        }
        return IncusApiParsers.parseMemoryLimit(config.valueOrNull() ?: emptyMap())
    }

    // ── Images ───────────────────────────────────────────────────────────────

    override suspend fun listImages(): ContainerResult<List<ContainerImageSummary>> =
        get("Failed to list images", scoped("$API_ROOT/images?recursion=1")) {
            IncusApiParsers.parseImageList(it)
        }

    override suspend fun inspectImage(ref: String): ContainerResult<String> =
        get("Failed to inspect image", scoped("$API_ROOT/images/${urlEncode(ref)}")) {
            metadataText(it)
        }

    /**
     * Copy a remote image into the local store, reporting the daemon's own
     * download progress. The pull is one asynchronous operation whose metadata
     * grows a `download_progress` field, so the operation is polled rather
     * than waited on — waiting would hide the progress the user came for.
     */
    override fun pullImage(ref: String): Flow<PullProgressEvent> = flow {
        val (server, alias) = splitImageRef(ref)
        val body = JSONObject().put(
            "source",
            JSONObject()
                .put("type", "image")
                .put("mode", "pull")
                .put("server", server)
                .put("protocol", "simplestreams")
                .put("alias", alias)
        )
        val requested = call(
            "Failed to pull image",
            { base ->
                Request.Builder()
                    .url("$base${scoped("$API_ROOT/images")}")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
            { it }
        )
        if (requested !is ContainerResult.Success) {
            emit(PullProgressEvent(status = "", error = failureDetail(requested)))
            return@flow
        }
        val operation = IncusApiParsers.operationPath(requested.value)
        if (operation == null) {
            emit(PullProgressEvent(status = ref))
            return@flow
        }
        val deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS
        var lastProgress: String? = null
        while (System.currentTimeMillis() < deadline) {
            val polled = get("Failed to pull image", operation) { it }
            if (polled !is ContainerResult.Success) {
                emit(PullProgressEvent(status = "", error = failureDetail(polled)))
                return@flow
            }
            IncusApiParsers.parseDownloadProgress(polled.value)?.let { progress ->
                if (progress != lastProgress) {
                    lastProgress = progress
                    emit(PullProgressEvent(status = progress))
                }
            }
            val outcome = IncusApiParsers.parseOperation(polled.value)
            if (outcome.done) {
                if (!outcome.success) {
                    emit(PullProgressEvent(status = "", error = outcome.error))
                }
                return@flow
            }
            delay(PULL_POLL_INTERVAL_MS)
        }
        emit(PullProgressEvent(status = "", error = "the image pull did not finish in time"))
    }.flowOn(Dispatchers.IO)

    /**
     * Split "images:debian/12" into its remote and its alias. A reference with
     * no remote comes from the engine's default image server.
     */
    private fun splitImageRef(ref: String): Pair<String, String> {
        val remote = ref.substringBefore(':', "")
        val alias = ref.substringAfter(':', ref)
        return imageServerUrl(remote) to alias
    }

    /**
     * Image-server URL for a remote name. Only the community servers the two
     * engines ship as defaults are known here; anything else is passed through
     * as an explicit URL, which is how a user names a private server.
     */
    private fun imageServerUrl(remote: String): String = when {
        remote.startsWith("http://") || remote.startsWith("https://") -> remote
        remote == "ubuntu" -> "https://cloud-images.ubuntu.com/releases"
        remote == "ubuntu-daily" -> "https://cloud-images.ubuntu.com/daily"
        engine == ContainerEngine.LXD -> "https://images.lxd.canonical.com"
        else -> "https://images.linuxcontainers.org"
    }

    /** The human detail of a failed result, for a Flow that cannot return one. */
    private fun failureDetail(result: ContainerResult<*>): String = when (result) {
        is ContainerResult.Success -> ""
        is ContainerResult.PermissionDenied -> result.detail ?: result.message
        is ContainerResult.NotFound -> result.detail ?: result.message
        is ContainerResult.EngineNotInstalled -> result.detail ?: result.message
        is ContainerResult.TransportUnavailable -> result.detail ?: result.message
        is ContainerResult.Error -> result.detail ?: result.message
    }

    override suspend fun removeImage(ref: String, force: Boolean): ContainerResult<Unit> =
        mutate("Failed to remove image", scoped("$API_ROOT/images/${urlEncode(ref)}"), "DELETE")

    override suspend fun pruneImages(): ContainerResult<Unit> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    // ── Volumes ──────────────────────────────────────────────────────────────

    override suspend fun listVolumes(): ContainerResult<List<ContainerVolumeSummary>> {
        val pools = storagePools()
        if (pools !is ContainerResult.Success) return pools.map { emptyList<ContainerVolumeSummary>() }
        val all = mutableListOf<ContainerVolumeSummary>()
        for ((pool, driver) in pools.value) {
            val volumes = get(
                "Failed to list storage volumes",
                scoped("$API_ROOT/storage-pools/${urlEncode(pool)}/volumes?recursion=1")
            ) { IncusApiParsers.parseVolumeList(it, pool, driver) }
            when (volumes) {
                is ContainerResult.Success -> all += volumes.value
                // A pool that is pending or unreachable on this cluster member
                // must not blank the volumes of every healthy pool.
                else -> Logger.w(TAG, "listVolumes: skipped pool (${failureDetail(volumes)})")
            }
        }
        return ContainerResult.Success(all)
    }

    private suspend fun storagePools(): ContainerResult<Map<String, String>> =
        get("Failed to list storage pools", scoped("$API_ROOT/storage-pools?recursion=1")) {
            IncusApiParsers.parseStoragePools(it)
        }

    override suspend fun inspectVolume(name: String): ContainerResult<String> {
        val (pool, volume) = splitVolumeRef(name)
            ?: return ContainerResult.NotFound("Failed to inspect storage volume", name)
        return get(
            "Failed to inspect storage volume",
            scoped("$API_ROOT/storage-pools/${urlEncode(pool)}/volumes/$VOLUME_TYPE_CUSTOM/${urlEncode(volume)}")
        ) { metadataText(it) }
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
        return mutate(
            "Failed to create storage volume",
            scoped("$API_ROOT/storage-pools/${urlEncode(pool)}/volumes"),
            "POST",
            JSONObject().put("name", volume).put("type", VOLUME_TYPE_CUSTOM)
        )
    }

    override suspend fun removeVolume(name: String, force: Boolean): ContainerResult<Unit> {
        val (pool, volume) = splitVolumeRef(name)
            ?: return ContainerResult.NotFound("Failed to remove storage volume", name)
        return mutate(
            "Failed to remove storage volume",
            scoped("$API_ROOT/storage-pools/${urlEncode(pool)}/volumes/$VOLUME_TYPE_CUSTOM/${urlEncode(volume)}"),
            "DELETE"
        )
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
        get("Failed to list networks", scoped("$API_ROOT/networks?recursion=1")) {
            IncusApiParsers.parseNetworkList(it)
        }

    override suspend fun inspectNetwork(id: String): ContainerResult<String> =
        get("Failed to inspect network", scoped("$API_ROOT/networks/${urlEncode(id)}")) {
            metadataText(it)
        }

    override suspend fun createNetwork(name: String, driver: String?): ContainerResult<Unit> =
        mutate(
            "Failed to create network",
            scoped("$API_ROOT/networks"),
            "POST",
            JSONObject()
                .put("name", name)
                .put("type", driver?.takeIf { it.isNotBlank() } ?: DEFAULT_NETWORK_TYPE)
        )

    override suspend fun removeNetwork(id: String): ContainerResult<Unit> =
        mutate("Failed to remove network", scoped("$API_ROOT/networks/${urlEncode(id)}"), "DELETE")

    override suspend fun pruneNetworks(): ContainerResult<Unit> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    // ── Snapshots ────────────────────────────────────────────────────────────

    override suspend fun listSnapshots(instance: String): ContainerResult<List<ContainerSnapshotSummary>> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: get(
            "Failed to list snapshots",
            scoped("$API_ROOT/instances/${urlEncode(instance)}/snapshots?recursion=1")
        ) { IncusApiParsers.parseSnapshotList(it, instance) }

    override suspend fun createSnapshot(
        instance: String,
        name: String,
        stateful: Boolean
    ): ContainerResult<Unit> = gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
        "Failed to create snapshot",
        scoped("$API_ROOT/instances/${urlEncode(instance)}/snapshots"),
        "POST",
        JSONObject().put("name", name).put("stateful", stateful)
    )

    override suspend fun restoreSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
            "Failed to restore snapshot",
            scoped("$API_ROOT/instances/${urlEncode(instance)}"),
            "PUT",
            JSONObject().put("restore", name)
        )

    override suspend fun removeSnapshot(instance: String, name: String): ContainerResult<Unit> =
        gate.reject(EngineCapability.SNAPSHOTS) ?: mutate(
            "Failed to remove snapshot",
            scoped("$API_ROOT/instances/${urlEncode(instance)}/snapshots/${urlEncode(name)}"),
            "DELETE"
        )

    // ── Profiles ─────────────────────────────────────────────────────────────

    override suspend fun listProfiles(): ContainerResult<List<ContainerProfileSummary>> =
        gate.reject(EngineCapability.PROFILES) ?: get(
            "Failed to list profiles",
            scoped("$API_ROOT/profiles?recursion=1")
        ) { IncusApiParsers.parseProfileList(it) }

    override suspend fun inspectProfile(name: String): ContainerResult<String> =
        gate.reject(EngineCapability.PROFILES) ?: get(
            "Failed to inspect profile",
            scoped("$API_ROOT/profiles/${urlEncode(name)}")
        ) { metadataText(it) }

    // ── Projects ─────────────────────────────────────────────────────────────

    override suspend fun listProjects(): ContainerResult<List<ContainerProjectSummary>> =
        gate.reject(EngineCapability.PROJECTS) ?: get(
            "Failed to list projects",
            "$API_ROOT/projects?recursion=1"
        ) { IncusApiParsers.parseProjectList(it, project) }

    override suspend fun inspectProject(name: String): ContainerResult<String> =
        gate.reject(EngineCapability.PROJECTS) ?: get(
            "Failed to inspect project",
            "$API_ROOT/projects/${urlEncode(name)}"
        ) { metadataText(it) }

    override suspend fun selectProject(name: String): ContainerResult<Unit> {
        gate.reject(EngineCapability.PROJECTS)?.let { return it }
        // Confirm the project exists before every later request starts
        // carrying it, so a typo fails here instead of on each tab.
        val verified = get("Failed to select project", "$API_ROOT/projects/${urlEncode(name)}") { }
        if (verified is ContainerResult.Success) project = name
        return verified
    }

    // ── Engine ───────────────────────────────────────────────────────────────

    override suspend fun engineInfo(): ContainerResult<ContainerEngineInfo> {
        val info = get("Failed to read engine info", API_ROOT) { IncusApiParsers.parseServerInfo(it) }
            .flatMapNotNull("Engine info was unparsable")
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
        get("Failed to read engine version", API_ROOT) { IncusApiParsers.parseServerVersion(it) }
            .flatMapNotNull("Engine version was unparsable")

    override suspend fun diskUsage(): ContainerResult<ContainerDiskUsage> =
        gate.refusal(EngineCapability.DISK_USAGE).asResult()

    // ── Compose + remote files (SSH exec via shared RemoteExecOps) ───────────

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
        relay.close()
        // Backstop for the polling flows whose collectors were not cancelled —
        // shutdown() only blocks new tasks and evictAll() only drops idle
        // connections, so cancel every in-flight call explicitly.
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

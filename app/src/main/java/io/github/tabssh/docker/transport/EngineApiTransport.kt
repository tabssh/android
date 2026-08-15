package io.github.tabssh.docker.transport

import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

/**
 * Primary transport: Docker Engine REST API over `http://127.0.0.1:{relayPort}`
 * where [relayPort] is the local end of a [SocketRelay] (streamlocal channel
 * or dial-stdio relay) to the host's docker.sock.
 *
 * API version negotiation: one
 * unversioned `GET /version`, negotiated = min(client ceiling
 * [DockerApiParsers.CLIENT_MAX_API_VERSION], server ApiVersion), cached for
 * the transport's lifetime; DockerHost.pinnedApiVersion overrides.
 *
 * Compose and remote-file operations still run over SSH exec (compose has no
 * REST API) via the shared [RemoteExecOps].
 */
class EngineApiTransport(
    private val host: DockerHost,
    private val relay: SocketRelay,
    private val runner: SshExecRunner
) : DockerTransport {

    private companion object {
        private const val TAG = "EngineApiTransport"
        private const val CONNECT_TIMEOUT_S = 15L
        private const val READ_TIMEOUT_S = 60L

        /** Cap on a single non-streaming response body held in memory (8 MiB). */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }

    private val remoteOps = RemoteExecOps(runner, host)

    /**
     * Client for one-shot requests — bounded read timeout, no connection
     * reuse. Every pooled-connection reuse against the SSH socket relay
     * hangs until the read timeout (fresh connections always work — each
     * gets its own SSH channel to the daemon socket), so keep-alive is
     * disabled: `Connection: close` makes both sides tear the connection
     * down after each exchange, and the zero-idle pool stops OkHttp from
     * ever handing a used connection to a later request.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        // Authenticates every connection to relay.localPort against the
        // relay's per-session token preamble (SocketRelay.authenticateClient).
        .socketFactory(RelayTokenSocketFactory(relay.token))
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder().header("Connection", "close").build()
            )
        }
        .build()

    /** Client for follow/stream endpoints — no read timeout. */
    private val streamingClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Cached negotiated API version ("1.43" style). */
    @Volatile
    private var negotiatedVersion: String? =
        host.pinnedApiVersion?.takeIf { it.isNotBlank() }

    private fun baseUrl(): String {
        val port = relay.localPort
            ?: throw TransportUnavailableException(DockerTransportMessages.ALL_TIERS_FAILED,
                detail = "socket relay is not open")
        return "http://127.0.0.1:$port"
    }

    /**
     * The negotiated version prefix ("/v1.43"), performing the one-time
     * unversioned `GET /version` negotiation on first use.
     */
    private suspend fun versionPrefix(): String {
        negotiatedVersion?.let { return "/v$it" }
        val info = fetchVersionUnversioned()
        val negotiated = DockerApiParsers.negotiateApiVersion(
            DockerApiParsers.CLIENT_MAX_API_VERSION,
            info?.apiVersion,
            info?.minApiVersion
        )
        negotiatedVersion = negotiated
        Logger.i(TAG, "negotiated Engine API version $negotiated " +
            "(server=${info?.apiVersion}, min=${info?.minApiVersion})")
        return "/v$negotiated"
    }

    /** Unversioned `GET /version` used for negotiation and tier probing. */
    private suspend fun fetchVersionUnversioned(): DockerVersionInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl()}/version").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            DockerApiParsers.parseVersion(readBoundedBody(response))
        }
    }

    // ── HTTP plumbing ────────────────────────────────────────────────────────

    /**
     * Read a non-streaming response body, capped at [MAX_BODY_BYTES].
     *
     * The daemon sits behind an SSH relay on a remote host, so its answers are
     * remote input: an unbounded `string()` would let one oversized `inspect`
     * or `logs` reply exhaust the app's heap.
     */
    private fun readBoundedBody(response: Response): String {
        val bytes = response.peekBody(MAX_BODY_BYTES).bytes()
        if (bytes.size.toLong() >= MAX_BODY_BYTES) {
            Logger.w(TAG, "response body reached the $MAX_BODY_BYTES byte cap; truncated")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    /** Map an HTTP failure response to the matching DockerResult. */
    private fun classifyHttp(context: String, response: Response): DockerResult<Nothing> {
        val body = try {
            readBoundedBody(response)
        } catch (_: Exception) {
            ""
        }
        val detail = try {
            JSONObject(body).optString("message").ifEmpty { body }
        } catch (_: Exception) {
            body
        }.trim().take(500)
        return when (response.code) {
            404 -> DockerResult.NotFound(context, detail)
            401, 403 -> DockerResult.PermissionDenied(
                DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION, detail)
            else -> DockerResult.Error("$context (HTTP ${response.code})", detail)
        }
    }

    /** Run one request, mapping transport errors and HTTP failures. */
    private suspend fun <T> call(
        context: String,
        build: (String) -> Request,
        parse: (String) -> T
    ): DockerResult<T> = withContext(Dispatchers.IO) {
        try {
            val prefix = versionPrefix()
            val request = build("${baseUrl()}$prefix")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    classifyHttp(context, response)
                } else {
                    DockerResult.Success(parse(readBoundedBody(response)))
                }
            }
        } catch (e: TransportUnavailableException) {
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: java.io.IOException) {
            DockerResult.TransportUnavailable(context, e.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error(context, e.message)
        }
    }

    private suspend fun <T> get(context: String, path: String, parse: (String) -> T): DockerResult<T> =
        call(context, { base -> Request.Builder().url("$base$path").get().build() }, parse)

    private suspend fun post(context: String, path: String): DockerResult<Unit> =
        call(context, { base -> Request.Builder().url("$base$path").post(EMPTY_BODY).build() }, { })

    private suspend fun postJson(context: String, path: String, body: JSONObject): DockerResult<Unit> =
        call(
            context,
            { base ->
                Request.Builder()
                    .url("$base$path")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
            { }
        )

    private suspend fun delete(context: String, path: String): DockerResult<Unit> =
        call(context, { base -> Request.Builder().url("$base$path").delete().build() }, { })

    /**
     * Open a streaming GET/POST and emit lines produced by [consume] until
     * the collector cancels; cancellation closes the response, which unblocks
     * the reader thread.
     */
    private fun <T> streamingFlow(
        path: String,
        post: Boolean = false,
        consume: (Response, kotlinx.coroutines.channels.SendChannel<T>) -> Unit
    ): Flow<T> = channelFlow {
        val prefix = versionPrefix()
        val builder = Request.Builder().url("${baseUrl()}$prefix$path")
        if (post) builder.post(EMPTY_BODY) else builder.get()
        val call = streamingClient.newCall(builder.build())
        // Collector cancellation closes the channel; cancelling the call
        // closes the socket, which unblocks the blocking body reader below.
        channel.invokeOnClose { call.cancel() }
        // execute() itself can fail (relay down, daemon gone); that is a closed
        // stream for the collector, not an exception thrown out of the Flow.
        val response = try {
            call.execute()
        } catch (e: java.io.IOException) {
            Logger.d(TAG, "stream failed to open: ${e.message}")
            return@channelFlow
        }
        try {
            if (!response.isSuccessful) {
                return@channelFlow
            }
            consume(response, channel)
        } catch (e: java.io.IOException) {
            // Normal teardown path when the collector cancels mid-read.
            Logger.d(TAG, "stream ended: ${e.message}")
        } finally {
            response.close()
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    // ── Containers ───────────────────────────────────────────────────────────

    override suspend fun listContainers(all: Boolean): DockerResult<List<DockerContainerSummary>> =
        get("Failed to list containers", "/containers/json?all=${if (all) 1 else 0}") {
            DockerApiParsers.parseContainerList(it)
        }

    override suspend fun inspectContainer(id: String): DockerResult<String> =
        get("Failed to inspect container", "/containers/${urlEncode(id)}/json") { it }

    override suspend fun containerAction(id: String, action: ContainerAction): DockerResult<Unit> =
        post("Failed to ${action.verb} container", "/containers/${urlEncode(id)}/${action.verb}")

    override suspend fun renameContainer(id: String, newName: String): DockerResult<Unit> =
        post("Failed to rename container",
            "/containers/${urlEncode(id)}/rename?name=${urlEncode(newName)}")

    override suspend fun removeContainer(id: String, force: Boolean): DockerResult<Unit> =
        delete("Failed to remove container",
            "/containers/${urlEncode(id)}?force=${if (force) 1 else 0}")

    override suspend fun createAndStartContainer(
        name: String,
        createBody: JSONObject,
        runArgv: List<String>
    ): DockerResult<Unit> {
        // POST the recreate-plan body verbatim; the response carries the Id.
        val created = call(
            "Failed to create container $name",
            { base ->
                Request.Builder()
                    .url("$base/containers/create?name=${urlEncode(name)}")
                    .post(createBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
            { body -> JSONObject(body).optString("Id") }
        )
        val id = when (created) {
            is DockerResult.Success -> created.value
            is DockerResult.PermissionDenied -> return created
            is DockerResult.NotFound -> return created
            is DockerResult.TransportUnavailable -> return created
            is DockerResult.Error -> return created
        }
        if (id.isEmpty()) {
            return DockerResult.Error("Failed to create container $name", "no Id in create response")
        }
        return post("Failed to start container $name", "/containers/${urlEncode(id)}/start")
    }

    override fun streamLogs(id: String, tail: Int?): Flow<String> {
        val tailArg = tail?.toString() ?: "all"
        val path = "/containers/${urlEncode(id)}/logs?follow=1&stdout=1&stderr=1&tail=$tailArg"
        return streamingFlow(path) { response, channel ->
            val stream = response.body?.byteStream() ?: return@streamingFlow
            // Chunked multiplexed stream → demuxed text lines.
            DockerApiParsers.decodeLogStream(stream) { line ->
                channel.trySend(line)
            }
        }
    }

    override fun streamStats(id: String): Flow<DockerContainerStats> {
        val path = "/containers/${urlEncode(id)}/stats?stream=1"
        return streamingFlow(path) { response, channel ->
            val reader = response.body?.charStream()?.let { BufferedReader(it) } ?: return@streamingFlow
            var line = DockerApiParsers.readBoundedLine(reader)
            while (line != null) {
                DockerApiParsers.parseApiStats(line)?.let { channel.trySend(it) }
                line = DockerApiParsers.readBoundedLine(reader)
            }
        }
    }

    // ── Images ───────────────────────────────────────────────────────────────

    override suspend fun listImages(): DockerResult<List<DockerImageSummary>> =
        get("Failed to list images", "/images/json") { DockerApiParsers.parseImageList(it) }

    override suspend fun inspectImage(ref: String): DockerResult<String> =
        get("Failed to inspect image", "/images/${urlEncode(ref)}/json") { it }

    override fun pullImage(ref: String): Flow<PullProgressEvent> {
        val (image, tag) = DockerApiParsers.splitImageRef(ref)
        val path = "/images/create?fromImage=${urlEncode(image)}&tag=${urlEncode(tag)}"
        return streamingFlow(path, post = true) { response, channel ->
            val reader = response.body?.charStream()?.let { BufferedReader(it) } ?: return@streamingFlow
            // NDJSON progress stream — one JSON object per line.
            var line = DockerApiParsers.readBoundedLine(reader)
            while (line != null) {
                DockerApiParsers.parsePullProgressLine(line)?.let { channel.trySend(it) }
                line = DockerApiParsers.readBoundedLine(reader)
            }
        }
    }

    override suspend fun removeImage(ref: String, force: Boolean): DockerResult<Unit> =
        delete("Failed to remove image", "/images/${urlEncode(ref)}?force=${if (force) 1 else 0}")

    override suspend fun pruneImages(): DockerResult<Unit> =
        post("Failed to prune images", "/images/prune")

    // ── Volumes ──────────────────────────────────────────────────────────────

    override suspend fun listVolumes(): DockerResult<List<DockerVolumeSummary>> =
        get("Failed to list volumes", "/volumes") { DockerApiParsers.parseVolumeList(it) }

    override suspend fun inspectVolume(name: String): DockerResult<String> =
        get("Failed to inspect volume", "/volumes/${urlEncode(name)}") { it }

    override suspend fun createVolume(name: String, driver: String?): DockerResult<Unit> {
        val body = JSONObject().put("Name", name)
        if (!driver.isNullOrBlank()) body.put("Driver", driver)
        return postJson("Failed to create volume", "/volumes/create", body)
    }

    override suspend fun removeVolume(name: String, force: Boolean): DockerResult<Unit> =
        delete("Failed to remove volume", "/volumes/${urlEncode(name)}?force=${if (force) 1 else 0}")

    override suspend fun pruneVolumes(): DockerResult<Unit> =
        post("Failed to prune volumes", "/volumes/prune")

    // ── Networks ─────────────────────────────────────────────────────────────

    override suspend fun listNetworks(): DockerResult<List<DockerNetworkSummary>> =
        get("Failed to list networks", "/networks") { DockerApiParsers.parseNetworkList(it) }

    override suspend fun inspectNetwork(id: String): DockerResult<String> =
        get("Failed to inspect network", "/networks/${urlEncode(id)}") { it }

    override suspend fun createNetwork(name: String, driver: String?): DockerResult<Unit> {
        val body = JSONObject().put("Name", name)
        if (!driver.isNullOrBlank()) body.put("Driver", driver)
        return postJson("Failed to create network", "/networks/create", body)
    }

    override suspend fun removeNetwork(id: String): DockerResult<Unit> =
        delete("Failed to remove network", "/networks/${urlEncode(id)}")

    override suspend fun pruneNetworks(): DockerResult<Unit> =
        post("Failed to prune networks", "/networks/prune")

    // ── Engine ───────────────────────────────────────────────────────────────

    override suspend fun engineInfo(): DockerResult<DockerEngineInfo> {
        val result = get("Failed to read engine info", "/info") { DockerApiParsers.parseInfo(it) }
        return when (result) {
            is DockerResult.Success -> result.value?.let { DockerResult.Success(it) }
                ?: DockerResult.Error("Engine info was unparsable")
            is DockerResult.PermissionDenied -> result
            is DockerResult.NotFound -> result
            is DockerResult.TransportUnavailable -> result
            is DockerResult.Error -> result
        }
    }

    override suspend fun engineVersion(): DockerResult<DockerVersionInfo> = withContext(Dispatchers.IO) {
        try {
            fetchVersionUnversioned()?.let { DockerResult.Success(it) }
                ?: DockerResult.Error("Engine version was unparsable")
        } catch (e: TransportUnavailableException) {
            DockerResult.TransportUnavailable(e.message.orEmpty(), e.detail)
        } catch (e: java.io.IOException) {
            DockerResult.TransportUnavailable("Failed to read engine version", e.message)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DockerResult.Error("Failed to read engine version", e.message)
        }
    }

    override suspend fun diskUsage(): DockerResult<DockerDiskUsage> =
        get("Failed to read disk usage", "/system/df") { DockerApiParsers.parseSystemDf(it) }

    // ── Compose + remote files (SSH exec via shared RemoteExecOps) ───────────

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
        relay.close()
        // Backstop for streaming calls whose collectors were not cancelled —
        // shutdown() only blocks new tasks and evictAll() only drops idle
        // connections, so cancel every in-flight call explicitly.
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}

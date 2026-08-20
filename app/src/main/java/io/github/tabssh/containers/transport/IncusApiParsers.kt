package io.github.tabssh.containers.transport

import io.github.tabssh.utils.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsers for the Incus / LXC-LXD REST API. No I/O and no Android
 * dependencies — everything here is unit-testable on the JVM, mirroring
 * [DockerApiParsers].
 *
 * Every response is wrapped in a `{"type": …, "metadata": …}` envelope:
 *  - `sync` — the answer is already in `metadata`.
 *  - `async` — the daemon queued an operation; `operation` carries its path,
 *    and the caller waits on it before the change is visible.
 *  - `error` — `error`/`error_code` carry the failure.
 *
 * The transport handles the waiting; this object only reads the envelopes.
 */
object IncusApiParsers {

    /** Envelope type for an answer that is already complete. */
    const val TYPE_SYNC = "sync"

    /** Envelope type for a queued background operation. */
    const val TYPE_ASYNC = "async"

    /** Envelope type for a daemon-side failure. */
    const val TYPE_ERROR = "error"

    /** Operation status code for a finished, successful operation. */
    const val OPERATION_SUCCESS = 200

    /** Instance status the engine reports for a frozen (paused) instance. */
    private const val STATUS_FROZEN = "frozen"

    /** Instance status the engine reports for a stopped instance. */
    private const val STATUS_STOPPED = "stopped"

    /** Zero value the engine uses for "no expiry" timestamps. */
    private const val NEVER_TIMESTAMP = "0001-01-01T00:00:00Z"

    /** Prefix the daemon puts in front of every `used_by` reference. */
    private const val API_PREFIX = "/1.0/"

    /** A daemon failure carried by an `error` envelope. */
    data class ApiError(val code: Int, val message: String)

    /** Terminal or in-flight state of a background operation. */
    data class OperationOutcome(
        val done: Boolean,
        val success: Boolean,
        val statusCode: Int,
        val error: String
    )

    /** One `/1.0/instances/<name>/state` reading, before rate conversion. */
    data class InstanceStateSample(
        val cpuUsageNanos: Long,
        val memUsageBytes: Long,
        val memPeakBytes: Long,
        val netInputBytes: Long,
        val netOutputBytes: Long,
        val diskUsageBytes: Long,
        val processes: Int
    )

    // ── Envelope handling ────────────────────────────────────────────────────

    /** The parsed envelope, or null when [body] is not JSON at all. */
    fun envelope(body: String): JSONObject? = try {
        JSONObject(body.trim())
    } catch (e: Exception) {
        Logger.w("IncusApiParsers", "envelope: unparsable body (${body.length} chars): ${e.message}")
        null
    }

    /** The `error` envelope in [body], or null when it reports no failure. */
    fun parseError(body: String): ApiError? {
        val obj = envelope(body) ?: return null
        val message = obj.optString("error")
        val code = obj.optInt("error_code")
        return if (obj.optString("type") == TYPE_ERROR || message.isNotEmpty()) {
            ApiError(code, message)
        } else {
            null
        }
    }

    /** `metadata` of a sync envelope as an object, or null. */
    fun syncObject(body: String): JSONObject? = envelope(body)?.optJSONObject("metadata")

    /** `metadata` of a sync envelope as an array, or null. */
    fun syncArray(body: String): JSONArray? = envelope(body)?.optJSONArray("metadata")

    /**
     * The operation path (`/1.0/operations/<uuid>`) an async envelope queued,
     * or null when [body] is not an async envelope. The transport appends
     * `/wait` to this to block until the operation finishes.
     */
    fun operationPath(body: String): String? {
        val obj = envelope(body) ?: return null
        if (obj.optString("type") != TYPE_ASYNC) return null
        return obj.optString("operation").takeIf { it.isNotEmpty() }
            ?: obj.optJSONObject("metadata")?.optString("id")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "${API_PREFIX}operations/$it" }
    }

    /**
     * Read the outcome of `GET /1.0/operations/<id>[/wait]`. A `wait` that
     * timed out answers with the operation still Running, which is
     * [OperationOutcome.done] = false — the caller waits again.
     */
    fun parseOperation(body: String): OperationOutcome {
        parseError(body)?.let { return OperationOutcome(true, false, it.code, it.message) }
        val metadata = syncObject(body)
            ?: return OperationOutcome(true, false, 0, "operation response had no metadata")
        val statusCode = metadata.optInt("status_code")
        // Status codes below 200 are the in-flight range (Pending 100,
        // Running 103); 200 is Success and anything above it is a failure.
        val done = statusCode >= OPERATION_SUCCESS
        return OperationOutcome(
            done = done,
            success = statusCode == OPERATION_SUCCESS,
            statusCode = statusCode,
            error = metadata.optString("err")
        )
    }

    /**
     * Human download progress an image-pull operation reports
     * ("rootfs: 42% (3.20MB/s)"), or null while the daemon has published none.
     */
    fun parseDownloadProgress(body: String): String? =
        syncObject(body)
            ?.optJSONObject("metadata")
            ?.optString("download_progress")
            ?.takeIf { it.isNotEmpty() }

    // ── Instances ────────────────────────────────────────────────────────────

    /** `GET /1.0/instances?recursion=1` — the instance listing. */
    fun parseInstanceList(body: String): List<ContainerSummary> = instancesFrom(syncArray(body))

    /** Instance listing from an already-unwrapped array (CLI `--format json`). */
    fun instancesFrom(arr: JSONArray?): List<ContainerSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseInstance(it) }
        }
    }

    /** One instance object from a listing or a single-instance fetch. */
    fun parseInstance(obj: JSONObject): ContainerSummary? {
        val name = obj.optString("name")
        if (name.isEmpty()) return null
        val config = parseStringMap(obj.optJSONObject("config"))
        val status = obj.optString("status")
        return ContainerSummary(
            id = name,
            names = listOf(name),
            image = imageOf(config),
            state = normalizeState(status),
            status = status,
            created = obj.optString("created_at"),
            ports = formatProxyDevices(obj.optJSONObject("devices")),
            labels = config
        )
    }

    /**
     * Map an Incus status onto the state vocabulary the rest of the app uses,
     * so one set of state chips covers every engine: Frozen is Docker's
     * paused, Stopped is its exited, and anything else passes through
     * lowercased (running, starting, stopping, error).
     */
    fun normalizeState(status: String): String = when (status.lowercase()) {
        STATUS_FROZEN -> "paused"
        STATUS_STOPPED -> "exited"
        else -> status.lowercase()
    }

    /**
     * The best available image label for an instance. Incus records the image
     * it was created from in `image.*` config keys; a manually built instance
     * has none, leaving the volatile base fingerprint as the only identity.
     */
    fun imageOf(config: Map<String, String>): String {
        config["image.description"]?.takeIf { it.isNotBlank() }?.let { return it }
        val os = config["image.os"].orEmpty()
        val release = config["image.release"].orEmpty()
        if (os.isNotBlank()) return listOf(os, release).filter { it.isNotBlank() }.joinToString(" ")
        return config["volatile.base_image"]?.take(12).orEmpty()
    }

    /**
     * Summarize proxy devices as a Docker-style port string. A proxy device is
     * how Incus publishes a port, and its listen/connect addresses carry the
     * same information Docker's PortBindings do.
     */
    fun formatProxyDevices(devices: JSONObject?): String {
        if (devices == null) return ""
        val parts = mutableListOf<String>()
        for (key in jsonKeys(devices)) {
            val device = devices.optJSONObject(key) ?: continue
            if (device.optString("type") != "proxy") continue
            val listen = device.optString("listen")
            val connect = device.optString("connect")
            if (listen.isEmpty() && connect.isEmpty()) continue
            parts += "$listen->$connect"
        }
        return parts.joinToString(", ")
    }

    /** `GET /1.0/instances/<name>/state` — one raw usage reading. */
    fun parseInstanceState(body: String): InstanceStateSample? = instanceStateFrom(syncObject(body))

    /** One raw usage reading from an already-unwrapped object. */
    fun instanceStateFrom(metadata: JSONObject?): InstanceStateSample? {
        if (metadata == null) return null
        var netIn = 0L
        var netOut = 0L
        val networks = metadata.optJSONObject("network")
        if (networks != null) {
            val keys = networks.keys()
            while (keys.hasNext()) {
                val counters = networks.optJSONObject(keys.next())?.optJSONObject("counters") ?: continue
                netIn += counters.optLong("bytes_received")
                netOut += counters.optLong("bytes_sent")
            }
        }
        var disk = 0L
        val disks = metadata.optJSONObject("disk")
        if (disks != null) {
            val keys = disks.keys()
            while (keys.hasNext()) {
                disk += disks.optJSONObject(keys.next())?.optLong("usage") ?: 0L
            }
        }
        val memory = metadata.optJSONObject("memory")
        return InstanceStateSample(
            cpuUsageNanos = metadata.optJSONObject("cpu")?.optLong("usage") ?: 0L,
            memUsageBytes = memory?.optLong("usage") ?: 0L,
            memPeakBytes = memory?.optLong("usage_peak") ?: 0L,
            netInputBytes = netIn,
            netOutputBytes = netOut,
            diskUsageBytes = disk,
            processes = metadata.optInt("processes")
        )
    }

    /**
     * Convert two consecutive state readings into one stats sample. CPU usage
     * is a monotonic nanosecond counter, so the percentage is its delta over
     * the wall-clock delta — the same shape the Docker API computes from its
     * own cumulative counters.
     */
    fun statsFrom(
        current: InstanceStateSample,
        previous: InstanceStateSample?,
        elapsedMillis: Long,
        memLimitBytes: Long
    ): ContainerStats {
        val cpuPercent = if (previous == null || elapsedMillis <= 0) {
            0.0
        } else {
            val deltaNanos = (current.cpuUsageNanos - previous.cpuUsageNanos).coerceAtLeast(0)
            deltaNanos.toDouble() / (elapsedMillis * 1_000_000.0) * 100.0
        }
        val limit = if (memLimitBytes > 0) memLimitBytes else current.memPeakBytes
        return ContainerStats(
            cpuPercent = cpuPercent,
            memUsageBytes = current.memUsageBytes,
            memLimitBytes = limit,
            memPercent = if (limit > 0) current.memUsageBytes.toDouble() / limit * 100.0 else 0.0,
            netInputBytes = current.netInputBytes,
            netOutputBytes = current.netOutputBytes,
            // Incus reports disk usage, not cumulative block I/O; the usage
            // total is the closest true value, and inventing a rate would be
            // worse than reporting the figure the engine actually publishes.
            blockReadBytes = current.diskUsageBytes,
            blockWriteBytes = 0,
            pids = current.processes
        )
    }

    /**
     * The instance's configured memory ceiling in bytes, or 0 when it has
     * none. `limits.memory` is either a suffixed size ("2GiB") or a percentage
     * of host memory, which is not a byte value and is therefore ignored.
     */
    fun parseMemoryLimit(config: Map<String, String>): Long {
        val raw = config["limits.memory"]?.trim().orEmpty()
        if (raw.isEmpty() || raw.endsWith("%")) return 0
        return DockerCliParsers.parseSizeToBytes(raw)
    }

    // ── Images ───────────────────────────────────────────────────────────────

    /** `GET /1.0/images?recursion=1`. */
    fun parseImageList(body: String): List<ContainerImageSummary> = imagesFrom(syncArray(body))

    /** Image listing from an already-unwrapped array. */
    fun imagesFrom(arr: JSONArray?): List<ContainerImageSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseImage(it) }
        }
    }

    /** One image object. */
    fun parseImage(obj: JSONObject): ContainerImageSummary? {
        val fingerprint = obj.optString("fingerprint")
        if (fingerprint.isEmpty()) return null
        val aliases = obj.optJSONArray("aliases")
        val names = mutableListOf<String>()
        if (aliases != null) {
            for (i in 0 until aliases.length()) {
                aliases.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { names += it }
            }
        }
        if (names.isEmpty()) {
            obj.optJSONObject("properties")?.optString("description")
                ?.takeIf { it.isNotEmpty() }
                ?.let { names += it }
        }
        return ContainerImageSummary(
            id = fingerprint,
            repoTags = names,
            sizeBytes = obj.optLong("size"),
            created = obj.optString("created_at")
        )
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    /** `GET /1.0/storage-pools?recursion=1` — pool name to pool driver. */
    fun parseStoragePools(body: String): Map<String, String> = storagePoolsFrom(syncArray(body))

    /** Pool name to driver from an already-unwrapped array. */
    fun storagePoolsFrom(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        val pools = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isEmpty()) continue
            pools[name] = obj.optString("driver")
        }
        return pools
    }

    /**
     * `GET /1.0/storage-pools/<pool>/volumes?recursion=1`, keeping only
     * user-managed `custom` volumes — the image and instance volumes in the
     * same listing are engine bookkeeping, not something a user acts on.
     *
     * Names come back pool-qualified ("default/data") because a volume is only
     * addressable together with its pool.
     */
    fun parseVolumeList(body: String, pool: String, poolDriver: String): List<ContainerVolumeSummary> =
        volumesFrom(syncArray(body), pool, poolDriver)

    /** Volume listing from an already-unwrapped array. */
    fun volumesFrom(arr: JSONArray?, pool: String, poolDriver: String): List<ContainerVolumeSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            if (obj.optString("type") != "custom") return@mapNotNull null
            val name = obj.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            ContainerVolumeSummary(
                name = "$pool/$name",
                driver = poolDriver,
                mountpoint = obj.optString("content_type")
            )
        }
    }

    // ── Networks ─────────────────────────────────────────────────────────────

    /** `GET /1.0/networks?recursion=1`. */
    fun parseNetworkList(body: String): List<ContainerNetworkSummary> = networksFrom(syncArray(body))

    /** Network listing from an already-unwrapped array. */
    fun networksFrom(arr: JSONArray?): List<ContainerNetworkSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            ContainerNetworkSummary(
                id = name,
                name = name,
                driver = obj.optString("type"),
                scope = obj.optString("status")
            )
        }
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    /** `GET /1.0/instances/<name>/snapshots?recursion=1`. */
    fun parseSnapshotList(body: String, instance: String): List<ContainerSnapshotSummary> =
        snapshotsFrom(syncArray(body), instance)

    /** Snapshot listing from an already-unwrapped array. */
    fun snapshotsFrom(arr: JSONArray?, instance: String): List<ContainerSnapshotSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = obj.optString("name")
            if (raw.isEmpty()) return@mapNotNull null
            ContainerSnapshotSummary(
                // Snapshot names are reported both bare and instance-qualified
                // ("c1/snap0") across versions; the bare half is what every
                // snapshot endpoint takes.
                name = raw.substringAfterLast('/'),
                instance = instance,
                created = obj.optString("created_at"),
                expires = obj.optString("expires_at").takeIf { it != NEVER_TIMESTAMP }.orEmpty(),
                stateful = obj.optBoolean("stateful")
            )
        }
    }

    // ── Profiles ─────────────────────────────────────────────────────────────

    /** `GET /1.0/profiles?recursion=1`. */
    fun parseProfileList(body: String): List<ContainerProfileSummary> = profilesFrom(syncArray(body))

    /** Profile listing from an already-unwrapped array. */
    fun profilesFrom(arr: JSONArray?): List<ContainerProfileSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            ContainerProfileSummary(
                name = name,
                description = obj.optString("description"),
                devices = jsonKeys(obj.optJSONObject("devices")),
                usedBy = parseUsedBy(obj.optJSONArray("used_by"))
            )
        }
    }

    // ── Projects ─────────────────────────────────────────────────────────────

    /** `GET /1.0/projects?recursion=1`; [active] marks the current scope. */
    fun parseProjectList(body: String, active: String?): List<ContainerProjectSummary> =
        projectsFrom(syncArray(body), active)

    /** Project listing from an already-unwrapped array. */
    fun projectsFrom(arr: JSONArray?, active: String?): List<ContainerProjectSummary> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = obj.optString("name")
            if (name.isEmpty()) return@mapNotNull null
            ContainerProjectSummary(
                name = name,
                description = obj.optString("description"),
                usedByCount = obj.optJSONArray("used_by")?.length() ?: 0,
                active = name == active
            )
        }
    }

    // ── Server ───────────────────────────────────────────────────────────────

    /**
     * `GET /1.0` — the daemon's own description. Object counts are not part of
     * this document, so they stay zero here and the transport fills them in
     * from the listings it already fetched for the dashboard.
     */
    fun parseServerInfo(body: String): ContainerEngineInfo? = serverInfoFrom(syncObject(body))

    /** Server description from an already-unwrapped object. */
    fun serverInfoFrom(metadata: JSONObject?): ContainerEngineInfo? {
        if (metadata == null) return null
        val environment = metadata.optJSONObject("environment") ?: return null
        val osName = environment.optString("os_name")
        val osVersion = environment.optString("os_version")
        return ContainerEngineInfo(
            name = environment.optString("server_name"),
            serverVersion = environment.optString("server_version"),
            operatingSystem = listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" "),
            architecture = environment.optString("kernel_architecture"),
            containersTotal = 0,
            containersRunning = 0,
            containersPaused = 0,
            containersStopped = 0,
            images = 0,
            memTotalBytes = 0,
            ncpu = 0
        )
    }

    /** `GET /1.0` — engine and API version. */
    fun parseServerVersion(body: String): ContainerEngineVersion? = serverVersionFrom(syncObject(body))

    /** Server version from an already-unwrapped object. */
    fun serverVersionFrom(metadata: JSONObject?): ContainerEngineVersion? {
        if (metadata == null) return null
        val version = metadata.optJSONObject("environment")?.optString("server_version").orEmpty()
        if (version.isEmpty()) return null
        return ContainerEngineVersion(
            version = version,
            apiVersion = metadata.optString("api_version"),
            minApiVersion = null
        )
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /** Flatten a JSON string-map to a Kotlin map, preserving key order. */
    fun parseStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }

    /**
     * The keys of a JSON object, sorted; null yields empty. `JSONObject` is
     * hash-backed, so iteration order is neither the document's nor stable
     * across runs — sorting is what keeps a device list from reshuffling
     * itself between two reads of the same profile.
     */
    fun jsonKeys(obj: JSONObject?): List<String> {
        if (obj == null) return emptyList()
        val out = ArrayList<String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) out += keys.next()
        out.sort()
        return out
    }

    /** `used_by` entries are API paths; keep the object name they end with. */
    fun parseUsedBy(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i)
                .substringBefore('?')
                .substringAfterLast('/')
                .takeIf { it.isNotEmpty() }
        }
    }
}

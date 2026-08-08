package io.github.tabssh.docker.transport

import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure parsers for Docker Engine REST API responses. No I/O beyond the
 * stream-decoding helpers (which operate on any InputStream) and no Android
 * dependencies — unit-testable on the JVM.
 */
object DockerApiParsers {

    /**
     * Highest Engine API version this client speaks. Endpoints added after
     * 1.43 are not used; the negotiated version is min(this, server).
     */
    const val CLIENT_MAX_API_VERSION = "1.43"

    // ── Version negotiation ─────────────────────────────────────────────────

    /**
     * Compare two "MAJOR.MINOR" API versions numerically ("1.9" < "1.43").
     * Returns <0, 0, >0 like [Comparable.compareTo].
     */
    fun compareApiVersions(a: String, b: String): Int {
        val partsA = a.trim().split(".")
        val partsB = b.trim().split(".")
        val len = maxOf(partsA.size, partsB.size)
        for (i in 0 until len) {
            val numA = partsA.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            val numB = partsB.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            if (numA != numB) return numA - numB
        }
        return 0
    }

    /**
     * Negotiated version = min(client ceiling, server-reported ApiVersion).
     * A blank/absent server version falls back to the client ceiling — old
     * engines ignore version prefixes they do not understand anyway.
     */
    fun negotiateApiVersion(
        clientMax: String,
        serverApiVersion: String?
    ): String {
        val server = serverApiVersion?.trim().orEmpty()
        if (server.isEmpty()) return clientMax
        return if (compareApiVersions(server, clientMax) < 0) server else clientMax
    }

    // ── Response object parsers ─────────────────────────────────────────────

    /** `GET /version` body. */
    fun parseVersion(body: String): DockerVersionInfo? {
        return try {
            val obj = JSONObject(body)
            DockerVersionInfo(
                version = obj.optString("Version"),
                apiVersion = obj.optString("ApiVersion"),
                minApiVersion = obj.optString("MinAPIVersion").ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** `GET /containers/json` body (JSON array). */
    fun parseContainerList(body: String): List<DockerContainerSummary> {
        val arr = JSONArray(body)
        val result = mutableListOf<DockerContainerSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val names = mutableListOf<String>()
            val namesArr = obj.optJSONArray("Names")
            if (namesArr != null) {
                for (j in 0 until namesArr.length()) {
                    names += namesArr.getString(j).removePrefix("/")
                }
            }
            result += DockerContainerSummary(
                id = obj.optString("Id"),
                names = names,
                image = obj.optString("Image"),
                state = obj.optString("State"),
                status = obj.optString("Status"),
                created = formatEpochSeconds(obj.optLong("Created")),
                ports = formatPorts(obj.optJSONArray("Ports"))
            )
        }
        return result
    }

    /** `GET /images/json` body (JSON array). */
    fun parseImageList(body: String): List<DockerImageSummary> {
        val arr = JSONArray(body)
        val result = mutableListOf<DockerImageSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val tagsArr = obj.optJSONArray("RepoTags")
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) {
                    val tag = tagsArr.getString(j)
                    if (tag != "<none>:<none>") tags += tag
                }
            }
            result += DockerImageSummary(
                id = obj.optString("Id"),
                repoTags = tags,
                sizeBytes = obj.optLong("Size"),
                created = formatEpochSeconds(obj.optLong("Created"))
            )
        }
        return result
    }

    /** `GET /volumes` body — `{"Volumes":[…],"Warnings":…}`. */
    fun parseVolumeList(body: String): List<DockerVolumeSummary> {
        val arr = JSONObject(body).optJSONArray("Volumes") ?: return emptyList()
        val result = mutableListOf<DockerVolumeSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result += DockerVolumeSummary(
                name = obj.optString("Name"),
                driver = obj.optString("Driver"),
                mountpoint = obj.optString("Mountpoint")
            )
        }
        return result
    }

    /** `GET /networks` body (JSON array). */
    fun parseNetworkList(body: String): List<DockerNetworkSummary> {
        val arr = JSONArray(body)
        val result = mutableListOf<DockerNetworkSummary>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            result += DockerNetworkSummary(
                id = obj.optString("Id"),
                name = obj.optString("Name"),
                driver = obj.optString("Driver"),
                scope = obj.optString("Scope")
            )
        }
        return result
    }

    /** `GET /info` body. */
    fun parseInfo(body: String): DockerEngineInfo? {
        return try {
            val obj = JSONObject(body)
            DockerEngineInfo(
                name = obj.optString("Name"),
                serverVersion = obj.optString("ServerVersion"),
                operatingSystem = obj.optString("OperatingSystem"),
                architecture = obj.optString("Architecture"),
                containersTotal = obj.optInt("Containers"),
                containersRunning = obj.optInt("ContainersRunning"),
                containersPaused = obj.optInt("ContainersPaused"),
                containersStopped = obj.optInt("ContainersStopped"),
                images = obj.optInt("Images"),
                memTotalBytes = obj.optLong("MemTotal"),
                ncpu = obj.optInt("NCPU")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `GET /system/df` body → dashboard rows matching the CLI's
     * `docker system df` categories.
     */
    fun parseSystemDf(body: String): DockerDiskUsage {
        val obj = JSONObject(body)
        val rows = mutableListOf<DiskUsageRow>()

        val images = obj.optJSONArray("Images") ?: JSONArray()
        var imagesSize = 0L
        var imagesActive = 0
        for (i in 0 until images.length()) {
            val img = images.getJSONObject(i)
            imagesSize += img.optLong("Size")
            if (img.optLong("Containers") > 0) imagesActive++
        }
        rows += DiskUsageRow("Images", images.length(), imagesActive, imagesSize, 0)

        val containers = obj.optJSONArray("Containers") ?: JSONArray()
        var containersSize = 0L
        var containersActive = 0
        for (i in 0 until containers.length()) {
            val c = containers.getJSONObject(i)
            containersSize += c.optLong("SizeRw")
            if (c.optString("State") == "running") containersActive++
        }
        rows += DiskUsageRow("Containers", containers.length(), containersActive, containersSize, 0)

        val volumes = obj.optJSONArray("Volumes") ?: JSONArray()
        var volumesSize = 0L
        var volumesActive = 0
        for (i in 0 until volumes.length()) {
            val v = volumes.getJSONObject(i)
            val usage = v.optJSONObject("UsageData")
            volumesSize += usage?.optLong("Size")?.coerceAtLeast(0) ?: 0
            if ((usage?.optLong("RefCount") ?: 0) > 0) volumesActive++
        }
        rows += DiskUsageRow("Local Volumes", volumes.length(), volumesActive, volumesSize, 0)

        return DockerDiskUsage(rows)
    }

    /** One NDJSON line of `POST /images/create` pull progress. */
    fun parsePullProgressLine(line: String): PullProgressEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            val obj = JSONObject(trimmed)
            val error = obj.optString("error").ifEmpty { null }
            val detail = obj.optJSONObject("progressDetail")
            PullProgressEvent(
                status = obj.optString("status"),
                layerId = obj.optString("id").ifEmpty { null },
                currentBytes = detail?.optLong("current") ?: 0,
                totalBytes = detail?.optLong("total") ?: 0,
                error = error
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One raw stats JSON object (`GET /containers/{id}/stats`) → sample.
     * CPU% uses the engine's documented formula:
     * (cpuDelta / systemDelta) * onlineCpus * 100.
     */
    fun parseApiStats(body: String): DockerContainerStats? {
        return try {
            val obj = JSONObject(body)
            val cpu = obj.optJSONObject("cpu_stats")
            val precpu = obj.optJSONObject("precpu_stats")
            val cpuTotal = cpu?.optJSONObject("cpu_usage")?.optLong("total_usage") ?: 0
            val preCpuTotal = precpu?.optJSONObject("cpu_usage")?.optLong("total_usage") ?: 0
            val systemTotal = cpu?.optLong("system_cpu_usage") ?: 0
            val preSystemTotal = precpu?.optLong("system_cpu_usage") ?: 0
            val onlineCpus = (cpu?.optInt("online_cpus") ?: 0).takeIf { it > 0 }
                ?: (cpu?.optJSONObject("cpu_usage")?.optJSONArray("percpu_usage")?.length() ?: 1)
            val cpuDelta = (cpuTotal - preCpuTotal).toDouble()
            val systemDelta = (systemTotal - preSystemTotal).toDouble()
            val cpuPercent = if (cpuDelta > 0 && systemDelta > 0) {
                (cpuDelta / systemDelta) * onlineCpus * 100.0
            } else {
                0.0
            }

            val mem = obj.optJSONObject("memory_stats")
            val memUsage = mem?.optLong("usage") ?: 0
            val memLimit = mem?.optLong("limit") ?: 0
            val memPercent = if (memLimit > 0) memUsage.toDouble() / memLimit * 100.0 else 0.0

            var rx = 0L
            var tx = 0L
            val networks = obj.optJSONObject("networks")
            if (networks != null) {
                for (key in networks.keys()) {
                    val iface = networks.optJSONObject(key) ?: continue
                    rx += iface.optLong("rx_bytes")
                    tx += iface.optLong("tx_bytes")
                }
            }

            var blockRead = 0L
            var blockWrite = 0L
            val blkio = obj.optJSONObject("blkio_stats")?.optJSONArray("io_service_bytes_recursive")
            if (blkio != null) {
                for (i in 0 until blkio.length()) {
                    val entry = blkio.optJSONObject(i) ?: continue
                    when (entry.optString("op").lowercase()) {
                        "read" -> blockRead += entry.optLong("value")
                        "write" -> blockWrite += entry.optLong("value")
                    }
                }
            }

            DockerContainerStats(
                cpuPercent = cpuPercent,
                memUsageBytes = memUsage,
                memLimitBytes = memLimit,
                memPercent = memPercent,
                netInputBytes = rx,
                netOutputBytes = tx,
                blockReadBytes = blockRead,
                blockWriteBytes = blockWrite,
                pids = obj.optJSONObject("pids_stats")?.optInt("current") ?: 0
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── Log stream demultiplexing ───────────────────────────────────────────

    /**
     * Decode a `GET /containers/{id}/logs` byte stream into text lines,
     * invoking [emit] for each complete line. Non-TTY containers multiplex
     * stdout/stderr in 8-byte-header frames (byte 0 = stream, bytes 4–7 =
     * big-endian payload length); TTY containers send raw bytes. The format
     * is sniffed from the first header: stream byte in 0..2 with three zero
     * padding bytes.
     *
     * Blocking — callers run it inside a Flow on Dispatchers.IO and stop it
     * by closing [input] (collector cancellation closes the HTTP body).
     */
    fun decodeLogStream(input: InputStream, emit: (String) -> Unit) {
        val header = ByteArray(8)
        val first = readFully(input, header, 8)
        if (first == 0) return
        val framed = first == 8 &&
            header[0].toInt() in 0..2 &&
            header[1].toInt() == 0 && header[2].toInt() == 0 && header[3].toInt() == 0
        val pending = StringBuilder()
        if (!framed) {
            // TTY stream — emit the sniffed bytes plus the rest raw.
            pending.append(String(header, 0, first, Charsets.UTF_8))
            emitLines(pending, emit)
            val buf = ByteArray(8192)
            var n = input.read(buf)
            while (n > 0) {
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                emitLines(pending, emit)
                n = input.read(buf)
            }
        } else {
            var currentHeader: ByteArray? = header
            while (true) {
                val head = currentHeader ?: ByteArray(8).also {
                    if (readFully(input, it, 8) < 8) {
                        flushPending(pending, emit)
                        return
                    }
                }
                currentHeader = null
                val length = ((head[4].toInt() and 0xFF) shl 24) or
                    ((head[5].toInt() and 0xFF) shl 16) or
                    ((head[6].toInt() and 0xFF) shl 8) or
                    (head[7].toInt() and 0xFF)
                if (length > 0) {
                    val payload = ByteArray(length)
                    if (readFully(input, payload, length) < length) {
                        pending.append(String(payload, Charsets.UTF_8))
                        flushPending(pending, emit)
                        return
                    }
                    pending.append(String(payload, Charsets.UTF_8))
                    emitLines(pending, emit)
                }
            }
        }
        flushPending(pending, emit)
    }

    /** Read up to [count] bytes; returns bytes read before EOF. */
    private fun readFully(input: InputStream, buf: ByteArray, count: Int): Int {
        var offset = 0
        while (offset < count) {
            val n = try {
                input.read(buf, offset, count - offset)
            } catch (_: EOFException) {
                -1
            }
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /** Emit each complete \n-terminated line in [pending], keeping the rest. */
    private fun emitLines(pending: StringBuilder, emit: (String) -> Unit) {
        var idx = pending.indexOf("\n")
        while (idx >= 0) {
            emit(pending.substring(0, idx).removeSuffix("\r"))
            pending.delete(0, idx + 1)
            idx = pending.indexOf("\n")
        }
    }

    /** Emit a trailing partial line, if any. */
    private fun flushPending(pending: StringBuilder, emit: (String) -> Unit) {
        if (pending.isNotEmpty()) {
            emit(pending.toString())
            pending.setLength(0)
        }
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    /** Epoch seconds → ISO 8601 UTC string ("" for 0/absent). */
    fun formatEpochSeconds(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(epochSeconds * 1000))
    }

    /** Ports array of `/containers/json` → CLI-style summary string. */
    private fun formatPorts(arr: JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val ip = p.optString("IP")
            val publicPort = p.optInt("PublicPort")
            val privatePort = p.optInt("PrivatePort")
            val proto = p.optString("Type")
            parts += if (publicPort > 0) {
                "${if (ip.isEmpty()) "0.0.0.0" else ip}:$publicPort->$privatePort/$proto"
            } else {
                "$privatePort/$proto"
            }
        }
        return parts.joinToString(", ")
    }
}

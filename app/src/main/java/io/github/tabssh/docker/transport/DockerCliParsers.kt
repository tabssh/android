package io.github.tabssh.docker.transport

import org.json.JSONObject

/**
 * Pure parsers for `docker … --format '{{json .}}'` NDJSON output and CLI
 * failure classification. No I/O and no Android dependencies — everything
 * here is unit-testable on the JVM.
 */
object DockerCliParsers {

    /**
     * Classify a failed CLI invocation into the matching [DockerResult]
     * failure. [context] becomes the message; the raw output travels in
     * detail so the UI can offer a "show details" affordance.
     */
    fun classifyFailure(context: String, stderr: String, stdout: String = ""): DockerResult<Nothing> {
        val text = (stderr + "\n" + stdout).lowercase()
        return when {
            text.contains("permission denied") ->
                DockerResult.PermissionDenied(
                    DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION,
                    detail = "$context: ${stderr.trim().ifEmpty { stdout.trim() }}"
                )
            text.contains("no such object") ||
                text.contains("no such container") ||
                text.contains("no such image") ||
                text.contains("no such volume") ||
                text.contains("no such network") ||
                text.contains("no such file or directory") ||
                text.contains("not found: manifest unknown") ->
                DockerResult.NotFound(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            text.contains("command not found") ||
                text.contains("docker: not found") ||
                text.contains("executable file not found") ->
                DockerResult.TransportUnavailable(
                    DockerTransportMessages.DOCKER_CLI_MISSING,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            text.contains("cannot connect to the docker daemon") ->
                DockerResult.TransportUnavailable(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            else ->
                DockerResult.Error(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
        }
    }

    /**
     * Parse NDJSON output (one JSON object per line) with [parseLine].
     * Blank and unparsable lines are skipped — `docker` occasionally mixes
     * warnings into stdout on some distros.
     */
    fun <T> parseNdjson(output: String, parseLine: (JSONObject) -> T?): List<T> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                try {
                    parseLine(JSONObject(line))
                } catch (_: Exception) {
                    null
                }
            }
            .toList()

    /** One `docker ps --format '{{json .}}'` line. */
    fun parseContainerLine(obj: JSONObject): DockerContainerSummary =
        DockerContainerSummary(
            id = obj.optString("ID"),
            names = obj.optString("Names").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            image = obj.optString("Image"),
            state = obj.optString("State"),
            status = obj.optString("Status"),
            created = obj.optString("CreatedAt"),
            ports = obj.optString("Ports")
        )

    /** One `docker images --format '{{json .}}'` line. */
    fun parseImageLine(obj: JSONObject): DockerImageSummary {
        val repo = obj.optString("Repository")
        val tag = obj.optString("Tag")
        val repoTags = if (repo.isEmpty() || repo == "<none>") {
            emptyList()
        } else {
            listOf(if (tag.isEmpty() || tag == "<none>") repo else "$repo:$tag")
        }
        return DockerImageSummary(
            id = obj.optString("ID"),
            repoTags = repoTags,
            sizeBytes = parseSizeToBytes(obj.optString("Size")),
            created = obj.optString("CreatedAt")
        )
    }

    /** One `docker volume ls --format '{{json .}}'` line. */
    fun parseVolumeLine(obj: JSONObject): DockerVolumeSummary =
        DockerVolumeSummary(
            name = obj.optString("Name"),
            driver = obj.optString("Driver"),
            mountpoint = obj.optString("Mountpoint")
        )

    /** One `docker network ls --format '{{json .}}'` line. */
    fun parseNetworkLine(obj: JSONObject): DockerNetworkSummary =
        DockerNetworkSummary(
            id = obj.optString("ID"),
            name = obj.optString("Name"),
            driver = obj.optString("Driver"),
            scope = obj.optString("Scope")
        )

    /** One `docker stats --no-stream --format '{{json .}}'` line. */
    fun parseStatsLine(obj: JSONObject): DockerContainerStats {
        val memParts = splitPair(obj.optString("MemUsage"))
        val netParts = splitPair(obj.optString("NetIO"))
        val blockParts = splitPair(obj.optString("BlockIO"))
        return DockerContainerStats(
            cpuPercent = parsePercent(obj.optString("CPUPerc")),
            memUsageBytes = parseSizeToBytes(memParts.first),
            memLimitBytes = parseSizeToBytes(memParts.second),
            memPercent = parsePercent(obj.optString("MemPerc")),
            netInputBytes = parseSizeToBytes(netParts.first),
            netOutputBytes = parseSizeToBytes(netParts.second),
            blockReadBytes = parseSizeToBytes(blockParts.first),
            blockWriteBytes = parseSizeToBytes(blockParts.second),
            pids = obj.optString("PIDs").toIntOrNull() ?: 0
        )
    }

    /** One `docker system df --format '{{json .}}'` line. */
    fun parseSystemDfLine(obj: JSONObject): DiskUsageRow =
        DiskUsageRow(
            type = obj.optString("Type"),
            totalCount = obj.optString("TotalCount").toIntOrNull() ?: 0,
            active = obj.optString("Active").toIntOrNull() ?: 0,
            sizeBytes = parseSizeToBytes(obj.optString("Size")),
            reclaimableBytes = parseSizeToBytes(obj.optString("Reclaimable").substringBefore("(").trim())
        )

    /** `docker version --format '{{json .}}'` — extracts the Server block. */
    fun parseCliVersion(output: String): DockerVersionInfo? {
        return try {
            val obj = JSONObject(output.trim())
            val server = obj.optJSONObject("Server") ?: return null
            DockerVersionInfo(
                version = server.optString("Version"),
                apiVersion = server.optString("ApiVersion"),
                minApiVersion = server.optString("MinAPIVersion").ifEmpty { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    /** `docker info --format '{{json .}}'` — single JSON object. */
    fun parseCliInfo(output: String): DockerEngineInfo? {
        return try {
            val obj = JSONObject(output.trim())
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
     * Parse a human size string ("1.2GB", "10MiB", "356kB", "0B") to bytes.
     * Decimal (kB/MB/GB/TB) and binary (KiB/MiB/GiB/TiB) units both appear in
     * docker CLI output. Unknown or empty input yields 0.
     */
    fun parseSizeToBytes(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty() || text == "--" || text == "N/A") return 0
        val match = Regex("^([0-9]*\\.?[0-9]+)\\s*([A-Za-z]*)$").find(text) ?: return 0
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0
        val multiplier = when (match.groupValues[2].lowercase()) {
            "", "b" -> 1.0
            "kb" -> 1000.0
            "kib" -> 1024.0
            "mb" -> 1000.0 * 1000
            "mib" -> 1024.0 * 1024
            "gb" -> 1000.0 * 1000 * 1000
            "gib" -> 1024.0 * 1024 * 1024
            "tb" -> 1000.0 * 1000 * 1000 * 1000
            "tib" -> 1024.0 * 1024 * 1024 * 1024
            else -> return 0
        }
        return (value * multiplier).toLong()
    }

    /** Parse "12.34%" → 12.34; unparsable input yields 0.0. */
    fun parsePercent(raw: String): Double =
        raw.trim().removeSuffix("%").toDoubleOrNull() ?: 0.0

    /** Split "10MiB / 1GiB" into its two sides (empty string when absent). */
    private fun splitPair(raw: String): Pair<String, String> {
        val parts = raw.split("/")
        return Pair(
            parts.getOrElse(0) { "" }.trim(),
            parts.getOrElse(1) { "" }.trim()
        )
    }
}

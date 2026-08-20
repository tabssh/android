package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.utils.logging.Logger
import org.json.JSONObject

/**
 * Pure parsers for `docker … --format '{{json .}}'` NDJSON output and CLI
 * failure classification. No I/O and no Android dependencies — everything
 * here is unit-testable on the JVM.
 */
object DockerCliParsers {

    /**
     * Classify a failed CLI invocation into the matching [ContainerResult]
     * failure. [context] becomes the message; the raw output travels in
     * detail so the UI can offer a "show details" affordance. [engine] selects
     * the remediation text for the two failures that have one — a permission
     * error and a missing binary mean different fixes on each engine.
     */
    fun classifyFailure(
        context: String,
        stderr: String,
        stdout: String = "",
        engine: ContainerEngine = ContainerEngine.DOCKER
    ): ContainerResult<Nothing> {
        val text = (stderr + "\n" + stdout).lowercase()
        return when {
            text.contains("permission denied") ->
                ContainerResult.PermissionDenied(
                    ContainerTransportMessages.socketPermission(engine),
                    detail = "$context: ${stderr.trim().ifEmpty { stdout.trim() }}"
                )
            text.contains("no such object") ||
                text.contains("no such container") ||
                text.contains("no such image") ||
                text.contains("no such volume") ||
                text.contains("no such network") ||
                text.contains("no such file or directory") ||
                text.contains("not found: manifest unknown") ->
                ContainerResult.NotFound(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            text.contains("command not found") ||
                // Generic ": not found" covers every engine's binary name
                // ("incus: not found", "lxc: not found") the way the
                // docker-specific form used to cover only one.
                text.contains(": not found") ||
                text.contains("executable file not found") ->
                ContainerResult.EngineNotInstalled(
                    ContainerTransportMessages.cliMissing(engine),
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            text.contains("cannot connect to the docker daemon") ||
                text.contains("cannot connect to the podman socket") ||
                text.contains("failed to connect to local incus") ||
                text.contains("unix.socket: connect: connection refused") ->
                ContainerResult.TransportUnavailable(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            else ->
                ContainerResult.Error(
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
                } catch (e: Exception) {
                    Logger.w("DockerCliParsers", "parseNdjson: skipped unparsable line (${line.length} chars): ${e.message}")
                    null
                }
            }
            .toList()

    /** One `docker ps --format '{{json .}}'` line. */
    fun parseContainerLine(obj: JSONObject): ContainerSummary =
        ContainerSummary(
            id = obj.optString("ID"),
            names = obj.optString("Names").split(",").map { it.trim() }.filter { it.isNotEmpty() },
            image = obj.optString("Image"),
            state = obj.optString("State"),
            status = obj.optString("Status"),
            created = obj.optString("CreatedAt"),
            ports = obj.optString("Ports"),
            labels = parseLabelList(obj.optString("Labels"))
        )

    /**
     * Parse the `docker ps` `Labels` field — a comma-separated `key=value`
     * list. Values may themselves contain `=` (a compose working dir does), so
     * only the first separator splits; entries without one are dropped.
     */
    fun parseLabelList(raw: String): Map<String, String> =
        raw.split(",")
            .mapNotNull { entry ->
                val trimmed = entry.trim()
                val split = trimmed.indexOf('=')
                if (split <= 0) null else trimmed.substring(0, split) to trimmed.substring(split + 1)
            }
            .toMap()

    /** One `docker images --format '{{json .}}'` line. */
    fun parseImageLine(obj: JSONObject): ContainerImageSummary {
        val repo = obj.optString("Repository")
        val tag = obj.optString("Tag")
        val repoTags = if (repo.isEmpty() || repo == "<none>") {
            emptyList()
        } else {
            listOf(if (tag.isEmpty() || tag == "<none>") repo else "$repo:$tag")
        }
        return ContainerImageSummary(
            id = obj.optString("ID"),
            repoTags = repoTags,
            sizeBytes = parseSizeToBytes(obj.optString("Size")),
            created = obj.optString("CreatedAt")
        )
    }

    /** One `docker volume ls --format '{{json .}}'` line. */
    fun parseVolumeLine(obj: JSONObject): ContainerVolumeSummary =
        ContainerVolumeSummary(
            name = obj.optString("Name"),
            driver = obj.optString("Driver"),
            mountpoint = obj.optString("Mountpoint")
        )

    /** One `docker network ls --format '{{json .}}'` line. */
    fun parseNetworkLine(obj: JSONObject): ContainerNetworkSummary =
        ContainerNetworkSummary(
            id = obj.optString("ID"),
            name = obj.optString("Name"),
            driver = obj.optString("Driver"),
            scope = obj.optString("Scope")
        )

    /** One `docker stats --no-stream --format '{{json .}}'` line. */
    fun parseStatsLine(obj: JSONObject): ContainerStats {
        val memParts = splitPair(obj.optString("MemUsage"))
        val netParts = splitPair(obj.optString("NetIO"))
        val blockParts = splitPair(obj.optString("BlockIO"))
        return ContainerStats(
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
    fun parseCliVersion(output: String): ContainerEngineVersion? {
        return try {
            val obj = JSONObject(output.trim())
            val server = obj.optJSONObject("Server") ?: return null
            ContainerEngineVersion(
                version = server.optString("Version"),
                apiVersion = server.optString("ApiVersion"),
                minApiVersion = server.optString("MinAPIVersion").ifEmpty { null }
            )
        } catch (e: Exception) {
            Logger.w("DockerCliParsers", "parseCliVersion: unparsable output (${output.length} chars): ${e.message}")
            null
        }
    }

    /** `docker info --format '{{json .}}'` — single JSON object. */
    fun parseCliInfo(output: String): ContainerEngineInfo? {
        return try {
            val obj = JSONObject(output.trim())
            ContainerEngineInfo(
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
        } catch (e: Exception) {
            Logger.w("DockerCliParsers", "parseCliInfo: unparsable output (${output.length} chars): ${e.message}")
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

    /**
     * Parse `docker compose ls --all --format json` output into
     * [ComposeLsEntry] rows. The plugin emits a single JSON array; hosts
     * without compose (or with nothing running) may emit an empty array or
     * blank output — both yield an empty list rather than an error, since
     * "no external stacks" is a normal outcome, not a failure.
     */
    fun parseComposeLs(output: String): List<ComposeLsEntry> {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            val array = org.json.JSONArray(trimmed)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("Name")
                if (name.isEmpty()) return@mapNotNull null
                ComposeLsEntry(
                    name = name,
                    status = obj.optString("Status"),
                    configFiles = obj.optString("ConfigFiles")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            Logger.w("DockerCliParsers", "parseComposeLs: unparsable output (${trimmed.length} chars): ${e.message}")
            emptyList()
        }
    }

    /**
     * Parse `docker compose ps --format json` output into the distinct
     * service names it reports. Compose has emitted this either as one JSON
     * array or as NDJSON (one object per line) across versions — both forms
     * are tried, falling back to the container Name with the project prefix
     * stripped when the Service field itself is absent.
     */
    fun parseComposePsServices(output: String): List<String> {
        val trimmed = output.trim()
        if (trimmed.isEmpty()) return emptyList()
        val objects: List<JSONObject> = try {
            val array = org.json.JSONArray(trimmed)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            trimmed.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { line ->
                    try {
                        JSONObject(line)
                    } catch (e: Exception) {
                        Logger.w(
                            "DockerCliParsers",
                            "parseComposePsServices: skipped unparsable line (${line.length} chars): ${e.message}"
                        )
                        null
                    }
                }
                .toList()
        }
        return objects
            .map { it.optString("Service").ifEmpty { it.optString("Name") } }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

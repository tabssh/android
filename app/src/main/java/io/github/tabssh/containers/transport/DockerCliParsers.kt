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

    /**
     * One `ps --format '{{json .}}'` line. Docker templates over a struct
     * with "ID", Names as a comma string, Labels as a `k=v` list and Ports as
     * a rendered string; Podman marshals entities.ListContainer with "Id",
     * Names as a JSON array, Labels as a JSON map and Ports as an array of
     * port mappings — both shapes are accepted here.
     */
    fun parseContainerLine(obj: JSONObject): ContainerSummary =
        ContainerSummary(
            id = obj.optString("ID").ifEmpty { obj.optString("Id") },
            names = parseNameField(obj.opt("Names")),
            image = obj.optString("Image"),
            state = obj.optString("State"),
            status = obj.optString("Status"),
            created = obj.optString("CreatedAt"),
            ports = parsePortsField(obj.opt("Ports")),
            labels = parseLabelsField(obj.opt("Labels"))
        )

    /** Names field — Docker comma string or Podman JSON array. */
    fun parseNameField(value: Any?): List<String> = when (value) {
        is org.json.JSONArray ->
            (0 until value.length()).map { value.optString(it).trim() }.filter { it.isNotEmpty() }
        is String -> value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    /** Labels field — Docker `k=v,k=v` string or Podman JSON map. */
    fun parseLabelsField(value: Any?): Map<String, String> = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { value.optString(it) }
        is String -> parseLabelList(value)
        else -> emptyMap()
    }

    /**
     * Ports field — Docker's pre-rendered "ip:host->ctr/proto" string, or
     * Podman's array of port mappings (snake_case keys per
     * containers/common types.PortMapping), rendered into the same shape.
     */
    fun parsePortsField(value: Any?): String = when (value) {
        is org.json.JSONArray ->
            (0 until value.length())
                .mapNotNull { i ->
                    val mapping = value.optJSONObject(i) ?: return@mapNotNull null
                    val hostIp = mapping.optString("host_ip").ifEmpty { "0.0.0.0" }
                    val proto = mapping.optString("protocol").ifEmpty { "tcp" }
                    val containerPort = mapping.optInt("container_port")
                    val hostPort = mapping.optInt("host_port")
                    if (hostPort > 0) "$hostIp:$hostPort->$containerPort/$proto"
                    else "$containerPort/$proto"
                }
                .joinToString(", ")
        is String -> value
        else -> ""
    }

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

    /**
     * One `images --format '{{json .}}'` line. Docker emits "Repository",
     * "Tag", "ID", a human "Size" and a "CreatedAt" string; Podman's
     * imageReporter emits lowercase "repository"/"tag" plus the embedded
     * ImageSummary's "Id", raw byte "Size" and unix-seconds "Created".
     */
    fun parseImageLine(obj: JSONObject): ContainerImageSummary {
        val repo = obj.optString("Repository").ifEmpty { obj.optString("repository") }
        val tag = obj.optString("Tag").ifEmpty { obj.optString("tag") }
        val repoTags = if (repo.isEmpty() || repo == "<none>") {
            emptyList()
        } else {
            listOf(if (tag.isEmpty() || tag == "<none>") repo else "$repo:$tag")
        }
        return ContainerImageSummary(
            id = obj.optString("ID").ifEmpty { obj.optString("Id") },
            repoTags = repoTags,
            sizeBytes = parseSizeToBytes(obj.optString("Size")),
            created = imageCreated(obj)
        )
    }

    /** The image creation timestamp — Docker's "CreatedAt" string, or Podman's unix "Created" formatted. */
    private fun imageCreated(obj: JSONObject): String {
        val createdAt = obj.optString("CreatedAt")
        if (createdAt.isNotEmpty()) return createdAt
        val epochSeconds = obj.optLong("Created")
        if (epochSeconds <= 0) return ""
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
        return format.format(java.util.Date(epochSeconds * 1000))
    }

    /** One `docker volume ls --format '{{json .}}'` line. */
    fun parseVolumeLine(obj: JSONObject): ContainerVolumeSummary =
        ContainerVolumeSummary(
            name = obj.optString("Name"),
            driver = obj.optString("Driver"),
            mountpoint = obj.optString("Mountpoint")
        )

    /**
     * One `network ls --format '{{json .}}'` line. Docker capitalizes the
     * keys; Podman marshals containers/common types.Network with lowercase
     * "id"/"name"/"driver" tags and has no scope concept.
     */
    fun parseNetworkLine(obj: JSONObject): ContainerNetworkSummary =
        ContainerNetworkSummary(
            id = obj.optString("ID").ifEmpty { obj.optString("id") },
            name = obj.optString("Name").ifEmpty { obj.optString("name") },
            driver = obj.optString("Driver").ifEmpty { obj.optString("driver") },
            scope = obj.optString("Scope")
        )

    /**
     * One `stats --no-stream --format '{{json .}}'` line. Docker emits
     * formatted strings ("CPUPerc": "1.2%", "MemUsage": "10MiB / 1GiB");
     * Podman marshals define.ContainerStats with raw numbers ("CPU" percent,
     * "MemUsage"/"MemLimit" bytes) — both shapes are accepted.
     */
    fun parseStatsLine(obj: JSONObject): ContainerStats {
        if (!obj.has("CPUPerc") && obj.has("CPU")) return parsePodmanStatsLine(obj)
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

    /** Podman's raw-number stats shape, mapped into the same [ContainerStats]. */
    private fun parsePodmanStatsLine(obj: JSONObject): ContainerStats {
        // Podman 5 nests per-interface counters under "Network"; Podman 4
        // exposed flat "NetInput"/"NetOutput" totals instead.
        var netInput = 0L
        var netOutput = 0L
        val network = obj.optJSONObject("Network")
        if (network != null) {
            for (key in network.keys()) {
                val iface = network.optJSONObject(key) ?: continue
                netInput += iface.optLong("RxBytes")
                netOutput += iface.optLong("TxBytes")
            }
        } else {
            netInput = obj.optLong("NetInput")
            netOutput = obj.optLong("NetOutput")
        }
        return ContainerStats(
            cpuPercent = obj.optDouble("CPU", 0.0),
            memUsageBytes = obj.optLong("MemUsage"),
            memLimitBytes = obj.optLong("MemLimit"),
            memPercent = obj.optDouble("MemPerc", 0.0),
            netInputBytes = netInput,
            netOutputBytes = netOutput,
            blockReadBytes = obj.optLong("BlockInput"),
            blockWriteBytes = obj.optLong("BlockOutput"),
            pids = obj.optInt("PIDs")
        )
    }

    /**
     * One `system df --format '{{json .}}'` line. Podman's marshaler emits
     * the Docker-compat "TotalCount"/"Size"/"Reclaimable" keys plus exact
     * "RawSize"/"RawReclaimable" byte counts, which win when present.
     */
    fun parseSystemDfLine(obj: JSONObject): DiskUsageRow =
        DiskUsageRow(
            type = obj.optString("Type"),
            totalCount = obj.optString("TotalCount").toIntOrNull()
                ?: obj.optString("Total").toIntOrNull() ?: 0,
            active = obj.optString("Active").toIntOrNull() ?: 0,
            sizeBytes = if (obj.has("RawSize")) obj.optLong("RawSize")
            else parseSizeToBytes(obj.optString("Size")),
            reclaimableBytes = if (obj.has("RawReclaimable")) obj.optLong("RawReclaimable")
            else parseSizeToBytes(obj.optString("Reclaimable").substringBefore("(").trim())
        )

    /**
     * `version --format '{{json .}}'`. Docker always reports a Server block;
     * a socketless local Podman reports only Client — the Client block is a
     * valid answer for a working install, not a failure. Podman spells the
     * API field "APIVersion" where Docker uses "ApiVersion".
     */
    fun parseCliVersion(output: String): ContainerEngineVersion? {
        return try {
            val obj = JSONObject(output.trim())
            val block = obj.optJSONObject("Server") ?: obj.optJSONObject("Client") ?: return null
            ContainerEngineVersion(
                version = block.optString("Version"),
                apiVersion = block.optString("ApiVersion").ifEmpty { block.optString("APIVersion") },
                minApiVersion = block.optString("MinAPIVersion").ifEmpty { null }
            )
        } catch (e: Exception) {
            Logger.w("DockerCliParsers", "parseCliVersion: unparsable output (${output.length} chars): ${e.message}")
            null
        }
    }

    /**
     * `info --format '{{json .}}'` — single JSON object. Docker emits a flat
     * capitalized shape; Podman marshals define.Info as nested lowercase
     * "host"/"store"/"version" blocks, detected by the "host" object.
     */
    fun parseCliInfo(output: String): ContainerEngineInfo? {
        return try {
            val obj = JSONObject(output.trim())
            val podmanHost = obj.optJSONObject("host")
            if (podmanHost != null) return parsePodmanInfo(obj, podmanHost)
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

    /** Podman's nested `info` shape, mapped into the same [ContainerEngineInfo]. */
    private fun parsePodmanInfo(obj: JSONObject, host: JSONObject): ContainerEngineInfo {
        val store = obj.optJSONObject("store")
        val containerStore = store?.optJSONObject("containerStore")
        val distribution = host.optJSONObject("distribution")
        val operatingSystem = listOfNotNull(
            distribution?.optString("distribution")?.takeIf { it.isNotEmpty() },
            distribution?.optString("version")?.takeIf { it.isNotEmpty() }
        ).joinToString(" ").ifEmpty { host.optString("os") }
        return ContainerEngineInfo(
            name = host.optString("hostname"),
            serverVersion = obj.optJSONObject("version")?.optString("Version").orEmpty(),
            operatingSystem = operatingSystem,
            architecture = host.optString("arch"),
            containersTotal = containerStore?.optInt("number") ?: 0,
            containersRunning = containerStore?.optInt("running") ?: 0,
            containersPaused = containerStore?.optInt("paused") ?: 0,
            containersStopped = containerStore?.optInt("stopped") ?: 0,
            images = store?.optJSONObject("imageStore")?.optInt("number") ?: 0,
            memTotalBytes = host.optLong("memTotal"),
            ncpu = host.optInt("cpus")
        )
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

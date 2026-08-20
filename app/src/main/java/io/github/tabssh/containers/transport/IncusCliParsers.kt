package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.utils.logging.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure parsers for the `incus` / `lxc` CLI. No I/O and no Android
 * dependencies — everything here is unit-testable on the JVM, mirroring
 * [DockerCliParsers].
 *
 * Both binaries offer `--format json` on every listing and `query` for the
 * raw REST paths, so nothing here parses columnar output. The two shapes are:
 *  - `<cli> <thing> list --format json` — a bare JSON array of the same
 *    objects the REST API returns, without the sync envelope.
 *  - `<cli> query /1.0/...` — the bare `metadata` of the REST answer.
 *
 * Because both are the REST payload minus its envelope, the per-object
 * parsing lives once in [IncusApiParsers] and this object only unwraps.
 */
object IncusCliParsers {

    /** Parse a bare JSON array, or null when the output is not one. */
    fun jsonArray(output: String): JSONArray? {
        val trimmed = output.trim()
        if (!trimmed.startsWith("[")) return null
        return try {
            JSONArray(trimmed)
        } catch (e: Exception) {
            Logger.w("IncusCliParsers", "jsonArray: unparsable output (${trimmed.length} chars): ${e.message}")
            null
        }
    }

    /** Parse a bare JSON object, or null when the output is not one. */
    fun jsonObject(output: String): JSONObject? {
        val trimmed = output.trim()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            Logger.w("IncusCliParsers", "jsonObject: unparsable output (${trimmed.length} chars): ${e.message}")
            null
        }
    }

    /** `<cli> list --format json`. */
    fun parseInstanceList(output: String): List<ContainerSummary> =
        IncusApiParsers.instancesFrom(jsonArray(output))

    /** `<cli> image list --format json`. */
    fun parseImageList(output: String): List<ContainerImageSummary> =
        IncusApiParsers.imagesFrom(jsonArray(output))

    /** `<cli> network list --format json`. */
    fun parseNetworkList(output: String): List<ContainerNetworkSummary> =
        IncusApiParsers.networksFrom(jsonArray(output))

    /** `<cli> storage list --format json` — pool name to pool driver. */
    fun parseStoragePools(output: String): Map<String, String> =
        IncusApiParsers.storagePoolsFrom(jsonArray(output))

    /** `<cli> storage volume list <pool> --format json`. */
    fun parseVolumeList(output: String, pool: String, poolDriver: String): List<ContainerVolumeSummary> =
        IncusApiParsers.volumesFrom(jsonArray(output), pool, poolDriver)

    /** `<cli> query /1.0/instances/<name>/snapshots?recursion=1`. */
    fun parseSnapshotList(output: String, instance: String): List<ContainerSnapshotSummary> =
        IncusApiParsers.snapshotsFrom(jsonArray(output), instance)

    /** `<cli> profile list --format json`. */
    fun parseProfileList(output: String): List<ContainerProfileSummary> =
        IncusApiParsers.profilesFrom(jsonArray(output))

    /** `<cli> project list --format json`. */
    fun parseProjectList(output: String, active: String?): List<ContainerProjectSummary> =
        IncusApiParsers.projectsFrom(jsonArray(output), active)

    /** `<cli> query /1.0/instances/<name>/state`. */
    fun parseInstanceState(output: String): IncusApiParsers.InstanceStateSample? =
        IncusApiParsers.instanceStateFrom(jsonObject(output))

    /** `<cli> query /1.0`. */
    fun parseServerInfo(output: String): ContainerEngineInfo? =
        IncusApiParsers.serverInfoFrom(jsonObject(output))

    /** `<cli> query /1.0`. */
    fun parseServerVersion(output: String): ContainerEngineVersion? =
        IncusApiParsers.serverVersionFrom(jsonObject(output))

    /**
     * The instance `config` map from `<cli> query /1.0/instances/<name>`,
     * which is where the memory ceiling used to scale live stats lives.
     */
    fun parseInstanceConfig(output: String): Map<String, String> =
        IncusApiParsers.parseStringMap(jsonObject(output)?.optJSONObject("config"))

    /**
     * Classify a failed `incus`/`lxc` invocation. The engine-independent
     * patterns (permission denied, missing binary) are already handled by
     * [DockerCliParsers.classifyFailure]; this adds the phrasings only these
     * two engines produce, so the same taxonomy covers all four engines
     * instead of a parallel one growing here.
     */
    fun classifyFailure(
        context: String,
        stderr: String,
        stdout: String = "",
        engine: ContainerEngine = ContainerEngine.INCUS
    ): ContainerResult<Nothing> {
        val text = (stderr + "\n" + stdout).lowercase()
        return when {
            text.contains("not authorized") ||
                text.contains("you must be part of the incus group") ||
                text.contains("you must be part of the lxd group") ->
                ContainerResult.PermissionDenied(
                    ContainerTransportMessages.socketPermission(engine),
                    detail = "$context: ${stderr.trim().ifEmpty { stdout.trim() }}"
                )
            text.contains("error: not found") ||
                text.contains("instance not found") ||
                text.contains("no such object") ||
                text.contains("storage volume not found") ||
                text.contains("network not found") ||
                text.contains("image not found") ||
                text.contains("profile not found") ||
                text.contains("project not found") ->
                ContainerResult.NotFound(
                    context,
                    detail = stderr.trim().ifEmpty { stdout.trim() }
                )
            else -> DockerCliParsers.classifyFailure(context, stderr, stdout, engine)
        }
    }
}

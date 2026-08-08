package io.github.tabssh.docker.transport

/**
 * One row of a container listing. Populated from `GET /containers/json`
 * (Engine API) or `docker ps --format '{{json .}}'` (CLI) — both map to the
 * same summary shape so the UI never knows which tier served it.
 */
data class DockerContainerSummary(
    val id: String,
    val names: List<String>,
    val image: String,
    /** Engine state: created/running/paused/restarting/exited/dead. */
    val state: String,
    /** Human status line, e.g. "Up 2 hours (healthy)". */
    val status: String,
    /** Human creation time (CLI CreatedAt string or ISO from epoch). */
    val created: String,
    /** Port summary string, e.g. "0.0.0.0:8080->80/tcp". */
    val ports: String
)

/** One row of an image listing. */
data class DockerImageSummary(
    val id: String,
    /** repo:tag pairs; empty for dangling images. */
    val repoTags: List<String>,
    val sizeBytes: Long,
    val created: String
)

/** One row of a volume listing. */
data class DockerVolumeSummary(
    val name: String,
    val driver: String,
    val mountpoint: String
)

/** One row of a network listing. */
data class DockerNetworkSummary(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String
)

/** Engine information for the host dashboard (`/info` or `docker info`). */
data class DockerEngineInfo(
    val name: String,
    val serverVersion: String,
    val operatingSystem: String,
    val architecture: String,
    val containersTotal: Int,
    val containersRunning: Int,
    val containersPaused: Int,
    val containersStopped: Int,
    val images: Int,
    val memTotalBytes: Long,
    val ncpu: Int
)

/** Engine + API version (`/version` or `docker version --format`). */
data class DockerVersionInfo(
    val version: String,
    val apiVersion: String,
    val minApiVersion: String?
)

/** One live stats sample for a container. */
data class DockerContainerStats(
    val cpuPercent: Double,
    val memUsageBytes: Long,
    val memLimitBytes: Long,
    val memPercent: Double,
    val netInputBytes: Long,
    val netOutputBytes: Long,
    val blockReadBytes: Long,
    val blockWriteBytes: Long,
    val pids: Int
)

/** One row of the disk-usage dashboard (`/system/df` or `docker system df`). */
data class DiskUsageRow(
    /** Category: Images / Containers / Local Volumes / Build Cache. */
    val type: String,
    val totalCount: Int,
    val active: Int,
    val sizeBytes: Long,
    val reclaimableBytes: Long
)

/** Disk usage summary for the host dashboard. */
data class DockerDiskUsage(
    val rows: List<DiskUsageRow>
)

/**
 * One progress event of an image pull. Engine API pulls carry layer id and
 * byte counters; CLI pulls only carry the raw output line in [status].
 */
data class PullProgressEvent(
    val status: String,
    /** Layer id the event refers to; null for global events. */
    val layerId: String? = null,
    val currentBytes: Long = 0,
    val totalBytes: Long = 0,
    /** Non-null when the engine reported a pull error for this stream. */
    val error: String? = null
)

/** One entry of a remote directory listing. */
data class RemoteDirEntry(
    val name: String,
    val isDirectory: Boolean
)

/** Captured output of one remote command execution. */
data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitStatus: Int
) {
    /** True when the remote command exited 0. */
    val isSuccess: Boolean get() = exitStatus == 0
}

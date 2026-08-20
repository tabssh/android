package io.github.tabssh.containers.transport

/**
 * One row of a container listing. Populated from `GET /containers/json`
 * (Engine API) or `docker ps --format '{{json .}}'` (CLI) — both map to the
 * same summary shape so the UI never knows which tier served it.
 */
data class ContainerSummary(
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
    val ports: String,
    /**
     * Engine-assigned key/value metadata: Docker/Podman container labels, and
     * the instance `config` map on Incus and LXC/LXD, which is where those
     * engines carry the same kind of user metadata. Empty when the serving
     * tier reports none.
     */
    val labels: Map<String, String> = emptyMap()
)

/** One row of an image listing. */
data class ContainerImageSummary(
    val id: String,
    /** repo:tag pairs; empty for dangling images. */
    val repoTags: List<String>,
    val sizeBytes: Long,
    val created: String
)

/** One row of a volume listing. */
data class ContainerVolumeSummary(
    val name: String,
    val driver: String,
    val mountpoint: String
)

/** One row of a network listing. */
data class ContainerNetworkSummary(
    val id: String,
    val name: String,
    val driver: String,
    val scope: String
)

/**
 * One row of an instance's snapshot listing. Incus and LXC/LXD expose
 * snapshots as first-class children of an instance; [instance] carries the
 * parent so a flat listing can still act on the right one.
 */
data class ContainerSnapshotSummary(
    val name: String,
    val instance: String,
    /** Human creation time, or empty when the engine reports none. */
    val created: String,
    /** Human expiry time, or empty when the snapshot never expires. */
    val expires: String,
    /** True when the snapshot captured the instance's running memory state. */
    val stateful: Boolean
)

/** One row of a profile listing (Incus and LXC/LXD). */
data class ContainerProfileSummary(
    val name: String,
    val description: String,
    /** Device names the profile attaches, in engine order. */
    val devices: List<String>,
    /** Instance names currently using the profile. */
    val usedBy: List<String>
)

/** One row of a project listing (Incus and LXC/LXD). */
data class ContainerProjectSummary(
    val name: String,
    val description: String,
    /** Count of objects the engine reports as belonging to the project. */
    val usedByCount: Int,
    /** True when this project is the transport's active one. */
    val active: Boolean = false
)

/** Engine information for the host dashboard (`/info` or `docker info`). */
data class ContainerEngineInfo(
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
data class ContainerEngineVersion(
    val version: String,
    val apiVersion: String,
    val minApiVersion: String?
)

/** One live stats sample for a container. */
data class ContainerStats(
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
data class ContainerDiskUsage(
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

/**
 * One row of `docker compose ls --all --format json` — a compose project
 * discovered on the host regardless of whether it is tracked in Room
 * (external-stack discovery).
 */
data class ComposeLsEntry(
    val name: String,
    /** Human status, e.g. "running(2)" or "exited(1)". */
    val status: String,
    /** Absolute remote paths to the project's compose file(s). */
    val configFiles: List<String>
) {
    /** The primary compose file — first of possibly several `-f` layers. */
    val primaryConfigFile: String get() = configFiles.firstOrNull().orEmpty()
}

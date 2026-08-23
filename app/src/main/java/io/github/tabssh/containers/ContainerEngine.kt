package io.github.tabssh.containers

/**
 * A container engine TabSSH can manage over SSH.
 *
 * Declaration order is the order the engine dropdown shows (user decision,
 * 2026-08-19): Docker first and preselected, then Incus, Podman, LXC/LXD.
 *
 * Every engine reaches its daemon the same way — the hybrid transport of
 * IDEA.md: REST API over an SSH forward of [defaultSocketPaths], falling back
 * to the [cliBinary] over SSH exec. What differs per engine is the socket
 * location, the CLI verb set, and which concepts exist at all; the latter is
 * [capabilities], which is what the UI reads to decide whether a tab or an
 * action is shown. A tab for a concept the engine does not have is hidden,
 * never shown empty.
 */
enum class ContainerEngine(
    val id: String,
    val cliBinary: String,
    val defaultSocketPaths: List<String>,
    val capabilities: Set<EngineCapability>
) {
    DOCKER(
        id = "docker",
        cliBinary = "docker",
        defaultSocketPaths = listOf("/var/run/docker.sock"),
        capabilities = setOf(
            EngineCapability.CONTAINERS,
            EngineCapability.IMAGES,
            EngineCapability.VOLUMES,
            EngineCapability.NETWORKS,
            EngineCapability.COMPOSE_STACKS,
            EngineCapability.EXEC,
            EngineCapability.LOGS,
            EngineCapability.STATS,
            EngineCapability.DISK_USAGE
        )
    ),

    INCUS(
        id = "incus",
        cliBinary = "incus",
        // The upstream systemd units (incus.socket / incus-user.socket) both
        // listen under /run/incus, which is what every systemd-based install
        // actually creates; /var/lib/incus is kept as a fallback for older or
        // non-systemd source installs that predate the /run layout.
        defaultSocketPaths = listOf(
            "/run/incus/unix.socket",
            "/run/incus/unix.socket.user",
            "/var/lib/incus/unix.socket",
            "/var/lib/incus/unix.socket.user"
        ),
        capabilities = setOf(
            EngineCapability.CONTAINERS,
            EngineCapability.IMAGES,
            EngineCapability.VOLUMES,
            EngineCapability.NETWORKS,
            EngineCapability.EXEC,
            EngineCapability.LOGS,
            EngineCapability.STATS,
            EngineCapability.SNAPSHOTS,
            EngineCapability.PROFILES,
            EngineCapability.PROJECTS
        )
    ),

    PODMAN(
        id = "podman",
        cliBinary = "podman",
        // Rootful first; the rootless socket lives under the caller's XDG
        // runtime dir, which the probe expands remotely because $UID is only
        // known on the host.
        defaultSocketPaths = listOf(
            "/run/podman/podman.sock",
            "/run/user/\$(id -u)/podman/podman.sock"
        ),
        capabilities = setOf(
            EngineCapability.CONTAINERS,
            EngineCapability.IMAGES,
            EngineCapability.VOLUMES,
            EngineCapability.NETWORKS,
            EngineCapability.COMPOSE_STACKS,
            EngineCapability.EXEC,
            EngineCapability.LOGS,
            EngineCapability.STATS,
            EngineCapability.DISK_USAGE
        )
    ),

    LXD(
        id = "lxd",
        cliBinary = "lxc",
        // Snap is the only supported LXD install upstream still ships, so its
        // socket is probed first; the deb path stays for older hosts.
        defaultSocketPaths = listOf(
            "/var/snap/lxd/common/lxd/unix.socket",
            "/var/lib/lxd/unix.socket"
        ),
        capabilities = setOf(
            EngineCapability.CONTAINERS,
            EngineCapability.IMAGES,
            EngineCapability.VOLUMES,
            EngineCapability.NETWORKS,
            EngineCapability.EXEC,
            EngineCapability.LOGS,
            EngineCapability.STATS,
            EngineCapability.SNAPSHOTS,
            EngineCapability.PROFILES,
            EngineCapability.PROJECTS
        )
    );

    fun supports(capability: EngineCapability): Boolean = capability in capabilities

    /**
     * True when this engine speaks the Docker Engine REST API, so the existing
     * Docker API and CLI parsers apply unchanged. Podman ships a
     * Docker-compatible endpoint on its own socket; Incus and LXD do not — they
     * have their own REST dialect.
     */
    val speaksDockerApi: Boolean
        get() = this == DOCKER || this == PODMAN

    companion object {
        val DEFAULT = DOCKER

        /** Resolve a stored [id]; an unknown value falls back to [DEFAULT]. */
        fun fromId(id: String?): ContainerEngine =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * A concept an engine either has or does not have. The UI derives tab
 * visibility and action availability from these, so adding an engine never
 * means editing a `when` in a fragment.
 */
enum class EngineCapability {
    CONTAINERS,
    IMAGES,
    VOLUMES,
    NETWORKS,
    COMPOSE_STACKS,
    EXEC,
    LOGS,
    STATS,
    DISK_USAGE,
    SNAPSHOTS,
    PROFILES,
    PROJECTS
}

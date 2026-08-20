package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.containers.EngineCapability

/**
 * User-facing transport error and remediation text.
 *
 * Every message is a stable constant, and the engine-dependent ones are
 * selected by a function over [ContainerEngine] rather than built by
 * interpolation. That is deliberate: ContainerErrorPresenter maps the constant
 * it receives onto a string resource by exact identity, so a formatted string
 * would never match — and the remediation genuinely differs per engine anyway
 * (a Docker socket needs the `docker` group, a rootless Podman socket needs no
 * group at all, Incus needs `incus-admin`, LXC/LXD needs `lxd`).
 *
 * Nothing here is formatted — detail strings (paths, stderr) travel separately
 * in ContainerResult.detail.
 */
object ContainerTransportMessages {

    // ── Socket permission (per engine — the remediation is not the same) ─────

    const val SOCKET_PERMISSION_DOCKER =
        "Permission denied on the Docker socket. On the host, add your SSH " +
        "user to the docker group (sudo usermod -aG docker <user>), then log " +
        "out and back in — or connect as a user that is already in the " +
        "docker group. Afterwards use \"Retest transport\" on this host."

    const val SOCKET_PERMISSION_PODMAN =
        "Permission denied on the Podman socket. Rootless Podman uses a " +
        "per-user socket, so connect over SSH as the user that owns it and " +
        "enable it with \"systemctl --user enable --now podman.socket\"; for " +
        "the rootful socket connect as root instead. Afterwards use " +
        "\"Retest transport\" on this host."

    const val SOCKET_PERMISSION_INCUS =
        "Permission denied on the Incus socket. On the host, add your SSH " +
        "user to the incus-admin group (sudo usermod -aG incus-admin <user>), " +
        "then log out and back in — or connect as a user that is already in " +
        "that group. Afterwards use \"Retest transport\" on this host."

    const val SOCKET_PERMISSION_LXD =
        "Permission denied on the LXD socket. On the host, add your SSH user " +
        "to the lxd group (sudo usermod -aG lxd <user>), then log out and " +
        "back in — or connect as a user that is already in that group. " +
        "Afterwards use \"Retest transport\" on this host."

    /** Remediation shown when the SSH user cannot read/write [engine]'s socket. */
    fun socketPermission(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.DOCKER -> SOCKET_PERMISSION_DOCKER
        ContainerEngine.PODMAN -> SOCKET_PERMISSION_PODMAN
        ContainerEngine.INCUS -> SOCKET_PERMISSION_INCUS
        ContainerEngine.LXD -> SOCKET_PERMISSION_LXD
    }

    // ── Socket missing ───────────────────────────────────────────────────────

    const val SOCKET_MISSING_DOCKER =
        "No Docker socket was found at any of the paths tried. Check that " +
        "Docker is running on the host, or set an explicit socket path for " +
        "this host."

    const val SOCKET_MISSING_PODMAN =
        "No Podman socket was found at any of the paths tried. Start the " +
        "Podman API service (\"systemctl --user enable --now podman.socket\", " +
        "or the system unit for rootful Podman), or set an explicit socket " +
        "path for this host."

    const val SOCKET_MISSING_INCUS =
        "No Incus socket was found at any of the paths tried. Check that the " +
        "incus daemon is running on the host, or set an explicit socket path " +
        "for this host."

    const val SOCKET_MISSING_LXD =
        "No LXD socket was found at any of the paths tried. Check that the " +
        "lxd daemon is running on the host, or set an explicit socket path " +
        "for this host."

    /** Shown when none of [engine]'s candidate socket paths exists. */
    fun socketMissing(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.DOCKER -> SOCKET_MISSING_DOCKER
        ContainerEngine.PODMAN -> SOCKET_MISSING_PODMAN
        ContainerEngine.INCUS -> SOCKET_MISSING_INCUS
        ContainerEngine.LXD -> SOCKET_MISSING_LXD
    }

    // ── CLI missing ──────────────────────────────────────────────────────────

    const val CLI_MISSING_DOCKER =
        "The docker command was not found on the host. Install Docker, or set " +
        "an explicit CLI path for this host."

    const val CLI_MISSING_PODMAN =
        "The podman command was not found on the host. Install Podman, or set " +
        "an explicit CLI path for this host."

    const val CLI_MISSING_INCUS =
        "The incus command was not found on the host. Install Incus, or set " +
        "an explicit CLI path for this host."

    const val CLI_MISSING_LXD =
        "The lxc command was not found on the host. Install LXD, or set an " +
        "explicit CLI path for this host."

    /** Shown when [engine]'s CLI cannot be found on the remote PATH. */
    fun cliMissing(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.DOCKER -> CLI_MISSING_DOCKER
        ContainerEngine.PODMAN -> CLI_MISSING_PODMAN
        ContainerEngine.INCUS -> CLI_MISSING_INCUS
        ContainerEngine.LXD -> CLI_MISSING_LXD
    }

    // ── dial-stdio (Docker-API engines only) ─────────────────────────────────

    const val DIAL_STDIO_UNSUPPORTED_DOCKER =
        "The docker CLI on the host is missing or too old to support " +
        "\"docker system dial-stdio\" (needs Docker 18.09+). Install or " +
        "upgrade Docker, or rely on the CLI transport."

    const val DIAL_STDIO_UNSUPPORTED_PODMAN =
        "The podman CLI on the host does not support " +
        "\"podman system dial-stdio\". Upgrade Podman, or rely on the socket " +
        "forward or the CLI transport."

    /**
     * Shown when the dial-stdio tier is unusable. Only the Docker-API engines
     * have this tier at all — Incus and LXC/LXD never attempt it, so their
     * ladder never produces this message.
     */
    fun dialStdioUnsupported(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.PODMAN -> DIAL_STDIO_UNSUPPORTED_PODMAN
        else -> DIAL_STDIO_UNSUPPORTED_DOCKER
    }

    // ── Compose (Docker-API engines only) ────────────────────────────────────

    const val COMPOSE_MISSING_DOCKER =
        "Neither the docker compose plugin nor docker-compose is installed on " +
        "the host. Install the compose plugin to manage stacks."

    const val COMPOSE_MISSING_PODMAN =
        "Neither \"podman compose\" nor docker-compose is installed on the " +
        "host. Install podman-compose to manage stacks."

    /** Shown when [engine] has compose support but no compose binary. */
    fun composeMissing(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.PODMAN -> COMPOSE_MISSING_PODMAN
        else -> COMPOSE_MISSING_DOCKER
    }

    // ── Whole-ladder failure ─────────────────────────────────────────────────

    const val ALL_TIERS_FAILED_DOCKER =
        "No Docker transport is available on this host. Socket forwarding, " +
        "the dial-stdio relay, and the docker CLI all failed."

    const val ALL_TIERS_FAILED_PODMAN =
        "No Podman transport is available on this host. Socket forwarding, " +
        "the dial-stdio relay, and the podman CLI all failed."

    const val ALL_TIERS_FAILED_INCUS =
        "No Incus transport is available on this host. Socket forwarding and " +
        "the incus CLI both failed."

    const val ALL_TIERS_FAILED_LXD =
        "No LXD transport is available on this host. Socket forwarding and " +
        "the lxc CLI both failed."

    /** Shown when every tier in [engine]'s ladder failed during detection. */
    fun allTiersFailed(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.DOCKER -> ALL_TIERS_FAILED_DOCKER
        ContainerEngine.PODMAN -> ALL_TIERS_FAILED_PODMAN
        ContainerEngine.INCUS -> ALL_TIERS_FAILED_INCUS
        ContainerEngine.LXD -> ALL_TIERS_FAILED_LXD
    }

    // ── Endpoint overrides ───────────────────────────────────────────────────

    const val NETWORK_ENDPOINT_UNSUPPORTED_INCUS =
        "Incus hosts are reached over their unix socket only. A tcp:// or " +
        "ssh:// endpoint is a Docker concept — clear the socket override, or " +
        "enter the path of the incus unix socket on the host."

    const val NETWORK_ENDPOINT_UNSUPPORTED_LXD =
        "LXD hosts are reached over their unix socket only. A tcp:// or " +
        "ssh:// endpoint is a Docker concept — clear the socket override, or " +
        "enter the path of the lxd unix socket on the host."

    /** Shown when a `tcp://`/`ssh://` override is set on an engine without one. */
    fun networkEndpointUnsupported(engine: ContainerEngine): String = when (engine) {
        ContainerEngine.LXD -> NETWORK_ENDPOINT_UNSUPPORTED_LXD
        else -> NETWORK_ENDPOINT_UNSUPPORTED_INCUS
    }

    /** Shown when a `tcp://`/`ssh://` override cannot be parsed at all. */
    const val ENDPOINT_MALFORMED =
        "The endpoint override for this host is not a valid address. Use a " +
        "socket path, tcp://host:port, or ssh://user@host."

    // ── Shared ───────────────────────────────────────────────────────────────

    /**
     * Remediation shown when sshd refuses the direct-streamlocal channel.
     * JSch only surfaces this as a generic "channel is not opened." error, so
     * the hint names the two sshd_config options that must BOTH be enabled.
     */
    const val STREAMLOCAL_DENIED_REMEDIATION =
        "The SSH server refused the unix-socket forward. Ensure sshd_config " +
        "has BOTH AllowTcpForwarding yes AND AllowStreamLocalForwarding yes " +
        "(AllowTcpForwarding no silently blocks socket forwards too), then " +
        "restart sshd and use \"Retest transport\"."

    /** Remediation shown when sshd refuses the direct-tcpip forward. */
    const val TCP_FORWARD_DENIED_REMEDIATION =
        "The SSH server refused the TCP forward to the endpoint. Ensure " +
        "sshd_config has AllowTcpForwarding yes and that the address is " +
        "reachable from the host, then use \"Retest transport\"."

    /** Shown when the SSH session backing the transport is gone. */
    const val SSH_SESSION_UNAVAILABLE =
        "The SSH session for this container host is not connected."

    /**
     * Shown when an operation needs a concept the selected engine does not
     * have. The capability and engine travel in ContainerResult.detail
     * ([capabilityDetail]) rather than in the message, so one string covers
     * every combination without ever being a formatted near-match the
     * presenter cannot map back to a resource.
     */
    const val CAPABILITY_UNSUPPORTED =
        "This engine does not support that feature, so the request was not " +
        "sent to the host."

    /** Detail line naming the missing [capability] and the [engine] lacking it. */
    fun capabilityDetail(engine: ContainerEngine, capability: EngineCapability): String =
        "${capability.name} is not available on ${engine.id}"
}

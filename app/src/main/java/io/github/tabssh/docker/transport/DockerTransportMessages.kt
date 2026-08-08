package io.github.tabssh.docker.transport

/**
 * User-facing transport error and remediation text, kept in one place so the
 * UI phase can move it to strings.xml wholesale. Nothing here is formatted —
 * detail strings (paths, stderr) travel separately in DockerResult.detail.
 */
object DockerTransportMessages {

    /** Remediation shown when the SSH user cannot read/write the socket. */
    const val SOCKET_PERMISSION_REMEDIATION =
        "Permission denied on the Docker socket. On the host, add your SSH " +
        "user to the docker group (sudo usermod -aG docker <user>), then log " +
        "out and back in — or connect as a user that is already in the " +
        "docker group. Afterwards use \"Retest transport\" on this host."

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

    /** Shown when the Docker socket does not exist at the configured path. */
    const val SOCKET_MISSING =
        "The Docker socket was not found at the configured path. Check that " +
        "Docker is running on the host and that the socket path is correct."

    /** Shown when `docker system dial-stdio --help` fails or the docker CLI is missing. */
    const val DIAL_STDIO_UNSUPPORTED =
        "The docker CLI on the host is missing or too old to support " +
        "\"docker system dial-stdio\" (needs Docker 18.09+). Install or " +
        "upgrade Docker, or rely on the CLI transport."

    /** Shown when the docker CLI cannot be found on the remote PATH. */
    const val DOCKER_CLI_MISSING =
        "The docker command was not found on the host. Install Docker, or set " +
        "an explicit docker CLI path for this host."

    /** Shown when neither compose plugin nor docker-compose exists. */
    const val COMPOSE_MISSING =
        "Neither the docker compose plugin nor docker-compose is installed on " +
        "the host. Install the compose plugin to manage stacks."

    /** Shown when the SSH session backing the transport is gone. */
    const val SSH_SESSION_UNAVAILABLE =
        "The SSH session for this Docker host is not connected."

    /** Shown when every transport tier failed during detection. */
    const val ALL_TIERS_FAILED =
        "No Docker transport is available on this host. Socket forwarding, " +
        "the dial-stdio relay, and the docker CLI all failed."
}

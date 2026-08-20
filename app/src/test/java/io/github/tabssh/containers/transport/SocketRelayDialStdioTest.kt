package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure decision logic for the api_stdio tier's `<cli> system dial-stdio`
 * command construction ([SocketRelay.buildDialStdioCommand]) — no SSH session
 * required. The endpoint environment assignment is engine-specific, so each
 * engine that has the verb is exercised separately.
 */
class SocketRelayDialStdioTest {

    @Test
    fun `docker default socket omits the endpoint prefix`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "docker",
            ContainerEndpoint.UnixSocket("/var/run/docker.sock")
        )

        assertEquals("docker system dial-stdio", command)
    }

    @Test
    fun `docker custom socket adds a shell-quoted DOCKER_HOST prefix`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "docker",
            ContainerEndpoint.UnixSocket("/run/user/1000/docker.sock")
        )

        assertEquals(
            "DOCKER_HOST='unix:///run/user/1000/docker.sock' docker system dial-stdio",
            command
        )
    }

    @Test
    fun `custom cli path is interpolated unquoted`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "/opt/docker/bin/docker",
            ContainerEndpoint.UnixSocket("/var/run/docker.sock")
        )

        assertEquals("/opt/docker/bin/docker system dial-stdio", command)
    }

    @Test
    fun `socket path with a single quote is shell-escaped`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "docker",
            ContainerEndpoint.UnixSocket("/tmp/it's/docker.sock")
        )

        assertEquals(
            "DOCKER_HOST='unix:///tmp/it'\\''s/docker.sock' docker system dial-stdio",
            command
        )
    }

    @Test
    fun `podman default socket omits the endpoint prefix`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.PODMAN,
            "podman",
            ContainerEndpoint.UnixSocket("/run/podman/podman.sock")
        )

        assertEquals("podman system dial-stdio", command)
    }

    @Test
    fun `podman rootless socket uses CONTAINER_HOST, not DOCKER_HOST`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.PODMAN,
            "podman",
            ContainerEndpoint.UnixSocket("/run/user/1000/podman/podman.sock")
        )

        assertEquals(
            "CONTAINER_HOST='unix:///run/user/1000/podman/podman.sock' podman system dial-stdio",
            command
        )
    }

    @Test
    fun `nested ssh endpoint is passed through as an ssh url`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "docker",
            ContainerEndpoint.NestedSsh("deploy@inner")
        )

        assertEquals("DOCKER_HOST='ssh://deploy@inner' docker system dial-stdio", command)
    }

    @Test
    fun `tcp endpoint is passed through as a tcp url`() {
        val command = SocketRelay.buildDialStdioCommand(
            ContainerEngine.DOCKER,
            "docker",
            ContainerEndpoint.TcpForward("127.0.0.1", 2375)
        )

        assertEquals("DOCKER_HOST='tcp://127.0.0.1:2375' docker system dial-stdio", command)
    }
}

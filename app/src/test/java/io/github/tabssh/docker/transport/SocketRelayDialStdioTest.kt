package io.github.tabssh.docker.transport

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure decision logic for the tier-b `docker system dial-stdio` command
 * construction ([SocketRelay.buildDialStdioCommand]) — no SSH session
 * required.
 */
class SocketRelayDialStdioTest {

    @Test
    fun `default socket path omits DOCKER_HOST prefix`() {
        val command = SocketRelay.buildDialStdioCommand("docker", "/var/run/docker.sock")

        assertEquals("docker system dial-stdio", command)
    }

    @Test
    fun `blank socket path omits DOCKER_HOST prefix`() {
        val command = SocketRelay.buildDialStdioCommand("docker", "")

        assertEquals("docker system dial-stdio", command)
    }

    @Test
    fun `custom socket path adds shell-quoted DOCKER_HOST prefix`() {
        val command = SocketRelay.buildDialStdioCommand("docker", "/run/user/1000/docker.sock")

        assertEquals(
            "DOCKER_HOST=unix://'/run/user/1000/docker.sock' docker system dial-stdio",
            command
        )
    }

    @Test
    fun `custom docker cli path is interpolated unquoted`() {
        val command = SocketRelay.buildDialStdioCommand("/opt/docker/bin/docker", "/var/run/docker.sock")

        assertEquals("/opt/docker/bin/docker system dial-stdio", command)
    }

    @Test
    fun `custom socket path with single quote is shell-escaped`() {
        val command = SocketRelay.buildDialStdioCommand("docker", "/tmp/it's/docker.sock")

        assertEquals(
            "DOCKER_HOST=unix://'/tmp/it'\\''s/docker.sock' docker system dial-stdio",
            command
        )
    }
}

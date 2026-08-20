package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.entities.ContainerHost
import org.junit.Test
import kotlin.test.assertEquals

/**
 * [EngineCliContext] — the per-engine endpoint environment assignment that
 * steers a remote CLI invocation, and the binary that invocation runs. Pure
 * companion logic; no SSH session required.
 */
class EngineCliContextTest {

    @Test
    fun `each engine reads its own endpoint variable`() {
        assertEquals("DOCKER_HOST", EngineCliContext.endpointEnvVar(ContainerEngine.DOCKER))
        assertEquals("CONTAINER_HOST", EngineCliContext.endpointEnvVar(ContainerEngine.PODMAN))
        assertEquals("INCUS_SOCKET", EngineCliContext.endpointEnvVar(ContainerEngine.INCUS))
        assertEquals("LXD_SOCKET", EngineCliContext.endpointEnvVar(ContainerEngine.LXD))
    }

    @Test
    fun `docker api engines take a url value, incus and lxd take a bare path`() {
        val socket = ContainerEndpoint.UnixSocket("/tmp/engine.sock")

        assertEquals(
            "unix:///tmp/engine.sock",
            EngineCliContext.endpointEnvValue(ContainerEngine.DOCKER, socket)
        )
        assertEquals(
            "unix:///tmp/engine.sock",
            EngineCliContext.endpointEnvValue(ContainerEngine.PODMAN, socket)
        )
        assertEquals(
            "/tmp/engine.sock",
            EngineCliContext.endpointEnvValue(ContainerEngine.INCUS, socket)
        )
        assertEquals(
            "/tmp/engine.sock",
            EngineCliContext.endpointEnvValue(ContainerEngine.LXD, socket)
        )
    }

    @Test
    fun `the engine's own first default needs no prefix`() {
        for (engine in ContainerEngine.entries) {
            val default = ContainerEndpoint.UnixSocket(engine.defaultSocketPaths.first())

            assertEquals(
                "",
                EngineCliContext.buildEnvPrefix(engine, default),
                "${engine.id} steered its CLI at the socket it already uses"
            )
        }
    }

    @Test
    fun `a secondary default still gets an explicit prefix`() {
        val prefix = EngineCliContext.buildEnvPrefix(
            ContainerEngine.INCUS,
            ContainerEndpoint.UnixSocket("/var/lib/incus/unix.socket.user")
        )

        assertEquals("INCUS_SOCKET='/var/lib/incus/unix.socket.user' ", prefix)
    }

    @Test
    fun `a prefix value is shell-quoted against injection`() {
        val prefix = EngineCliContext.buildEnvPrefix(
            ContainerEngine.DOCKER,
            ContainerEndpoint.UnixSocket("/tmp/a b; rm -rf /")
        )

        assertEquals("DOCKER_HOST='unix:///tmp/a b; rm -rf /' ", prefix)
    }

    @Test
    fun `network endpoints keep their scheme in the prefix`() {
        assertEquals(
            "DOCKER_HOST='tcp://10.0.0.2:2375' ",
            EngineCliContext.buildEnvPrefix(
                ContainerEngine.DOCKER,
                ContainerEndpoint.TcpForward("10.0.0.2", 2375)
            )
        )
        assertEquals(
            "DOCKER_HOST='ssh://deploy@inner' ",
            EngineCliContext.buildEnvPrefix(
                ContainerEngine.DOCKER,
                ContainerEndpoint.NestedSsh("deploy@inner")
            )
        )
    }

    @Test
    fun `the binary is the per-host override when set, the engine's own otherwise`() {
        val runner = SshExecRunner { null }
        val plain = ContainerHost(id = 1L, name = "test", engine = ContainerEngine.LXD.id)
        val overridden = plain.copy(engineCliPath = "/snap/bin/lxc")

        assertEquals("lxc", EngineCliContext(plain, EngineSocketResolver(plain, runner)).binary)
        assertEquals(
            "/snap/bin/lxc",
            EngineCliContext(overridden, EngineSocketResolver(overridden, runner)).binary
        )
    }
}

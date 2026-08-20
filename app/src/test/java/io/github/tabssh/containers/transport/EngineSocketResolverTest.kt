package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import io.github.tabssh.storage.database.entities.ContainerHost
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EngineSocketResolver] — endpoint classification, `tcp://`/`ssh://` parsing,
 * and the one-shot remote socket sweep's command construction and output
 * parsing. Everything here is pure except the last group, which needs only a
 * runner with no session to prove the failure path.
 */
class EngineSocketResolverTest {

    private fun host(
        engine: ContainerEngine = ContainerEngine.DOCKER,
        socketPath: String = ""
    ): ContainerHost = ContainerHost(
        id = 1L,
        name = "test",
        engine = engine.id,
        socketPath = socketPath
    )

    /** No live SSH session — every exec fails before it reaches a shell. */
    private fun noSessionRunner(): SshExecRunner = SshExecRunner { null }

    @Test
    fun `blank and absolute paths classify as unix`() {
        assertEquals(ContainerEndpointKind.UNIX, EngineSocketResolver.classify(""))
        assertEquals(
            ContainerEndpointKind.UNIX,
            EngineSocketResolver.classify("/var/run/docker.sock")
        )
    }

    @Test
    fun `tcp and ssh prefixes classify as network endpoints`() {
        assertEquals(
            ContainerEndpointKind.TCP,
            EngineSocketResolver.classify("tcp://10.0.0.2:2375")
        )
        assertEquals(
            ContainerEndpointKind.SSH,
            EngineSocketResolver.classify("ssh://deploy@inner")
        )
    }

    @Test
    fun `tcp authority parses host and port`() {
        val parsed = EngineSocketResolver.parseTcp("tcp://10.0.0.2:2375")

        assertEquals(ContainerEndpoint.TcpForward("10.0.0.2", 2375), parsed)
    }

    @Test
    fun `ipv6 literal keeps its brackets out of the host part`() {
        val parsed = EngineSocketResolver.parseTcp("tcp://[::1]:2375")

        assertEquals(ContainerEndpoint.TcpForward("::1", 2375), parsed)
    }

    @Test
    fun `malformed tcp authorities are rejected rather than guessed`() {
        assertNull(EngineSocketResolver.parseTcp("tcp://"))
        assertNull(EngineSocketResolver.parseTcp("tcp://10.0.0.2"))
        assertNull(EngineSocketResolver.parseTcp("tcp://10.0.0.2:"))
        assertNull(EngineSocketResolver.parseTcp("tcp://10.0.0.2:notaport"))
        assertNull(EngineSocketResolver.parseTcp("tcp://10.0.0.2:0"))
        assertNull(EngineSocketResolver.parseTcp("tcp://10.0.0.2:70000"))
        assertNull(EngineSocketResolver.parseTcp("tcp://[::1]2375"))
    }

    @Test
    fun `ssh target strips the scheme and rejects an empty target`() {
        assertEquals("deploy@inner", EngineSocketResolver.parseSshTarget("ssh://deploy@inner"))
        assertNull(EngineSocketResolver.parseSshTarget("ssh://"))
    }

    @Test
    fun `trusted candidates keep their shell expansion`() {
        val command = EngineSocketResolver.buildSocketProbeCommand(
            ContainerEngine.PODMAN.defaultSocketPaths,
            trusted = true
        )

        // Podman's rootless socket lives under the caller's runtime dir, so
        // the $(id -u) must reach the remote shell unquoted to expand there.
        assertTrue("/run/user/\$(id -u)/podman/podman.sock" in command)
        assertTrue("[ -S \"\$p\" ]" in command)
    }

    @Test
    fun `untrusted candidates are shell-quoted`() {
        val command = EngineSocketResolver.buildSocketProbeCommand(
            listOf("/tmp/\$(rm -rf /)/docker.sock"),
            trusted = false
        )

        assertTrue("'/tmp/\$(rm -rf /)/docker.sock'" in command)
    }

    @Test
    fun `probe output parses each of the three states`() {
        assertEquals(
            EngineSocketResolver.SocketProbe.Ok("/var/run/docker.sock"),
            EngineSocketResolver.parseSocketProbeOutput("ok\n/var/run/docker.sock\n")
        )
        assertEquals(
            EngineSocketResolver.SocketProbe.Denied("/run/podman/podman.sock"),
            EngineSocketResolver.parseSocketProbeOutput("denied\n/run/podman/podman.sock\n")
        )
        assertEquals(
            EngineSocketResolver.SocketProbe.Missing,
            EngineSocketResolver.parseSocketProbeOutput("missing\n\n")
        )
    }

    @Test
    fun `unrecognised probe output is treated as missing`() {
        assertEquals(
            EngineSocketResolver.SocketProbe.Missing,
            EngineSocketResolver.parseSocketProbeOutput("bash: syntax error near unexpected token")
        )
        assertEquals(
            EngineSocketResolver.SocketProbe.Missing,
            EngineSocketResolver.parseSocketProbeOutput("ok\n")
        )
        assertEquals(
            EngineSocketResolver.SocketProbe.Missing,
            EngineSocketResolver.parseSocketProbeOutput("")
        )
    }

    @Test
    fun `tcp override resolves without touching the remote host`() = runTest {
        val resolver = EngineSocketResolver(
            host(socketPath = "tcp://10.0.0.2:2375"),
            noSessionRunner()
        )

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.Success)
        assertEquals(ContainerEndpoint.TcpForward("10.0.0.2", 2375), result.valueOrNull())
    }

    @Test
    fun `ssh override resolves to a nested target`() = runTest {
        val resolver = EngineSocketResolver(
            host(socketPath = "ssh://deploy@inner"),
            noSessionRunner()
        )

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.Success)
        assertEquals(ContainerEndpoint.NestedSsh("deploy@inner"), result.valueOrNull())
    }

    @Test
    fun `malformed tcp override is a configuration error`() = runTest {
        val resolver = EngineSocketResolver(host(socketPath = "tcp://"), noSessionRunner())

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.Error)
        assertEquals(ContainerTransportMessages.ENDPOINT_MALFORMED, result.message)
    }

    @Test
    fun `incus rejects a tcp endpoint with its own remediation`() = runTest {
        val resolver = EngineSocketResolver(
            host(engine = ContainerEngine.INCUS, socketPath = "tcp://10.0.0.2:8443"),
            noSessionRunner()
        )

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.TransportUnavailable)
        assertEquals(
            ContainerTransportMessages.NETWORK_ENDPOINT_UNSUPPORTED_INCUS,
            result.message
        )
    }

    @Test
    fun `lxd rejects an ssh endpoint with its own remediation`() = runTest {
        val resolver = EngineSocketResolver(
            host(engine = ContainerEngine.LXD, socketPath = "ssh://deploy@inner"),
            noSessionRunner()
        )

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.TransportUnavailable)
        assertEquals(ContainerTransportMessages.NETWORK_ENDPOINT_UNSUPPORTED_LXD, result.message)
    }

    @Test
    fun `a unix probe with no session reports the session, not a missing socket`() = runTest {
        val resolver = EngineSocketResolver(host(), noSessionRunner())

        val result = resolver.resolve()

        assertTrue(result is ContainerResult.TransportUnavailable)
        assertEquals(ContainerTransportMessages.SSH_SESSION_UNAVAILABLE, result.message)
    }

    @Test
    fun `a resolved endpoint is cached for the life of the resolver`() = runTest {
        val resolver = EngineSocketResolver(
            host(socketPath = "tcp://10.0.0.2:2375"),
            noSessionRunner()
        )

        val first = resolver.resolve()
        val second = resolver.resolve()

        assertTrue(first is ContainerResult.Success)
        assertTrue(second is ContainerResult.Success)
        assertEquals(first.valueOrNull(), second.valueOrNull())
    }

    @Test
    fun `endpoint kind is decided without probing`() {
        assertEquals(ContainerEndpointKind.UNIX, EngineSocketResolver(host(), noSessionRunner()).kind)
        assertEquals(
            ContainerEndpointKind.TCP,
            EngineSocketResolver(host(socketPath = "tcp://h:1"), noSessionRunner()).kind
        )
    }
}

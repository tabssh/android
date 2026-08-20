package io.github.tabssh.containers.transport

import io.github.tabssh.containers.ContainerEngine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stderr → ContainerResult failure-class mapping for the CLI tier, including
 * the engine-dependent remediation each failure carries.
 */
class ContainerResultMappingTest {

    @Test
    fun `permission denied stderr maps to PermissionDenied with remediation`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Got permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock"
        )

        assertTrue(result is ContainerResult.PermissionDenied)
        assertEquals(
            ContainerTransportMessages.socketPermission(ContainerEngine.DOCKER),
            result.message
        )
        assertTrue(result.detail.orEmpty().contains("docker.sock"))
    }

    @Test
    fun `permission denied carries the engine's own remediation`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Error: permission denied while trying to connect to /var/lib/incus/unix.socket",
            engine = ContainerEngine.INCUS
        )

        assertTrue(result is ContainerResult.PermissionDenied)
        assertEquals(ContainerTransportMessages.SOCKET_PERMISSION_INCUS, result.message)
    }

    @Test
    fun `no such object stderr maps to NotFound`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to inspect container",
            "Error: No such object: deadbeef"
        )

        assertTrue(result is ContainerResult.NotFound)
        assertEquals("Failed to inspect container", result.message)
    }

    @Test
    fun `no such container stderr maps to NotFound`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to stop container",
            "Error response from daemon: No such container: web"
        )

        assertTrue(result is ContainerResult.NotFound)
    }

    @Test
    fun `docker binary missing maps to EngineNotInstalled`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "sh: docker: command not found"
        )

        assertTrue(result is ContainerResult.EngineNotInstalled)
        assertEquals(ContainerTransportMessages.CLI_MISSING_DOCKER, result.message)
    }

    @Test
    fun `missing binary names the engine that is actually missing`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "sh: lxc: not found",
            engine = ContainerEngine.LXD
        )

        assertTrue(result is ContainerResult.EngineNotInstalled)
        assertEquals(ContainerTransportMessages.CLI_MISSING_LXD, result.message)
    }

    @Test
    fun `podman socket unreachable maps to TransportUnavailable`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Cannot connect to the Podman socket: is the podman service running?",
            engine = ContainerEngine.PODMAN
        )

        assertTrue(result is ContainerResult.TransportUnavailable)
        assertEquals("Failed to list containers", result.message)
    }

    @Test
    fun `daemon unreachable maps to TransportUnavailable`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?"
        )

        assertTrue(result is ContainerResult.TransportUnavailable)
    }

    @Test
    fun `unclassified stderr maps to generic Error with detail`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to remove image",
            "Error response from daemon: conflict: unable to remove repository reference"
        )

        assertTrue(result is ContainerResult.Error)
        assertEquals("Failed to remove image", result.message)
        assertTrue(result.detail.orEmpty().contains("conflict"))
    }

    @Test
    fun `classification falls back to stdout when stderr is empty`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to inspect volume",
            "",
            "Error: No such volume: data"
        )

        assertTrue(result is ContainerResult.NotFound)
        assertEquals("Error: No such volume: data", result.detail)
    }

    @Test
    fun `result map transforms success and passes failures through`() {
        val success: ContainerResult<Int> = ContainerResult.Success(21)
        val mapped = success.map { it * 2 }
        assertEquals(42, mapped.valueOrNull())

        val failure: ContainerResult<Int> = ContainerResult.NotFound("gone")
        assertTrue(failure.map { it * 2 } is ContainerResult.NotFound)
    }
}

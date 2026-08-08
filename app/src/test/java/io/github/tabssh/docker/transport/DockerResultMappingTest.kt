package io.github.tabssh.docker.transport

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stderr → DockerResult failure-class mapping for the CLI tier.
 */
class DockerResultMappingTest {

    @Test
    fun `permission denied stderr maps to PermissionDenied with remediation`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Got permission denied while trying to connect to the Docker daemon socket at unix:///var/run/docker.sock"
        )

        assertTrue(result is DockerResult.PermissionDenied)
        assertEquals(DockerTransportMessages.SOCKET_PERMISSION_REMEDIATION, result.message)
        assertTrue(result.detail.orEmpty().contains("docker.sock"))
    }

    @Test
    fun `no such object stderr maps to NotFound`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to inspect container",
            "Error: No such object: deadbeef"
        )

        assertTrue(result is DockerResult.NotFound)
        assertEquals("Failed to inspect container", result.message)
    }

    @Test
    fun `no such container stderr maps to NotFound`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to stop container",
            "Error response from daemon: No such container: web"
        )

        assertTrue(result is DockerResult.NotFound)
    }

    @Test
    fun `docker binary missing maps to TransportUnavailable`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "sh: docker: command not found"
        )

        assertTrue(result is DockerResult.TransportUnavailable)
        assertEquals(DockerTransportMessages.DOCKER_CLI_MISSING, result.message)
    }

    @Test
    fun `daemon unreachable maps to TransportUnavailable`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to list containers",
            "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?"
        )

        assertTrue(result is DockerResult.TransportUnavailable)
    }

    @Test
    fun `unclassified stderr maps to generic Error with detail`() {
        val result = DockerCliParsers.classifyFailure(
            "Failed to remove image",
            "Error response from daemon: conflict: unable to remove repository reference"
        )

        assertTrue(result is DockerResult.Error)
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

        assertTrue(result is DockerResult.NotFound)
        assertEquals("Error: No such volume: data", result.detail)
    }

    @Test
    fun `result map transforms success and passes failures through`() {
        val success: DockerResult<Int> = DockerResult.Success(21)
        val mapped = success.map { it * 2 }
        assertEquals(42, mapped.valueOrNull())

        val failure: DockerResult<Int> = DockerResult.NotFound("gone")
        assertTrue(failure.map { it * 2 } is DockerResult.NotFound)
    }
}

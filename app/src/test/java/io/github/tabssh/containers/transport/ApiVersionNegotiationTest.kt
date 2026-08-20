package io.github.tabssh.containers.transport

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine API version negotiation — negotiated = min(client ceiling, server).
 */
class ApiVersionNegotiationTest {

    @Test
    fun `server older than client ceiling wins`() {
        assertEquals("1.41", DockerApiParsers.negotiateApiVersion("1.43", "1.41"))
    }

    @Test
    fun `server newer than client ceiling is capped at ceiling`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.45"))
    }

    @Test
    fun `equal versions negotiate to themselves`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.43"))
    }

    @Test
    fun `missing server version falls back to client ceiling`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", null))
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", ""))
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "   "))
    }

    @Test
    fun `server minimum above client ceiling raises negotiated version`() {
        // Docker 29: ApiVersion 1.54, MinAPIVersion 1.44 — a 1.43 request
        // would be rejected with HTTP 400, so negotiation lifts to the min.
        assertEquals("1.44", DockerApiParsers.negotiateApiVersion("1.43", "1.54", "1.44"))
    }

    @Test
    fun `server minimum below negotiated version changes nothing`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.54", "1.24"))
        assertEquals("1.41", DockerApiParsers.negotiateApiVersion("1.43", "1.41", "1.24"))
    }

    @Test
    fun `server minimum above server maximum is ignored as malformed`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.54", "1.60"))
    }

    @Test
    fun `blank server minimum falls back to plain negotiation`() {
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.54", ""))
        assertEquals("1.43", DockerApiParsers.negotiateApiVersion("1.43", "1.54", null))
    }

    @Test
    fun `comparison is numeric not lexicographic`() {
        // Lexicographic comparison would order "1.9" after "1.43".
        assertTrue(DockerApiParsers.compareApiVersions("1.9", "1.43") < 0)
        assertTrue(DockerApiParsers.compareApiVersions("1.43", "1.9") > 0)
        assertEquals("1.9", DockerApiParsers.negotiateApiVersion("1.43", "1.9"))
    }

    @Test
    fun `comparison handles differing segment counts`() {
        assertEquals(0, DockerApiParsers.compareApiVersions("1.43", "1.43.0"))
        assertTrue(DockerApiParsers.compareApiVersions("1.43.1", "1.43") > 0)
    }

    @Test
    fun `version response body parses`() {
        val fixture =
            """{"Platform":{"Name":"Docker Engine - Community"},"Version":"24.0.7","ApiVersion":"1.43","MinAPIVersion":"1.12","Os":"linux","Arch":"amd64"}"""

        val info = DockerApiParsers.parseVersion(fixture)

        assertEquals("24.0.7", info?.version)
        assertEquals("1.43", info?.apiVersion)
        assertEquals("1.12", info?.minApiVersion)
    }
}

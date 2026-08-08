package io.github.tabssh.docker.transport

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NDJSON pull-progress parsing for `POST /images/create`.
 */
class PullProgressParserTest {

    @Test
    fun `parses layer download progress with byte counters`() {
        val line =
            """{"status":"Downloading","progressDetail":{"current":539687,"total":3622892},"progress":"[=======>   ]","id":"a3ed95caeb02"}"""

        val event = DockerApiParsers.parsePullProgressLine(line)

        assertEquals("Downloading", event?.status)
        assertEquals("a3ed95caeb02", event?.layerId)
        assertEquals(539_687L, event?.currentBytes)
        assertEquals(3_622_892L, event?.totalBytes)
        assertNull(event?.error)
    }

    @Test
    fun `parses global status events without layer id`() {
        val line = """{"status":"Status: Downloaded newer image for nginx:1.27"}"""

        val event = DockerApiParsers.parsePullProgressLine(line)

        assertEquals("Status: Downloaded newer image for nginx:1.27", event?.status)
        assertNull(event?.layerId)
        assertEquals(0L, event?.currentBytes)
    }

    @Test
    fun `parses engine pull errors`() {
        val line =
            """{"errorDetail":{"message":"manifest unknown"},"error":"manifest unknown"}"""

        val event = DockerApiParsers.parsePullProgressLine(line)

        assertEquals("manifest unknown", event?.error)
    }

    @Test
    fun `skips blank and non json lines`() {
        assertNull(DockerApiParsers.parsePullProgressLine(""))
        assertNull(DockerApiParsers.parsePullProgressLine("   "))
        assertNull(DockerApiParsers.parsePullProgressLine("not json"))
    }

    @Test
    fun `full pull stream parses in order`() {
        val stream = """
            {"status":"Pulling from library/nginx","id":"1.27"}
            {"status":"Pulling fs layer","progressDetail":{},"id":"aaa"}
            {"status":"Downloading","progressDetail":{"current":10,"total":100},"id":"aaa"}
            {"status":"Pull complete","progressDetail":{},"id":"aaa"}
            {"status":"Digest: sha256:deadbeef"}
        """.trimIndent()

        val events = stream.lines().mapNotNull { DockerApiParsers.parsePullProgressLine(it) }

        assertEquals(5, events.size)
        assertEquals("Pulling from library/nginx", events[0].status)
        assertEquals(10L, events[2].currentBytes)
        assertEquals(100L, events[2].totalBytes)
        assertTrue(events.last().status.startsWith("Digest:"))
    }
}

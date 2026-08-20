package io.github.tabssh.containers.transport

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hostile-input behavior of the Engine API stream helpers: frame lengths that
 * a daemon (or anything else answering on the relay port) can set freely, and
 * NDJSON lines with no terminator.
 */
class DockerApiStreamParsersTest {

    /** Build one multiplexed log frame with an explicit declared length. */
    private fun frame(stream: Int, declaredLength: Long, payload: ByteArray): ByteArray {
        val head = ByteArray(8)
        head[0] = stream.toByte()
        head[4] = ((declaredLength shr 24) and 0xFF).toByte()
        head[5] = ((declaredLength shr 16) and 0xFF).toByte()
        head[6] = ((declaredLength shr 8) and 0xFF).toByte()
        head[7] = (declaredLength and 0xFF).toByte()
        return head + payload
    }

    @Test
    fun `framed log stream is demultiplexed into lines`() {
        val payload = "hello\nworld\n".toByteArray()
        val input = ByteArrayInputStream(frame(1, payload.size.toLong(), payload))
        val lines = mutableListOf<String>()
        DockerApiParsers.decodeLogStream(input) { lines += it }
        assertEquals(listOf("hello", "world"), lines)
    }

    @Test
    fun `frame length with the high bit set does not blow up`() {
        // 0x80000000 read as a signed Int is negative — ByteArray(length) would
        // throw NegativeArraySizeException; as an unsigned value it would be a
        // 2 GiB allocation. Neither may happen: the short payload is emitted
        // and the decoder stops at EOF.
        val payload = "partial line".toByteArray()
        val input = ByteArrayInputStream(frame(1, 0x80000000L, payload))
        val lines = mutableListOf<String>()
        DockerApiParsers.decodeLogStream(input) { lines += it }
        assertEquals(listOf("partial line"), lines)
    }

    @Test
    fun `frame length larger than the payload ends at eof`() {
        val payload = "a\nb\n".toByteArray()
        val input = ByteArrayInputStream(frame(1, 0xFFFFFFFFL, payload))
        val lines = mutableListOf<String>()
        DockerApiParsers.decodeLogStream(input) { lines += it }
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun `unterminated log output is emitted in bounded slices`() {
        val size = DockerApiParsers.MAX_PENDING_CHARS * 2
        val payload = "x".repeat(size).toByteArray()
        val input = ByteArrayInputStream(frame(1, payload.size.toLong(), payload))
        val lines = mutableListOf<String>()
        DockerApiParsers.decodeLogStream(input) { lines += it }
        assertEquals(size, lines.sumOf { it.length })
        assertTrue(lines.all { it.length <= DockerApiParsers.MAX_PENDING_CHARS })
    }

    @Test
    fun `bounded line reader returns whole lines and stops at eof`() {
        val reader = StringReader("one\ntwo\r\nthree")
        assertEquals("one", DockerApiParsers.readBoundedLine(reader))
        assertEquals("two", DockerApiParsers.readBoundedLine(reader))
        assertEquals("three", DockerApiParsers.readBoundedLine(reader))
        assertEquals(null, DockerApiParsers.readBoundedLine(reader))
    }

    @Test
    fun `bounded line reader truncates an endless line`() {
        val reader = StringReader("y".repeat(DockerApiParsers.MAX_PENDING_CHARS * 2) + "\ntail")
        val first = DockerApiParsers.readBoundedLine(reader)!!
        assertEquals(DockerApiParsers.MAX_PENDING_CHARS, first.length)
        // The discarded overflow must not desynchronize the following line.
        assertEquals("tail", DockerApiParsers.readBoundedLine(reader))
    }

    @Test
    fun `image ref splits at the digest not the last colon`() {
        val digest = "sha256:" + "0".repeat(64)
        assertEquals(
            Pair("ghcr.io/tabssh/android", digest),
            DockerApiParsers.splitImageRef("ghcr.io/tabssh/android@$digest")
        )
    }

    @Test
    fun `image ref splits tag and defaults to latest`() {
        assertEquals(Pair("nginx", "1.27"), DockerApiParsers.splitImageRef("nginx:1.27"))
        assertEquals(Pair("nginx", "latest"), DockerApiParsers.splitImageRef("nginx"))
        assertEquals(
            Pair("registry.local:5000/app", "latest"),
            DockerApiParsers.splitImageRef("registry.local:5000/app")
        )
    }
}

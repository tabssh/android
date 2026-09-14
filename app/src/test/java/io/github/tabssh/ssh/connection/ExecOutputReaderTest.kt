package io.github.tabssh.ssh.connection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Tests for [ExecOutputReader.readUntilClosed], the cancellable read loop
 * behind `SSHConnection.executeCommand`.
 *
 * Against the old design — a blocking `inputStream.read()` inside
 * `withTimeoutOrNull` — the timeout could never fire while the thread was
 * parked in `read()`, so a command that stopped producing output (or a dead
 * socket) hung the caller forever and the buffered partial output was lost.
 * These tests pin the fixed contract: the timeout actually fires on an idle
 * stream, partial output survives a timeout, and EOF/close still complete
 * normally with every buffered byte drained first.
 */
class ExecOutputReaderTest {

    /**
     * Stream that serves the given chunks (available() reports each chunk),
     * then reports nothing available forever — modelling a command that
     * produced some output and went silent without closing the channel.
     */
    private class ChunkedThenIdleStream(private val chunks: List<ByteArray>) : InputStream() {
        private var chunkIndex = 0
        private var offset = 0

        override fun available(): Int =
            if (chunkIndex < chunks.size) chunks[chunkIndex].size - offset else 0

        override fun read(): Int {
            if (chunkIndex >= chunks.size) return -1
            val b = chunks[chunkIndex][offset].toInt() and 0xFF
            advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (chunkIndex >= chunks.size) return -1
            val chunk = chunks[chunkIndex]
            val n = minOf(len, chunk.size - offset)
            System.arraycopy(chunk, offset, b, off, n)
            advance(n)
            return n
        }

        private fun advance(n: Int) {
            offset += n
            if (offset >= chunks[chunkIndex].size) {
                chunkIndex++
                offset = 0
            }
        }
    }

    @Test
    fun `timeout on silent stream returns false and keeps buffered output`() = runBlocking {
        val stream = ChunkedThenIdleStream(listOf("partial output".toByteArray()))
        val output = StringBuilder()
        val finished = ExecOutputReader.readUntilClosed(stream, { false }, output, 200L)
        assertFalse("reader must report timeout, not completion", finished)
        assertEquals("partial output", output.toString())
    }

    @Test
    fun `idle stream with channel never closing times out instead of hanging`() = runBlocking {
        val stream = ChunkedThenIdleStream(emptyList())
        val output = StringBuilder()
        val start = System.currentTimeMillis()
        val finished = ExecOutputReader.readUntilClosed(stream, { false }, output, 200L)
        val elapsed = System.currentTimeMillis() - start
        assertFalse(finished)
        assertEquals("", output.toString())
        assertTrue("timed out after ${elapsed}ms, expected well under 5s", elapsed < 5000)
    }

    @Test
    fun `eof completes normally with full output`() = runBlocking {
        val stream = ByteArrayInputStream("hello world\n".toByteArray())
        val output = StringBuilder()
        // The reader never issues a read that could block, so plain-stream
        // EOF is signalled the way JSch does it: isClosed once drained.
        val finished = ExecOutputReader.readUntilClosed(stream, { stream.available() == 0 }, output, 5000L)
        assertTrue(finished)
        assertEquals("hello world\n", output.toString())
    }

    @Test
    fun `closed channel completes after draining all buffered chunks`() = runBlocking {
        val stream = ChunkedThenIdleStream(listOf("first ".toByteArray(), "second".toByteArray()))
        val output = StringBuilder()
        val finished = ExecOutputReader.readUntilClosed(stream, { true }, output, 5000L)
        assertTrue("close after drain must count as completion", finished)
        assertEquals("first second", output.toString())
    }
}

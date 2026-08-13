package io.github.tabssh.hypervisor.console

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the WebSocket byte bridge.
 *
 * The behaviour under test is exactly what java.io.PipedInputStream could not
 * provide: a writer thread may die between writes, and the writer must never
 * block waiting for the reader.
 */
class ByteStreamPipeTest {

    @Test
    fun `bytes survive the writing thread exiting`() {
        val pipe = ByteStreamPipe()
        // PipedInputStream throws IOException("Write end dead") here.
        val writer = Thread { pipe.sink.write(byteArrayOf(1, 2, 3)) }
        writer.start()
        writer.join()

        val out = ByteArray(3)
        assertEquals(3, pipe.source.read(out, 0, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), out)
    }

    @Test
    fun `each write may come from a different thread`() {
        val pipe = ByteStreamPipe()
        repeat(3) { i ->
            val t = Thread { pipe.sink.write(byteArrayOf(i.toByte())) }
            t.start()
            t.join()
        }
        val out = ByteArray(3)
        assertEquals(3, pipe.source.read(out, 0, 3))
        assertArrayEquals(byteArrayOf(0, 1, 2), out)
    }

    @Test
    fun `writer does not block when the reader never reads`() {
        val pipe = ByteStreamPipe()
        val chunk = ByteArray(64 * 1024)
        // Well past PipedInputStream's 64 KiB ring buffer, which would deadlock
        // OkHttp's reader thread here.
        repeat(16) { pipe.sink.write(chunk) }
        assertEquals(16 * chunk.size, pipe.bufferedBytes)
    }

    @Test
    fun `writes past the buffer cap fail instead of stalling the transport`() {
        val pipe = ByteStreamPipe(maxBufferedBytes = 1024)
        pipe.sink.write(ByteArray(1024))
        try {
            pipe.sink.write(ByteArray(1))
            throw AssertionError("expected IOException once the cap was exceeded")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("limit exceeded"))
        }
    }

    @Test
    fun `reader sees EOF after the writer closes and the buffer drains`() {
        val pipe = ByteStreamPipe()
        pipe.sink.write(byteArrayOf(7))
        pipe.sink.close()
        val out = ByteArray(4)
        assertEquals(1, pipe.source.read(out, 0, 4))
        assertEquals(-1, pipe.source.read(out, 0, 4))
    }

    @Test
    fun `blocked reader is released when the pipe is closed`() {
        val pipe = ByteStreamPipe()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val reader = Thread {
            started.countDown()
            try {
                pipe.source.read(ByteArray(8), 0, 8)
            } catch (_: IOException) {
                // Expected: teardown while blocked.
            }
            finished.countDown()
        }
        reader.start()
        assertTrue(started.await(2, TimeUnit.SECONDS))
        // Give the reader a moment to actually block on the condition.
        Thread.sleep(50)
        pipe.close()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `partial reads preserve chunk boundaries and byte order`() {
        val pipe = ByteStreamPipe()
        pipe.sink.write(byteArrayOf(1, 2, 3))
        pipe.sink.write(byteArrayOf(4, 5))
        val first = ByteArray(2)
        assertEquals(2, pipe.source.read(first, 0, 2))
        assertArrayEquals(byteArrayOf(1, 2), first)
        val rest = ByteArray(8)
        val n = pipe.source.read(rest, 0, 8)
        assertEquals(3, n)
        assertArrayEquals(byteArrayOf(3, 4, 5), rest.copyOf(3))
    }
}

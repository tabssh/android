package io.github.tabssh.terminal.emulator

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the sendText() write path being serialised on a
 * single-thread executor (TerminalEmulator.writeExecutor).
 *
 * Before this change, sendText() wrote directly to the SSH OutputStream on
 * whatever thread called it — a network write on the UI thread, and with no
 * ordering guarantee when two threads called sendText() concurrently (e.g.
 * a composing-text flush racing a bar/hardware-key escape write). Each
 * sendText() call now posts its write to one dedicated writer thread, so
 * calls queue up FIFO and each call's bytes land on the stream contiguously
 * — never interleaved with another call's bytes.
 */
class TerminalEmulatorWriteOrderingTest {

    private fun newEmulator(): Pair<TerminalEmulator, ByteArrayOutputStream> {
        val emulator = TerminalEmulator(TerminalBuffer(24, 80))
        val out = ByteArrayOutputStream()
        emulator.attachOutputStream(out)
        return Pair(emulator, out)
    }

    @Test
    fun `sendText calls from two threads never interleave and preserve order`() {
        val (emulator, out) = newEmulator()

        // Each sender writes its own tag repeatedly; a correct implementation
        // never splits a single sendText() call's bytes across another
        // call's bytes on the wire, because the writer executor runs one
        // task at a time to completion before starting the next.
        val messagesPerThread = 200
        val threadACount = CountDownLatch(messagesPerThread)
        val threadBCount = CountDownLatch(messagesPerThread)
        val pool = Executors.newFixedThreadPool(2)

        pool.execute {
            repeat(messagesPerThread) { i ->
                emulator.sendText("A$i;")
                threadACount.countDown()
            }
        }
        pool.execute {
            repeat(messagesPerThread) { i ->
                emulator.sendText("B$i;")
                threadBCount.countDown()
            }
        }

        assertTrue(threadACount.await(10, TimeUnit.SECONDS), "thread A did not finish queuing sends")
        assertTrue(threadBCount.await(10, TimeUnit.SECONDS), "thread B did not finish queuing sends")
        pool.shutdown()

        emulator.awaitPendingWrites()

        // Split the recorded stream back into whole "A<i>;" / "B<i>;"
        // tokens. If a write from one thread were ever split by a write
        // from the other thread, this would fail to parse cleanly into
        // exactly the tokens each thread sent, in the order each thread
        // sent them.
        val written = out.toString("UTF-8")
        val tokens = written.split(";").filter { it.isNotEmpty() }

        val aTokens = tokens.filter { it.startsWith("A") }
        val bTokens = tokens.filter { it.startsWith("B") }

        assertEquals(messagesPerThread, aTokens.size)
        assertEquals(messagesPerThread, bTokens.size)
        assertEquals((0 until messagesPerThread).map { "A$it" }, aTokens)
        assertEquals((0 until messagesPerThread).map { "B$it" }, bTokens)

        // Every token must be exactly the sender's tag: no foreign bytes
        // (from the other thread's concurrent write) ever landed inside it.
        tokens.forEach { token ->
            assertTrue(
                token.matches(Regex("^[AB]\\d+$")),
                "token '$token' contains interleaved bytes from another sender"
            )
        }
    }

    @Test
    fun `sendText preserves single-thread call order exactly`() {
        val (emulator, out) = newEmulator()

        emulator.sendText("one ")
        emulator.sendText("two ")
        emulator.sendText("three")
        emulator.awaitPendingWrites()

        assertEquals("one two three", out.toString("UTF-8"))
    }
}

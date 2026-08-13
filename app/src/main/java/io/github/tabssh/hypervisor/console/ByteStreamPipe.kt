package io.github.tabssh.hypervisor.console

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock

/**
 * An in-process byte pipe: bytes written to [sink] become readable on [source].
 *
 * This exists because [java.io.PipedInputStream] is unusable for a WebSocket
 * transport:
 *
 *  1. It records the *thread* that last wrote to it and throws
 *     `IOException("Write end dead")` on the next read once that thread has
 *     terminated. OkHttp writes every incoming frame from its pooled task
 *     runner, whose threads are recycled after an idle period, so a quiet VNC
 *     session would fail with "Write end dead" the moment the server sent its
 *     next update.
 *  2. Its writes block once the ring buffer fills. Blocking there blocks
 *     OkHttp's reader thread, which also drives ping/pong and close frames —
 *     one slow RFB consumer would stall the whole connection, and the server
 *     would eventually drop it as unresponsive.
 *
 * This implementation is thread-agnostic (no writer-liveness check) and its
 * writer never blocks: the buffer grows as needed up to [maxBufferedBytes],
 * beyond which a write fails loudly rather than silently stalling the
 * transport. Chunks are enqueued by reference, so no copy happens on write.
 *
 * Thread safety: one reader and one writer, on any threads, concurrently.
 */
class ByteStreamPipe(private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES) {

    companion object {
        /**
         * Cap on unread bytes held in memory. A hostile or merely fast server
         * must not be able to grow this without bound; 8 MiB is far above any
         * legitimate burst of RFB rectangles and small enough to be safe on a
         * low-end device.
         */
        const val DEFAULT_MAX_BUFFERED_BYTES: Int = 8 * 1024 * 1024
    }

    private val lock = ReentrantLock()
    private val readable = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()

    // Offset into chunks.peek() of the next unread byte.
    private var head = 0
    private var buffered = 0
    private var writerClosed = false
    private var readerClosed = false

    /** Bytes accepted but not yet read. Exposed for diagnostics and tests. */
    val bufferedBytes: Int
        get() {
            lock.lock()
            try {
                return buffered
            } finally {
                lock.unlock()
            }
        }

    /** The read side. Blocking, EOF once the writer closes and the buffer drains. */
    val source: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return 0
            lock.lock()
            try {
                while (buffered == 0) {
                    if (readerClosed) throw IOException("Pipe closed")
                    if (writerClosed) return -1
                    readable.await()
                }
                var copied = 0
                while (copied < len && buffered > 0) {
                    val chunk = chunks.peek() ?: break
                    val available = chunk.size - head
                    val n = minOf(available, len - copied)
                    System.arraycopy(chunk, head, b, off + copied, n)
                    head += n
                    copied += n
                    buffered -= n
                    if (head == chunk.size) {
                        chunks.poll()
                        head = 0
                    }
                }
                return copied
            } finally {
                lock.unlock()
            }
        }

        override fun available(): Int = bufferedBytes

        override fun close() {
            lock.lock()
            try {
                readerClosed = true
                chunks.clear()
                head = 0
                buffered = 0
                readable.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    /** The write side. Never blocks; throws once the reader is gone or the cap is hit. */
    val sink: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
            if (len == 0) return
            // Copy: the caller may reuse its buffer as soon as write() returns.
            val chunk = b.copyOfRange(off, off + len)
            lock.lock()
            try {
                if (readerClosed) throw IOException("Pipe closed by reader")
                if (writerClosed) throw IOException("Pipe closed")
                if (buffered + chunk.size > maxBufferedBytes) {
                    throw IOException("Pipe buffer limit exceeded ($maxBufferedBytes bytes)")
                }
                chunks.add(chunk)
                buffered += chunk.size
                readable.signalAll()
            } finally {
                lock.unlock()
            }
        }

        override fun flush() {
            // Nothing to flush: write() already publishes to the reader.
        }

        override fun close() {
            lock.lock()
            try {
                writerClosed = true
                readable.signalAll()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Tear both ends down and drop anything still buffered. */
    fun close() {
        try { sink.close() } catch (_: IOException) {}
        try { source.close() } catch (_: IOException) {}
    }
}

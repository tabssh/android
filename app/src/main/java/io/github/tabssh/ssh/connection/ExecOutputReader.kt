package io.github.tabssh.ssh.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * Cancellable output reader for exec-channel streams.
 *
 * A plain blocking `InputStream.read()` on JSch's piped exec stream has no
 * coroutine cancellation point, so `withTimeoutOrNull` around it can never
 * fire while the thread is parked inside `read()` — a dead socket or a
 * command that never terminates would hang the caller forever. This reader
 * only calls `read()` when `available()` guarantees it cannot block, and
 * suspends in [delay] while idle, giving the timeout a place to cancel.
 */
object ExecOutputReader {

    // Idle poll interval: short enough to keep per-chunk latency low, long
    // enough not to spin while a slow command produces no output.
    private const val IDLE_POLL_MS = 50L

    /**
     * Reads from [input] into [output] until EOF or channel close, bounded
     * by [timeoutMs]. Returns true when the stream finished (EOF, or
     * [isClosed] with nothing left buffered); false on timeout, with
     * whatever bytes were buffered so far already appended to [output].
     */
    suspend fun readUntilClosed(
        input: InputStream,
        isClosed: () -> Boolean,
        output: StringBuilder,
        timeoutMs: Long
    ): Boolean {
        val buffer = ByteArray(4096)
        val finished = withTimeoutOrNull(timeoutMs) {
            while (true) {
                var sawEof = false
                // available() > 0 guarantees this read() returns without
                // blocking, so cancellation is never trapped inside it.
                while (input.available() > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size, input.available()))
                    if (n == -1) {
                        sawEof = true
                        break
                    }
                    if (n > 0) output.append(String(buffer, 0, n, Charsets.UTF_8))
                }
                if (sawEof) break
                // JSch sets isClosed on SSH_MSG_CHANNEL_CLOSE; checked only
                // after a full drain so buffered trailing output is kept.
                if (isClosed() && input.available() <= 0) break
                delay(IDLE_POLL_MS)
            }
            true
        }
        return finished == true
    }
}

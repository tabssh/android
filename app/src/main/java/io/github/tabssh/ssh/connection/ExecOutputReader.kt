package io.github.tabssh.ssh.connection

import kotlinx.coroutines.delay
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

    // A command that keeps printing is never cut off by the silence limit, but a stream that never
    // ends must still stop somewhere, so the whole read is bounded as well.
    private const val HARD_CEILING_MS = 2 * 60 * 60 * 1000L

    /**
     * Reads from [input] into [output] until EOF or channel close. [timeoutMs] is a SILENCE limit:
     * it counts time since the last byte arrived, so a command that is still producing output is
     * never timed out while it is working. Returns true when the stream finished (EOF, or [isClosed]
     * with nothing left buffered); false after [timeoutMs] without output, or at the hard ceiling,
     * with whatever bytes were buffered so far already appended to [output].
     */
    suspend fun readUntilClosed(
        input: InputStream,
        isClosed: () -> Boolean,
        output: StringBuilder,
        timeoutMs: Long,
        onChunk: ((String) -> Unit)? = null
    ): Boolean {
        val buffer = ByteArray(4096)
        val started = System.currentTimeMillis()
        var lastOutput = started
        while (true) {
            var sawEof = false
            // available() > 0 guarantees this read() returns without blocking, so cancellation
            // is never trapped inside it.
            while (input.available() > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, input.available()))
                if (n == -1) {
                    sawEof = true
                    break
                }
                if (n > 0) {
                    lastOutput = System.currentTimeMillis()
                    val chunk = String(buffer, 0, n, Charsets.UTF_8)
                    output.append(chunk)
                    onChunk?.invoke(chunk)
                }
            }
            if (sawEof) return true
            // JSch sets isClosed on SSH_MSG_CHANNEL_CLOSE; checked only after a full drain so
            // buffered trailing output is kept.
            if (isClosed() && input.available() <= 0) return true
            val now = System.currentTimeMillis()
            if (now - lastOutput >= timeoutMs || now - started >= HARD_CEILING_MS) return false
            delay(IDLE_POLL_MS)
        }
    }
}

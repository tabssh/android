package io.github.tabssh.containers.transport

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Shared SSH exec helper for the Docker transports (modeled on
 * LibvirtApiClient.runCommand, extended with stderr capture and exit status).
 *
 * Both [EngineApiTransport] (compose + remote-file ops) and
 * [CliExecTransport] (everything) run their remote commands through this
 * class, so channel handling, cancellation, and timeouts live in one place.
 *
 * [sessionProvider] returns the live JSch [Session] of the linked
 * ConnectionProfile (SSHConnection.jschSession()) or null when disconnected.
 */
class SshExecRunner(
    private val sessionProvider: () -> Session?
) {

    companion object {
        private const val TAG = "SshExecRunner"
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_EXEC_TIMEOUT_MS = 30_000L
        private const val POLL_INTERVAL_MS = 50L

        /**
         * Cap on captured stdout (and, separately, stderr) for one exec.
         * Remote output is untrusted in size — `docker inspect` on a huge
         * container set or a stray `cat` of a large file would otherwise be
         * buffered whole. 8 MiB matches the bound used for Engine API bodies.
         */
        const val MAX_EXEC_OUTPUT_BYTES = 8 * 1024 * 1024

        /** Longest unterminated line buffered by [stream] before it is emitted. */
        const val MAX_STREAM_LINE_CHARS = 64 * 1024

        /**
         * POSIX single-quote-escape [value] for safe interpolation into a
         * remote shell command. Same injection barrier as
         * LibvirtApiClient.shQuote — no metacharacter in [value] can break
         * out of the argument.
         */
        fun shQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }

    /** The live session, or a [TransportUnavailableException] when gone. */
    fun requireSession(): Session =
        sessionProvider()?.takeIf { it.isConnected }
            ?: throw TransportUnavailableException(ContainerTransportMessages.SSH_SESSION_UNAVAILABLE)

    /**
     * Run [command] on the remote host, capturing stdout, stderr, and the
     * exit status. Honors coroutine cancellation between read polls; the
     * channel is always disconnected on exit.
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_EXEC_TIMEOUT_MS
    ): ExecResult = withContext(Dispatchers.IO) {
        val sess = requireSession()
        var ch: ChannelExec? = null
        try {
            val channel = sess.openChannel("exec") as ChannelExec
            ch = channel
            channel.setCommand(command)
            channel.setInputStream(null)
            // Streams must be obtained BEFORE connect() (JSch requirement).
            val stdout = channel.inputStream
            val stderr = channel.errStream
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)

            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                val moved = drainAvailable(stdout, outBuf, buf) + drainAvailable(stderr, errBuf, buf)
                if (moved == 0) {
                    // delay() honors coroutine cancellation; Thread.sleep() does not.
                    delay(POLL_INTERVAL_MS)
                }
            }
            if (channel.isClosed) {
                // Drain any bytes that arrived between the last poll and close.
                drainFully(stdout, outBuf, buf)
                drainFully(stderr, errBuf, buf)
            } else {
                // Timeout with the channel still open: never block-read it — on a
                // dead (black-holed) connection the read would hang forever, and on
                // a slow command it would defeat the timeout. Take what's buffered.
                Logger.w(TAG, "run: timeout after ${timeoutMs}ms cmdLen=${command.length}")
                drainAvailable(stdout, outBuf, buf)
                drainAvailable(stderr, errBuf, buf)
            }
            val exit = if (channel.isClosed) channel.exitStatus else -1
            Logger.d(TAG, "run: exit=$exit cmdLen=${command.length}")
            ExecResult(
                stdout = outBuf.toString("UTF-8"),
                stderr = errBuf.toString("UTF-8"),
                exitStatus = exit
            )
        } finally {
            ch?.disconnect()
        }
    }

    /**
     * Run [command] and emit stdout as complete text lines until the remote
     * process exits or the collector cancels. Used for `docker logs --follow`
     * and CLI pull output. Runs on Dispatchers.IO; cancellation is honored at
     * every poll via delay().
     */
    fun stream(command: String): Flow<String> = flow {
        val sess = requireSession()
        val channel = sess.openChannel("exec") as ChannelExec
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            val stdout = channel.inputStream
            channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)

            val buf = ByteArray(8192)
            val pending = StringBuilder()
            while (true) {
                val available = stdout.available()
                if (available > 0) {
                    val n = stdout.read(buf, 0, minOf(available, buf.size))
                    if (n > 0) {
                        pending.append(String(buf, 0, n, Charsets.UTF_8))
                        emitCompleteLines(pending) { emit(it) }
                    }
                } else if (channel.isClosed) {
                    break
                } else {
                    delay(POLL_INTERVAL_MS)
                }
            }
            // Final drain plus trailing partial line.
            var n = stdout.read(buf)
            while (n > 0) {
                pending.append(String(buf, 0, n, Charsets.UTF_8))
                emitCompleteLines(pending) { emit(it) }
                n = stdout.read(buf)
            }
            emitCompleteLines(pending) { emit(it) }
            if (pending.isNotEmpty()) emit(pending.toString())
        } finally {
            channel.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Append [count] bytes of [buf] to [sink], but never past
     * [MAX_EXEC_OUTPUT_BYTES].
     *
     * Bytes over the cap are read and dropped rather than left unread: the
     * remote process must keep draining so the channel closes and the command
     * finishes, but a `docker logs` or `cat` against a multi-gigabyte file
     * must not be buffered whole into the app's heap.
     */
    private fun appendCapped(sink: ByteArrayOutputStream, buf: ByteArray, count: Int) {
        val room = MAX_EXEC_OUTPUT_BYTES - sink.size()
        if (room <= 0) return
        sink.write(buf, 0, minOf(count, room))
    }

    /** Move every currently-available byte from [input] into [sink]. */
    private fun drainAvailable(
        input: InputStream,
        sink: ByteArrayOutputStream,
        buf: ByteArray
    ): Int {
        var moved = 0
        var available = input.available()
        while (available > 0) {
            val n = input.read(buf, 0, minOf(available, buf.size))
            if (n <= 0) break
            appendCapped(sink, buf, n)
            moved += n
            available = input.available()
        }
        return moved
    }

    /** Blocking drain of [input] to EOF (safe once the channel is closed). */
    private fun drainFully(
        input: InputStream,
        sink: ByteArrayOutputStream,
        buf: ByteArray
    ) {
        var n = input.read(buf)
        while (n > 0) {
            appendCapped(sink, buf, n)
            n = input.read(buf)
        }
    }

    /** Emit every complete \n-terminated line in [pending], keeping the rest. */
    private suspend fun emitCompleteLines(
        pending: StringBuilder,
        emit: suspend (String) -> Unit
    ) {
        var idx = pending.indexOf("\n")
        while (idx >= 0) {
            val line = pending.substring(0, idx).removeSuffix("\r")
            pending.delete(0, idx + 1)
            emit(line)
            idx = pending.indexOf("\n")
        }
        // A remote process that never emits a newline would otherwise grow
        // this buffer without limit; flush it in fixed slices instead.
        while (pending.length >= MAX_STREAM_LINE_CHARS) {
            val slice = pending.substring(0, MAX_STREAM_LINE_CHARS)
            pending.delete(0, MAX_STREAM_LINE_CHARS)
            emit(slice)
        }
    }
}

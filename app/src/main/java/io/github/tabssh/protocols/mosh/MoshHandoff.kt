package io.github.tabssh.protocols.mosh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wave 2.X — **Honest Mosh handoff** (NOT real Mosh).
 *
 * Real Mosh requires speaking the Mosh State Synchronization Protocol over
 * UDP with AES-128-OCB authenticated encryption. That's a protocol port we
 * do not have a Java/Kotlin implementation of, and reimplementing SSP in a
 * single development pass is unrealistic (the C++ reference is thousands
 * of LOC and the wire format is non-trivial).
 *
 * What this DOES do, end to end:
 *  1. Run `mosh-server new -l LANG=en_US.UTF-8` over the existing SSH
 *     `exec` channel.
 *  2. Parse the canonical line `MOSH CONNECT <udp-port> <base64-key>`.
 *  3. Return [MoshHandoffInfo] so the UI can show the user the
 *     `MOSH_KEY=… mosh -p <port> user@host` command they can copy/paste
 *     into a real Mosh client (Termux's `mosh`, the official iOS client,
 *     etc.). On Android specifically, Termux is the simplest path.
 *
 * Important: the SSH session is what kept mosh-server alive briefly for
 * the bootstrap handshake. Mosh's design is that mosh-server detaches
 * from its SSH parent immediately and listens on the published UDP port
 * directly. Closing our SSH session does NOT kill the Mosh session — the
 * user can disconnect SSH and still attach with a real Mosh client.
 *
 * Privacy: the key is sensitive (it's the session secret). We surface it
 * to the user once, give them a Copy button, and don't log it.
 */
object MoshHandoff {

    private const val TAG = "MoshHandoff"

    data class MoshHandoffInfo(
        val host: String,
        val username: String,
        val port: Int,
        val keyBase64: String
    ) {
        /** Build the canonical client invocation for the Mosh client. */
        fun toClientCommand(): String =
            "MOSH_KEY=$keyBase64 mosh -p $port $username@$host"

        // Hand-written toString so the generated data-class one can never spill
        // the Mosh session key into a log line, crash report, or debugger dump.
        override fun toString(): String =
            "MoshHandoffInfo(host=$host, username=$username, port=$port, keyBase64=xxxxx)"
    }

    sealed class Result {
        data class Success(val info: MoshHandoffInfo) : Result()
        data class Error(val message: String) : Result()
    }

    /**
     * Execute the bootstrap. Returns within ~10s on success, or surfaces an
     * error message if mosh-server isn't installed / refused / produced
     * unparseable output.
     */
    suspend fun bootstrap(
        ssh: SSHConnection,
        username: String,
        host: String,
        commandOverride: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val session = grabSession(ssh) ?: return@withContext Result.Error("SSH session not connected")
        // Do NOT use -s here. The -s flag tells mosh-server to read its
        // session key from stdin; since we never write to the exec channel's
        // stdin, mosh-server blocks indefinitely and the 8-second deadline
        // fires with empty output. Without -s, mosh-server generates its own
        // key and immediately prints "MOSH CONNECT <port> <key>".
        val cmd = commandOverride?.takeIf { it.isNotBlank() }
            ?: "mosh-server new -l LANG=en_US.UTF-8"

        var ch: ChannelExec? = null
        try {
            ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(cmd)
            val input = ch.inputStream
            ch.connect(10_000)

            val sb = StringBuilder()
            val buf = ByteArray(2048)
            // mosh-server prints a few lines then daemonizes. Read a brief
            // window then stop — don't hang waiting for EOF. The remote is
            // untrusted output, so cap the buffer: a host that streams
            // forever must not grow this StringBuilder without bound.
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline && sb.length < MAX_SERVER_OUTPUT_CHARS) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                } else {
                    // JSch's channel stream returns 0 when no data is pending;
                    // yield instead of spinning the CPU until the deadline.
                    Thread.sleep(20L)
                }
                if (sb.contains("MOSH CONNECT")) break
            }
            val raw = sb.toString()
            // Never log the raw line — it carries the Mosh session secret.
            // Log only whether the handshake line was seen at all.
            Logger.d(TAG, "mosh-server handshake line ${if ("MOSH CONNECT" in raw) "received" else "absent"}")

            val match = Regex("""MOSH CONNECT (\d+) (\S+)""").find(raw)
            if (match == null) {
                return@withContext Result.Error(
                    if (raw.contains("not found", ignoreCase = true) ||
                        raw.contains("command not found", ignoreCase = true)
                    ) "mosh-server is not installed on the remote host"
                    else if (raw.isBlank()) "mosh-server produced no output"
                    else "Could not parse mosh-server response (mosh installed?)"
                )
            }
            val (portStr, key) = match.destructured
            // Everything past this point is remote-controlled. Validate before
            // it reaches a ProcessBuilder / Intent extra: an out-of-range port
            // or a key with shell/argument metacharacters is a protocol
            // violation, not something to pass through.
            val parsedPort = portStr.toIntOrNull()
            if (parsedPort == null || parsedPort !in 1..65535) {
                return@withContext Result.Error("mosh-server reported an invalid UDP port")
            }
            if (!isValidMoshKey(key)) {
                return@withContext Result.Error("mosh-server reported a malformed session key")
            }
            return@withContext Result.Success(
                MoshHandoffInfo(
                    host = host,
                    username = username,
                    port = parsedPort,
                    keyBase64 = key
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "Mosh handoff failed", e)
            return@withContext Result.Error("Bootstrap failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun grabSession(ssh: SSHConnection): Session? = ssh.jschSession()

    /**
     * A Mosh session key is a 16-byte AES key printed as unpadded base64 (22
     * chars). Accept the standard base64 alphabet with an optional pad and a
     * sane length window rather than pinning exactly 22, so a future key size
     * still passes — but reject anything containing shell or argument
     * metacharacters.
     */
    internal fun isValidMoshKey(key: String): Boolean =
        key.length in 16..64 && MOSH_KEY_REGEX.matches(key)

    private val MOSH_KEY_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private const val MAX_SERVER_OUTPUT_CHARS = 64 * 1024
}

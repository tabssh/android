package io.github.tabssh.protocols.mosh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
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
 *  1. Run `mosh-server new -s -l LANG=en_US.UTF-8` over the existing SSH
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
        val cmd = commandOverride?.takeIf { it.isNotBlank() }
            ?: "mosh-server new -s -l LANG=en_US.UTF-8"

        var ch: ChannelExec? = null
        try {
            ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(cmd)
            val input = ch.inputStream
            ch.connect(10_000)

            val sb = StringBuilder()
            val buf = ByteArray(2048)
            // mosh-server prints a few lines then daemonizes. Read a brief
            // window then stop — don't hang waiting for EOF.
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.contains("MOSH CONNECT")) break
            }
            val raw = sb.toString()
            Logger.d(TAG, "mosh-server raw output: ${raw.lineSequence().firstOrNull { "MOSH CONNECT" in it } ?: "(no MOSH CONNECT line)"}")

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
            val (port, key) = match.destructured
            return@withContext Result.Success(
                MoshHandoffInfo(
                    host = host,
                    username = username,
                    port = port.toInt(),
                    keyBase64 = key
                )
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Mosh handoff failed", e)
            return@withContext Result.Error("Bootstrap failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
        }
    }

    /** Reflect into SSHConnection to get the JSch Session — same trick the
     *  rest of the codebase uses (HistoryFetcher, SCPClient, …). */
    private fun grabSession(ssh: SSHConnection): Session? = try {
        val f = ssh.javaClass.getDeclaredField("session")
        f.isAccessible = true
        f.get(ssh) as? Session
    } catch (e: Exception) {
        Logger.w(TAG, "Couldn't grab JSch session: ${e.message}")
        null
    }
}

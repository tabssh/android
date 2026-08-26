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
        val keyBase64: String,
        // PID that mosh-server prints on its "[mosh-server detached, pid = N]"
        // line, when we manage to capture it. Used to reap an orphaned server
        // if the client never attaches (fast SSH fallback). Null when the
        // remote mosh-server didn't print it (older builds) — reap then falls
        // back to killing whatever is bound to [port].
        val serverPid: Int? = null
    ) {
        /** Build the canonical client invocation for the Mosh client. */
        fun toClientCommand(): String =
            "MOSH_KEY=$keyBase64 mosh -p $port $username@$host"

        // Hand-written toString so the generated data-class one can never spill
        // the Mosh session key into a log line, crash report, or debugger dump.
        override fun toString(): String =
            "MoshHandoffInfo(host=$host, username=$username, port=$port, serverPid=$serverPid, keyBase64=xxxxx)"
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
        commandOverride: String? = null,
        networkTimeoutSeconds: Int = DEFAULT_NETWORK_TMOUT_SECONDS
    ): Result = withContext(Dispatchers.IO) {
        val session = grabSession(ssh) ?: return@withContext Result.Error("SSH session not connected")
        // Do NOT use -s here. The -s flag tells mosh-server to read its
        // session key from stdin; since we never write to the exec channel's
        // stdin, mosh-server blocks indefinitely and the 8-second deadline
        // fires with empty output. Without -s, mosh-server generates its own
        // key and immediately prints "MOSH CONNECT <port> <key>".
        val baseCmd = commandOverride?.takeIf { it.isNotBlank() }
            ?: "mosh-server new -l LANG=en_US.UTF-8"
        // Bound the detached server's lifetime. Without this, a mosh-server
        // whose client roams away or whose app is killed lingers forever,
        // holding a utmp/`who` entry, and every reconnect spawns another —
        // orphans pile up on the host without bound.
        val cmd = withNetworkTimeout(baseCmd, networkTimeoutSeconds)

        var ch: ChannelExec? = null
        try {
            ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(cmd)
            // No pty here — bootstrap mosh-server over a plain exec channel,
            // exactly as the `mosh` client does. An earlier build allocated a
            // pty to force a `lastlog`/utmp entry so a mosh connect would look
            // like an ssh login, but that made every detached mosh-server
            // register a `who` entry (host field literally "mosh [pid]"), so
            // leaked servers flooded the login banner's `who` list. Mosh is
            // now left to do what mosh does by default: it still writes its own
            // utmp/wtmp/lastlog, and MOTD prints through the login shell.
            val input = ch.inputStream
            ch.connect(10_000)

            val sb = StringBuilder()
            val buf = ByteArray(2048)
            // mosh-server prints a few lines then daemonizes. Read a brief
            // window then stop — don't hang waiting for EOF. The remote is
            // untrusted output, so cap the buffer: a host that streams
            // forever must not grow this StringBuilder without bound.
            val deadline = System.currentTimeMillis() + 8_000L
            // Once "MOSH CONNECT" is seen, linger a short grace trying to also
            // capture the "[mosh-server detached, pid = N]" line — it arrives in
            // the same startup burst and lets us reap the server by PID on a
            // fast SSH fallback. Best-effort: if it never comes, proceed without
            // it rather than delay the whole connect.
            var connectSeenAt = 0L
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
                val hasConnect = sb.contains("MOSH CONNECT")
                if (hasConnect && connectSeenAt == 0L) connectSeenAt = System.currentTimeMillis()
                val hasPid = hasConnect && MOSH_PID_REGEX.containsMatchIn(sb)
                if (hasConnect && (hasPid || System.currentTimeMillis() - connectSeenAt > PID_GRACE_MS)) break
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
                    keyBase64 = key,
                    serverPid = parseDetachedPid(raw)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // MoshHandoff has no Context of its own; TabSSHApplication.get() is
            // the documented last-resort accessor (see its companion object)
            // rather than threading a Context through this object solely for
            // error-message mapping. ThrowableMapper.map() logs the throwable
            // itself, so no separate Logger.e call.
            val friendly = io.github.tabssh.utils.ThrowableMapper.map(
                io.github.tabssh.TabSSHApplication.get(), TAG, e, "Mosh handoff failed"
            ).message
            return@withContext Result.Error("Bootstrap failed: $friendly")
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Prepend `MOSH_SERVER_NETWORK_TMOUT=<seconds>` to the launch command so a
     * detached mosh-server whose client stops checking in exits on its own
     * instead of lingering forever (mosh's default is 0 = never). Leaves the
     * command untouched when the caller already set the variable, or when the
     * timeout is disabled (<= 0). The prefix is valid `VAR=val cmd` shell for
     * both the default command and custom paths like `/usr/local/bin/mosh-server`.
     */
    internal fun withNetworkTimeout(command: String, seconds: Int): String =
        if (seconds <= 0 || command.contains("MOSH_SERVER_NETWORK_TMOUT")) command
        else "MOSH_SERVER_NETWORK_TMOUT=$seconds $command"

    /**
     * Pull the PID out of mosh-server's "[mosh-server detached, pid = N]" line.
     * Returns null when the line is absent (older mosh builds) or unparseable.
     */
    internal fun parseDetachedPid(raw: String): Int? =
        MOSH_PID_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Best-effort reap of an orphaned mosh-server we bootstrapped but never
     * attached a client to (the fast SSH-fallback path). Prefers the captured
     * [pid]; falls back to whatever process is bound to the published UDP
     * [port]. Runs over the still-open SSH session and never throws — cleanup
     * is advisory, so a missing `fuser`/`kill` or a closed channel is ignored.
     *
     * [pid] and [port] are integers parsed from mosh-server's own output and
     * the validated handshake, so neither can carry shell metacharacters.
     */
    suspend fun reapServer(ssh: SSHConnection, pid: Int?, port: Int) = withContext(Dispatchers.IO) {
        val session = grabSession(ssh) ?: return@withContext
        val kills = buildList {
            if (pid != null && pid > 1) add("kill $pid 2>/dev/null")
            if (port in 1..65535) add("kill \$(fuser -n udp $port 2>/dev/null) 2>/dev/null")
        }
        if (kills.isEmpty()) return@withContext
        val cmd = "sh -c '${kills.joinToString(" ; ")} ; true'"
        var ch: ChannelExec? = null
        try {
            ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(cmd)
            ch.connect(5_000)
            // Drain briefly so the remote command actually runs before we
            // disconnect the channel; bounded so a wedged host can't hang us.
            val ins = ch.inputStream
            val drain = ByteArray(256)
            val deadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < deadline && ch.isConnected) {
                if (ins.available() > 0) {
                    if (ins.read(drain) < 0) break
                } else if (ch.isEOF) {
                    break
                } else {
                    Thread.sleep(20L)
                }
            }
            Logger.d(TAG, "Reaped orphaned mosh-server (pid=${pid ?: "?"}, port=$port)")
        } catch (e: Exception) {
            Logger.d(TAG, "mosh-server reap best-effort failed: ${e.message}")
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

    // mosh-server announces its daemon PID as "[mosh-server detached, pid = N]".
    private val MOSH_PID_REGEX = Regex("""mosh-server detached, pid\s*=?\s*(\d+)""")

    private const val MAX_SERVER_OUTPUT_CHARS = 64 * 1024

    // Grace window (ms) to keep reading after "MOSH CONNECT" so the detached-pid
    // line can be captured; short enough not to add noticeable connect latency.
    private const val PID_GRACE_MS = 400L

    // Default MOSH_SERVER_NETWORK_TMOUT (seconds). A detached server that loses
    // its client self-terminates after this long, so a roamed-away or
    // app-killed session can't orphan a mosh-server forever. Seven days is
    // generous enough never to cut off a real reconnect yet guarantees no
    // orphan outlives a week; the fast-fallback path reaps its server at once.
    private const val DEFAULT_NETWORK_TMOUT_SECONDS = 604_800
}

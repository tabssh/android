package io.github.tabssh.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wave 2.10 — Remote shell history fetcher.
 *
 * One-shot read of `~/.bash_history` and `~/.zsh_history` via JSch
 * ChannelExec. We `cat` both, ignore failures, dedupe (keep most-recent
 * order), and trim to a reasonable cap so we don't load megabytes into
 * RAM for the palette.
 *
 * The history palette is a UX nicety — we'd rather return what we can
 * read and silently skip the rest than crash on a missing file or perm
 * error. So most failures get a Logger.w and an empty list.
 *
 * History line format: bash uses one command per line; zsh extended
 * history starts each line with `: <epoch>:0;<command>`. We strip that
 * prefix so both look the same.
 */
class HistoryFetcher(private val sshConnection: SSHConnection) {

    companion object {
        private const val TAG = "HistoryFetcher"
        private const val MAX_LINES = 2000
        private const val PER_FILE_CAP_BYTES = 512 * 1024 // 512 KiB
    }

    /** Fetch + dedupe history. Most-recent first; safe to call from a coroutine. */
    suspend fun fetch(): List<String> = withContext(Dispatchers.IO) {
        val session = grabSession() ?: return@withContext emptyList()
        val cmd = "cat ~/.bash_history ~/.zsh_history 2>/dev/null | head -c $PER_FILE_CAP_BYTES"
        val raw = runRemote(session, cmd) ?: return@withContext emptyList()

        // Most-recent-first: reverse, dedupe (keep first occurrence).
        val seen = LinkedHashSet<String>()
        val parsed = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { stripZshPrefix(it) }
            .filter { it.length in 1..400 } // reject pasted blobs
            .toList()
            .asReversed()
        for (line in parsed) {
            seen.add(line)
            if (seen.size >= MAX_LINES) break
        }
        Logger.i(TAG, "Fetched ${seen.size} unique history entries")
        seen.toList()
    }

    /** zsh extended history format: `: 1714150000:0;ls -la` → `ls -la`. */
    private fun stripZshPrefix(line: String): String {
        if (!line.startsWith(": ")) return line
        val semi = line.indexOf(';')
        return if (semi > 0 && semi + 1 < line.length) line.substring(semi + 1) else line
    }

    private fun grabSession(): Session? = try {
        val f = sshConnection.javaClass.getDeclaredField("session")
        f.isAccessible = true
        f.get(sshConnection) as? Session
    } catch (e: Exception) {
        Logger.w(TAG, "Couldn't grab JSch session: ${e.message}")
        null
    }

    private fun runRemote(session: Session, command: String): String? {
        if (!session.isConnected) return null
        var ch: ChannelExec? = null
        return try {
            ch = session.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            val input = ch.inputStream
            ch.connect(5_000)
            val sb = StringBuilder()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.length > PER_FILE_CAP_BYTES * 2) break // safety
            }
            sb.toString()
        } catch (e: Exception) {
            Logger.w(TAG, "runRemote failed: ${e.message}")
            null
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
        }
    }
}

package io.github.tabssh.sftp

import com.jcraft.jsch.ChannelExec
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Wave 1.9 — SCP (rcp-style) device → server upload.
 *
 * SCP is a tiny wire protocol layered on `ssh remote 'scp -t /target'`. We
 * speak it directly via JSch's ChannelExec so we don't need an scp binary
 * on the device. Note: OpenSSH 9.x deprecated server-side scp by default,
 * so this only works on servers where `scp` is still present (it usually
 * still is — distros ship it).
 *
 * SFTP is the recommended path; SCP is exposed for compatibility with
 * minimal embedded systems / network gear that don't ship an SFTP
 * subsystem.
 *
 * This implementation supports UPLOAD only (device → server). Download via
 * SCP is intentionally omitted — SFTP is strictly better for that direction.
 */
class SCPClient(private val sshConnection: SSHConnection) {

    companion object {
        private const val TAG = "SCPClient"
        // Per-chunk buffer when streaming.
        private const val BUFFER_SIZE = 64 * 1024
    }

    /**
     * Upload a single local file to a remote path. The remote path is the
     * full destination (including filename). Returns true on success.
     *
     * Progress is reported via the listener with the same TransferTask
     * conventions as SFTPManager.
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        listener: TransferListener? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!localFile.exists() || !localFile.isFile) {
            Logger.e(TAG, "Local file missing or not a regular file: $localFile")
            return@withContext false
        }

        val session = try {
            // Reuse SSHConnection's underlying JSch session via reflection — it's the
            // same object SFTPManager opens its channel on.
            val sessionField = sshConnection.javaClass.getDeclaredField("session")
            sessionField.isAccessible = true
            sessionField.get(sshConnection) as? com.jcraft.jsch.Session
        } catch (e: Exception) {
            Logger.e(TAG, "Couldn't grab JSch session reference", e)
            null
        }
        if (session == null || !session.isConnected) {
            Logger.e(TAG, "Underlying SSH session not connected")
            return@withContext false
        }

        val task = TransferTask(
            id = "scp-${System.currentTimeMillis()}",
            type = TransferType.UPLOAD,
            localPath = localFile.absolutePath,
            remotePath = remotePath,
            totalBytes = localFile.length(),
            listener = listener
        )
        task.updateState(TransferState.ACTIVE)

        var channel: ChannelExec? = null
        var out: OutputStream? = null
        var inStream: InputStream? = null
        try {
            // SCP server-side process invoked with -t (target).
            val cmd = "scp -t " + shellEscape(remotePath)
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(cmd)

            // Pipes for the SCP handshake.
            out = channel.outputStream
            inStream = channel.inputStream
            channel.connect()

            if (!checkAck(inStream)) {
                Logger.e(TAG, "SCP server didn't ack initial handshake")
                task.complete(TransferResult.Error("SCP handshake failed"))
                return@withContext false
            }

            // Send file header: C<mode> <length> <filename>\n
            val mode = "0644"
            val name = File(remotePath).name
            val header = "C$mode ${localFile.length()} $name\n"
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.flush()
            if (!checkAck(inStream)) {
                Logger.e(TAG, "SCP server rejected header")
                task.complete(TransferResult.Error("SCP rejected header"))
                return@withContext false
            }

            // Stream file contents.
            localFile.inputStream().use { fin ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = fin.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    task.addBytesTransferred(read.toLong())
                    task.notifyProgress()
                }
                out.flush()
            }

            // SCP transfer terminator (single null byte).
            out.write(byteArrayOf(0))
            out.flush()
            if (!checkAck(inStream)) {
                Logger.e(TAG, "SCP server rejected transfer terminator")
                task.complete(TransferResult.Error("SCP terminator rejected"))
                return@withContext false
            }

            task.complete(TransferResult.Success)
            Logger.i(TAG, "SCP upload complete: ${localFile.name} → $remotePath")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "SCP upload failed", e)
            task.complete(TransferResult.Error(e.message ?: "SCP error"))
            false
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { inStream?.close() } catch (_: Exception) {}
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * SCP "ack" byte protocol: server writes 0=ok, 1=warn (followed by
     * message+\n), 2=error (followed by message+\n). Returns true on 0.
     */
    private fun checkAck(input: InputStream): Boolean {
        val b = input.read()
        if (b == 0) return true
        if (b == -1) return false
        if (b == 1 || b == 2) {
            val sb = StringBuilder()
            var c: Int
            do {
                c = input.read()
                if (c < 0 || c.toChar() == '\n') break
                sb.append(c.toChar())
            } while (true)
            Logger.w(TAG, "SCP server ${if (b == 1) "warning" else "error"}: $sb")
            return b == 1 // warning is recoverable; error is fatal
        }
        return false
    }

    /**
     * Conservative shell-escape of a remote path for use in the scp -t
     * command. Wraps in single quotes and escapes embedded single quotes.
     */
    private fun shellEscape(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}

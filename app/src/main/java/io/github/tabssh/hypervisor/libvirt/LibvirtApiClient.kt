package io.github.tabssh.hypervisor.libvirt

import android.content.Context
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * SSH-backed libvirt / QEMU client.
 *
 * Connects to the hypervisor host via JSch (SSH), then:
 *  - Runs `virsh list --all` to enumerate domains.
 *  - Runs `virsh vncdisplay <domain>` to discover the VNC port.
 *  - Opens a `direct-tcpip` channel to `127.0.0.1:(5900+display)` to proxy
 *    the VNC stream back to the Android client without opening the VNC port
 *    to the outside world.
 *
 * The JSch [Session] is kept alive as a field; callers must call [disconnect]
 * when the console session ends.
 */
class LibvirtApiClient(
    private val context: Context,
    private val profile: HypervisorProfile
) {
    private companion object {
        private const val TAG = "LibvirtApiClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val EXEC_TIMEOUT_MS = 10_000
    }

    private var session: Session? = null

    // ── Connection ────────────────────────────────────────────────────────────

    /**
     * Establish the SSH session. Must be called (on IO dispatcher) before any
     * other method. Throws on failure.
     *
     * When [HypervisorProfile.sshIdentityId] is set, the corresponding key is
     * loaded from [KeyStorage] and offered to the server first (publickey auth);
     * password is still tried as a fallback so existing setups keep working.
     * When no identity is configured, password-only auth is used as before.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        val app = context.applicationContext as TabSSHApplication
        val password = HypervisorPasswordStore.retrieve(context, profile)

        val keyId = profile.sshIdentityId
        // Validate credentials before attempting the SSH handshake so the
        // user gets a clear message instead of an opaque "Auth fail" from JSch.
        if (password.isBlank() && keyId == null) {
            throw LibvirtException(
                "No credentials found for ${profile.host}. " +
                "Re-open Hypervisor Settings and re-enter the password."
            )
        }

        val jsch = JSch()
        val config = java.util.Properties()
        // Disable strict host-key checking for hypervisor connections;
        // the hypervisor UI handles its own TLS pinning separately.
        config["StrictHostKeyChecking"] = "no"
        if (keyId != null) {
            // Load the SSH key. getJSchBytesWithFallback() returns JSch-native PEM bytes,
            // reconstructing them from stored PKCS#8 DER for generated keys that pre-date
            // the JSch bytes cache (and caching the result for future connects).
            val jschBytes = app.keyStorage.getJSchBytesWithFallback(keyId)
            if (jschBytes != null) {
                // Prefer the byte-array addIdentity variant so we never write a
                // temp file (avoids data leaks on unencrypted external storage).
                val storedKey = app.database.keyDao().getKeyById(keyId)
                val certBytes = storedKey?.certificate
                    ?.takeIf { it.isNotBlank() }
                    ?.toByteArray(Charsets.US_ASCII)
                jsch.addIdentity(
                    "tabssh-libvirt-$keyId",
                    jschBytes,
                    certBytes,
                    null  // passphrase — LIBVIRT keys stored unencrypted in Keystore
                )
                config["PreferredAuthentications"] = "publickey,password"
                Logger.i(TAG, "SSH key identity loaded for ${profile.host} (keyId=$keyId)")
            } else {
                Logger.w(TAG, "sshIdentityId=$keyId set but JSch bytes not found — falling back to password")
                config["PreferredAuthentications"] = "password"
            }
        } else {
            config["PreferredAuthentications"] = "password"
        }

        val sess = jsch.getSession(profile.username, profile.host, profile.port)
        sess.setPassword(password)
        sess.setConfig(config)
        sess.connect(CONNECT_TIMEOUT_MS)
        session = sess
        Logger.i(TAG, "SSH session established to ${profile.host}:${profile.port}")
    }

    // ── virsh commands ────────────────────────────────────────────────────────

    /**
     * Returns all domains reported by `virsh list --all`.
     * Parses the table output:
     * ```
     *  Id   Name        State
     * ----------------------------
     *  1    myvm        running
     *  -    stopped-vm  shut off
     * ```
     */
    suspend fun listDomains(): List<LibvirtVm> = withContext(Dispatchers.IO) {
        val output = runCommand("virsh list --all")
        val lines = output.lines()
        val result = mutableListOf<LibvirtVm>()
        // Skip header lines (dashes separator and column header)
        var pastHeader = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("---") || trimmed.startsWith("Id")) {
                pastHeader = true
                continue
            }
            if (!pastHeader || trimmed.isEmpty()) continue

            // Format: " Id   Name   State..." — split on 2+ spaces
            val parts = trimmed.split(Regex("\\s{2,}"), limit = 3)
            if (parts.size < 3) continue
            val idStr = parts[0].trim()
            val name = parts[1].trim()
            val state = parts[2].trim()
            val id = if (idStr == "-") -1 else idStr.toIntOrNull() ?: -1
            result += LibvirtVm(id = id, name = name, state = state)
        }
        Logger.d(TAG, "listDomains: found ${result.size} domain(s)")
        result
    }

    // ── Power management ──────────────────────────────────────────────────────

    /**
     * Start a shut-off or paused domain via `virsh start <domain>`.
     * Throws [LibvirtException] if virsh reports an error.
     */
    suspend fun startDomain(domain: String) = withContext(Dispatchers.IO) {
        val output = runCommand("virsh start $domain 2>&1").trim()
        if (!output.contains("started") && !output.contains("Domain '$domain' started")) {
            // virsh exit code is not surfaced via JSch exec channel exit status
            // reliably on all distros; check stdout instead.
            if (output.contains("error:") || output.contains("failed")) {
                throw LibvirtException("virsh start failed: $output")
            }
        }
        Logger.i(TAG, "startDomain($domain): $output")
    }

    /**
     * Gracefully shut down a running domain via `virsh shutdown <domain>`.
     * The domain transitions to "shut off" asynchronously; callers should
     * poll [listDomains] to confirm.
     */
    suspend fun shutdownDomain(domain: String) = withContext(Dispatchers.IO) {
        val output = runCommand("virsh shutdown $domain 2>&1").trim()
        if (output.contains("error:") || output.contains("failed")) {
            throw LibvirtException("virsh shutdown failed: $output")
        }
        Logger.i(TAG, "shutdownDomain($domain): $output")
    }

    /**
     * Gracefully reboot a running domain via `virsh reboot <domain>`.
     */
    suspend fun rebootDomain(domain: String) = withContext(Dispatchers.IO) {
        val output = runCommand("virsh reboot $domain 2>&1").trim()
        if (output.contains("error:") || output.contains("failed")) {
            throw LibvirtException("virsh reboot failed: $output")
        }
        Logger.i(TAG, "rebootDomain($domain): $output")
    }

    /**
     * Hard-reset a domain (equivalent to pulling the power cord) via
     * `virsh reset <domain>`. Use only when graceful reboot is unresponsive.
     */
    suspend fun resetDomain(domain: String) = withContext(Dispatchers.IO) {
        val output = runCommand("virsh reset $domain 2>&1").trim()
        if (output.contains("error:") || output.contains("failed")) {
            throw LibvirtException("virsh reset failed: $output")
        }
        Logger.i(TAG, "resetDomain($domain): $output")
    }

    /**
     * Returns the VNC display number for [domain] by running
     * `virsh vncdisplay <domain>` and parsing e.g. `:1` or `localhost:1`.
     * Throws [LibvirtException] if the domain has no VNC display configured.
     */
    suspend fun getVncDisplay(domain: String): Int = withContext(Dispatchers.IO) {
        val output = runCommand("virsh vncdisplay $domain").trim()
        // Expected formats: ":1", "localhost:1", "127.0.0.1:1"
        val match = Regex("(?:.*:)(\\d+)").find(output)
            ?: throw LibvirtException("VNC not configured for domain '$domain' — enable display in VM XML")
        match.groupValues[1].toIntOrNull()
            ?: throw LibvirtException("Could not parse VNC display number from: $output")
    }

    /**
     * Opens a JSch `direct-tcpip` channel to the VNC port for [domain] and
     * returns its input and output streams.
     *
     * The [Session] is kept alive; call [disconnect] when the console is done.
     */
    suspend fun openVncChannel(domain: String): Pair<InputStream, OutputStream> =
        withContext(Dispatchers.IO) {
            val displayNumber = getVncDisplay(domain)
            val vncPort = 5900 + displayNumber
            Logger.d(TAG, "Opening direct-tcpip channel to 127.0.0.1:$vncPort for domain '$domain'")

            val sess = session ?: throw LibvirtException("SSH session not established; call connect() first")
            val ch = sess.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost("127.0.0.1")
            ch.setPort(vncPort)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(0)
            ch.connect(CONNECT_TIMEOUT_MS)
            Pair(ch.inputStream, ch.outputStream)
        }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Close the SSH session and all channels it owns. */
    fun disconnect() {
        try {
            session?.disconnect()
        } catch (e: Exception) {
            Logger.w(TAG, "disconnect threw: ${e.message}")
        } finally {
            session = null
        }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Run [command] via a JSch exec channel, wait for it to complete, and
     * return stdout as a String. Throws if the session is not established or
     * if the channel cannot be opened.
     */
    private fun runCommand(command: String): String {
        val sess = session ?: throw LibvirtException("SSH session not established; call connect() first")
        var ch: ChannelExec? = null
        return try {
            ch = sess.openChannel("exec") as ChannelExec
            ch.setCommand(command)
            ch.inputStream = null
            val output = ch.inputStream
            ch.connect(CONNECT_TIMEOUT_MS)

            val sb = StringBuilder()
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS
            while (!ch.isClosed && System.currentTimeMillis() < deadline) {
                val available = output.available()
                if (available > 0) {
                    val n = output.read(buf, 0, minOf(available, buf.size))
                    if (n > 0) sb.append(String(buf, 0, n, Charsets.UTF_8))
                } else {
                    Thread.sleep(50)
                }
            }
            // Drain any remaining bytes
            var n = output.read(buf)
            while (n > 0) {
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                n = output.read(buf)
            }
            Logger.d(TAG, "runCommand('$command') exit=${ch.exitStatus}")
            sb.toString()
        } finally {
            ch?.disconnect()
        }
    }
}

/** Thrown when a libvirt / virsh operation fails for a domain-level reason. */
class LibvirtException(message: String) : Exception(message)

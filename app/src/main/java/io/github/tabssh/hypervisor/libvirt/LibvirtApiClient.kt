package io.github.tabssh.hypervisor.libvirt

import android.content.Context
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.hypervisor.spice.SpiceConnectionParams
import io.github.tabssh.hypervisor.spice.SpiceLoader
import io.github.tabssh.ssh.connection.HostKeyAction
import io.github.tabssh.ssh.connection.HostKeyVerifier
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        // Wire the same database-backed TOFU host-key verification the regular
        // SSH path uses, with accept-new semantics: the first key seen for a
        // host is trusted and persisted to the shared known-hosts DB; a later
        // CHANGED key is rejected and the handshake fails closed. This replaces
        // the previous StrictHostKeyChecking=no, which defeated MITM protection
        // on the hypervisor management channel. The console flow has no
        // interactive host-key prompt, so new hosts are auto-accepted (TOFU)
        // and changed keys are hard-rejected rather than surfacing a dialog —
        // matching the accept-new default documented on HypervisorProfile.
        val hostKeyVerifier = HostKeyVerifier(context)
        hostKeyVerifier.setNewHostKeyCallback { HostKeyAction.ACCEPT_NEW_KEY }
        hostKeyVerifier.setHostKeyChangedCallback { HostKeyAction.REJECT_CONNECTION }
        jsch.hostKeyRepository = hostKeyVerifier

        val config = java.util.Properties()
        // "ask" routes verification through hostKeyRepository.check() above;
        // with no UserInfo set, a rejected (changed) key fails closed.
        config["StrictHostKeyChecking"] = "ask"
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

    // ── Shell-safety helpers ───────────────────────────────────────────────────

    /**
     * POSIX single-quote-escape [value] for safe interpolation into a shell
     * command string. Wraps the value in single quotes and renders any embedded
     * single quote as the `'\''` sequence, so no shell metacharacter in [value]
     * can break out of the argument. This is the injection barrier for domain
     * names, which may originate from imported profiles or the remote host —
     * e.g. a VM named `x; rm -rf ~` is passed as one literal argument.
     */
    private fun shQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * True when [output] carries a virsh diagnostic. virsh always emits errors
     * as a line beginning `error:`, so the check is anchored to line start.
     *
     * A bare `contains("error:")`/`contains("failed")` scan of the whole output
     * misfires on the object's own name: virsh echoes it back on success, so a
     * domain or snapshot legitimately named `failed-boot` would report every
     * successful operation — and every listing that includes it — as a failure.
     */
    private fun isVirshError(output: String): Boolean =
        output.lineSequence().any { it.trimStart().startsWith("error:") }

    /**
     * Reject domain names that are empty or contain whitespace or a NUL byte.
     * libvirt domain names never contain these, and the `virsh list` parser
     * splits on whitespace so a space would corrupt enumeration anyway.
     * Single-quoting already neutralises shell
     * metacharacters; this is a defence-in-depth guard that fails fast with a
     * clear message rather than sending a malformed command to the host.
     */
    private fun requireValidDomain(domain: String): String {
        if (domain.isBlank()) {
            throw LibvirtException("Domain name is empty")
        }
        if (domain.any { it.isWhitespace() || it == '\u0000' }) {
            throw LibvirtException("Domain name contains illegal whitespace or control characters")
        }
        return domain
    }

    /**
     * Reject snapshot names that are empty or contain whitespace or a NUL byte.
     * The `virsh snapshot-list` table parser splits on whitespace runs, so a
     * space would corrupt enumeration. Single-quoting already neutralises shell
     * metacharacters; this is the same defence-in-depth guard as
     * [requireValidDomain], failing fast with a snapshot-specific message.
     */
    private fun requireValidSnapshotName(name: String): String {
        if (name.isBlank()) {
            throw LibvirtException("Snapshot name is empty")
        }
        if (name.any { it.isWhitespace() || it == '\u0000' }) {
            throw LibvirtException("Snapshot name contains illegal whitespace or control characters")
        }
        return name
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
        requireValidDomain(domain)
        val output = runCommand("virsh start ${shQuote(domain)} 2>&1").trim()
        if (!output.contains("started") && !output.contains("Domain '$domain' started")) {
            // virsh exit code is not surfaced via JSch exec channel exit status
            // reliably on all distros; check stdout instead.
            if (isVirshError(output)) {
                throw LibvirtException("virsh start failed: $output")
            }
        }
        Logger.i(TAG, "startDomain($domain): $output")
    }

    /**
     * Hard-stop a running domain immediately via `virsh destroy <domain>`.
     * Equivalent to cutting power — always succeeds regardless of guest agent.
     * The domain transitions to "shut off" synchronously from libvirt's view.
     */
    suspend fun destroyDomain(domain: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh destroy ${shQuote(domain)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh destroy failed: $output")
        }
        Logger.i(TAG, "destroyDomain($domain): $output")
    }

    /**
     * Gracefully shut down a running domain via `virsh shutdown <domain>`.
     * Requires the guest agent or ACPI support. Prefer [destroyDomain] when
     * a reliable stop is needed.
     */
    suspend fun shutdownDomain(domain: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh shutdown ${shQuote(domain)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh shutdown failed: $output")
        }
        Logger.i(TAG, "shutdownDomain($domain): $output")
    }

    /**
     * Gracefully reboot a running domain via `virsh reboot <domain>`.
     */
    suspend fun rebootDomain(domain: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh reboot ${shQuote(domain)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh reboot failed: $output")
        }
        Logger.i(TAG, "rebootDomain($domain): $output")
    }

    /**
     * Hard-reset a domain (equivalent to pulling the power cord) via
     * `virsh reset <domain>`. Use only when graceful reboot is unresponsive.
     */
    suspend fun resetDomain(domain: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh reset ${shQuote(domain)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh reset failed: $output")
        }
        Logger.i(TAG, "resetDomain($domain): $output")
    }

    /**
     * Returns the primary IPv4 address for [domain] by running
     * `virsh domifaddr <domain>` and extracting the first IPv4 address found.
     * Returns null if the domain has no interfaces with a known address (e.g.
     * the guest agent is not installed, or the VM was just started).
     *
     * Non-fatal — callers should treat null as "IP unknown" and still offer
     * SSH with an empty host field for the user to fill in.
     */
    suspend fun getVmIpAddress(domain: String): String? = withContext(Dispatchers.IO) {
        try {
            requireValidDomain(domain)
            val output = runCommand("virsh domifaddr ${shQuote(domain)} 2>/dev/null")
            // Expected line: "vnet0  52:54:00:xx:xx:xx  ipv4  192.168.1.100/24"
            Regex("""(\d{1,3}(?:\.\d{1,3}){3})""").find(output)?.groupValues?.get(1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.d(TAG, "getVmIpAddress($domain): ${e.message}")
            null
        }
    }

    /**
     * Returns the VNC display number for [domain] by running
     * `virsh vncdisplay <domain>` and parsing e.g. `:1` or `localhost:1`.
     * Throws [LibvirtException] if the domain has no VNC display configured.
     */
    suspend fun getVncDisplay(domain: String): Int = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh vncdisplay ${shQuote(domain)} 2>/dev/null").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh vncdisplay failed for domain '$domain': $output")
        }
        // Expected formats: ":1", "localhost:1", "127.0.0.1:1". Anchored so a
        // stray banner or warning line cannot be mined for a bogus number: the
        // whole (single) line must be an optional host followed by ":<digits>".
        val match = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { Regex("""^[A-Za-z0-9._\-\[\]:]*:(\d+)$""").find(it) }
            ?: throw LibvirtException("VNC not configured for domain '$domain' — enable display in VM XML")
        match.groupValues[1].toIntOrNull()
            ?: throw LibvirtException("Could not parse VNC display number from: $output")
    }

    /**
     * Returns the VNC password configured on [domain]
     * (`<graphics type='vnc' passwd='…'/>`), or null when the display needs no
     * authentication.
     *
     * `virsh domdisplay --include-password` renders it as URI userinfo —
     * `vnc://:secret@host:5901` — which avoids parsing the whole domain XML.
     * Without this, every password-protected libvirt display failed the RFB
     * VNC-Auth challenge with "server requires a password but none was set".
     *
     * Never logged: the value is a live credential.
     */
    suspend fun getVncPassword(domain: String): String? = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val line = runCommand("virsh domdisplay --include-password ${shQuote(domain)} 2>/dev/null")
            .lines().firstOrNull { it.trim().startsWith("vnc://") }?.trim()
            ?: return@withContext null
        // userinfo lives between "vnc://" and the last '@' before the host part.
        val rest = line.removePrefix("vnc://")
        val at = rest.lastIndexOf('@')
        if (at <= 0) return@withContext null
        val userinfo = rest.substring(0, at)
        // libvirt emits an empty user with the password after ':'.
        val raw = userinfo.substringAfter(':', "")
        if (raw.isEmpty()) return@withContext null
        try {
            java.net.URLDecoder.decode(raw, "UTF-8")
        } catch (e: IllegalArgumentException) {
            // Not percent-encoded (older libvirt) — take the literal bytes.
            Logger.d(TAG, "domdisplay VNC userinfo is not percent-encoded: ${e.message}")
            raw
        }
    }

    /**
     * A live `direct-tcpip` forward to a domain's VNC port.
     *
     * [channel] is returned alongside the streams so the caller can close the
     * forward when the console tab goes away. Returning only the streams leaked
     * one JSch channel (and its two pump threads) per libvirt VNC tab, because
     * closing an [InputStream] obtained from a [ChannelDirectTCPIP] does not
     * disconnect the channel.
     */
    data class VncChannel(
        val channel: ChannelDirectTCPIP,
        val input: InputStream,
        val output: OutputStream,
        val password: String?
    ) {
        /** Close the forward. Safe to call more than once. */
        fun close() {
            try { channel.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Opens a JSch `direct-tcpip` channel to the VNC port for [domain] and
     * returns it together with its input and output streams.
     *
     * The [Session] is kept alive; call [VncChannel.close] when the console
     * tab closes, and [disconnect] when the whole client is done.
     */
    suspend fun openVncChannel(domain: String): VncChannel =
        withContext(Dispatchers.IO) {
            val displayNumber = getVncDisplay(domain)
            val vncPort = 5900 + displayNumber
            val vncPassword = getVncPassword(domain)
            Logger.d(TAG, "Opening direct-tcpip channel to 127.0.0.1:$vncPort for domain '$domain'")

            val sess = session ?: throw LibvirtException("SSH session not established; call connect() first")
            val ch = sess.openChannel("direct-tcpip") as ChannelDirectTCPIP
            try {
                ch.setHost("127.0.0.1")
                ch.setPort(vncPort)
                ch.setOrgIPAddress("127.0.0.1")
                ch.setOrgPort(0)
                // JSch requires getInputStream/getOutputStream to be called BEFORE
                // connect() — calling them after triggers a "getInputStream() should
                // be called before connect()" warning and may return stale references.
                val ins = ch.inputStream
                val out = ch.outputStream
                ch.connect(CONNECT_TIMEOUT_MS)
                VncChannel(ch, ins, out, vncPassword)
            } catch (e: Throwable) {
                // connect() can throw on timeout / network failure; the channel
                // is still attached to the Session and must be explicitly
                // disconnected or it leaks until the Session itself closes.
                try { ch.disconnect() } catch (_: Exception) {}
                throw e
            }
        }

    // ── SPICE display ────────────────────────────────────────────────────────

    /**
     * Result of a successful [getSpiceDisplay] probe: the SSH-forwarded local
     * port (kept so [stopSpiceForward] can tear the forward down when the
     * console tab closes) plus ready-to-use [SpiceConnectionParams] pointing
     * the native client at `127.0.0.1:<localPort>`.
     */
    data class SpiceDisplay(
        val localPort: Int,
        val params: SpiceConnectionParams
    )

    /**
     * Probe [domain] for a SPICE display via `virsh domdisplay` and, when one
     * exists, tunnel it over this client's SSH session with a local port
     * forward — same keep-the-display-off-the-network model as [openVncChannel].
     *
     * Returns null — silently, per the console fallback contract (Logger.i
     * only) — when the native SPICE library is not shipped in this APK, the
     * domain has no SPICE display (VNC-only or headless), the domdisplay URI
     * is unparsable, or the port forward cannot be established; the caller
     * falls through to the VNC path. Throws only when the SSH session itself
     * is unusable.
     */
    suspend fun getSpiceDisplay(domain: String): SpiceDisplay? = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        if (!SpiceLoader.isSpiceAvailable()) {
            Logger.i(TAG, "getSpiceDisplay($domain): native SPICE library not present — VNC fallback")
            return@withContext null
        }
        // --include-password embeds the SPICE ticket as URI userinfo
        // (spice://:ticket@host:port); without it a password-protected
        // display would pass the transport handshake and then fail auth.
        val output = runCommand("virsh domdisplay --include-password ${shQuote(domain)} 2>/dev/null")
            .lines().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        if (!output.startsWith("spice://")) {
            Logger.i(TAG, "getSpiceDisplay($domain): no SPICE display (domdisplay='$output') — VNC fallback")
            return@withContext null
        }
        val parsed = parseSpiceUri(output)
        if (parsed == null) {
            Logger.i(TAG, "getSpiceDisplay($domain): unparsable domdisplay URI — VNC fallback")
            return@withContext null
        }
        val sess = session ?: throw LibvirtException("SSH session not established; call connect() first")
        // Prefer the plaintext port: the SSH tunnel already encrypts and
        // authenticates the hop, so SPICE-level TLS adds only a certificate
        // that could never validate against the 127.0.0.1 tunnel endpoint.
        val remotePort = if (parsed.port > 0) parsed.port else parsed.tlsPort
        val localPort = try {
            // lport 0 lets JSch pick a free local port and return it
            sess.setPortForwardingL(0, parsed.host, remotePort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.i(TAG, "getSpiceDisplay($domain): port forward to ${parsed.host}:$remotePort failed: ${e.message} — VNC fallback")
            return@withContext null
        }
        Logger.i(TAG, "SPICE display for '$domain' at ${parsed.host}:$remotePort forwarded to 127.0.0.1:$localPort")
        val params = if (parsed.port > 0) {
            SpiceConnectionParams(
                host = "127.0.0.1",
                port = localPort,
                tlsPort = 0,
                password = parsed.password
            )
        } else {
            // TLS-only display: tunnel the TLS port but skip chain validation —
            // the server certificate names the hypervisor host, not 127.0.0.1,
            // and the SSH tunnel already provides transport integrity/authenticity.
            SpiceConnectionParams(
                host = "127.0.0.1",
                port = 0,
                tlsPort = localPort,
                password = parsed.password,
                tlsVerify = false
            )
        }
        SpiceDisplay(localPort, params)
    }

    /**
     * Remove the local port forward created by [getSpiceDisplay]. Safe to call
     * after the session is gone — a closed session tears down all forwards
     * itself, so a failure here is log-only.
     */
    fun stopSpiceForward(localPort: Int) {
        try {
            session?.delPortForwardingL(localPort)
            Logger.d(TAG, "stopSpiceForward: removed local forward on port $localPort")
        } catch (e: Exception) {
            Logger.d(TAG, "stopSpiceForward($localPort): ${e.message}")
        }
    }

    /** Parsed pieces of a `virsh domdisplay` `spice://` URI. */
    private data class ParsedSpiceUri(
        val password: String,
        val host: String,
        val port: Int,
        val tlsPort: Int
    )

    /**
     * Parse `spice://[:password@]host[:port][?tls-port=N&...]` as produced by
     * `virsh domdisplay --include-password` (see libvirt's cmdDomDisplay).
     * Returns null when the URI does not match that shape or carries neither
     * a plain port nor a tls-port. IPv6 listen addresses arrive bracketed
     * (`spice://[::1]:5900`) and are unwrapped for the forward target.
     */
    private fun parseSpiceUri(uri: String): ParsedSpiceUri? {
        val match = Regex("^spice://(?:([^@/?]*)@)?(\\[[^\\]]*\\]|[^:/?]*)(?::(\\d+))?(?:\\?(.*))?$")
            .find(uri) ?: return null
        // Userinfo is ":ticket" (empty user part); tolerate a bare value too.
        val userinfo = match.groupValues[1]
        val password = userinfo.substringAfter(':', missingDelimiterValue = userinfo)
        val rawHost = match.groupValues[2].removeSurrounding("[", "]")
        // An empty listen address means the display binds localhost on the hypervisor.
        val host = rawHost.ifBlank { "127.0.0.1" }
        val port = match.groupValues[3].toIntOrNull() ?: 0
        var tlsPort = 0
        for (param in match.groupValues[4].split('&')) {
            val kv = param.split('=', limit = 2)
            if (kv.size == 2 && kv[0] == "tls-port") tlsPort = kv[1].toIntOrNull() ?: 0
        }
        if (port == 0 && tlsPort == 0) return null
        return ParsedSpiceUri(password, host, port, tlsPort)
    }

    // ── Snapshots ────────────────────────────────────────────────────────────

    /** One snapshot of a domain as reported by `virsh snapshot-list`. */
    data class LibvirtSnapshot(
        val name: String,
        val creationTime: String,
        val state: String
    )

    /**
     * Returns all snapshots of [domain] reported by `virsh snapshot-list <domain>`.
     * Parses the table output:
     * ```
     *  Name    Creation Time               State
     * ---------------------------------------------
     *  clean   2026-01-01 12:00:00 +0000   shutoff
     * ```
     * Throws [LibvirtException] if virsh reports an error.
     */
    suspend fun listSnapshots(domain: String): List<LibvirtSnapshot> = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        val output = runCommand("virsh snapshot-list ${shQuote(domain)} 2>&1")
        if (isVirshError(output)) {
            throw LibvirtException("virsh snapshot-list failed: ${output.trim()}")
        }
        val result = mutableListOf<LibvirtSnapshot>()
        // Skip header lines (dashes separator and column header)
        var pastHeader = false
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("---") || trimmed.startsWith("Name")) {
                pastHeader = true
                continue
            }
            if (!pastHeader || trimmed.isEmpty()) continue

            // Format: " Name   Creation Time   State" — split on 2+ spaces
            val parts = trimmed.split(Regex("\\s{2,}"), limit = 3)
            if (parts.size < 3) continue
            result += LibvirtSnapshot(
                name = parts[0].trim(),
                creationTime = parts[1].trim(),
                state = parts[2].trim()
            )
        }
        Logger.d(TAG, "listSnapshots($domain): found ${result.size} snapshot(s)")
        result
    }

    /**
     * Create a snapshot of [domain] named [name] via
     * `virsh snapshot-create-as <domain> --name <name>`.
     * Throws [LibvirtException] if virsh reports an error.
     */
    suspend fun createSnapshot(domain: String, name: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        requireValidSnapshotName(name)
        val output = runCommand("virsh snapshot-create-as ${shQuote(domain)} --name ${shQuote(name)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh snapshot-create-as failed: $output")
        }
        Logger.i(TAG, "createSnapshot($domain, $name): $output")
    }

    /**
     * Revert [domain] to snapshot [name] via
     * `virsh snapshot-revert <domain> --snapshotname <name>`.
     * Throws [LibvirtException] if virsh reports an error.
     */
    suspend fun revertSnapshot(domain: String, name: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        requireValidSnapshotName(name)
        val output = runCommand("virsh snapshot-revert ${shQuote(domain)} --snapshotname ${shQuote(name)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh snapshot-revert failed: $output")
        }
        Logger.i(TAG, "revertSnapshot($domain, $name): $output")
    }

    /**
     * Delete snapshot [name] of [domain] via
     * `virsh snapshot-delete <domain> --snapshotname <name>`.
     * Throws [LibvirtException] if virsh reports an error.
     */
    suspend fun deleteSnapshot(domain: String, name: String) = withContext(Dispatchers.IO) {
        requireValidDomain(domain)
        requireValidSnapshotName(name)
        val output = runCommand("virsh snapshot-delete ${shQuote(domain)} --snapshotname ${shQuote(name)} 2>&1").trim()
        if (isVirshError(output)) {
            throw LibvirtException("virsh snapshot-delete failed: $output")
        }
        Logger.i(TAG, "deleteSnapshot($domain, $name): $output")
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
    private suspend fun runCommand(command: String): String {
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
                    // delay() honours coroutine cancellation; Thread.sleep() does not.
                    delay(50)
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

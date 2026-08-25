package io.github.tabssh.ssh.connection

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.dao.HostKeyVerificationResult
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import android.util.Base64

/**
 * Custom HostKeyRepository that integrates with TabSSH's database
 * Implements JSch's HostKeyRepository interface for host key verification
 */
class HostKeyVerifier(private val context: Context) : HostKeyRepository {

    private val database = TabSSHDatabase.getDatabase(context)
    private val hostKeyDao = database.hostKeyDao()

    // Registered from the UI thread, invoked from JSch's handshake thread —
    // volatile so a freshly registered callback is never missed.
    @Volatile private var hostKeyChangedCallback: ((HostKeyChangedInfo) -> HostKeyAction)? = null
    @Volatile private var newHostKeyCallback: ((NewHostKeyInfo) -> HostKeyAction)? = null

    /**
     * Set callback for when host key changes are detected
     */
    fun setHostKeyChangedCallback(callback: (HostKeyChangedInfo) -> HostKeyAction) {
        hostKeyChangedCallback = callback
    }

    /**
     * Set callback for when connecting to a new (unknown) host
     */
    fun setNewHostKeyCallback(callback: (NewHostKeyInfo) -> HostKeyAction) {
        newHostKeyCallback = callback
    }

    /**
     * Check if host key is acceptable
     * Called by JSch during connection
     */
    override fun check(host: String, key: ByteArray): Int {
        return try {
            Logger.i("HostKeyVerifier", "HOST KEY CHECK CALLED for: $host")
            
            val (hostname, port) = parseHostPort(host)
            Logger.d("HostKeyVerifier", "Parsed: hostname=$hostname, port=$port")

            // Generate fingerprint
            val fingerprint = generateFingerprint(key)
            val keyType = detectKeyType(key)
            val publicKeyBase64 = Base64.encodeToString(key, Base64.NO_WRAP)

            Logger.i("HostKeyVerifier", "Checking host key for $hostname:$port ($keyType)")
            Logger.i("HostKeyVerifier", "Fingerprint: $fingerprint")

            ensureCacheStarted(hostKeyDao)

            // Fast path (audit O5): serve known-good hosts from the in-memory
            // cache so the JSch handshake thread never blocks on a DB round-trip.
            // Any miss or mismatch — including a cold or stale cache — falls
            // through to the authoritative transactional verify below, so
            // staleness can only produce an extra prompt, never a silent accept
            // of an unknown key (the removed-key window matches the pre-existing
            // check-then-connect race and is bounded by Room's Flow invalidation).
            val cachedEntry = hostKeyCache?.get("$hostname:$port")
            val result = if (cachedEntry != null && cachedEntry.publicKey == publicKeyBase64) {
                // Bookkeeping (last-verified bump / fingerprint self-heal) is not
                // security-relevant — run it asynchronously off the handshake thread.
                cacheScope.launch {
                    hostKeyDao.verifyHostKey(hostname, port, publicKeyBase64, fingerprint)
                }
                HostKeyVerificationResult.ACCEPTED
            } else {
                // Verify against database on the dedicated host-key DB thread
                // (hostKeyDbDispatcher) so a bulk reconnect cannot starve the
                // shared IO pool — see the companion-object kdoc.
                Logger.d("HostKeyVerifier", "Querying database for existing host key...")
                runBlocking(hostKeyDbDispatcher) {
                    hostKeyDao.verifyHostKey(hostname, port, publicKeyBase64, fingerprint)
                }
            }
            Logger.i("HostKeyVerifier", "Verification result: $result")

            when (result) {
                HostKeyVerificationResult.ACCEPTED -> {
                    Logger.i("HostKeyVerifier", "Host key verified: $hostname:$port")
                    HostKeyRepository.OK
                }

                HostKeyVerificationResult.NEW_HOST -> {
                    Logger.i("HostKeyVerifier", "NEW HOST: $hostname:$port - Asking user for confirmation")

                    val info = NewHostKeyInfo(
                        hostname = hostname,
                        port = port,
                        keyType = keyType,
                        fingerprint = fingerprint,
                        publicKey = publicKeyBase64
                    )

                    // Ask user what to do with this new host
                    Logger.i("HostKeyVerifier", "Invoking new host key callback... (callback is ${if (newHostKeyCallback != null) "SET" else "NULL"})")

                    // Snapshot the mutable callback ref so a concurrent
                    // setter cannot null it between the check and invoke.
                    val cb = newHostKeyCallback
                    val action = if (cb != null) {
                        cb.invoke(info)
                    } else {
                        // No callback available - show blocking dialog directly
                        Logger.w("HostKeyVerifier", "No callback available - showing blocking dialog")
                        showBlockingNewHostDialog(info)
                    }

                    Logger.i("HostKeyVerifier", "User action for new host: $action")

                    return when (action) {
                        HostKeyAction.ACCEPT_NEW_KEY -> {
                            runBlocking(hostKeyDbDispatcher) {
                                val hostKeyEntry = HostKeyEntry(
                                    id = HostKeyEntry.createId(hostname, port),
                                    hostname = hostname,
                                    port = port,
                                    keyType = keyType,
                                    publicKey = publicKeyBase64,
                                    fingerprint = fingerprint,
                                    trustLevel = "ACCEPTED"
                                )
                                hostKeyDao.insertOrUpdateHostKey(hostKeyEntry)
                                Logger.i("HostKeyVerifier", "User accepted and stored new host key for $hostname:$port")
                            }
                            HostKeyRepository.OK
                        }

                        HostKeyAction.REJECT_CONNECTION -> {
                            Logger.w("HostKeyVerifier", "User rejected new host key for $hostname:$port")
                            HostKeyRepository.NOT_INCLUDED
                        }

                        HostKeyAction.ACCEPT_ONCE -> {
                            Logger.i("HostKeyVerifier", "User accepted host key ONCE for $hostname:$port (not stored)")
                            HostKeyRepository.OK
                        }
                    }
                }

                HostKeyVerificationResult.CHANGED_KEY -> {
                    Logger.w("HostKeyVerifier", "WARNING:HOST KEY HAS CHANGED for $hostname:$port")

                    // Get existing key for comparison
                    val existingKey = runBlocking(hostKeyDbDispatcher) {
                        hostKeyDao.getHostKey(hostname, port)
                    }

                    if (existingKey != null) {
                        Logger.w("HostKeyVerifier", "Old fingerprint: ${existingKey.fingerprint}")
                        Logger.w("HostKeyVerifier", "New fingerprint: $fingerprint")
                        
                        val info = HostKeyChangedInfo(
                            hostname = hostname,
                            port = port,
                            oldKeyType = existingKey.keyType,
                            newKeyType = keyType,
                            oldFingerprint = existingKey.fingerprint,
                            newFingerprint = fingerprint,
                            oldPublicKey = existingKey.publicKey,
                            newPublicKey = publicKeyBase64,
                            firstSeen = existingKey.firstSeen,
                            lastVerified = existingKey.lastVerified
                        )

                        // ALWAYS ask user what to do - never fail silently
                        Logger.i("HostKeyVerifier", "Invoking host key changed callback... (callback is ${if (hostKeyChangedCallback != null) "SET" else "NULL"})")

                        // Snapshot the mutable callback ref so a concurrent
                        // setter cannot null it between the check and invoke.
                        val cb = hostKeyChangedCallback
                        val action = if (cb != null) {
                            cb.invoke(info)
                        } else {
                            // No callback available - show blocking dialog directly
                            Logger.w("HostKeyVerifier", "No callback available - showing blocking dialog for changed key")
                            showBlockingChangedHostDialog(info)
                        }

                        Logger.i("HostKeyVerifier", "User action: $action")

                        return when (action) {
                            HostKeyAction.ACCEPT_NEW_KEY -> {
                                runBlocking(hostKeyDbDispatcher) {
                                    // Replace old key with new key
                                    val updatedEntry = HostKeyEntry(
                                        id = HostKeyEntry.createId(hostname, port),
                                        hostname = hostname,
                                        port = port,
                                        keyType = keyType,
                                        publicKey = publicKeyBase64,
                                        fingerprint = fingerprint,
                                        trustLevel = "ACCEPTED"
                                    )
                                    hostKeyDao.insertOrUpdateHostKey(updatedEntry)
                                    Logger.i("HostKeyVerifier", "User accepted new host key for $hostname:$port")
                                }
                                HostKeyRepository.OK
                            }

                            HostKeyAction.REJECT_CONNECTION -> {
                                Logger.w("HostKeyVerifier", "User rejected changed host key for $hostname:$port")
                                HostKeyRepository.NOT_INCLUDED
                            }

                            HostKeyAction.ACCEPT_ONCE -> {
                                Logger.i("HostKeyVerifier", "User accepted host key ONCE for $hostname:$port")
                                // Don't store, just allow this connection
                                HostKeyRepository.OK
                            }
                        }
                    } else {
                        // Should never happen, but treat as new host if no existing key found
                        Logger.w("HostKeyVerifier", "CHANGED_KEY result but no existing key in database")
                        HostKeyRepository.NOT_INCLUDED
                    }
                }

                HostKeyVerificationResult.INVALID_KEY -> {
                    Logger.e("HostKeyVerifier", "Invalid host key for $hostname:$port")
                    HostKeyRepository.NOT_INCLUDED
                }
            }

        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error verifying host key", e)
            HostKeyRepository.NOT_INCLUDED
        }
    }

    /**
     * Add a host key to the repository
     */
    override fun add(hostkey: HostKey, userInfo: UserInfo?) {
        try {
            val (hostname, port) = parseHostPort(hostkey.host)
            // JSch's HostKey.getKey() returns the key Base64-encoded (a String,
            // not raw bytes). The old `hostkey.key as ByteArray` cast could never
            // succeed — it threw ClassCastException on every call, the catch below
            // swallowed it, and add() never persisted a key. Decode for the
            // fingerprint and store the Base64 string directly.
            val keyBytes = Base64.decode(hostkey.key, Base64.DEFAULT)
            // Use our own fingerprint generator since JSch's method signature may vary
            val fingerprint = generateFingerprint(keyBytes)

            runBlocking(hostKeyDbDispatcher) {
                val entry = HostKeyEntry(
                    id = HostKeyEntry.createId(hostname, port),
                    hostname = hostname,
                    port = port,
                    keyType = hostkey.type,
                    publicKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP),
                    fingerprint = fingerprint,
                    trustLevel = "ACCEPTED"
                )
                hostKeyDao.insertOrUpdateHostKey(entry)
                Logger.i("HostKeyVerifier", "Added host key for $hostname:$port")
            }
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error adding host key", e)
        }
    }

    /**
     * Remove a host key from the repository
     */
    override fun remove(host: String, type: String?) {
        try {
            val (hostname, port) = parseHostPort(host)
            runBlocking(hostKeyDbDispatcher) {
                if (type != null) {
                    // Remove specific key type for host
                    val existing = hostKeyDao.getHostKey(hostname, port)
                    if (existing?.keyType == type) {
                        hostKeyDao.deleteHostKeyById(existing.id)
                        Logger.i("HostKeyVerifier", "Removed $type key for $hostname:$port")
                    }
                } else {
                    // Remove all keys for host
                    hostKeyDao.deleteHostKeyById(HostKeyEntry.createId(hostname, port))
                    Logger.i("HostKeyVerifier", "Removed all keys for $hostname:$port")
                }
            }
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error removing host key", e)
        }
    }

    /**
     * Remove a host key from the repository
     */
    override fun remove(host: String, type: String?, key: ByteArray?) {
        remove(host, type)
    }

    /**
     * Get repository ID (required by JSch interface)
     */
    override fun getKnownHostsRepositoryID(): String = "TabSSH Host Key Database"

    /**
     * Get all known host keys
     */
    override fun getHostKey(): Array<HostKey> {
        return try {
            runBlocking(hostKeyDbDispatcher) {
                val entries = hostKeyDao.getAllHostKeys().first()
                entries.map { entry ->
                    val hostPort = "${entry.hostname}:${entry.port}"
                    val keyBytes = Base64.decode(entry.publicKey, Base64.DEFAULT)
                    // JSch HostKey constructor: HostKey(String host, int type, byte[] key)
                    // The 'type' parameter is an integer constant
                    HostKey(hostPort, keyTypeToInt(entry.keyType), keyBytes)
                }.toTypedArray()
            }
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error getting host keys", e)
            emptyArray()
        }
    }

    /**
     * Get host keys for specific host
     */
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        if (host == null) return emptyArray()

        return try {
            val (hostname, port) = parseHostPort(host)
            runBlocking(hostKeyDbDispatcher) {
                val entry = hostKeyDao.getHostKey(hostname, port)
                if (entry != null && (type == null || entry.keyType == type)) {
                    val keyBytes = Base64.decode(entry.publicKey, Base64.DEFAULT)
                    // JSch HostKey constructor: HostKey(String host, int type, byte[] key)
                    // The 'type' parameter is an integer constant
                    arrayOf(HostKey(host, keyTypeToInt(entry.keyType), keyBytes))
                } else {
                    emptyArray()
                }
            }
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error getting host key for $host", e)
            emptyArray()
        }
    }

    /**
     * Clear all host keys from database.
     * Unlike check()/add()/remove()/getHostKey() this is not a JSch
     * HostKeyRepository override, so it has no synchronous-caller constraint
     * and can be a genuine suspend function instead of blocking via runBlocking.
     */
    suspend fun clearAllHostKeys() {
        withContext(hostKeyDbDispatcher) {
            hostKeyDao.deleteAllHostKeys()
            Logger.i("HostKeyVerifier", "Cleared all host keys")
        }
    }

    /**
     * Parse hostname and port from host string
     */
    internal fun parseHostPort(host: String): Pair<String, Int> {
        val trimmed = host.trim()

        // Bracketed IPv6 literal, optionally with a port: "[::1]" or "[2001:db8::1]:2222".
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            if (close > 0) {
                val addr = trimmed.substring(1, close)
                val rest = trimmed.substring(close + 1)
                val port = if (rest.startsWith(":")) validPort(rest.substring(1)) else DEFAULT_SSH_PORT
                return Pair(addr, port)
            }
        }

        // Bare IPv6 literal (two or more colons, no brackets) carries no port component;
        // splitting on ":" would corrupt the address, so return it verbatim.
        if (trimmed.count { it == ':' } > 1) {
            return Pair(trimmed, DEFAULT_SSH_PORT)
        }

        // "host:port" or a bare host/IPv4. Split on the last colon so hostnames survive.
        val idx = trimmed.lastIndexOf(':')
        return if (idx > 0) {
            Pair(trimmed.substring(0, idx), validPort(trimmed.substring(idx + 1)))
        } else {
            Pair(trimmed, DEFAULT_SSH_PORT)
        }
    }

    /**
     * Parse a port component, falling back to 22 for anything that is not a
     * valid TCP port. An out-of-range value here would be stored on the host
     * key row and make the lookup for the real host:22 entry miss, silently
     * downgrading a known host to an unknown one.
     */
    private fun validPort(raw: String): Int =
        raw.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_SSH_PORT

    /**
     * Generate SHA256 fingerprint from key bytes
     */
    private fun generateFingerprint(key: ByteArray): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key)

            // OpenSSH SHA256 fingerprint: "SHA256:" + unpadded base64 of the
            // raw digest, matching `ssh-keygen -l` so users can cross-check.
            // Use android.util.Base64 (API 1) — java.util.Base64 is API 26 and
            // this module targets minSdk 21 with no core-library desugaring.
            val b64 = android.util.Base64.encodeToString(
                hash,
                android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            "SHA256:$b64"
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error generating fingerprint", e)
            "UNKNOWN"
        }
    }

    /**
     * Detect key type from key bytes.
     *
     * An SSH public key blob (RFC 4253 §6.6) starts with a uint32 big-endian
     * length followed by exactly that many bytes of the algorithm name. Read
     * that field instead of substring-searching the whole blob: the blob's
     * modulus/point bytes are attacker-influenced, so a server could embed the
     * ASCII "ssh-ed25519" inside an RSA key's payload and have this report the
     * wrong algorithm — which then decides which stored key row this one is
     * compared against.
     */
    internal fun detectKeyType(key: ByteArray): String {
        return try {
            if (key.size < 4) return "unknown"
            val len = ((key[0].toInt() and 0xFF) shl 24) or
                ((key[1].toInt() and 0xFF) shl 16) or
                ((key[2].toInt() and 0xFF) shl 8) or
                (key[3].toInt() and 0xFF)
            // Algorithm names are short ASCII identifiers; anything else means
            // this is not a well-formed key blob.
            if (len !in 1..64 || 4 + len > key.size) return "unknown"
            val name = String(key, 4, len, Charsets.US_ASCII)
            if (name in KNOWN_KEY_TYPES) name else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Convert string key type to JSch integer constant
     * JSch uses: 0=RSA, 1=DSS/DSA, 2=ECDSA256, 3=ECDSA384, 4=ECDSA521, 5=ED25519
     */
    private fun keyTypeToInt(keyTypeStr: String): Int {
        return when (keyTypeStr) {
            // HostKey.SSHRSA
            "ssh-rsa" -> 0
            // HostKey.SSHDSS
            "ssh-dss" -> 1
            "ecdsa-sha2-nistp256" -> 2
            "ecdsa-sha2-nistp384" -> 3
            "ecdsa-sha2-nistp521" -> 4
            "ssh-ed25519" -> 5
            // Default to RSA
            else -> 0
        }
    }

    /**
     * Show a blocking dialog for new host key verification.
     * Used when no callback is available (fallback mechanism).
     *
     * Robustness: previously the latch had no timeout and relied on
     * `setOnDismissListener` to release on activity destruction. That's
     * only true if `MaterialAlertDialogBuilder.show()` actually succeeds — if
     * the activity died between resolution and the runOnUiThread post
     * (or if Builder().show() throws because the activity is in a bad
     * state), the dismiss listener never fires and the JSch thread
     * hangs forever. Now: pre-check `isFinishing/isDestroyed`, default
     * to REJECT after 30s if no user response arrives.
     */
    private fun showBlockingNewHostDialog(info: NewHostKeyInfo): HostKeyAction =
        showBlockingHostDialog(
            title = "New Host Key",
            message = info.getDisplayMessage(),
            icon = null,
            positiveLabel = "Accept & Save",
            neutralLabel = "Accept Once",
            negativeLabel = "Reject",
            logTag = "new host: ${info.hostname}:${info.port}"
        )

    /**
     * Show a blocking dialog for changed host key verification.
     * Used when no callback is available (fallback mechanism).
     */
    private fun showBlockingChangedHostDialog(info: HostKeyChangedInfo): HostKeyAction =
        showBlockingHostDialog(
            title = "WARNING: Host Key Changed!",
            message = info.getDisplayMessage(),
            icon = android.R.drawable.ic_dialog_alert,
            positiveLabel = "Accept New Key",
            neutralLabel = "Accept Once",
            negativeLabel = "Reject (Recommended)",
            logTag = "CHANGED host key: ${info.hostname}:${info.port}"
        )

    /**
     * Common machinery for the two host-key dialogs. Resolves an
     * Activity from the context chain or from the app's
     * current-activity tracking, posts the dialog to the UI thread,
     * and waits with a hard timeout. On any failure path (no Activity,
     * Activity already finishing, dialog throws on show, latch
     * timeout, thread interrupt) the safe answer is REJECT.
     */
    private fun showBlockingHostDialog(
        title: String,
        message: String,
        icon: Int?,
        positiveLabel: String,
        neutralLabel: String,
        negativeLabel: String,
        logTag: String
    ): HostKeyAction {
        Logger.i("HostKeyVerifier", "Showing blocking dialog for $logTag")

        var userAction: HostKeyAction = HostKeyAction.REJECT_CONNECTION
        val latch = java.util.concurrent.CountDownLatch(1)

        val effectiveActivity = resolveActivity()
            ?: run {
                Logger.e("HostKeyVerifier", "Cannot show dialog ($logTag) — no Activity context, defaulting to REJECT")
                return HostKeyAction.REJECT_CONNECTION
            }

        if (effectiveActivity.isFinishing || effectiveActivity.isDestroyed) {
            Logger.e(
                "HostKeyVerifier",
                "Cannot show dialog ($logTag) — activity finishing/destroyed, defaulting to REJECT"
            )
            return HostKeyAction.REJECT_CONNECTION
        }

        effectiveActivity.runOnUiThread {
            try {
                if (effectiveActivity.isFinishing || effectiveActivity.isDestroyed) {
                    // Activity died between our pre-check and this UI-thread
                    // post — release the latch so the JSch thread doesn't hang.
                    latch.countDown()
                    return@runOnUiThread
                }
                val builder = MaterialAlertDialogBuilder(effectiveActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(positiveLabel) { _, _ ->
                        userAction = HostKeyAction.ACCEPT_NEW_KEY
                        latch.countDown()
                    }
                    .setNeutralButton(neutralLabel) { _, _ ->
                        userAction = HostKeyAction.ACCEPT_ONCE
                        latch.countDown()
                    }
                    .setNegativeButton(negativeLabel) { _, _ ->
                        userAction = HostKeyAction.REJECT_CONNECTION
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setOnDismissListener {
                        // Belt-and-suspenders: button handlers already
                        // count down, but if the dialog is dismissed for
                        // any other reason (config change, window-leak
                        // teardown) make sure the JSch thread wakes up.
                        latch.countDown()
                    }
                if (icon != null) builder.setIcon(icon)
                builder.show()
            } catch (e: Exception) {
                Logger.e("HostKeyVerifier", "Error showing host-key dialog ($logTag)", e)
                latch.countDown()
            }
        }

        try {
            // Hard 30s timeout. If the user can't be reached in that
            // window, default to REJECT — a hung connection is worse
            // than a forced disconnect.
            val signaled = latch.await(DIALOG_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            if (!signaled) {
                Logger.w(
                    "HostKeyVerifier",
                    "Dialog timeout (${DIALOG_TIMEOUT_SECONDS}s) — defaulting to REJECT for $logTag"
                )
                userAction = HostKeyAction.REJECT_CONNECTION
            }
        } catch (e: InterruptedException) {
            Logger.e("HostKeyVerifier", "Interrupted waiting for dialog response ($logTag)", e)
            userAction = HostKeyAction.REJECT_CONNECTION
            Thread.currentThread().interrupt()
        }

        Logger.i("HostKeyVerifier", "Blocking dialog result for $logTag: $userAction")
        return userAction
    }

    /**
     * Walk the context wrapper chain looking for an Activity, then fall
     * back to the application's tracked current activity. Both showBlocking*
     * methods use this — keeping it factored so the resolution rules can
     * evolve in one place.
     */
    private fun resolveActivity(): android.app.Activity? {
        val direct = when (context) {
            is android.app.Activity -> context
            is android.content.ContextWrapper -> {
                var ctx = context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is android.app.Activity) return ctx
                    ctx = ctx.baseContext
                }
                null
            }
            else -> null
        }
        if (direct != null) return direct
        val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
        return app?.getCurrentActivity()
    }

    companion object {
        /** Hard timeout for the host-key prompt dialogs — see kdoc on
         *  showBlockingHostDialog. */
        private const val DIALOG_TIMEOUT_SECONDS: Long = 30

        /** Port assumed when a host string carries no valid port component. */
        private const val DEFAULT_SSH_PORT: Int = 22

        // Algorithm names accepted from a host key blob's leading name field.
        // Anything outside this set is reported as "unknown" rather than
        // echoed back, so a server cannot inject an arbitrary string into the
        // stored key row or the fingerprint dialog.
        private val KNOWN_KEY_TYPES = setOf(
            "ssh-rsa",
            "rsa-sha2-256",
            "rsa-sha2-512",
            "ssh-ed25519",
            "ssh-ed448",
            "ecdsa-sha2-nistp256",
            "ecdsa-sha2-nistp384",
            "ecdsa-sha2-nistp521",
            "ssh-dss"
        )

        // Dedicated single-thread dispatcher for all host-key DB access.
        // JSch invokes check()/add()/remove() synchronously on its handshake
        // thread, so each Room query must block that thread via runBlocking.
        // Routing those through the shared Dispatchers.IO pool means a bulk
        // reconnect (many handshakes at once) competes with every other app IO
        // task for the same ~64 threads: a handshake thread blocked in
        // runBlocking can end up waiting on a dispatch that cannot get a free
        // IO slot. One app-lifetime thread decouples host-key checks from that
        // pool entirely. The queries are tiny, so serializing them here is
        // cheap, and the calls are sequential (never nested) so a single
        // thread cannot self-deadlock. The executor lives for the process
        // lifetime by design; it is a single daemon thread, so it is never
        // shut down.
        private val hostKeyDbDispatcher =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "tabssh-hostkey-db").apply { isDaemon = true }
            }.asCoroutineDispatcher()

        // App-wide known-hosts cache (audit O5). Populated from Room's
        // getAllHostKeys() Flow, so every DB write — accept, remove, sync
        // apply, clear-all — refreshes it automatically via invalidation.
        // check() reads this map on its ACCEPTED fast path instead of
        // blocking the handshake thread; all other outcomes go through the
        // authoritative transactional DAO verify. The database is an
        // app-level singleton, so a single shared cache is correct for
        // every HostKeyVerifier instance.
        private val cacheScope = CoroutineScope(SupervisorJob() + hostKeyDbDispatcher)

        @Volatile
        private var hostKeyCache: Map<String, HostKeyEntry>? = null

        private val cacheStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun ensureCacheStarted(dao: io.github.tabssh.storage.database.dao.HostKeyDao) {
            if (cacheStarted.compareAndSet(false, true)) {
                cacheScope.launch {
                    dao.getAllHostKeys().collect { entries ->
                        hostKeyCache = entries.associateBy { "${it.hostname}:${it.port}" }
                    }
                }
            }
        }
    }

}

/**
 * Information about a changed host key
 */
data class HostKeyChangedInfo(
    val hostname: String,
    val port: Int,
    val oldKeyType: String,
    val newKeyType: String,
    val oldFingerprint: String,
    val newFingerprint: String,
    val oldPublicKey: String,
    val newPublicKey: String,
    val firstSeen: Long,
    val lastVerified: Long
) {
    fun getDisplayMessage(): String = buildString {
        appendLine("SERVER KEY CHANGED")
        appendLine()
        appendLine("🌐 Server: $hostname:$port")
        appendLine()
        appendLine("The SSH server presented a different key than before.")
        appendLine()
        appendLine("💭 This could mean:")
        appendLine("  ✓ Server was reinstalled or upgraded")
        appendLine("  ✓ SSH configuration was changed")
        appendLine("  Man-in-the-middle attack (rare but serious)")
        appendLine()
        appendLine("📜 Previous Key ($oldKeyType):")
        appendLine("   $oldFingerprint")
        appendLine()
        appendLine("✨ New Key ($newKeyType):")
        appendLine("   $newFingerprint")
        appendLine()
        appendLine("📅 Timeline:")
        appendLine("   First seen: ${formatTimestamp(firstSeen)}")
        appendLine("   Last verified: ${formatTimestamp(lastVerified)}")
    }

    private fun formatTimestamp(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        return format.format(date)
    }
}

/**
 * Actions user can take when host key changes
 */
enum class HostKeyAction {
    // Replace old key with new key and continue
    ACCEPT_NEW_KEY,
    // Abort connection for security
    REJECT_CONNECTION,
    // Accept for this connection only, don't store
    ACCEPT_ONCE
}

/**
 * Information about a new (unknown) host key
 */
data class NewHostKeyInfo(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val publicKey: String
) {
    fun getDisplayMessage(): String = buildString {
        appendLine("NEW HOST KEY")
        appendLine()
        appendLine("🌐 Server: $hostname:$port")
        appendLine()
        appendLine("The authenticity of this host can't be established.")
        appendLine("This is the first time you're connecting to this server.")
        appendLine()
        appendLine("🔑 Key Type: $keyType")
        appendLine()
        appendLine("📜 Fingerprint:")
        appendLine("   $fingerprint")
        appendLine()
        appendLine("Please verify this fingerprint matches the server.")
        appendLine("If you're unsure, contact your server administrator.")
    }
}

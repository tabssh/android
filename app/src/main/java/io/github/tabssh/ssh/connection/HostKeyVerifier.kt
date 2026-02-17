package io.github.tabssh.ssh.connection

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.dao.HostKeyVerificationResult
import io.github.tabssh.storage.database.entities.HostKeyEntry
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.Base64

/**
 * Custom HostKeyRepository that integrates with TabSSH's database
 * Implements JSch's HostKeyRepository interface for host key verification
 */
class HostKeyVerifier(private val context: Context) : HostKeyRepository {

    private val database = TabSSHDatabase.getDatabase(context)
    private val hostKeyDao = database.hostKeyDao()

    private var hostKeyChangedCallback: ((HostKeyChangedInfo) -> HostKeyAction)? = null
    private var newHostKeyCallback: ((NewHostKeyInfo) -> HostKeyAction)? = null

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
            Logger.i("HostKeyVerifier", "🔐 HOST KEY CHECK CALLED for: $host")
            
            val (hostname, port) = parseHostPort(host)
            Logger.d("HostKeyVerifier", "Parsed: hostname=$hostname, port=$port")

            // Generate fingerprint
            val fingerprint = generateFingerprint(key)
            val keyType = detectKeyType(key)
            val publicKeyBase64 = Base64.getEncoder().encodeToString(key)

            Logger.i("HostKeyVerifier", "🔐 Checking host key for $hostname:$port ($keyType)")
            Logger.i("HostKeyVerifier", "🔐 Fingerprint: $fingerprint")

            // Verify against database
            Logger.d("HostKeyVerifier", "Querying database for existing host key...")
            val result = runBlocking {
                hostKeyDao.verifyHostKey(hostname, port, publicKeyBase64, fingerprint)
            }
            Logger.i("HostKeyVerifier", "🔐 Database verification result: $result")

            when (result) {
                HostKeyVerificationResult.ACCEPTED -> {
                    Logger.i("HostKeyVerifier", "✅ Host key verified: $hostname:$port")
                    HostKeyRepository.OK
                }

                HostKeyVerificationResult.NEW_HOST -> {
                    Logger.i("HostKeyVerifier", "🆕 NEW HOST: $hostname:$port - Asking user for confirmation")

                    val info = NewHostKeyInfo(
                        hostname = hostname,
                        port = port,
                        keyType = keyType,
                        fingerprint = fingerprint,
                        publicKey = publicKeyBase64
                    )

                    // Ask user what to do with this new host
                    Logger.i("HostKeyVerifier", "Invoking new host key callback... (callback is ${if (newHostKeyCallback != null) "SET" else "NULL"})")

                    val action = if (newHostKeyCallback != null) {
                        newHostKeyCallback!!.invoke(info)
                    } else {
                        // No callback available - show blocking dialog directly
                        Logger.w("HostKeyVerifier", "⚠️ No callback available - showing blocking dialog")
                        showBlockingNewHostDialog(info)
                    }

                    Logger.i("HostKeyVerifier", "User action for new host: $action")

                    return when (action) {
                        HostKeyAction.ACCEPT_NEW_KEY -> {
                            runBlocking {
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
                                Logger.i("HostKeyVerifier", "✅ User accepted and stored new host key for $hostname:$port")
                            }
                            HostKeyRepository.OK
                        }

                        HostKeyAction.REJECT_CONNECTION -> {
                            Logger.w("HostKeyVerifier", "❌ User rejected new host key for $hostname:$port")
                            HostKeyRepository.NOT_INCLUDED
                        }

                        HostKeyAction.ACCEPT_ONCE -> {
                            Logger.i("HostKeyVerifier", "✅ User accepted host key ONCE for $hostname:$port (not stored)")
                            HostKeyRepository.OK
                        }
                    }
                }

                HostKeyVerificationResult.CHANGED_KEY -> {
                    Logger.w("HostKeyVerifier", "⚠️⚠️⚠️ HOST KEY HAS CHANGED for $hostname:$port ⚠️⚠️⚠️")

                    // Get existing key for comparison
                    val existingKey = runBlocking {
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

                        val action = if (hostKeyChangedCallback != null) {
                            hostKeyChangedCallback!!.invoke(info)
                        } else {
                            // No callback available - show blocking dialog directly
                            Logger.w("HostKeyVerifier", "⚠️ No callback available - showing blocking dialog for changed key")
                            showBlockingChangedHostDialog(info)
                        }

                        Logger.i("HostKeyVerifier", "User action: $action")

                        return when (action) {
                            HostKeyAction.ACCEPT_NEW_KEY -> {
                                runBlocking {
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
                                    Logger.i("HostKeyVerifier", "✅ User accepted new host key for $hostname:$port")
                                }
                                HostKeyRepository.OK
                            }

                            HostKeyAction.REJECT_CONNECTION -> {
                                Logger.w("HostKeyVerifier", "❌ User rejected changed host key for $hostname:$port")
                                HostKeyRepository.NOT_INCLUDED
                            }

                            HostKeyAction.ACCEPT_ONCE -> {
                                Logger.i("HostKeyVerifier", "✅ User accepted host key ONCE for $hostname:$port")
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
                    Logger.e("HostKeyVerifier", "❌ Invalid host key for $hostname:$port")
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
            // Use our own fingerprint generator since JSch's method signature may vary
            val fingerprint = generateFingerprint(hostkey.key as ByteArray)

            runBlocking {
                val entry = HostKeyEntry(
                    id = HostKeyEntry.createId(hostname, port),
                    hostname = hostname,
                    port = port,
                    keyType = hostkey.type,
                    publicKey = Base64.getEncoder().encodeToString(hostkey.key as ByteArray),
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
            runBlocking {
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
            runBlocking {
                val entries = hostKeyDao.getAllHostKeys().first()
                entries.map { entry ->
                    val hostPort = "${entry.hostname}:${entry.port}"
                    val keyBytes = Base64.getDecoder().decode(entry.publicKey)
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
            runBlocking {
                val entry = hostKeyDao.getHostKey(hostname, port)
                if (entry != null && (type == null || entry.keyType == type)) {
                    val keyBytes = Base64.getDecoder().decode(entry.publicKey)
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
     * Clear all host keys from database
     */
    fun clearAllHostKeys() {
        runBlocking {
            hostKeyDao.deleteAllHostKeys()
            Logger.i("HostKeyVerifier", "Cleared all host keys")
        }
    }

    /**
     * Parse hostname and port from host string
     */
    private fun parseHostPort(host: String): Pair<String, Int> {
        val parts = host.split(":")
        return if (parts.size == 2) {
            Pair(parts[0], parts[1].toIntOrNull() ?: 22)
        } else {
            Pair(host, 22)
        }
    }

    /**
     * Generate SHA256 fingerprint from key bytes
     */
    private fun generateFingerprint(key: ByteArray): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(key)

            // Format as SHA256:hex format (like OpenSSH)
            "SHA256:" + hash.joinToString(":") { byte ->
                String.format("%02x", byte)
            }
        } catch (e: Exception) {
            Logger.e("HostKeyVerifier", "Error generating fingerprint", e)
            "UNKNOWN"
        }
    }

    /**
     * Detect key type from key bytes
     */
    private fun detectKeyType(key: ByteArray): String {
        return try {
            val keyStr = String(key)
            when {
                keyStr.contains("ssh-rsa") -> "ssh-rsa"
                keyStr.contains("ssh-ed25519") -> "ssh-ed25519"
                keyStr.contains("ecdsa-sha2-nistp256") -> "ecdsa-sha2-nistp256"
                keyStr.contains("ecdsa-sha2-nistp384") -> "ecdsa-sha2-nistp384"
                keyStr.contains("ecdsa-sha2-nistp521") -> "ecdsa-sha2-nistp521"
                keyStr.contains("ssh-dss") -> "ssh-dss"
                else -> "unknown"
            }
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
            "ssh-rsa" -> 0  // HostKey.SSHRSA
            "ssh-dss" -> 1  // HostKey.SSHDSS
            "ecdsa-sha2-nistp256" -> 2
            "ecdsa-sha2-nistp384" -> 3
            "ecdsa-sha2-nistp521" -> 4
            "ssh-ed25519" -> 5
            else -> 0 // Default to RSA
        }
    }

    /**
     * Show a blocking dialog for new host key verification
     * Used when no callback is available (fallback mechanism)
     */
    private fun showBlockingNewHostDialog(info: NewHostKeyInfo): HostKeyAction {
        Logger.i("HostKeyVerifier", "Showing blocking dialog for new host: ${info.hostname}:${info.port}")

        var userAction: HostKeyAction = HostKeyAction.REJECT_CONNECTION
        val latch = java.util.concurrent.CountDownLatch(1)

        // Get the main activity from the context
        val activity = when (context) {
            is android.app.Activity -> context as android.app.Activity
            is android.content.ContextWrapper -> {
                var ctx = context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is android.app.Activity) break
                    ctx = ctx.baseContext
                }
                ctx as? android.app.Activity
            }
            else -> null
        }

        if (activity == null) {
            Logger.e("HostKeyVerifier", "Cannot show dialog - no activity context available")
            // Try to get activity from the application's current activity
            val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
            // Fallback: return REJECT for safety
            return HostKeyAction.REJECT_CONNECTION
        }

        activity.runOnUiThread {
            try {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("New Host Key")
                    .setMessage(info.getDisplayMessage())
                    .setPositiveButton("Accept & Save") { _, _ ->
                        userAction = HostKeyAction.ACCEPT_NEW_KEY
                        latch.countDown()
                    }
                    .setNeutralButton("Accept Once") { _, _ ->
                        userAction = HostKeyAction.ACCEPT_ONCE
                        latch.countDown()
                    }
                    .setNegativeButton("Reject") { _, _ ->
                        userAction = HostKeyAction.REJECT_CONNECTION
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setOnDismissListener {
                        // Ensure latch is counted down even if dialog is dismissed
                        latch.countDown()
                    }
                    .show()
            } catch (e: Exception) {
                Logger.e("HostKeyVerifier", "Error showing new host dialog", e)
                latch.countDown()
            }
        }

        try {
            // Wait for user response with timeout
            val responded = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!responded) {
                Logger.w("HostKeyVerifier", "Dialog timeout - rejecting connection")
                userAction = HostKeyAction.REJECT_CONNECTION
            }
        } catch (e: InterruptedException) {
            Logger.e("HostKeyVerifier", "Interrupted waiting for dialog response", e)
            userAction = HostKeyAction.REJECT_CONNECTION
        }

        Logger.i("HostKeyVerifier", "Blocking dialog result: $userAction")
        return userAction
    }

    /**
     * Show a blocking dialog for changed host key verification
     * Used when no callback is available (fallback mechanism)
     */
    private fun showBlockingChangedHostDialog(info: HostKeyChangedInfo): HostKeyAction {
        Logger.w("HostKeyVerifier", "Showing blocking dialog for CHANGED host key: ${info.hostname}:${info.port}")

        var userAction: HostKeyAction = HostKeyAction.REJECT_CONNECTION
        val latch = java.util.concurrent.CountDownLatch(1)

        // Get the main activity from the context
        val activity = when (context) {
            is android.app.Activity -> context as android.app.Activity
            is android.content.ContextWrapper -> {
                var ctx = context
                while (ctx is android.content.ContextWrapper) {
                    if (ctx is android.app.Activity) break
                    ctx = ctx.baseContext
                }
                ctx as? android.app.Activity
            }
            else -> null
        }

        if (activity == null) {
            Logger.e("HostKeyVerifier", "Cannot show dialog - no activity context available")
            return HostKeyAction.REJECT_CONNECTION
        }

        activity.runOnUiThread {
            try {
                androidx.appcompat.app.AlertDialog.Builder(activity)
                    .setTitle("WARNING: Host Key Changed!")
                    .setMessage(info.getDisplayMessage())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("Accept New Key") { _, _ ->
                        userAction = HostKeyAction.ACCEPT_NEW_KEY
                        latch.countDown()
                    }
                    .setNeutralButton("Accept Once") { _, _ ->
                        userAction = HostKeyAction.ACCEPT_ONCE
                        latch.countDown()
                    }
                    .setNegativeButton("Reject (Recommended)") { _, _ ->
                        userAction = HostKeyAction.REJECT_CONNECTION
                        latch.countDown()
                    }
                    .setCancelable(false)
                    .setOnDismissListener {
                        latch.countDown()
                    }
                    .show()
            } catch (e: Exception) {
                Logger.e("HostKeyVerifier", "Error showing changed host dialog", e)
                latch.countDown()
            }
        }

        try {
            val responded = latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!responded) {
                Logger.w("HostKeyVerifier", "Dialog timeout - rejecting connection")
                userAction = HostKeyAction.REJECT_CONNECTION
            }
        } catch (e: InterruptedException) {
            Logger.e("HostKeyVerifier", "Interrupted waiting for dialog response", e)
            userAction = HostKeyAction.REJECT_CONNECTION
        }

        Logger.w("HostKeyVerifier", "Blocking dialog result for changed key: $userAction")
        return userAction
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
        appendLine("🔐 SERVER KEY CHANGED")
        appendLine()
        appendLine("🌐 Server: $hostname:$port")
        appendLine()
        appendLine("The SSH server presented a different key than before.")
        appendLine()
        appendLine("💭 This could mean:")
        appendLine("  ✓ Server was reinstalled or upgraded")
        appendLine("  ✓ SSH configuration was changed")
        appendLine("  ⚠️ Man-in-the-middle attack (rare but serious)")
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
    ACCEPT_NEW_KEY,      // Replace old key with new key and continue
    REJECT_CONNECTION,   // Abort connection for security
    ACCEPT_ONCE          // Accept for this connection only, don't store
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
        appendLine("🔐 NEW HOST KEY")
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
        appendLine("⚠️ Please verify this fingerprint matches the server.")
        appendLine("If you're unsure, contact your server administrator.")
    }
}

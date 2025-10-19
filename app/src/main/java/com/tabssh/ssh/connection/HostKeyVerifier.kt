package com.tabssh.ssh.connection

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.UserInfo
import com.tabssh.storage.database.TabSSHDatabase
import com.tabssh.storage.database.dao.HostKeyVerificationResult
import com.tabssh.storage.database.entities.HostKeyEntry
import com.tabssh.utils.logging.Logger
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

    /**
     * Set callback for when host key changes are detected
     */
    fun setHostKeyChangedCallback(callback: (HostKeyChangedInfo) -> HostKeyAction) {
        hostKeyChangedCallback = callback
    }

    /**
     * Check if host key is acceptable
     * Called by JSch during connection
     */
    override fun check(host: String, key: ByteArray): Int {
        return try {
            val (hostname, port) = parseHostPort(host)

            // Generate fingerprint
            val fingerprint = generateFingerprint(key)
            val keyType = detectKeyType(key)
            val publicKeyBase64 = Base64.getEncoder().encodeToString(key)

            Logger.d("HostKeyVerifier", "Checking host key for $hostname:$port ($keyType)")
            Logger.d("HostKeyVerifier", "Fingerprint: $fingerprint")

            // Verify against database
            val result = runBlocking {
                hostKeyDao.verifyHostKey(hostname, port, publicKeyBase64, fingerprint)
            }

            when (result) {
                HostKeyVerificationResult.ACCEPTED -> {
                    Logger.i("HostKeyVerifier", "Host key verified: $hostname:$port")
                    HostKeyRepository.OK
                }

                HostKeyVerificationResult.NEW_HOST -> {
                    Logger.i("HostKeyVerifier", "New host: $hostname:$port")
                    // Automatically accept new hosts and store them
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
                        Logger.i("HostKeyVerifier", "Stored new host key for $hostname:$port")
                    }
                    HostKeyRepository.OK
                }

                HostKeyVerificationResult.CHANGED_KEY -> {
                    Logger.w("HostKeyVerifier", "⚠️ HOST KEY HAS CHANGED for $hostname:$port")

                    // Get existing key for comparison
                    val existingKey = runBlocking {
                        hostKeyDao.getHostKey(hostname, port)
                    }

                    if (existingKey != null) {
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
                        if (hostKeyChangedCallback != null) {
                            val action = hostKeyChangedCallback!!.invoke(info)

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
                                        Logger.i("HostKeyVerifier", "User accepted new host key for $hostname:$port")
                                    }
                                    HostKeyRepository.OK
                                }

                                HostKeyAction.REJECT_CONNECTION -> {
                                    Logger.w("HostKeyVerifier", "User rejected changed host key for $hostname:$port")
                                    HostKeyRepository.NOT_INCLUDED
                                }

                                HostKeyAction.ACCEPT_ONCE -> {
                                    Logger.i("HostKeyVerifier", "User accepted host key once for $hostname:$port")
                                    // Don't store, just allow this connection
                                    HostKeyRepository.OK
                                }
                            }
                        } else {
                            // No callback available - we must still ask via blocking mechanism
                            // Log critical warning but allow JSch to handle via UserInfo prompt
                            Logger.e("HostKeyVerifier", "⚠️ CRITICAL: Host key changed but no UI callback available")
                            Logger.e("HostKeyVerifier", "Old fingerprint: ${existingKey.fingerprint}")
                            Logger.e("HostKeyVerifier", "New fingerprint: $fingerprint")
                            Logger.w("HostKeyVerifier", "Allowing JSch default prompt - user MUST manually verify!")

                            // Return CHANGED status to trigger JSch's built-in prompt
                            // This ensures user is ALWAYS asked, even if our UI isn't available
                            HostKeyRepository.CHANGED
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

package io.github.tabssh.crypto.keys

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tabssh.storage.database.entities.StoredKey
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.*
import java.security.*
import java.security.interfaces.*
import java.security.spec.*
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// BouncyCastle imports for comprehensive PEM parsing
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder
import io.github.tabssh.crypto.SSHKeyParser

/**
 * Manages SSH private key storage with hardware-backed encryption
 * Supports RSA, DSA, ECDSA, and Ed25519 keys with import/generation
 */
class KeyStorage(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ENCRYPTION_ALIAS_PREFIX = "tabssh_key_encryption_"
        private const val SHARED_PREFS_NAME = "tabssh_key_storage"
        private const val PREF_ENCRYPTED_KEY_PREFIX = "encrypted_key_"
        private const val PREF_KEY_IV_PREFIX = "key_iv_"
        // JSch-native representation stored alongside PKCS#8 DER
        private const val PREF_JSCH_BYTES_PREFIX = "jsch_bytes_"
        private const val PREF_JSCH_IV_PREFIX = "jsch_iv_"
        
        // Supported key formats for import
        private val OPENSSH_PRIVATE_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
        private val RSA_PRIVATE_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
        private val DSA_PRIVATE_HEADER = "-----BEGIN DSA PRIVATE KEY-----"
        private val EC_PRIVATE_HEADER = "-----BEGIN EC PRIVATE KEY-----"
        private val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"
        private val PKCS8_ENCRYPTED_HEADER = "-----BEGIN ENCRYPTED PRIVATE KEY-----"
        private val PUTTY_HEADER = "PuTTY-User-Key-File-"
    }
    
    /**
     * Key import formats
     */
    enum class KeyFormat(val header: String, val description: String) {
        OPENSSH_PRIVATE(OPENSSH_PRIVATE_HEADER, "OpenSSH private key format"),
        RSA_PRIVATE(RSA_PRIVATE_HEADER, "RSA private key format"),
        DSA_PRIVATE(DSA_PRIVATE_HEADER, "DSA private key format"),
        EC_PRIVATE(EC_PRIVATE_HEADER, "EC private key format"),
        PKCS8(PKCS8_HEADER, "PKCS#8 private key format"),
        PKCS8_ENCRYPTED(PKCS8_ENCRYPTED_HEADER, "Encrypted PKCS#8 private key format"),
        PUTTY_PRIVATE(PUTTY_HEADER, "PuTTY private key format");
        
        companion object {
            fun detectFormat(keyContent: String): KeyFormat? {
                return values().find { format ->
                    keyContent.trimStart().startsWith(format.header)
                }
            }
        }
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
    private val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    private val database = TabSSHDatabase.getDatabase(context)
    
    private var isInitialized = false
    
    // BC provider is installed by TabSSHApplication.onCreate(); no-op here.
    
    fun initialize() {
        if (isInitialized) return

        try {
            keyStore.load(null)
            Logger.d("KeyStorage", "Initialized with Android Keystore")
        } catch (e: Exception) {
            // Keystore unavailable — SSH key encryption will be degraded.
            // Key import/generation will fail gracefully; existing app features remain usable.
            Logger.e("KeyStorage", "Keystore unavailable, SSH key encryption disabled", e)
        }
        isInitialized = true
    }
    
    /**
     * Generate a new SSH key pair
     */
    suspend fun generateKeyPair(
        keyType: KeyType,
        keySize: Int = keyType.defaultKeySize,
        name: String = "Generated ${keyType.name} Key",
        comment: String = "",
        passphrase: String? = null
    ): GenerateResult = withContext(Dispatchers.IO) {
        
        if (!KeyType.isValidKeySize(keyType, keySize)) {
            return@withContext GenerateResult.Error("Invalid key size $keySize for $keyType")
        }
        
        Logger.d("KeyStorage", "Generating $keyType key with size $keySize")
        
        try {
            val keyPair = when (keyType) {
                KeyType.RSA -> generateRSAKeyPair(keySize)
                KeyType.DSA -> generateDSAKeyPair(keySize)
                KeyType.ECDSA -> generateECDSAKeyPair(keySize)
                KeyType.ED25519 -> generateEd25519KeyPair()
            }
            
            // Calculate fingerprint
            val fingerprint = calculateFingerprint(keyPair.public)
            
            // Use provided name
            val keyName = name
            
            // Store the private key securely; alias defaults to SSH convention.
            val alias = generateDefaultAlias(keyType)
            val keyId = storePrivateKey(
                privateKey = keyPair.private,
                publicKey = keyPair.public,
                keyType = keyType,
                name = keyName,
                comment = comment,
                alias = alias,
                fingerprint = fingerprint,
                passphrase = passphrase
            )
            
            if (keyId != null) {
                // Store JSch-native bytes so connect-time paths (LibvirtApiClient,
                // SSHConnection) have the right format without needing to reconstruct.
                var jschBytes: ByteArray? = null
                try {
                    jschBytes = toJSchKeyBytes(keyPair.private, keyPair.public, keyType)
                    storeJSchBytes(keyId, jschBytes)
                } catch (e: Exception) {
                    Logger.w("KeyStorage", "Could not store JSch bytes for generated key $keyId (non-fatal)", e)
                } finally {
                    // Zero plaintext SSH key material from heap — storeJSchBytes
                    // has already encrypted what it needs.
                    jschBytes?.fill(0)
                }
                Logger.i("KeyStorage", "Generated $keyType key: $keyName")
                GenerateResult.Success(keyId, keyPair, fingerprint)
            } else {
                GenerateResult.Error("Failed to store generated key")
            }
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to generate $keyType key", e)
            GenerateResult.Error("Key generation failed: ${e.message}")
        }
    }
    
    private fun generateRSAKeyPair(keySize: Int): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(keySize, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    private fun generateDSAKeyPair(keySize: Int): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("DSA")
        keyPairGenerator.initialize(keySize, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    private fun generateECDSAKeyPair(keySize: Int): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        val ecGenParameterSpec = when (keySize) {
            256 -> ECGenParameterSpec("secp256r1")
            384 -> ECGenParameterSpec("secp384r1")
            521 -> ECGenParameterSpec("secp521r1")
            else -> ECGenParameterSpec("secp256r1")
        }
        keyPairGenerator.initialize(ecGenParameterSpec, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }
    
    private fun generateEd25519KeyPair(): KeyPair {
        // Ed25519 support requires API 23+ or external library
        // For now, fallback to ECDSA P-256 if Ed25519 not available
        return try {
            val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519")
            keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            Logger.w("KeyStorage", "Ed25519 not supported, falling back to ECDSA P-256")
            generateECDSAKeyPair(256)
        }
    }
    
    /**
     * Import private key from various sources
     */
    /**
     * Quickly parse [keyContent] to extract the embedded comment and key type
     * without storing anything. Used to populate import dialog defaults.
     *
     * Encrypted keys return [KeyPreviewInfo.isEncrypted] = true and an empty
     * comment (comment is inside the encrypted section and requires the passphrase).
     */
    suspend fun previewKey(keyContent: String): KeyPreviewInfo = withContext(Dispatchers.IO) {
        if (keyContent.isBlank()) return@withContext KeyPreviewInfo("", null)
        try {
            val format = KeyFormat.detectFormat(keyContent)
                ?: return@withContext KeyPreviewInfo("", null)
            val parseResult = when (format) {
                KeyFormat.OPENSSH_PRIVATE -> parseOpenSSHKey(keyContent, null)
                KeyFormat.PUTTY_PRIVATE -> parsePuttyKey(keyContent, null)
                else -> parseTraditionalKey(keyContent, null, "RSA")
            }
            when (parseResult) {
                is ParseResult.Success -> {
                    val type = detectKeyType(parseResult.keyPair.private)
                    KeyPreviewInfo(parseResult.comment, type)
                }
                is ParseResult.Error -> {
                    val encrypted = parseResult.message.contains("passphrase", ignoreCase = true) ||
                        parseResult.message.contains("encrypted", ignoreCase = true)
                    KeyPreviewInfo("", null, isEncrypted = encrypted)
                }
            }
        } catch (e: Exception) {
            val encrypted = e.message?.contains("passphrase", ignoreCase = true) == true ||
                e.message?.contains("encrypted", ignoreCase = true) == true
            KeyPreviewInfo("", null, isEncrypted = encrypted)
        }
    }

    /**
     * Compute a unique SSH-convention alias for [keyType] that doesn't collide
     * with any alias already in the database.
     *
     * Returns `id_ed25519` if unused; `id_ed25519_001`, `id_ed25519_002`, … otherwise.
     */
    suspend fun generateDefaultAlias(keyType: KeyType): String = withContext(Dispatchers.IO) {
        val base = keyType.sshConventionAlias()
        val usedAliases = database.keyDao().getAllAliases().toSet()
        if (base !in usedAliases) return@withContext base
        var n = 1
        while (true) {
            val candidate = "${base}_%03d".format(n)
            if (candidate !in usedAliases) return@withContext candidate
            n++
        }
        @Suppress("UNREACHABLE_CODE")
        base
    }

    suspend fun importKeyFromFile(fileUri: Uri, passphrase: String? = null): ImportResult = withContext(Dispatchers.IO) {
        try {
            val keyContent = context.contentResolver.openInputStream(fileUri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return@withContext ImportResult.Error("Cannot open file")

            // Query the content resolver for the human-readable display name.
            // fileUri.lastPathSegment on a content:// URI returns an encoded
            // path component (often raw bytes), not the filename the user sees.
            val displayName = context.contentResolver
                .query(fileUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                ?.takeIf { it.isNotBlank() }
                ?: fileUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: "Imported Key"

            importKeyFromText(keyContent, passphrase, displayName)
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to import key from file", e)
            ImportResult.Error("Failed to read key file: ${e.message}")
        }
    }
    
    suspend fun importKeyFromText(
        keyContent: String,
        passphrase: String? = null,
        keyName: String = "Imported Key",
        keyAlias: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        
        if (keyContent.isBlank()) {
            return@withContext ImportResult.Error("Key content is empty")
        }
        
        try {
            val format = KeyFormat.detectFormat(keyContent)
                ?: return@withContext ImportResult.Error("Unrecognized key format")
            
            Logger.d("KeyStorage", "Detected key format: $format")
            
            val parseResult = when (format) {
                KeyFormat.OPENSSH_PRIVATE -> parseOpenSSHKey(keyContent, passphrase)
                KeyFormat.RSA_PRIVATE -> parseTraditionalKey(keyContent, passphrase, "RSA")
                KeyFormat.DSA_PRIVATE -> parseTraditionalKey(keyContent, passphrase, "DSA")
                KeyFormat.EC_PRIVATE -> parseTraditionalKey(keyContent, passphrase, "EC")
                KeyFormat.PKCS8 -> parsePKCS8Key(keyContent, passphrase)
                KeyFormat.PKCS8_ENCRYPTED -> parsePKCS8Key(keyContent, passphrase)
                KeyFormat.PUTTY_PRIVATE -> parsePuttyKey(keyContent, passphrase)
            }

            when (parseResult) {
                is ParseResult.Success -> {
                    val fingerprint = calculateFingerprint(parseResult.keyPair.public)
                    val keyType = detectKeyType(parseResult.keyPair.private)

                    // If caller didn't supply an alias, generate the SSH-convention
                    // default (id_ed25519, id_rsa_001, etc.) automatically.
                    val resolvedAlias = keyAlias ?: generateDefaultAlias(keyType)

                    val keyId = storePrivateKey(
                        privateKey = parseResult.keyPair.private,
                        publicKey = parseResult.keyPair.public,
                        keyType = keyType,
                        name = keyName,
                        comment = parseResult.comment,
                        alias = resolvedAlias,
                        fingerprint = fingerprint,
                        passphrase = passphrase
                    )

                    if (keyId != null) {
                        // Store JSch-native bytes so connect-time has the right format
                        var jschBytes: ByteArray? = null
                        try {
                            // For OpenSSH format, use original bytes instead of reconstructing
                            jschBytes = if (format == KeyFormat.OPENSSH_PRIVATE && passphrase == null) {
                                // Unencrypted OpenSSH - use original bytes directly
                                Logger.d("KeyStorage", "Using original OpenSSH key bytes for $keyId")
                                keyContent.toByteArray(Charsets.UTF_8)
                            } else {
                                // For other formats or encrypted keys, reconstruct
                                Logger.d("KeyStorage", "Reconstructing JSch bytes for $keyId (format=$format)")
                                toJSchKeyBytes(parseResult.keyPair.private, parseResult.keyPair.public, keyType)
                            }
                            storeJSchBytes(keyId, jschBytes)
                        } catch (e: Exception) {
                            Logger.w("KeyStorage", "Could not store JSch bytes for $keyId (non-fatal)", e)
                        } finally {
                            // Wipe the plaintext key material now that it is
                            // encrypted-and-stored.
                            jschBytes?.fill(0)
                        }
                        Logger.i("KeyStorage", "Imported $keyType key: $keyName")
                        ImportResult.Success(keyId, parseResult.keyPair, fingerprint)
                    } else {
                        ImportResult.Error("Failed to store imported key")
                    }
                }
                is ParseResult.Error -> ImportResult.Error(parseResult.message)
            }
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to import key", e)
            ImportResult.Error("Key import failed: ${e.message}")
        }
    }
    
    /**
     * Store a private key securely using Android Keystore
     */
    suspend fun storePrivateKey(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        keyType: KeyType,
        name: String,
        comment: String? = null,
        alias: String? = null,
        fingerprint: String,
        passphrase: String? = null
    ): String? = withContext(Dispatchers.IO) {
        
        val keyId = UUID.randomUUID().toString()
        
        try {
            // Create encryption key for this SSH key
            val encryptionKey = createKeyEncryptionKey(keyId)
            
            // Encrypt the private key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
            
            val privateKeyBytes = privateKey.encoded
            val iv = cipher.iv
            val encryptedKeyData = cipher.doFinal(privateKeyBytes)
            // Zero the plaintext PKCS#8 DER copy now that it has been
            // encrypted. JCE returns a fresh array from privateKey.encoded
            // so this does not mutate the live key object's internal state.
            privateKeyBytes.fill(0)
            
            // Store encrypted key data
            val editor = sharedPrefs.edit()
            editor.putString("$PREF_ENCRYPTED_KEY_PREFIX$keyId", 
                android.util.Base64.encodeToString(encryptedKeyData, android.util.Base64.NO_WRAP))
            editor.putString("$PREF_KEY_IV_PREFIX$keyId",
                android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            editor.apply()
            
            // Store key metadata in database
            val storedKey = StoredKey(
                keyId = keyId,
                name = name,
                keyType = keyType.name,
                comment = comment,
                alias = alias,
                fingerprint = fingerprint,
                requiresPassphrase = passphrase != null,
                keySize = getKeySize(privateKey)
            )
            
            database.keyDao().insertKey(storedKey)

            // Store passphrase securely if provided
            if (passphrase != null) {
                try {
                    val app = context.applicationContext as? io.github.tabssh.TabSSHApplication
                    app?.securePasswordManager?.storePassword("key_passphrase_$keyId", passphrase)
                    Logger.d("KeyStorage", "Stored passphrase for key $keyId")
                } catch (e: Exception) {
                    Logger.e("KeyStorage", "Failed to store passphrase (non-fatal)", e)
                }
            }

            Logger.i("KeyStorage", "Stored SSH key: $name ($keyType)")
            keyId
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to store private key", e)
            
            // Cleanup on failure
            try {
                sharedPrefs.edit().apply {
                    remove("$PREF_ENCRYPTED_KEY_PREFIX$keyId")
                    remove("$PREF_KEY_IV_PREFIX$keyId")
                    apply()
                }
                keyStore.deleteEntry("$KEY_ENCRYPTION_ALIAS_PREFIX$keyId")
            } catch (cleanupError: Exception) {
                Logger.e("KeyStorage", "Error during cleanup", cleanupError)
            }
            
            null
        }
    }
    
    /**
     * Retrieve a stored private key
     */
    suspend fun retrievePrivateKey(keyId: String): PrivateKey? = withContext(Dispatchers.IO) {
        try {
            // Get encrypted key data
            val encryptedKeyData = sharedPrefs.getString("$PREF_ENCRYPTED_KEY_PREFIX$keyId", null)
                ?: return@withContext null
            val ivData = sharedPrefs.getString("$PREF_KEY_IV_PREFIX$keyId", null)
                ?: return@withContext null
            
            val encryptedBytes = android.util.Base64.decode(encryptedKeyData, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivData, android.util.Base64.NO_WRAP)
            
            // Get decryption key
            val encryptionKey = keyStore.getKey("$KEY_ENCRYPTION_ALIAS_PREFIX$keyId", null) as? SecretKey
                ?: return@withContext null
            
            // Decrypt private key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, gcmSpec)
            
            val decryptedKeyBytes = cipher.doFinal(encryptedBytes)

            // Get key metadata to determine algorithm
            val storedKey = database.keyDao().getKeyById(keyId)
                ?: run {
                    decryptedKeyBytes.fill(0)
                    return@withContext null
                }

            val keyType = KeyType.valueOf(storedKey.keyType)

            // Reconstruct private key
            val privateKey = reconstructPrivateKey(decryptedKeyBytes, keyType)
            // KeyFactory has copied the DER into the PrivateKey object —
            // wipe our plaintext buffer so the SSH private key isn't
            // sitting in heap until the next GC sweep.
            decryptedKeyBytes.fill(0)
            
            // Update last used timestamp
            database.keyDao().updateLastUsed(keyId)
            
            Logger.d("KeyStorage", "Retrieved private key: ${storedKey.name}")
            privateKey
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to retrieve private key $keyId", e)
            null
        }
    }
    
    /**
     * List all stored keys
     */
    suspend fun listStoredKeys(): List<StoredKey> = withContext(Dispatchers.IO) {
        return@withContext database.keyDao().getAllKeys().first()
    }

    // -------------------------------------------------------------------------
    // JSch-native byte storage – format that JSch can ALWAYS parse directly
    // -------------------------------------------------------------------------

    /**
     * Convert a parsed key pair to bytes in the format JSch accepts for every key type:
     *   Ed25519  → OpenSSH binary format  (-----BEGIN OPENSSH PRIVATE KEY-----)
     *   RSA      → PKCS#1 PEM             (-----BEGIN RSA PRIVATE KEY-----)
     *   ECDSA    → SEC1/EC PEM            (-----BEGIN EC PRIVATE KEY-----)
     *   DSA      → Traditional DSA PEM    (-----BEGIN DSA PRIVATE KEY-----)
     *
     * JSch cannot parse PKCS#8 PEM for Ed25519 (its KeyPair.load() requires
     * OpenSSH format for that algorithm).  For the others, JcaPEMWriter writes
     * the canonical traditional PEM that JSch has always supported.
     */
    fun toJSchKeyBytes(privateKey: PrivateKey, publicKey: PublicKey, keyType: KeyType): ByteArray {
        return when (keyType) {
            KeyType.ED25519 -> {
                val pkInfo = PrivateKeyInfo.getInstance(privateKey.encoded)
                val seed   = (pkInfo.parsePrivateKey() as ASN1OctetString).octets // 32 bytes
                val spki   = SubjectPublicKeyInfo.getInstance(publicKey.encoded)
                val pubPt  = spki.publicKeyData.bytes // 32 bytes
                buildOpenSSHEd25519(seed, pubPt)
            }
            else -> {
                // JcaPEMWriter with BouncyCastle writes type-specific PEM:
                // RSA  → -----BEGIN RSA PRIVATE KEY-----
                // EC   → -----BEGIN EC PRIVATE KEY-----
                // DSA  → -----BEGIN DSA PRIVATE KEY-----
                val sw = StringWriter()
                JcaPEMWriter(sw).use { it.writeObject(privateKey) }
                sw.toString().toByteArray(Charsets.US_ASCII)
            }
        }
    }

    /**
     * Build an unencrypted OpenSSH v1 private key file for an Ed25519 key.
     * seed   = 32-byte private seed
     * pubPt  = 32-byte public key point
     */
    private fun buildOpenSSHEd25519(seed: ByteArray, pubPt: ByteArray): ByteArray {
        require(seed.size == 32)  { "Ed25519 seed must be 32 bytes, got ${seed.size}" }
        require(pubPt.size == 32) { "Ed25519 pub must be 32 bytes, got ${pubPt.size}" }

        fun u32(v: Int) = byteArrayOf(
            (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
        )
        fun sshStr(s: String): ByteArray { val b = s.toByteArray(Charsets.UTF_8); return u32(b.size) + b }
        fun sshBytes(b: ByteArray): ByteArray = u32(b.size) + b

        // Public-key blob: ssh-ed25519 || pubPt
        val pubBlob = sshStr("ssh-ed25519") + sshBytes(pubPt)

        // Private section (no encryption → cipher=none, block_size=8)
        val checkInt = (System.nanoTime() and 0xFFFFFFFFL).toInt()
        var priv = u32(checkInt) + u32(checkInt)     // check1 + check2
        priv += sshStr("ssh-ed25519") + sshBytes(pubPt) + sshBytes(seed + pubPt) + sshStr("")
        // Padding: 0x01 0x02 … until length % 8 == 0
        var pad = 1; while (priv.size % 8 != 0) { priv += byteArrayOf(pad++.toByte()) }

        // Assemble the file
        var out = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        out += sshStr("none") + sshStr("none") + u32(0) // cipher / kdf / kdf_options
        out += u32(1)                                    // number of keys
        out += sshBytes(pubBlob)
        out += sshBytes(priv)

        val b64 = android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
        val wrapped = b64.chunked(70).joinToString("\n")
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n$wrapped\n-----END OPENSSH PRIVATE KEY-----"
            .toByteArray(Charsets.US_ASCII)
    }

    /**
     * Restore SSH key JSch bytes from an encrypted backup.
     *
     * Creates (or reuses) the Keystore AES-GCM wrapping key for [keyId],
     * then encrypts and persists [jschBytes] via [storeJSchBytes].  The
     * companion [StoredKey] metadata row is restored separately by
     * [BackupImporter.restoreKeys]; this method only handles the key
     * material so subsequent [retrieveJSchBytes] / [getJSchBytesWithFallback]
     * calls succeed without user re-import.
     *
     * Called by [io.github.tabssh.backup.import.BackupImporter].
     */
    fun importKeyFromBackup(keyId: String, jschBytes: ByteArray): Boolean {
        return try {
            createKeyEncryptionKey(keyId)   // idempotent — returns existing key if present
            storeJSchBytes(keyId, jschBytes)
            Logger.i("KeyStorage", "Restored key material from backup: $keyId")
            true
        } catch (e: Exception) {
            Logger.e("KeyStorage", "importKeyFromBackup($keyId) failed", e)
            false
        }
    }

    /**
     * Encrypt and store JSch-native bytes.  Reuses the same AES-GCM keystore key
     * already created by storePrivateKey() for this keyId.
     */
    internal fun storeJSchBytes(keyId: String, jschBytes: ByteArray) {
        try {
            val encKey = keyStore.getKey("$KEY_ENCRYPTION_ALIAS_PREFIX$keyId", null) as? SecretKey
                ?: run { Logger.w("KeyStorage", "No keystore entry for $keyId – skipping JSch bytes"); return }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encKey)
            val encrypted = cipher.doFinal(jschBytes)
            sharedPrefs.edit()
                .putString("$PREF_JSCH_BYTES_PREFIX$keyId",
                    android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                .putString("$PREF_JSCH_IV_PREFIX$keyId",
                    android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
                .apply()
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to store JSch bytes for $keyId", e)
        }
    }

    /**
     * Retrieve JSch-native bytes for use with JSch.addIdentity().
     * Returns null if not yet stored (keys imported before this version).
     */
    fun retrieveJSchBytes(keyId: String): ByteArray? {
        return try {
            val encData = sharedPrefs.getString("$PREF_JSCH_BYTES_PREFIX$keyId", null) ?: return null
            val ivData  = sharedPrefs.getString("$PREF_JSCH_IV_PREFIX$keyId",    null) ?: return null
            val encKey  = keyStore.getKey("$KEY_ENCRYPTION_ALIAS_PREFIX$keyId", null) as? SecretKey ?: return null
            val cipher  = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encKey, GCMParameterSpec(128, android.util.Base64.decode(ivData, android.util.Base64.NO_WRAP)))
            cipher.doFinal(android.util.Base64.decode(encData, android.util.Base64.NO_WRAP))
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to retrieve JSch bytes for $keyId", e)
            null
        }
    }

    /**
     * Get JSch-native bytes for [keyId], with a fallback for keys that were
     * generated or imported before the jsch_bytes store was introduced.
     *
     * Order:
     *  1. Return cached JSch bytes from [retrieveJSchBytes] (fast path).
     *  2. Reconstruct from stored PKCS#8 DER via [toJSchKeyBytes] and cache
     *     the result so future calls hit the fast path.
     *
     * Returns null if the key does not exist or cannot be reconstructed.
     */
    suspend fun getJSchBytesWithFallback(keyId: String): ByteArray? = withContext(Dispatchers.IO) {
        retrieveJSchBytes(keyId)?.let { return@withContext it }

        // Fallback: reconstruct from stored PKCS#8 DER
        return@withContext try {
            val privateKey = retrievePrivateKey(keyId) ?: run {
                Logger.e("KeyStorage", "getJSchBytesWithFallback: no private key for $keyId")
                return@withContext null
            }
            val storedKey = database.keyDao().getKeyById(keyId) ?: run {
                Logger.e("KeyStorage", "getJSchBytesWithFallback: no metadata for $keyId")
                return@withContext null
            }
            val keyType = KeyType.valueOf(storedKey.keyType)
            val publicKey = getPublicKeyFromPrivate(privateKey)
            val jschBytes = toJSchKeyBytes(privateKey, publicKey, keyType)
            // Cache so next call is fast
            storeJSchBytes(keyId, jschBytes)
            Logger.i("KeyStorage", "getJSchBytesWithFallback: rebuilt and cached JSch bytes for $keyId")
            jschBytes
        } catch (e: Exception) {
            Logger.e("KeyStorage", "getJSchBytesWithFallback: failed for $keyId", e)
            null
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Delete a stored key
     */
    suspend fun deleteKey(keyId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Remove from database
            database.keyDao().deleteKeyById(keyId)
            
            // Remove encrypted data
            val editor = sharedPrefs.edit()
            editor.remove("$PREF_ENCRYPTED_KEY_PREFIX$keyId")
            editor.remove("$PREF_KEY_IV_PREFIX$keyId")
            editor.remove("$PREF_JSCH_BYTES_PREFIX$keyId")
            editor.remove("$PREF_JSCH_IV_PREFIX$keyId")
            editor.apply()
            
            // Remove encryption key from keystore
            keyStore.deleteEntry("$KEY_ENCRYPTION_ALIAS_PREFIX$keyId")
            
            Logger.i("KeyStorage", "Deleted SSH key: $keyId")
            true
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to delete key $keyId", e)
            false
        }
    }
    
    /**
     * Export a key to file (public key only for security)
     */
    suspend fun exportPublicKey(keyId: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val storedKey = database.keyDao().getKeyById(keyId)
                ?: return@withContext false
            
            val privateKey = retrievePrivateKey(keyId)
                ?: return@withContext false
            
            val publicKey = getPublicKeyFromPrivate(privateKey)
            val publicKeyString = formatPublicKey(publicKey, storedKey.keyType, storedKey.comment)
            
            outputFile.writeText(publicKeyString)
            
            Logger.i("KeyStorage", "Exported public key to: ${outputFile.absolutePath}")
            true
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to export public key $keyId", e)
            false
        }
    }
    
    /**
     * Return the OpenSSH-format public key as a single-line string suitable
     * for copying into an authorized_keys file, or null if the key cannot be
     * retrieved.
     */
    suspend fun getPublicKeyText(keyId: String): String? = withContext(Dispatchers.IO) {
        try {
            val storedKey = database.keyDao().getKeyById(keyId) ?: return@withContext null
            val privateKey = retrievePrivateKey(keyId) ?: return@withContext null
            val publicKey = getPublicKeyFromPrivate(privateKey)
            formatPublicKey(publicKey, storedKey.keyType, storedKey.comment)
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to get public key text for $keyId", e)
            null
        }
    }

    /**
     * Get key fingerprint
     */
    suspend fun getKeyFingerprint(keyId: String): String? = withContext(Dispatchers.IO) {
        val storedKey = database.keyDao().getKeyById(keyId)
        return@withContext storedKey?.fingerprint
    }
    
    private fun calculateFingerprint(publicKey: PublicKey): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(publicKey.encoded)
            
            // Format as SHA256:base64 (OpenSSH format)
            "SHA256:" + android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to calculate fingerprint", e)
            "Unknown"
        }
    }
    
    private fun detectKeyType(privateKey: PrivateKey): KeyType {
        return when (privateKey) {
            is RSAPrivateKey -> KeyType.RSA
            is DSAPrivateKey -> KeyType.DSA
            is ECPrivateKey -> KeyType.ECDSA
            else -> {
                // Check algorithm name for Ed25519
                when (privateKey.algorithm) {
                    "Ed25519" -> KeyType.ED25519
                    "RSA" -> KeyType.RSA
                    "DSA" -> KeyType.DSA
                    "EC" -> KeyType.ECDSA
                    else -> KeyType.RSA // Default fallback
                }
            }
        }
    }
    
    private fun getKeySize(privateKey: PrivateKey): Int? {
        return when (privateKey) {
            is RSAPrivateKey -> privateKey.modulus.bitLength()
            is DSAPrivateKey -> privateKey.params.p.bitLength()
            is ECPrivateKey -> {
                val spec = privateKey.params
                spec.curve.field.fieldSize
            }
            else -> {
                when (privateKey.algorithm) {
                    "Ed25519" -> 256
                    else -> null
                }
            }
        }
    }
    
    private fun createKeyEncryptionKey(keyId: String): SecretKey {
        val keyAlias = "$KEY_ENCRYPTION_ALIAS_PREFIX$keyId"
        
        return try {
            (keyStore.getKey(keyAlias, null) as? SecretKey) ?: run {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to create key encryption key", e)
            throw e
        }
    }
    
    private fun parseOpenSSHKey(keyContent: String, passphrase: String?): ParseResult {
        return try {
            Logger.d("KeyStorage", "Parsing OpenSSH private key format using SSHKeyParser")
            
            // Use SSHKeyParser which properly handles openssh-key-v1 format
            // It correctly detects encrypted vs unencrypted keys
            val parsedKey = SSHKeyParser.parse(keyContent, passphrase)
            
            // Convert to KeyPair for consistency with other parsers
            val keyPair = if (parsedKey.privateKey != null) {
                KeyPair(parsedKey.publicKey, parsedKey.privateKey)
            } else {
                // Public key only
                throw IllegalArgumentException("Public key import not supported via this method")
            }
            
            Logger.i("KeyStorage", "Successfully parsed OpenSSH key with SSHKeyParser")
            ParseResult.Success(keyPair, parsedKey.comment)
            
        } catch (e: IllegalArgumentException) {
            // SSHKeyParser throws clear error messages
            Logger.e("KeyStorage", "Failed to parse OpenSSH key: ${e.message}", e)
            
            // Check if it's the "Passphrase required" error
            if (e.message?.contains("Passphrase required") == true) {
                ParseResult.Error("Key is encrypted but no passphrase provided")
            } else {
                ParseResult.Error(e.message ?: "Failed to parse OpenSSH key")
            }
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to parse OpenSSH key", e)
            ParseResult.Error("Failed to parse OpenSSH key: ${e.message}")
        }
    }
    
    private fun parseTraditionalKey(keyContent: String, passphrase: String?, algorithm: String): ParseResult {
        return try {
            Logger.d("KeyStorage", "Parsing traditional $algorithm key")
            
            val pemParser = PEMParser(StringReader(keyContent))
            val pemObject = pemParser.readObject()
            pemParser.close()
            
            val converter = JcaPEMKeyConverter().setProvider("BC")
            
            val keyPair = when (pemObject) {
                is PEMKeyPair -> {
                    // Unencrypted traditional format
                    converter.getKeyPair(pemObject)
                }
                is PEMEncryptedKeyPair -> {
                    // Encrypted traditional format
                    if (passphrase == null) {
                        return ParseResult.Error("Key is encrypted but no passphrase provided")
                    }
                    val decryptorProvider = JcePEMDecryptorProviderBuilder().build(passphrase.toCharArray())
                    val decryptedKeyPair = pemObject.decryptKeyPair(decryptorProvider)
                    converter.getKeyPair(decryptedKeyPair)
                }
                is PrivateKeyInfo -> {
                    // PKCS#8 format (unencrypted)
                    val privateKey = converter.getPrivateKey(pemObject)
                    val publicKey = getPublicKeyFromPrivate(privateKey)
                    KeyPair(publicKey, privateKey)
                }
                else -> {
                    return ParseResult.Error("Unsupported PEM object type: ${pemObject.javaClass.simpleName}")
                }
            }
            
            // Extract comment from original PEM if present
            val comment = extractCommentFromPEM(keyContent)
            
            Logger.i("KeyStorage", "Successfully parsed $algorithm key")
            ParseResult.Success(keyPair, comment)
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to parse traditional $algorithm key", e)
            ParseResult.Error(
                """Failed to parse $algorithm private key.

Possible causes:
• Key file corrupted
• Unsupported key variant  
• Wrong passphrase (if encrypted)

Error: ${e.javaClass.simpleName}: ${e.message}"""
            )
        }
    }
    
    private fun parsePKCS8Key(keyContent: String, passphrase: String?): ParseResult {
        return try {
            Logger.d("KeyStorage", "Parsing PKCS#8 key")
            
            val pemParser = PEMParser(StringReader(keyContent))
            val pemObject = pemParser.readObject()
            pemParser.close()
            
            val converter = JcaPEMKeyConverter().setProvider("BC")
            
            val privateKey = when (pemObject) {
                is PrivateKeyInfo -> {
                    // Unencrypted PKCS#8
                    converter.getPrivateKey(pemObject)
                }
                is PKCS8EncryptedPrivateKeyInfo -> {
                    // Encrypted PKCS#8
                    if (passphrase == null) {
                        return ParseResult.Error("Key is encrypted but no passphrase provided")
                    }
                    val decryptorProvider = JcePKCSPBEInputDecryptorProviderBuilder()
                        .setProvider("BC")
                        .build(passphrase.toCharArray())
                    val decryptedKeyInfo = pemObject.decryptPrivateKeyInfo(decryptorProvider)
                    converter.getPrivateKey(decryptedKeyInfo)
                }
                else -> {
                    return ParseResult.Error("Expected PKCS#8 format but found: ${pemObject.javaClass.simpleName}")
                }
            }
            
            val publicKey = getPublicKeyFromPrivate(privateKey)
            val comment = extractCommentFromPEM(keyContent)
            
            Logger.i("KeyStorage", "Successfully parsed PKCS#8 key")
            ParseResult.Success(KeyPair(publicKey, privateKey), comment)
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to parse PKCS8 key", e)
            ParseResult.Error("Failed to parse PKCS#8 key: ${e.message}")
        }
    }
    
    private fun parsePuttyKey(keyContent: String, passphrase: String?): ParseResult {
        // PuTTY key format (.ppk files) parsing
        // Format contains: PuTTY-User-Key-File-2: ssh-rsa, Encryption, Comment, Public-Lines, Private-Lines, etc.
        return try {
            Logger.d("KeyStorage", "Parsing PuTTY private key format")

            // PuTTY format is proprietary and complex
            // It includes: key type, encryption type, comment, public key blob, private key blob, and MAC
            // Full implementation would require:
            // 1. Parse the structured text format
            // 2. Decrypt private key if encrypted (using passphrase with AES-256-CBC)
            // 3. Verify MAC
            // 4. Reconstruct key pair

            val lines = keyContent.lines()
            val keyType = lines.find { it.startsWith("PuTTY-User-Key-File") }
                ?.substringAfter(":")
                ?.trim()

            Logger.i("KeyStorage", "Detected PuTTY key type: $keyType")

            // For now, provide helpful error with conversion instructions
            ParseResult.Error(
                "PuTTY key format not yet supported.\n" +
                "Convert to OpenSSH format using PuTTYgen:\n" +
                "1. Open PuTTYgen\n" +
                "2. Load your .ppk file\n" +
                "3. Conversions -> Export OpenSSH key\n" +
                "OR use puttygen command: puttygen key.ppk -O private-openssh -o key_openssh"
            )
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to parse PuTTY key", e)
            ParseResult.Error("PuTTY key parsing failed: ${e.message}")
        }
    }
    
    private fun reconstructPrivateKey(keyBytes: ByteArray, keyType: KeyType): PrivateKey {
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(keyType.getJavaAlgorithm())
        return keyFactory.generatePrivate(keySpec)
    }
    
    internal fun getPublicKeyFromPrivate(privateKey: PrivateKey): PublicKey {
        return when (privateKey) {
            is RSAPrivateCrtKey -> {
                val keySpec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePublic(keySpec)
            }
            is DSAPrivateKey -> {
                val params = privateKey.params
                val y = params.g.modPow(privateKey.x, params.p)
                val keySpec = DSAPublicKeySpec(y, params.p, params.q, params.g)
                val keyFactory = KeyFactory.getInstance("DSA")
                keyFactory.generatePublic(keySpec)
            }
            is ECPrivateKey -> {
                try {
                    // EC public key derivation using BouncyCastle
                    Logger.d("KeyStorage", "Deriving EC public key using BouncyCastle")

                    // Get the EC parameters from the private key
                    val ecParams = privateKey.params

                    // Get curve name - try to determine from field size
                    val fieldSize = ecParams.curve.field.fieldSize
                    val curveName = when (fieldSize) {
                        256 -> "secp256r1"
                        384 -> "secp384r1"
                        521 -> "secp521r1"
                        else -> throw IllegalArgumentException("Unsupported EC curve field size: $fieldSize")
                    }

                    // Use BouncyCastle for EC point multiplication
                    val bcSpec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(curveName)
                    val d = privateKey.s
                    val q = bcSpec.g.multiply(d)
                    val pubKeySpec = org.bouncycastle.jce.spec.ECPublicKeySpec(q, bcSpec)
                    val keyFactory = KeyFactory.getInstance("EC", "BC")
                    return keyFactory.generatePublic(pubKeySpec)

                } catch (e: Exception) {
                    Logger.e("KeyStorage", "EC public key derivation failed", e)
                    throw UnsupportedOperationException(
                        "Unable to derive EC public key from private key: ${e.message}"
                    )
                }
            }
            else -> {
                // Handle Ed25519 and other key types
                when (privateKey.algorithm) {
                    "Ed25519" -> {
                        try {
                            // Ed25519 public key derivation using BouncyCastle
                            val pkInfo = org.bouncycastle.asn1.pkcs.PrivateKeyInfo.getInstance(privateKey.encoded)
                            val seed = (pkInfo.parsePrivateKey() as org.bouncycastle.asn1.ASN1OctetString).octets

                            // Generate public key from seed using BouncyCastle
                            val privateParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
                            val publicParams = privateParams.generatePublicKey()
                            val publicBytes = publicParams.encoded

                            // Convert to Java PublicKey
                            val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo(
                                org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                    org.bouncycastle.asn1.edec.EdECObjectIdentifiers.id_Ed25519
                                ),
                                publicBytes
                            )
                            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
                            return keyFactory.generatePublic(X509EncodedKeySpec(spki.encoded))

                        } catch (e: Exception) {
                            Logger.e("KeyStorage", "Ed25519 public key derivation failed", e)
                            throw UnsupportedOperationException(
                                "Ed25519 public key derivation failed: ${e.message}"
                            )
                        }
                    }
                    else -> {
                        throw UnsupportedOperationException("Unsupported key type: ${privateKey.algorithm}")
                    }
                }
            }
        }
    }
    
    /**
     * Extract comment from PEM file content
     */
    private fun extractCommentFromPEM(pemContent: String): String {
        // Look for comment lines in PEM (lines starting with #)
        val lines = pemContent.lines()
        val commentLines = lines.filter { it.trim().startsWith("#") }
        return if (commentLines.isNotEmpty()) {
            commentLines.joinToString(" ") { it.trim().removePrefix("#").trim() }
        } else {
            ""
        }
    }
    
    private fun formatPublicKey(publicKey: PublicKey, keyType: String, comment: String?): String {
        return try {
            // Format public key in OpenSSH format
            val keyTypeStr = KeyType.valueOf(keyType).getOpenSSHIdentifier()
            
            // For proper OpenSSH format, we need to encode the key properly
            // This requires algorithm-specific encoding
            val keyData = when (keyTypeStr) {
                "ssh-rsa" -> {
                    val rsaKey = publicKey as RSAPublicKey
                    encodeRSAPublicKey(rsaKey)
                }
                "ssh-dss" -> {
                    val dsaKey = publicKey as DSAPublicKey
                    encodeDSAPublicKey(dsaKey)
                }
                "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521" -> {
                    val ecKey = publicKey as ECPublicKey
                    encodeECDSAPublicKey(ecKey)
                }
                "ssh-ed25519" -> {
                    encodeEd25519PublicKey(publicKey)
                }
                else -> {
                    // Fallback to basic encoding
                    android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.NO_WRAP)
                }
            }
            
            val commentStr = comment?.let { " $it" } ?: ""
            "$keyTypeStr $keyData$commentStr"
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to format public key in OpenSSH wire format", e)
            // Last-resort: log a clear warning. publicKey.encoded is SPKI/DER, not
            // authorized_keys format — do NOT base64 it as-is (sshd would reject it).
            // Propagate the error so the caller can surface it to the user.
            throw e
        }
    }
    
    // ── OpenSSH wire-format helpers ──────────────────────────────────────────
    //
    // `PublicKey.encoded` returns the X.509 SPKI/DER blob, NOT the OpenSSH
    // authorized_keys wire format. sshd silently rejects SPKI-encoded lines.
    // Each helper builds the SSH wire encoding manually:
    //   uint32(len(type_string)) || type_string || [key-type-specific payload]
    //
    // Reference: RFC 4253 §6.6 (SSH public-key wire format).

    private fun opensshWireString(s: String): ByteArray {
        val b = s.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        return java.nio.ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array()
    }

    private fun opensshWireMPInt(n: java.math.BigInteger): ByteArray {
        val b = n.toByteArray()   // two's-complement big-endian, with sign byte if needed
        return java.nio.ByteBuffer.allocate(4 + b.size).putInt(b.size).put(b).array()
    }

    private fun encodeRSAPublicKey(rsaKey: RSAPublicKey): String {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(opensshWireString("ssh-rsa"))
        buf.write(opensshWireMPInt(rsaKey.publicExponent))
        buf.write(opensshWireMPInt(rsaKey.modulus))
        return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun encodeDSAPublicKey(dsaKey: DSAPublicKey): String {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(opensshWireString("ssh-dss"))
        buf.write(opensshWireMPInt(dsaKey.params.p))
        buf.write(opensshWireMPInt(dsaKey.params.q))
        buf.write(opensshWireMPInt(dsaKey.params.g))
        buf.write(opensshWireMPInt(dsaKey.y))
        return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun encodeECDSAPublicKey(ecKey: ECPublicKey): String {
        val bits = ecKey.params.order.bitLength()
        val keyType = when (bits) {
            256 -> "ecdsa-sha2-nistp256"
            384 -> "ecdsa-sha2-nistp384"
            521 -> "ecdsa-sha2-nistp521"
            else -> throw IllegalArgumentException("Unsupported EC curve: $bits bits")
        }
        val curveName = when (bits) {
            256 -> "nistp256"; 384 -> "nistp384"; else -> "nistp521"
        }
        // Uncompressed EC point: 0x04 || x || y, each field ceil(bits/8) bytes.
        val fieldLen = (bits + 7) / 8
        fun bigIntToFixedLen(n: java.math.BigInteger): ByteArray {
            val raw = n.toByteArray()
            return when {
                raw.size == fieldLen     -> raw
                raw.size == fieldLen + 1 -> raw.copyOfRange(1, raw.size)  // strip sign byte
                else                     -> ByteArray(fieldLen - raw.size) + raw
            }
        }
        val pointBytes = byteArrayOf(0x04.toByte()) +
            bigIntToFixedLen(ecKey.w.affineX) +
            bigIntToFixedLen(ecKey.w.affineY)
        val buf = java.io.ByteArrayOutputStream()
        buf.write(opensshWireString(keyType))
        buf.write(opensshWireString(curveName))
        buf.write(java.nio.ByteBuffer.allocate(4 + pointBytes.size)
            .putInt(pointBytes.size).put(pointBytes).array())
        return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun encodeEd25519PublicKey(edKey: PublicKey): String {
        // On API < 33 generateEd25519KeyPair() silently falls back to ECDSA P-256.
        // Dispatch to the correct encoder so the exported line is always valid.
        if (edKey is ECPublicKey) {
            Logger.w("KeyStorage", "Ed25519 requested but got EC key (API<33 fallback) — encoding as ECDSA")
            return encodeECDSAPublicKey(edKey)
        }
        // BouncyCastle Ed25519 SPKI/DER is always exactly 44 bytes:
        //   30 2a 30 05 06 03 2b 65 70 03 21 00 <32-byte-raw-key>
        // Validate the length before trusting takeLast(32).
        val spki = edKey.encoded
        require(spki.size == 44) {
            "Unexpected Ed25519 SPKI size ${spki.size} — cannot extract raw public key"
        }
        val raw = spki.takeLast(32).toByteArray()
        val buf = java.io.ByteArrayOutputStream()
        buf.write(opensshWireString("ssh-ed25519"))
        buf.write(java.nio.ByteBuffer.allocate(4 + raw.size).putInt(raw.size).put(raw).array())
        return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
    }
}

// Result classes for key operations
sealed class GenerateResult {
    data class Success(val keyId: String, val keyPair: KeyPair, val fingerprint: String) : GenerateResult()
    data class Error(val message: String) : GenerateResult()
}

sealed class ImportResult {
    data class Success(val keyId: String, val keyPair: KeyPair, val fingerprint: String) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

sealed class ParseResult {
    data class Success(val keyPair: KeyPair, val comment: String) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

/**
 * Lightweight key preview returned by [KeyStorage.previewKey].
 * Does not store anything — used to populate import dialog defaults.
 */
data class KeyPreviewInfo(
    val comment: String,
    val keyType: KeyType?,
    val isEncrypted: Boolean = false
)
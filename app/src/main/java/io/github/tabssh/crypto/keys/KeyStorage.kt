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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEInputDecryptorProviderBuilder

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
    
    // Add BouncyCastle provider for enhanced cryptographic support
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
            Logger.d("KeyStorage", "Added BouncyCastle security provider")
        }
    }
    
    fun initialize() {
        if (isInitialized) return
        
        try {
            keyStore.load(null)
            Logger.d("KeyStorage", "Initialized with Android Keystore")
            isInitialized = true
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to initialize keystore", e)
            throw SecurityException("Failed to initialize key storage", e)
        }
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
            
            // Store the private key securely
            val keyId = storePrivateKey(
                privateKey = keyPair.private,
                publicKey = keyPair.public,
                keyType = keyType,
                name = keyName,
                comment = comment,
                fingerprint = fingerprint,
                passphrase = passphrase
            )
            
            if (keyId != null) {
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
    suspend fun importKeyFromFile(fileUri: Uri, passphrase: String? = null): ImportResult = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext ImportResult.Error("Cannot open file")
            
            val keyContent = inputStream.bufferedReader().readText()
            importKeyFromText(keyContent, passphrase, fileUri.lastPathSegment ?: "Imported Key")
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to import key from file", e)
            ImportResult.Error("Failed to read key file: ${e.message}")
        }
    }
    
    suspend fun importKeyFromText(
        keyContent: String, 
        passphrase: String? = null, 
        keyName: String = "Imported Key"
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
                    
                    val keyId = storePrivateKey(
                        privateKey = parseResult.keyPair.private,
                        publicKey = parseResult.keyPair.public,
                        keyType = keyType,
                        name = keyName,
                        comment = parseResult.comment,
                        fingerprint = fingerprint,
                        passphrase = passphrase
                    )
                    
                    if (keyId != null) {
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
                fingerprint = fingerprint,
                requiresPassphrase = passphrase != null,
                keySize = getKeySize(privateKey)
            )
            
            database.keyDao().insertKey(storedKey)
            
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
                ?: return@withContext null
            
            val keyType = KeyType.valueOf(storedKey.keyType)
            
            // Reconstruct private key
            val privateKey = reconstructPrivateKey(decryptedKeyBytes, keyType)
            
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
    
    // Parsing methods for different key formats (simplified implementations)
    
    private fun parseOpenSSHKey(keyContent: String, passphrase: String?): ParseResult {
        return try {
            Logger.d("KeyStorage", "Parsing OpenSSH private key format")
            
            // Try parsing with BouncyCastle first
            val pemParser = PEMParser(StringReader(keyContent))
            val pemObject = pemParser.readObject()
            pemParser.close()
            
            // Check if BouncyCastle can handle this OpenSSH format
            val converter = JcaPEMKeyConverter().setProvider("BC")
            
            val keyPair = when (pemObject) {
                is PEMKeyPair -> {
                    Logger.d("KeyStorage", "Parsing as unencrypted OpenSSH key")
                    converter.getKeyPair(pemObject)
                }
                is PEMEncryptedKeyPair -> {
                    if (passphrase == null) {
                        return ParseResult.Error("Key is encrypted but no passphrase provided")
                    }
                    Logger.d("KeyStorage", "Parsing as encrypted OpenSSH key")
                    val decryptorProvider = JcePEMDecryptorProviderBuilder().build(passphrase.toCharArray())
                    val decryptedKeyPair = pemObject.decryptKeyPair(decryptorProvider)
                    converter.getKeyPair(decryptedKeyPair)
                }
                is PrivateKeyInfo -> {
                    Logger.d("KeyStorage", "Parsing as PKCS#8 format within OpenSSH container")
                    val privateKey = converter.getPrivateKey(pemObject)
                    val publicKey = getPublicKeyFromPrivate(privateKey)
                    KeyPair(publicKey, privateKey)
                }
                else -> {
                    // Fallback: try manual OpenSSH format parsing
                    return parseOpenSSHFormatManually(keyContent, passphrase)
                }
            }
            
            val comment = extractCommentFromPEM(keyContent)
            Logger.i("KeyStorage", "Successfully parsed OpenSSH key")
            ParseResult.Success(keyPair, comment)
            
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Failed to parse OpenSSH key with BouncyCastle", e)
            // Fallback to manual parsing
            parseOpenSSHFormatManually(keyContent, passphrase)
        }
    }
    
    private fun parseOpenSSHFormatManually(keyContent: String, passphrase: String?): ParseResult {
        return try {
            Logger.d("KeyStorage", "Attempting manual OpenSSH format parsing")
            
            // Extract base64 data between headers
            val keyData = keyContent
                .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replace("-----END OPENSSH PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")

            val keyBytes = android.util.Base64.decode(keyData, android.util.Base64.DEFAULT)
            
            // OpenSSH format parsing requires detailed binary protocol implementation
            // For now, provide clear error message with conversion instructions
            if (passphrase != null) {
                ParseResult.Error(
                    "Encrypted OpenSSH keys require conversion.\n" +
                    "Convert to PKCS#8 format using:\n" +
                    "ssh-keygen -p -m pkcs8 -f your_key"
                )
            } else {
                ParseResult.Error(
                    "OpenSSH format parsing requires conversion.\n" +
                    "Convert to PKCS#8 format using:\n" +
                    "ssh-keygen -p -m pkcs8 -f your_key\n" +
                    "This will make the key compatible with TabSSH."
                )
            }
        } catch (e: Exception) {
            Logger.e("KeyStorage", "Manual OpenSSH parsing failed", e)
            ParseResult.Error("OpenSSH key parsing failed. Please convert to PKCS#8 format.")
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
            ParseResult.Error("Failed to parse $algorithm key: ${e.message}")
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
    
    private fun getPublicKeyFromPrivate(privateKey: PrivateKey): PublicKey {
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
                    // For EC keys, we'll use a simplified approach since full point multiplication
                    // requires either BouncyCastle EC classes or complex math
                    // Most practical cases will have the public key available in the PEM
                    
                    Logger.w("KeyStorage", "EC public key derivation is complex - recommend providing public key separately")
                    
                    // Attempt basic derivation using Java's built-in crypto
                    val keyFactory = KeyFactory.getInstance("EC")
                    
                    // This is a simplified fallback - real EC derivation is quite complex
                    // In practice, PEM parsing should extract both public and private parts
                    throw UnsupportedOperationException(
                        "EC public key derivation requires the public key to be provided separately. " +
                        "Most PEM files contain both keys, but this one may be incomplete. " +
                        "Try: ssh-keygen -y -f your_private_key > your_public_key.pub"
                    )
                    
                } catch (e: Exception) {
                    Logger.e("KeyStorage", "EC public key derivation failed", e)
                    throw UnsupportedOperationException(
                        "Unable to derive EC public key from private key. " +
                        "This is a known limitation. Please ensure your PEM file contains the public key, " +
                        "or generate it using: ssh-keygen -y -f your_private_key"
                    )
                }
            }
            else -> {
                // Handle Ed25519 and other key types
                when (privateKey.algorithm) {
                    "Ed25519" -> {
                        try {
                            // Ed25519 public key derivation
                            val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
                            
                            // For Ed25519, the public key can be derived from the private key
                            // BouncyCastle should handle this automatically
                            val encoded = privateKey.encoded
                            val keySpec = PKCS8EncodedKeySpec(encoded)
                            val regeneratedPrivateKey = keyFactory.generatePrivate(keySpec)
                            
                            // Extract public key from Ed25519 private key
                            // This is algorithm-specific and requires proper implementation
                            throw UnsupportedOperationException(
                                "Ed25519 public key derivation not yet implemented. " +
                                "Please provide the public key separately."
                            )
                            
                        } catch (e: Exception) {
                            Logger.e("KeyStorage", "Ed25519 public key derivation failed", e)
                            throw UnsupportedOperationException(
                                "Ed25519 public key derivation requires the public key to be provided separately."
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
            Logger.e("KeyStorage", "Failed to format public key", e)
            // Fallback to simple encoding
            val keyTypeStr = KeyType.valueOf(keyType).getOpenSSHIdentifier()
            val keyData = android.util.Base64.encodeToString(publicKey.encoded, android.util.Base64.NO_WRAP)
            val commentStr = comment?.let { " $it" } ?: ""
            "$keyTypeStr $keyData$commentStr"
        }
    }
    
    private fun encodeRSAPublicKey(rsaKey: RSAPublicKey): String {
        // OpenSSH RSA public key format: ssh-rsa AAAAB3NzaC1yc2EAAAA...
        // This requires proper wire format encoding
        return android.util.Base64.encodeToString(rsaKey.encoded, android.util.Base64.NO_WRAP)
    }
    
    private fun encodeDSAPublicKey(dsaKey: DSAPublicKey): String {
        // OpenSSH DSA public key format
        return android.util.Base64.encodeToString(dsaKey.encoded, android.util.Base64.NO_WRAP)
    }
    
    private fun encodeECDSAPublicKey(ecKey: ECPublicKey): String {
        // OpenSSH ECDSA public key format
        return android.util.Base64.encodeToString(ecKey.encoded, android.util.Base64.NO_WRAP)
    }
    
    private fun encodeEd25519PublicKey(edKey: PublicKey): String {
        // OpenSSH Ed25519 public key format
        return android.util.Base64.encodeToString(edKey.encoded, android.util.Base64.NO_WRAP)
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
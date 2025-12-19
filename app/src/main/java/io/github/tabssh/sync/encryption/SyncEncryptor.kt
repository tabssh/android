package io.github.tabssh.sync.encryption

import io.github.tabssh.sync.models.EncryptedData
import io.github.tabssh.utils.logging.Logger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles encryption and decryption of sync files using AES-256-GCM with PBKDF2 key derivation
 */
class SyncEncryptor {

    companion object {
        private const val TAG = "SyncEncryptor"

        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

        private const val KEY_SIZE = 256
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
        private const val SALT_SIZE = 32
        private const val PBKDF2_ITERATIONS = 100_000

        private const val HEADER_MAGIC = "TABSSH_SYNC_V2"
        private const val HEADER_SIZE = 32
    }

    private val secureRandom = SecureRandom()

    /**
     * Encrypt data with password-based encryption
     */
    fun encryptSyncFile(data: ByteArray, password: String): EncryptedData {
        try {
            val salt = generateSalt()
            val key = deriveKey(password, salt)
            val iv = generateIV()

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

            val ciphertext = cipher.doFinal(data)

            Logger.d(TAG, "Sync file encrypted successfully (${data.size} bytes -> ${ciphertext.size} bytes)")

            return EncryptedData(
                ciphertext = ciphertext,
                iv = iv,
                salt = salt
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to encrypt sync file", e)
            throw SyncEncryptionException("Encryption failed: ${e.message}", e)
        }
    }

    /**
     * Decrypt data with password-based decryption
     */
    fun decryptSyncFile(encrypted: EncryptedData, password: String): ByteArray {
        try {
            val key = deriveKey(password, encrypted.salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE, encrypted.iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

            val plaintext = cipher.doFinal(encrypted.ciphertext)

            Logger.d(TAG, "Sync file decrypted successfully (${encrypted.ciphertext.size} bytes -> ${plaintext.size} bytes)")

            return plaintext
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to decrypt sync file", e)
            throw SyncEncryptionException("Decryption failed - wrong password or corrupted data: ${e.message}", e)
        }
    }

    /**
     * Validate password against encrypted data without full decryption
     */
    fun validatePassword(encrypted: EncryptedData, password: String): Boolean {
        return try {
            decryptSyncFile(encrypted, password)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Derive encryption key from password using PBKDF2
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        try {
            val spec = PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                KEY_SIZE
            )

            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val key = factory.generateSecret(spec)

            return SecretKeySpec(key.encoded, KEY_ALGORITHM)
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to derive key", e)
            throw SyncEncryptionException("Key derivation failed: ${e.message}", e)
        }
    }

    /**
     * Serialize encrypted data to byte array for storage
     */
    fun serializeEncryptedData(encrypted: EncryptedData): ByteArray {
        val header = createHeader()
        val totalSize = HEADER_SIZE + SALT_SIZE + IV_SIZE + encrypted.ciphertext.size

        val output = ByteArray(totalSize)
        var offset = 0

        System.arraycopy(header, 0, output, offset, HEADER_SIZE)
        offset += HEADER_SIZE

        System.arraycopy(encrypted.salt, 0, output, offset, SALT_SIZE)
        offset += SALT_SIZE

        System.arraycopy(encrypted.iv, 0, output, offset, IV_SIZE)
        offset += IV_SIZE

        System.arraycopy(encrypted.ciphertext, 0, output, offset, encrypted.ciphertext.size)

        return output
    }

    /**
     * Deserialize encrypted data from byte array
     */
    fun deserializeEncryptedData(data: ByteArray): EncryptedData {
        if (data.size < HEADER_SIZE + SALT_SIZE + IV_SIZE) {
            throw SyncEncryptionException("Invalid encrypted data: too small")
        }

        var offset = 0

        val header = ByteArray(HEADER_SIZE)
        System.arraycopy(data, offset, header, 0, HEADER_SIZE)
        offset += HEADER_SIZE

        validateHeader(header)

        val salt = ByteArray(SALT_SIZE)
        System.arraycopy(data, offset, salt, 0, SALT_SIZE)
        offset += SALT_SIZE

        val iv = ByteArray(IV_SIZE)
        System.arraycopy(data, offset, iv, 0, IV_SIZE)
        offset += IV_SIZE

        val ciphertext = ByteArray(data.size - offset)
        System.arraycopy(data, offset, ciphertext, 0, ciphertext.size)

        return EncryptedData(
            ciphertext = ciphertext,
            iv = iv,
            salt = salt
        )
    }

    /**
     * Generate random salt for PBKDF2
     */
    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_SIZE)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Generate random IV for AES-GCM
     */
    private fun generateIV(): ByteArray {
        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Create file header with magic bytes and version
     */
    private fun createHeader(): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        val magicBytes = HEADER_MAGIC.toByteArray()
        System.arraycopy(magicBytes, 0, header, 0, minOf(magicBytes.size, HEADER_SIZE))
        return header
    }

    /**
     * Validate file header
     */
    private fun validateHeader(header: ByteArray) {
        val magicBytes = HEADER_MAGIC.toByteArray()
        for (i in magicBytes.indices) {
            if (header[i] != magicBytes[i]) {
                throw SyncEncryptionException("Invalid file format: header mismatch")
            }
        }
    }

    /**
     * Check if password meets strength requirements
     */
    fun isPasswordStrong(password: String): Boolean {
        if (password.length < 12) return false

        val hasUppercase = password.any { it.isUpperCase() }
        val hasLowercase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        val criteriaCount = listOf(hasUppercase, hasLowercase, hasDigit, hasSpecial).count { it }

        return criteriaCount >= 3
    }

    /**
     * Get password strength description
     */
    fun getPasswordStrength(password: String): PasswordStrength {
        if (password.length < 8) return PasswordStrength.WEAK
        if (password.length < 12) return PasswordStrength.FAIR

        val hasUppercase = password.any { it.isUpperCase() }
        val hasLowercase = password.any { it.isLowerCase() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() }

        val criteriaCount = listOf(hasUppercase, hasLowercase, hasDigit, hasSpecial).count { it }

        return when {
            password.length >= 16 && criteriaCount >= 4 -> PasswordStrength.VERY_STRONG
            password.length >= 14 && criteriaCount >= 3 -> PasswordStrength.STRONG
            password.length >= 12 && criteriaCount >= 3 -> PasswordStrength.GOOD
            criteriaCount >= 2 -> PasswordStrength.FAIR
            else -> PasswordStrength.WEAK
        }
    }
}

/**
 * Password strength levels
 */
enum class PasswordStrength(val description: String) {
    WEAK("Weak - Not recommended"),
    FAIR("Fair - Consider adding more characters"),
    GOOD("Good - Acceptable"),
    STRONG("Strong - Recommended"),
    VERY_STRONG("Very Strong - Excellent")
}

/**
 * Exception for sync encryption errors
 */
class SyncEncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

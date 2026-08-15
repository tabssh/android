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

        // Write-time PBKDF2 cost. Raised from 100k to OWASP's 2023 floor for
        // PBKDF2-HMAC-SHA256. NOT a hardcoded read constant: every file records
        // the count it was written with in its header (see createHeader), so
        // this value can move again in future without breaking any file already
        // on disk — the reader always honours the stored count, never this one.
        private const val PBKDF2_ITERATIONS = 600_000

        // Iteration count for legacy V2 files, which predate the self-describing
        // header field. Those files were always produced at exactly 100k, so a
        // V2 magic is read back with this fixed count.
        private const val PBKDF2_ITERATIONS_LEGACY = 100_000

        // Shared 13-byte prefix of every version magic ("TABSSH_SYNC_V"); the
        // 14th byte is the ASCII version digit ('2' legacy, '3' current). New
        // files are written as V3 with the iteration count embedded; V2 files
        // are still read (at PBKDF2_ITERATIONS_LEGACY) so existing encrypted
        // backups and sync snapshots keep decrypting unchanged.
        private const val MAGIC_PREFIX = "TABSSH_SYNC_V"
        private const val VERSION_LEGACY = '2'
        private const val VERSION_CURRENT = '3'
        private const val HEADER_MAGIC = "$MAGIC_PREFIX$VERSION_CURRENT"

        // Big-endian int32 iteration count lives here, right after the 14-byte
        // magic, inside the 32-byte header. V2 headers have zeroes here.
        private const val ITER_OFFSET = 14

        // Guard band for a header-declared iteration count: reject a corrupted
        // or absurd value and fall back to the legacy count rather than spin
        // for minutes (or trivially) on a garbage header.
        private const val ITER_MIN = 1_000
        private const val ITER_MAX = 100_000_000

        private const val HEADER_SIZE = 32
    }

    // Lazy: BouncyCastle's DRBG seeds SecureRandom by calling Provider.getServices()
    // which can block for 5-10 seconds on first use.  Deferring to first actual
    // encrypt/decrypt call ensures initialisation always happens on a background
    // thread (IO dispatcher) rather than on the main thread at construction time.
    private val secureRandom by lazy { SecureRandom() }

    /**
     * Simple encrypt method - returns serialized encrypted data
     */
    fun encrypt(data: ByteArray, password: String): ByteArray {
        val encrypted = encryptSyncFile(data, password)
        return serializeEncryptedData(encrypted)
    }

    /**
     * Simple decrypt method - takes serialized encrypted data
     */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        val encrypted = deserializeEncryptedData(data)
        return decryptSyncFile(encrypted, password, readIterations(data))
    }

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
    fun decryptSyncFile(
        encrypted: EncryptedData,
        password: String,
        iterations: Int = PBKDF2_ITERATIONS
    ): ByteArray {
        try {
            val key = deriveKey(password, encrypted.salt, iterations)
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
    fun deriveKey(
        password: String,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): SecretKey {
        try {
            val spec = PBEKeySpec(
                password.toCharArray(),
                salt,
                iterations,
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
        val header = createHeader(PBKDF2_ITERATIONS)
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
    private fun createHeader(iterations: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        val magicBytes = HEADER_MAGIC.toByteArray()
        System.arraycopy(magicBytes, 0, header, 0, minOf(magicBytes.size, HEADER_SIZE))
        // Embed the PBKDF2 iteration count (big-endian) so the file is
        // self-describing: a reader derives the key with the count the file was
        // written with, never a compiled-in constant.
        header[ITER_OFFSET] = (iterations ushr 24 and 0xFF).toByte()
        header[ITER_OFFSET + 1] = (iterations ushr 16 and 0xFF).toByte()
        header[ITER_OFFSET + 2] = (iterations ushr 8 and 0xFF).toByte()
        header[ITER_OFFSET + 3] = (iterations and 0xFF).toByte()
        return header
    }

    /**
     * Validate file header. Accepts both the current V3 magic and the legacy
     * V2 magic (shared 13-byte prefix + a '2' or '3' version digit); any other
     * prefix or version digit is rejected.
     */
    private fun validateHeader(header: ByteArray) {
        val prefixBytes = MAGIC_PREFIX.toByteArray()
        for (i in prefixBytes.indices) {
            if (header[i] != prefixBytes[i]) {
                throw SyncEncryptionException("Invalid file format: header mismatch")
            }
        }
        val version = header[prefixBytes.size].toInt().toChar()
        if (version != VERSION_LEGACY && version != VERSION_CURRENT) {
            throw SyncEncryptionException("Unsupported sync file version: $version")
        }
    }

    /**
     * Recover the PBKDF2 iteration count a serialized file was written with.
     * V3 files carry a big-endian int32 in the header; V2 files predate the
     * field and are always the fixed legacy count. A missing or out-of-range
     * value falls back to the legacy count so a corrupted header fails via GCM
     * authentication rather than a runaway KDF cost.
     */
    private fun readIterations(data: ByteArray): Int {
        if (data.size < ITER_OFFSET + 4) return PBKDF2_ITERATIONS_LEGACY
        val version = data[MAGIC_PREFIX.length].toInt().toChar()
        if (version != VERSION_CURRENT) return PBKDF2_ITERATIONS_LEGACY
        val iterations =
            (data[ITER_OFFSET].toInt() and 0xFF shl 24) or
                (data[ITER_OFFSET + 1].toInt() and 0xFF shl 16) or
                (data[ITER_OFFSET + 2].toInt() and 0xFF shl 8) or
                (data[ITER_OFFSET + 3].toInt() and 0xFF)
        return if (iterations in ITER_MIN..ITER_MAX) iterations else PBKDF2_ITERATIONS_LEGACY
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

}

/**
 * Exception for sync encryption errors
 */
class SyncEncryptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

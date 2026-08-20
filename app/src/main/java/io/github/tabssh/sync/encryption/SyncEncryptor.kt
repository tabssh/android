package io.github.tabssh.sync.encryption

import io.github.tabssh.sync.models.EncryptedData
import io.github.tabssh.utils.logging.Logger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Encryption and decryption of sync files and encrypted backups.
 *
 * AES-256-GCM for confidentiality and integrity, with the key derived from the
 * user's password by **Argon2id** (RFC 9106, version 1.3) via BouncyCastle, as
 * required by AI.md PART 6. Argon2id is memory-hard, so a stolen archive costs
 * an offline attacker real RAM per guess rather than cheap GPU hash rate.
 *
 * ## Serialized file layout
 *
 * A serialized file is a 32-byte header, then the salt, then the GCM IV, then
 * the ciphertext (which carries the 16-byte GCM tag as its final bytes):
 *
 * ```
 * offset  size  field
 * ------  ----  ---------------------------------------------------------
 *      0    14  magic, ASCII "TABSSH_SYNC_V3"
 *     14     1  KDF identifier (0x01 = Argon2id, RFC 9106 version 1.3)
 *     15     4  Argon2id memory cost in KiB, big-endian uint32
 *     19     4  Argon2id passes (t), big-endian uint32
 *     23     1  Argon2id parallelism / lanes (p), uint8
 *     24     8  reserved, written as zero, ignored on read
 *     32    32  salt
 *     64    12  AES-GCM IV
 *     76     n  ciphertext || 16-byte GCM tag
 * ```
 *
 * The KDF cost parameters are written into every file so that raising the
 * production defaults later never strands an archive already on disk: the
 * reader always derives with the parameters the file records, never with the
 * compiled-in defaults. The one-byte KDF identifier is there because the three
 * cost fields are Argon2-specific — if the KDF family ever changes, those
 * fields have to change meaning, and a reader must be able to tell that from
 * the file instead of silently reinterpreting them. That is exactly the trap
 * this layout replaced, where offset 14 held a bare PBKDF2 iteration count.
 * There is no fallback: an identifier other than 0x01 is a hard failure.
 *
 * Header cost parameters are not a tampering surface. Rewriting them changes
 * the derived key, so a modified file simply fails GCM authentication — an
 * attacker cannot downgrade the work factor protecting the original file. The
 * guard bands below exist to stop a *corrupt* header from either allocating
 * gigabytes or spinning for minutes, not to resist an adversary.
 */
class SyncEncryptor(
    /**
     * KDF cost used when writing. Reads always honour the parameters stored in
     * the file. Injectable so unit tests can use a cheap cost without weakening
     * the production defaults.
     */
    private val kdfParams: Argon2Params = DEFAULT_KDF_PARAMS
) {

    /**
     * Argon2id cost parameters as recorded in a file header.
     *
     * @param memoryKib memory cost in KiB
     * @param iterations number of passes (t)
     * @param parallelism number of lanes (p)
     */
    data class Argon2Params(
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int
    ) {
        init {
            require(memoryKib in SyncEncryptor.MEMORY_MIN_KIB..SyncEncryptor.MEMORY_MAX_KIB) {
                "Argon2id memory cost out of range: $memoryKib KiB"
            }
            require(iterations in SyncEncryptor.ITERATIONS_MIN..SyncEncryptor.ITERATIONS_MAX) {
                "Argon2id iteration count out of range: $iterations"
            }
            require(parallelism in SyncEncryptor.PARALLELISM_MIN..SyncEncryptor.PARALLELISM_MAX) {
                "Argon2id parallelism out of range: $parallelism"
            }
            require(memoryKib >= 8 * parallelism) {
                "Argon2id requires memory >= 8 * parallelism KiB"
            }
        }
    }

    companion object {
        private const val TAG = "SyncEncryptor"

        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"

        private const val KEY_SIZE_BYTES = 32
        private const val IV_SIZE = 12
        private const val TAG_SIZE = 128
        private const val SALT_SIZE = 32

        // Write-time Argon2id cost.
        //
        // 64 MiB / t=3 / p=1 measures at roughly one second on a mid-range
        // Android phone, which is the same profile the pairing and app-lock
        // KDFs already ship with (see PairingDecryptor) — one cost profile
        // across the app rather than three unrelated guesses. It sits well
        // above OWASP's 19 MiB / t=2 floor while staying inside the per-app
        // heap a low-RAM API 24 device grants, which rules out the RFC 9106
        // "first recommended option" of 2 GiB outright.
        //
        // p=1 is deliberate and is where this diverges from server-side
        // advice. BouncyCastle's Argon2BytesGenerator walks the lanes on a
        // single thread, so extra lanes buy the defender no wall-clock time
        // while shortening the sequential dependency chain an attacker with
        // real parallel hardware has to follow. One lane is strictly the
        // stronger choice for this implementation.
        //
        // NOT a hardcoded read constant: every file records the parameters it
        // was written with (see createHeader), so this can move again later
        // without breaking any file already on disk.
        private const val ARGON2_MEMORY_KIB = 64 * 1024
        private const val ARGON2_ITERATIONS = 3
        private const val ARGON2_PARALLELISM = 1

        /** Production write-time Argon2id cost. */
        val DEFAULT_KDF_PARAMS = Argon2Params(
            memoryKib = ARGON2_MEMORY_KIB,
            iterations = ARGON2_ITERATIONS,
            parallelism = ARGON2_PARALLELISM
        )

        // Guard bands for header-declared cost parameters. The upper bounds
        // stop a corrupt header from exhausting the heap or running for
        // minutes; the lower bounds reject values Argon2id will not accept.
        // 1 GiB is far past anything an Android device should attempt.
        const val MEMORY_MIN_KIB = 8
        const val MEMORY_MAX_KIB = 1024 * 1024
        const val ITERATIONS_MIN = 1
        const val ITERATIONS_MAX = 64
        const val PARALLELISM_MIN = 1
        const val PARALLELISM_MAX = 16

        // Shared 13-byte prefix of the version magic ("TABSSH_SYNC_V"); the
        // 14th byte is the ASCII version digit. There is exactly one supported
        // version — a file carrying any other digit is rejected outright.
        private const val MAGIC_PREFIX = "TABSSH_SYNC_V"
        private const val VERSION_CURRENT = '3'
        private const val HEADER_MAGIC = "$MAGIC_PREFIX$VERSION_CURRENT"

        // KDF family recorded at KDF_ID_OFFSET. Argon2id, RFC 9106 version 1.3.
        private const val KDF_ID_ARGON2ID: Byte = 0x01

        // Field offsets inside the 32-byte header. See the class KDoc.
        private const val KDF_ID_OFFSET = 14
        private const val MEMORY_OFFSET = 15
        private const val ITERATIONS_OFFSET = 19
        private const val PARALLELISM_OFFSET = 23

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
        return decryptSyncFile(encrypted, password, readKdfParams(data))
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
     * Decrypt data with password-based decryption.
     *
     * @param params Argon2id cost recovered from the file header. Callers that
     *   hold a serialized file must pass the stored parameters — the default
     *   is only correct for data this process just encrypted.
     */
    fun decryptSyncFile(
        encrypted: EncryptedData,
        password: String,
        params: Argon2Params = kdfParams
    ): ByteArray {
        try {
            val key = deriveKey(password, encrypted.salt, params)
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
    fun validatePassword(
        encrypted: EncryptedData,
        password: String,
        params: Argon2Params = kdfParams
    ): Boolean {
        return try {
            decryptSyncFile(encrypted, password, params)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Derive a 256-bit AES key from [password] using Argon2id.
     *
     * The password is encoded as UTF-8 and the working copy is wiped once the
     * generator has consumed it. Nothing derived here is ever logged.
     */
    fun deriveKey(
        password: String,
        salt: ByteArray,
        params: Argon2Params = kdfParams
    ): SecretKey {
        val passwordBytes = password.toByteArray(Charsets.UTF_8)
        try {
            val argon2Params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(argon2Params)

            val keyBytes = ByteArray(KEY_SIZE_BYTES)
            generator.generateBytes(passwordBytes, keyBytes)

            return SecretKeySpec(keyBytes, KEY_ALGORITHM)
        } catch (e: OutOfMemoryError) {
            // BouncyCastle allocates the whole Argon2 cost on the Java heap, which a
            // low-RAM device can refuse outright; an Error is not an Exception, so
            // without this the process dies instead of reporting a failed restore
            Logger.e(TAG, "Key derivation ran out of memory at ${params.memoryKib} KiB", null)
            throw SyncEncryptionException(
                "Key derivation needs ${params.memoryKib} KiB of memory and this device could not spare it",
                null
            )
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to derive key", e)
            throw SyncEncryptionException("Key derivation failed: ${e.message}", e)
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * Serialize encrypted data to byte array for storage
     */
    fun serializeEncryptedData(encrypted: EncryptedData): ByteArray {
        val header = createHeader(kdfParams)
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
     * Generate random salt for the KDF
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
     * Build the 32-byte file header: magic, KDF identifier, and the Argon2id
     * cost [params] the file is about to be written with. Recording the cost
     * makes the file self-describing, so a reader derives with the parameters
     * the file was written at rather than a compiled-in constant. Bytes 24..31
     * are reserved and left zero.
     */
    private fun createHeader(params: Argon2Params): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        val magicBytes = HEADER_MAGIC.toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magicBytes, 0, header, 0, minOf(magicBytes.size, HEADER_SIZE))

        header[KDF_ID_OFFSET] = KDF_ID_ARGON2ID
        writeInt32(header, MEMORY_OFFSET, params.memoryKib)
        writeInt32(header, ITERATIONS_OFFSET, params.iterations)
        header[PARALLELISM_OFFSET] = params.parallelism.toByte()

        return header
    }

    /**
     * Validate file header. Accepts the single current magic only; any other
     * prefix or version digit is an unsupported file and is rejected.
     */
    private fun validateHeader(header: ByteArray) {
        val prefixBytes = MAGIC_PREFIX.toByteArray(Charsets.ISO_8859_1)
        for (i in prefixBytes.indices) {
            if (header[i] != prefixBytes[i]) {
                throw SyncEncryptionException("Invalid file format: header mismatch")
            }
        }
        val version = header[prefixBytes.size].toInt().toChar()
        if (version != VERSION_CURRENT) {
            throw SyncEncryptionException("Unsupported sync file version: $version")
        }
    }

    /**
     * Recover the Argon2id cost parameters a serialized file was written with.
     *
     * A truncated header, an unknown KDF identifier, or an out-of-range cost
     * is a hard failure. Guessing would only turn a clear format error into a
     * confusing GCM authentication error minutes later.
     */
    private fun readKdfParams(data: ByteArray): Argon2Params {
        if (data.size < HEADER_SIZE) {
            throw SyncEncryptionException("Invalid file format: truncated header")
        }
        // Reject a foreign version here too: this runs before the full header
        // check, so without it an unsupported file surfaces as a confusing
        // KDF-parameter error instead of a version error.
        validateHeader(data)

        if (data[KDF_ID_OFFSET] != KDF_ID_ARGON2ID) {
            throw SyncEncryptionException(
                "Unsupported key derivation function: 0x${
                    (data[KDF_ID_OFFSET].toInt() and 0xFF).toString(16).padStart(2, '0')
                }"
            )
        }

        val memoryKib = readInt32(data, MEMORY_OFFSET)
        val iterations = readInt32(data, ITERATIONS_OFFSET)
        val parallelism = data[PARALLELISM_OFFSET].toInt() and 0xFF

        if (memoryKib !in MEMORY_MIN_KIB..MEMORY_MAX_KIB) {
            throw SyncEncryptionException("Invalid file format: bad KDF memory cost")
        }
        if (iterations !in ITERATIONS_MIN..ITERATIONS_MAX) {
            throw SyncEncryptionException("Invalid file format: bad KDF iteration count")
        }
        if (parallelism !in PARALLELISM_MIN..PARALLELISM_MAX) {
            throw SyncEncryptionException("Invalid file format: bad KDF parallelism")
        }
        if (memoryKib < 8 * parallelism) {
            throw SyncEncryptionException("Invalid file format: KDF memory cost too low for parallelism")
        }

        return Argon2Params(
            memoryKib = memoryKib,
            iterations = iterations,
            parallelism = parallelism
        )
    }

    /** Write [value] as a big-endian int32 at [offset]. */
    private fun writeInt32(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value ushr 24 and 0xFF).toByte()
        buffer[offset + 1] = (value ushr 16 and 0xFF).toByte()
        buffer[offset + 2] = (value ushr 8 and 0xFF).toByte()
        buffer[offset + 3] = (value and 0xFF).toByte()
    }

    /** Read a big-endian int32 at [offset]. */
    private fun readInt32(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF shl 24) or
            (buffer[offset + 1].toInt() and 0xFF shl 16) or
            (buffer[offset + 2].toInt() and 0xFF shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
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

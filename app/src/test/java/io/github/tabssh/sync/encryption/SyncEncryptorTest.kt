package io.github.tabssh.sync.encryption

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

/**
 * SyncEncryptor round-trip and format tests.
 *
 * Pure JVM crypto (javax.crypto + BouncyCastle's lightweight Argon2id API) with
 * no Android Keystore dependency, so these run in the local
 * `testDebugUnitTest` gate. They lock in four guarantees:
 *   1. Files are written as V3 with an Argon2id KDF identifier and the exact
 *      cost parameters used, recorded in the header.
 *   2. Reads are driven by the header's stored cost, not a compiled-in
 *      constant, so raising the production defaults never strands a file.
 *   3. There is exactly one supported version and one supported KDF — anything
 *      else, including a dev-build PBKDF2 archive, is rejected outright rather
 *      than parsed best-effort.
 *   4. A wrong password fails with a typed exception, never with garbage
 *      plaintext.
 *
 * Production Argon2id cost is 64 MiB / t=3, which is intentionally ~1 second
 * per derivation. Tests that do not specifically exercise the defaults inject a
 * deliberately cheap cost so the suite stays fast — the production defaults are
 * never weakened for the sake of the test gate.
 */
class SyncEncryptorTest {

    // Cheap Argon2id cost for tests: still a real Argon2id derivation, just far
    // below the production work factor so the suite runs in milliseconds.
    private val cheapParams = SyncEncryptor.Argon2Params(
        memoryKib = 64,
        iterations = 1,
        parallelism = 1
    )

    private val encryptor = SyncEncryptor(cheapParams)

    @Test
    fun productionDefaults_areArgon2idTunedForMobile() {
        val defaults = SyncEncryptor.DEFAULT_KDF_PARAMS
        assertEquals(64 * 1024, defaults.memoryKib)
        assertEquals(3, defaults.iterations)
        assertEquals(1, defaults.parallelism)
    }

    @Test
    fun v3_roundTrip_recoversPlaintext() {
        val plaintext = "the quick brown fox — π ≈ 3.14159".toByteArray(Charsets.UTF_8)
        val password = "Correct-Horse-Battery-Staple-9"

        val blob = encryptor.encrypt(plaintext, password)

        // Written as the current V3 magic.
        assertEquals("TABSSH_SYNC_V3", String(blob, 0, 14, Charsets.ISO_8859_1))
        // Header records Argon2id (0x01) and the exact cost used.
        assertEquals(0x01.toByte(), blob[14])
        assertEquals(cheapParams.memoryKib, readInt32(blob, 15))
        assertEquals(cheapParams.iterations, readInt32(blob, 19))
        assertEquals(cheapParams.parallelism, blob[23].toInt() and 0xFF)
        // Bytes 24..31 are reserved and must be written as zero.
        for (i in 24 until 32) {
            assertEquals(0.toByte(), blob[i], "reserved header byte $i must be zero")
        }

        assertContentEquals(plaintext, encryptor.decrypt(blob, password))
    }

    @Test
    fun storedCost_drivesTheRead_notTheReadersDefault() {
        val plaintext = "header-driven KDF cost".toByteArray(Charsets.UTF_8)
        val password = "pw-for-header-cost-test"

        // Written at a cost that differs from the writer's own defaults...
        val writerParams = SyncEncryptor.Argon2Params(
            memoryKib = 128,
            iterations = 2,
            parallelism = 1
        )
        val blob = SyncEncryptor(writerParams).encrypt(plaintext, password)

        // ...and read back by an instance whose defaults are different again.
        // This only succeeds if the reader honours the header, never its own
        // compiled-in cost.
        val reader = SyncEncryptor(cheapParams)
        assertContentEquals(plaintext, reader.decrypt(blob, password))
    }

    @Test
    fun wrongPassword_throws() {
        val blob = encryptor.encrypt("secret".toByteArray(), "right-password")
        assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "wrong-password")
        }
    }

    @Test
    fun unsupportedVersion_isRejected() {
        val plaintext = "older-format payload".toByteArray(Charsets.UTF_8)
        val password = "old-backup-pw"

        // A well-formed file in a version this build does not support: correct
        // 13-byte prefix, wrong version digit. It must be refused, not
        // decrypted best-effort at a guessed cost.
        val rnd = SecureRandom()
        val salt = ByteArray(32).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = encryptor.deriveKey(password, salt, cheapParams)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val header = ByteArray(32)
        val magic = "TABSSH_SYNC_V2".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magic, 0, header, 0, magic.size)

        val blob = header + salt + iv + ciphertext
        assertFailsWith<SyncEncryptionException> { encryptor.decrypt(blob, password) }
    }

    @Test
    fun legacyPbkdf2Header_isRejected() {
        // The rolling dev build wrote a bare big-endian PBKDF2 iteration count
        // at offset 14. Under the current layout that byte is the KDF
        // identifier, and 600_000 puts 0x00 there — an unknown KDF. Such a file
        // must fail loudly on the KDF check; there is no PBKDF2 read path.
        val blob = encryptor.encrypt("payload".toByteArray(), "pw123456").copyOf()
        blob[14] = 0x00
        blob[15] = 0x09
        blob[16] = 0x27
        blob[17] = 0xC0.toByte()

        val error = assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "pw123456")
        }
        assertTrue(
            error.message?.contains("key derivation function") == true,
            "expected an unsupported-KDF error, got: ${error.message}"
        )
    }

    @Test
    fun absurdMemoryCost_isRejectedRatherThanAttempted() {
        // A corrupt header must not be allowed to ask for gigabytes of RAM.
        val blob = encryptor.encrypt("payload".toByteArray(), "pw123456").copyOf()
        blob[15] = 0x7F
        blob[16] = 0xFF.toByte()
        blob[17] = 0xFF.toByte()
        blob[18] = 0xFF.toByte()

        assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "pw123456")
        }
    }

    @Test
    fun zeroIterationCount_isRejected() {
        val blob = encryptor.encrypt("payload".toByteArray(), "pw123456").copyOf()
        blob[19] = 0
        blob[20] = 0
        blob[21] = 0
        blob[22] = 0

        assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "pw123456")
        }
    }

    @Test
    fun foreignMagic_isRejected() {
        val blob = ByteArray(128).also { SecureRandom().nextBytes(it) }
        "NOT_A_TABSSH_FILE".toByteArray(Charsets.ISO_8859_1)
            .copyInto(blob, 0, 0, 17)
        assertFailsWith<SyncEncryptionException> { encryptor.decrypt(blob, "pw123456") }
    }

    @Test
    fun tamperedCiphertext_throws() {
        val blob = encryptor.encrypt("integrity".toByteArray(), "pw123456").copyOf()
        // Flip a byte in the ciphertext region (past header+salt+iv = 76).
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "pw123456")
        }
    }

    @Test
    fun outOfRangeParams_areRejectedAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            SyncEncryptor.Argon2Params(memoryKib = 0, iterations = 3, parallelism = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SyncEncryptor.Argon2Params(memoryKib = 1024, iterations = 0, parallelism = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SyncEncryptor.Argon2Params(memoryKib = 1024, iterations = 3, parallelism = 0)
        }
    }

    @Test
    fun passwordStrength_enforcesLengthAndVariety() {
        assertTrue(encryptor.isPasswordStrong("Abcd1234!xyz"))
        assertTrue(!encryptor.isPasswordStrong("short1!"))
        assertTrue(!encryptor.isPasswordStrong("alllowercaseletters"))
    }

    private fun readInt32(buffer: ByteArray, offset: Int): Int {
        return (buffer[offset].toInt() and 0xFF shl 24) or
            (buffer[offset + 1].toInt() and 0xFF shl 16) or
            (buffer[offset + 2].toInt() and 0xFF shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)
    }
}

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
 * SyncEncryptor round-trip and format-compatibility tests.
 *
 * Pure JVM crypto (javax.crypto + PBKDF2) with no Android Keystore dependency,
 * so these run in the local `testDebugUnitTest` gate. They lock in two
 * guarantees of the self-describing header change:
 *   1. New files are written as V3 at the 600k write-time cost and round-trip.
 *   2. Legacy V2 files (fixed 100k) still decrypt unchanged — the header's
 *      embedded iteration count, not a compiled-in constant, drives the KDF.
 */
class SyncEncryptorTest {

    private val encryptor = SyncEncryptor()

    @Test
    fun v3_roundTrip_recoversPlaintext() {
        val plaintext = "the quick brown fox — π ≈ 3.14159".toByteArray(Charsets.UTF_8)
        val password = "Correct-Horse-Battery-Staple-9"

        val blob = encryptor.encrypt(plaintext, password)

        // Written as the current V3 magic.
        assertEquals("TABSSH_SYNC_V3", String(blob, 0, 14, Charsets.ISO_8859_1))
        // Header records the 600k write-time cost (big-endian int32 at offset 14).
        val storedIterations =
            (blob[14].toInt() and 0xFF shl 24) or
                (blob[15].toInt() and 0xFF shl 16) or
                (blob[16].toInt() and 0xFF shl 8) or
                (blob[17].toInt() and 0xFF)
        assertEquals(600_000, storedIterations)

        assertContentEquals(plaintext, encryptor.decrypt(blob, password))
    }

    @Test
    fun wrongPassword_throws() {
        val blob = encryptor.encrypt("secret".toByteArray(), "right-password")
        assertFailsWith<SyncEncryptionException> {
            encryptor.decrypt(blob, "wrong-password")
        }
    }

    @Test
    fun legacyV2_decryptsAtHundredKIterations() {
        val plaintext = "legacy backup payload".toByteArray(Charsets.UTF_8)
        val password = "old-backup-pw"

        // Reproduce a pre-change V2 file exactly: 100k-iteration key, a 14-byte
        // "TABSSH_SYNC_V2" magic with NO embedded iteration count, then
        // salt(32) + iv(12) + ciphertext. decrypt() must recover it by reading
        // the version as legacy and using 100k.
        val rnd = SecureRandom()
        val salt = ByteArray(32).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val key = encryptor.deriveKey(password, salt, 100_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val header = ByteArray(32)
        val magic = "TABSSH_SYNC_V2".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(magic, 0, header, 0, magic.size)

        val blob = header + salt + iv + ciphertext
        assertContentEquals(plaintext, encryptor.decrypt(blob, password))
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
    fun passwordStrength_enforcesLengthAndVariety() {
        assertTrue(encryptor.isPasswordStrong("Abcd1234!xyz"))
        assertTrue(!encryptor.isPasswordStrong("short1!"))
        assertTrue(!encryptor.isPasswordStrong("alllowercaseletters"))
    }
}

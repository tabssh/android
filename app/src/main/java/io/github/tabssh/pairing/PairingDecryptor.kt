package io.github.tabssh.pairing

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id KDF + AES-256-GCM decryption for QR pairing payloads.
 *
 * Parameters chosen to make brute-forcing the 6-digit code infeasible:
 *  - m = 64 MiB, t = 3, p = 1 → roughly 1 second per derivation on a mid-range
 *    Android phone. With a 60-second QR TTL, a 1 M-code brute force needs
 *    1.2 M+ concurrent cores — infeasible.
 *  - 128-bit AES-GCM authentication tag — standard.
 *  - 32-byte symmetric key (AES-256).
 *
 * BouncyCastle is already a TabSSH dependency (`bcprov-jdk18on:1.77`) — no new
 * crypto deps needed.
 */
object PairingDecryptor {

    private const val KEY_SIZE_BYTES = 32           // AES-256
    private const val GCM_TAG_BITS = 128
    private const val ARGON2_MEMORY_KIB = 64 * 1024 // 64 MiB
    private const val ARGON2_ITERATIONS = 3
    private const val ARGON2_PARALLELISM = 1

    /**
     * Derive the symmetric key from the user's 6-digit code + the salt.
     * Slow on purpose — typically ~1 second on a phone.
     */
    fun deriveKey(code: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(ARGON2_ITERATIONS)
            .withMemoryAsKB(ARGON2_MEMORY_KIB)
            .withParallelism(ARGON2_PARALLELISM)
            .withSalt(salt)
            .build()

        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(KEY_SIZE_BYTES)
        // generateBytes(char[], byte[]) — char-array variant gives BC the
        // password as Unicode code points, matching Rust's argon2 crate
        // behaviour when fed `code.as_bytes()` from a UTF-8 string of digits.
        gen.generateBytes(code.toCharArray(), out)
        return out
    }

    /**
     * Decrypt the envelope's ciphertext.
     *
     * Throws [AEADBadTagException] if the authentication tag is invalid —
     * this is the "wrong code" path. Callers should catch that and prompt
     * the user to retry (with a 3-attempt limit; see ImportFromQrActivity).
     */
    fun decrypt(envelope: QrEnvelope, key: ByteArray): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "key must be $KEY_SIZE_BYTES bytes" }
        require(envelope.nonce.size == 12) { "nonce must be 12 bytes (got ${envelope.nonce.size})" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val params = GCMParameterSpec(GCM_TAG_BITS, envelope.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, params)
        return cipher.doFinal(envelope.ciphertext)
    }

    /**
     * Full pipeline: take a scanned QR's text + the 6-digit code, return a
     * typed result. Catches every expected failure mode and reports it via
     * the [PairingResult] sum type.
     *
     * Run this on a background thread — Argon2id is intentionally slow.
     */
    fun decryptAndDecode(scannedText: String, code: String): PairingResult {
        // Stage 1: outer envelope
        val envelope = try {
            QrPayloadCodec.decodeEnvelope(scannedText)
        } catch (e: QrPayloadCodec.UnsupportedVersionException) {
            return PairingResult.Failure(FailureReason.UNSUPPORTED_VERSION, e.message ?: "unsupported envelope version")
        } catch (e: Exception) {
            return PairingResult.Failure(FailureReason.BAD_ENVELOPE, e.message ?: "couldn't read pairing data")
        }

        // Stage 2: KDF + AES-GCM decrypt
        val plaintext = try {
            val key = deriveKey(code, envelope.salt)
            decrypt(envelope, key)
        } catch (e: AEADBadTagException) {
            return PairingResult.Failure(FailureReason.WRONG_CODE, "wrong code")
        } catch (e: Exception) {
            return PairingResult.Failure(FailureReason.INTERNAL_ERROR, e.message ?: "decryption failed")
        }

        // Stage 3: inner payload
        val payload = try {
            QrPayloadCodec.decodePayload(plaintext)
        } catch (e: QrPayloadCodec.UnsupportedVersionException) {
            return PairingResult.Failure(
                FailureReason.UNSUPPORTED_PAYLOAD_VERSION,
                e.message ?: "unsupported payload version",
            )
        } catch (e: Exception) {
            return PairingResult.Failure(FailureReason.BAD_PAYLOAD, e.message ?: "couldn't read pairing payload")
        }

        // Stage 4: TTL check
        if (payload.expiresAt < System.currentTimeMillis() / 1000) {
            return PairingResult.Failure(FailureReason.EXPIRED, "QR has expired — generate a new one on desktop")
        }

        return PairingResult.Success(payload)
    }
}

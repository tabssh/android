package io.github.tabssh.crypto.algorithms

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic utilities for TabSSH
 * Provides high-level encryption and decryption operations
 */
object CryptoUtils {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16

    /**
     * Generate a new AES key
     */
    fun generateAESKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(256)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypt data using AES-GCM
     */
    fun encrypt(data: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)

        val encryptedData = cipher.doFinal(data)

        // Prepend IV to encrypted data
        return iv + encryptedData
    }

    /**
     * Decrypt data using AES-GCM
     */
    fun decrypt(encryptedData: ByteArray, key: SecretKey): ByteArray {
        val iv = encryptedData.sliceArray(0 until GCM_IV_LENGTH)
        val cipherText = encryptedData.sliceArray(GCM_IV_LENGTH until encryptedData.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)

        return cipher.doFinal(cipherText)
    }

    /**
     * Create SecretKey from byte array
     */
    fun keyFromBytes(keyBytes: ByteArray): SecretKey {
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Generate secure random bytes
     */
    fun generateRandomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}
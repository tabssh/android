package io.github.tabssh.crypto

import android.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import java.io.StringWriter
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SSH key generator supporting all major key types
 * Supports: RSA (2048, 3072, 4096), ECDSA (P-256, P-384, P-521), Ed25519
 */
object SSHKeyGenerator {

    data class GeneratedKeyPair(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val type: SSHKeyParser.KeyType,
        val privateKeyPEM: String,
        val publicKeyOpenSSH: String,
        val fingerprint: String,
        val comment: String = ""
    )

    enum class RSAKeySize(val bits: Int) {
        RSA_2048(2048),
        RSA_3072(3072),
        RSA_4096(4096)
    }

    enum class ECDSACurve(val curveName: String, val type: SSHKeyParser.KeyType) {
        NIST_P256("secp256r1", SSHKeyParser.KeyType.ECDSA_P256),
        NIST_P384("secp384r1", SSHKeyParser.KeyType.ECDSA_P384),
        NIST_P521("secp521r1", SSHKeyParser.KeyType.ECDSA_P521)
    }

    init {
        // Register BouncyCastle provider
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
    }

    /**
     * Generate RSA key pair
     */
    fun generateRSAKeyPair(
        keySize: RSAKeySize = RSAKeySize.RSA_2048,
        comment: String = "",
        passphrase: String? = null
    ): GeneratedKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGenerator.initialize(keySize.bits, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKeyPEM = exportPrivateKeyPEM(keyPair.private, passphrase)
        val publicKeyOpenSSH = exportPublicKeyOpenSSH(keyPair.public, comment)
        val fingerprint = generateFingerprint(keyPair.public)

        return GeneratedKeyPair(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            type = SSHKeyParser.KeyType.RSA,
            privateKeyPEM = privateKeyPEM,
            publicKeyOpenSSH = publicKeyOpenSSH,
            fingerprint = fingerprint,
            comment = comment
        )
    }

    /**
     * Generate ECDSA key pair
     */
    fun generateECDSAKeyPair(
        curve: ECDSACurve = ECDSACurve.NIST_P256,
        comment: String = "",
        passphrase: String? = null
    ): GeneratedKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC")
        val ecSpec = ECGenParameterSpec(curve.curveName)
        keyPairGenerator.initialize(ecSpec, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKeyPEM = exportPrivateKeyPEM(keyPair.private, passphrase)
        val publicKeyOpenSSH = exportPublicKeyOpenSSH(keyPair.public, comment)
        val fingerprint = generateFingerprint(keyPair.public)

        return GeneratedKeyPair(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            type = curve.type,
            privateKeyPEM = privateKeyPEM,
            publicKeyOpenSSH = publicKeyOpenSSH,
            fingerprint = fingerprint,
            comment = comment
        )
    }

    /**
     * Generate Ed25519 key pair
     */
    fun generateEd25519KeyPair(
        comment: String = "",
        passphrase: String? = null
    ): GeneratedKeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("Ed25519", "BC")
        keyPairGenerator.initialize(255, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val privateKeyPEM = exportPrivateKeyPEM(keyPair.private, passphrase)
        val publicKeyOpenSSH = exportPublicKeyOpenSSH(keyPair.public, comment)
        val fingerprint = generateFingerprint(keyPair.public)

        return GeneratedKeyPair(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            type = SSHKeyParser.KeyType.ED25519,
            privateKeyPEM = privateKeyPEM,
            publicKeyOpenSSH = publicKeyOpenSSH,
            fingerprint = fingerprint,
            comment = comment
        )
    }

    /**
     * Export private key in PEM format (PKCS#8)
     */
    private fun exportPrivateKeyPEM(privateKey: PrivateKey, passphrase: String?): String {
        val stringWriter = StringWriter()
        val pemWriter = JcaPEMWriter(stringWriter)

        try {
            if (passphrase != null && passphrase.isNotEmpty()) {
                // Encrypted PEM
                val encryptor = JcePEMEncryptorBuilder("AES-256-CBC")
                    .setProvider("BC")
                    .build(passphrase.toCharArray())
                pemWriter.writeObject(privateKey, encryptor)
            } else {
                // Unencrypted PEM
                pemWriter.writeObject(privateKey)
            }
            pemWriter.flush()
            return stringWriter.toString()
        } finally {
            pemWriter.close()
            stringWriter.close()
        }
    }

    /**
     * Export public key in OpenSSH format
     */
    private fun exportPublicKeyOpenSSH(publicKey: PublicKey, comment: String): String {
        val keyType = when (publicKey) {
            is RSAPublicKey -> "ssh-rsa"
            is ECPublicKey -> {
                when (publicKey.params.order.bitLength()) {
                    256 -> "ecdsa-sha2-nistp256"
                    384 -> "ecdsa-sha2-nistp384"
                    521 -> "ecdsa-sha2-nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
            }
            else -> {
                if (publicKey.algorithm == "Ed25519") "ssh-ed25519"
                else throw IllegalArgumentException("Unsupported key type: ${publicKey.algorithm}")
            }
        }

        val buffer = java.io.ByteArrayOutputStream()

        // Write key type
        val keyTypeBytes = keyType.toByteArray(StandardCharsets.UTF_8)
        buffer.write(ByteBuffer.allocate(4).putInt(keyTypeBytes.size).array())
        buffer.write(keyTypeBytes)

        // Write key-specific data
        when (publicKey) {
            is RSAPublicKey -> {
                writeMPInt(buffer, publicKey.publicExponent)
                writeMPInt(buffer, publicKey.modulus)
            }
            is ECPublicKey -> {
                val curveNameSSH = when (publicKey.params.order.bitLength()) {
                    256 -> "nistp256"
                    384 -> "nistp384"
                    521 -> "nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }

                // Write curve name
                val curveBytes = curveNameSSH.toByteArray(StandardCharsets.UTF_8)
                buffer.write(ByteBuffer.allocate(4).putInt(curveBytes.size).array())
                buffer.write(curveBytes)

                // Write EC point
                val point = publicKey.w
                val pointBytes = encodeECPoint(point, publicKey.params.order.bitLength())
                buffer.write(ByteBuffer.allocate(4).putInt(pointBytes.size).array())
                buffer.write(pointBytes)
            }
            else -> {
                // Ed25519
                if (publicKey.algorithm == "Ed25519") {
                    val encoded = publicKey.encoded
                    // Ed25519 public key is 32 bytes, extract from X.509 encoding
                    val keyBytes = encoded.takeLast(32).toByteArray()
                    buffer.write(ByteBuffer.allocate(4).putInt(keyBytes.size).array())
                    buffer.write(keyBytes)
                } else {
                    throw IllegalArgumentException("Unsupported key type: ${publicKey.algorithm}")
                }
            }
        }

        val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
        return "$keyType $encoded${if (comment.isNotEmpty()) " $comment" else ""}"
    }

    /**
     * Export key pair in OpenSSH private key format
     */
    fun exportOpenSSHPrivateKey(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        comment: String = "",
        passphrase: String? = null
    ): String {
        val buffer = java.io.ByteArrayOutputStream()

        // Magic bytes
        buffer.write("openssh-key-v1\u0000".toByteArray(StandardCharsets.UTF_8))

        // Cipher name
        val cipherName = if (passphrase != null && passphrase.isNotEmpty()) "aes256-cbc" else "none"
        writeString(buffer, cipherName.toByteArray(StandardCharsets.UTF_8))

        // KDF name
        val kdfName = if (passphrase != null && passphrase.isNotEmpty()) "bcrypt" else "none"
        writeString(buffer, kdfName.toByteArray(StandardCharsets.UTF_8))

        // KDF options (salt + rounds for bcrypt)
        if (passphrase != null && passphrase.isNotEmpty()) {
            val kdfOptions = java.io.ByteArrayOutputStream()
            val salt = ByteArray(16)
            SecureRandom().nextBytes(salt)
            writeString(kdfOptions, salt)
            kdfOptions.write(ByteBuffer.allocate(4).putInt(16).array()) // rounds
            writeString(buffer, kdfOptions.toByteArray())
        } else {
            writeString(buffer, ByteArray(0))
        }

        // Number of keys
        buffer.write(ByteBuffer.allocate(4).putInt(1).array())

        // Public key
        val publicKeyBytes = encodePublicKeyForOpenSSH(publicKey)
        writeString(buffer, publicKeyBytes)

        // Private key section
        val privateKeySection = encodePrivateKeySectionForOpenSSH(privateKey, publicKey, comment)
        val encryptedPrivateKey = if (passphrase != null && passphrase.isNotEmpty()) {
            encryptOpenSSHPrivateKey(privateKeySection, passphrase)
        } else {
            privateKeySection
        }
        writeString(buffer, encryptedPrivateKey)

        // Base64 encode the entire structure
        val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)

        // Format with line breaks (70 characters per line)
        val formattedKey = StringBuilder()
        formattedKey.append("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        encoded.chunked(70).forEach { line ->
            formattedKey.append(line).append("\n")
        }
        formattedKey.append("-----END OPENSSH PRIVATE KEY-----\n")

        return formattedKey.toString()
    }

    private fun encodePublicKeyForOpenSSH(publicKey: PublicKey): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()

        val keyType = when (publicKey) {
            is RSAPublicKey -> "ssh-rsa"
            is ECPublicKey -> {
                when (publicKey.params.order.bitLength()) {
                    256 -> "ecdsa-sha2-nistp256"
                    384 -> "ecdsa-sha2-nistp384"
                    521 -> "ecdsa-sha2-nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
            }
            else -> if (publicKey.algorithm == "Ed25519") "ssh-ed25519"
            else throw IllegalArgumentException("Unsupported key type")
        }

        writeString(buffer, keyType.toByteArray(StandardCharsets.UTF_8))

        when (publicKey) {
            is RSAPublicKey -> {
                writeMPInt(buffer, publicKey.publicExponent)
                writeMPInt(buffer, publicKey.modulus)
            }
            is ECPublicKey -> {
                val curveNameSSH = when (publicKey.params.order.bitLength()) {
                    256 -> "nistp256"
                    384 -> "nistp384"
                    521 -> "nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
                writeString(buffer, curveNameSSH.toByteArray(StandardCharsets.UTF_8))
                val pointBytes = encodeECPoint(publicKey.w, publicKey.params.order.bitLength())
                writeString(buffer, pointBytes)
            }
            else -> {
                // Ed25519
                val encoded = publicKey.encoded
                val keyBytes = encoded.takeLast(32).toByteArray()
                writeString(buffer, keyBytes)
            }
        }

        return buffer.toByteArray()
    }

    private fun encodePrivateKeySectionForOpenSSH(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        comment: String
    ): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()

        // Check bytes (random, should be identical)
        val checkBytes = SecureRandom().nextInt()
        buffer.write(ByteBuffer.allocate(4).putInt(checkBytes).array())
        buffer.write(ByteBuffer.allocate(4).putInt(checkBytes).array())

        // Key type
        val keyType = when (publicKey) {
            is RSAPublicKey -> "ssh-rsa"
            is ECPublicKey -> {
                when (publicKey.params.order.bitLength()) {
                    256 -> "ecdsa-sha2-nistp256"
                    384 -> "ecdsa-sha2-nistp384"
                    521 -> "ecdsa-sha2-nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
            }
            else -> if (publicKey.algorithm == "Ed25519") "ssh-ed25519"
            else throw IllegalArgumentException("Unsupported key type")
        }
        writeString(buffer, keyType.toByteArray(StandardCharsets.UTF_8))

        // Public key data (same as in public key section)
        when (publicKey) {
            is RSAPublicKey -> {
                writeMPInt(buffer, publicKey.publicExponent)
                writeMPInt(buffer, publicKey.modulus)
            }
            is ECPublicKey -> {
                val curveNameSSH = when (publicKey.params.order.bitLength()) {
                    256 -> "nistp256"
                    384 -> "nistp384"
                    521 -> "nistp521"
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
                writeString(buffer, curveNameSSH.toByteArray(StandardCharsets.UTF_8))
                val pointBytes = encodeECPoint(publicKey.w, publicKey.params.order.bitLength())
                writeString(buffer, pointBytes)
            }
            else -> {
                // Ed25519
                val encoded = publicKey.encoded
                val keyBytes = encoded.takeLast(32).toByteArray()
                writeString(buffer, keyBytes)
            }
        }

        // Private key data
        when (privateKey) {
            is java.security.interfaces.RSAPrivateCrtKey -> {
                writeMPInt(buffer, privateKey.privateExponent)
                writeMPInt(buffer, privateKey.crtCoefficient)
                writeMPInt(buffer, privateKey.primeP)
                writeMPInt(buffer, privateKey.primeQ)
            }
            // Add other key types as needed
        }

        // Comment
        writeString(buffer, comment.toByteArray(StandardCharsets.UTF_8))

        // Padding (1, 2, 3, 4, 5, 6, 7, ...)
        val paddingLength = 8 - (buffer.size() % 8)
        for (i in 1..paddingLength) {
            buffer.write(i)
        }

        return buffer.toByteArray()
    }

    private fun encryptOpenSSHPrivateKey(data: ByteArray, passphrase: String): ByteArray {
        // Simplified encryption - full implementation would use bcrypt KDF
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "BC")
        val keySpec = SecretKeySpec(passphrase.toByteArray().copyOf(32), "AES")
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))

        val encrypted = cipher.doFinal(data)
        val result = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(encrypted, 0, result, iv.size, encrypted.size)
        return result
    }

    private fun encodeECPoint(point: java.security.spec.ECPoint, bitLength: Int): ByteArray {
        val coordLength = (bitLength + 7) / 8
        val x = point.affineX.toByteArray()
        val y = point.affineY.toByteArray()

        val result = ByteArray(1 + 2 * coordLength)
        result[0] = 0x04 // Uncompressed point

        // Copy X coordinate
        System.arraycopy(
            x,
            Math.max(0, x.size - coordLength),
            result,
            1 + Math.max(0, coordLength - x.size),
            Math.min(x.size, coordLength)
        )

        // Copy Y coordinate
        System.arraycopy(
            y,
            Math.max(0, y.size - coordLength),
            result,
            1 + coordLength + Math.max(0, coordLength - y.size),
            Math.min(y.size, coordLength)
        )

        return result
    }

    private fun generateFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return "SHA256:" + Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun writeString(output: java.io.OutputStream, data: ByteArray) {
        output.write(ByteBuffer.allocate(4).putInt(data.size).array())
        output.write(data)
    }

    private fun writeMPInt(output: java.io.OutputStream, value: BigInteger) {
        val bytes = value.toByteArray()
        output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        output.write(bytes)
    }
}

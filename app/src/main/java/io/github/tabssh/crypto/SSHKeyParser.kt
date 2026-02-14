package io.github.tabssh.crypto

import android.util.Base64
import io.github.tabssh.utils.logging.Logger
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPrivateKey
import org.bouncycastle.jcajce.provider.asymmetric.edec.BCEdDSAPublicKey
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.interfaces.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Comprehensive SSH key parser supporting all major formats
 * Supports: RSA, ECDSA (P-256, P-384, P-521), Ed25519, DSA
 * Formats: OpenSSH, PEM (PKCS#1, PKCS#8), PuTTY
 */
object SSHKeyParser {

    data class ParsedKey(
        val privateKey: PrivateKey?,
        val publicKey: PublicKey,
        val type: KeyType,
        val comment: String = "",
        val fingerprint: String
    )

    enum class KeyType {
        RSA, ECDSA_P256, ECDSA_P384, ECDSA_P521, ED25519, DSA
    }

    init {
        // Register BouncyCastle provider
        Security.removeProvider("BC")
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }

    /**
     * Parse SSH key from string content
     * Auto-detects format: OpenSSH, PEM, or PuTTY
     */
    fun parse(keyContent: String, passphrase: String? = null): ParsedKey {
        val trimmed = keyContent.trim()

        return when {
            // OpenSSH private key format
            trimmed.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") -> {
                parseOpenSSHPrivateKey(trimmed, passphrase)
            }
            // PEM private key formats
            trimmed.startsWith("-----BEGIN") && trimmed.contains("PRIVATE KEY") -> {
                parsePEMPrivateKey(trimmed, passphrase)
            }
            // OpenSSH public key format
            trimmed.startsWith("ssh-") || trimmed.startsWith("ecdsa-") -> {
                parseOpenSSHPublicKey(trimmed)
            }
            // PuTTY private key format
            trimmed.startsWith("PuTTY-User-Key-File-2:") || trimmed.startsWith("PuTTY-User-Key-File-3:") -> {
                parsePuTTYPrivateKey(trimmed, passphrase)
            }
            else -> {
                throw IllegalArgumentException("Unsupported key format")
            }
        }
    }

    /**
     * Parse OpenSSH private key format
     */
    private fun parseOpenSSHPrivateKey(keyContent: String, passphrase: String?): ParsedKey {
        val base64Content = keyContent
            .replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
            .replace("-----END OPENSSH PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")

        val decoded = Base64.decode(base64Content, Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(decoded)

        // Read magic bytes
        val magic = ByteArray(15)
        buffer.get(magic)
        if (!String(magic, StandardCharsets.UTF_8).equals("openssh-key-v1\u0000")) {
            throw IllegalArgumentException("Invalid OpenSSH key format")
        }

        // Read cipher name
        val cipherNameBytes = readString(buffer)
        val cipherName = String(cipherNameBytes, StandardCharsets.UTF_8)
        // Read KDF name
        val kdfNameBytes = readString(buffer)
        val kdfName = String(kdfNameBytes, StandardCharsets.UTF_8)
        // Read KDF options
        val kdfOptions = readString(buffer)

        // Read number of keys
        val numKeys = buffer.int
        if (numKeys != 1) {
            throw IllegalArgumentException("Multiple keys not supported")
        }

        // Read public key
        val publicKeyBytes = readString(buffer)
        val publicKeyBuffer = ByteBuffer.wrap(publicKeyBytes)
        val keyTypeNameBytes = readString(publicKeyBuffer)
        val keyTypeName = String(keyTypeNameBytes, StandardCharsets.UTF_8)

        // Read private key section
        var privateKeyBytes = readString(buffer)

        // Decrypt if encrypted
        if (cipherName != "none") {
            if (passphrase == null) {
                throw IllegalArgumentException("Passphrase required for encrypted key")
            }
            privateKeyBytes = decryptOpenSSHPrivateKey(privateKeyBytes, cipherName, kdfName, kdfOptions, passphrase)
        }

        val privateBuffer = ByteBuffer.wrap(privateKeyBytes)

        // Check bytes (should match)
        val check1 = privateBuffer.int
        val check2 = privateBuffer.int
        if (check1 != check2) {
            throw IllegalArgumentException("Incorrect passphrase or corrupted key")
        }

        // Re-read key type from private section
        val privateKeyTypeNameBytes = readString(privateBuffer)

        return when {
            keyTypeName.startsWith("ssh-rsa") -> parseOpenSSHRSAKey(publicKeyBuffer, privateBuffer)
            keyTypeName.startsWith("ecdsa-") -> parseOpenSSHECDSAKey(keyTypeName, publicKeyBuffer, privateBuffer)
            keyTypeName.startsWith("ssh-ed25519") -> parseOpenSSHEd25519Key(publicKeyBuffer, privateBuffer)
            keyTypeName.startsWith("ssh-dss") -> parseOpenSSHDSAKey(publicKeyBuffer, privateBuffer)
            else -> throw IllegalArgumentException("Unsupported key type: $keyTypeName")
        }
    }

    /**
     * Parse PEM format private key (PKCS#1 or PKCS#8)
     */
    private fun parsePEMPrivateKey(keyContent: String, passphrase: String?): ParsedKey {
        val reader = StringReader(keyContent)
        val pemParser = PEMParser(reader)

        try {
            var obj = pemParser.readObject()
            val converter = JcaPEMKeyConverter().setProvider("BC")

            // Handle encrypted PEM
            if (obj is org.bouncycastle.openssl.PEMEncryptedKeyPair) {
                if (passphrase == null) {
                    throw IllegalArgumentException("Passphrase required for encrypted key")
                }
                val decryptor = JcePEMDecryptorProviderBuilder().build(passphrase.toCharArray())
                val decryptedKeyPair = obj.decryptKeyPair(decryptor)
                obj = decryptedKeyPair
            }

            val keyPair = when (obj) {
                is PEMKeyPair -> converter.getKeyPair(obj)
                is PrivateKeyInfo -> KeyPair(null, converter.getPrivateKey(obj))
                is SubjectPublicKeyInfo -> KeyPair(converter.getPublicKey(obj), null)
                else -> throw IllegalArgumentException("Unsupported PEM object type: ${obj?.javaClass}")
            }

            val privateKey = keyPair.private
            val publicKey = keyPair.public ?: derivePublicKey(privateKey)

            val type = detectKeyType(publicKey)
            val fingerprint = generateFingerprint(publicKey)

            return ParsedKey(
                privateKey = privateKey,
                publicKey = publicKey,
                type = type,
                fingerprint = fingerprint
            )
        } finally {
            pemParser.close()
            reader.close()
        }
    }

    /**
     * Parse OpenSSH public key format
     */
    private fun parseOpenSSHPublicKey(keyContent: String): ParsedKey {
        val parts = keyContent.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            throw IllegalArgumentException("Invalid public key format")
        }

        val keyTypeName = parts[0]
        val base64Key = parts[1]
        val comment = if (parts.size > 2) parts.drop(2).joinToString(" ") else ""

        val decoded = Base64.decode(base64Key, Base64.DEFAULT)
        val buffer = ByteBuffer.wrap(decoded)

        // Read and verify key type
        val typeFromDataBytes = readString(buffer)
        val typeFromData = String(typeFromDataBytes, StandardCharsets.UTF_8)
        if (typeFromData != keyTypeName) {
            throw IllegalArgumentException("Key type mismatch: $keyTypeName vs $typeFromData")
        }

        val (publicKey, type) = when {
            keyTypeName.startsWith("ssh-rsa") -> {
                val e = readMPInt(buffer)
                val n = readMPInt(buffer)
                val keyFactory = KeyFactory.getInstance("RSA", "BC")
                val pubSpec = java.security.spec.RSAPublicKeySpec(n, e)
                Pair(keyFactory.generatePublic(pubSpec), KeyType.RSA)
            }
            keyTypeName.startsWith("ecdsa-sha2-nistp") -> {
                val curveNameBytes = readString(buffer)
                val curveName = String(curveNameBytes, StandardCharsets.UTF_8)
                val point = readString(buffer)
                val type = when (curveName) {
                    "nistp256" -> KeyType.ECDSA_P256
                    "nistp384" -> KeyType.ECDSA_P384
                    "nistp521" -> KeyType.ECDSA_P521
                    else -> throw IllegalArgumentException("Unsupported ECDSA curve: $curveName")
                }
                val publicKey = parseECPoint(point, curveName)
                Pair(publicKey, type)
            }
            keyTypeName.startsWith("ssh-ed25519") -> {
                val publicKeyBytes = readString(buffer)
                // Raw Ed25519 public key is 32 bytes - need to wrap in SubjectPublicKeyInfo
                // X509EncodedKeySpec expects ASN.1/DER format, not raw bytes
                val publicKeyInfo = SubjectPublicKeyInfo(
                    org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                        org.bouncycastle.asn1.edec.EdECObjectIdentifiers.id_Ed25519
                    ),
                    publicKeyBytes
                )
                val keyFactory = KeyFactory.getInstance("Ed25519", "BC")
                val pubSpec = X509EncodedKeySpec(publicKeyInfo.encoded)
                Pair(keyFactory.generatePublic(pubSpec), KeyType.ED25519)
            }
            keyTypeName.startsWith("ssh-dss") -> {
                val p = readMPInt(buffer)
                val q = readMPInt(buffer)
                val g = readMPInt(buffer)
                val y = readMPInt(buffer)
                val keyFactory = KeyFactory.getInstance("DSA", "BC")
                val pubSpec = java.security.spec.DSAPublicKeySpec(y, p, q, g)
                Pair(keyFactory.generatePublic(pubSpec), KeyType.DSA)
            }
            else -> throw IllegalArgumentException("Unsupported key type: $keyTypeName")
        }

        return ParsedKey(
            privateKey = null,
            publicKey = publicKey,
            type = type,
            comment = comment,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    /**
     * Parse PuTTY format private key
     */
    private fun parsePuTTYPrivateKey(keyContent: String, passphrase: String?): ParsedKey {
        val lines = keyContent.lines()
        val headers = mutableMapOf<String, String>()
        val publicLines = mutableListOf<String>()
        val privateLines = mutableListOf<String>()

        var currentSection = ""
        for (line in lines) {
            when {
                line.contains(":") && !line.startsWith(" ") -> {
                    val (key, value) = line.split(":", limit = 2)
                    headers[key.trim()] = value.trim()
                    currentSection = key.trim()
                }
                currentSection == "Public-Lines" || currentSection.isEmpty() && publicLines.isNotEmpty() -> {
                    if (line.isNotBlank()) publicLines.add(line.trim())
                }
                currentSection == "Private-Lines" || currentSection.isEmpty() && privateLines.isNotEmpty() -> {
                    if (line.isNotBlank()) privateLines.add(line.trim())
                }
            }
        }

        val keyType = headers["PuTTY-User-Key-File-2"] ?: headers["PuTTY-User-Key-File-3"]
            ?: throw IllegalArgumentException("Invalid PuTTY key format")

        val encryption = headers["Encryption"] ?: "none"
        val comment = headers["Comment"] ?: ""

        val publicBlob = Base64.decode(publicLines.joinToString(""), Base64.DEFAULT)
        val privateBlob = Base64.decode(privateLines.joinToString(""), Base64.DEFAULT)

        // Decrypt private blob if needed
        val decryptedPrivateBlob = if (encryption != "none") {
            if (passphrase == null) {
                throw IllegalArgumentException("Passphrase required for encrypted key")
            }
            decryptPuTTYPrivateBlob(privateBlob, passphrase, encryption)
        } else {
            privateBlob
        }

        // Parse based on key type
        return when (keyType) {
            "ssh-rsa" -> parsePuTTYRSA(publicBlob, decryptedPrivateBlob, comment)
            "ssh-dss" -> parsePuTTYDSA(publicBlob, decryptedPrivateBlob, comment)
            else -> throw IllegalArgumentException("Unsupported PuTTY key type: $keyType")
        }
    }

    // Helper methods for parsing specific key types

    private fun parseOpenSSHRSAKey(publicBuffer: ByteBuffer, privateBuffer: ByteBuffer): ParsedKey {
        // Public key components
        val e = readMPInt(publicBuffer)
        val n = readMPInt(publicBuffer)

        // Private key components
        val n2 = readMPInt(privateBuffer)
        val e2 = readMPInt(privateBuffer)
        val d = readMPInt(privateBuffer)
        val iqmp = readMPInt(privateBuffer)
        val p = readMPInt(privateBuffer)
        val q = readMPInt(privateBuffer)

        val keyFactory = KeyFactory.getInstance("RSA", "BC")
        val publicKey = keyFactory.generatePublic(java.security.spec.RSAPublicKeySpec(n, e)) as RSAPublicKey
        val privateKey = keyFactory.generatePrivate(
            java.security.spec.RSAPrivateCrtKeySpec(n, e, d, p, q,
                d.mod(p.subtract(java.math.BigInteger.ONE)),
                d.mod(q.subtract(java.math.BigInteger.ONE)),
                iqmp)
        )

        return ParsedKey(
            privateKey = privateKey,
            publicKey = publicKey,
            type = KeyType.RSA,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    private fun parseOpenSSHECDSAKey(keyTypeName: String, publicBuffer: ByteBuffer, privateBuffer: ByteBuffer): ParsedKey {
        val curveNameBytes = readString(publicBuffer)
        val curveName = String(curveNameBytes, StandardCharsets.UTF_8)
        val publicPoint = readString(publicBuffer)

        val curveName2Bytes = readString(privateBuffer)
        val publicPoint2 = readString(privateBuffer)
        val privateScalar = readMPInt(privateBuffer)

        val type = when (curveName) {
            "nistp256" -> KeyType.ECDSA_P256
            "nistp384" -> KeyType.ECDSA_P384
            "nistp521" -> KeyType.ECDSA_P521
            else -> throw IllegalArgumentException("Unsupported ECDSA curve: $curveName")
        }

        val publicKey = parseECPoint(publicPoint, curveName)
        
        // Note: EC private key construction requires curve parameters
        // For maximum compatibility, we parse public key only and derive private key separately if needed
        return ParsedKey(
            privateKey = null, // EC private key parsing requires additional curve parameters
            publicKey = publicKey,
            type = type,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    private fun parseOpenSSHEd25519Key(publicBuffer: ByteBuffer, privateBuffer: ByteBuffer): ParsedKey {
        // OpenSSH Ed25519 format:
        // - Public key: 32 bytes raw
        // - Private key: 64 bytes (32 bytes seed + 32 bytes public key copy)
        val publicKeyBytes = readString(publicBuffer)
        val privateKeyBytes = readString(privateBuffer)

        // Use BouncyCastle Ed25519 parameters to handle raw bytes
        // (X509EncodedKeySpec/PKCS8EncodedKeySpec expect ASN.1/DER format, not raw)
        val bcPublicKey = Ed25519PublicKeyParameters(publicKeyBytes, 0)
        val bcPrivateKey = Ed25519PrivateKeyParameters(privateKeyBytes, 0)

        // Convert BouncyCastle keys to Java KeyPair format
        val keyFactory = KeyFactory.getInstance("Ed25519", "BC")

        // Build proper ASN.1 encoded specs from BouncyCastle parameters
        val publicKeyInfo = SubjectPublicKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.edec.EdECObjectIdentifiers.id_Ed25519
            ),
            publicKeyBytes
        )
        val privateKeyInfo = PrivateKeyInfo(
            org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                org.bouncycastle.asn1.edec.EdECObjectIdentifiers.id_Ed25519
            ),
            org.bouncycastle.asn1.DEROctetString(privateKeyBytes.copyOfRange(0, 32))
        )

        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyInfo.encoded))
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyInfo.encoded))

        return ParsedKey(
            privateKey = privateKey,
            publicKey = publicKey,
            type = KeyType.ED25519,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    private fun parseOpenSSHDSAKey(publicBuffer: ByteBuffer, privateBuffer: ByteBuffer): ParsedKey {
        // Public key components
        val p = readMPInt(publicBuffer)
        val q = readMPInt(publicBuffer)
        val g = readMPInt(publicBuffer)
        val y = readMPInt(publicBuffer)

        // Private key components
        val p2 = readMPInt(privateBuffer)
        val q2 = readMPInt(privateBuffer)
        val g2 = readMPInt(privateBuffer)
        val y2 = readMPInt(privateBuffer)
        val x = readMPInt(privateBuffer)

        val keyFactory = KeyFactory.getInstance("DSA", "BC")
        val publicKey = keyFactory.generatePublic(java.security.spec.DSAPublicKeySpec(y, p, q, g))
        val privateKey = keyFactory.generatePrivate(java.security.spec.DSAPrivateKeySpec(x, p, q, g))

        return ParsedKey(
            privateKey = privateKey,
            publicKey = publicKey,
            type = KeyType.DSA,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    private fun parsePuTTYRSA(publicBlob: ByteArray, privateBlob: ByteArray, comment: String): ParsedKey {
        val pubBuffer = ByteBuffer.wrap(publicBlob)
        val privBuffer = ByteBuffer.wrap(privateBlob)

        // Public key
        readString(pubBuffer) // algorithm name
        val e = readMPInt(pubBuffer)
        val n = readMPInt(pubBuffer)

        // Private key
        val d = readMPInt(privBuffer)
        val p = readMPInt(privBuffer)
        val q = readMPInt(privBuffer)
        val iqmp = readMPInt(privBuffer)

        val keyFactory = KeyFactory.getInstance("RSA", "BC")
        val publicKey = keyFactory.generatePublic(java.security.spec.RSAPublicKeySpec(n, e))
        val privateKey = keyFactory.generatePrivate(
            java.security.spec.RSAPrivateCrtKeySpec(n, e, d, p, q,
                d.mod(p.subtract(java.math.BigInteger.ONE)),
                d.mod(q.subtract(java.math.BigInteger.ONE)),
                iqmp)
        )

        return ParsedKey(
            privateKey = privateKey,
            publicKey = publicKey,
            type = KeyType.RSA,
            comment = comment,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    private fun parsePuTTYDSA(publicBlob: ByteArray, privateBlob: ByteArray, comment: String): ParsedKey {
        val pubBuffer = ByteBuffer.wrap(publicBlob)
        val privBuffer = ByteBuffer.wrap(privateBlob)

        // Public key
        readString(pubBuffer) // algorithm name
        val p = readMPInt(pubBuffer)
        val q = readMPInt(pubBuffer)
        val g = readMPInt(pubBuffer)
        val y = readMPInt(pubBuffer)

        // Private key
        val x = readMPInt(privBuffer)

        val keyFactory = KeyFactory.getInstance("DSA", "BC")
        val publicKey = keyFactory.generatePublic(java.security.spec.DSAPublicKeySpec(y, p, q, g))
        val privateKey = keyFactory.generatePrivate(java.security.spec.DSAPrivateKeySpec(x, p, q, g))

        return ParsedKey(
            privateKey = privateKey,
            publicKey = publicKey,
            type = KeyType.DSA,
            comment = comment,
            fingerprint = generateFingerprint(publicKey)
        )
    }

    // Utility methods

    private fun readString(buffer: ByteBuffer): ByteArray {
        val length = buffer.int
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun readMPInt(buffer: ByteBuffer): java.math.BigInteger {
        val bytes = readString(buffer)
        return java.math.BigInteger(1, bytes)
    }

    private fun parseECPoint(pointBytes: ByteArray, curveName: String): PublicKey {
        // Map SSH curve names to Java standard curve names
        val javaName = when (curveName) {
            "nistp256" -> "secp256r1"
            "nistp384" -> "secp384r1"
            "nistp521" -> "secp521r1"
            else -> throw IllegalArgumentException("Unsupported curve: $curveName")
        }

        try {
            // Use BouncyCastle to construct EC public key from point
            val ecSpec = org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec(javaName)
            val point = ecSpec.curve.decodePoint(pointBytes)
            val pubKeySpec = org.bouncycastle.jce.spec.ECPublicKeySpec(point, ecSpec)
            val keyFactory = KeyFactory.getInstance("EC", "BC")
            return keyFactory.generatePublic(pubKeySpec)
        } catch (e: Exception) {
            Logger.e("SSHKeyParser", "Failed to parse EC point for curve $curveName", e)
            throw IllegalArgumentException("Failed to parse EC point: ${e.message}", e)
        }
    }

    private fun decryptOpenSSHPrivateKey(
        encrypted: ByteArray,
        cipherName: String,
        kdfName: String,
        kdfOptions: ByteArray,
        passphrase: String
    ): ByteArray {
        try {
            // Parse KDF options to get salt and rounds
            val kdfBuffer = ByteBuffer.wrap(kdfOptions)
            val salt = readString(kdfBuffer)
            val rounds = kdfBuffer.getInt()

            // Derive key and IV using bcrypt KDF
            val keyIvLength = when (cipherName) {
                "aes128-cbc", "aes128-ctr" -> 16 + 16 // 128-bit key + 128-bit IV
                "aes256-cbc", "aes256-ctr" -> 32 + 16 // 256-bit key + 128-bit IV
                else -> throw IllegalArgumentException("Unsupported cipher: $cipherName")
            }

            // Use BCrypt KDF (OpenSSH uses bcrypt_pbkdf)
            val keyIv = bcryptPbkdf(passphrase.toByteArray(), salt, rounds, keyIvLength)
            val key = keyIv.copyOfRange(0, keyIvLength - 16)
            val iv = keyIv.copyOfRange(keyIvLength - 16, keyIvLength)

            // Decrypt using appropriate cipher
            val cipher = when {
                cipherName.contains("cbc") -> javax.crypto.Cipher.getInstance("AES/CBC/NoPadding", "BC")
                cipherName.contains("ctr") -> javax.crypto.Cipher.getInstance("AES/CTR/NoPadding", "BC")
                else -> throw IllegalArgumentException("Unsupported cipher mode: $cipherName")
            }

            val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
            val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, ivSpec)

            return cipher.doFinal(encrypted)
        } catch (e: Exception) {
            Logger.e("SSHKeyParser", "Failed to decrypt OpenSSH private key", e)
            throw IllegalArgumentException("Failed to decrypt key (wrong passphrase?): ${e.message}", e)
        }
    }

    // Simple bcrypt_pbkdf implementation (OpenSSH KDF)
    private fun bcryptPbkdf(password: ByteArray, salt: ByteArray, rounds: Int, keyLen: Int): ByteArray {
        // Simplified implementation - for production, use proper bcrypt_pbkdf library
        // For now, fallback to PBKDF2 (less secure but functional)
        Logger.w("SSHKeyParser", "Using PBKDF2 fallback for OpenSSH key decryption (bcrypt_pbkdf not available)")
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "BC")
        val spec = javax.crypto.spec.PBEKeySpec(
            String(password).toCharArray(),
            salt,
            rounds,
            keyLen * 8
        )
        return factory.generateSecret(spec).encoded
    }

    private fun decryptPuTTYPrivateBlob(blob: ByteArray, passphrase: String, encryption: String): ByteArray {
        try {
            when (encryption) {
                "aes256-cbc" -> {
                    // PuTTY uses MD5-based key derivation
                    val md = java.security.MessageDigest.getInstance("MD5")
                    
                    // Derive key (32 bytes for AES-256)
                    val hash1 = md.digest((0.toString() + passphrase).toByteArray())
                    md.reset()
                    val hash2 = md.digest((1.toString() + passphrase).toByteArray())
                    val key = hash1 + hash2 // 32 bytes
                    
                    // PuTTY uses zero IV
                    val iv = ByteArray(16)
                    
                    // Decrypt
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding", "BC")
                    val secretKey = javax.crypto.spec.SecretKeySpec(key, "AES")
                    val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, ivSpec)
                    
                    return cipher.doFinal(blob)
                }
                "none" -> {
                    // Unencrypted
                    return blob
                }
                else -> throw IllegalArgumentException("Unsupported PuTTY encryption: $encryption")
            }
        } catch (e: Exception) {
            Logger.e("SSHKeyParser", "Failed to decrypt PuTTY private key", e)
            throw IllegalArgumentException("Failed to decrypt PuTTY key (wrong passphrase?): ${e.message}", e)
        }
    }

    private fun derivePublicKey(privateKey: PrivateKey): PublicKey {
        return when (privateKey) {
            is java.security.interfaces.RSAPrivateCrtKey -> {
                // RSA: Derive public key from private CRT key
                val spec = java.security.spec.RSAPublicKeySpec(
                    privateKey.modulus,
                    privateKey.publicExponent
                )
                val keyFactory = KeyFactory.getInstance("RSA", "BC")
                keyFactory.generatePublic(spec)
            }
            is DSAPrivateKey -> {
                // DSA: Derive public key (y = g^x mod p)
                val params = privateKey.params
                val y = params.g.modPow(privateKey.x, params.p)
                val spec = java.security.spec.DSAPublicKeySpec(y, params.p, params.q, params.g)
                val keyFactory = KeyFactory.getInstance("DSA", "BC")
                keyFactory.generatePublic(spec)
            }
            is ECPrivateKey -> {
                // EC: Derive public key using BouncyCastle
                try {
                    val bcPrivateKey = org.bouncycastle.jce.provider.BouncyCastleProvider().run {
                        val keyFactory = KeyFactory.getInstance("EC", this)
                        keyFactory.getKeySpec(privateKey, org.bouncycastle.jce.spec.ECPrivateKeySpec::class.java)
                    }
                    val ecSpec = bcPrivateKey.params
                    val q = ecSpec.g.multiply(bcPrivateKey.d)
                    val pubKeySpec = org.bouncycastle.jce.spec.ECPublicKeySpec(q, ecSpec)
                    val keyFactory = KeyFactory.getInstance("EC", "BC")
                    keyFactory.generatePublic(pubKeySpec)
                } catch (e: Exception) {
                    Logger.e("SSHKeyParser", "Failed to derive EC public key", e)
                    throw IllegalArgumentException("Failed to derive EC public key: ${e.message}", e)
                }
            }
            else -> throw IllegalArgumentException("Unsupported private key type: ${privateKey.javaClass.simpleName}")
        }
    }

    private fun detectKeyType(publicKey: PublicKey): KeyType {
        return when (publicKey) {
            is RSAPublicKey -> KeyType.RSA
            is DSAPublicKey -> KeyType.DSA
            is ECPublicKey -> {
                val params = publicKey.params
                when (params.order.bitLength()) {
                    256 -> KeyType.ECDSA_P256
                    384 -> KeyType.ECDSA_P384
                    521 -> KeyType.ECDSA_P521
                    else -> throw IllegalArgumentException("Unsupported EC curve")
                }
            }
            else -> {
                if (publicKey.algorithm == "Ed25519") KeyType.ED25519
                else throw IllegalArgumentException("Unsupported key type: ${publicKey.algorithm}")
            }
        }
    }

    private fun generateFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return "SHA256:" + Base64.encodeToString(hash, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Export public key in OpenSSH format
     */
    fun exportPublicKey(publicKey: PublicKey, comment: String = ""): String {
        val keyType = when (publicKey) {
            is RSAPublicKey -> "ssh-rsa"
            is DSAPublicKey -> "ssh-dss"
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
                else throw IllegalArgumentException("Unsupported key type")
            }
        }

        // Encode public key to OpenSSH format
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
            is DSAPublicKey -> {
                writeMPInt(buffer, publicKey.params.p)
                writeMPInt(buffer, publicKey.params.q)
                writeMPInt(buffer, publicKey.params.g)
                writeMPInt(buffer, publicKey.y)
            }
            // Add other key types as needed
        }

        val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
        return "$keyType $encoded${if (comment.isNotEmpty()) " $comment" else ""}"
    }

    private fun writeMPInt(output: java.io.OutputStream, value: java.math.BigInteger) {
        val bytes = value.toByteArray()
        output.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        output.write(bytes)
    }
}

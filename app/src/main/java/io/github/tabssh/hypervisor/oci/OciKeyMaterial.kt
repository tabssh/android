package io.github.tabssh.hypervisor.oci

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.StringReader
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.security.KeyFactory

/**
 * Holds an unlocked OCI API-key keypair plus its OCI-style fingerprint.
 *
 * OCI displays public keys by fingerprint = MD5(SubjectPublicKeyInfo DER),
 * formatted as colon-separated lowercase hex pairs (e.g.
 * `f1:c0:fe:...`). We round-trip imported PEMs through this fingerprint
 * to give the user "this is the right key" certainty during onboarding.
 *
 * Networking-free, Android-API-free — pure JCA + BouncyCastle so it can
 * be exercised from a unit test if we ever add one.
 */
class OciKeyMaterial private constructor(
    val keyPair: KeyPair,
    val fingerprint: String
) {
    val privateKey: RSAPrivateKey get() = keyPair.private as RSAPrivateKey
    val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey

    companion object {
        init {
            // BouncyCastle is already on the classpath via the SSH key parser.
            // Add the provider lazily — multiple addProvider() calls are no-ops.
            if (java.security.Security.getProvider("BC") == null) {
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
        }

        /**
         * Parse a PEM-encoded RSA private key (PKCS#1 or PKCS#8, encrypted
         * or unencrypted). Returns the loaded keypair plus its OCI
         * fingerprint. `passphrase` may be null/empty when the key is
         * unencrypted; pass the user-entered passphrase for encrypted PEMs.
         *
         * Throws on parse error, wrong passphrase, or non-RSA keys.
         */
        fun fromPem(pem: String, passphrase: CharArray? = null): OciKeyMaterial {
            val parser = PEMParser(StringReader(pem))
            val obj = parser.readObject()
                ?: throw IllegalArgumentException("Not a PEM file (no objects parsed)")
            val converter = JcaPEMKeyConverter().setProvider("BC")

            val keyPair: KeyPair = when (obj) {
                is PEMEncryptedKeyPair -> {
                    val pp = passphrase ?: throw IllegalArgumentException(
                        "Key is passphrase-protected"
                    )
                    val decryptor = JcePEMDecryptorProviderBuilder()
                        .setProvider("BC").build(pp)
                    converter.getKeyPair(obj.decryptKeyPair(decryptor))
                }
                is PEMKeyPair -> converter.getKeyPair(obj)
                is PKCS8EncryptedPrivateKeyInfo -> {
                    val pp = passphrase ?: throw IllegalArgumentException(
                        "Key is passphrase-protected"
                    )
                    val decryptor = JceOpenSSLPKCS8DecryptorProviderBuilder()
                        .setProvider("BC").build(pp)
                    val info = obj.decryptPrivateKeyInfo(decryptor)
                    privateKeyInfoToKeyPair(info, converter)
                }
                is PrivateKeyInfo -> privateKeyInfoToKeyPair(obj, converter)
                else -> throw IllegalArgumentException(
                    "Unsupported PEM object: ${obj::class.java.simpleName}"
                )
            }

            require(keyPair.private is RSAPrivateKey && keyPair.public is RSAPublicKey) {
                "OCI requires an RSA keypair, got ${keyPair.private::class.java.simpleName}"
            }

            val fingerprint = computeFingerprint(keyPair.public as RSAPublicKey)
            return OciKeyMaterial(keyPair, fingerprint)
        }

        private fun privateKeyInfoToKeyPair(
            info: PrivateKeyInfo,
            converter: JcaPEMKeyConverter
        ): KeyPair {
            val priv = converter.getPrivateKey(info)
            require(priv is RSAPrivateKey) {
                "OCI requires RSA, got ${priv::class.java.simpleName}"
            }
            // Derive the matching public key from the modulus + public exponent
            // we'd otherwise lose: PKCS#8 unencrypted PrivateKeyInfo carries them
            // for RSA via the inner RSAPrivateKey ASN.1 sequence. We use the
            // CRT private-key fields if present, else fall back to the standard
            // RSAPrivateKey accessors (which all RSAPrivateKey impls expose).
            val pubExp = (priv as? java.security.interfaces.RSAPrivateCrtKey)?.publicExponent
                ?: java.math.BigInteger.valueOf(65537L)
            val publicSpec = RSAPublicKeySpec(priv.modulus, pubExp)
            val pub = KeyFactory.getInstance("RSA").generatePublic(publicSpec) as RSAPublicKey
            return KeyPair(pub, priv)
        }

        /**
         * Compute the OCI public-key fingerprint: MD5 of the X.509
         * SubjectPublicKeyInfo DER, displayed as colon-separated lowercase
         * hex pairs. Matches the format the OCI Console shows alongside an
         * uploaded API key.
         */
        fun computeFingerprint(publicKey: RSAPublicKey): String {
            // RSAPublicKey.encoded is the SubjectPublicKeyInfo DER.
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(publicKey.encoded)
            return digest.joinToString(":") { "%02x".format(it) }
        }
    }
}

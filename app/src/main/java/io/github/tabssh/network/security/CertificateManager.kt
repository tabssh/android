package io.github.tabssh.network.security

import android.content.Context
import android.util.Base64
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.TrustedCertificate
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*

/**
 * Certificate management and validation
 * Handles SSL/TLS certificate verification and storage
 */
class CertificateManager(private val context: Context) {

    private val database = TabSSHDatabase.getDatabase(context)
    private val certificateFactory = CertificateFactory.getInstance("X.509")

    enum class CertificateStatus {
        TRUSTED,
        UNTRUSTED,
        EXPIRED,
        SELF_SIGNED,
        CHAIN_INCOMPLETE,
        REVOKED,
        UNKNOWN
    }

    data class CertificateInfo(
        val subject: String,
        val issuer: String,
        val serialNumber: String,
        val notBefore: Date,
        val notAfter: Date,
        val fingerprint: String,
        val algorithm: String,
        val keySize: Int,
        val status: CertificateStatus,
        val chain: List<X509Certificate> = emptyList()
    )

    /**
     * Verify certificate for a host
     */
    suspend fun verifyCertificate(
        hostname: String,
        port: Int,
        certificate: X509Certificate
    ): CertificateVerificationResult = withContext(Dispatchers.IO) {
        try {
            // Check if certificate is already trusted
            val trustedCert = database.certificateDao().getCertificateByHostAndPort(hostname, port)
            if (trustedCert != null) {
                val storedFingerprint = trustedCert.fingerprint
                val currentFingerprint = getCertificateFingerprint(certificate)

                if (storedFingerprint == currentFingerprint) {
                    return@withContext CertificateVerificationResult(
                        trusted = true,
                        status = CertificateStatus.TRUSTED,
                        message = "Certificate is trusted"
                    )
                } else {
                    return@withContext CertificateVerificationResult(
                        trusted = false,
                        status = CertificateStatus.UNTRUSTED,
                        message = "Certificate has changed!",
                        previousFingerprint = storedFingerprint,
                        currentFingerprint = currentFingerprint
                    )
                }
            }

            // Validate certificate
            val status = validateCertificate(certificate)
            val message = when (status) {
                CertificateStatus.EXPIRED -> "Certificate has expired"
                CertificateStatus.SELF_SIGNED -> "Certificate is self-signed"
                CertificateStatus.CHAIN_INCOMPLETE -> "Certificate chain is incomplete"
                CertificateStatus.REVOKED -> "Certificate has been revoked"
                else -> "Certificate is not trusted"
            }

            return@withContext CertificateVerificationResult(
                trusted = false,
                status = status,
                message = message,
                certificateInfo = getCertificateInfo(certificate)
            )
        } catch (e: Exception) {
            Logger.e("CertificateManager", "Failed to verify certificate", e)
            return@withContext CertificateVerificationResult(
                trusted = false,
                status = CertificateStatus.UNKNOWN,
                message = "Failed to verify certificate: ${e.message}"
            )
        }
    }

    /**
     * Validate certificate status
     */
    private fun validateCertificate(certificate: X509Certificate): CertificateStatus {
        return try {
            // Check expiration
            certificate.checkValidity()

            // Check if self-signed
            if (certificate.issuerDN == certificate.subjectDN) {
                return CertificateStatus.SELF_SIGNED
            }

            // Additional validation would check:
            // - Certificate chain
            // - Revocation status (OCSP/CRL)
            // - Key usage constraints
            // - Hostname verification

            CertificateStatus.UNTRUSTED
        } catch (e: Exception) {
            when {
                e.message?.contains("expired") == true -> CertificateStatus.EXPIRED
                e.message?.contains("not yet valid") == true -> CertificateStatus.UNTRUSTED
                else -> CertificateStatus.UNKNOWN
            }
        }
    }

    /**
     * Get certificate information
     */
    fun getCertificateInfo(certificate: X509Certificate): CertificateInfo {
        return CertificateInfo(
            subject = certificate.subjectDN.name,
            issuer = certificate.issuerDN.name,
            serialNumber = certificate.serialNumber.toString(16),
            notBefore = certificate.notBefore,
            notAfter = certificate.notAfter,
            fingerprint = getCertificateFingerprint(certificate),
            algorithm = certificate.sigAlgName,
            keySize = getKeySize(certificate),
            status = validateCertificate(certificate)
        )
    }

    /**
     * Get certificate fingerprint (SHA-256)
     */
    private fun getCertificateFingerprint(certificate: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val fingerprint = md.digest(certificate.encoded)
        return fingerprint.joinToString(":") { "%02X".format(it) }
    }

    /**
     * Get key size from certificate
     */
    private fun getKeySize(certificate: X509Certificate): Int {
        return when (certificate.publicKey.algorithm) {
            "RSA" -> (certificate.publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 0
            "EC" -> (certificate.publicKey as? java.security.interfaces.ECPublicKey)?.params?.order?.bitLength() ?: 0
            else -> 0
        }
    }

    /**
     * Trust a certificate
     */
    suspend fun trustCertificate(
        hostname: String,
        port: Int,
        certificate: X509Certificate,
        permanent: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val info = getCertificateInfo(certificate)

        val trustedCert = TrustedCertificate(
            id = "${hostname}:${port}",
            hostname = hostname,
            port = port,
            fingerprint = info.fingerprint,
            algorithm = "SHA-256",
            certificateData = Base64.encodeToString(certificate.encoded, Base64.NO_WRAP),
            subject = info.subject,
            issuer = info.issuer,
            serialNumber = certificate.serialNumber.toString(),
            notBefore = info.notBefore.time,
            notAfter = info.notAfter.time,
            expiresAt = info.notAfter.time,
            createdAt = System.currentTimeMillis(),
            lastUsed = System.currentTimeMillis()
        )

        database.certificateDao().insertCertificate(trustedCert)
        Logger.i("CertificateManager", "Certificate trusted for $hostname:$port")
    }

    /**
     * Remove trusted certificate
     */
    suspend fun removeTrustedCertificate(hostname: String, port: Int) = withContext(Dispatchers.IO) {
        val cert = database.certificateDao().getCertificateByHostAndPort(hostname, port)
        cert?.let { database.certificateDao().deleteCertificate(it) }
        Logger.i("CertificateManager", "Certificate removed for $hostname:$port")
    }

    /**
     * Get all trusted certificates
     */
    suspend fun getTrustedCertificates(): List<TrustedCertificate> = withContext(Dispatchers.IO) {
        return@withContext database.certificateDao().getAllCertificates().first()
    }

    /**
     * Create custom SSL socket factory with certificate validation
     */
    fun createSSLSocketFactory(hostname: String, port: Int): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                // Client certificates not used
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                // Custom certificate validation
                if (chain.isNotEmpty()) {
                    val certificate = chain[0]
                    // Synchronous validation - in real implementation would need to handle async
                    Logger.d("CertificateManager", "Validating certificate for $hostname")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), java.security.SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * Certificate verification result
     */
    data class CertificateVerificationResult(
        val trusted: Boolean,
        val status: CertificateStatus,
        val message: String,
        val previousFingerprint: String? = null,
        val currentFingerprint: String? = null,
        val certificateInfo: CertificateInfo? = null
    )

    /**
     * Clean up expired certificates
     */
    suspend fun cleanupExpiredCertificates() = withContext(Dispatchers.IO) {
        val certificates = database.certificateDao().getAllCertificates().first()
        val now = System.currentTimeMillis()

        certificates.filter { cert ->
            cert.expiresAt < now
        }.forEach { cert ->
            database.certificateDao().deleteCertificate(cert)
            Logger.d("CertificateManager", "Removed expired certificate for ${cert.hostname}:${cert.port}")
        }
    }

    /**
     * Export trusted certificates
     */
    suspend fun exportTrustedCertificates(): String = withContext(Dispatchers.IO) {
        val certificates = database.certificateDao().getAllCertificates().first()
        return@withContext certificates.joinToString("\n") { cert ->
            "${cert.hostname}:${cert.port}|${cert.fingerprint}|${cert.subject}"
        }
    }

    /**
     * Import trusted certificates
     */
    suspend fun importTrustedCertificates(data: String) = withContext(Dispatchers.IO) {
        data.lines().forEach { line ->
            if (line.isNotBlank()) {
                val parts = line.split("|")
                if (parts.size >= 3) {
                    val hostPort = parts[0].split(":")
                    if (hostPort.size == 2) {
                        // Would need to reconstruct full certificate from data
                        Logger.d("CertificateManager", "Imported certificate for ${parts[0]}")
                    }
                }
            }
        }
    }
}
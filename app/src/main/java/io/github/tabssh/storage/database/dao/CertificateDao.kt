package io.github.tabssh.storage.database.dao

import androidx.room.*
import io.github.tabssh.storage.database.entities.TrustedCertificate
import kotlinx.coroutines.flow.Flow

/**
 * DAO for TrustedCertificate entities
 * Manages trusted SSL/TLS certificates for secure connections
 */
@Dao
interface CertificateDao {

    @Query("SELECT * FROM trusted_certificates ORDER BY created_at DESC")
    fun getAllCertificates(): Flow<List<TrustedCertificate>>

    @Query("SELECT * FROM trusted_certificates WHERE hostname = :hostname")
    suspend fun getCertificatesByHostname(hostname: String): List<TrustedCertificate>

    @Query("SELECT * FROM trusted_certificates WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getCertificateByFingerprint(fingerprint: String): TrustedCertificate?

    @Query("SELECT * FROM trusted_certificates WHERE id = :id LIMIT 1")
    suspend fun getCertificate(id: String): TrustedCertificate?

    @Query("""
        SELECT * FROM trusted_certificates
        WHERE hostname = :hostname AND port = :port
        LIMIT 1
    """)
    suspend fun getCertificateByHostAndPort(hostname: String, port: Int): TrustedCertificate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCertificate(certificate: TrustedCertificate)

    @Update
    suspend fun updateCertificate(certificate: TrustedCertificate)

    @Delete
    suspend fun deleteCertificate(certificate: TrustedCertificate)

    @Query("DELETE FROM trusted_certificates WHERE hostname = :hostname")
    suspend fun deleteCertificatesByHostname(hostname: String)

    @Query("DELETE FROM trusted_certificates WHERE fingerprint = :fingerprint")
    suspend fun deleteCertificateByFingerprint(fingerprint: String)

    @Query("DELETE FROM trusted_certificates")
    suspend fun deleteAllCertificates()

    @Query("SELECT COUNT(*) FROM trusted_certificates")
    suspend fun getCertificateCount(): Int

    @Query("""
        SELECT * FROM trusted_certificates
        WHERE expires_at > :currentTime
        ORDER BY expires_at ASC
    """)
    suspend fun getValidCertificates(currentTime: Long): List<TrustedCertificate>

    @Query("""
        SELECT * FROM trusted_certificates
        WHERE expires_at <= :currentTime
        ORDER BY expires_at ASC
    """)
    suspend fun getExpiredCertificates(currentTime: Long): List<TrustedCertificate>

    @Query("UPDATE trusted_certificates SET last_used = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: String, timestamp: Long)
}
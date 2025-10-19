package com.tabssh.storage.database.entities

import androidx.room.*

/**
 * Entity representing a trusted SSL/TLS certificate
 * Used for certificate pinning and trust management
 */
@Entity(
    tableName = "trusted_certificates",
    indices = [
        Index("hostname"),
        Index("fingerprint", unique = true),
        Index(value = ["hostname", "port"], unique = true)
    ]
)
data class TrustedCertificate(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "hostname")
    val hostname: String,

    @ColumnInfo(name = "port")
    val port: Int,

    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "algorithm")
    val algorithm: String,

    @ColumnInfo(name = "certificate_data")
    val certificateData: String,

    @ColumnInfo(name = "subject")
    val subject: String,

    @ColumnInfo(name = "issuer")
    val issuer: String,

    @ColumnInfo(name = "serial_number")
    val serialNumber: String,

    @ColumnInfo(name = "not_before")
    val notBefore: Long,

    @ColumnInfo(name = "not_after")
    val notAfter: Long,

    @ColumnInfo(name = "expires_at")
    val expiresAt: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_used")
    val lastUsed: Long,

    @ColumnInfo(name = "trust_level")
    val trustLevel: String = "USER_ACCEPTED",

    @ColumnInfo(name = "is_self_signed")
    val isSelfSigned: Boolean = false,

    @ColumnInfo(name = "is_ca_signed")
    val isCaSigned: Boolean = true,

    @ColumnInfo(name = "key_usage")
    val keyUsage: String? = null,

    @ColumnInfo(name = "extended_key_usage")
    val extendedKeyUsage: String? = null,

    @ColumnInfo(name = "subject_alternative_names")
    val subjectAlternativeNames: String? = null,

    @ColumnInfo(name = "verification_status")
    val verificationStatus: String = "PENDING",

    @ColumnInfo(name = "notes")
    val notes: String? = null
) {
    companion object {
        const val TRUST_LEVEL_SYSTEM = "SYSTEM"
        const val TRUST_LEVEL_CA = "CA_SIGNED"
        const val TRUST_LEVEL_USER_ACCEPTED = "USER_ACCEPTED"
        const val TRUST_LEVEL_USER_REJECTED = "USER_REJECTED"
        const val TRUST_LEVEL_PINNED = "PINNED"

        const val VERIFICATION_PENDING = "PENDING"
        const val VERIFICATION_VALID = "VALID"
        const val VERIFICATION_INVALID = "INVALID"
        const val VERIFICATION_EXPIRED = "EXPIRED"
        const val VERIFICATION_REVOKED = "REVOKED"
    }

    /**
     * Check if certificate is expired
     */
    fun isExpired(): Boolean {
        return System.currentTimeMillis() > expiresAt
    }

    /**
     * Check if certificate is valid for given hostname
     */
    fun isValidForHostname(targetHostname: String): Boolean {
        if (hostname.equals(targetHostname, ignoreCase = true)) {
            return true
        }

        // Check wildcard certificates
        if (hostname.startsWith("*.")) {
            val wildcardDomain = hostname.substring(2)
            return targetHostname.endsWith(".$wildcardDomain", ignoreCase = true)
        }

        // Check Subject Alternative Names
        subjectAlternativeNames?.let { sanList ->
            val sans = sanList.split(",").map { it.trim() }
            return sans.any { san ->
                san.equals(targetHostname, ignoreCase = true) ||
                (san.startsWith("*.") && targetHostname.endsWith(".${san.substring(2)}", ignoreCase = true))
            }
        }

        return false
    }

    /**
     * Get human readable expiry info
     */
    fun getExpiryInfo(): String {
        val now = System.currentTimeMillis()
        val daysUntilExpiry = (expiresAt - now) / (24 * 60 * 60 * 1000)

        return when {
            isExpired() -> "Expired"
            daysUntilExpiry <= 30 -> "Expires in $daysUntilExpiry days"
            else -> "Valid"
        }
    }
}
package io.github.tabssh.crypto.keys

/**
 * SSH key types supported by TabSSH
 */
enum class KeyType(
    val algorithmName: String,
    val defaultKeySize: Int,
    val supportedKeySizes: List<Int>,
    val description: String,
    val securityLevel: SecurityLevel
) {
    RSA(
        algorithmName = "RSA",
        defaultKeySize = 3072,
        supportedKeySizes = listOf(2048, 3072, 4096),
        description = "RSA keys - widely supported but larger",
        securityLevel = SecurityLevel.GOOD
    ),
    
    DSA(
        algorithmName = "DSA", 
        defaultKeySize = 2048,
        supportedKeySizes = listOf(1024, 2048, 3072),
        description = "DSA keys - legacy, not recommended",
        securityLevel = SecurityLevel.LEGACY
    ),
    
    ECDSA(
        algorithmName = "ECDSA",
        defaultKeySize = 256,
        supportedKeySizes = listOf(256, 384, 521),
        description = "ECDSA keys - modern, efficient",
        securityLevel = SecurityLevel.EXCELLENT
    ),
    
    ED25519(
        algorithmName = "Ed25519",
        defaultKeySize = 256,
        supportedKeySizes = listOf(256),
        description = "Ed25519 keys - most secure and efficient",
        securityLevel = SecurityLevel.BEST
    );
    
    enum class SecurityLevel(val description: String, val color: Int) {
        LEGACY("Legacy - Not Recommended", 0xFFFF5722.toInt()),
        GOOD("Good Security", 0xFFFF9800.toInt()),
        EXCELLENT("Excellent Security", 0xFF4CAF50.toInt()),
        BEST("Best Security", 0xFF2196F3.toInt())
    }
    
    companion object {
        /**
         * Get key type from algorithm name
         */
        fun fromAlgorithm(algorithm: String): KeyType? {
            return values().find { it.algorithmName.equals(algorithm, ignoreCase = true) }
        }
        
        /**
         * Get recommended key types for new key generation
         */
        fun getRecommendedTypes(): List<KeyType> {
            return listOf(ED25519, ECDSA, RSA)
        }
        
        /**
         * Get all supported key types
         */
        fun getAllTypes(): List<KeyType> {
            return values().toList()
        }
        
        /**
         * Check if key size is valid for key type
         */
        fun isValidKeySize(keyType: KeyType, keySize: Int): Boolean {
            return keySize in keyType.supportedKeySizes
        }
    }
    
    /**
     * Get OpenSSH key type identifier
     */
    fun getOpenSSHIdentifier(): String {
        return when (this) {
            RSA -> "ssh-rsa"
            DSA -> "ssh-dss"
            ECDSA -> when (defaultKeySize) {
                256 -> "ecdsa-sha2-nistp256"
                384 -> "ecdsa-sha2-nistp384"
                521 -> "ecdsa-sha2-nistp521"
                else -> "ecdsa-sha2-nistp256"
            }
            ED25519 -> "ssh-ed25519"
        }
    }
    
    /**
     * Get Java algorithm name for key generation
     */
    fun getJavaAlgorithm(): String {
        return when (this) {
            RSA -> "RSA"
            DSA -> "DSA"
            ECDSA -> "EC"
            ED25519 -> "Ed25519"
        }
    }
    
    /**
     * Check if this key type is recommended for new keys
     */
    fun isRecommended(): Boolean {
        return securityLevel == SecurityLevel.EXCELLENT || securityLevel == SecurityLevel.BEST
    }
    
    /**
     * Get curve name for ECDSA keys
     */
    fun getECCurveName(): String? {
        return when (this) {
            ECDSA -> when (defaultKeySize) {
                256 -> "secp256r1"
                384 -> "secp384r1"
                521 -> "secp521r1"
                else -> "secp256r1"
            }
            else -> null
        }
    }
}
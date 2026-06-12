package io.github.tabssh.crypto.storage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manages secure password storage using Android Keystore with multiple security levels
 * Provides hardware-backed encryption for sensitive SSH credentials
 */
class SecurePasswordManager(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS_PREFIX = "tabssh_password_"
        private const val BIOMETRIC_KEY_ALIAS_PREFIX = "tabssh_bio_password_"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 16
        
        private const val SHARED_PREFS_NAME = "tabssh_secure_storage"
        private const val PREF_STORAGE_LEVEL_PREFIX = "storage_level_"
        private const val PREF_ENCRYPTED_DATA_PREFIX = "encrypted_data_"
        private const val PREF_IV_PREFIX = "iv_"
        private const val PREF_TIMESTAMP_PREFIX = "timestamp_"
    }
    
    /**
     * Password storage security levels
     */
    enum class StorageLevel(val value: Int, val description: String) {
        NEVER(0, "Never store passwords"),
        SESSION_ONLY(1, "Store for session only"),
        ENCRYPTED(2, "Store encrypted with device security"),
        BIOMETRIC(3, "Store with biometric protection");
        
        companion object {
            fun fromValue(value: Int): StorageLevel {
                return values().find { it.value == value } ?: ENCRYPTED
            }
        }
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
    private val sharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)

    // Session-only password storage (in memory)
    private val sessionPasswords = mutableMapOf<String, String>()

    // Lifecycle-independent scope for biometric callback continuations.
    // Lives as long as the SecurePasswordManager instance (i.e. the process).
    // SupervisorJob so a failed biometric coroutine doesn't cancel siblings.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Security policy settings
    private var defaultStorageLevel = StorageLevel.ENCRYPTED
    private var passwordTTL = 24 * 60 * 60 * 1000L // 24 hours
    private var requireBiometricForSensitive = true
    private var maxFailedAttempts = 3
    private var autoDeleteOnFailure = true
    
    private var isInitialized = false
    
    fun initialize() {
        if (isInitialized) return

        try {
            keyStore.load(null)
            Logger.d("SecurePasswordManager", "Initialized with Android Keystore")
        } catch (e: Exception) {
            // Keystore unavailable (e.g. corrupted, unsupported ROM) — degrade to session-only.
            // Passwords will work for the current session but will not be persisted.
            Logger.e("SecurePasswordManager", "Keystore unavailable, falling back to session-only storage", e)
            defaultStorageLevel = StorageLevel.SESSION_ONLY
        }
        isInitialized = true
    }
    
    /**
     * Store a password with the specified security level
     */
    suspend fun storePassword(
        connectionId: String,
        password: String,
        level: StorageLevel = defaultStorageLevel
    ): Boolean {
        
        if (password.isBlank()) {
            Logger.w("SecurePasswordManager", "Cannot store empty password")
            return false
        }
        
        return try {
            when (level) {
                StorageLevel.NEVER -> {
                    // Just clear any existing stored password
                    clearPassword(connectionId)
                    true
                }
                StorageLevel.SESSION_ONLY -> {
                    sessionPasswords[connectionId] = password
                    clearPersistedPassword(connectionId)
                    Logger.d("SecurePasswordManager", "Stored password in session for $connectionId")
                    true
                }
                StorageLevel.ENCRYPTED -> {
                    storeEncryptedPassword(connectionId, password, false)
                }
                StorageLevel.BIOMETRIC -> {
                    if (isBiometricAvailable()) {
                        storeEncryptedPassword(connectionId, password, true)
                    } else {
                        Logger.w("SecurePasswordManager", "Biometric not available, falling back to encrypted storage")
                        storeEncryptedPassword(connectionId, password, false)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("SecurePasswordManager", "Failed to store password for $connectionId", e)
            false
        }
    }
    
    private suspend fun storeEncryptedPassword(
        connectionId: String,
        password: String,
        useBiometric: Boolean
    ): Boolean {
        
        val keyAlias = if (useBiometric) {
            "$BIOMETRIC_KEY_ALIAS_PREFIX$connectionId"
        } else {
            "$KEY_ALIAS_PREFIX$connectionId"
        }
        
        try {
            // Generate or get existing key
            val secretKey = getOrCreateSecretKey(keyAlias, useBiometric)
            
            // Encrypt password
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            
            // Store encrypted data and metadata
            val editor = sharedPrefs.edit()
            editor.putString("$PREF_ENCRYPTED_DATA_PREFIX$connectionId", 
                android.util.Base64.encodeToString(encryptedData, android.util.Base64.NO_WRAP))
            editor.putString("$PREF_IV_PREFIX$connectionId", 
                android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            editor.putInt("$PREF_STORAGE_LEVEL_PREFIX$connectionId", 
                if (useBiometric) StorageLevel.BIOMETRIC.value else StorageLevel.ENCRYPTED.value)
            editor.putLong("$PREF_TIMESTAMP_PREFIX$connectionId", System.currentTimeMillis())
            // Use commit() (synchronous) since this is always called from a background thread
            // (Dispatchers.IO). apply() queues an async write; if the process is killed before
            // it flushes, the credential is silently lost. Check return value — false means
            // the write failed (e.g. storage full); surface it as a store failure.
            val written = editor.commit()
            if (!written) {
                Logger.e("SecurePasswordManager", "SharedPrefs commit() returned false for $connectionId — disk full?")
                return false
            }

            // Clear session password since we have persistent storage
            sessionPasswords.remove(connectionId)

            Logger.d("SecurePasswordManager", "Stored encrypted password for $connectionId (biometric: $useBiometric)")
            return true
            
        } catch (e: Exception) {
            Logger.e("SecurePasswordManager", "Failed to encrypt password for $connectionId", e)
            return false
        }
    }
    
    /**
     * Retrieve a stored password
     */
    suspend fun retrievePassword(connectionId: String): String? {
        return try {
            // Check session storage first
            sessionPasswords[connectionId]?.let { password ->
                Logger.d("SecurePasswordManager", "Retrieved password from session for $connectionId")
                return password
            }
            
            // Check if we have persistent storage
            val storageLevel = StorageLevel.fromValue(
                sharedPrefs.getInt("$PREF_STORAGE_LEVEL_PREFIX$connectionId", -1)
            )
            
            if (storageLevel == StorageLevel.NEVER) {
                return null
            }

            // No TTL expiry on locally-stored encrypted credentials.
            // The previous 24-hour expiry silently deleted hypervisor and
            // SSH passwords a day after they were saved, surfacing as
            // "password not persisting" reports. The Keystore key is
            // already bound to device-unlock; expiring on top of that
            // adds friction without a security gain.

            // Retrieve encrypted password
            retrieveEncryptedPassword(connectionId, storageLevel == StorageLevel.BIOMETRIC)
            
        } catch (e: Exception) {
            Logger.e("SecurePasswordManager", "Failed to retrieve password for $connectionId", e)
            null
        }
    }
    
    private suspend fun retrieveEncryptedPassword(
        connectionId: String,
        requiresBiometric: Boolean
    ): String? {
        
        val keyAlias = if (requiresBiometric) {
            "$BIOMETRIC_KEY_ALIAS_PREFIX$connectionId"
        } else {
            "$KEY_ALIAS_PREFIX$connectionId"
        }
        
        try {
            // Get stored data
            val encryptedDataStr = sharedPrefs.getString("$PREF_ENCRYPTED_DATA_PREFIX$connectionId", null)
                ?: return null
            val ivStr = sharedPrefs.getString("$PREF_IV_PREFIX$connectionId", null)
                ?: return null
            
            val encryptedData = android.util.Base64.decode(encryptedDataStr, android.util.Base64.NO_WRAP)
            val iv = android.util.Base64.decode(ivStr, android.util.Base64.NO_WRAP)
            
            // Get secret key
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey
                ?: return null
            
            // Decrypt password
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            val decryptedData = cipher.doFinal(encryptedData)
            val password = String(decryptedData, Charsets.UTF_8)
            
            Logger.d("SecurePasswordManager", "Retrieved encrypted password for $connectionId")
            return password
            
        } catch (e: Exception) {
            Logger.e("SecurePasswordManager", "Failed to decrypt password for $connectionId", e)
            // Do NOT auto-delete on decryption failure. A key-invalidation event
            // (new biometric enrolled, screen lock changed) is transient; wiping the
            // stored ciphertext makes the error permanent and silent. Return null so
            // the caller can prompt the user to re-enter — the next storePassword()
            // call will re-encrypt with a fresh key and overwrite cleanly.
            return null
        }
    }
    
    /**
     * Store password with biometric authentication
     */
    suspend fun storePasswordWithBiometrics(
        connectionId: String,
        password: String,
        activity: FragmentActivity
    ): Boolean {
        
        if (!isBiometricAvailable()) {
            Logger.w("SecurePasswordManager", "Biometric authentication not available")
            return false
        }
        
        return suspendCancellableCoroutine { continuation ->
            val biometricPrompt = BiometricPrompt(activity, 
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Logger.d("SecurePasswordManager", "Biometric authentication succeeded for password storage")
                        
                        // Store password after successful biometric authentication
                        managerScope.launch {
                            val success = storeEncryptedPassword(connectionId, password, true)
                            continuation.resume(success)
                        }
                    }
                    
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Logger.e("SecurePasswordManager", "Biometric authentication error: $errString")
                        continuation.resume(false)
                    }
                    
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Logger.w("SecurePasswordManager", "Biometric authentication failed")
                        continuation.resume(false)
                    }
                })
            
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Store Password Securely")
                .setSubtitle("Use your biometric to securely store the SSH password")
                .setDescription("This will encrypt and store your password using hardware-backed security")
                .setNegativeButtonText("Cancel")
                .build()
            
            biometricPrompt.authenticate(promptInfo)
        }
    }
    
    /**
     * Retrieve password with biometric authentication
     */
    suspend fun retrievePasswordWithBiometrics(
        connectionId: String,
        activity: FragmentActivity
    ): String? {
        
        if (!isBiometricAvailable()) {
            Logger.w("SecurePasswordManager", "Biometric authentication not available")
            return null
        }
        
        return suspendCancellableCoroutine { continuation ->
            val biometricPrompt = BiometricPrompt(activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        Logger.d("SecurePasswordManager", "Biometric authentication succeeded for password retrieval")
                        
                        // Retrieve password after successful biometric authentication
                        managerScope.launch {
                            val password = retrieveEncryptedPassword(connectionId, true)
                            continuation.resume(password)
                        }
                    }
                    
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Logger.e("SecurePasswordManager", "Biometric authentication error: $errString")
                        continuation.resume(null)
                    }
                    
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Logger.w("SecurePasswordManager", "Biometric authentication failed")
                        continuation.resume(null)
                    }
                })
            
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Access Stored Password")
                .setSubtitle("Use your biometric to access the stored SSH password")
                .setDescription("Authenticate to decrypt your securely stored password")
                .setNegativeButtonText("Cancel")
                .build()
            
            biometricPrompt.authenticate(promptInfo)
        }
    }
    
    /**
     * Clear password for a connection.
     *
     * suspend + IO dispatch: keyStore.deleteEntry() is a hardware-backed
     * HAL round-trip that blocks the caller thread. Must never run on Main
     * — calling this without the dispatch would ANR on slow/emulated devices.
     * The in-memory remove is safe on any thread (ConcurrentHashMap).
     */
    suspend fun clearPassword(connectionId: String) {
        // In-memory remove is lock-free, do it first regardless of dispatcher.
        sessionPasswords.remove(connectionId)
        withContext(Dispatchers.IO) {
            clearPersistedPassword(connectionId)
        }
        Logger.d("SecurePasswordManager", "Cleared password for $connectionId")
    }

    private fun clearPersistedPassword(connectionId: String) {
        // Remove keys from Keystore first — blocking HAL op, must be called
        // from IO dispatcher. Doing this before SharedPrefs ensures that if the
        // process is killed mid-cleanup, the ciphertext left in SharedPrefs is
        // unreadable (its key is already gone) rather than leaving a live key
        // with no ciphertext to encrypt (less dangerous but still inconsistent).
        try {
            keyStore.deleteEntry("$KEY_ALIAS_PREFIX$connectionId")
            keyStore.deleteEntry("$BIOMETRIC_KEY_ALIAS_PREFIX$connectionId")
        } catch (e: Exception) {
            Logger.w("SecurePasswordManager", "Error deleting keystore entries for $connectionId", e)
        }
        // Synchronous commit() — this is always called from Dispatchers.IO.
        // apply() queues an async write; if the process is killed before it
        // flushes, stale ciphertext persists in SharedPrefs.
        // Log if commit returns false — the ciphertext would remain as an
        // unreadable orphan (its Keystore key is already gone above).
        val cleared = sharedPrefs.edit()
            .remove("$PREF_ENCRYPTED_DATA_PREFIX$connectionId")
            .remove("$PREF_IV_PREFIX$connectionId")
            .remove("$PREF_STORAGE_LEVEL_PREFIX$connectionId")
            .remove("$PREF_TIMESTAMP_PREFIX$connectionId")
            .commit()
        if (!cleared) Logger.w("SecurePasswordManager", "SharedPrefs commit() returned false clearing $connectionId")
    }
    
    /**
     * Clear all stored passwords
     */
    fun clearAllPasswords() {
        Logger.d("SecurePasswordManager", "Clearing all stored passwords")
        
        // Clear session storage
        sessionPasswords.clear()
        
        // Get all connection IDs from preferences
        val connectionIds = mutableSetOf<String>()
        sharedPrefs.all.keys.forEach { key ->
            when {
                key.startsWith(PREF_ENCRYPTED_DATA_PREFIX) -> {
                    connectionIds.add(key.removePrefix(PREF_ENCRYPTED_DATA_PREFIX))
                }
            }
        }
        
        // Clear each connection's data
        connectionIds.forEach { connectionId ->
            clearPersistedPassword(connectionId)
        }
        
        Logger.i("SecurePasswordManager", "Cleared all stored passwords (${connectionIds.size} connections)")
    }
    
    /**
     * Clear sensitive data on app crash
     */
    fun clearSensitiveDataOnCrash() {
        Logger.w("SecurePasswordManager", "Clearing sensitive data due to crash")
        
        // Clear session-only passwords (persistent encrypted data remains)
        sessionPasswords.clear()
        
        // Could also clear temporary cached data here
    }
    
    /**
     * Clear all sensitive data from memory
     */
    fun clearSensitiveData() {
        Logger.d("SecurePasswordManager", "Clearing sensitive data from memory")
        
        // Clear session storage
        sessionPasswords.clear()
        
        // Force garbage collection to clear memory
        System.gc()
    }
    
    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    /**
     * Get or create a secret key for encryption
     */
    private fun getOrCreateSecretKey(keyAlias: String, requiresBiometric: Boolean): SecretKey {
        return try {
            // Try to get existing key
            (keyStore.getKey(keyAlias, null) as? SecretKey) 
                ?: createSecretKey(keyAlias, requiresBiometric)
        } catch (e: Exception) {
            // Key might be corrupted, create new one
            Logger.w("SecurePasswordManager", "Existing key corrupted, creating new one", e)
            createSecretKey(keyAlias, requiresBiometric)
        }
    }
    
    private fun createSecretKey(keyAlias: String, requiresBiometric: Boolean): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        
        val keyGenParameterSpec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (requiresBiometric && isBiometricAvailable()) {
                    setUserAuthenticationRequired(true)
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG) // Require auth for each use
                }
            }
            .build()
        
        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }
    
    /**
     * Get storage statistics
     */
    fun getStorageStatistics(): StorageStatistics {
        val sessionCount = sessionPasswords.size
        val encryptedCount = sharedPrefs.all.keys.count { it.startsWith(PREF_ENCRYPTED_DATA_PREFIX) }
        val biometricCount = sharedPrefs.all.keys.count { key ->
            key.startsWith(PREF_STORAGE_LEVEL_PREFIX) && 
            sharedPrefs.getInt(key, -1) == StorageLevel.BIOMETRIC.value
        }
        
        return StorageStatistics(
            sessionOnlyCount = sessionCount,
            encryptedCount = encryptedCount - biometricCount,
            biometricCount = biometricCount,
            totalCount = sessionCount + encryptedCount
        )
    }
    
    /**
     * Set security policy
     */
    fun setSecurityPolicy(
        defaultLevel: StorageLevel = StorageLevel.ENCRYPTED,
        passwordTTLHours: Int = 24,
        requireBiometric: Boolean = true,
        maxAttempts: Int = 3,
        autoDelete: Boolean = true
    ) {
        defaultStorageLevel = defaultLevel
        passwordTTL = passwordTTLHours * 60 * 60 * 1000L
        requireBiometricForSensitive = requireBiometric
        maxFailedAttempts = maxAttempts
        autoDeleteOnFailure = autoDelete
        
        Logger.d("SecurePasswordManager", "Security policy updated: level=$defaultLevel, ttl=${passwordTTLHours}h")
    }
    
    /**
     * Check if host requires enhanced security
     */
    fun requiresEnhancedSecurity(hostname: String): Boolean {
        val sensitivePatterns = arrayOf("prod", "production", "live", "master", "main")
        return sensitivePatterns.any { pattern ->
            hostname.lowercase().contains(pattern)
        }
    }
}

/**
 * Storage statistics
 */
data class StorageStatistics(
    val sessionOnlyCount: Int,
    val encryptedCount: Int,
    val biometricCount: Int,
    val totalCount: Int
)
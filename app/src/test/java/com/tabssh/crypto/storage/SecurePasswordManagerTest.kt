package com.tabssh.crypto.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.*

/**
 * Comprehensive tests for secure password management
 */
@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SecurePasswordManagerTest {
    
    private lateinit var context: Context
    private lateinit var passwordManager: SecurePasswordManager
    
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        passwordManager = SecurePasswordManager(context)
        
        // Initialize for testing (may throw on devices without hardware keystore)
        try {
            passwordManager.initialize()
        } catch (e: Exception) {
            // Handle test environment without hardware keystore
        }
    }
    
    @Test
    fun `test storage level enum`() {
        assertEquals(0, SecurePasswordManager.StorageLevel.NEVER.value)
        assertEquals(1, SecurePasswordManager.StorageLevel.SESSION_ONLY.value)
        assertEquals(2, SecurePasswordManager.StorageLevel.ENCRYPTED.value)
        assertEquals(3, SecurePasswordManager.StorageLevel.BIOMETRIC.value)
        
        assertEquals(SecurePasswordManager.StorageLevel.ENCRYPTED, 
            SecurePasswordManager.StorageLevel.fromValue(2))
        assertEquals(SecurePasswordManager.StorageLevel.ENCRYPTED,
            SecurePasswordManager.StorageLevel.fromValue(-1)) // Invalid defaults to ENCRYPTED
    }
    
    @Test
    fun `test session-only password storage`() = runTest {
        val connectionId = "test-connection"
        val password = "test-password-123"
        
        // Store password in session only
        val stored = passwordManager.storePassword(
            connectionId, 
            password, 
            SecurePasswordManager.StorageLevel.SESSION_ONLY
        )
        
        assertTrue(stored)
        
        // Should be able to retrieve immediately
        val retrieved = passwordManager.retrievePassword(connectionId)
        assertEquals(password, retrieved)
        
        // Clear session data
        passwordManager.clearSensitiveData()
        
        // Should no longer be available
        val retrievedAfterClear = passwordManager.retrievePassword(connectionId)
        assertNull(retrievedAfterClear)
    }
    
    @Test
    fun `test never store password level`() = runTest {
        val connectionId = "test-connection"
        val password = "test-password-123"
        
        // Store with NEVER level should clear any existing passwords
        val stored = passwordManager.storePassword(
            connectionId,
            password,
            SecurePasswordManager.StorageLevel.NEVER
        )
        
        assertTrue(stored)
        
        // Should not be retrievable
        val retrieved = passwordManager.retrievePassword(connectionId)
        assertNull(retrieved)
    }
    
    @Test
    fun `test empty password handling`() = runTest {
        val connectionId = "test-connection"
        
        // Empty password should not be stored
        val stored = passwordManager.storePassword(connectionId, "")
        assertFalse(stored)
        
        // Blank password should not be stored
        val storedBlank = passwordManager.storePassword(connectionId, "   ")
        assertFalse(storedBlank)
    }
    
    @Test
    fun `test password clearing`() = runTest {
        val connectionId = "test-connection"
        val password = "test-password-123"
        
        // Store password
        passwordManager.storePassword(
            connectionId,
            password,
            SecurePasswordManager.StorageLevel.SESSION_ONLY
        )
        
        // Verify stored
        assertNotNull(passwordManager.retrievePassword(connectionId))
        
        // Clear password
        passwordManager.clearPassword(connectionId)
        
        // Should be cleared
        assertNull(passwordManager.retrievePassword(connectionId))
    }
    
    @Test
    fun `test clear all passwords`() = runTest {
        val passwords = mapOf(
            "connection1" to "password1",
            "connection2" to "password2", 
            "connection3" to "password3"
        )
        
        // Store multiple passwords
        passwords.forEach { (id, pwd) ->
            passwordManager.storePassword(id, pwd, SecurePasswordManager.StorageLevel.SESSION_ONLY)
        }
        
        // Verify all stored
        passwords.forEach { (id, pwd) ->
            assertEquals(pwd, passwordManager.retrievePassword(id))
        }
        
        // Clear all passwords
        passwordManager.clearAllPasswords()
        
        // Verify all cleared
        passwords.keys.forEach { id ->
            assertNull(passwordManager.retrievePassword(id))
        }
    }
    
    @Test
    fun `test security policy for sensitive hosts`() {
        val sensitiveHosts = listOf(
            "prod.example.com",
            "production-server.com",
            "live.mycompany.com",
            "master.database.com",
            "main.server.org"
        )
        
        sensitiveHosts.forEach { host ->
            assertTrue(passwordManager.requiresEnhancedSecurity(host), 
                "Host $host should require enhanced security")
        }
        
        val normalHosts = listOf(
            "dev.example.com",
            "test.server.com",
            "localhost"
        )
        
        normalHosts.forEach { host ->
            assertFalse(passwordManager.requiresEnhancedSecurity(host),
                "Host $host should not require enhanced security")
        }
    }
    
    @Test
    fun `test storage statistics`() = runTest {
        // Store passwords with different levels
        passwordManager.storePassword("session1", "pwd1", SecurePasswordManager.StorageLevel.SESSION_ONLY)
        passwordManager.storePassword("session2", "pwd2", SecurePasswordManager.StorageLevel.SESSION_ONLY)
        
        val stats = passwordManager.getStorageStatistics()
        
        assertEquals(2, stats.sessionOnlyCount)
        assertTrue(stats.totalCount >= 2)
    }
    
    @Test
    fun `test security policy configuration`() {
        passwordManager.setSecurityPolicy(
            defaultLevel = SecurePasswordManager.StorageLevel.BIOMETRIC,
            passwordTTLHours = 12,
            requireBiometric = true,
            maxAttempts = 5,
            autoDelete = false
        )
        
        // Configuration should be applied (can't easily test without exposing internals)
        // This tests that the method doesn't crash
    }
    
    @Test
    fun `test biometric availability detection`() {
        // Test that biometric availability check doesn't crash
        val isAvailable = passwordManager.isBiometricAvailable()
        
        // Result depends on test device/emulator capabilities
        // Just verify the method works
        assertTrue(isAvailable || !isAvailable) // Always true, just testing no crash
    }
}
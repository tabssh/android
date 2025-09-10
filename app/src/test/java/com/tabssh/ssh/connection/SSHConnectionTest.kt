package com.tabssh.ssh.connection

import com.tabssh.storage.database.entities.ConnectionProfile
import com.tabssh.ssh.auth.AuthType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Comprehensive unit tests for SSH connection management
 */
@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class SSHConnectionTest {
    
    @Mock
    private lateinit var mockConnectionListener: ConnectionListener
    
    private lateinit var connectionProfile: ConnectionProfile
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        
        connectionProfile = ConnectionProfile(
            id = "test-connection",
            name = "Test Server",
            host = "test.example.com",
            port = 22,
            username = "testuser",
            authType = AuthType.PASSWORD.name
        )
    }
    
    @Test
    fun `test connection profile creation`() {
        assertEquals("test-connection", connectionProfile.id)
        assertEquals("Test Server", connectionProfile.name)
        assertEquals("test.example.com", connectionProfile.host)
        assertEquals(22, connectionProfile.port)
        assertEquals("testuser", connectionProfile.username)
        assertEquals(AuthType.PASSWORD, connectionProfile.getAuthTypeEnum())
    }
    
    @Test
    fun `test connection profile display name`() {
        assertEquals("Test Server", connectionProfile.getDisplayName())
        
        val profileWithoutName = connectionProfile.copy(name = "")
        assertEquals("testuser@test.example.com:22", profileWithoutName.getDisplayName())
    }
    
    @Test
    fun `test auth type enum conversion`() {
        val passwordProfile = connectionProfile.copy(authType = "PASSWORD")
        assertEquals(AuthType.PASSWORD, passwordProfile.getAuthTypeEnum())
        
        val keyProfile = connectionProfile.copy(authType = "PUBLIC_KEY")
        assertEquals(AuthType.PUBLIC_KEY, keyProfile.getAuthTypeEnum())
        
        val invalidProfile = connectionProfile.copy(authType = "INVALID")
        assertEquals(AuthType.PASSWORD, invalidProfile.getAuthTypeEnum()) // Default fallback
    }
    
    @Test
    fun `test connection state transitions`() {
        assertEquals("Disconnected", ConnectionState.DISCONNECTED.displayName)
        assertEquals("Connecting...", ConnectionState.CONNECTING.displayName)
        assertEquals("Connected", ConnectionState.CONNECTED.displayName)
        assertEquals("Error", ConnectionState.ERROR.displayName)
        
        assertFalse(ConnectionState.DISCONNECTED.isActive())
        assertTrue(ConnectionState.CONNECTING.isActive())
        assertTrue(ConnectionState.CONNECTED.isActive())
        assertFalse(ConnectionState.ERROR.isActive())
        
        assertTrue(ConnectionState.CONNECTED.isConnected())
        assertFalse(ConnectionState.CONNECTING.isConnected())
        
        assertTrue(ConnectionState.DISCONNECTED.canReconnect())
        assertTrue(ConnectionState.ERROR.canReconnect())
        assertFalse(ConnectionState.CONNECTED.canReconnect())
    }
    
    @Test
    fun `test auth type requirements`() {
        assertTrue(AuthType.requiresKey(AuthType.PUBLIC_KEY))
        assertFalse(AuthType.requiresKey(AuthType.PASSWORD))
        
        assertTrue(AuthType.requiresPassword(AuthType.PASSWORD))
        assertTrue(AuthType.requiresPassword(AuthType.KEYBOARD_INTERACTIVE))
        assertFalse(AuthType.requiresPassword(AuthType.PUBLIC_KEY))
    }
    
    @Test
    fun `test auth type from string`() {
        assertEquals(AuthType.PASSWORD, AuthType.fromString("PASSWORD"))
        assertEquals(AuthType.PUBLIC_KEY, AuthType.fromString("PUBLIC_KEY"))
        assertEquals(AuthType.PASSWORD, AuthType.fromString(null)) // Default
        assertEquals(AuthType.PASSWORD, AuthType.fromString("INVALID")) // Default
    }
    
    @Test
    fun `test connection listener notifications`() = runTest {
        // This would test the connection listener callbacks
        // Requires more setup with actual connection mocking
    }
    
    @Test
    fun `test connection statistics`() {
        // Test connection stats tracking
        val stats = ConnectionStats(
            serverVersion = "OpenSSH_8.0",
            clientVersion = "JSch-1.55",
            bytesTransferred = 1024,
            isConnected = true
        )
        
        assertEquals("OpenSSH_8.0", stats.serverVersion)
        assertEquals(1024, stats.bytesTransferred)
        assertTrue(stats.isConnected)
    }
}
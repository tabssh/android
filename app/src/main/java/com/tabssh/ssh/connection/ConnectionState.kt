package com.tabssh.ssh.connection

/**
 * Represents the state of an SSH connection
 */
enum class ConnectionState(val displayName: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    AUTHENTICATING("Authenticating..."),
    CONNECTED("Connected"),
    ERROR("Error");
    
    fun isActive(): Boolean {
        return this == CONNECTING || this == AUTHENTICATING || this == CONNECTED
    }
    
    fun isConnected(): Boolean {
        return this == CONNECTED
    }
    
    fun canReconnect(): Boolean {
        return this == DISCONNECTED || this == ERROR
    }
}
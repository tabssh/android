package io.github.tabssh.ssh.auth

/**
 * SSH authentication types supported by TabSSH
 */
enum class AuthType(val displayName: String, val description: String) {
    PASSWORD("Password", "Username and password authentication"),
    PUBLIC_KEY("Public Key", "SSH key-based authentication"),
    KEYBOARD_INTERACTIVE("Keyboard Interactive", "Interactive authentication (2FA, challenge-response)"),
    GSSAPI("GSSAPI", "Generic Security Services API authentication");
    
    companion object {
        /**
         * Get AuthType from string name, with fallback to PASSWORD
         */
        fun fromString(name: String?): AuthType {
            return try {
                valueOf(name ?: "PASSWORD")
            } catch (e: IllegalArgumentException) {
                PASSWORD
            }
        }
        
        /**
         * Get all available auth types for UI display
         */
        fun getAvailableTypes(): List<AuthType> {
            return values().toList()
        }
        
        /**
         * Check if auth type requires a private key
         */
        fun requiresKey(authType: AuthType): Boolean {
            return authType == PUBLIC_KEY
        }
        
        /**
         * Check if auth type requires a password
         */
        fun requiresPassword(authType: AuthType): Boolean {
            return authType == PASSWORD || authType == KEYBOARD_INTERACTIVE
        }
    }
}
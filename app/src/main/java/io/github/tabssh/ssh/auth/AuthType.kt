package io.github.tabssh.ssh.auth

import kotlinx.serialization.Serializable
import io.github.tabssh.utils.logging.Logger

/**
 * SSH authentication types supported by TabSSH
 */
@Serializable
enum class AuthType(val displayName: String, val description: String) {
    PASSWORD("Password", "Username and password authentication"),
    PUBLIC_KEY("Public Key", "SSH key-based authentication"),
    KEYBOARD_INTERACTIVE("Keyboard Interactive", "Interactive authentication (2FA, challenge-response)");

    companion object {
        /**
         * Get AuthType from a string that may be an enum name ("PUBLIC_KEY") or
         * an OpenSSH config / import alias ("publickey", "keyboard-interactive").
         * Falls back to PASSWORD on any unrecognised value.
         */
        fun fromString(name: String?): AuthType {
            if (name == null) return PASSWORD
            return when (name.lowercase().trim()) {
                "public_key", "publickey", "public-key", "key" -> PUBLIC_KEY
                "password" -> PASSWORD
                "keyboard_interactive", "keyboard-interactive", "keyboard" -> KEYBOARD_INTERACTIVE
                else -> try { valueOf(name) } catch (e: IllegalArgumentException) { Logger.d("AuthType", "fromString failed: ${e.javaClass.simpleName}: ${e.message}"); PASSWORD }
            }
        }

        /**
         * Get auth types shown in the connection-edit picker.
         */
        fun getAvailableTypes(): List<AuthType> = values().toList()

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

package io.github.tabssh.ssh.auth

import kotlinx.serialization.Serializable

/**
 * SSH authentication types supported by TabSSH
 */
@Serializable
enum class AuthType(val displayName: String, val description: String) {
    PASSWORD("Password", "Username and password authentication"),
    PUBLIC_KEY("Public Key", "SSH key-based authentication"),
    KEYBOARD_INTERACTIVE("Keyboard Interactive", "Interactive authentication (2FA, challenge-response)"),
    GSSAPI("GSSAPI", "Generic Security Services API authentication"),
    // Wave 2.9 — alpha. Hardware detection works (Fido2Detector); the
    // CTAP2 getAssertion + sk-ssh-ed25519 signature plumbing into JSch
    // is NOT yet implemented. Selecting this auth type currently fails
    // with a clear message so users don't think it's silently broken.
    FIDO2_SECURITY_KEY("FIDO2 Security Key (Alpha)", "Hardware token (YubiKey, SoloKey, …) — alpha, not yet wired to SSH");
    
    companion object {
        /**
         * Get AuthType from a string that may be an enum name ("PUBLIC_KEY") or
         * an OpenSSH config / import alias ("publickey", "keyboard-interactive").
         * Falls back to PASSWORD on any unrecognised value.
         */
        fun fromString(name: String?): AuthType {
            if (name == null) return PASSWORD
            return when (name.lowercase().trim()) {
                "public_key", "publickey", "public-key" -> PUBLIC_KEY
                "password" -> PASSWORD
                "keyboard_interactive", "keyboard-interactive", "keyboard" -> KEYBOARD_INTERACTIVE
                "gssapi", "gssapi-with-mic" -> GSSAPI
                "fido2_security_key", "fido2", "sk-ssh-ed25519", "sk-ecdsa-sha2-nistp256" -> FIDO2_SECURITY_KEY
                else -> try { valueOf(name) } catch (_: IllegalArgumentException) { PASSWORD }
            }
        }
        
        /**
         * Get auth types shown in the connection-edit picker.
         * GSSAPI and FIDO2_SECURITY_KEY are intentionally excluded:
         * GSSAPI has no JSch wiring (would silently fall through to password),
         * FIDO2 throws NotImplementedError at connect time (CTAP2 not shipped).
         * Both enum values are kept for backup/restore compatibility.
         */
        fun getAvailableTypes(): List<AuthType> {
            return values().filter { it != GSSAPI && it != FIDO2_SECURITY_KEY }
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
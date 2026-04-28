package io.github.tabssh.pairing

/**
 * QR pairing payload schema. Mirrors the desktop side's `PairingPayload`
 * (Rust ciborium-serialized struct). Field names are snake_case in the CBOR
 * representation to match Rust's default serde naming.
 *
 * See QR_PAIRING.md for the full design and threat model.
 *
 * **What's in here:** non-secret connection metadata + public-key references.
 * **What's NOT in here:** passwords, private keys, biometric/keystore aliases.
 * The phone never receives credentials over a QR scan.
 */
data class PairingPayload(
    /** Schema version; phone aborts gracefully if this exceeds what it understands. */
    val version: Int,

    /** Unix epoch seconds. Phone refuses imports past this point. */
    val expiresAt: Long,

    /** Display string shown in the confirmation dialog (e.g. "Alice's Linux desktop"). */
    val deviceLabel: String?,

    val connections: List<ExportedConnection>,
    val groups: List<ExportedGroup>,
    val identities: List<ExportedIdentity>,
)

data class ExportedConnection(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val protocol: String,            // "ssh" | "telnet"
    val authType: String,            // AuthType enum name
    val terminalType: String,
    val encoding: String,
    val compression: Boolean,
    val keepAlive: Boolean,
    val x11Forwarding: Boolean,
    val useMosh: Boolean,
    val agentForwarding: Boolean,
    val theme: String,
    val colorTag: Int,
    val groupName: String?,          // resolved to groupId at import time
    val identityName: String?,       // resolved to identityId at import time
    val envVars: String?,
    val postConnectScript: String?,
    val proxyHost: String?,
    val proxyPort: Int?,
    val proxyType: String?,
    val proxyUsername: String?,
    val proxyAuthType: String?,
    /** OpenSSH-format public key line, optional (e.g. "ssh-ed25519 AAAA…"). */
    val sshKeyPublic: String?,
    /** SHA-256 fingerprint of the key for user verification. */
    val sshKeyFingerprint: String?,
)

data class ExportedGroup(
    val name: String,
    val parentName: String?,
    val color: String?,
    val icon: String?,
    val sortOrder: Int,
)

data class ExportedIdentity(
    val name: String,
    val username: String,
    val authType: String,
    val description: String?,
)

/**
 * Outer envelope encoded into the QR. The desktop generates `salt` + `nonce`
 * fresh per QR; the 6-digit code stretched via Argon2id is what makes the
 * encrypted payload secret. The QR itself is treated as public.
 */
data class QrEnvelope(
    /** Envelope version. v1 of this format. */
    val version: Int,
    /** Argon2id salt — 16 random bytes. */
    val salt: ByteArray,
    /** AES-GCM nonce — 12 random bytes. */
    val nonce: ByteArray,
    /** AES-256-GCM ciphertext + 128-bit auth tag, in standard javax.crypto layout. */
    val ciphertext: ByteArray,
) {
    // Generated equals/hashCode that respect ByteArray content (the
    // auto-generated data-class versions compare by reference).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrEnvelope) return false
        return version == other.version &&
                salt.contentEquals(other.salt) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

/** Outcomes returned by the decryption + decode pipeline. */
sealed class PairingResult {
    data class Success(val payload: PairingPayload) : PairingResult()
    data class Failure(val reason: FailureReason, val message: String) : PairingResult()
}

enum class FailureReason {
    /** Couldn't decode the outer CBOR envelope (malformed bytes). */
    BAD_ENVELOPE,

    /** Envelope version is newer than what we understand. */
    UNSUPPORTED_VERSION,

    /** AES-GCM auth tag mismatch — wrong code or tampered ciphertext. */
    WRONG_CODE,

    /** Inner payload's `expires_at` is in the past. */
    EXPIRED,

    /** Couldn't decode the inner payload as a known PairingPayload. */
    BAD_PAYLOAD,

    /** Inner payload's version is newer than what we understand. */
    UNSUPPORTED_PAYLOAD_VERSION,

    /** Catch-all (programming errors, unexpected exceptions). */
    INTERNAL_ERROR,
}

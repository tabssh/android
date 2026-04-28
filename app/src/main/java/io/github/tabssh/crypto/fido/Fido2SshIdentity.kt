package io.github.tabssh.crypto.fido

import com.jcraft.jsch.Identity
import com.jcraft.jsch.JSchException

/**
 * Wave 2.9 alpha — JSch [Identity] that *would* sign SSH challenges using a
 * FIDO2 hardware token (YubiKey, SoloKey, Nitrokey, …) but is intentionally
 * not implemented yet. Surfacing this class lets the rest of the codebase
 * reference it (auth-type spinner, ConnectionEditActivity, sync model) so
 * the wiring is in place when the CTAP2 transport layer ships.
 *
 * What's blocking real implementation:
 *   1. CTAP2 USB-HID transport — needs `UsbDeviceConnection.bulkTransfer`
 *      with the right HID report descriptor parsing. Doable but ~1k LOC.
 *   2. Authenticator command framing (`authenticatorMakeCredential` /
 *      `authenticatorGetAssertion`) per FIDO CTAP 2.1 spec.
 *   3. JSch doesn't natively understand `sk-ssh-ed25519@openssh.com` or
 *      `sk-ecdsa-sha2-nistp256@openssh.com` signature formats — we'd need
 *      to either patch upstream JSch (mwiede fork) or wrap with a custom
 *      signature post-processor that emits the SK_RAW format the server
 *      expects (challenge || flags || counter || sig).
 *
 * Until those land, [getSignature] throws a runtime error with a clear
 * message. That keeps the failure visible rather than hidden behind a
 * generic "auth failed".
 */
class Fido2SshIdentity(
    private val handleBytes: ByteArray,
    private val publicKeyBytes: ByteArray,
    private val algorithm: String  // "sk-ssh-ed25519@openssh.com" or sk-ecdsa-...
) : Identity {

    @Throws(JSchException::class)
    override fun getSignature(data: ByteArray?): ByteArray {
        throw JSchException(
            "FIDO2 SSH signing is alpha and not yet implemented. " +
            "Use a regular SSH key for now; this option is reserved for the upcoming " +
            "CTAP2 transport (Wave 2.9 part 2)."
        )
    }

    @Throws(JSchException::class)
    override fun getSignature(data: ByteArray?, alg: String?): ByteArray = getSignature(data)

    override fun getAlgName(): String = algorithm

    override fun getName(): String = "fido2-${algorithm.removePrefix("sk-").substringBefore('@')}"

    override fun getPublicKeyBlob(): ByteArray = publicKeyBytes

    override fun isEncrypted(): Boolean = false

    @Throws(JSchException::class)
    override fun decrypt(): Boolean = true

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun clear() { /* no-op */ }
}

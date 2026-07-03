package io.github.tabssh.hypervisor.spice

/**
 * Connection parameters for a single [SpiceClient] session.
 *
 * The set mirrors the fields that libspice-client-glib's
 * `SpiceSession` GObject accepts via `g_object_set` — see
 * <https://gitlab.freedesktop.org/spice/spice-gtk> — so the JNI layer
 * can forward them without translation. Anything the C side does not
 * need at handshake time (e.g. audio, USB redirection) is deliberately
 * absent from this task; task #14 or later can extend the struct.
 *
 * @param host Guest-facing hostname or IP of the SPICE endpoint. For
 *   Proxmox spiceproxy this is the *proxy* host, not the VM host.
 * @param port Plain-text SPICE port. Set to `0` when only TLS is
 *   available (Proxmox `spice-tls`-only setups).
 * @param tlsPort TLS SPICE port. Set to `0` when TLS is not
 *   configured — mutually exclusive with plain-only is fine, both may
 *   also be non-zero and the C side will prefer TLS.
 * @param password SPICE ticket (a.k.a. session password) issued by the
 *   hypervisor. libspice sends this on the main channel after the
 *   transport handshake. May be empty for hypervisors that use
 *   URL-embedded auth (rare for SPICE — Proxmox and oVirt both use
 *   tickets).
 * @param caCert PEM-encoded CA certificate that signed the SPICE
 *   server's TLS cert, or `null` to use the platform trust store.
 *   Required for Proxmox because its `pveproxy` cert is self-signed
 *   by the per-cluster PVE CA.
 * @param hostSubject Optional TLS certificate subject the C side will
 *   pin against — set to the SPICE server's expected subject line
 *   (e.g. `"O=Proxmox Virtual Environment, CN=<node-fqdn>"`). Null
 *   means no pinning beyond CA validation.
 * @param tlsVerify When true, libspice validates the TLS chain
 *   against [caCert] and [hostSubject]. When false, TLS is still used
 *   but validation is skipped — intended for the "trust this cert
 *   once" flow, never a default.
 */
data class SpiceConnectionParams(
    val host: String,
    val port: Int,
    val tlsPort: Int,
    val password: String,
    val caCert: ByteArray? = null,
    val hostSubject: String? = null,
    val tlsVerify: Boolean = true,
) {
    init {
        require(host.isNotEmpty()) { "host must not be empty" }
        require(port in 0..65535) { "port out of range: $port" }
        require(tlsPort in 0..65535) { "tlsPort out of range: $tlsPort" }
        require(port != 0 || tlsPort != 0) {
            "at least one of port or tlsPort must be non-zero"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpiceConnectionParams) return false
        return host == other.host &&
            port == other.port &&
            tlsPort == other.tlsPort &&
            password == other.password &&
            (caCert?.contentEquals(other.caCert) == true ||
                (caCert == null && other.caCert == null)) &&
            hostSubject == other.hostSubject &&
            tlsVerify == other.tlsVerify
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + tlsPort
        result = 31 * result + password.hashCode()
        result = 31 * result + (caCert?.contentHashCode() ?: 0)
        result = 31 * result + (hostSubject?.hashCode() ?: 0)
        result = 31 * result + tlsVerify.hashCode()
        return result
    }
}

package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Hypervisor connection profile
 * Supports: Proxmox, XCP-ng, VMware, Xen Orchestra (via XCP-ng + apiTypeOverride),
 * and Oracle Cloud Infrastructure (OCI). The dialect is gated by `type` and,
 * for non-password auth, by `authType` (see below).
 */
@Entity(tableName = "hypervisors")
@Serializable
data class HypervisorProfile(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    
    @ColumnInfo(name = "name")
    val name: String,
    
    @ColumnInfo(name = "type")
    val type: HypervisorType,
    
    @ColumnInfo(name = "host")
    val host: String,
    
    @ColumnInfo(name = "port")
    val port: Int,
    
    @ColumnInfo(name = "username")
    val username: String,
    
    @ColumnInfo(name = "password")
    val password: String,
    
    @ColumnInfo(name = "realm")
    val realm: String? = null, // Proxmox: pam/pve, XCP-ng: not used
    
    @ColumnInfo(name = "verify_ssl")
    val verifySsl: Boolean = false,

    /**
     * SHA-256 fingerprint of the leaf certificate this host presented
     * on its most recent successful connect. Captured by
     * `HypervisorTrustManagerFactory` on a verifySsl=true connect when
     * no prior pin existed (TOFU); enforced on subsequent connects —
     * mismatch aborts the TLS handshake. NULL means "no pin yet";
     * verifySsl=false ignores this column entirely.
     *
     * Format mirrors what we display: lowercase hex bytes joined by
     * colons, e.g. `f1:c0:fe:...`. Stored without the `SHA-256:` prefix.
     */
    @ColumnInfo(name = "pinned_cert_sha256")
    val pinnedCertSha256: String? = null,
    
    @ColumnInfo(name = "is_xen_orchestra")
    val isXenOrchestra: Boolean = false, // DEPRECATED: Use apiTypeOverride instead

    @ColumnInfo(name = "api_type_override")
    val apiTypeOverride: String = "auto", // "auto", "direct", "centralized" - Override auto-detection

    @ColumnInfo(name = "linked_connection_id")
    val linkedConnectionId: String? = null, // Reference to existing SSH connection (ConnectionProfile.id)

    /**
     * Optional reference to a reusable `HypervisorAccount`. When set,
     * the host inherits username + (Keystore) password + (optional)
     * realm from the account; the inline `username` / `password` /
     * `realm` columns on this row become legacy fallbacks.
     *
     * Resolution rules — see `HypervisorPasswordStore.resolveCredentials`:
     *   accountId == null: inline fields win.
     *   accountId != null: account.username + account-Keystore password;
     *                      profile.realm if non-blank else account.realm.
     */
    @ColumnInfo(name = "account_id")
    val accountId: Long? = null,

    @ColumnInfo(name = "notes")
    val notes: String? = null,

    @ColumnInfo(name = "last_connected")
    val lastConnected: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Auth-style discriminator. Existing types (PROXMOX/XCPNG/VMWARE) use
     * `"password"` — username + (Keystore-stored) password. OCI uses
     * `"oci_api_key"` — five OCIDs + RSA key pair stored in Keystore under
     * `oci_private_key_${id}` (and optional `oci_passphrase_${id}`).
     *
     * Kept as a free-form TEXT so future auth styles (token, mTLS, …) can
     * land without a schema change. Defaulted to "password" so the
     * v28→v29 migration is invisible to existing rows.
     */
    @ColumnInfo(name = "auth_type")
    val authType: String = "password",

    /** OCI tenancy OCID. Required when authType="oci_api_key". */
    @ColumnInfo(name = "oci_tenancy_ocid")
    val ociTenancyOcid: String? = null,

    /** OCI user OCID (the IAM user the API key was issued to). */
    @ColumnInfo(name = "oci_user_ocid")
    val ociUserOcid: String? = null,

    /** OCI region identifier, e.g. "us-ashburn-1". Determines the API host
     *  (iaas.<region>.oraclecloud.com). For type=OCI rows we mirror this
     *  into the legacy `host` column for display purposes only. */
    @ColumnInfo(name = "oci_region")
    val ociRegion: String? = null,

    /** Public-key fingerprint as displayed by the OCI console — colon-
     *  separated lowercase hex of MD5(SubjectPublicKeyInfo DER). Cross-
     *  checked against the imported PEM during onboarding. */
    @ColumnInfo(name = "oci_fingerprint")
    val ociFingerprint: String? = null,

    /** Compartment OCID to scope ListInstances against. NULL/empty = use
     *  tenancy OCID (the root compartment). */
    @ColumnInfo(name = "oci_compartment_ocid")
    val ociCompartmentOcid: String? = null
)

@Serializable
enum class HypervisorType {
    PROXMOX,
    XCPNG,
    VMWARE,
    OCI,
    LIBVIRT
}

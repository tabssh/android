package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Reusable hypervisor credential set. Covers two auth styles:
 *
 *  **Password** (`authType = "password"`) — username + Keystore-stored
 *  password + optional realm. Multiple `HypervisorProfile` rows can
 *  reference one account via `HypervisorProfile.accountId`, so a single
 *  "Proxmox admin" credential drives N servers without duplication.
 *
 *  **OCI API Key** (`authType = "oci_api_key"`) — OCI tenancy/user/region/
 *  fingerprint stored here; PEM private key stored in the Keystore under
 *  `oci_private_key_account_${id}`. One account per OCI tenancy user.
 *
 * Why not reuse `Identity` for hypervisor credentials? Identity is SSH-
 * shaped (carries `keyId`, `authType = PUBLIC_KEY / PASSWORD`). Hypervisor
 * REST APIs only use the password/API-key half, and overlapping the two
 * creates dead fields and tangled rotation policies.
 */
@Serializable
@Entity(tableName = "hypervisor_accounts")
data class HypervisorAccount(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    /**
     * Username for password-type accounts (e.g. `root@pam`). Set to an
     * empty string for OCI accounts — the user identity is [ociUserOcid].
     */
    @ColumnInfo(name = "username")
    val username: String = "",

    /** Optional default realm (Proxmox: `pam`/`pve`/...). Per-host
     *  realm on `HypervisorProfile.realm` overrides this when set. */
    @ColumnInfo(name = "realm")
    val realm: String? = null,

    /**
     * Auth-style discriminator. `"password"` — username + Keystore
     * password. `"oci_api_key"` — OCI API-key HTTP-signature auth;
     * the five OCI fields below carry credential metadata and the PEM
     * lives in Keystore under `oci_private_key_account_${id}`.
     * Free-form TEXT so future auth styles land without schema changes.
     */
    @ColumnInfo(name = "auth_type")
    val authType: String = "password",

    /** OCI tenancy OCID. Required when authType="oci_api_key". */
    @ColumnInfo(name = "oci_tenancy_ocid")
    val ociTenancyOcid: String? = null,

    /** OCI user OCID (the IAM user the API key was issued to). */
    @ColumnInfo(name = "oci_user_ocid")
    val ociUserOcid: String? = null,

    /** OCI region identifier, e.g. "us-ashburn-1". */
    @ColumnInfo(name = "oci_region")
    val ociRegion: String? = null,

    /** Public-key fingerprint as displayed by the OCI console —
     *  colon-separated lowercase hex of MD5(SubjectPublicKeyInfo DER). */
    @ColumnInfo(name = "oci_fingerprint")
    val ociFingerprint: String? = null,

    /**
     * Default compartment OCID for instance listing. Optional — when
     * blank, `OciApiClient` falls back to the tenancy OCID which lists
     * all top-level resources the user can see.
     */
    @ColumnInfo(name = "oci_compartment_ocid")
    val ociCompartmentOcid: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /** Label shown in dropdowns and list items. */
    fun getDisplayName(): String = when {
        authType == "oci_api_key" -> "$name (OCI — ${ociRegion ?: "?"})"
        username.isNotBlank()     -> "$name ($username)"
        else                      -> name
    }
}

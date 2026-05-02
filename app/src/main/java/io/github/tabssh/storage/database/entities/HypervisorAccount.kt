package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Reusable hypervisor credential set — username + (Keystore-stored)
 * password + optional realm. Multiple `HypervisorProfile` rows can
 * point at one account via `HypervisorProfile.accountId`, so a single
 * "Proxmox admin" credential can drive 20 servers without duplication.
 *
 * Why not reuse `Identity` for this? Identity is SSH-shaped (carries
 * `keyId` + `authType=PASSWORD/PUBLIC_KEY`). Hypervisor REST APIs only
 * use the password half, and overlapping the two creates dead fields
 * + tangled rotation policies (SSH-key prod hosts vs. admin password
 * for hypervisors usually rotate on different cadences).
 *
 * The password itself never lives in this row — it's stored in the
 * Keystore-backed `SecurePasswordManager` under
 * `hypervisor_account_${id}`, mirroring the per-host pattern
 * (`hypervisor_${id}`) introduced for `HypervisorProfile`.
 */
@Entity(tableName = "hypervisor_accounts")
data class HypervisorAccount(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "username")
    val username: String,

    /** Optional default realm (Proxmox: `pam`/`pve`/...). Per-host
     *  realm on `HypervisorProfile.realm` overrides this when set. */
    @ColumnInfo(name = "realm")
    val realm: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /** "name (username)" — used by the dropdown picker. */
    fun getDisplayName(): String = "$name ($username)"
}

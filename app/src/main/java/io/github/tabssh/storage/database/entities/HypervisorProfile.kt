package io.github.tabssh.storage.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Hypervisor connection profile
 * Supports: Proxmox, XCP-ng, VMware, Xen Orchestra
 */
@Entity(tableName = "hypervisors")
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
    val createdAt: Long = System.currentTimeMillis()
)

enum class HypervisorType {
    PROXMOX,
    XCPNG,
    VMWARE
}

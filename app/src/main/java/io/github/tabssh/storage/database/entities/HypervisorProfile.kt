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
    val isXenOrchestra: Boolean = false, // For XCP-ng: true = XO REST API, false = XML-RPC direct
    
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

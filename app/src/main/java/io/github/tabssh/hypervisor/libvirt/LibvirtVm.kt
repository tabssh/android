package io.github.tabssh.hypervisor.libvirt

/**
 * A libvirt domain as reported by `virsh list --all`.
 *
 * @param id    virsh domain ID; -1 when the VM is shut off.
 * @param name  domain name.
 * @param state human-readable state string from virsh output (e.g. "running", "shut off", "paused").
 */
data class LibvirtVm(
    val id: Int,
    val name: String,
    val state: String
)

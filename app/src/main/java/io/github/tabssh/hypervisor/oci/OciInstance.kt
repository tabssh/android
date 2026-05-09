package io.github.tabssh.hypervisor.oci

/**
 * OCI Compute Instance — the v1 API view (`Instance` resource). Mirrors
 * the fields the manager UI needs; we don't model everything.
 *
 *  - `id` is the instance OCID (`ocid1.instance.oc1...`).
 *  - `lifecycleState` matches the values OCI returns: PROVISIONING,
 *    RUNNING, STARTING, STOPPING, STOPPED, CREATING_IMAGE,
 *    TERMINATING, TERMINATED, MOVING.
 *  - `publicIp` / `privateIp` come from a separate VNIC walk
 *    (`OciApiClient.getInstancePublicIp`) — the Instance resource itself
 *    does not include them.
 */
data class OciInstance(
    val id: String,
    val displayName: String,
    val lifecycleState: String,
    val region: String?,
    val availabilityDomain: String,
    val compartmentId: String,
    val shape: String,
    val timeCreated: String,
    val publicIp: String? = null,
    val privateIp: String? = null
)

/** Per OCI Compute API — actions accepted by `instanceAction`. */
enum class OciInstanceAction(val wireValue: String) {
    START("START"),
    STOP("STOP"),
    SOFTSTOP("SOFTSTOP"),
    RESET("RESET"),
    SOFTRESET("SOFTRESET")
}

package io.github.tabssh.sync.tombstone

import android.content.Context
import io.github.tabssh.storage.database.TabSSHDatabase
import io.github.tabssh.storage.database.entities.ComposeStack
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.DockerHost
import io.github.tabssh.storage.database.entities.HypervisorAccount
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.RegistryCredential
import io.github.tabssh.storage.database.entities.SingleContainerConfig
import io.github.tabssh.storage.database.entities.SyncTombstone
import io.github.tabssh.sync.metadata.SyncMetadataManager
import io.github.tabssh.utils.logging.Logger

/**
 * H6 — central tombstone recorder (the explicit half of the approved
 * "Hybrid: helper + safety net" recording scheme).
 *
 * Call [record] immediately after a synced entity is hard-deleted; it captures
 * the accurate delete time and this device's id so the deletion propagates and
 * wins the last-write-wins comparison against a stale peer copy. The
 * diff-at-collect backstop (in the collector) is the safety net that catches
 * bulk/cascade/future un-instrumented deletes with an approximate collect-time
 * timestamp.
 *
 * Recording is best-effort: a tombstone write must NEVER block or crash a user
 * delete, so every failure is swallowed and logged. If the explicit record is
 * lost, the backstop still tombstones the vanished row on the next collect.
 *
 * [entityKey] is the stable cross-device identity, NOT the raw Room PK. For the
 * 14 UUID-keyed entities pass the UUID. For the Long-autoincrement entities
 * (HypervisorProfile, HypervisorAccount, and the five Docker entities) pass
 * [naturalKey] — their Long id is meaningless across devices.
 */
object TombstoneRecorder {

    const val CONNECTION = "connection"
    const val KEY = "key"
    const val THEME = "theme"
    const val HOST_KEY = "host_key"
    const val WORKSPACE = "workspace"
    const val SNIPPET = "snippet"
    const val IDENTITY = "identity"
    const val GROUP = "group"
    const val HYPERVISOR = "hypervisor"
    const val CERTIFICATE = "certificate"
    const val MACRO = "macro"
    const val MONITOR_SLOT = "monitor_slot"
    const val HYPERVISOR_ACCOUNT = "hypervisor_account"
    const val VNC_HOST = "vnc_host"
    const val VNC_IDENTITY = "vnc_identity"
    const val CLOUD_ACCOUNT = "cloud_account"
    const val PORT_FORWARD = "port_forward"
    const val DOCKER_HOST = "docker_host"
    const val REGISTRY_CREDENTIAL = "registry_credential"
    const val COMPOSE_STACK = "compose_stack"
    const val SINGLE_CONTAINER_CONFIG = "single_container_config"
    const val CONTAINER_AUTO_UPDATE_POLICY = "container_auto_update_policy"

    /**
     * Stable cross-device key for the Long-PK [HypervisorProfile]: its id is a
     * local autoincrement value, so identity is the user-facing `name|type`.
     */
    fun naturalKey(profile: HypervisorProfile): String = "${profile.name}|${profile.type}"

    /**
     * Stable cross-device key for the Long-PK [HypervisorAccount]: `name`, the
     * auth-style discriminator, and `username` together identify the account
     * without relying on the local autoincrement id.
     */
    fun naturalKey(account: HypervisorAccount): String =
        "${account.name}|${account.authType}|${account.username}"

    /**
     * Stable cross-device key for the Long-PK [DockerHost]: the user-facing
     * `name` plus whichever endpoint identifier is set, since two devices'
     * autoincrement ids for the same host are unrelated.
     */
    fun naturalKey(host: DockerHost): String =
        "${host.name}|${host.linkedConnectionId ?: ""}|${host.customHost ?: ""}"

    /**
     * Stable cross-device key for the Long-PK [RegistryCredential]: a registry
     * host plus username together identify one credential entry.
     */
    fun naturalKey(credential: RegistryCredential): String =
        "${credential.registryHost}|${credential.username}"

    /**
     * Stable cross-device key for the Long-PK [ComposeStack]: scoped by its
     * parent [DockerHost.id] (FK-by-convention, not remapped — see AI.md
     * PART 6) plus the stack name, which is unique per host.
     */
    fun naturalKey(stack: ComposeStack): String = "${stack.dockerHostId}|${stack.name}"

    /**
     * Stable cross-device key for the Long-PK [SingleContainerConfig]: scoped
     * by its parent [DockerHost.id] plus the config name, unique per host.
     */
    fun naturalKey(config: SingleContainerConfig): String = "${config.dockerHostId}|${config.name}"

    /**
     * Stable cross-device key for the Long-PK [ContainerAutoUpdatePolicy]:
     * scoped by its parent [DockerHost.id] plus the container/stack name and
     * scope discriminator, which together are unique per host.
     */
    fun naturalKey(policy: ContainerAutoUpdatePolicy): String =
        "${policy.dockerHostId}|${policy.containerNameOrStackName}|${policy.scope}"

    /**
     * Record a tombstone for a just-deleted synced entity. Best-effort — never
     * throws; the diff-at-collect backstop is the safety net if this is lost.
     */
    suspend fun record(context: Context, entityType: String, entityKey: String) {
        runCatching {
            val app = context.applicationContext
            val deviceId = SyncMetadataManager(app).getDeviceId()
            TabSSHDatabase.getDatabase(app).syncTombstoneDao().record(
                SyncTombstone(
                    entityType = entityType,
                    entityKey = entityKey,
                    deletedAt = System.currentTimeMillis(),
                    deviceId = deviceId
                )
            )
        }.onFailure { e ->
            Logger.w("TombstoneRecorder", "Failed to record tombstone for $entityType: ${e.message}")
        }
    }
}

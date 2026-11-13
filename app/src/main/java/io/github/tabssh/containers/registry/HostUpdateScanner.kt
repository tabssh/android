package io.github.tabssh.containers.registry

import io.github.tabssh.containers.runconfig.RecreateContainer
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.ContainerHost

/**
 * One update pass over a single container host: enumerate the running containers,
 * resolve each one's settings through [UpdateSettingsResolver], lazily create the
 * policy row that tracks its pending-update state, and ask [UpdateChecker] whether
 * a newer image exists. Shared by the background worker and the Updates tab's
 * "Check now" so the scan logic exists once; notifying and applying stay with the caller.
 */
class HostUpdateScanner(
    private val policyDao: ContainerAutoUpdatePolicyDao,
    private val checker: UpdateChecker
) {

    /** The global tier of the update settings. */
    data class GlobalSettings(val enabled: Boolean, val autoApply: Boolean, val intervalHours: Long)

    /** One container with a newer image available, plus its resolved auto-apply setting. */
    data class FoundUpdate(
        val policy: ContainerAutoUpdatePolicy,
        val result: UpdateChecker.CheckResult,
        val autoApply: Boolean,
        val project: String?
    )

    /**
     * Check every container on [host] whose resolved settings enable checks. Returns the
     * containers with an update available, or null when the container list could not be read.
     */
    suspend fun scan(
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        global: GlobalSettings,
        transport: ContainerTransport
    ): List<FoundUpdate>? {
        val containers = (transport.listContainers(all = false) as? ContainerResult.Success)?.value
            ?: return null
        val byName = hostPolicies.filter { it.scope != SCOPE_STACK }
            .associateBy { it.containerNameOrStackName }
        val byProject = hostPolicies.filter { it.scope == SCOPE_STACK }
            .associateBy { it.containerNameOrStackName }

        val found = mutableListOf<FoundUpdate>()
        for (container in containers) {
            val name = container.names.firstOrNull()?.trimStart('/') ?: continue
            if (name.endsWith(RecreateContainer.OLD_NAME_SUFFIX)) continue
            val project = container.labels[UpdateApplier.COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotEmpty() }
            var policy = byName[name]
            val stack = project?.let { byProject[it] }
            val settings = UpdateSettingsResolver.resolve(
                global.enabled, global.autoApply, global.intervalHours,
                host.updateEnabledOverride, host.updateAutoApply, host.updateCheckIntervalHours,
                stack?.enabledOverride, stack?.autoApplyOverride,
                policy?.enabledOverride, policy?.autoApplyOverride
            )
            if (!settings.enabled.value) continue
            if (policy == null) {
                val fresh = ContainerAutoUpdatePolicy(
                    containerHostId = host.id,
                    containerNameOrStackName = name,
                    scope = if (project != null) SCOPE_STACK_SERVICE else SCOPE_CONTAINER,
                    modifiedAt = System.currentTimeMillis()
                )
                policy = fresh.copy(id = policyDao.insert(fresh))
            }
            val result = checker.checkOne(policy, transport)
            if (result.status == UpdateChecker.Status.UPDATE_AVAILABLE) {
                found += FoundUpdate(policy, result, settings.autoApply.value, project)
            }
        }
        return found
    }

    companion object {
        const val SCOPE_CONTAINER = "container"
        const val SCOPE_STACK_SERVICE = "stack_service"
        const val SCOPE_STACK = "stack"
    }
}

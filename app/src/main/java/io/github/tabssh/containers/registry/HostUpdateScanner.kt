package io.github.tabssh.containers.registry

import io.github.tabssh.containers.runconfig.RecreateContainer
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.storage.database.dao.ContainerAutoUpdatePolicyDao
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.containers.transport.failureSummary

/**
 * One update pass over a single container host: enumerate the running containers,
 * resolve each one's settings through [UpdateSettingsResolver], lazily create the
 * policy row that tracks its pending-update state, and ask [UpdateChecker] whether
 * a newer image exists. Shared by the background worker and the Updates tab's
 * "Check now" so the scan logic exists once; notifying and applying stay with the caller.
 */
class HostUpdateScanner(
    private val policyDao: ContainerAutoUpdatePolicyDao,
    private val checker: UpdateChecker,
    private val stackPruner: StackPruner? = null
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
        transport: ContainerTransport,
        failures: MutableList<String>? = null
    ): List<FoundUpdate>? = UpdateCoordinator.withHost(host.id) {
        scanLocked(host, hostPolicies, global, transport, failures)
    }

    private suspend fun scanLocked(
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        global: GlobalSettings,
        transport: ContainerTransport,
        failures: MutableList<String>?
    ): List<FoundUpdate>? {
        stackPruner?.prune(host, transport)
        val listed = transport.listContainers(all = false)
        val containers = (listed as? ContainerResult.Success)?.value
        if (containers == null) {
            Logger.w(TAG, "scan ${host.name}: could not list containers: ${listed.failureSummary()}")
            return null
        }
        Logger.i(TAG, "scan ${host.name}: ${containers.size} running container(s), ${hostPolicies.size} polic(ies)")
        val vanished = clearVanishedPending(host, hostPolicies, containers, transport)
        recheckStoppedPending(host, hostPolicies, containers, vanished, transport, failures)
        // Duplicate rows for one container: only the one the scan keeps is ever checked, so a flag on any
        // other copy could never be confirmed or cleared and would stay listed.
        val byName = hostPolicies.filter { it.scope != SCOPE_STACK }
            .groupBy { it.containerNameOrStackName }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.id }!! }
        for ((name, rows) in hostPolicies.filter { it.scope != SCOPE_STACK }.groupBy { it.containerNameOrStackName }) {
            if (rows.size > 1) {
                val keep = byName.getValue(name)
                rows.filter { it.id != keep.id && it.pendingUpdateDigest != null }
                    .forEach { policyDao.updatePendingUpdateDigest(it.id, null) }
            }
        }
        val byProject = hostPolicies.filter { it.scope == SCOPE_STACK }
            .associateBy { it.containerNameOrStackName }

        val found = mutableListOf<FoundUpdate>()
        val dirExists = mutableMapOf<String, Boolean>()
        for (container in containers) {
            val name = container.names.firstOrNull()?.trimStart('/') ?: continue
            if (name.endsWith(RecreateContainer.OLD_NAME_SUFFIX)) {
                Logger.d(TAG, "scan ${host.name}: skipping leftover $name")
                continue
            }
            val project = container.labels[UpdateApplier.COMPOSE_PROJECT_LABEL]?.takeIf { it.isNotEmpty() }
            var policy = byName[name]
            // A compose container whose project directory was deleted has nothing to update through.
            val workDir = container.labels[UpdateApplier.COMPOSE_WORKDIR_LABEL]?.takeIf { it.isNotEmpty() }
            if (project != null && workDir != null &&
                !dirExists.getOrPut(workDir) { StackPruner.directoryExists(transport, workDir) }
            ) {
                policy?.let { policyDao.updatePendingUpdateDigest(it.id, null) }
                Logger.i(TAG, "scan ${host.name}: skipping $name, compose directory $workDir no longer exists")
                continue
            }
            val stack = project?.let { byProject[it] }
            val settings = UpdateSettingsResolver.resolve(
                global.enabled, global.autoApply, global.intervalHours,
                host.updateEnabledOverride, host.updateAutoApply, host.updateCheckIntervalHours,
                stack?.enabledOverride, stack?.autoApplyOverride,
                policy?.enabledOverride, policy?.autoApplyOverride
            )
            if (!settings.enabled.value) {
                // Checks are off for it, so a leftover pending flag could never be re-confirmed.
                policy?.takeIf { it.pendingUpdateDigest != null }?.let { policyDao.updatePendingUpdateDigest(it.id, null) }
                Logger.d(TAG, "scan ${host.name}: skipping $name, update checks are disabled for it")
                continue
            }
            if (policy == null) {
                val fresh = ContainerAutoUpdatePolicy(
                    containerHostId = host.id,
                    containerNameOrStackName = name,
                    scope = if (project != null) SCOPE_STACK_SERVICE else SCOPE_CONTAINER,
                    modifiedAt = System.currentTimeMillis()
                )
                policy = fresh.copy(id = policyDao.insert(fresh))
                Logger.d(TAG, "scan ${host.name}: created policy ${policy.id} for $name")
            }
            val result = checker.checkOne(policy, transport)
            if (result.status == UpdateChecker.Status.ERROR) {
                discardUnconfirmed(policy, result.message, failures)
            }
            if (result.status == UpdateChecker.Status.UPDATE_AVAILABLE) {
                Logger.i(TAG, "scan ${host.name}: update available for $name (autoApply=${settings.autoApply.value})")
                found += FoundUpdate(policy, result, settings.autoApply.value, project)
            }
        }
        Logger.i(TAG, "scan ${host.name}: ${found.size} update(s) found")
        return found
    }

    /**
     * A policy whose container no longer exists (stack recreated, renamed or removed) keeps its
     * pending-update flag forever, because scans only visit running containers; it then shows up
     * as an update that fails with "no such container". Clear the flag for every such policy.
     * A stopped container still counts as existing.
     */
    private suspend fun clearVanishedPending(
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        running: List<io.github.tabssh.containers.transport.ContainerSummary>,
        transport: ContainerTransport
    ): Set<Long> {
        val runningNames = running.flatMap { c -> c.names.map { it.trimStart('/') } }.toSet()
        val suspects = hostPolicies.filter {
            it.scope != SCOPE_STACK && it.pendingUpdateDigest != null && it.containerNameOrStackName !in runningNames
        }
        if (suspects.isEmpty()) return emptySet()
        val all = (transport.listContainers(all = true) as? ContainerResult.Success)?.value ?: return emptySet()
        val existing = all.flatMap { c -> c.names.map { it.trimStart('/') } }.toSet()
        return clearPendingForMissing(policyDao, host.name, suspects, existing).map { it.id }.toSet()
    }

    /**
     * Scans only visit running containers, so a stopped container that was flagged earlier was never
     * looked at again and stayed in the list even after its image was updated by hand. Each such
     * container is checked here so its flag is confirmed or cleared like any other.
     */
    private suspend fun recheckStoppedPending(
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        running: List<io.github.tabssh.containers.transport.ContainerSummary>,
        vanished: Set<Long>,
        transport: ContainerTransport,
        failures: MutableList<String>?
    ) {
        val runningNames = running.flatMap { c -> c.names.map { it.trimStart('/') } }.toSet()
        val stopped = hostPolicies.filter {
            it.scope != SCOPE_STACK && it.pendingUpdateDigest != null &&
                it.containerNameOrStackName !in runningNames && it.id !in vanished
        }
        for (policy in stopped) {
            val result = checker.checkOne(policy, transport)
            Logger.i(TAG, "scan ${host.name}: re-checked stopped ${policy.containerNameOrStackName}: ${result.status}")
            if (result.status == UpdateChecker.Status.ERROR) {
                discardUnconfirmed(policy, result.message, failures)
            }
        }
    }

    /**
     * A container whose check failed (host, engine or registry error) cannot be confirmed, so its
     * pending flag is removed rather than left standing, and the failure is reported to the caller.
     */
    private suspend fun discardUnconfirmed(
        policy: ContainerAutoUpdatePolicy,
        message: String?,
        failures: MutableList<String>?
    ) {
        if (policy.pendingUpdateDigest != null) {
            policyDao.clearPendingForContainer(policy.containerHostId, policy.containerNameOrStackName)
        }
        val reason = message ?: "check failed"
        val suffix = if (policy.pendingUpdateDigest != null) " (update removed: could not be confirmed)" else ""
        failures?.add("${policy.containerNameOrStackName}: $reason$suffix")
    }

    companion object {
        private const val MAX_LOGGED_NAMES = 40
        private const val TAG = "HostUpdateScanner"

        /**
         * Removes every pending update on a host that could not be reached or listed, returning how many
         * were removed. An update that cannot be confirmed is not left on the list; the caller reports it.
         */
        suspend fun discardAllUnconfirmed(
            policyDao: ContainerAutoUpdatePolicyDao,
            hostLabel: String,
            hostId: Long
        ): Int {
            val removed = policyDao.clearPendingForHost(hostId)
            if (removed > 0) {
                Logger.w(TAG, "$hostLabel: could not be checked; removed $removed pending update(s) that could not be confirmed")
            }
            return removed
        }

        /**
         * Clears the pending-update flag of every container policy in [policies] whose container is
         * not among [existing] (the names of ALL containers on the host, stopped ones included) and
         * returns those policies. The Container Updates page, the notification and the dashboard
         * all count from these flags, so any screen that has just seen the host's real container
         * list calls this to keep them in agreement instead of each filtering on its own.
         */
        suspend fun clearPendingForMissing(
            policyDao: ContainerAutoUpdatePolicyDao,
            hostLabel: String,
            policies: List<ContainerAutoUpdatePolicy>,
            existing: Set<String>
        ): List<ContainerAutoUpdatePolicy> {
            val gone = policies.filter {
                it.scope != SCOPE_STACK && it.pendingUpdateDigest != null &&
                    it.containerNameOrStackName !in existing
            }
            for (policy in gone) {
                policyDao.updatePendingUpdateDigest(policy.id, null)
                Logger.i(
                    TAG,
                    "$hostLabel: container ${policy.containerNameOrStackName} no longer exists; " +
                        "cleared its pending update (containers on the host: ${existing.sorted().take(MAX_LOGGED_NAMES).joinToString()})"
                )
            }
            return gone
        }

        const val SCOPE_CONTAINER = "container"
        const val SCOPE_STACK_SERVICE = "stack_service"
        const val SCOPE_STACK = "stack"
    }
}

package io.github.tabssh.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.work.*
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.RegistryCredentialStore
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.registry.RegistryClient
import io.github.tabssh.containers.registry.UpdateApplier
import io.github.tabssh.containers.registry.UpdateChecker
import io.github.tabssh.containers.registry.HostUpdateScanner
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.containers.transport.ContainerTransport
import io.github.tabssh.containers.transport.SshExecRunner
import io.github.tabssh.containers.transport.TransportCapabilityDetector
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.storage.database.entities.ContainerHost
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.logging.Logger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Periodic app-driven container image update checker.
 *
 * The worker wakes hourly; each container host is only checked when its own
 * resolved interval has elapsed ([UpdateCheckGate]). Settings come from three
 * tiers, most specific wins ([UpdateSettingsResolver]): global preferences,
 * per-host overrides on [ContainerHost], and per-image / per-compose-stack
 * overrides on [ContainerAutoUpdatePolicy]. Containers without an override row
 * are checked under the inherited settings; the worker creates the row it needs
 * to track their pending-update state.
 *
 * ## Connections
 *
 * A host is reached through the UI's cached session, an already-live SSH
 * connection, or — for hosts linked to a saved connection — a monitoring
 * connection the worker dials itself and closes afterwards. Hosts it cannot
 * reach (custom endpoint or cloud-linked hosts with no live session) are
 * skipped and retried on a later wake. Registry HTTPS calls are direct.
 *
 * ## Outcomes
 *
 * - New pending update → [NotificationHelper.notifyContainerUpdateAvailable]
 *   (once per standalone container or compose project) plus the DB badge state.
 * - Resolved auto-apply on → [UpdateApplier] updates it unattended and the result
 *   is posted via [NotificationHelper.notifyContainerUpdateApplied]. Compose
 *   containers are updated through compose as a whole project, never with
 *   `docker run`; standalone containers never through compose.
 *
 * ## Gating
 *
 * Network required (no battery-not-low constraint — it can defer checks for
 * days), a validated-internet gate and the battery-saver opt-in pref.
 */
class ContainerUpdateCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ContainerUpdateCheckWorker"

        /** Unique work name used with [ExistingPeriodicWorkPolicy.UPDATE]. */
        const val WORK_NAME = "container_update_check"

        /**
         * Unique work name this worker used in the development build. Still referenced so the
         * startup sweep can cancel the orphaned periodic registration before the new one is
         * enqueued — see TabSSHApplication.cancelLegacyDockerUpdateWork().
         */
        const val LEGACY_WORK_NAME = "docker_update_check"

        /** Default check cadence (twice daily) — registry digests move slowly; 12 h is plenty. */
        const val INTERVAL_HOURS = 12L

        /** Hard cap on hosts checked concurrently per cycle — never unbounded fan-out. */
        private const val MAX_CONCURRENT_HOSTS = 2

        /** How long to wait for a background SSH connect (an unknown host key can never be accepted here). */
        private const val CONNECT_TIMEOUT_MS = 45_000L

        /** How often the worker wakes; each host's own interval decides whether it is actually checked. */
        private const val WAKE_INTERVAL_HOURS = 1L

        /**
         * Enqueue the periodic worker. Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.UPDATE] swaps in the current definition without
         * resetting the schedule.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ContainerUpdateCheckWorker>(
                WAKE_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Logger.d(TAG, "Periodic container update check scheduled (wakes every $WAKE_INTERVAL_HOURS h, network required)")
        }

        /** Cancel the periodic worker (master toggle switched off). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.d(TAG, "Periodic container update check cancelled")
        }
    }

    /**
     * True only when the active network passed Android's own internet
     * validation probe — same rationale as [HostAvailabilityWorker].
     */
    private fun isInternetAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override suspend fun doWork(): Result {
        // Gate 0: validated internet — registry HTTPS calls need a real route.
        if (!isInternetAvailable()) {
            Logger.d(TAG, "Skipping run: no validated internet connection")
            return Result.success()
        }

        val app = TabSSHApplication.get()
        val preferencesManager = app.preferencesManager

        // Gate 1: battery saver mode (same opt-in pref as monitoring).
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode && !preferencesManager.isMonitoringRunInBatterySaverEnabled()) {
            Logger.d(TAG, "Skipping run: battery saver active and pref not set")
            return Result.success()
        }

        val db = app.database
        val global = HostUpdateScanner.GlobalSettings(
            enabled = preferencesManager.isContainerUpdateCheckEnabled(),
            autoApply = preferencesManager.isContainerAutoApplyEnabled(),
            intervalHours = preferencesManager.getContainerUpdateIntervalHours().toLong()
        )
        val allPolicies = db.containerAutoUpdatePolicyDao().getAllList().groupBy { it.containerHostId }

        val checker = UpdateChecker(
            db.containerAutoUpdatePolicyDao(),
            db.registryCredentialDao(),
            RegistryClient()
        ) { credentialId -> RegistryCredentialStore.retrieve(appContext, credentialId) }
        val applier = UpdateApplier(db.containerAutoUpdatePolicyDao())

        // Bounded fan-out: at most MAX_CONCURRENT_HOSTS hosts in flight at
        // once; each host is gated by its resolved enable/interval settings.
        val hostSemaphore = Semaphore(MAX_CONCURRENT_HOSTS)
        val now = System.currentTimeMillis()
        coroutineScope {
            for (host in db.containerHostDao().getAllList()) {
                val hostPolicies = allPolicies[host.id].orEmpty()
                // A host is worth a pass when checks are on for it, or when one of its
                // images/stacks switches them back on over a host/global "off".
                val hostEnabled = host.updateEnabledOverride ?: global.enabled
                if (!hostEnabled && hostPolicies.none { it.enabledOverride == true }) {
                    continue
                }
                if (!UpdateCheckGate.isHostDue(
                        true, host.lastUpdateCheck, host.updateCheckIntervalHours,
                        global.intervalHours, now
                    )
                ) {
                    Logger.d(TAG, "Host ${host.name} not due — skipping")
                    continue
                }
                launch {
                    hostSemaphore.withPermit {
                        val checked = checkHost(app, host, hostPolicies, global, checker, applier)
                        // Only a completed pass advances the per-host clock —
                        // a skipped host retries on the next hourly run.
                        if (checked) {
                            db.containerHostDao().updateLastUpdateCheck(host.id, System.currentTimeMillis())
                        }
                    }
                }
            }
        }

        Logger.d(TAG, "Container update check complete")
        return Result.success()
    }

    /**
     * Get a transport for [host]: the UI's cached session, then an already-live SSH
     * connection, then (saved-connection hosts only) a connection this worker dials
     * itself. [onOwnedConnection] receives the profile id of a connection the worker
     * opened so the caller can close it afterwards; null means skip this host.
     */
    private suspend fun acquireTransport(
        app: TabSSHApplication,
        host: ContainerHost,
        onOwnedConnection: (String) -> Unit,
        onOwnedTransport: (ContainerTransport) -> Unit
    ): ContainerTransport? {
        ContainerSessionManager.cached(host.id)?.let { return it.transport }

        val sessionKey = host.linkedConnectionId
            ?: host.takeIf { it.usesCustomEndpoint() }?.ephemeralProfileId()
            ?: run {
                Logger.w(TAG, "Host ${host.name} has no linked SSH connection — skipping")
                return null
            }
        var connection = app.sshSessionManager.getConnection(sessionKey)?.takeIf { it.isConnected() }
        if (connection == null && host.linkedConnectionId != null && !host.linksCloudInstance()) {
            val profile = app.database.connectionDao().getConnectionById(host.linkedConnectionId)
            if (profile != null) {
                connection = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    app.sshSessionManager.connectForMonitoring(profile)
                }
                if (connection != null) onOwnedConnection(profile.id)
            }
        }
        if (connection == null) {
            Logger.d(TAG, "No SSH session available for ${host.name} — skipping this cycle")
            return null
        }
        val runner = SshExecRunner { connection.jschSession() }
        val detector = TransportCapabilityDetector(app.database.containerHostDao())
        return when (val detected = detector.detect(host, runner)) {
            is ContainerResult.Success -> detected.value.transport.also(onOwnedTransport)
            else -> {
                Logger.w(TAG, "Transport detection failed for ${host.name} — skipping")
                null
            }
        }
    }

    /**
     * Check every container on [host] whose resolved settings enable checks, then notify
     * and (where resolved auto-apply is on) update. Returns true when a pass ran (advances
     * the per-host clock); false when the host was skipped this cycle.
     */
    private suspend fun checkHost(
        app: TabSSHApplication,
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        global: HostUpdateScanner.GlobalSettings,
        checker: UpdateChecker,
        applier: UpdateApplier
    ): Boolean {
        var ownedConnectionId: String? = null
        var ownTransport: ContainerTransport? = null
        val transport = acquireTransport(
            app, host, { ownedConnectionId = it }, { ownTransport = it }
        ) ?: return false

        try {
            val found = HostUpdateScanner(app.database.containerAutoUpdatePolicyDao(), checker)
                .scan(host, hostPolicies, global, transport)
                ?: return false
            notifyAndApply(host, found, applier, transport)
        } finally {
            // Only close what this worker opened — never the UI's session.
            try {
                ownTransport?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "transport close failed for ${host.name}: ${e.message}")
            }
            ownedConnectionId?.let { app.sshSessionManager.closeConnection(it) }
        }
        return true
    }

    /**
     * Notify once per standalone container / compose project, then apply: a standalone
     * container on its own, a compose project as one unit — and only when every updated
     * container in it resolves to auto-apply, since compose recreates the whole project.
     */
    private suspend fun notifyAndApply(
        host: ContainerHost,
        found: List<HostUpdateScanner.FoundUpdate>,
        applier: UpdateApplier,
        transport: ContainerTransport
    ) {
        val units = found.groupBy { it.project ?: "container:${it.policy.containerNameOrStackName}" }
        for ((_, members) in units) {
            val first = members.first()
            val label = first.project ?: first.policy.containerNameOrStackName
            // Dedup: the stored pending digest is the one already alerted on.
            if (members.any { it.policy.pendingUpdateDigest != it.result.registryDigest }) {
                NotificationHelper.notifyContainerUpdateAvailable(
                    appContext, first.policy.id, host.name, label,
                    if (members.size == 1) first.result.imageRef ?: label
                    else "${members.size} images"
                )
            }
            if (members.all { it.autoApply }) {
                applyUnattended(host, first.policy, label, applier, transport)
            }
        }
    }

    /** Run the unattended update for one unit and notify the outcome. */
    private suspend fun applyUnattended(
        host: ContainerHost,
        policy: ContainerAutoUpdatePolicy,
        label: String,
        applier: UpdateApplier,
        transport: ContainerTransport
    ) {
        Logger.i(TAG, "Auto-updating $label on ${host.name}")
        var failure: UpdateApplier.ApplyEvent.Failed? = null
        var completed = false
        try {
            applier.apply(policy.id, transport).collect { event ->
                when (event) {
                    is UpdateApplier.ApplyEvent.Completed -> completed = true
                    is UpdateApplier.ApplyEvent.Failed -> failure = event
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Unattended update threw for policy ${policy.id}", e)
        }
        val f = failure
        NotificationHelper.notifyContainerUpdateApplied(
            appContext, policy.id, host.name, label,
            success = completed,
            detail = f?.let {
                "${it.message} (at ${it.step}${if (it.rolledBack) ", rolled back" else ""})"
            }
        )
    }
}

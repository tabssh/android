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

/**
 * Periodic app-driven container image update checker.
 *
 * Every cycle (default 12 h — twice daily) the worker walks all enabled
 * [ContainerAutoUpdatePolicy] rows grouped by container host and, for each host
 * it can reach WITHOUT opening a new SSH session, runs an
 * [UpdateChecker.checkAll] pass: running digest (inspect RepoDigests) vs the
 * registry's current manifest digest. Each host carries its own override
 * (ContainerHost.updateCheckEnabled / updateCheckIntervalHours, gated by
 * [UpdateCheckGate]), and at most [MAX_CONCURRENT_HOSTS] hosts are checked
 * concurrently — fan-out is semaphore-bounded, never unbounded.
 *
 * ## Piggyback-only SSH
 *
 * Like [HostAvailabilityWorker]'s metrics tier, this worker never dials out
 * from the background: a host is only checked when its linked SSH connection
 * is already live (app open or [io.github.tabssh.services.SSHConnectionService]
 * running). Hosts without a live session are skipped silently and picked up
 * on a later cycle. Registry HTTPS calls are direct (no SSH needed).
 *
 * ## Outcomes
 *
 * - New pending update → [NotificationHelper.notifyContainerUpdateAvailable]
 *   plus the DB badge state (`pending_update_digest`, written by the
 *   checker). Deduped: an already-known pending digest never re-alerts.
 * - Policy has `autoRecreateOnUpdate` → [UpdateApplier] runs the unattended
 *   pull-and-recreate and the result is posted via
 *   [NotificationHelper.notifyContainerUpdateApplied].
 *
 * ## Battery gating
 *
 * Mirrors [HostAvailabilityWorker]: CONNECTED + not-low-battery constraints,
 * a validated-internet gate, the battery-saver opt-in pref, and the master
 * `container_update_check_enabled` switch.
 */
class ContainerUpdateCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ContainerUpdateCheckWorker"

        /** Unique work name used with [ExistingPeriodicWorkPolicy.KEEP]. */
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

        /**
         * Enqueue (or keep) the periodic worker. Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.KEEP] is idempotent.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ContainerUpdateCheckWorker>(
                INTERVAL_HOURS, TimeUnit.HOURS
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
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Logger.d(TAG, "Periodic container update check scheduled ($INTERVAL_HOURS h, network + not-low-battery)")
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

        // Gate 2: master update-check switch.
        if (!preferencesManager.isContainerUpdateCheckEnabled()) {
            Logger.d(TAG, "Skipping run: container_update_check_enabled = false")
            return Result.success()
        }

        val db = app.database
        val policies = db.containerAutoUpdatePolicyDao().getEnabledList()
        if (policies.isEmpty()) {
            Logger.d(TAG, "No enabled auto-update policies — nothing to check")
            return Result.success()
        }

        val checker = UpdateChecker(
            db.containerAutoUpdatePolicyDao(),
            db.registryCredentialDao(),
            RegistryClient()
        ) { credentialId -> RegistryCredentialStore.retrieve(appContext, credentialId) }
        val applier = UpdateApplier(db.containerAutoUpdatePolicyDao())

        // Bounded fan-out: at most MAX_CONCURRENT_HOSTS hosts in flight at
        // once; each host is gated by its per-host enable/interval override.
        val hostSemaphore = Semaphore(MAX_CONCURRENT_HOSTS)
        val now = System.currentTimeMillis()
        coroutineScope {
            for ((hostId, hostPolicies) in policies.groupBy { it.containerHostId }) {
                val host = db.containerHostDao().getById(hostId)
                if (host == null) {
                    Logger.w(TAG, "Policies reference missing container host $hostId — skipping")
                    continue
                }
                if (!UpdateCheckGate.isHostDue(
                        host.updateCheckEnabled, host.lastUpdateCheck,
                        host.updateCheckIntervalHours, INTERVAL_HOURS, now
                    )
                ) {
                    Logger.d(TAG, "Host ${host.name} not due (enabled=${host.updateCheckEnabled}) — skipping")
                    continue
                }
                launch {
                    hostSemaphore.withPermit {
                        val checked = checkHost(app, host, hostPolicies, checker, applier)
                        // Only a completed pass advances the per-host clock —
                        // a piggyback skip retries on the next cycle.
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
     * Check all [hostPolicies] on [host] over a piggybacked transport.
     * Reuses a cached UI ContainerSession when one is live; otherwise builds a
     * private transport on the existing SSH connection and closes it after.
     * Returns true when a check pass actually ran (advances the per-host
     * clock); false when the host was skipped this cycle.
     */
    private suspend fun checkHost(
        app: TabSSHApplication,
        host: ContainerHost,
        hostPolicies: List<ContainerAutoUpdatePolicy>,
        checker: UpdateChecker,
        applier: UpdateApplier
    ): Boolean {
        // Prefer the UI's cached session — same relay, zero extra setup.
        val cached = ContainerSessionManager.cached(host.id)
        var ownTransport: ContainerTransport? = null
        val transport: ContainerTransport = cached?.transport ?: run {
            // Custom-endpoint hosts key their session on the ephemeral
            // profile id; the piggyback-only rule below applies unchanged.
            val sessionKey = host.linkedConnectionId
                ?: host.takeIf { it.usesCustomEndpoint() }?.ephemeralProfileId()
                ?: run {
                    Logger.w(TAG, "Host ${host.name} has no linked SSH connection — skipping")
                    return false
                }
            // Piggyback-only: never open a new SSH session from the background.
            val connection = app.sshSessionManager.getConnection(sessionKey)
                ?.takeIf { it.isConnected() }
                ?: run {
                    Logger.d(TAG, "No live SSH session for ${host.name} — skipping this cycle")
                    return false
                }
            val runner = SshExecRunner { connection.jschSession() }
            val detector = TransportCapabilityDetector(app.database.containerHostDao())
            when (val detected = detector.detect(host, runner)) {
                is ContainerResult.Success -> detected.value.transport.also { ownTransport = it }
                else -> {
                    Logger.w(TAG, "Transport detection failed for ${host.name} — skipping")
                    return false
                }
            }
        }

        try {
            for (policy in hostPolicies) {
                val result = checker.checkOne(policy, transport)
                if (result.status != UpdateChecker.Status.UPDATE_AVAILABLE) continue

                // Dedup: the stored pending digest is the one we already
                // alerted on — only a digest we have not seen pending re-alerts.
                val isNew = policy.pendingUpdateDigest != result.registryDigest
                if (isNew) {
                    NotificationHelper.notifyContainerUpdateAvailable(
                        appContext, policy.id, host.name,
                        policy.containerNameOrStackName,
                        result.imageRef ?: policy.containerNameOrStackName
                    )
                }

                if (policy.autoRecreateOnUpdate) {
                    applyUnattended(host, policy, applier, transport)
                }
            }
        } finally {
            // Only close what this worker opened — never the UI's session.
            try {
                ownTransport?.close()
            } catch (e: Exception) {
                Logger.w(TAG, "transport close failed for ${host.name}: ${e.message}")
            }
        }
        return true
    }

    /** Run the unattended pull-and-recreate for one policy and notify. */
    private suspend fun applyUnattended(
        host: ContainerHost,
        policy: ContainerAutoUpdatePolicy,
        applier: UpdateApplier,
        transport: ContainerTransport
    ) {
        Logger.i(TAG, "Auto-recreating ${policy.containerNameOrStackName} on ${host.name}")
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
            Logger.e(TAG, "Unattended recreate threw for policy ${policy.id}", e)
        }
        val f = failure
        NotificationHelper.notifyContainerUpdateApplied(
            appContext, policy.id, host.name, policy.containerNameOrStackName,
            success = completed,
            detail = f?.let {
                "${it.message} (at ${it.step}${if (it.rolledBack) ", rolled back" else ""})"
            }
        )
    }
}

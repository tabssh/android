package io.github.tabssh.background

import android.content.Context
import android.os.PowerManager
import androidx.preference.PreferenceManager
import androidx.work.*
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.utils.NotificationHelper
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Battery-aware background host availability monitor.
 *
 * Runs as a [PeriodicWorkRequest] via WorkManager; Android Doze mode,
 * battery-saver constraints, and the 15-minute minimum interval are all
 * handled by the OS, not by us.
 *
 * ## Two-tier check model
 *
 * 1. **TCP probe** (always): opens a TCP socket to `host:port` with a 5 s
 *    timeout. No SSH handshake, no credentials needed — just checks whether
 *    the port is accepting connections. Cost: one TCP SYN per host.
 *
 * 2. **SSH metrics check** (opt-in per slot): when [MonitorSlot.enablePerformanceChecks]
 *    is true, the worker reuses any already-open [SSHConnection] from the
 *    session manager and runs a [MetricsCollector] pass to evaluate CPU/
 *    memory/disk/load thresholds. It never opens new SSH sessions from the
 *    background — only piggybacks on live ones.  This means metric alerts
 *    only fire while the user has the app open or [SSHConnectionService] is
 *    running; they won't drain battery by establishing connections in the
 *    dark.
 *
 * ## Battery gating
 *
 * - [WorkManager] constraints: `CONNECTED` network + `requiresBatteryNotLow`.
 * - If [PowerManager.isPowerSaveMode] is true and the user hasn't opted into
 *   "run in battery saver" (pref `monitoring_run_in_battery_saver`), the
 *   worker exits early with [Result.success] so WorkManager backs off
 *   gracefully and re-schedules normally.
 *
 * ## Notification behaviour
 *
 * - First failure after a success: fires [NotificationHelper.notifyHostDown].
 * - Consecutive failures: suppressed until [MonitorSlot.alertCooldownMinutes]
 *   have elapsed, then fires [NotificationHelper.notifyHostStillDown].
 * - Recovery: fires [NotificationHelper.notifyHostRecovered] on the first
 *   successful probe after at least one failure.
 */
class HostAvailabilityWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "HostAvailabilityWorker"

        /** Unique work name used with [ExistingPeriodicWorkPolicy.KEEP]. */
        const val WORK_NAME = "host_availability_check"

        /** TCP probe timeout in milliseconds. */
        private const val TCP_TIMEOUT_MS = 5_000

        /** Maximum concurrent TCP probes. Limits open file descriptors and ensures
         *  the worker finishes quickly even for large fleets (e.g., 300 hosts at
         *  30 concurrent = ~10 batches × avg ~100ms = well under 5 seconds for
         *  reachable hosts; worst-case all-down = 10 × 5 s = 50 s, not 25 min). */
        private const val MAX_PARALLEL_PROBES = 30

        /**
         * Enqueue (or keep) the periodic worker. Safe to call multiple times —
         * [ExistingPeriodicWorkPolicy.KEEP] is idempotent.
         *
         * The minimum periodic interval enforced by WorkManager is 15 minutes,
         * which is exactly what we want for battery-conscious monitoring.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<HostAvailabilityWorker>(
                15, TimeUnit.MINUTES
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
            Logger.d(TAG, "Periodic availability check scheduled (15 min, network + not-low-battery)")
        }

        /** Cancel the periodic worker (e.g., when master monitoring toggle is off). */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Logger.d(TAG, "Periodic availability check cancelled")
        }
    }

    override suspend fun doWork(): Result {
        // Gate 1: battery saver mode.
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            if (!prefs.getBoolean("monitoring_run_in_battery_saver", false)) {
                Logger.d(TAG, "Skipping run: battery saver active and pref not set")
                return Result.success()
            }
        }

        // Gate 2: master monitoring switch.
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (!prefs.getBoolean("monitoring_enabled", true)) {
            Logger.d(TAG, "Skipping run: monitoring_enabled = false")
            return Result.success()
        }

        val app = TabSSHApplication.get()
        val db = app.database
        val allSlots = db.monitorSlotDao().getEnabledSlots()

        if (allSlots.isEmpty()) {
            Logger.d(TAG, "No enabled monitor slots — nothing to check")
            return Result.success()
        }

        // Per-slot interval gating: skip slots that were checked recently enough.
        // The global worker fires every 15 min; slots configured at 30 or 60 min
        // are skipped on runs that fall within their interval window.
        val now = System.currentTimeMillis()
        val dueSlots = allSlots.filter { it.isDue(now) }

        if (dueSlots.isEmpty()) {
            Logger.d(TAG, "All ${allSlots.size} slot(s) checked recently — nothing due")
            return Result.success()
        }

        Logger.i(TAG, "Checking ${dueSlots.size}/${allSlots.size} monitored host(s) (${allSlots.size - dueSlots.size} skipped, interval not elapsed)")

        // Parallel TCP probes bounded by MAX_PARALLEL_PROBES.
        // supervisorScope ensures one probe failure does not cancel others.
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        supervisorScope {
            dueSlots.map { slot ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val profile = db.connectionDao().getConnectionById(slot.connectionId)
                        if (profile == null) {
                            Logger.w(TAG, "Slot ${slot.id}: profile ${slot.connectionId} not found, skipping")
                            return@withPermit
                        }

                        val probeTime = System.currentTimeMillis()
                        val isReachable = tcpProbe(profile.host, profile.port)
                        val wasDown = slot.isCurrentlyDown

                        if (!isReachable) {
                            val firstFailure = !wasDown
                            val cooldownMs = slot.alertCooldownMinutes * 60_000L
                            val cooldownExpired = probeTime - slot.lastNotifiedDownAt > cooldownMs

                            val stampDown = (firstFailure || cooldownExpired) && slot.alertOnDown
                            db.monitorSlotDao().updateProbeResult(
                                slotId = slot.id,
                                now = probeTime,
                                isDown = true,
                                stampDown = stampDown,
                                stampUp = false
                            )

                            if (slot.alertOnDown) {
                                when {
                                    firstFailure -> {
                                        Logger.i(TAG, "${profile.host}: went down (first failure)")
                                        NotificationHelper.notifyHostDown(appContext, profile)
                                    }
                                    cooldownExpired -> {
                                        val failures = slot.consecutiveFailures + 1
                                        Logger.i(TAG, "${profile.host}: still down ($failures failures)")
                                        NotificationHelper.notifyHostStillDown(appContext, profile, failures)
                                    }
                                }
                            }
                        } else {
                            val justRecovered = wasDown

                            db.monitorSlotDao().updateProbeResult(
                                slotId = slot.id,
                                now = probeTime,
                                isDown = false,
                                stampDown = false,
                                stampUp = justRecovered && slot.alertOnRecovery
                            )

                            if (justRecovered && slot.alertOnRecovery) {
                                Logger.i(TAG, "${profile.host}: recovered")
                                NotificationHelper.notifyHostRecovered(appContext, profile)
                            }

                            // Optional SSH metrics check — only if user opted in AND a
                            // live session already exists (no new connections from background).
                            if (slot.enablePerformanceChecks) {
                                checkMetrics(app, slot.copy(
                                    lastCheckedAt = probeTime,
                                    isCurrentlyDown = false,
                                    consecutiveFailures = 0
                                ), profile)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        Logger.d(TAG, "Availability check complete")
        return Result.success()
    }

    /** TCP connect to [host]:[port]; returns true if port accepts the connection. */
    private suspend fun tcpProbe(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Piggyback SSH metrics check on an already-open session.
     * Never opens a new SSH connection — if no live session exists, returns.
     */
    private suspend fun checkMetrics(
        app: TabSSHApplication,
        slot: io.github.tabssh.storage.database.entities.MonitorSlot,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile
    ) {
        val ssh = app.sshSessionManager.getConnection(slot.connectionId) ?: return
        if (!ssh.isConnected()) return

        val result = runCatching { MetricsCollector(ssh).collectMetrics() }
        val metrics = result.getOrNull()?.getOrNull() ?: return

        // CPU threshold — uses 5-minute load average normalised by core count so
        // momentary spikes don't trigger alerts. load5min / coreCount × 100 gives
        // "what fraction of total CPU capacity was busy on average over 5 minutes."
        slot.cpuThreshold?.let { threshold ->
            val cores = metrics.cpuUsage.coreCount.coerceAtLeast(1)
            val value = (metrics.loadAverage.load5min / cores) * 100f
            if (value > threshold) {
                NotificationHelper.notifyMetricThreshold(
                    appContext, profile, "CPU (5 min avg)",
                    "%.0f%%".format(value), "$threshold%"
                )
            }
        }

        // Memory threshold
        slot.memoryThreshold?.let { threshold ->
            val value = metrics.memoryUsage.usedPercent
            if (value > threshold) {
                NotificationHelper.notifyMetricThreshold(
                    appContext, profile, "Memory",
                    "%.0f%%".format(value), "$threshold%"
                )
            }
        }

        // Disk threshold
        slot.diskThreshold?.let { threshold ->
            val value = metrics.diskUsage.usedPercent
            if (value > threshold) {
                NotificationHelper.notifyMetricThreshold(
                    appContext, profile, "Disk",
                    "%.0f%%".format(value), "$threshold%"
                )
            }
        }

        // Load average threshold
        slot.loadThreshold?.let { threshold ->
            val value = metrics.loadAverage.load1min
            if (value > threshold) {
                NotificationHelper.notifyMetricThreshold(
                    appContext, profile, "Load",
                    "%.2f".format(value), "%.2f".format(threshold)
                )
            }
        }
    }
}

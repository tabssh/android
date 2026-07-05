package io.github.tabssh.background

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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

    /**
     * Returns true only when the active network has been validated by Android
     * (i.e., real internet is reachable, not just a connected Wi-Fi interface).
     *
     * WorkManager's [NetworkType.CONNECTED] constraint fires whenever any
     * network interface is up — including captive-portal Wi-Fi and interfaces
     * with no upstream route.  [NetworkCapabilities.NET_CAPABILITY_VALIDATED]
     * (API 23+) reflects Android's own internet validation probe result and is
     * the correct signal to use here.  On API 21-22 we fall back to the legacy
     * [ConnectivityManager.activeNetworkInfo].
     */
    @Suppress("DEPRECATION")
    private fun isInternetAvailable(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    override suspend fun doWork(): Result {
        // Gate 0: validated internet access.  WorkManager's CONNECTED constraint
        // only guarantees a network interface is up — not that the internet is
        // reachable.  A Wi-Fi-only device on a network with no upstream route
        // (guest network, captive portal, router without WAN) would pass the
        // constraint but every TCP probe would fail, flooding the user with
        // "host down" alerts for unreachable servers that are actually fine.
        if (!isInternetAvailable()) {
            Logger.d(TAG, "Skipping run: no validated internet connection")
            return Result.success()
        }

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

        // Gate 3: global notification default.
        // When false, all monitoring notifications are suppressed regardless of
        // per-host alertOnDown / alertOnRecovery flags. Per-host flags still take
        // effect when this is true — they can suppress individual hosts below the
        // global level, but cannot enable notifications above it.
        // (Group-level notification settings are a planned future addition that
        // will sit between the global default and the per-host flag in precedence.)
        val globalNotificationsEnabled = prefs.getBoolean("monitoring_notifications_enabled", true)

        // Dismiss any stale "monitoring suspended — no network" notification from
        // a previous run that detected an outage. We're past the internet gate,
        // so connectivity is restored and the stale row should disappear.
        NotificationHelper.cancelNetworkDownNotification(appContext)

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

        // Set to true if any probe failure was caused by the device losing internet
        // mid-run rather than by the remote host going down. Used after awaitAll()
        // to post a single "monitoring suspended" notification instead of one alert
        // per host — WiFi off → 8 spam alerts is the problem this solves.
        val networkWentDown = java.util.concurrent.atomic.AtomicBoolean(false)

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
                            // Re-check internet before attributing the failure to the host.
                            // WiFi turning off mid-run causes ALL TCP probes to fail at once;
                            // that's a network event, not a host-down event. Suppress per-host
                            // alerts and let the caller post a single "monitoring suspended"
                            // notification instead of flooding the user with one alert per host.
                            if (!isInternetAvailable()) {
                                networkWentDown.set(true)
                                Logger.i(TAG, "${profile.host}: probe failed — no internet, suppressing per-host alert")
                                return@withPermit
                            }

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

                            // Notification precedence: global default → per-host flag.
                            // (Group-level setting is a planned future addition between
                            // these two tiers.) Both must be true for a notification to fire.
                            if (globalNotificationsEnabled && slot.alertOnDown) {
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

                            // Notification precedence: global default → per-host flag.
                            if (globalNotificationsEnabled && justRecovered && slot.alertOnRecovery) {
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
                                ), profile, globalNotificationsEnabled)
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        // If one or more probes were skipped because the device lost internet
        // mid-run, post the single consolidated notification now. All per-host
        // alerts were suppressed above; this one replaces them.
        if (networkWentDown.get()) {
            Logger.i(TAG, "Network outage detected during probe run — posting consolidated notification")
            NotificationHelper.postNetworkDownNotification(appContext)
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
     *
     * [notificationsEnabled] is the resolved global default; threshold alerts
     * are skipped entirely when false, regardless of per-slot threshold config.
     */
    private suspend fun checkMetrics(
        app: TabSSHApplication,
        slot: io.github.tabssh.storage.database.entities.MonitorSlot,
        profile: io.github.tabssh.storage.database.entities.ConnectionProfile,
        notificationsEnabled: Boolean
    ) {
        val ssh = app.sshSessionManager.getConnection(slot.connectionId) ?: return
        if (!ssh.isConnected()) return

        val result = runCatching { MetricsCollector(ssh).collectMetrics() }
        val metrics = result.getOrNull()?.getOrNull() ?: return

        if (!notificationsEnabled) return

        // Resolve effective thresholds: per-host value when set; global Settings default otherwise.
        val globalPrefs   = PreferenceManager.getDefaultSharedPreferences(appContext)
        val effectiveCpu  = slot.cpuThreshold  ?: globalPrefs.getInt("monitoring_default_cpu_threshold",    85)
        val effectiveMem  = slot.memoryThreshold ?: globalPrefs.getInt("monitoring_default_memory_threshold", 90)
        val effectiveDisk = slot.diskThreshold  ?: globalPrefs.getInt("monitoring_default_disk_threshold",   80)

        // CPU threshold — uses 5-minute load average normalised by core count so
        // momentary spikes don't trigger alerts. load5min / coreCount × 100 gives
        // "what fraction of total CPU capacity was busy on average over 5 minutes."
        val cores    = metrics.cpuUsage.coreCount.coerceAtLeast(1)
        val cpuValue = (metrics.loadAverage.load5min / cores) * 100f
        if (cpuValue > effectiveCpu) {
            NotificationHelper.notifyMetricThreshold(
                appContext, profile, "CPU (5 min avg)",
                "%.0f%%".format(cpuValue), "$effectiveCpu%"
            )
        }

        // Memory threshold
        val memValue = metrics.memoryUsage.usedPercent
        if (memValue > effectiveMem) {
            NotificationHelper.notifyMetricThreshold(
                appContext, profile, "Memory",
                "%.0f%%".format(memValue), "$effectiveMem%"
            )
        }

        // Disk threshold
        val diskValue = metrics.diskUsage.usedPercent
        if (diskValue > effectiveDisk) {
            NotificationHelper.notifyMetricThreshold(
                appContext, profile, "Disk",
                "%.0f%%".format(diskValue), "$effectiveDisk%"
            )
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

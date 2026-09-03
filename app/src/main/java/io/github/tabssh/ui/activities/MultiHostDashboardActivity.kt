package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityMultiHostDashboardBinding
import io.github.tabssh.databinding.DialogDashboardMonitorConfigBinding
import io.github.tabssh.databinding.ItemDashboardEmptyBinding
import io.github.tabssh.databinding.ItemDashboardGroupHeaderBinding
import io.github.tabssh.databinding.ItemDashboardHostCardBinding
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.performance.PerformanceMetrics
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.background.BatteryOptimizationHelper
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.ui.dialogs.DialogFields
import io.github.tabssh.utils.Format
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.storage.preferences.PreferenceManager as TabPreferenceManager
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import io.github.tabssh.utils.tabSSHApp

/**
 * Multi-host monitoring dashboard.
 *
 * ## Dashboard groups vs connection groups
 *
 * Dashboard groups are a UI-only concept — they are stored in SharedPreferences
 * as JSON and are entirely independent of the [ConnectionGroup] database entities
 * used to organise the main connection list.  A host can be in one connection
 * group (for the connection list) and in a completely different dashboard group
 * (for this screen), with no coupling between the two.
 *
 * ## Group storage
 *
 * - `dash_groups_json` → `[{id, name, order, collapsed}, …]` (JSON array)
 * - `dash_hosts_<groupId>` → comma-separated connection IDs for that group
 * - `dash_hosts___ungrouped__` → connection IDs not assigned to any named group
 *
 * ## Metrics
 *
 * Each selected host gets its own [SSHConnection] + [MetricsCollector] pump
 * running in [pumpScope].  Metrics update every 5 s while the activity is
 * visible; owned sessions are disconnected in [onDestroy].
 */
class MultiHostDashboardActivity : TabSSHActivity() {

    // ── Data model ────────────────────────────────────────────────────────────

    /** A named group on the dashboard — independent of ConnectionGroup in the DB. */
    data class DashboardGroup(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val order: Int = 0,
        val collapsed: Boolean = false
    )

    /** Averaged metric snapshot across all hosts in a group that have data. */
    data class GroupAggMetrics(
        val avgCpu: Int,
        val avgMem: Int,
        val avgDisk: Int,
        val avgLoad1: Int,
        val avgLoad5: Int,
        val avgLoad15: Int
    )

    sealed class DashboardItem {
        /** Header row for a named dashboard group. */
        data class GroupHeader(
            val group: DashboardGroup,
            val memberCount: Int,
            val aggMetrics: GroupAggMetrics? = null
        ) : DashboardItem()
        /** Header row for the "Ungrouped" pseudo-group. */
        data class UngroupedHeader(val count: Int, val collapsed: Boolean) : DashboardItem()
        /** One host card row. */
        data class Host(
            val profile: ConnectionProfile,
            /** null = ungrouped. */
            val groupId: String?
        ) : DashboardItem()
        /** Shown when the dashboard has no hosts, or when the last load failed
         *  ([errorMessage] non-null) — kept visually distinct from a genuine empty
         *  list so a load failure never reads as "nothing here yet". */
        data class EmptyState(val errorMessage: String? = null) : DashboardItem()
    }

    companion object {
        private const val TAG = "MultiHostDash"

        const val UNGROUPED_ID = "__ungrouped__"
        private const val PREF_FILE       = "multi_host_dashboard"
        private const val KEY_GROUPS      = "dash_groups_json"
        private const val KEY_HOSTS_PFX   = "dash_hosts_"
        private const val REFRESH_MS      = 5_000L

        /** Max simultaneous SSH handshakes. A full SSH connect (key exchange +
         *  auth) is CPU- and network-heavy; blasting all hosts at once causes
         *  some to time out while others succeed. 5 concurrent handshakes
         *  keeps the network stack comfortable even on cellular. */
        private const val MAX_CONCURRENT_CONNECTS = 10

        /** Initial backoff after a failed connect attempt (ms). */
        private const val CONNECT_BACKOFF_INITIAL_MS = 5_000L
        /** Maximum backoff between retries (ms). */
        private const val CONNECT_BACKOFF_MAX_MS     = 60_000L

        private const val VT_GROUP_HEADER    = 0
        private const val VT_UNGROUPED_HDR   = 1
        private const val VT_HOST            = 2
        private const val VT_EMPTY           = 3

        /** Check-interval spinner values in minutes — parallel to the option labels. */
        private val INTERVAL_MINUTES = intArrayOf(15, 30, 60, 240, 720)
        /** Alert-cooldown spinner values in minutes — parallel to the option labels. */
        private val COOLDOWN_MINUTES = intArrayOf(15, 30, 60, 240, 1440)

        /**
         * Inflate and pre-fill the shared monitor-config form
         * (dialog_dashboard_monitor_config.xml) used by both the per-host and
         * the per-group dialogs.
         */
        private fun inflateMonitorForm(
            context: Context,
            enabled: Boolean,
            alertOnDown: Boolean,
            alertOnRecovery: Boolean,
            intervalMinutes: Int,
            cooldownMinutes: Int,
            perfChecks: Boolean,
            cpuThreshold: Int?,
            memoryThreshold: Int?,
            diskThreshold: Int?
        ): DialogDashboardMonitorConfigBinding {
            val b = DialogDashboardMonitorConfigBinding.inflate(LayoutInflater.from(context))
            b.cbEnabled.isChecked       = enabled
            b.cbAlertDown.isChecked     = alertOnDown
            b.cbAlertRecovery.isChecked = alertOnRecovery
            b.cbPerfChecks.isChecked    = perfChecks

            fun makeAdapter(labels: Array<String>) =
                ArrayAdapter(context, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
            b.spinnerInterval.adapter = makeAdapter(arrayOf(
                context.getString(R.string.dashboard_duration_15min),
                context.getString(R.string.dashboard_duration_30min),
                context.getString(R.string.dashboard_duration_1h),
                context.getString(R.string.dashboard_duration_4h),
                context.getString(R.string.dashboard_duration_12h)
            ))
            b.spinnerInterval.setSelection(
                INTERVAL_MINUTES.indexOfFirst { it >= intervalMinutes }.coerceAtLeast(0)
            )
            b.spinnerCooldown.adapter = makeAdapter(arrayOf(
                context.getString(R.string.dashboard_duration_15min),
                context.getString(R.string.dashboard_duration_30min),
                context.getString(R.string.dashboard_duration_1h),
                context.getString(R.string.dashboard_duration_4h),
                context.getString(R.string.dashboard_duration_24h)
            ))
            b.spinnerCooldown.setSelection(
                COOLDOWN_MINUTES.indexOfFirst { it >= cooldownMinutes }.coerceAtLeast(0)
            )

            // Read global threshold defaults so the form pre-fills with them when no override is set
            val globalPrefs = TabPreferenceManager(context)
            bindThreshold(context, b.tvCpuThresholdLabel, b.tvCpuValue, b.sbCpu,
                R.string.dashboard_cpu_threshold_label_fmt,
                globalPrefs.getInt("monitoring_default_cpu_threshold", 85), cpuThreshold)
            bindThreshold(context, b.tvMemThresholdLabel, b.tvMemValue, b.sbMem,
                R.string.dashboard_memory_threshold_label_fmt,
                globalPrefs.getInt("monitoring_default_memory_threshold", 90), memoryThreshold)
            bindThreshold(context, b.tvDiskThresholdLabel, b.tvDiskValue, b.sbDisk,
                R.string.dashboard_disk_threshold_label_fmt,
                globalPrefs.getInt("monitoring_default_disk_threshold", 80), diskThreshold)
            return b
        }

        /**
         * Wire one threshold section (label + value readout + SeekBar).
         * Progress 0 = "use global default" (matches the label and what Save
         * stores) — starting the thumb at the global value made a plain Save
         * silently persist it as an explicit override.
         */
        private fun bindThreshold(
            context: Context,
            label: TextView,
            value: TextView,
            seek: SeekBar,
            labelRes: Int,
            globalDefault: Int,
            current: Int?
        ) {
            label.text = context.getString(labelRes, globalDefault)
            fun render(v: Int) {
                value.text = if (v == 0) context.getString(R.string.dashboard_global_default_pct_fmt, globalDefault)
                             else context.getString(R.string.dashboard_percent_fmt, v)
            }
            seek.progress = current?.takeIf { it > 0 } ?: 0
            render(seek.progress)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) = render(v)
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        /**
         * Show a monitor-configuration dialog for [profile].
         *
         * Exposed as a companion fun so [HostDetailActivity] can reuse it
         * without duplicating the dialog logic.
         *
         * @param existing  The current [MonitorSlot] for this profile, or null
         *                  if no slot has been created yet.
         * @param onSaved   Called with the updated (or newly created) slot after
         *                  the user confirms.  The slot is already written to the
         *                  database before this callback fires.
         */
        fun showMonitorConfigDialog(
            context: Context,
            profile: ConnectionProfile,
            existing: MonitorSlot?,
            onSaved: (MonitorSlot) -> Unit = {}
        ) {
            val app  = TabSSHApplication.get()
            val slot = existing ?: MonitorSlot(connectionId = profile.id)
            val form = inflateMonitorForm(
                context,
                enabled         = slot.enabled,
                alertOnDown     = slot.alertOnDown,
                alertOnRecovery = slot.alertOnRecovery,
                intervalMinutes = slot.checkIntervalMinutes,
                cooldownMinutes = slot.alertCooldownMinutes,
                perfChecks      = slot.enablePerformanceChecks,
                cpuThreshold    = slot.cpuThreshold,
                memoryThreshold = slot.memoryThreshold,
                diskThreshold   = slot.diskThreshold
            )

            MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.dashboard_monitor_title_fmt, profile.getDisplayName()))
                .setView(form.root)
                .setPositiveButton(context.getString(R.string.save)) { _, _ ->
                    val updated = slot.copy(
                        enabled                 = form.cbEnabled.isChecked,
                        alertOnDown             = form.cbAlertDown.isChecked,
                        alertOnRecovery         = form.cbAlertRecovery.isChecked,
                        checkIntervalMinutes    = INTERVAL_MINUTES[form.spinnerInterval.selectedItemPosition],
                        alertCooldownMinutes    = COOLDOWN_MINUTES[form.spinnerCooldown.selectedItemPosition],
                        enablePerformanceChecks = form.cbPerfChecks.isChecked,
                        cpuThreshold            = form.sbCpu.progress.takeIf { it > 0 },
                        memoryThreshold         = form.sbMem.progress.takeIf { it > 0 },
                        diskThreshold           = form.sbDisk.progress.takeIf { it > 0 },
                        modifiedAt              = System.currentTimeMillis()
                    )
                    app.applicationScope.launch(Dispatchers.IO) {
                        app.database.monitorSlotDao().insertOrReplace(updated)
                        withContext(Dispatchers.Main) {
                            onSaved(updated)
                            // If the user just enabled monitoring, check battery
                            // optimization so alerts actually arrive when the
                            // app is closed. Show once per save — not on disabling.
                            if (updated.enabled) {
                                BatteryOptimizationHelper.requestExemptionIfNeeded(context) {
                                    // Already exempt — check for OEM restrictions
                                    BatteryOptimizationHelper.showManufacturerGuidanceIfNeeded(context)
                                }
                            }
                        }
                    }
                }
                .setNeutralButton(context.getString(R.string.dashboard_remove)) { _, _ ->
                    if (existing != null) {
                        app.applicationScope.launch(Dispatchers.IO) {
                            app.database.monitorSlotDao().delete(existing)
                            // H6 — record the deletion so it propagates and is not resurrected.
                            TombstoneRecorder.record(app, TombstoneRecorder.MONITOR_SLOT, existing.id)
                            withContext(Dispatchers.Main) { onSaved(existing.copy(enabled = false)) }
                        }
                    }
                }
                .setNegativeButton(context.getString(R.string.cancel), null)
                .show()
                .apply {
                    // Remove is destructive — tint it error red so it can't be
                    // mistaken for Cancel/Save (M3 colors all dialog buttons alike)
                    getButton(android.content.DialogInterface.BUTTON_NEUTRAL)
                        ?.setTextColor(ContextCompat.getColor(context, R.color.status_error))
                }
        }
    }

    // ── Activity state ────────────────────────────────────────────────────────

    private lateinit var app: TabSSHApplication
    private lateinit var binding: ActivityMultiHostDashboardBinding

    /** Named dashboard groups, ordered by [DashboardGroup.order]. */
    private val dashboardGroups = mutableListOf<DashboardGroup>()

    /** groupId → set of connection IDs. UNGROUPED_ID key = the ungrouped bucket. */
    private val groupHosts = mutableMapOf<String, MutableSet<String>>()

    /** connectionId → last-received metrics snapshot (null while loading). */
    private val metricsMap = mutableMapOf<String, PerformanceMetrics?>()

    /** connectionId → error string shown instead of metrics. */
    private val errorMap   = mutableMapOf<String, String?>()

    /** connectionId → MonitorSlot (loaded lazily; refreshed after config dialog). */
    private val monitorSlots = mutableMapOf<String, MonitorSlot>()

    /** Cached profile objects so adapter view holders can re-bind without a DB hit. */
    private val profileCache = mutableMapOf<String, ConnectionProfile>()

    /** Whether the "Ungrouped" header is collapsed. */
    private var ungroupedCollapsed = false

    /** Set when the last profile load failed; shown via [DashboardItem.EmptyState]
     *  instead of silently rendering the same view as a genuine empty dashboard. */
    private var lastLoadError: String? = null

    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs          = mutableMapOf<String, Job>()
    private val ownedSessions = mutableMapOf<String, SSHConnection>()

    /** Limits simultaneous SSH handshakes so we don't overwhelm the network
     *  stack when the dashboard loads many hosts at once. */
    private val connectSemaphore = Semaphore(MAX_CONCURRENT_CONNECTS)

    private lateinit var adapter: DashboardAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = tabSSHApp
        binding = ActivityMultiHostDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setTitle(R.string.multi_host_dashboard_title)

        adapter = DashboardAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabNewGroup.setOnClickListener { showAddGroupDialog() }

        // Offline indicator — HostAvailabilityWorker already suspends probes
        // when the phone has no validated internet (Gate 0), so a lack of
        // network was silently making every card look stale. Surface it
        // instead so the user knows checks are paused and not that hosts
        // are down.
        lifecycleScope.launch {
            app.networkDetector.networkState.collect { state ->
                binding.offlineBanner.visibility =
                    if (state.isConnected) View.GONE else View.VISIBLE
            }
        }

        loadPersistedState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.menu_add_hosts  -> { showHostPicker(targetGroupId = UNGROUPED_ID); true }
        else                 -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
        // Blocking disconnect — JSch teardown is blocking I/O; runBlocking keeps
        // the teardown synchronous so the Activity is fully cleaned up before the
        // OS reclaims it, without blocking the UI earlier in the lifecycle.
        runBlocking {
            ownedSessions.values.forEach {
                try { it.disconnect() } catch (e: Exception) {
                    Logger.w(TAG, "onDestroy disconnect: ${e.message}")
                }
            }
        }
        ownedSessions.clear()
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private val prefs get() = getSharedPreferences(PREF_FILE, MODE_PRIVATE)

    private fun saveGroups() {
        val arr = JSONArray()
        dashboardGroups.forEachIndexed { i, g ->
            arr.put(JSONObject().apply {
                put("id",        g.id)
                put("name",      g.name)
                put("order",     i)
                put("collapsed", g.collapsed)
            })
        }
        prefs.edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    private fun loadGroups(): MutableList<DashboardGroup> {
        val json = prefs.getString(KEY_GROUPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                DashboardGroup(
                    id        = o.getString("id"),
                    name      = o.getString("name"),
                    order     = o.optInt("order", it),
                    collapsed = o.optBoolean("collapsed", false)
                )
            }.sortedBy { it.order }.toMutableList()
        } catch (e: Exception) {
            Logger.w(TAG, "loadGroups parse error: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveGroupHosts(groupId: String) {
        val csv = groupHosts[groupId]?.joinToString(",") ?: ""
        prefs.edit().putString(KEY_HOSTS_PFX + groupId, csv).apply()
    }

    private fun loadGroupHosts(groupId: String): MutableSet<String> {
        val csv = prefs.getString(KEY_HOSTS_PFX + groupId, "") ?: ""
        return csv.split(",").filter { it.isNotBlank() }.toMutableSet()
    }

    private fun loadPersistedState() {
        dashboardGroups.clear()
        dashboardGroups.addAll(loadGroups())

        groupHosts.clear()
        dashboardGroups.forEach { g -> groupHosts[g.id] = loadGroupHosts(g.id) }
        groupHosts[UNGROUPED_ID] = loadGroupHosts(UNGROUPED_ID)

        ungroupedCollapsed = prefs.getBoolean("dash_ungrouped_collapsed", false)

        val allIds = allHostIds()
        if (allIds.isEmpty()) {
            rebuildAndSubmit()
            return
        }

        lifecycleScope.launch {
            var loadFailed = false
            val profiles = withContext(Dispatchers.IO) {
                try {
                    app.database.connectionDao().getAllConnectionsList()
                        .filter { it.id in allIds }
                } catch (e: Exception) {
                    Logger.e(TAG, "loadProfiles failed", e)
                    loadFailed = true
                    emptyList()
                }
            }
            if (loadFailed) {
                // Do not treat a DB read failure as "every host was deleted" —
                // surface the error instead of wiping groups on a transient error.
                lastLoadError = getString(R.string.load_state_error_generic)
                rebuildAndSubmit()
                return@launch
            }
            lastLoadError = null
            profiles.forEach { profileCache[it.id] = it }

            // Ids not backed by a saved connection may be registry-backed
            // (cloud instance / container host) — resolve them through the
            // shared resolver. The resolver guarantees profile.id == registry
            // id so all dashboard maps stay on one id space. A registry row
            // that exists but fails to resolve right now (offline, expired
            // token) is kept in its group — never treated as deleted.
            val registryBacked = mutableSetOf<String>()
            for (id in allIds - profiles.map { it.id }.toSet()) {
                val row = withContext(Dispatchers.IO) {
                    try { app.database.connectableHostDao().getById(id) }
                    catch (e: Exception) { Logger.w(TAG, "registry lookup failed for $id", e); null }
                } ?: continue
                registryBacked.add(id)
                val resolved = try {
                    io.github.tabssh.storage.registry.ConnectableHostResolver.resolveProfile(app, row)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "resolveProfile failed for ${row.name}", e)
                    null
                }
                if (resolved != null) profileCache[id] = resolved
            }

            // Load monitor slots
            withContext(Dispatchers.IO) {
                allIds.filter { it in profileCache }.forEach { id ->
                    app.database.monitorSlotDao().getByConnectionId(id)?.let {
                        monitorSlots[id] = it
                    }
                }
            }

            // Remove stale IDs (source row was deleted from both the
            // connections table and the registry)
            val validIds = profiles.map { it.id }.toSet() + registryBacked
            val staleIds = allIds - validIds
            staleIds.forEach { removeHostFromAllGroups(it) }

            // Start metric pumps
            allIds.mapNotNull { profileCache[it] }.forEach { startPumpIfNeeded(it) }
            rebuildAndSubmit()
        }
    }

    // ── Group CRUD ────────────────────────────────────────────────────────────

    private fun showAddGroupDialog() {
        val form = DialogFields.form(this)
        val et = DialogFields.addText(
            form, getString(R.string.group_name_hint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_new_group_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.container_create)) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { showMessage(getString(R.string.group_mgmt_name_empty)); return@setPositiveButton }
                val g = DashboardGroup(name = name, order = dashboardGroups.size)
                dashboardGroups.add(g)
                groupHosts[g.id] = mutableSetOf()
                saveGroups()
                saveGroupHosts(g.id)
                rebuildAndSubmit()
                showMessage(getString(R.string.dashboard_group_created_toast_fmt, name))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showRenameGroupDialog(group: DashboardGroup) {
        val form = DialogFields.form(this)
        val et = DialogFields.addText(
            form, getString(R.string.group_rename_hint), initial = group.name,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        et.setSelection(group.name.length)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_rename_group_title))
            .setView(form.root)
            .setPositiveButton(getString(R.string.connections_group_rename_confirm)) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { showMessage(getString(R.string.group_mgmt_name_empty)); return@setPositiveButton }
                val idx = dashboardGroups.indexOfFirst { it.id == group.id }
                if (idx >= 0) {
                    dashboardGroups[idx] = group.copy(name = name)
                    saveGroups()
                    rebuildAndSubmit()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteGroup(group: DashboardGroup) {
        val hostCount = groupHosts[group.id]?.size ?: 0
        val msg = if (hostCount > 0)
            getString(R.string.dashboard_delete_group_message_with_hosts_fmt, group.name, hostCount)
        else
            getString(R.string.dashboard_delete_group_message_fmt, group.name)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_delete_group_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                // Move hosts to ungrouped
                val hosts = groupHosts.remove(group.id) ?: mutableSetOf()
                val ungrouped = groupHosts.getOrPut(UNGROUPED_ID) { mutableSetOf() }
                ungrouped.addAll(hosts)
                saveGroupHosts(UNGROUPED_ID)
                prefs.edit().remove(KEY_HOSTS_PFX + group.id).apply()

                dashboardGroups.removeAll { it.id == group.id }
                saveGroups()
                rebuildAndSubmit()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Show a monitor-config dialog that applies the same settings to every host
     * in [groupId].  Existing MonitorSlot values are used as initial defaults so
     * the user sees the current state rather than blank fields.  On save the
     * template is merged onto every slot in the group — enable/disable and
     * threshold fields are always overwritten.
     */
    private fun showBulkMonitorConfigDialog(groupId: String, groupName: String) {
        val hostIds = groupHosts[groupId] ?: run { showMessage(getString(R.string.dashboard_group_has_no_hosts)); return }
        if (hostIds.isEmpty()) { showMessage(getString(R.string.dashboard_group_has_no_hosts)); return }

        val app = TabSSHApplication.get()

        // Use first available slot as defaults; fall back to blank if none yet.
        val firstSlot = hostIds.firstOrNull()?.let { monitorSlots[it] }

        val form = inflateMonitorForm(
            this,
            enabled         = firstSlot?.enabled ?: true,
            alertOnDown     = firstSlot?.alertOnDown ?: true,
            alertOnRecovery = firstSlot?.alertOnRecovery ?: true,
            intervalMinutes = firstSlot?.checkIntervalMinutes ?: 60,
            cooldownMinutes = firstSlot?.alertCooldownMinutes ?: 60,
            perfChecks      = firstSlot?.enablePerformanceChecks ?: false,
            cpuThreshold    = firstSlot?.cpuThreshold,
            memoryThreshold = firstSlot?.memoryThreshold,
            diskThreshold   = firstSlot?.diskThreshold
        )
        // Group variant — swap the per-host checkbox texts for the group wording
        form.cbEnabled.text    = getString(R.string.dashboard_enable_monitoring)
        form.cbPerfChecks.text = getString(R.string.dashboard_enable_ssh_metric_checks_group)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_monitor_group_title_fmt, groupName))
            .setMessage(resources.getQuantityString(R.plurals.dashboard_hosts_will_be_updated_fmt, hostIds.size, hostIds.size))
            .setView(form.root)
            .setPositiveButton(getString(R.string.dashboard_apply_to_all)) { _, _ ->
                val enabled      = form.cbEnabled.isChecked
                val alertOnDown  = form.cbAlertDown.isChecked
                val alertOnRecov = form.cbAlertRecovery.isChecked
                val interval     = INTERVAL_MINUTES[form.spinnerInterval.selectedItemPosition]
                val cooldown     = COOLDOWN_MINUTES[form.spinnerCooldown.selectedItemPosition]
                val perfChecks   = form.cbPerfChecks.isChecked
                val cpuThr       = form.sbCpu.progress.takeIf { it > 0 }
                val memThr       = form.sbMem.progress.takeIf { it > 0 }
                val diskThr      = form.sbDisk.progress.takeIf { it > 0 }

                app.applicationScope.launch(Dispatchers.IO) {
                    hostIds.forEach { connId ->
                        val base    = monitorSlots[connId] ?: MonitorSlot(connectionId = connId)
                        val updated = base.copy(
                            enabled                 = enabled,
                            alertOnDown             = alertOnDown,
                            alertOnRecovery         = alertOnRecov,
                            checkIntervalMinutes    = interval,
                            alertCooldownMinutes    = cooldown,
                            enablePerformanceChecks = perfChecks,
                            cpuThreshold            = cpuThr,
                            memoryThreshold         = memThr,
                            diskThreshold           = diskThr,
                            modifiedAt              = System.currentTimeMillis()
                        )
                        app.database.monitorSlotDao().insertOrReplace(updated)
                        monitorSlots[connId] = updated
                    }
                    withContext(Dispatchers.Main) {
                        rebuildAndSubmit()
                        showMessage(resources.getQuantityString(R.plurals.dashboard_monitor_applied_toast_fmt, hostIds.size, hostIds.size))
                        if (enabled) {
                            BatteryOptimizationHelper.requestExemptionIfNeeded(this@MultiHostDashboardActivity) {
                                BatteryOptimizationHelper.showManufacturerGuidanceIfNeeded(this@MultiHostDashboardActivity)
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun toggleGroupCollapsed(groupId: String) {
        if (groupId == UNGROUPED_ID) {
            ungroupedCollapsed = !ungroupedCollapsed
            prefs.edit().putBoolean("dash_ungrouped_collapsed", ungroupedCollapsed).apply()
        } else {
            val idx = dashboardGroups.indexOfFirst { it.id == groupId }
            if (idx >= 0) {
                dashboardGroups[idx] = dashboardGroups[idx].copy(
                    collapsed = !dashboardGroups[idx].collapsed
                )
                saveGroups()
            }
        }
        rebuildAndSubmit()
    }

    // ── Host picker ───────────────────────────────────────────────────────────

    /**
     * Show a multi-select host picker that targets [targetGroupId] for
     * additions. Backed by the [io.github.tabssh.storage.registry
     * .ConnectableHostRegistry] so cloud instances and container hosts are
     * selectable alongside saved connections — only SSH-capable rows are
     * offered (metric pumps run shell commands over an [SSHConnection];
     * telnet rows are filtered out). Registry ids for saved connections ARE
     * the profile ids, so previously persisted `groupHosts` buckets keep
     * working unchanged.
     */
    private fun showHostPicker(targetGroupId: String) {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                try {
                    io.github.tabssh.storage.registry.ConnectableHostRegistry.refreshAll(app.database, app)
                    app.database.connectableHostDao().getAllList()
                        .filter { it.protocol.equals("ssh", ignoreCase = true) }
                        .sortedBy { it.name.lowercase() }
                } catch (e: Exception) { Logger.e(TAG, "host registry load failed", e); emptyList() }
            }
            if (all.isEmpty()) { showMessage(getString(R.string.dashboard_no_saved_connections)); return@launch }

            val inThisGroup = groupHosts[targetGroupId] ?: emptySet<String>()
            val labels  = all.map {
                io.github.tabssh.ui.utils.ConnectableHostLabels.pickerLabel(this@MultiHostDashboardActivity, it)
            }.toTypedArray()
            val checked = BooleanArray(all.size) { i -> all[i].id in inThisGroup }

            MaterialAlertDialogBuilder(this@MultiHostDashboardActivity)
                .setTitle(getString(R.string.dashboard_add_hosts_title))
                .setMultiChoiceItems(labels, checked) { _, idx, isChecked ->
                    checked[idx] = isChecked
                }
                .setPositiveButton(getString(R.string.terminal_apply)) { _, _ ->
                    lifecycleScope.launch {
                        applyHostPickerResult(targetGroupId, all, checked)
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private suspend fun applyHostPickerResult(
        targetGroupId: String,
        allHosts: List<io.github.tabssh.storage.database.entities.ConnectableHost>,
        checked: BooleanArray
    ) {
        val bucket = groupHosts.getOrPut(targetGroupId) { mutableSetOf() }
        val newIds = allHosts.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
        val removed = bucket - newIds
        bucket.clear()
        bucket.addAll(newIds)
        saveGroupHosts(targetGroupId)

        // Stop pumps for removed hosts (only if they're not in another group)
        removed.filter { id -> allHostIds().none { it == id } || !bucket.contains(id) }.forEach { id ->
            if (!allHostIds().contains(id)) stopPump(id)
        }
        // Restart stop check: a host might have been removed from this group but still in another
        removed.forEach { id ->
            if (!allHostIds().contains(id)) stopPump(id)
        }

        // Resolve profiles for new IDs through the shared resolver — the
        // resolver guarantees profile.id == registry id, so profileCache,
        // monitorSlots, pumps, and metric maps all stay on one id space.
        val hostsById = allHosts.associateBy { it.id }
        val neededProfiles = newIds.filter { it !in profileCache }
        for (id in neededProfiles) {
            val host = hostsById[id] ?: continue
            val profile = try {
                io.github.tabssh.storage.registry.ConnectableHostResolver.resolveProfile(app, host)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "resolveProfile failed for ${host.name}", e)
                null
            }
            if (profile != null) profileCache[id] = profile
        }
        withContext(Dispatchers.IO) {
            newIds.forEach { id ->
                if (id !in monitorSlots) {
                    app.database.monitorSlotDao().getByConnectionId(id)?.let {
                        monitorSlots[id] = it
                    }
                }
            }
        }

        newIds.mapNotNull { profileCache[it] }.forEach { startPumpIfNeeded(it) }
        rebuildAndSubmit()
    }

    // ── Host management ───────────────────────────────────────────────────────

    private fun allHostIds(): Set<String> =
        groupHosts.values.flatten().toSet()

    private fun removeHostFromDashboard(connectionId: String, persist: Boolean = true) {
        removeHostFromAllGroups(connectionId)
        if (persist) {
            groupHosts.keys.forEach { saveGroupHosts(it) }
        }
        stopPump(connectionId)
        rebuildAndSubmit()
    }

    private fun removeHostFromAllGroups(connectionId: String) {
        groupHosts.values.forEach { it.remove(connectionId) }
    }

    private fun showMoveHostDialog(connectionId: String) {
        val profile = profileCache[connectionId] ?: return
        val options = buildList {
            add(getString(R.string.import_export_ungrouped))
            dashboardGroups.forEach { add(it.name) }
        }
        val groupIds = buildList {
            add(UNGROUPED_ID)
            dashboardGroups.forEach { add(it.id) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_move_host_title_fmt, profile.getDisplayName()))
            .setItems(options.toTypedArray()) { _, idx ->
                val destGroupId = groupIds[idx]
                // Remove from current group(s)
                groupHosts.values.forEach { it.remove(connectionId) }
                // Add to destination
                groupHosts.getOrPut(destGroupId) { mutableSetOf() }.add(connectionId)
                groupHosts.keys.forEach { saveGroupHosts(it) }
                rebuildAndSubmit()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Pump management ───────────────────────────────────────────────────────

    private fun startPumpIfNeeded(profile: ConnectionProfile) {
        if (jobs.containsKey(profile.id)) return
        jobs[profile.id] = pumpScope.launch { runHostPump(profile) }
    }

    private fun stopPump(connectionId: String) {
        jobs.remove(connectionId)?.cancel()
        ownedSessions.remove(connectionId)?.let { conn ->
            // disconnect() is suspend (blocking I/O); fire-and-forget on IO
            // dispatcher so the UI thread is never blocked. Monitoring connections
            // tolerate a brief teardown delay — there is no user-facing terminal.
            lifecycleScope.launch(Dispatchers.IO) {
                try { conn.disconnect() } catch (e: Exception) {
                    Logger.w(TAG, "stopPump disconnect: ${e.message}")
                }
            }
        }
        metricsMap.remove(connectionId)
        errorMap.remove(connectionId)
    }

    private suspend fun runHostPump(profile: ConnectionProfile) {
        var backoffMs = CONNECT_BACKOFF_INITIAL_MS
        while (true) {
            // Acquire connect slot — serialises handshakes so the network
            // stack isn't flooded when many hosts start at the same time.
            val ssh = connectSemaphore.withPermit { openOrReuseSession(profile) }
            if (ssh == null) {
                // Transient failure (timeout, auth retry race, etc.).
                // Show the error and retry after a backoff; don't give up.
                withContext(Dispatchers.Main) {
                    errorMap[profile.id] = getString(R.string.dashboard_status_connecting)
                    notifyHostCard(profile.id)
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(CONNECT_BACKOFF_MAX_MS)
                continue
            }
            // Connected — reset backoff for the next reconnect cycle.
            backoffMs = CONNECT_BACKOFF_INITIAL_MS

            val collector = MetricsCollector(ssh)
            // Metrics collection loop — runs until the session drops.
            while (true) {
                if (!ssh.isConnected()) {
                    withContext(Dispatchers.Main) {
                        errorMap[profile.id] = getString(R.string.dashboard_status_reconnecting)
                        notifyHostCard(profile.id)
                    }
                    // fall back to the outer reconnect loop
                    break
                }
                val r = runCatching { collector.collectMetrics() }
                    .onFailure { e -> Logger.w("MultiHostDashboard", "Metrics collection threw for ${profile.name}", e) }
                withContext(Dispatchers.Main) {
                    if (r.isSuccess) {
                        r.getOrNull()?.let { result ->
                            result
                                .onSuccess { m ->
                                    metricsMap[profile.id] = m
                                    errorMap.remove(profile.id)
                                    notifyHostCard(profile.id)
                                }
                                .onFailure { e ->
                                    errorMap[profile.id] = e.message ?: getString(R.string.dashboard_status_error)
                                    notifyHostCard(profile.id)
                                }
                        }
                    } else {
                        errorMap[profile.id] = r.exceptionOrNull()?.message ?: getString(R.string.dashboard_status_error)
                        notifyHostCard(profile.id)
                    }
                }
                if (r.isFailure && !ssh.isConnected()) break
                delay(REFRESH_MS)
            }
            // Brief pause before reconnecting so we don't spin tight on
            // a host that keeps dropping immediately after connect.
            delay(CONNECT_BACKOFF_INITIAL_MS)
        }
    }

    private suspend fun openOrReuseSession(profile: ConnectionProfile): SSHConnection? {
        // Use the monitoring-specific connect path so these background sessions
        // do not start SSHConnectionService or post "Connected to …" notifications.
        // If a real terminal session is already open for this profile it will be
        // reused (its existing notification is unaffected).
        return app.sshSessionManager.connectForMonitoring(profile)?.also {
            ownedSessions[profile.id] = it
        }
    }

    private fun notifyHostCard(connectionId: String) {
        val pos = adapter.items.indexOfFirst {
            it is DashboardItem.Host && it.profile.id == connectionId
        }
        if (pos >= 0) adapter.notifyItemChanged(pos, PAYLOAD_METRICS)

        // Also refresh the group header so its aggregate metrics stay current.
        val groupId = groupHosts.entries.find { it.value.contains(connectionId) }?.key ?: return
        val headerPos = adapter.items.indexOfFirst {
            (it is DashboardItem.GroupHeader && it.group.id == groupId)
        }
        if (headerPos >= 0) adapter.notifyItemChanged(headerPos, PAYLOAD_GROUP_METRICS)
    }

    // ── RecyclerView list builder ─────────────────────────────────────────────

    /** Average metric snapshot across all hosts in [groupId] that have data. */
    private fun computeGroupAgg(groupId: String): GroupAggMetrics? {
        val snapshots = groupHosts[groupId]
            ?.mapNotNull { metricsMap[it] }
            ?: return null
        if (snapshots.isEmpty()) return null
        fun List<PerformanceMetrics>.avgInt(f: (PerformanceMetrics) -> Float) =
            map { f(it).toInt() }.average().toInt().coerceAtLeast(0)
        fun loadPct(m: PerformanceMetrics, load: Float): Int =
            (load / m.cpuUsage.coreCount.coerceAtLeast(1) * 100).toInt().coerceAtLeast(0)
        return GroupAggMetrics(
            avgCpu   = snapshots.avgInt { it.cpuUsage.totalPercent },
            avgMem   = snapshots.avgInt { it.memoryUsage.usedPercent },
            avgDisk  = snapshots.avgInt { it.diskUsage.usedPercent },
            avgLoad1 = snapshots.map { loadPct(it, it.loadAverage.load1min)  }.average().toInt().coerceAtLeast(0),
            avgLoad5 = snapshots.map { loadPct(it, it.loadAverage.load5min)  }.average().toInt().coerceAtLeast(0),
            avgLoad15= snapshots.map { loadPct(it, it.loadAverage.load15min) }.average().toInt().coerceAtLeast(0),
        )
    }

    private fun buildItemList(): List<DashboardItem> {
        val list = mutableListOf<DashboardItem>()

        // Named groups
        for (group in dashboardGroups) {
            val hosts = groupHosts[group.id]?.mapNotNull { profileCache[it] } ?: emptyList()
            list.add(DashboardItem.GroupHeader(group, hosts.size, computeGroupAgg(group.id)))
            if (!group.collapsed) {
                hosts.forEach { list.add(DashboardItem.Host(it, group.id)) }
            }
        }

        // Ungrouped
        val ungrouped = groupHosts[UNGROUPED_ID]?.mapNotNull { profileCache[it] } ?: emptyList()
        if (ungrouped.isNotEmpty()) {
            list.add(DashboardItem.UngroupedHeader(ungrouped.size, ungroupedCollapsed))
            if (!ungroupedCollapsed) {
                ungrouped.forEach { list.add(DashboardItem.Host(it, null)) }
            }
        }

        if (list.isEmpty()) list.add(DashboardItem.EmptyState(lastLoadError))
        return list
    }

    private fun rebuildAndSubmit() {
        val newItems = buildItemList()
        val diff = DiffUtil.calculateDiff(ItemDiffCallback(adapter.items, newItems))
        adapter.items = newItems
        diff.dispatchUpdatesTo(adapter)
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    inner class DashboardAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var items: List<DashboardItem> = emptyList()

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is DashboardItem.GroupHeader    -> VT_GROUP_HEADER
            is DashboardItem.UngroupedHeader -> VT_UNGROUPED_HDR
            is DashboardItem.Host           -> VT_HOST
            is DashboardItem.EmptyState     -> VT_EMPTY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                VT_GROUP_HEADER, VT_UNGROUPED_HDR -> GroupHeaderHolder(
                    ItemDashboardGroupHeaderBinding.inflate(inflater, parent, false)
                )
                VT_HOST -> HostCardHolder(
                    ItemDashboardHostCardBinding.inflate(inflater, parent, false)
                )
                else -> EmptyStateHolder(
                    ItemDashboardEmptyBinding.inflate(inflater, parent, false)
                )
            }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DashboardItem.GroupHeader     -> (holder as GroupHeaderHolder).bind(item)
                is DashboardItem.UngroupedHeader -> (holder as GroupHeaderHolder).bindUngrouped(item)
                is DashboardItem.Host            -> (holder as HostCardHolder).bind(item)
                is DashboardItem.EmptyState      -> (holder as EmptyStateHolder).bind(item.errorMessage)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty()) {
                val item = items[position]
                when {
                    holder is HostCardHolder && item is DashboardItem.Host -> {
                        holder.updateMetrics(item.profile.id)
                        return
                    }
                    holder is GroupHeaderHolder && item is DashboardItem.GroupHeader -> {
                        holder.updateGroupMetrics(item.group.id)
                        return
                    }
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    // ── View holders ──────────────────────────────────────────────────────────

    inner class GroupHeaderHolder(
        private val b: ItemDashboardGroupHeaderBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: DashboardItem.GroupHeader) {
            val g = item.group
            b.tvGroupName.text  = g.name
            b.tvHostCount.text  = b.root.context.resources.getQuantityString(R.plurals.dashboard_host_count_fmt, item.memberCount, item.memberCount)
            b.btnToggle.setImageResource(
                if (g.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
            b.btnRename.visibility = View.VISIBLE
            b.btnDelete.visibility = View.VISIBLE

            bindMetricsRow(item.aggMetrics)

            b.btnToggle.setOnClickListener       { toggleGroupCollapsed(g.id) }
            b.root.setOnClickListener            { toggleGroupCollapsed(g.id) }
            b.btnAddHosts.setOnClickListener     { showHostPicker(g.id) }
            b.btnBulkMonitor.setOnClickListener  { showBulkMonitorConfigDialog(g.id, g.name) }
            b.btnRename.setOnClickListener       { showRenameGroupDialog(g) }
            b.btnDelete.setOnClickListener       { confirmDeleteGroup(g) }
        }

        fun bindUngrouped(item: DashboardItem.UngroupedHeader) {
            b.tvGroupName.text  = b.root.context.getString(R.string.import_export_ungrouped)
            b.tvHostCount.text  = b.root.context.resources.getQuantityString(R.plurals.dashboard_host_count_fmt, item.count, item.count)
            b.btnToggle.setImageResource(
                if (item.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )

            bindMetricsRow(computeGroupAgg(UNGROUPED_ID))

            b.btnToggle.setOnClickListener       { toggleGroupCollapsed(UNGROUPED_ID) }
            b.root.setOnClickListener            { toggleGroupCollapsed(UNGROUPED_ID) }
            b.btnAddHosts.setOnClickListener     { showHostPicker(UNGROUPED_ID) }
            b.btnBulkMonitor.setOnClickListener  { showBulkMonitorConfigDialog(UNGROUPED_ID, b.root.context.getString(R.string.import_export_ungrouped)) }
            // Ungrouped cannot be renamed or deleted — hide those buttons
            b.btnRename.visibility = View.GONE
            b.btnDelete.visibility = View.GONE
        }

        /** Payload-only refresh — recomputes aggregates without rebinding click listeners. */
        fun updateGroupMetrics(groupId: String) {
            bindMetricsRow(computeGroupAgg(groupId))
        }

        /**
         * Returns green / yellow / red based on [value] vs thresholds.
         *   ≤ warnAt  → success (green)
         *   ≤ critAt  → warning (amber)
         *   > critAt  → error   (red)
         */
        private fun metricColor(value: Int, warnAt: Int, critAt: Int): Int {
            val ctx = itemView.context
            return when {
                value > critAt -> ContextCompat.getColor(ctx, R.color.status_error)
                value > warnAt -> ContextCompat.getColor(ctx, R.color.status_warning)
                else           -> ContextCompat.getColor(ctx, R.color.status_success)
            }
        }

        private fun bindMetricsRow(agg: GroupAggMetrics?) {
            if (agg == null) { b.tvGroupMetrics.visibility = View.GONE; return }

            val sb = SpannableStringBuilder()

            fun SpannableStringBuilder.appendColored(text: String, color: Int) {
                val start = length
                append(text)
                setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            val ctx = b.root.context
            // Line 1: CPU | MEM
            sb.append(ctx.getString(R.string.dashboard_metrics_cpu_label))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgCpu), metricColor(agg.avgCpu, warnAt = 60, critAt = 80))
            sb.append(ctx.getString(R.string.dashboard_metrics_mem_label))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgMem), metricColor(agg.avgMem, warnAt = 60, critAt = 80))
            sb.append("\n")
            // Line 2: DISK | LOAD
            sb.append(ctx.getString(R.string.dashboard_metrics_disk_label))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgDisk), metricColor(agg.avgDisk, warnAt = 70, critAt = 85))
            sb.append(ctx.getString(R.string.dashboard_metrics_load_label))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgLoad1), metricColor(agg.avgLoad1, warnAt = 60, critAt = 90))
            sb.append(ctx.getString(R.string.dashboard_metrics_load_sep))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgLoad5), metricColor(agg.avgLoad5, warnAt = 60, critAt = 90))
            sb.append(ctx.getString(R.string.dashboard_metrics_load_sep))
            sb.appendColored(ctx.getString(R.string.dashboard_percent_fmt, agg.avgLoad15), metricColor(agg.avgLoad15, warnAt = 60, critAt = 90))

            b.tvGroupMetrics.text = sb
            b.tvGroupMetrics.visibility = View.VISIBLE
        }
    }

    inner class HostCardHolder(
        private val b: ItemDashboardHostCardBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: DashboardItem.Host) {
            val profile = item.profile
            b.tvHostname.text = profile.getDisplayName()
            b.tvSubtitle.text =
                b.root.context.getString(R.string.dashboard_host_port_fmt, profile.host, profile.port)

            // Status dot from MonitorSlot state
            val slot = monitorSlots[profile.id]
            val dotColor = when {
                slot == null || !slot.enabled -> ContextCompat.getColor(b.root.context, R.color.status_neutral)
                slot.isCurrentlyDown          -> ContextCompat.getColor(b.root.context, R.color.status_error)
                slot.lastSeenUp > 0           -> ContextCompat.getColor(b.root.context, R.color.status_success)
                else                          -> ContextCompat.getColor(b.root.context, R.color.status_neutral)
            }
            b.statusDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(dotColor)

            // Monitor bell
            b.btnMonitor.setOnClickListener {
                showMonitorConfigDialog(this@MultiHostDashboardActivity, profile, monitorSlots[profile.id]) { updated ->
                    monitorSlots[profile.id] = updated
                    notifyHostCard(profile.id)
                }
            }

            // Tap → detail
            b.root.setOnClickListener {
                HostDetailActivity.start(this@MultiHostDashboardActivity, profile.id)
            }

            // Long press → context menu
            b.root.setOnLongClickListener {
                showHostContextMenu(profile.id, item.groupId)
                true
            }

            // Apply current metrics (may be null if still loading)
            updateMetrics(profile.id)
        }

        fun updateMetrics(connectionId: String) {
            val metrics = metricsMap[connectionId]
            val error   = errorMap[connectionId]

            val ctx = b.root.context
            if (error != null) {
                b.tvOsIcon.text   = ctx.getString(R.string.dashboard_error_icon)
                b.tvSubtitle.text = ctx.getString(R.string.dashboard_error_prefix_fmt, error)
                b.pbCpu.progress  = 0; b.tvCpu.text  = ctx.getString(R.string.container_dashboard_count_unavailable)
                b.pbMem.progress  = 0; b.tvMem.text  = ctx.getString(R.string.container_dashboard_count_unavailable)
                b.pbDisk.progress = 0; b.tvDisk.text = ctx.getString(R.string.container_dashboard_count_unavailable)
                b.tvLoad.text   = ctx.getString(R.string.dashboard_load_dash)
                b.tvUptime.text = ctx.getString(R.string.dashboard_uptime_dash)
                b.tvNet.text    = ctx.getString(R.string.dashboard_net_dash)
                b.tvProcs.text  = ctx.getString(R.string.dashboard_procs_dash)
                return
            }

            if (metrics == null) {
                b.tvOsIcon.text   = ""
                b.pbCpu.progress  = 0; b.tvCpu.text  = ctx.getString(R.string.dashboard_placeholder_ellipsis)
                b.pbMem.progress  = 0; b.tvMem.text  = ctx.getString(R.string.dashboard_placeholder_ellipsis)
                b.pbDisk.progress = 0; b.tvDisk.text = ctx.getString(R.string.dashboard_placeholder_ellipsis)
                b.tvLoad.text   = ctx.getString(R.string.dashboard_load_ellipsis)
                b.tvUptime.text = ctx.getString(R.string.dashboard_uptime_ellipsis)
                b.tvNet.text    = ctx.getString(R.string.dashboard_net_ellipsis)
                b.tvProcs.text  = ctx.getString(R.string.dashboard_procs_ellipsis)
                return
            }

            val cpu  = metrics.cpuUsage.totalPercent.toInt().coerceIn(0, 100)
            val mem  = metrics.memoryUsage.usedPercent.toInt().coerceIn(0, 100)
            val disk = metrics.diskUsage.usedPercent.toInt().coerceIn(0, 100)

            b.tvOsIcon.text   = metrics.platformInfo.getOsIcon()
            b.tvSubtitle.text = buildSubtitle(metrics)

            setBar(b.pbCpu,  cpu);  b.tvCpu.text  = ctx.getString(R.string.dashboard_percent_fmt, cpu)
            setBar(b.pbMem,  mem);  b.tvMem.text  = ctx.getString(R.string.dashboard_percent_fmt, mem)
            setBar(b.pbDisk, disk); b.tvDisk.text = ctx.getString(R.string.dashboard_percent_fmt, disk)

            val load  = metrics.loadAverage
            val cores = metrics.cpuUsage.coreCount.coerceAtLeast(1)
            val l1    = (load.load1min  / cores * 100).toInt().coerceAtLeast(0)
            val l5    = (load.load5min  / cores * 100).toInt().coerceAtLeast(0)
            val l15   = (load.load15min / cores * 100).toInt().coerceAtLeast(0)
            b.tvLoad.text = ctx.getString(R.string.dashboard_load_fmt, l1, l5, l15)
            b.tvUptime.text = b.root.context.getString(
                R.string.dashboard_uptime_fmt,
                Format.duration(b.root.context, load.uptime * 1_000L)
            )

            val net = metrics.networkStats
            b.tvNet.text = b.root.context.getString(
                R.string.dashboard_net_rx_tx_fmt,
                Format.rate(b.root.context, net.rxBytesPerSec),
                Format.rate(b.root.context, net.txBytesPerSec)
            )
            b.tvProcs.text = ctx.getString(R.string.dashboard_procs_fmt, load.runningProcesses, load.totalProcesses)
        }

        private fun buildSubtitle(m: PerformanceMetrics): String {
            val os = m.platformInfo.getDisplayName()
            val kern = m.platformInfo.kernelRelease.substringBefore("-").take(20)
            return if (kern.isNotBlank())
                itemView.context.getString(R.string.dashboard_subtitle_os_kernel_fmt, os, kern)
            else os
        }

        private fun setBar(bar: LinearProgressIndicator, pct: Int) {
            bar.setIndicatorColor(barColor(pct))
            bar.setProgressCompat(pct, true)
        }

        private fun barColor(pct: Int): Int = androidx.core.content.ContextCompat.getColor(
            itemView.context,
            when {
                pct >= 85 -> R.color.gauge_critical
                pct >= 65 -> R.color.gauge_warn
                else      -> R.color.gauge_ok
            }
        )
    }

    inner class EmptyStateHolder(
        private val b: ItemDashboardEmptyBinding
    ) : RecyclerView.ViewHolder(b.root) {
        /** [errorMessage] non-null renders the load-error variant: error-colored
         *  message plus a Retry button, distinct from the genuine empty state. */
        fun bind(errorMessage: String?) {
            val ctx = b.root.context
            if (errorMessage != null) {
                b.tvEmptyMessage.text = ctx.getString(R.string.dashboard_load_error_fmt, errorMessage)
                b.tvEmptyMessage.setTextColor(ContextCompat.getColor(ctx, R.color.status_error))
                b.btnEmptyAction.text = ctx.getString(R.string.retry)
                b.btnEmptyAction.setOnClickListener { loadPersistedState() }
            } else {
                b.tvEmptyMessage.text = ctx.getString(R.string.dashboard_empty_state)
                b.tvEmptyMessage.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                b.btnEmptyAction.text = ctx.getString(R.string.dashboard_add_hosts_title)
                b.btnEmptyAction.setOnClickListener { showHostPicker(UNGROUPED_ID) }
            }
        }
    }

    // ── Context menu for host cards ───────────────────────────────────────────

    private fun showHostContextMenu(connectionId: String, currentGroupId: String?) {
        val profile = profileCache[connectionId] ?: return
        val options = arrayOf(
            getString(R.string.dashboard_context_monitor_settings),
            getString(R.string.dashboard_context_move_to_group),
            getString(R.string.dashboard_context_remove_from_dashboard)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.getDisplayName())
            .setItems(options) { _, idx ->
                when (idx) {
                    0 -> showMonitorConfigDialog(this, profile, monitorSlots[connectionId]) { updated ->
                        monitorSlots[connectionId] = updated
                        notifyHostCard(connectionId)
                    }
                    1 -> showMoveHostDialog(connectionId)
                    2 -> confirmRemoveHost(connectionId, profile.getDisplayName())
                }
            }
            .show()
    }

    private fun confirmRemoveHost(connectionId: String, displayName: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_remove_host_title_fmt, displayName))
            .setMessage(getString(R.string.dashboard_remove_host_message))
            .setPositiveButton(getString(R.string.dashboard_remove)) { _, _ -> removeHostFromDashboard(connectionId) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── DiffUtil callback ─────────────────────────────────────────────────────

    private val PAYLOAD_METRICS       = Any()
    private val PAYLOAD_GROUP_METRICS = Any()

    private class ItemDiffCallback(
        private val old: List<DashboardItem>,
        private val new: List<DashboardItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) =
            itemKey(old[oldPos]) == itemKey(new[newPos])
        override fun areContentsTheSame(oldPos: Int, newPos: Int) =
            old[oldPos] == new[newPos]

        private fun itemKey(item: DashboardItem): String = when (item) {
            is DashboardItem.GroupHeader     -> "gh_${item.group.id}"
            is DashboardItem.UngroupedHeader -> "ugh"
            is DashboardItem.Host            -> "h_${item.profile.id}"
            is DashboardItem.EmptyState      -> if (item.errorMessage != null) "error" else "empty"
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────


    // Transient confirmations use a Snackbar (anchored above the FAB), per the
    // Toast-vs-Snackbar rule — Toast is reserved for fire-and-forget messages
    private fun showMessage(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabNewGroup)
            .show()
}

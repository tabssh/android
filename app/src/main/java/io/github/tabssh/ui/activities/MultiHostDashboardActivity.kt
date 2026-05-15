package io.github.tabssh.ui.activities

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityMultiHostDashboardBinding
import io.github.tabssh.databinding.ItemDashboardGroupHeaderBinding
import io.github.tabssh.databinding.ItemDashboardHostCardBinding
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.performance.PerformanceMetrics
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.background.BatteryOptimizationHelper
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
class MultiHostDashboardActivity : AppCompatActivity() {

    // ── Data model ────────────────────────────────────────────────────────────

    /** A named group on the dashboard — independent of ConnectionGroup in the DB. */
    data class DashboardGroup(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val order: Int = 0,
        val collapsed: Boolean = false
    )

    sealed class DashboardItem {
        /** Header row for a named dashboard group. */
        data class GroupHeader(val group: DashboardGroup, val memberCount: Int) : DashboardItem()
        /** Header row for the "Ungrouped" pseudo-group. */
        data class UngroupedHeader(val count: Int, val collapsed: Boolean) : DashboardItem()
        /** One host card row. */
        data class Host(
            val profile: ConnectionProfile,
            /** null = ungrouped. */
            val groupId: String?
        ) : DashboardItem()
        /** Shown when the dashboard is empty. */
        object EmptyState : DashboardItem()
    }

    companion object {
        private const val TAG = "MultiHostDash"

        const val UNGROUPED_ID = "__ungrouped__"
        private const val PREF_FILE       = "multi_host_dashboard"
        private const val KEY_GROUPS      = "dash_groups_json"
        private const val KEY_HOSTS_PFX   = "dash_hosts_"
        private const val REFRESH_MS      = 5_000L

        private const val VT_GROUP_HEADER    = 0
        private const val VT_UNGROUPED_HDR   = 1
        private const val VT_HOST            = 2
        private const val VT_EMPTY           = 3

        private fun dp(ctx: Context, v: Int) =
            (v * ctx.resources.displayMetrics.density).toInt()

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
            val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
            val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT

            val scroll = ScrollView(context)
            val form   = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 20))
            }
            scroll.addView(form)

            fun label(text: String) = TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.topMargin = dp(context, 12)
                layoutParams = lp
            }

            val cbEnabled = CheckBox(context).apply {
                text = "Enable monitoring for this host"
                isChecked = slot.enabled
            }
            form.addView(cbEnabled)

            val cbDown = CheckBox(context).apply {
                text = "Notify when host is unreachable"
                isChecked = slot.alertOnDown
            }
            form.addView(cbDown)

            val cbUp = CheckBox(context).apply {
                text = "Notify when host recovers"
                isChecked = slot.alertOnRecovery
            }
            form.addView(cbUp)

            form.addView(label("Check interval"))
            val intervalOptions = arrayOf("15 min", "30 min", "1 hour", "4 hours", "12 hours")
            val intervalValues  = intArrayOf(15, 30, 60, 240, 720)
            val intervalAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, intervalOptions)
            intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val spInterval = Spinner(context).apply { adapter = intervalAdapter }
            spInterval.setSelection(intervalValues.indexOfFirst { it >= slot.checkIntervalMinutes }.coerceAtLeast(0))
            form.addView(spInterval)

            form.addView(label("Alert cooldown (min gap between repeat alerts)"))
            val cooldownOptions = arrayOf("15 min", "30 min", "1 hour", "4 hours", "24 hours")
            val cooldownValues  = intArrayOf(15, 30, 60, 240, 1440)
            val cooldownAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, cooldownOptions)
            cooldownAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val spCooldown = Spinner(context).apply { adapter = cooldownAdapter }
            spCooldown.setSelection(cooldownValues.indexOfFirst { it >= slot.alertCooldownMinutes }.coerceAtLeast(0))
            form.addView(spCooldown)

            val cbPerf = CheckBox(context).apply {
                text = "Enable SSH metric checks (CPU/memory/disk — uses more battery)"
                isChecked = slot.enablePerformanceChecks
            }
            form.addView(cbPerf)

            // CPU threshold
            form.addView(label("CPU threshold (0 = disabled)"))
            val tvCpuVal = TextView(context).apply {
                text = if (slot.cpuThreshold != null) "${slot.cpuThreshold}%" else "Disabled"
            }
            form.addView(tvCpuVal)
            val sbCpu = SeekBar(context).apply { max = 100; progress = slot.cpuThreshold ?: 0 }
            sbCpu.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                    tvCpuVal.text = if (v == 0) "Disabled" else "$v%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            form.addView(sbCpu)

            // Memory threshold
            form.addView(label("Memory threshold (0 = disabled)"))
            val tvMemVal = TextView(context).apply {
                text = if (slot.memoryThreshold != null) "${slot.memoryThreshold}%" else "Disabled"
            }
            form.addView(tvMemVal)
            val sbMem = SeekBar(context).apply { max = 100; progress = slot.memoryThreshold ?: 0 }
            sbMem.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                    tvMemVal.text = if (v == 0) "Disabled" else "$v%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            form.addView(sbMem)

            // Disk threshold
            form.addView(label("Disk threshold (0 = disabled)"))
            val tvDiskVal = TextView(context).apply {
                text = if (slot.diskThreshold != null) "${slot.diskThreshold}%" else "Disabled"
            }
            form.addView(tvDiskVal)
            val sbDisk = SeekBar(context).apply { max = 100; progress = slot.diskThreshold ?: 0 }
            sbDisk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, f: Boolean) {
                    tvDiskVal.text = if (v == 0) "Disabled" else "$v%"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            form.addView(sbDisk)

            AlertDialog.Builder(context)
                .setTitle("Monitor: ${profile.getDisplayName()}")
                .setView(scroll)
                .setPositiveButton("Save") { _, _ ->
                    val updated = slot.copy(
                        enabled                 = cbEnabled.isChecked,
                        alertOnDown             = cbDown.isChecked,
                        alertOnRecovery         = cbUp.isChecked,
                        checkIntervalMinutes    = intervalValues[spInterval.selectedItemPosition],
                        alertCooldownMinutes    = cooldownValues[spCooldown.selectedItemPosition],
                        enablePerformanceChecks = cbPerf.isChecked,
                        cpuThreshold            = sbCpu.progress.takeIf { it > 0 },
                        memoryThreshold         = sbMem.progress.takeIf { it > 0 },
                        diskThreshold           = sbDisk.progress.takeIf { it > 0 }
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
                .setNeutralButton("Remove") { _, _ ->
                    if (existing != null) {
                        app.applicationScope.launch(Dispatchers.IO) {
                            app.database.monitorSlotDao().delete(existing)
                            withContext(Dispatchers.Main) { onSaved(existing.copy(enabled = false)) }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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

    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs          = mutableMapOf<String, Job>()
    private val ownedSessions = mutableMapOf<String, SSHConnection>()

    private lateinit var adapter: DashboardAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication
        binding = ActivityMultiHostDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Multi-host Dashboard"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = DashboardAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabNewGroup.setOnClickListener { showAddGroupDialog() }

        loadPersistedState()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_dashboard, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home    -> { finish(); true }
        R.id.menu_add_hosts  -> { showHostPicker(targetGroupId = UNGROUPED_ID); true }
        else                 -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
        ownedSessions.values.forEach {
            try { it.disconnect() } catch (e: Exception) {
                Logger.w(TAG, "onDestroy disconnect: ${e.message}")
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
            val profiles = withContext(Dispatchers.IO) {
                try {
                    app.database.connectionDao().getAllConnectionsList()
                        .filter { it.id in allIds }
                } catch (e: Exception) {
                    Logger.e(TAG, "loadProfiles failed", e); emptyList()
                }
            }
            profiles.forEach { profileCache[it.id] = it }

            // Load monitor slots
            withContext(Dispatchers.IO) {
                profiles.forEach { p ->
                    app.database.monitorSlotDao().getByConnectionId(p.id)?.let {
                        monitorSlots[p.id] = it
                    }
                }
            }

            // Remove stale IDs (profile was deleted)
            val validIds = profiles.map { it.id }.toSet()
            val staleIds = allIds - validIds
            staleIds.forEach { removeHostFromAllGroups(it) }

            // Start metric pumps
            profiles.forEach { startPumpIfNeeded(it) }
            rebuildAndSubmit()
        }
    }

    // ── Group CRUD ────────────────────────────────────────────────────────────

    private fun showAddGroupDialog() {
        val et = EditText(this).apply {
            hint = "Group name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(this@MultiHostDashboardActivity, 16))
        }
        AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { toast("Group name cannot be empty"); return@setPositiveButton }
                val g = DashboardGroup(name = name, order = dashboardGroups.size)
                dashboardGroups.add(g)
                groupHosts[g.id] = mutableSetOf()
                saveGroups()
                saveGroupHosts(g.id)
                rebuildAndSubmit()
                toast("Group \"$name\" created — use ⊕ to add hosts")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameGroupDialog(group: DashboardGroup) {
        val et = EditText(this).apply {
            setText(group.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(this@MultiHostDashboardActivity, 16))
            setSelection(group.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename group")
            .setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { toast("Group name cannot be empty"); return@setPositiveButton }
                val idx = dashboardGroups.indexOfFirst { it.id == group.id }
                if (idx >= 0) {
                    dashboardGroups[idx] = group.copy(name = name)
                    saveGroups()
                    rebuildAndSubmit()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteGroup(group: DashboardGroup) {
        val hostCount = groupHosts[group.id]?.size ?: 0
        val msg = if (hostCount > 0)
            "Delete group \"${group.name}\"?\n\n$hostCount host(s) will move to Ungrouped."
        else
            "Delete group \"${group.name}\"?"

        AlertDialog.Builder(this)
            .setTitle("Delete group")
            .setMessage(msg)
            .setPositiveButton("Delete") { _, _ ->
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
            .setNegativeButton("Cancel", null)
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

    /** Show a multi-select host picker that targets [targetGroupId] for additions. */
    private fun showHostPicker(targetGroupId: String) {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) {
                try { app.database.connectionDao().getAllConnectionsList() }
                catch (e: Exception) { Logger.e(TAG, "getAllConnections failed", e); emptyList() }
            }
            if (all.isEmpty()) { toast("No saved connections"); return@launch }

            val inThisGroup = groupHosts[targetGroupId] ?: emptySet<String>()
            val labels  = all.map { it.getDisplayName() }.toTypedArray()
            val checked = BooleanArray(all.size) { i -> all[i].id in inThisGroup }

            AlertDialog.Builder(this@MultiHostDashboardActivity)
                .setTitle("Add hosts")
                .setMultiChoiceItems(labels, checked) { _, idx, isChecked ->
                    checked[idx] = isChecked
                }
                .setPositiveButton("Apply") { _, _ ->
                    lifecycleScope.launch {
                        applyHostPickerResult(targetGroupId, all, checked)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun applyHostPickerResult(
        targetGroupId: String,
        allProfiles: List<ConnectionProfile>,
        checked: BooleanArray
    ) {
        val bucket = groupHosts.getOrPut(targetGroupId) { mutableSetOf() }
        val newIds = allProfiles.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
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

        // Load profiles and slots for new IDs
        val neededProfiles = newIds.filter { it !in profileCache }
        if (neededProfiles.isNotEmpty()) {
            val fetched = withContext(Dispatchers.IO) {
                try { allProfiles.filter { it.id in neededProfiles } }
                catch (e: Exception) { emptyList() }
            }
            fetched.forEach { profileCache[it.id] = it }
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
            add("Ungrouped")
            dashboardGroups.forEach { add(it.name) }
        }
        val groupIds = buildList {
            add(UNGROUPED_ID)
            dashboardGroups.forEach { add(it.id) }
        }
        AlertDialog.Builder(this)
            .setTitle("Move ${profile.getDisplayName()}")
            .setItems(options.toTypedArray()) { _, idx ->
                val destGroupId = groupIds[idx]
                // Remove from current group(s)
                groupHosts.values.forEach { it.remove(connectionId) }
                // Add to destination
                groupHosts.getOrPut(destGroupId) { mutableSetOf() }.add(connectionId)
                groupHosts.keys.forEach { saveGroupHosts(it) }
                rebuildAndSubmit()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Pump management ───────────────────────────────────────────────────────

    private fun startPumpIfNeeded(profile: ConnectionProfile) {
        if (jobs.containsKey(profile.id)) return
        jobs[profile.id] = pumpScope.launch { runHostPump(profile) }
    }

    private fun stopPump(connectionId: String) {
        jobs.remove(connectionId)?.cancel()
        ownedSessions.remove(connectionId)?.let {
            try { it.disconnect() } catch (e: Exception) {
                Logger.w(TAG, "stopPump disconnect: ${e.message}")
            }
        }
        metricsMap.remove(connectionId)
        errorMap.remove(connectionId)
    }

    private suspend fun runHostPump(profile: ConnectionProfile) {
        val ssh = openOrReuseSession(profile)
        if (ssh == null) {
            withContext(Dispatchers.Main) { errorMap[profile.id] = "connect failed"; notifyHostCard(profile.id) }
            return
        }
        val collector = MetricsCollector(ssh)
        while (true) {
            if (!ssh.isConnected()) {
                withContext(Dispatchers.Main) { errorMap[profile.id] = "disconnected"; notifyHostCard(profile.id) }
                return
            }
            val r = runCatching { collector.collectMetrics() }
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
                                errorMap[profile.id] = e.message ?: "error"
                                notifyHostCard(profile.id)
                            }
                    }
                } else {
                    errorMap[profile.id] = r.exceptionOrNull()?.message ?: "error"
                    notifyHostCard(profile.id)
                }
            }
            if (r.isFailure && !ssh.isConnected()) return
            delay(REFRESH_MS)
        }
    }

    private suspend fun openOrReuseSession(profile: ConnectionProfile): SSHConnection? {
        app.sshSessionManager.getConnection(profile.id)?.let { return it }
        return app.sshSessionManager.connectToServer(profile)?.also {
            ownedSessions[profile.id] = it
        }
    }

    private fun notifyHostCard(connectionId: String) {
        val pos = adapter.items.indexOfFirst {
            it is DashboardItem.Host && it.profile.id == connectionId
        }
        if (pos >= 0) adapter.notifyItemChanged(pos, PAYLOAD_METRICS)
    }

    // ── RecyclerView list builder ─────────────────────────────────────────────

    private fun buildItemList(): List<DashboardItem> {
        val list = mutableListOf<DashboardItem>()

        // Named groups
        for (group in dashboardGroups) {
            val hosts = groupHosts[group.id]?.mapNotNull { profileCache[it] } ?: emptyList()
            list.add(DashboardItem.GroupHeader(group, hosts.size))
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

        if (list.isEmpty()) list.add(DashboardItem.EmptyState)
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
            DashboardItem.EmptyState        -> VT_EMPTY
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
                else -> EmptyStateHolder(inflater.inflate(
                    android.R.layout.simple_list_item_1, parent, false
                ))
            }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is DashboardItem.GroupHeader     -> (holder as GroupHeaderHolder).bind(item)
                is DashboardItem.UngroupedHeader -> (holder as GroupHeaderHolder).bindUngrouped(item)
                is DashboardItem.Host            -> (holder as HostCardHolder).bind(item)
                DashboardItem.EmptyState         -> (holder as EmptyStateHolder).bind()
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty() && holder is HostCardHolder) {
                val item = items[position]
                if (item is DashboardItem.Host) holder.updateMetrics(item.profile.id)
                return
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
            b.tvHostCount.text  = "${item.memberCount} host${if (item.memberCount == 1) "" else "s"}"
            b.btnToggle.setImageResource(
                if (g.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )

            b.btnToggle.setOnClickListener    { toggleGroupCollapsed(g.id) }
            b.root.setOnClickListener         { toggleGroupCollapsed(g.id) }
            b.btnAddHosts.setOnClickListener  { showHostPicker(g.id) }
            b.btnRename.setOnClickListener    { showRenameGroupDialog(g) }
            b.btnDelete.setOnClickListener    { confirmDeleteGroup(g) }
        }

        fun bindUngrouped(item: DashboardItem.UngroupedHeader) {
            b.tvGroupName.text  = "Ungrouped"
            b.tvHostCount.text  = "${item.count} host${if (item.count == 1) "" else "s"}"
            b.btnToggle.setImageResource(
                if (item.collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )

            b.btnToggle.setOnClickListener    { toggleGroupCollapsed(UNGROUPED_ID) }
            b.root.setOnClickListener         { toggleGroupCollapsed(UNGROUPED_ID) }
            b.btnAddHosts.setOnClickListener  { showHostPicker(UNGROUPED_ID) }
            // Ungrouped cannot be renamed or deleted — hide those buttons
            b.btnRename.visibility = View.GONE
            b.btnDelete.visibility = View.GONE
        }
    }

    inner class HostCardHolder(
        private val b: ItemDashboardHostCardBinding
    ) : RecyclerView.ViewHolder(b.root) {

        fun bind(item: DashboardItem.Host) {
            val profile = item.profile
            b.tvHostname.text = profile.getDisplayName()
            b.tvSubtitle.text = "${profile.host}:${profile.port}"

            // Status dot from MonitorSlot state
            val slot = monitorSlots[profile.id]
            val dotColor = when {
                slot == null || !slot.enabled -> 0xFF888888.toInt()
                slot.isCurrentlyDown          -> 0xFFFF4444.toInt()
                slot.lastSeenUp > 0           -> 0xFF44CC44.toInt()
                else                          -> 0xFF888888.toInt()
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

            if (error != null) {
                b.tvOsIcon.text   = "⚠"
                b.tvSubtitle.text = "error: $error"
                b.pbCpu.progress  = 0; b.tvCpu.text  = "—"
                b.pbMem.progress  = 0; b.tvMem.text  = "—"
                b.pbDisk.progress = 0; b.tvDisk.text = "—"
                b.tvLoad.text   = "LOAD —"
                b.tvUptime.text = "⏱ —"
                b.tvNet.text    = "↓ — ↑ —"
                b.tvProcs.text  = "⚙ —"
                return
            }

            if (metrics == null) {
                b.tvOsIcon.text   = ""
                b.pbCpu.progress  = 0; b.tvCpu.text  = "…"
                b.pbMem.progress  = 0; b.tvMem.text  = "…"
                b.pbDisk.progress = 0; b.tvDisk.text = "…"
                b.tvLoad.text   = "LOAD …"
                b.tvUptime.text = "⏱ …"
                b.tvNet.text    = "↓ … ↑ …"
                b.tvProcs.text  = "⚙ …"
                return
            }

            val cpu  = metrics.cpuUsage.totalPercent.toInt().coerceIn(0, 100)
            val mem  = metrics.memoryUsage.usedPercent.toInt().coerceIn(0, 100)
            val disk = metrics.diskUsage.usedPercent.toInt().coerceIn(0, 100)

            b.tvOsIcon.text   = metrics.platformInfo.getOsIcon()
            b.tvSubtitle.text = buildSubtitle(metrics)

            setBar(b.pbCpu,  cpu);  b.tvCpu.text  = "$cpu%"
            setBar(b.pbMem,  mem);  b.tvMem.text  = "$mem%"
            setBar(b.pbDisk, disk); b.tvDisk.text = "$disk%"

            val load = metrics.loadAverage
            b.tvLoad.text = "LOAD %.2f / %.2f / %.2f".format(
                load.load1min, load.load5min, load.load15min
            )
            b.tvUptime.text = "⏱ " + formatUptime(load.uptime)

            val net = metrics.networkStats
            b.tvNet.text  = "↓ ${fmtBps(net.rxBytesPerSec.toFloat())}  ↑ ${fmtBps(net.txBytesPerSec.toFloat())}"
            b.tvProcs.text = "⚙ ${load.runningProcesses}/${load.totalProcesses}"
        }

        private fun buildSubtitle(m: PerformanceMetrics): String {
            val os = m.platformInfo.getDisplayName()
            val kern = m.platformInfo.kernelRelease.substringBefore("-").take(20)
            return if (kern.isNotBlank()) "$os · $kern" else os
        }

        private fun setBar(bar: LinearProgressIndicator, pct: Int) {
            bar.setIndicatorColor(barColor(pct))
            bar.setProgressCompat(pct, true)
        }

        private fun barColor(pct: Int): Int = when {
            pct >= 85 -> Color.parseColor("#F44336")
            pct >= 65 -> Color.parseColor("#FF9800")
            else      -> Color.parseColor("#4CAF50")
        }
    }

    inner class EmptyStateHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            (itemView as? TextView)?.apply {
                text = "No hosts — tap  New Group  to create a group, then ⊕ to add hosts"
                gravity = android.view.Gravity.CENTER
                setPadding(dp(this@MultiHostDashboardActivity, 32))
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 14f
            }
        }
    }

    // ── Context menu for host cards ───────────────────────────────────────────

    private fun showHostContextMenu(connectionId: String, currentGroupId: String?) {
        val profile = profileCache[connectionId] ?: return
        val options = arrayOf(
            "Monitor settings",
            "Move to group…",
            "Remove from dashboard"
        )
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle("Remove $displayName?")
            .setMessage("This removes it from the dashboard. The connection itself is not deleted.")
            .setPositiveButton("Remove") { _, _ -> removeHostFromDashboard(connectionId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── DiffUtil callback ─────────────────────────────────────────────────────

    private val PAYLOAD_METRICS = Any()

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
            DashboardItem.EmptyState         -> "empty"
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun formatUptime(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0  -> "${d}d ${h}h"
            h > 0  -> "${h}h ${m}m"
            else   -> "${m}m"
        }
    }

    private fun fmtBps(bytesPerSec: Float): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576)
        bytesPerSec >= 1_024     -> "%.0f KB/s".format(bytesPerSec / 1_024)
        else                     -> "%.0f B/s".format(bytesPerSec)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

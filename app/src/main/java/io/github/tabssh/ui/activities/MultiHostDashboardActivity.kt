package io.github.tabssh.ui.activities

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.performance.PerformanceMetrics
import io.github.tabssh.ssh.connection.SSHConnection
import io.github.tabssh.storage.database.entities.ConnectionGroup
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Multi-host monitoring dashboard.
 *
 * ## Architecture
 *
 * - **Foreground metrics**: each selected host gets its own [SSHConnection] +
 *   [MetricsCollector] pump while this activity is visible.
 * - **Background availability**: [HostAvailabilityWorker] (WorkManager) runs
 *   TCP probes every 15 min independently of this screen.
 *
 * ## Groups
 *
 * Hosts are displayed under collapsible group headers derived from
 * [ConnectionProfile.groupId]. The toolbar "Add group" action creates a new
 * [ConnectionGroup]; long-pressing a header renames it.
 *
 * ## Monitor config
 *
 * Long-pressing a host card (or tapping "Monitor settings" in
 * [HostDetailActivity]) opens [showMonitorConfigDialog] which reads/writes a
 * [MonitorSlot] row.
 */
class MultiHostDashboardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MultiHostDash"
        private const val MENU_ADD_GROUP  = 1001
        private const val MENU_PICK_HOSTS = 1002
        private const val REFRESH_MS = 5_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val PREF_SELECTED  = "multihost_selected_ids"
        private const val PREF_COLLAPSED = "multihost_collapsed_groups"
        private const val UNGROUPED = "__ungrouped__"

        /**
         * Show a monitor-configuration dialog for [profile].
         *
         * Exposed as a companion fun so [HostDetailActivity] can reuse it
         * without duplicating the dialog logic.
         *
         * @param existing  The current [MonitorSlot] for this profile, or null
         *                  if no slot has been created yet.
         * @param onSaved   Called with the updated (or newly created) slot after
         *                  the user confirms. The slot is already written to the
         *                  database before this callback fires.
         */
        fun showMonitorConfigDialog(
            context: Context,
            profile: ConnectionProfile,
            existing: MonitorSlot?,
            onSaved: (MonitorSlot) -> Unit = {}
        ) {
            val app = TabSSHApplication.get()
            val slot = existing ?: MonitorSlot(connectionId = profile.id)

            // ── Dialog layout ─────────────────────────────────────────────────
            val scroll = ScrollView(context)
            val form = LinearLayout(context).apply {
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

            // Master enable toggle
            val cbEnabled = CheckBox(context).apply {
                text = "Enable monitoring for this host"
                isChecked = slot.enabled
            }
            form.addView(cbEnabled)

            // Alert on down
            val cbDown = CheckBox(context).apply {
                text = "Notify when host is unreachable"
                isChecked = slot.alertOnDown
            }
            form.addView(cbDown)

            // Alert on recovery
            val cbUp = CheckBox(context).apply {
                text = "Notify when host recovers"
                isChecked = slot.alertOnRecovery
            }
            form.addView(cbUp)

            // Check interval spinner
            form.addView(label("Check interval"))
            val intervalOptions = arrayOf("15 min", "30 min", "1 hour", "4 hours", "12 hours")
            val intervalValues  = intArrayOf(15, 30, 60, 240, 720)
            val intervalAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, intervalOptions)
            intervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val spInterval = Spinner(context).apply { adapter = intervalAdapter }
            val intervalIdx = intervalValues.indexOfFirst { it >= slot.checkIntervalMinutes }.coerceAtLeast(0)
            spInterval.setSelection(intervalIdx)
            form.addView(spInterval)

            // Alert cooldown spinner
            form.addView(label("Alert cooldown (min gap between repeat alerts)"))
            val cooldownOptions = arrayOf("15 min", "30 min", "1 hour", "4 hours", "24 hours")
            val cooldownValues  = intArrayOf(15, 30, 60, 240, 1440)
            val cooldownAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, cooldownOptions)
            cooldownAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            val spCooldown = Spinner(context).apply { adapter = cooldownAdapter }
            val cooldownIdx = cooldownValues.indexOfFirst { it >= slot.alertCooldownMinutes }.coerceAtLeast(0)
            spCooldown.setSelection(cooldownIdx)
            form.addView(spCooldown)

            // Performance checks opt-in
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
            val sbCpu = SeekBar(context).apply {
                max = 100
                progress = slot.cpuThreshold ?: 0
            }
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
            val sbMem = SeekBar(context).apply {
                max = 100
                progress = slot.memoryThreshold ?: 0
            }
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
            val sbDisk = SeekBar(context).apply {
                max = 100
                progress = slot.diskThreshold ?: 0
            }
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
                        enabled               = cbEnabled.isChecked,
                        alertOnDown           = cbDown.isChecked,
                        alertOnRecovery       = cbUp.isChecked,
                        checkIntervalMinutes  = intervalValues[spInterval.selectedItemPosition],
                        alertCooldownMinutes  = cooldownValues[spCooldown.selectedItemPosition],
                        enablePerformanceChecks = cbPerf.isChecked,
                        cpuThreshold          = sbCpu.progress.takeIf { it > 0 },
                        memoryThreshold       = sbMem.progress.takeIf { it > 0 },
                        diskThreshold         = sbDisk.progress.takeIf { it > 0 }
                    )
                    app.applicationScope.launch(Dispatchers.IO) {
                        app.database.monitorSlotDao().insertOrReplace(updated)
                        withContext(Dispatchers.Main) { onSaved(updated) }
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

        private fun dp(context: Context, v: Int) =
            (v * context.resources.displayMetrics.density).toInt()
    }

    private lateinit var app: TabSSHApplication
    private lateinit var listContainer: LinearLayout
    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()
    private val ownedSessions = mutableMapOf<String, SSHConnection>()
    private val cards = mutableMapOf<String, HostCard>()
    private val groupHeaders = mutableMapOf<String, GroupHeader>()
    private val collapsedGroups = mutableSetOf<String>()
    private var groupCache = emptyList<ConnectionGroup>()
    private val monitorSlots = mutableMapOf<String, MonitorSlot>() // keyed by connectionId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val toolbar = Toolbar(this).apply {
            title = "Multi-host Dashboard"
            setBackgroundResource(R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val pickBtn = Button(this).apply {
            text = "Pick hosts to monitor…"
            isAllCaps = false
        }
        pickBtn.setOnClickListener { showHostPicker() }
        root.addView(pickBtn)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8))
        }
        scroll.addView(listContainer)
        root.addView(scroll)
        setContentView(root)

        collapsedGroups.clear()
        collapsedGroups.addAll(prefSet(PREF_COLLAPSED))

        val saved = prefSet(PREF_SELECTED)
        if (saved.isNotEmpty()) restoreSelection(saved) else showHostPicker()
    }

    // ── Options menu ─────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD_GROUP,   0, "Add group").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_PICK_HOSTS,  1, "Pick hosts").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        MENU_ADD_GROUP    -> { showAddGroupDialog(); true }
        MENU_PICK_HOSTS   -> { showHostPicker(); true }
        else              -> super.onOptionsItemSelected(item)
    }

    // ── Group management ─────────────────────────────────────────────────────

    private fun showAddGroupDialog() {
        val et = EditText(this).apply {
            hint = "Group name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { toast("Group name cannot be empty"); return@setPositiveButton }
                lifecycleScope.launch {
                    val g = ConnectionGroup(name = name)
                    app.database.connectionGroupDao().insertGroup(g)
                    groupCache = app.database.connectionGroupDao().getAllGroups().first()
                    runOnUiThread { toast("Group \"$name\" created") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameGroupDialog(group: ConnectionGroup) {
        val et = EditText(this).apply {
            setText(group.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setSelection(group.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename group")
            .setView(et)
            .setPositiveButton("Rename") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isBlank()) { toast("Group name cannot be empty"); return@setPositiveButton }
                lifecycleScope.launch {
                    app.database.connectionGroupDao().updateGroup(group.copy(name = name))
                    groupCache = app.database.connectionGroupDao().getAllGroups().first()
                    // Re-render so the updated name shows immediately.
                    val want = jobs.keys.mapNotNull { id -> cards[id]?.profile }
                    runOnUiThread { applySelection(want, persist = false) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Host picker ──────────────────────────────────────────────────────────

    private fun restoreSelection(savedIds: Set<String>) {
        lifecycleScope.launch {
            try {
                groupCache = app.database.connectionGroupDao().getAllGroups().first()
                val all = app.database.connectionDao().getAllConnectionsList()
                val want = all.filter { it.id in savedIds }
                loadMonitorSlots(want.map { it.id })
                runOnUiThread { applySelection(want, persist = false) }
            } catch (e: Exception) {
                Logger.e(TAG, "Restore selection failed", e)
            }
        }
    }

    private fun showHostPicker() {
        lifecycleScope.launch {
            val all = try {
                app.database.connectionDao().getRecentConnections(50)
            } catch (e: Exception) {
                Logger.e(TAG, "Recent fetch failed", e); emptyList()
            }
            try {
                groupCache = app.database.connectionGroupDao().getAllGroups().first()
            } catch (_: Exception) { /* picker still works without group names */ }

            if (all.isEmpty()) {
                runOnUiThread { toast("No saved connections") }
                return@launch
            }
            val labels = all.map { p ->
                val g = groupCache.firstOrNull { it.id == p.groupId }
                if (g != null) "${g.name} / ${p.getDisplayName()}" else p.getDisplayName()
            }.toTypedArray()
            val checked = BooleanArray(all.size) { i -> jobs.containsKey(all[i].id) }
            runOnUiThread {
                AlertDialog.Builder(this@MultiHostDashboardActivity)
                    .setTitle("Pick hosts")
                    .setMultiChoiceItems(labels, checked) { _, idx, isChecked -> checked[idx] = isChecked }
                    .setPositiveButton("Apply") { _, _ ->
                        val want = all.filterIndexed { i, _ -> checked[i] }
                        lifecycleScope.launch {
                            loadMonitorSlots(want.map { it.id })
                            runOnUiThread { applySelection(want, persist = true) }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private suspend fun loadMonitorSlots(connectionIds: List<String>) {
        for (id in connectionIds) {
            val slot = app.database.monitorSlotDao().getByConnectionId(id)
            if (slot != null) monitorSlots[id] = slot
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun applySelection(want: List<ConnectionProfile>, persist: Boolean) {
        val keepIds = want.map { it.id }.toSet()
        jobs.keys.toList().filter { it !in keepIds }.forEach { stopHost(it, persistChange = false) }

        listContainer.removeAllViews()
        groupHeaders.clear()

        val byGroup   = want.groupBy { it.groupId ?: UNGROUPED }
        val groupOrder = byGroup.keys.sortedWith(compareBy({ it == UNGROUPED }, { groupName(it) }))
        for (gid in groupOrder) {
            val members = byGroup[gid].orEmpty()
            if (gid != UNGROUPED) {
                val header = GroupHeader(gid, groupName(gid), members.size)
                groupHeaders[gid] = header
                listContainer.addView(header.view)
                if (gid in collapsedGroups) {
                    members.forEach { p -> ensurePumpFor(p); cards[p.id]?.view?.visibility = View.GONE }
                    continue
                }
            }
            for (p in members) {
                ensurePumpFor(p)
                cards[p.id]?.view?.let {
                    it.visibility = View.VISIBLE
                    listContainer.addView(it)
                }
            }
        }

        if (persist) prefPutSet(PREF_SELECTED, keepIds)
    }

    private fun ensurePumpFor(profile: ConnectionProfile) {
        if (!cards.containsKey(profile.id)) {
            cards[profile.id] = HostCard(profile)
            // Refresh status dot from any previously loaded MonitorSlot.
            monitorSlots[profile.id]?.let { cards[profile.id]?.updateMonitorStatus(it) }
        }
        if (!jobs.containsKey(profile.id)) {
            jobs[profile.id] = pumpScope.launch { runHostPump(profile) }
        }
    }

    private suspend fun runHostPump(profile: ConnectionProfile) {
        val card = cards[profile.id] ?: return
        val ssh  = openOrReuseSession(profile)
        if (ssh == null) {
            runOnUiThread { card.setError("connect failed") }
            return
        }
        val collector = MetricsCollector(ssh)
        while (true) {
            if (!ssh.isConnected()) {
                runOnUiThread { card.setError("disconnected") }
                return
            }
            val r = runCatching { collector.collectMetrics() }
            runOnUiThread {
                if (r.isSuccess) r.getOrNull()?.let { m ->
                    m.onSuccess { card.update(it) }.onFailure { e -> card.setError(e.message ?: "error") }
                }
                else card.setError(r.exceptionOrNull()?.message ?: "error")
            }
            if (r.isFailure && !ssh.isConnected()) return
            delay(REFRESH_MS)
        }
    }

    private fun stopHost(id: String, persistChange: Boolean) {
        jobs.remove(id)?.cancel()
        ownedSessions.remove(id)?.let {
            try { it.disconnect() } catch (e: Exception) { Logger.w(TAG, "disconnect: ${e.message}") }
        }
        cards.remove(id)?.let { listContainer.removeView(it.view) }
        if (persistChange) {
            val current = prefSet(PREF_SELECTED).toMutableSet().also { it.remove(id) }
            prefPutSet(PREF_SELECTED, current)
        }
    }

    private suspend fun openOrReuseSession(profile: ConnectionProfile): SSHConnection? {
        app.sshSessionManager.getConnection(profile.id)?.let { return it }
        val s = app.sshSessionManager.connectToServer(profile)
        if (s != null) ownedSessions[profile.id] = s
        return s
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
        ownedSessions.values.forEach {
            try { it.disconnect() } catch (e: Exception) { Logger.w(TAG, "onDestroy disconnect: ${e.message}") }
        }
        ownedSessions.clear()
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    /** Group heading row. Tap = collapse/expand. Long-press = rename. */
    private inner class GroupHeader(val groupId: String, name: String, count: Int) {
        val view: View
        private val title: TextView

        init {
            val row = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(4))
                setBackgroundColor(0xFF101820.toInt())
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.bottomMargin = dp(4)
                layoutParams = lp
                isClickable = true
                isFocusable = true
                isLongClickable = true
            }
            val collapsed = groupId in collapsedGroups
            val arrow = if (collapsed) "▶" else "▼"
            title = TextView(this@MultiHostDashboardActivity).apply {
                text = "$arrow  $name / $count host${if (count == 1) "" else "s"}"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            // Rename hint label on the right
            val hint = TextView(this@MultiHostDashboardActivity).apply {
                text = "✎"
                setTextColor(0xFF555555.toInt())
                textSize = 16f
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(title)
            row.addView(hint)
            row.setOnClickListener { toggleCollapse() }
            row.setOnLongClickListener {
                val group = groupCache.firstOrNull { it.id == groupId }
                if (group != null) showRenameGroupDialog(group) else toast("Group not found")
                true
            }
            view = row
        }

        private fun toggleCollapse() {
            if (groupId in collapsedGroups) collapsedGroups.remove(groupId)
            else collapsedGroups.add(groupId)
            prefPutSet(PREF_COLLAPSED, collapsedGroups)
            val want = jobs.keys.mapNotNull { id -> cards[id]?.profile }
            applySelection(want, persist = false)
        }
    }

    /** One row per monitored host. Tap = detail view. Long-press = monitor config. */
    private inner class HostCard(val profile: ConnectionProfile) {
        val view: View
        private val title: TextView
        private val cpu: TextView
        private val mem: TextView
        private val load: TextView
        private val status: TextView
        private val statusDot: TextView

        init {
            val card = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12))
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setBackgroundColor(0xFF1A1A1A.toInt())
                isClickable = true
                isFocusable = true
                isLongClickable = true
            }

            // Title row: status dot + host name
            val titleRow = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            statusDot = TextView(this@MultiHostDashboardActivity).apply {
                text = "●"
                textSize = 12f
                setTextColor(0xFF888888.toInt())      // grey = unknown
                setPadding(0, 0, dp(6), 0)
            }
            title = TextView(this@MultiHostDashboardActivity).apply {
                text = profile.getDisplayName()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            // Bell icon — tap = monitor config
            val bellBtn = TextView(this@MultiHostDashboardActivity).apply {
                text = "🔔"
                textSize = 16f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showMonitorConfigDialog(
                        this@MultiHostDashboardActivity,
                        profile,
                        monitorSlots[profile.id]
                    ) { updated ->
                        monitorSlots[profile.id] = updated
                        updateMonitorStatus(updated)
                    }
                }
            }
            titleRow.addView(statusDot)
            titleRow.addView(title)
            titleRow.addView(bellBtn)

            val row = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            cpu   = metric("CPU: —")
            mem   = metric("MEM: —")
            load  = metric("LOAD: —")
            row.addView(cpu); row.addView(mem); row.addView(load)
            status = TextView(this@MultiHostDashboardActivity).apply {
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 11f
            }
            card.addView(titleRow)
            card.addView(row)
            card.addView(status)

            // Tap → detail screen
            card.setOnClickListener {
                HostDetailActivity.start(this@MultiHostDashboardActivity, profile.id)
            }

            // Long-press → monitor config
            card.setOnLongClickListener {
                showMonitorConfigDialog(
                    this@MultiHostDashboardActivity,
                    profile,
                    monitorSlots[profile.id]
                ) { updated ->
                    monitorSlots[profile.id] = updated
                    updateMonitorStatus(updated)
                }
                true
            }

            view = card
        }

        private fun metric(initial: String) = TextView(this@MultiHostDashboardActivity).apply {
            text = initial
            setTextColor(0xFFCCCCCC.toInt())
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }

        fun update(m: PerformanceMetrics) {
            cpu.text    = "CPU: %.0f%%".format(m.cpuUsage.totalPercent)
            mem.text    = "MEM: %.0f%%".format(m.memoryUsage.usedPercent)
            load.text   = "LOAD: %.2f".format(m.loadAverage.load1min)
            status.text = "${m.platformInfo.getDisplayName()} · live"
        }

        fun setError(msg: String) {
            cpu.text = "CPU: ?"; mem.text = "MEM: ?"; load.text = "LOAD: ?"
            status.text = "error: $msg"
        }

        /** Refresh the coloured status dot from the latest [MonitorSlot] state. */
        fun updateMonitorStatus(slot: MonitorSlot) {
            statusDot.setTextColor(when {
                !slot.enabled         -> 0xFF888888.toInt()  // grey — not monitored
                slot.isCurrentlyDown  -> 0xFFFF4444.toInt()  // red  — down
                slot.lastSeenUp > 0   -> 0xFF44CC44.toInt()  // green — confirmed up
                else                  -> 0xFF888888.toInt()  // grey — never checked yet
            })
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun groupName(id: String): String =
        if (id == UNGROUPED) "Ungrouped"
        else groupCache.firstOrNull { it.id == id }?.name ?: "Group"

    private fun toast(msg: String) =
        Toast.makeText(this@MultiHostDashboardActivity, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun prefSet(key: String): Set<String> {
        val csv = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this).getString(key, "") ?: ""
        return csv.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun prefPutSet(key: String, value: Set<String>) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .edit().putString(key, value.joinToString(",")).apply()
    }

}


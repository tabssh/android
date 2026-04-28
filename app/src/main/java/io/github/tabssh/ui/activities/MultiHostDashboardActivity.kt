package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
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
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wave 4.g + persistence/grouping update.
 *
 * Selection persists across activity restarts via the
 * `multihost_selected_ids` pref. Cards render grouped by
 * `ConnectionProfile.groupId`: each group becomes a header row that
 * collapses (`groupname / N hosts`) on tap; ungrouped hosts render
 * flat at the bottom. Collapse state is per-group, also persisted.
 *
 * Per-host monitoring is unchanged from before — each host gets its
 * own SSHConnection + MetricsCollector + repeat job; slow / dead
 * hosts don't block the others.
 */
class MultiHostDashboardActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "MultiHostDash"
        private const val REFRESH_MS = 5000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val PREF_SELECTED = "multihost_selected_ids"
        private const val PREF_COLLAPSED = "multihost_collapsed_groups"
        private const val UNGROUPED = "__ungrouped__"
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

        // `isAllCaps = false` keeps the button text in sentence case to
        // match the rest of the app — Material Button defaults to ALL
        // CAPS in many themes which clashes with the "Pick hosts to
        // monitor…" sentence case used everywhere else.
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
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Restore collapse state.
        collapsedGroups.clear()
        collapsedGroups.addAll(prefSet(PREF_COLLAPSED))

        // Auto-restore previous selection. If nothing saved, prompt.
        val saved = prefSet(PREF_SELECTED)
        if (saved.isNotEmpty()) {
            restoreSelection(saved)
        } else {
            showHostPicker()
        }
    }

    private fun restoreSelection(savedIds: Set<String>) {
        lifecycleScope.launch {
            try {
                groupCache = app.database.connectionGroupDao().getAllGroups().first()
                val all = app.database.connectionDao().getAllConnectionsList()
                val want = all.filter { it.id in savedIds }
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
                Logger.e(TAG, "Recent fetch failed", e)
                emptyList()
            }
            try {
                groupCache = app.database.connectionGroupDao().getAllGroups().first()
            } catch (_: Exception) { /* picker still works without group names */ }

            if (all.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@MultiHostDashboardActivity, "No saved connections", Toast.LENGTH_SHORT).show()
                }
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
                    .setMultiChoiceItems(labels, checked) { _, idx, isChecked ->
                        checked[idx] = isChecked
                    }
                    .setPositiveButton("Apply") { _, _ ->
                        val want = all.filterIndexed { i, _ -> checked[i] }
                        applySelection(want, persist = true)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun applySelection(want: List<ConnectionProfile>, persist: Boolean) {
        // Stop hosts no longer wanted.
        val keepIds = want.map { it.id }.toSet()
        jobs.keys.toList().filter { it !in keepIds }.forEach { stopHost(it, persistChange = false) }

        // Wipe and re-render container so groups stay sorted as they
        // change. Remember which jobs are already alive — we don't
        // restart their pumps, just re-attach the existing cards.
        listContainer.removeAllViews()
        groupHeaders.clear()

        val byGroup = want.groupBy { it.groupId ?: UNGROUPED }

        // Stable order: named groups alphabetical, then ungrouped last.
        val groupOrder = byGroup.keys.sortedWith(compareBy({ it == UNGROUPED }, { groupName(it) }))
        for (gid in groupOrder) {
            val members = byGroup[gid].orEmpty()
            if (gid != UNGROUPED) {
                val header = GroupHeader(gid, groupName(gid), members.size)
                groupHeaders[gid] = header
                listContainer.addView(header.view)
                if (gid in collapsedGroups) {
                    members.forEach { p ->
                        // Still need the pump; just hide the card view.
                        ensurePumpFor(p)
                        cards[p.id]?.view?.visibility = View.GONE
                    }
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
        }
        if (!jobs.containsKey(profile.id)) {
            jobs[profile.id] = pumpScope.launch { runHostPump(profile) }
        }
    }

    private suspend fun runHostPump(profile: ConnectionProfile) {
        val card = cards[profile.id] ?: return
        val ssh = openOrReuseSession(profile)
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
            val r = try {
                collector.collectMetrics()
            } catch (e: Exception) {
                Result.failure<PerformanceMetrics>(e)
            }
            runOnUiThread {
                if (r.isSuccess) card.update(r.getOrThrow())
                else card.setError(r.exceptionOrNull()?.message ?: "error")
            }
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

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
        ownedSessions.values.forEach {
            try { it.disconnect() } catch (e: Exception) { Logger.w(TAG, "onDestroy disconnect: ${e.message}") }
        }
        ownedSessions.clear()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun prefSet(key: String): Set<String> {
        val csv = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .getString(key, "") ?: ""
        return csv.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun prefPutSet(key: String, value: Set<String>) {
        androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this)
            .edit()
            .putString(key, value.joinToString(","))
            .apply()
    }

    private fun groupName(id: String): String =
        if (id == UNGROUPED) "Ungrouped" else groupCache.firstOrNull { it.id == id }?.name ?: "Group"

    /** Group heading row — tap to collapse / expand. Reflows the layout. */
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
            }
            val collapsed = groupId in collapsedGroups
            val arrow = if (collapsed) "▶" else "▼"
            title = TextView(this@MultiHostDashboardActivity).apply {
                text = "$arrow  $name / $count host${if (count == 1) "" else "s"}"
                setTextColor(0xFFCCCCCC.toInt())
                textSize = 14f
            }
            row.addView(title)
            row.setOnClickListener { toggleCollapse() }
            view = row
        }

        private fun toggleCollapse() {
            if (groupId in collapsedGroups) collapsedGroups.remove(groupId)
            else collapsedGroups.add(groupId)
            prefPutSet(PREF_COLLAPSED, collapsedGroups)
            // Re-apply the current selection so the layout reflows; we
            // don't restart any pumps, just show/hide cards.
            val want = jobs.keys.mapNotNull { id -> cards[id]?.profile }
            applySelection(want, persist = false)
        }
    }

    /** One row per monitored host. UI is plain TextViews — keep it tight. */
    private inner class HostCard(val profile: ConnectionProfile) {
        val view: View
        private val title: TextView
        private val cpu: TextView
        private val mem: TextView
        private val load: TextView
        private val status: TextView

        init {
            val card = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12))
                val lp = LinearLayout.LayoutParams(MATCH, WRAP)
                lp.bottomMargin = dp(8)
                layoutParams = lp
                setBackgroundColor(0xFF1A1A1A.toInt())
            }
            title = TextView(this@MultiHostDashboardActivity).apply {
                text = profile.getDisplayName()
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
            }
            val row = LinearLayout(this@MultiHostDashboardActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            cpu = metric("CPU: —")
            mem = metric("MEM: —")
            load = metric("LOAD: —")
            row.addView(cpu); row.addView(mem); row.addView(load)
            status = TextView(this@MultiHostDashboardActivity).apply {
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 11f
            }
            card.addView(title); card.addView(row); card.addView(status)
            view = card
        }

        private fun metric(initial: String) = TextView(this@MultiHostDashboardActivity).apply {
            text = initial
            setTextColor(0xFFCCCCCC.toInt())
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            layoutParams = lp
        }

        fun update(m: PerformanceMetrics) {
            cpu.text = "CPU: %.0f%%".format(m.cpuUsage.totalPercent)
            mem.text = "MEM: %.0f%%".format(m.memoryUsage.usedPercent)
            load.text = "LOAD: %.2f".format(m.loadAverage.load1min)
            status.text = "${m.platformInfo.getDisplayName()} · live"
        }

        fun setError(msg: String) {
            cpu.text = "CPU: ?"; mem.text = "MEM: ?"; load.text = "LOAD: ?"
            status.text = "error: $msg"
        }
    }
}

package io.github.tabssh.ui.activities

import android.content.Intent
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
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wave 4.g — Multi-host performance dashboard.
 *
 * Pick N saved connections and watch CPU% / mem% / load1 update on each
 * card every 5s. Hosts run independently — slow / dead hosts don't block
 * others' updates.
 *
 * Implementation:
 *  - Each host gets its own SSHConnection + MetricsCollector + repeat job.
 *  - Cards live in a vertical LinearLayout inside a ScrollView. Quick to
 *    add/remove without RecyclerView ceremony.
 *  - On finish() we cancel every job and disconnect each SSH session that
 *    we opened ourselves; sessions reused from SSHSessionManager are left
 *    alone for whoever opened them first.
 */
class MultiHostDashboardActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "MultiHostDash"
        private const val REFRESH_MS = 5000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var app: TabSSHApplication
    private lateinit var listContainer: LinearLayout
    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()
    private val ownedSessions = mutableMapOf<String, SSHConnection>()
    private val cards = mutableMapOf<String, HostCard>()

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

        val pickBtn = Button(this).apply { text = "Pick hosts to monitor…" }
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

        // Auto-prompt on first open.
        showHostPicker()
    }

    private fun showHostPicker() {
        lifecycleScope.launch {
            val all = try {
                app.database.connectionDao().getRecentConnections(50)
            } catch (e: Exception) {
                Logger.e(TAG, "Recent fetch failed", e)
                emptyList()
            }
            if (all.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this@MultiHostDashboardActivity, "No saved connections", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val labels = all.map { it.getDisplayName() }.toTypedArray()
            val checked = BooleanArray(all.size) { i -> jobs.containsKey(all[i].id) }
            runOnUiThread {
                AlertDialog.Builder(this@MultiHostDashboardActivity)
                    .setTitle("Pick hosts")
                    .setMultiChoiceItems(labels, checked) { _, idx, isChecked ->
                        checked[idx] = isChecked
                    }
                    .setPositiveButton("Apply") { _, _ ->
                        val want = all.filterIndexed { i, _ -> checked[i] }
                        applySelection(want)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun applySelection(want: List<ConnectionProfile>) {
        // Stop hosts no longer wanted.
        val keepIds = want.map { it.id }.toSet()
        jobs.keys.toList().filter { it !in keepIds }.forEach { stopHost(it) }
        // Start newly wanted.
        for (p in want) {
            if (!jobs.containsKey(p.id)) startHost(p)
        }
    }

    private fun startHost(profile: ConnectionProfile) {
        val card = HostCard(profile).also { cards[profile.id] = it }
        listContainer.addView(card.view)

        jobs[profile.id] = pumpScope.launch {
            val ssh = openOrReuseSession(profile)
            if (ssh == null) {
                runOnUiThread { card.setError("connect failed") }
                return@launch
            }
            val collector = MetricsCollector(ssh)
            while (true) {
                if (!ssh.isConnected()) {
                    // Server closed the SSH session — stop polling, show
                    // disconnected, exit. Otherwise we'd spam executeCommand
                    // failures forever (one per metric × N hosts).
                    runOnUiThread { card.setError("disconnected") }
                    return@launch
                }
                val r = try {
                    collector.collectMetrics()
                } catch (e: Exception) {
                    Result.failure<PerformanceMetrics>(e)
                }
                runOnUiThread {
                    if (r.isSuccess) card.update(r.getOrThrow()) else card.setError(r.exceptionOrNull()?.message ?: "error")
                }
                delay(REFRESH_MS)
            }
        }
    }

    private fun stopHost(id: String) {
        jobs.remove(id)?.cancel()
        ownedSessions.remove(id)?.let {
            try { it.disconnect() } catch (e: Exception) { Logger.w(TAG, "disconnect: ${e.message}") }
        }
        cards.remove(id)?.let { listContainer.removeView(it.view) }
    }

    private suspend fun openOrReuseSession(profile: ConnectionProfile): SSHConnection? {
        // Reuse if already in SessionManager (user already opened terminal).
        app.sshSessionManager.getConnection(profile.id)?.let { return it }
        // Otherwise open and remember so we can clean up on finish.
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

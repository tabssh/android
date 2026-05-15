package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.performance.MetricsCollector
import io.github.tabssh.performance.PerformanceMetrics
import io.github.tabssh.storage.database.entities.ConnectionProfile
import io.github.tabssh.storage.database.entities.MonitorSlot
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-host performance detail screen.
 *
 * Launched from [MultiHostDashboardActivity] when the user taps a host card.
 * Opens (or reuses) an SSH session for the target host and shows a live
 * metrics view with:
 *   - CPU / memory / disk / load gauges
 *   - Network rx/tx rates
 *   - Platform info (OS, hostname)
 *   - Monitoring status (up/down, last checked, last seen up)
 *   - Quick-access toolbar actions: "Connect" (launch terminal) and
 *     "Monitor settings" (configure alert thresholds for this host)
 */
class HostDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HostDetailActivity"
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val REFRESH_MS = 5_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun start(context: Context, profileId: String) {
            context.startActivity(
                Intent(context, HostDetailActivity::class.java)
                    .putExtra(EXTRA_PROFILE_ID, profileId)
            )
        }
    }

    private lateinit var app: TabSSHApplication
    private var profile: ConnectionProfile? = null
    private var monitorSlot: MonitorSlot? = null

    // UI refs
    private lateinit var tvStatus: TextView
    private lateinit var tvLastChecked: TextView
    private lateinit var tvLastSeenUp: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvDisk: TextView
    private lateinit var tvLoad: TextView
    private lateinit var tvNetRx: TextView
    private lateinit var tvNetTx: TextView
    private lateinit var tvPlatform: TextView

    private val pumpScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pumpJob: Job? = null

    private val dateFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run {
            finish(); return
        }

        buildUi()
        loadAndStart(profileId)
    }

    // ── UI construction ──────────────────────────────────────────────────────

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        val toolbar = Toolbar(this).apply {
            title = "Host detail"
            setBackgroundResource(R.color.primary_500)
            setTitleTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Inflate menu items after setContentView (below).
        // Actions are added programmatically to avoid XML inflation here.

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        // ── Monitoring status card ────────────────────────────────────────────
        content.addView(sectionHeader("Monitoring"))
        tvStatus      = infoRow(content, "Status",       "—")
        tvLastChecked = infoRow(content, "Last checked", "—")
        tvLastSeenUp  = infoRow(content, "Last seen up", "—")

        // ── Live metrics card ─────────────────────────────────────────────────
        content.addView(sectionHeader("Live metrics"))
        tvCpu    = infoRow(content, "CPU",      "—")
        tvMem    = infoRow(content, "Memory",   "—")
        tvDisk   = infoRow(content, "Disk",     "—")
        tvLoad   = infoRow(content, "Load avg", "—")
        tvNetRx  = infoRow(content, "Net RX",   "—")
        tvNetTx  = infoRow(content, "Net TX",   "—")

        // ── Platform card ─────────────────────────────────────────────────────
        content.addView(sectionHeader("Platform"))
        tvPlatform = infoRow(content, "System", "—")

        // ── Action buttons ────────────────────────────────────────────────────
        content.addView(sectionHeader("Actions"))

        val connectBtn = actionButton("Connect (open terminal)")
        connectBtn.setOnClickListener { launchTerminal() }
        content.addView(connectBtn)

        val monitorBtn = actionButton("Monitor settings")
        monitorBtn.setOnClickListener { openMonitorSettings() }
        content.addView(monitorBtn)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun sectionHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(this@HostDetailActivity, R.color.on_surface_variant))
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(16)
        lp.bottomMargin = dp(4)
        layoutParams = lp
    }

    /**
     * Creates a two-column row (label on the left, value on the right) and
     * appends it to [parent]. Returns the value [TextView] so the caller can
     * update it later.
     */
    private fun infoRow(parent: LinearLayout, label: String, initial: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH, WRAP)
            lp.bottomMargin = dp(4)
            layoutParams = lp
            setBackgroundColor(ContextCompat.getColor(this@HostDetailActivity, R.color.surface_variant))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val labelTv = TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@HostDetailActivity, R.color.on_surface_variant))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 0.4f)
        }
        val valueTv = TextView(this).apply {
            text = initial
            setTextColor(ContextCompat.getColor(this@HostDetailActivity, R.color.on_surface))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 0.6f)
        }
        row.addView(labelTv)
        row.addView(valueTv)
        parent.addView(row)
        return valueTv
    }

    private fun actionButton(label: String) = android.widget.Button(this).apply {
        text = label
        isAllCaps = false
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.topMargin = dp(8)
        layoutParams = lp
    }

    // ── Data loading and live pump ───────────────────────────────────────────

    private fun loadAndStart(profileId: String) {
        lifecycleScope.launch {
            profile = app.database.connectionDao().getConnectionById(profileId)
            monitorSlot = app.database.monitorSlotDao().getByConnectionId(profileId)

            val p = profile ?: run {
                Logger.e(TAG, "Profile $profileId not found")
                finish()
                return@launch
            }

            runOnUiThread {
                supportActionBar?.title = p.getDisplayName()
                updateMonitoringStatus()
            }

            startPump(p)
        }
    }

    private fun updateMonitoringStatus() {
        val slot = monitorSlot
        if (slot == null) {
            tvStatus.text = "Not monitored"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
            tvLastChecked.text = "—"
            tvLastSeenUp.text = "—"
            return
        }
        if (!slot.enabled) {
            tvStatus.text = "Disabled"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        } else if (slot.isCurrentlyDown) {
            tvStatus.text = "DOWN — ${slot.consecutiveFailures} consecutive failure(s)"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
        } else {
            tvStatus.text = "UP"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        }
        tvLastChecked.text = if (slot.lastCheckedAt > 0) dateFmt.format(Date(slot.lastCheckedAt)) else "Never"
        tvLastSeenUp.text  = if (slot.lastSeenUp  > 0) dateFmt.format(Date(slot.lastSeenUp))    else "Unknown"
    }

    private fun startPump(profile: ConnectionProfile) {
        pumpJob?.cancel()
        pumpJob = pumpScope.launch {
            val ssh = app.sshSessionManager.getConnection(profile.id)
                ?: app.sshSessionManager.connectToServer(profile)
            if (ssh == null) {
                runOnUiThread {
                    tvCpu.text = "—"; tvMem.text = "—"; tvDisk.text = "—"
                    tvLoad.text = "—"; tvNetRx.text = "—"; tvNetTx.text = "—"
                    tvPlatform.text = "Could not connect"
                }
                return@launch
            }
            val collector = MetricsCollector(ssh)
            while (true) {
                if (!ssh.isConnected()) break
                val r = runCatching { collector.collectMetrics() }
                val metrics = r.getOrNull()?.getOrNull()
                runOnUiThread { if (metrics != null) updateMetrics(metrics) }
                delay(REFRESH_MS)
            }
        }
    }

    private fun updateMetrics(m: PerformanceMetrics) {
        tvCpu.text  = "%.0f%%  (user %.0f%%  sys %.0f%%  iowait %.0f%%)".format(
            m.cpuUsage.totalPercent,
            m.cpuUsage.userPercent,
            m.cpuUsage.systemPercent,
            m.cpuUsage.iowaitPercent
        )
        tvMem.text  = "%.0f%%  (%d MB used / %d MB total)".format(
            m.memoryUsage.usedPercent,
            m.memoryUsage.usedMB,
            m.memoryUsage.totalMB
        )
        tvDisk.text = m.diskUsage.let { "%.0f%%  (%.1f GB free)".format(
            it.usedPercent, it.availableGB
        ) }
        tvLoad.text = "%.2f  %.2f  %.2f  (1/5/15 min)".format(
            m.loadAverage.load1min,
            m.loadAverage.load5min,
            m.loadAverage.load15min
        )
        tvNetRx.text = "${formatBytes(m.networkStats.rxBytesPerSec)}/s"
        tvNetTx.text = "${formatBytes(m.networkStats.txBytesPerSec)}/s"
        tvPlatform.text = m.platformInfo.getDisplayName()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1_024          -> "$bytes B"
        bytes < 1_048_576      -> "%.1f KB".format(bytes / 1_024.0)
        bytes < 1_073_741_824  -> "%.1f MB".format(bytes / 1_048_576.0)
        else                   -> "%.1f GB".format(bytes / 1_073_741_824.0)
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun launchTerminal() {
        val p = profile ?: return
        startActivity(
            TabTerminalActivity.createIntent(this, p, autoConnect = true)
        )
    }

    private fun openMonitorSettings() {
        val p = profile ?: return
        // Delegate to the dashboard's reusable monitor-config dialog.
        MultiHostDashboardActivity.showMonitorConfigDialog(this, p, monitorSlot) { updated ->
            monitorSlot = updated
            updateMonitoringStatus()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

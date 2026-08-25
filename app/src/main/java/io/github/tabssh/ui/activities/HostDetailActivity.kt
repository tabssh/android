package io.github.tabssh.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import io.github.tabssh.utils.Format
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
import io.github.tabssh.utils.tabSSHApp

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
class HostDetailActivity : TabSSHActivity() {

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
        app = tabSSHApp

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

        // Shared app bar, inflated rather than hand-built so this programmatic
        // screen gets the same toolbar styling as every XML-defined screen.
        val appBar = layoutInflater.inflate(R.layout.include_app_bar, root, false)
        val toolbar = appBar.findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.host_detail_title)
        root.addView(appBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }

        val blank = getString(R.string.host_detail_value_unavailable)

        // ── Monitoring status card ────────────────────────────────────────────
        content.addView(sectionHeader(getString(R.string.host_detail_section_monitoring)))
        tvStatus      = infoRow(content, getString(R.string.host_detail_label_status), blank)
        tvLastChecked = infoRow(content, getString(R.string.host_detail_label_last_checked), blank)
        tvLastSeenUp  = infoRow(content, getString(R.string.host_detail_label_last_seen_up), blank)

        // ── Live metrics card ─────────────────────────────────────────────────
        content.addView(sectionHeader(getString(R.string.host_detail_section_live_metrics)))
        tvCpu    = infoRow(content, getString(R.string.host_detail_label_cpu), blank)
        tvMem    = infoRow(content, getString(R.string.host_detail_label_memory), blank)
        tvDisk   = infoRow(content, getString(R.string.host_detail_label_disk), blank)
        tvLoad   = infoRow(content, getString(R.string.host_detail_label_load), blank)
        tvNetRx  = infoRow(content, getString(R.string.host_detail_label_net_rx), blank)
        tvNetTx  = infoRow(content, getString(R.string.host_detail_label_net_tx), blank)

        // ── Platform card ─────────────────────────────────────────────────────
        content.addView(sectionHeader(getString(R.string.host_detail_section_platform)))
        tvPlatform = infoRow(content, getString(R.string.host_detail_label_system), blank)

        // ── Action buttons ────────────────────────────────────────────────────
        content.addView(sectionHeader(getString(R.string.host_detail_section_actions)))

        val connectBtn = actionButton(getString(R.string.host_detail_action_connect))
        connectBtn.setOnClickListener { launchTerminal() }
        content.addView(connectBtn)

        val monitorBtn = actionButton(getString(R.string.host_detail_action_monitor_settings))
        monitorBtn.setOnClickListener { openMonitorSettings() }
        content.addView(monitorBtn)

        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
        setSupportActionBar(toolbar)
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
        val blank = getString(R.string.host_detail_value_unavailable)
        if (slot == null) {
            tvStatus.text = getString(R.string.host_detail_status_not_monitored)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
            tvLastChecked.text = blank
            tvLastSeenUp.text = blank
            return
        }
        if (!slot.enabled) {
            tvStatus.text = getString(R.string.host_detail_status_disabled)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
        } else if (slot.isCurrentlyDown) {
            tvStatus.text = resources.getQuantityString(
                R.plurals.host_detail_status_down,
                slot.consecutiveFailures,
                slot.consecutiveFailures
            )
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        } else {
            tvStatus.text = getString(R.string.host_detail_status_up)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
        }
        tvLastChecked.text = if (slot.lastCheckedAt > 0) {
            dateFmt.format(Date(slot.lastCheckedAt))
        } else {
            getString(R.string.host_detail_never_checked)
        }
        tvLastSeenUp.text = if (slot.lastSeenUp > 0) {
            dateFmt.format(Date(slot.lastSeenUp))
        } else {
            getString(R.string.host_detail_last_seen_unknown)
        }
    }

    private fun startPump(profile: ConnectionProfile) {
        pumpJob?.cancel()
        pumpJob = pumpScope.launch {
            val ssh = app.sshSessionManager.connectForMonitoring(profile)
            if (ssh == null) {
                runOnUiThread {
                    val blank = getString(R.string.host_detail_value_unavailable)
                    tvCpu.text = blank; tvMem.text = blank; tvDisk.text = blank
                    tvLoad.text = blank; tvNetRx.text = blank; tvNetTx.text = blank
                    tvPlatform.text = getString(R.string.host_detail_connect_failed)
                }
                return@launch
            }
            val collector = MetricsCollector(ssh)
            while (true) {
                if (!ssh.isConnected()) break
                val r = runCatching { collector.collectMetrics() }
                    .onFailure { e -> Logger.w("HostDetailActivity", "Metrics collection threw — keeping last values", e) }
                val metrics = r.getOrNull()?.getOrNull()
                runOnUiThread { if (metrics != null) updateMetrics(metrics) }
                delay(REFRESH_MS)
            }
        }
    }

    private fun updateMetrics(m: PerformanceMetrics) {
        tvCpu.text = getString(
            R.string.host_detail_cpu_fmt,
            m.cpuUsage.totalPercent,
            m.cpuUsage.userPercent,
            m.cpuUsage.systemPercent,
            m.cpuUsage.iowaitPercent
        )
        tvMem.text = getString(
            R.string.host_detail_memory_fmt,
            m.memoryUsage.usedPercent,
            Format.size(this, m.memoryUsage.usedBytes),
            Format.size(this, m.memoryUsage.totalBytes)
        )
        tvDisk.text = getString(
            R.string.host_detail_disk_fmt,
            m.diskUsage.usedPercent,
            Format.size(this, m.diskUsage.availableBytes)
        )
        tvLoad.text = getString(
            R.string.host_detail_load_fmt,
            m.loadAverage.load1min,
            m.loadAverage.load5min,
            m.loadAverage.load15min
        )
        tvNetRx.text = Format.rate(this, m.networkStats.rxBytesPerSec)
        tvNetTx.text = Format.rate(this, m.networkStats.txBytesPerSec)
        tvPlatform.text = m.platformInfo.getDisplayName()
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

    override fun onDestroy() {
        super.onDestroy()
        pumpScope.cancel()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

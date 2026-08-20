package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.transport.ContainerAction
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.ui.dialogs.ContainerRenameDialog
import io.github.tabssh.ui.dialogs.ContainerErrorPresenter
import io.github.tabssh.ui.utils.ContainerExecLauncher
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.ui.views.SparklineView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Container detail screen: inspect, env/mounts/ports,
 * live logs (ANSI-stripped follow), and live stats with sparklines, plus the
 * full lifecycle action menu and the docker-exec terminal entry point.
 * FLAG_SECURE keeps env values and logs out of screenshots and recents.
 */
class ContainerDetailActivity : TabSSHActivity() {

    companion object {
        const val EXTRA_HOST_ID = "container_host_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_CONTAINER_NAME = "container_name"
        /** Which tab to open on ([TAB_INSPECT] et al.) — defaults to inspect. */
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val TAB_INSPECT = 0
        const val TAB_CONFIG = 1
        const val TAB_LOGS = 2
        const val TAB_STATS = 3
        private const val MAX_LOG_LINES = 2000
        // A single daemon log line is untrusted remote data — cap it so one
        // pathological line cannot blow up the TextView layout pass.
        private const val MAX_LOG_LINE_LENGTH = 1024
        // CSI sequences, OSC sequences (BEL or ST terminated), and stray ESCs.
        private val ANSI_REGEX = Regex(
            "\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)?|\u001B"
        )
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var sectionInspect: ScrollView
    private lateinit var sectionConfig: ScrollView
    private lateinit var sectionLogs: ScrollView
    private lateinit var sectionStats: ScrollView
    private lateinit var textInspect: TextView
    private lateinit var textConfig: TextView
    private lateinit var textLogs: TextView
    private lateinit var textStatsCpu: TextView
    private lateinit var textStatsMem: TextView
    private lateinit var textStatsNet: TextView
    private lateinit var textStatsBlock: TextView
    private lateinit var textStatsPids: TextView
    private lateinit var sparklineCpu: SparklineView
    private lateinit var sparklineMem: SparklineView

    private var hostId: Long = 0
    private lateinit var containerId: String
    private lateinit var containerName: String
    private var session: ContainerSessionManager.ContainerSession? = null
    private var logsJob: Job? = null
    private var statsJob: Job? = null
    private val logLines = ArrayDeque<String>()

    // One lifecycle/exec action at a time — the menu stays tappable while a
    // suspend call is in flight, and a double tap would fire two transport
    // operations (two removes, two exec terminals) against the same container.
    private var actionInFlight = false

    /** False once the activity is tearing down — dialogs must not be shown then. */
    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Env values and log output may contain secrets — block screenshots.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_container_detail)

        app = application as TabSSHApplication
        hostId = intent.getLongExtra(EXTRA_HOST_ID, 0)
        containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: ""
        // The name comes from the daemon and is rendered in the toolbar and in
        // the remove-confirmation dialog — sanitize once at intake so bidi
        // overrides cannot make the confirmation read as a different container.
        containerName = ContainerText.display(
            intent.getStringExtra(EXTRA_CONTAINER_NAME)
        ).ifEmpty { containerId.take(12) }
        if (containerId.isEmpty()) {
            finish()
            return
        }

        bindViews()
        setupToolbar()
        setupTabs()
        acquireSession()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        tabLayout = findViewById(R.id.tab_layout)
        progressBar = findViewById(R.id.progress_bar)
        sectionInspect = findViewById(R.id.section_inspect)
        sectionConfig = findViewById(R.id.section_config)
        sectionLogs = findViewById(R.id.section_logs)
        sectionStats = findViewById(R.id.section_stats)
        textInspect = findViewById(R.id.text_inspect)
        textConfig = findViewById(R.id.text_config)
        textLogs = findViewById(R.id.text_logs)
        textStatsCpu = findViewById(R.id.text_stats_cpu)
        textStatsMem = findViewById(R.id.text_stats_mem)
        textStatsNet = findViewById(R.id.text_stats_net)
        textStatsBlock = findViewById(R.id.text_stats_block)
        textStatsPids = findViewById(R.id.text_stats_pids)
        sparklineCpu = findViewById(R.id.sparkline_cpu)
        sparklineMem = findViewById(R.id.sparkline_mem)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.title = containerName
    }

    private fun setupTabs() {
        val titles = intArrayOf(
            R.string.container_detail_tab_inspect,
            R.string.container_detail_tab_config,
            R.string.container_detail_tab_logs,
            R.string.container_detail_tab_stats
        )
        titles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_INSPECT)
            .coerceIn(TAB_INSPECT, TAB_STATS)
        if (initialTab != TAB_INSPECT) {
            tabLayout.getTabAt(initialTab)?.select()
        }
    }

    private fun showSection(position: Int) {
        sectionInspect.visibility = if (position == 0) View.VISIBLE else View.GONE
        sectionConfig.visibility = if (position == 1) View.VISIBLE else View.GONE
        sectionLogs.visibility = if (position == 2) View.VISIBLE else View.GONE
        sectionStats.visibility = if (position == 3) View.VISIBLE else View.GONE
        // Streams only run while their tab is visible.
        if (position == 2) startLogs() else stopLogs()
        if (position == 3) startStats() else stopStats()
    }

    private fun acquireSession() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = ContainerSessionManager.acquire(app, hostId)
            // Acquisition suspends for the whole SSH/API handshake — the user
            // can leave the screen in that window, and present() would then
            // attach a dialog to a dead window token.
            if (!isAlive) return@launch
            when (result) {
                is ContainerResult.Success -> {
                    session = result.value
                    progressBar.visibility = View.GONE
                    loadInspect()
                    // A non-inspect EXTRA_INITIAL_TAB selected its tab before
                    // the session existed, so its stream never started — kick
                    // it off now that session is available.
                    showSection(tabLayout.selectedTabPosition)
                }
                else -> {
                    progressBar.visibility = View.GONE
                    ContainerErrorPresenter.present(this@ContainerDetailActivity, result)
                }
            }
        }
    }

    private fun loadInspect() {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = current.transport.inspectContainer(containerId)
            if (!isAlive) return@launch
            progressBar.visibility = View.GONE
            when (result) {
                is ContainerResult.Success -> {
                    // Inspect output is daemon-controlled (labels, env values,
                    // image names) — strip control/bidi characters and cap it.
                    textInspect.text = ContainerText.block(prettyJson(result.value))
                    textConfig.text = ContainerText.block(buildConfigSummary(result.value))
                }
                else -> ContainerErrorPresenter.present(this@ContainerDetailActivity, result)
            }
        }
    }

    /** 2-space indented JSON, or the raw payload when it does not parse. */
    private fun prettyJson(json: String): String {
        val trimmed = json.trim()
        return try {
            if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed).toString(2)
            } else {
                JSONObject(trimmed).toString(2)
            }
        } catch (_: Exception) {
            json
        }
    }

    /** Env / mounts / ports summary extracted from the inspect payload. */
    private fun buildConfigSummary(json: String): String {
        val none = getString(R.string.container_detail_none)
        return try {
            val root = json.trim()
            // docker inspect wraps the object in a one-element array.
            val obj = if (root.startsWith("[")) {
                org.json.JSONArray(root).optJSONObject(0) ?: JSONObject()
            } else {
                JSONObject(root)
            }
            val builder = StringBuilder()

            builder.append(getString(R.string.container_detail_env)).append('\n')
            val env = obj.optJSONObject("Config")?.optJSONArray("Env")
            if (env == null || env.length() == 0) {
                builder.append("  ").append(none).append('\n')
            } else {
                for (i in 0 until env.length()) {
                    builder.append("  ").append(env.optString(i)).append('\n')
                }
            }

            builder.append('\n').append(getString(R.string.container_detail_mounts)).append('\n')
            val mounts = obj.optJSONArray("Mounts")
            if (mounts == null || mounts.length() == 0) {
                builder.append("  ").append(none).append('\n')
            } else {
                for (i in 0 until mounts.length()) {
                    val mount = mounts.optJSONObject(i) ?: continue
                    builder.append("  ")
                        .append(mount.optString("Source", mount.optString("Name")))
                        .append(" -> ").append(mount.optString("Destination")).append('\n')
                }
            }

            builder.append('\n').append(getString(R.string.container_detail_ports)).append('\n')
            val ports = obj.optJSONObject("NetworkSettings")?.optJSONObject("Ports")
            if (ports == null || ports.length() == 0) {
                builder.append("  ").append(none).append('\n')
            } else {
                ports.keys().forEach { key ->
                    val bindings = ports.optJSONArray(key)
                    if (bindings == null || bindings.length() == 0) {
                        builder.append("  ").append(key).append('\n')
                    } else {
                        for (i in 0 until bindings.length()) {
                            val binding = bindings.optJSONObject(i) ?: continue
                            builder.append("  ")
                                .append(binding.optString("HostIp", "0.0.0.0")).append(':')
                                .append(binding.optString("HostPort"))
                                .append(" -> ").append(key).append('\n')
                        }
                    }
                }
            }
            builder.toString()
        } catch (_: Exception) {
            none
        }
    }

    private fun startLogs() {
        if (logsJob?.isActive == true) return
        val current = session ?: return
        logLines.clear()
        textLogs.text = ""
        logsJob = lifecycleScope.launch {
            // Unlike the suspend transport calls, the streaming Flows surface a
            // dead session by throwing into the collector — an uncaught throw
            // here would take the whole app down instead of the log tab.
            try {
                current.transport.streamLogs(containerId, tail = 200).collect { line ->
                    // Daemon-controlled: strip ANSI first, then the remaining
                    // C0/C1 controls and bidi overrides, and cap the line.
                    logLines.addLast(
                        ContainerText.display(ANSI_REGEX.replace(line, ""), MAX_LOG_LINE_LENGTH)
                    )
                    while (logLines.size > MAX_LOG_LINES) {
                        logLines.removeFirst()
                    }
                    textLogs.text = logLines.joinToString("\n")
                    sectionLogs.post { sectionLogs.fullScroll(View.FOCUS_DOWN) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive) return@launch
                textLogs.text = getString(
                    R.string.container_error_detail_fmt, ContainerText.display(e.message)
                )
            }
        }
    }

    private fun stopLogs() {
        logsJob?.cancel()
        logsJob = null
    }

    private fun startStats() {
        if (statsJob?.isActive == true) return
        val current = session ?: return
        sparklineCpu.clear()
        sparklineMem.clear()
        statsJob = lifecycleScope.launch {
            // Same contract as the logs stream — a transport failure arrives as
            // a throw into the collector and must not crash the activity.
            try {
                current.transport.streamStats(containerId).collect { stats ->
                    sparklineCpu.addSample(stats.cpuPercent.toFloat())
                    sparklineMem.addSample(stats.memPercent.toFloat())
                    val ctx = this@ContainerDetailActivity
                    textStatsCpu.text = getString(
                        R.string.container_stats_cpu_fmt, String.format("%.1f", stats.cpuPercent)
                    )
                    textStatsMem.text = getString(
                        R.string.container_stats_mem_fmt,
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.memUsageBytes),
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.memLimitBytes),
                        String.format("%.1f", stats.memPercent)
                    )
                    textStatsNet.text = getString(
                        R.string.container_stats_net_fmt,
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.netInputBytes),
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.netOutputBytes)
                    )
                    textStatsBlock.text = getString(
                        R.string.container_stats_block_fmt,
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.blockReadBytes),
                        android.text.format.Formatter.formatShortFileSize(ctx, stats.blockWriteBytes)
                    )
                    textStatsPids.text = getString(R.string.container_stats_pids_fmt, stats.pids)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive) return@launch
                textStatsCpu.text = getString(
                    R.string.container_error_detail_fmt, ContainerText.display(e.message)
                )
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_container_detail, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                loadInspect()
                true
            }
            R.id.action_terminal -> {
                enterTerminal()
                true
            }
            R.id.action_start -> runAction(ContainerAction.START)
            R.id.action_stop -> runAction(ContainerAction.STOP)
            R.id.action_restart -> runAction(ContainerAction.RESTART)
            R.id.action_pause -> runAction(ContainerAction.PAUSE)
            R.id.action_unpause -> runAction(ContainerAction.UNPAUSE)
            R.id.action_kill -> {
                // KILL sends SIGKILL — no graceful shutdown, so gate it behind
                // the same confirmation the container list applies.
                confirmKill()
                true
            }
            R.id.action_rename -> {
                showRenameDialog()
                true
            }
            R.id.action_remove -> {
                confirmRemove()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** SIGKILL confirmation — the container name is the subject of the prompt. */
    private fun confirmKill() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.container_action_kill)
            .setMessage(getString(R.string.container_kill_container_message, containerName))
            .setPositiveButton(R.string.container_action_kill) { _, _ ->
                runAction(ContainerAction.KILL)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runAction(action: ContainerAction): Boolean {
        val current = session ?: return true
        if (actionInFlight) return true
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = current.transport.containerAction(containerId, action)
                if (!isAlive) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is ContainerResult.Success -> {
                        Toast.makeText(
                            this@ContainerDetailActivity,
                            R.string.container_action_success, Toast.LENGTH_SHORT
                        ).show()
                        loadInspect()
                    }
                    else -> ContainerErrorPresenter.present(this@ContainerDetailActivity, result)
                }
            } finally {
                actionInFlight = false
            }
        }
        return true
    }

    private fun showRenameDialog() {
        ContainerRenameDialog.show(this, containerName) { newName ->
            val current = session ?: return@show
            if (actionInFlight) return@show
            actionInFlight = true
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val result = current.transport.renameContainer(containerId, newName)
                    if (!isAlive) return@launch
                    progressBar.visibility = View.GONE
                    when (result) {
                        is ContainerResult.Success -> {
                            containerName = ContainerText.display(newName)
                            supportActionBar?.title = containerName
                            loadInspect()
                        }
                        else -> ContainerErrorPresenter.present(this@ContainerDetailActivity, result)
                    }
                } finally {
                    actionInFlight = false
                }
            }
        }
    }

    private fun confirmRemove() {
        MaterialAlertDialogBuilder(this)
            .setTitle(containerName)
            .setMessage(getString(R.string.container_remove_container_message, containerName))
            .setPositiveButton(R.string.delete) { _, _ -> removeContainer() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeContainer() {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val result = current.transport.removeContainer(containerId, force = true)
                if (!isAlive) return@launch
                progressBar.visibility = View.GONE
                when (result) {
                    is ContainerResult.Success -> finish()
                    else -> ContainerErrorPresenter.present(this@ContainerDetailActivity, result)
                }
            } finally {
                actionInFlight = false
            }
        }
    }

    /**
     * Open a terminal tab running docker exec -it into this container via the
     * shared ContainerExecLauncher (bash/sh probe + ephemeral profile).
     */
    private fun enterTerminal() {
        val current = session ?: return
        if (actionInFlight) return
        actionInFlight = true
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, R.string.container_terminal_probing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                // buildExecIntent runs a shell probe over the session runner,
                // which throws when the SSH session died since acquisition.
                val intent = ContainerExecLauncher.buildExecIntent(
                    this@ContainerDetailActivity, current, hostId, containerId, containerName
                )
                if (!isAlive) return@launch
                progressBar.visibility = View.GONE
                startActivity(intent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isAlive) return@launch
                progressBar.visibility = View.GONE
                Toast.makeText(
                    this@ContainerDetailActivity,
                    getString(R.string.container_error_detail_fmt, ContainerText.display(e.message)),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                actionInFlight = false
            }
        }
    }

    override fun onDestroy() {
        stopLogs()
        stopStats()
        super.onDestroy()
    }
}

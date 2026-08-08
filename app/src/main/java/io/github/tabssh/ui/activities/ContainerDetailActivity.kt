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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.docker.DockerSessionManager
import io.github.tabssh.docker.transport.ContainerAction
import io.github.tabssh.docker.transport.DockerResult
import io.github.tabssh.docker.transport.SshExecRunner
import io.github.tabssh.ui.dialogs.ContainerRenameDialog
import io.github.tabssh.ui.dialogs.DockerErrorPresenter
import io.github.tabssh.ui.views.SparklineView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Container detail screen (PLAN.AI.md step 24): inspect, env/mounts/ports,
 * live logs (ANSI-stripped follow), and live stats with sparklines, plus the
 * full lifecycle action menu and the docker-exec terminal entry point.
 * FLAG_SECURE keeps env values and logs out of screenshots and recents.
 */
class ContainerDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST_ID = "docker_host_id"
        const val EXTRA_CONTAINER_ID = "container_id"
        const val EXTRA_CONTAINER_NAME = "container_name"
        private const val MAX_LOG_LINES = 2000
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
    private var session: DockerSessionManager.DockerSession? = null
    private var logsJob: Job? = null
    private var statsJob: Job? = null
    private val logLines = ArrayDeque<String>()

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
        containerName = intent.getStringExtra(EXTRA_CONTAINER_NAME) ?: containerId.take(12)
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = containerName
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupTabs() {
        val titles = intArrayOf(
            R.string.docker_detail_tab_inspect,
            R.string.docker_detail_tab_config,
            R.string.docker_detail_tab_logs,
            R.string.docker_detail_tab_stats
        )
        titles.forEach { tabLayout.addTab(tabLayout.newTab().setText(it)) }
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showSection(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
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
            when (val result = DockerSessionManager.acquire(app, hostId)) {
                is DockerResult.Success -> {
                    session = result.value
                    progressBar.visibility = View.GONE
                    loadInspect()
                }
                else -> {
                    progressBar.visibility = View.GONE
                    DockerErrorPresenter.present(this@ContainerDetailActivity, result)
                }
            }
        }
    }

    private fun loadInspect() {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = current.transport.inspectContainer(containerId)
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    textInspect.text = prettyJson(result.value)
                    textConfig.text = buildConfigSummary(result.value)
                }
                else -> DockerErrorPresenter.present(this@ContainerDetailActivity, result)
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
        } catch (e: Exception) {
            json
        }
    }

    /** Env / mounts / ports summary extracted from the inspect payload. */
    private fun buildConfigSummary(json: String): String {
        val none = getString(R.string.docker_detail_none)
        return try {
            val root = json.trim()
            // docker inspect wraps the object in a one-element array.
            val obj = if (root.startsWith("[")) {
                org.json.JSONArray(root).optJSONObject(0) ?: JSONObject()
            } else {
                JSONObject(root)
            }
            val builder = StringBuilder()

            builder.append(getString(R.string.docker_detail_env)).append('\n')
            val env = obj.optJSONObject("Config")?.optJSONArray("Env")
            if (env == null || env.length() == 0) {
                builder.append("  ").append(none).append('\n')
            } else {
                for (i in 0 until env.length()) {
                    builder.append("  ").append(env.optString(i)).append('\n')
                }
            }

            builder.append('\n').append(getString(R.string.docker_detail_mounts)).append('\n')
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

            builder.append('\n').append(getString(R.string.docker_detail_ports)).append('\n')
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
        } catch (e: Exception) {
            none
        }
    }

    private fun startLogs() {
        if (logsJob?.isActive == true) return
        val current = session ?: return
        logLines.clear()
        textLogs.text = ""
        logsJob = lifecycleScope.launch {
            current.transport.streamLogs(containerId, tail = 200).collect { line ->
                logLines.addLast(ANSI_REGEX.replace(line, ""))
                while (logLines.size > MAX_LOG_LINES) {
                    logLines.removeFirst()
                }
                textLogs.text = logLines.joinToString("\n")
                sectionLogs.post { sectionLogs.fullScroll(View.FOCUS_DOWN) }
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
            current.transport.streamStats(containerId).collect { stats ->
                sparklineCpu.addSample(stats.cpuPercent.toFloat())
                sparklineMem.addSample(stats.memPercent.toFloat())
                val ctx = this@ContainerDetailActivity
                textStatsCpu.text = getString(
                    R.string.docker_stats_cpu_fmt, String.format("%.1f", stats.cpuPercent)
                )
                textStatsMem.text = getString(
                    R.string.docker_stats_mem_fmt,
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.memUsageBytes),
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.memLimitBytes),
                    String.format("%.1f", stats.memPercent)
                )
                textStatsNet.text = getString(
                    R.string.docker_stats_net_fmt,
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.netInputBytes),
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.netOutputBytes)
                )
                textStatsBlock.text = getString(
                    R.string.docker_stats_block_fmt,
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.blockReadBytes),
                    android.text.format.Formatter.formatShortFileSize(ctx, stats.blockWriteBytes)
                )
                textStatsPids.text = getString(R.string.docker_stats_pids_fmt, stats.pids)
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
            android.R.id.home -> {
                finish()
                true
            }
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
            R.id.action_kill -> runAction(ContainerAction.KILL)
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

    private fun runAction(action: ContainerAction): Boolean {
        val current = session ?: return true
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = current.transport.containerAction(containerId, action)
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> {
                    Toast.makeText(
                        this@ContainerDetailActivity,
                        R.string.docker_action_success, Toast.LENGTH_SHORT
                    ).show()
                    loadInspect()
                }
                else -> DockerErrorPresenter.present(this@ContainerDetailActivity, result)
            }
        }
        return true
    }

    private fun showRenameDialog() {
        ContainerRenameDialog.show(this, containerName) { newName ->
            val current = session ?: return@show
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = current.transport.renameContainer(containerId, newName)
                progressBar.visibility = View.GONE
                when (result) {
                    is DockerResult.Success -> {
                        containerName = newName
                        supportActionBar?.title = newName
                        loadInspect()
                    }
                    else -> DockerErrorPresenter.present(this@ContainerDetailActivity, result)
                }
            }
        }
    }

    private fun confirmRemove() {
        MaterialAlertDialogBuilder(this)
            .setTitle(containerName)
            .setMessage(getString(R.string.docker_remove_container_message, containerName))
            .setPositiveButton(R.string.delete) { _, _ -> removeContainer() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun removeContainer() {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = current.transport.removeContainer(containerId, force = true)
            progressBar.visibility = View.GONE
            when (result) {
                is DockerResult.Success -> finish()
                else -> DockerErrorPresenter.present(this@ContainerDetailActivity, result)
            }
        }
    }

    /**
     * Probe for bash (falling back to sh), then open a terminal tab whose
     * remote command is docker exec -it into this container. The ephemeral
     * profile copies the linked connection's endpoint and auth and is never
     * saved to the database.
     */
    private fun enterTerminal() {
        val current = session ?: return
        progressBar.visibility = View.VISIBLE
        Toast.makeText(this, R.string.docker_terminal_probing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val docker = current.host.dockerCliPath ?: "docker"
            val quotedId = SshExecRunner.shQuote(containerId)
            val probe = current.runner.run(
                "$docker exec $quotedId sh -c " +
                    "'command -v bash >/dev/null 2>&1 && echo bash || echo sh'"
            )
            progressBar.visibility = View.GONE
            val shell = probe.stdout.trim().lines().lastOrNull()?.trim()
                .takeIf { it == "bash" } ?: "sh"
            val execProfile = current.profile.copy(
                id = "docker-exec:$hostId:$containerId",
                name = "docker: $containerName",
                remoteCommand = "$docker exec -it $quotedId $shell",
                multiplexerMode = "OFF"
            )
            startActivity(
                TabTerminalActivity.createIntent(
                    this@ContainerDetailActivity, execProfile,
                    autoConnect = true, forceNew = true
                )
            )
        }
    }

    override fun onDestroy() {
        stopLogs()
        stopStats()
        super.onDestroy()
    }
}

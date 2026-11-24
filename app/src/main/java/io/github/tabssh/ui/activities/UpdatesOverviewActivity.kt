package io.github.tabssh.ui.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.containers.registry.UpdateAllRunner
import io.github.tabssh.containers.registry.UpdateApplier
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import io.github.tabssh.R
import io.github.tabssh.background.ContainerUpdateCheckWorker
import io.github.tabssh.containers.ContainerSessionManager
import io.github.tabssh.containers.registry.UpdateCheckStatus
import io.github.tabssh.containers.transport.ContainerResult
import io.github.tabssh.storage.database.entities.ContainerAutoUpdatePolicy
import io.github.tabssh.ui.dialogs.UpdateApplyDialog
import io.github.tabssh.ui.utils.ContainerText
import io.github.tabssh.utils.tabSSHApp
import kotlinx.coroutines.launch

/**
 * Container Updates page: every container with a newer image waiting, across all hosts, grouped
 * by host. A row opens that host's Updates tab, where the update is applied. The list follows the
 * database, so a check (periodic, or Check all now) refreshes it without reloading.
 */
class UpdatesOverviewActivity : TabSSHActivity() {

    private lateinit var textSummary: TextView
    private lateinit var listHosts: LinearLayout
    private lateinit var textEmpty: TextView
    private lateinit var textCheckStatus: TextView
    private var lastPending: List<ContainerAutoUpdatePolicy> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates_overview)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.nav_item_container_updates)

        textSummary = findViewById(R.id.text_summary)
        listHosts = findViewById(R.id.list_hosts)
        textEmpty = findViewById(R.id.text_empty)
        textCheckStatus = findViewById(R.id.text_check_status)

        findViewById<MaterialButton>(R.id.button_check_all).setOnClickListener {
            ContainerUpdateCheckWorker.checkNow(applicationContext)
            Toast.makeText(this, R.string.container_updates_check_all_started, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.button_update_all).setOnClickListener { confirmUpdateAll() }

        val dao = tabSSHApp.database.containerAutoUpdatePolicyDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { dao.observePending().collect { lastPending = it; render(it) } }
                launch {
                    kotlinx.coroutines.flow.combine(
                        UpdateCheckStatus.results, UpdateCheckStatus.running
                    ) { _, _ -> Unit }.collect { render(lastPending) }
                }
            }
        }
    }

    private suspend fun render(pending: List<ContainerAutoUpdatePolicy>) {
        val hosts = tabSSHApp.database.containerHostDao().getAllList()
        val names = hosts.associate { it.id to it.name }
        val byHost = pending.filter { it.containerHostId in names }.groupBy { it.containerHostId }

        showCheckStatus(names)
        listHosts.removeAllViews()
        val total = byHost.values.sumOf { it.size }
        textSummary.visibility = if (total == 0) View.GONE else View.VISIBLE
        textSummary.text = resources.getQuantityString(
            R.plurals.container_updates_overview_summary, total, total, byHost.size
        )
        textEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
        findViewById<View>(R.id.button_update_all).visibility = if (total == 0) View.GONE else View.VISIBLE
        textEmpty.setText(
            if (hosts.isEmpty()) R.string.container_updates_overview_no_hosts
            else R.string.container_updates_overview_none
        )

        val inflater = LayoutInflater.from(this)
        for ((hostId, items) in byHost.entries.sortedBy { names[it.key]?.lowercase() }) {
            val hostName = ContainerText.display(names[hostId])
            val header = inflater.inflate(R.layout.item_update_overview_host, listHosts, false)
            header.findViewById<TextView>(R.id.text_host).text = hostName
            header.findViewById<TextView>(R.id.text_count).text =
                resources.getQuantityString(R.plurals.container_updates_overview_host_count, items.size, items.size)
            listHosts.addView(header)
            for (policy in items.sortedBy { it.containerNameOrStackName.lowercase() }) {
                val row = inflater.inflate(R.layout.item_update_available, listHosts, false)
                row.findViewById<TextView>(R.id.text_title).text =
                    ContainerText.display(policy.containerNameOrStackName)
                row.findViewById<TextView>(R.id.text_subtitle).text =
                    getString(R.string.container_updates_overview_row_subtitle)
                row.setOnClickListener { openHost(hostId) }
                val name = ContainerText.display(policy.containerNameOrStackName)
                row.findViewById<MaterialButton>(R.id.button_update).apply {
                    contentDescription = getString(R.string.container_updates_update_desc, name)
                    setOnClickListener { applyUpdate(this, hostId, policy, name) }
                }
                listHosts.addView(row)
            }
        }
        refreshActionState()
    }

    /** Says what the last check actually did per host, so a stale list is never mistaken for a fresh one. */
    private fun showCheckStatus(names: Map<Long, String>) {
        val lines = mutableListOf<String>()
        val results = UpdateCheckStatus.results.value.filterKeys { it in names }
        if (UpdateCheckStatus.running.value) lines += getString(R.string.container_updates_status_running)
        var checked = 0
        var noNetworkRemoved = 0
        for ((id, r) in results) {
            val host = ContainerText.display(names[id])
            when (r.outcome) {
                UpdateCheckStatus.Outcome.CHECKED -> {
                    checked++
                    if (r.failedChecks > 0) {
                        lines += getString(
                            R.string.container_updates_status_failed_checks,
                            host, r.failedChecks, ContainerText.display(r.firstFailure, 120)
                        )
                    }
                }
                UpdateCheckStatus.Outcome.NO_CONNECTION -> lines += if (r.removedUpdates > 0) {
                    getString(R.string.container_updates_status_no_connection_removed, host, r.removedUpdates)
                } else {
                    getString(R.string.container_updates_status_no_connection, host)
                }
                UpdateCheckStatus.Outcome.LIST_FAILED -> lines += if (r.removedUpdates > 0) {
                    getString(R.string.container_updates_status_list_failed_removed, host, r.removedUpdates)
                } else {
                    getString(R.string.container_updates_status_list_failed, host)
                }
                UpdateCheckStatus.Outcome.NO_NETWORK -> noNetworkRemoved += r.removedUpdates

            }
        }
        if (results.values.any { it.outcome == UpdateCheckStatus.Outcome.NO_NETWORK }) {
            lines += if (noNetworkRemoved > 0) {
                getString(R.string.container_updates_status_no_network_removed, noNetworkRemoved)
            } else {
                getString(R.string.container_updates_status_no_network)
            }
        }
        if (checked > 0 && !UpdateCheckStatus.running.value) {
            lines.add(0, getString(R.string.container_updates_status_ok, checked))
        }
        textCheckStatus.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        textCheckStatus.text = lines.joinToString("\n")
    }

    private fun confirmUpdateAll() {
        val targets = lastPending
        if (targets.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.container_updates_update_all_title)
            .setMessage(R.string.container_updates_update_all_confirm)
            .setPositiveButton(R.string.container_updates_update_all) { _, _ -> runUpdateAll(targets) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** True while an Update all run is in progress; the list's other actions wait for it. */
    private var updating = false

    /** Keeps Check all, Update all and the row buttons from starting work on top of work already running. */
    private fun refreshActionState() {
        val busy = updating || UpdateCheckStatus.running.value
        findViewById<MaterialButton>(R.id.button_check_all).isEnabled = !busy
        findViewById<MaterialButton>(R.id.button_update_all).isEnabled = !busy
        for (i in 0 until listHosts.childCount) {
            listHosts.getChildAt(i).findViewById<MaterialButton>(R.id.button_update)?.isEnabled = !busy
        }
    }

    /**
     * Applies every pending update in turn and shows what is happening the whole time: the host being
     * connected, each unit's steps, the command and pull output as it arrives, and a result per unit.
     * The list and its count follow the database, so each finished update leaves the list live.
     */
    private fun runUpdateAll(targets: List<ContainerAutoUpdatePolicy>) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_update_apply, null)
        val textStep = view.findViewById<TextView>(R.id.text_step)
        val textStatus = view.findViewById<TextView>(R.id.text_status)
        val progressBar = view.findViewById<android.widget.ProgressBar>(R.id.progress_bar)
        val textOutput = view.findViewById<TextView>(R.id.text_output)
        val scrollOutput = view.findViewById<View>(R.id.scroll_output)
        scrollOutput.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        textStep.setText(R.string.container_updates_update_all_connecting)

        val live = ArrayList<String>()
        fun append(text: String) {
            live += text
            textOutput.text = ContainerText.block(live.takeLast(MAX_LIVE_LINES).joinToString("\n"))
            scrollOutput.post { (scrollOutput as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN) }
        }

        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.container_updates_update_all_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                job?.cancel()
                updating = false
                refreshActionState()
            }
            .create()
        updating = true
        refreshActionState()
        dialog.show()

        job = lifecycleScope.launch {
            val db = tabSSHApp.database
            val hostNames = db.containerHostDao().getAllList().associate { it.id to it.name }
            val dao = db.containerAutoUpdatePolicyDao()
            val runner = UpdateAllRunner(
                dao,
                hostName = { ContainerText.display(hostNames[it]) },
                transportFor = { hostId ->
                    (ContainerSessionManager.acquire(tabSSHApp, hostId) as? ContainerResult.Success)?.value?.transport
                }
            )
            var summary: UpdateAllRunner.Event.Finished? = null
            try {
                runner.run(targets).collect { event ->
                    when (event) {
                        is UpdateAllRunner.Event.HostStarted -> {
                            textStep.text = getString(R.string.container_updates_update_all_host, event.host)
                            append("== ${event.host}: ${event.containers} container(s)")
                        }
                        is UpdateAllRunner.Event.UnitStarted -> {
                            progressBar.isIndeterminate = false
                            progressBar.max = event.total
                            progressBar.progress = event.index - 1
                            textStep.text = getString(
                                R.string.container_updates_update_all_progress,
                                ContainerText.display(event.label), event.index, event.total
                            )
                            append("[${event.index}/${event.total}] ${event.label}")
                        }
                        is UpdateAllRunner.Event.Line -> append("    ${event.text}")
                        is UpdateAllRunner.Event.UnitDone -> {
                            val line = when {
                                event.failed > 0 -> getString(
                                    R.string.container_updates_update_all_unit_failed,
                                    event.label, ContainerText.display(event.detail, 200)
                                )
                                else -> getString(R.string.container_updates_update_all_item_ok, event.label)
                            }
                            append(line)
                            progressBar.progress = progressBar.progress + 1
                        }
                        is UpdateAllRunner.Event.Finished -> summary = event
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("UpdatesOverview", "update all threw", e)
                append(ContainerText.display(e.message, 200))
            }
            progressBar.visibility = View.GONE
            textStep.setText(R.string.container_update_apply_done)
            summary?.let {
                textStatus.visibility = View.VISIBLE
                textStatus.text = getString(
                    R.string.container_updates_update_all_summary, it.updated, it.failed
                )
            }
            updating = false
            refreshActionState()
            dialog.setCancelable(true)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setText(R.string.close)
        }
    }

    /** Connect to the host (or reuse its session), then run the confirmed update with progress. */
    private fun applyUpdate(
        button: MaterialButton,
        hostId: Long,
        policy: ContainerAutoUpdatePolicy,
        name: String
    ) {
        val label = button.text
        button.isEnabled = false
        button.setText(R.string.container_updates_overview_connecting)
        lifecycleScope.launch {
            val result = try {
                ContainerSessionManager.acquire(tabSSHApp, hostId)
            } finally {
                button.isEnabled = true
                button.text = label
            }
            when (result) {
                is ContainerResult.Success -> UpdateApplyDialog.show(
                    this@UpdatesOverviewActivity,
                    this@UpdatesOverviewActivity,
                    tabSSHApp.database.containerAutoUpdatePolicyDao(),
                    policy.id,
                    result.value.transport,
                    name
                ) { }
                else -> Toast.makeText(
                    this@UpdatesOverviewActivity,
                    R.string.container_updates_overview_connect_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openHost(hostId: Long) {
        startActivity(
            Intent(this, ContainerHostManagerActivity::class.java)
                .putExtra(ContainerHostManagerActivity.EXTRA_HOST_ID, hostId)
                .putExtra(ContainerHostManagerActivity.EXTRA_OPEN_UPDATES, true)
        )
    }

    private companion object {
        /** Lines of live output kept on screen; older lines scroll off. */
        const val MAX_LIVE_LINES = 500
    }
}

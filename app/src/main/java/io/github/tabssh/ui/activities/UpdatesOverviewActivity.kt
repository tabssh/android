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
    }

    /** Says what the last check actually did per host, so a stale list is never mistaken for a fresh one. */
    private fun showCheckStatus(names: Map<Long, String>) {
        val lines = mutableListOf<String>()
        val results = UpdateCheckStatus.results.value.filterKeys { it in names }
        if (UpdateCheckStatus.running.value) lines += getString(R.string.container_updates_status_running)
        var checked = 0
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
                UpdateCheckStatus.Outcome.NO_CONNECTION ->
                    lines += getString(R.string.container_updates_status_no_connection, host)
                UpdateCheckStatus.Outcome.LIST_FAILED ->
                    lines += getString(R.string.container_updates_status_list_failed, host)
                UpdateCheckStatus.Outcome.NO_NETWORK -> {
                    val text = getString(R.string.container_updates_status_no_network)
                    if (text !in lines) lines += text
                }
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

    /** Applies every pending update one after another, grouped by host so each host connects once. */
    private fun runUpdateAll(targets: List<ContainerAutoUpdatePolicy>) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_update_apply, null)
        val textStep = view.findViewById<TextView>(R.id.text_step)
        val textStatus = view.findViewById<TextView>(R.id.text_status)
        val progressBar = view.findViewById<android.widget.ProgressBar>(R.id.progress_bar)
        val textOutput = view.findViewById<TextView>(R.id.text_output)
        val scrollOutput = view.findViewById<View>(R.id.scroll_output)
        scrollOutput.visibility = View.VISIBLE

        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.container_updates_update_all_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { job?.cancel() }
            .create()
        dialog.show()

        job = lifecycleScope.launch {
            val db = tabSSHApp.database
            val hostNames = db.containerHostDao().getAllList().associate { it.id to it.name }
            val dao = db.containerAutoUpdatePolicyDao()
            val applier = UpdateApplier(dao)
            val log = ArrayList<String>()
            var done = 0
            var ok = 0
            var failed = 0
            try {
                for ((hostId, items) in targets.groupBy { it.containerHostId }) {
                    val session = ContainerSessionManager.acquire(tabSSHApp, hostId)
                    if (session !is ContainerResult.Success) {
                        failed += items.size
                        done += items.size
                        log += getString(
                            R.string.container_updates_update_all_connect_failed,
                            ContainerText.display(hostNames[hostId])
                        )
                        textOutput.text = log.joinToString("\n")
                        continue
                    }
                    for (policy in items) {
                        val name = ContainerText.display(policy.containerNameOrStackName)
                        done++
                        textStep.text = getString(
                            R.string.container_updates_update_all_progress, name, done, targets.size
                        )
                        var success = false
                        applier.apply(policy.id, session.value.transport).collect { event ->
                            when (event) {
                                is UpdateApplier.ApplyEvent.Completed -> {
                                    success = true
                                    log += getString(R.string.container_updates_update_all_item_ok, name)
                                }
                                is UpdateApplier.ApplyEvent.Failed -> log += getString(
                                    R.string.container_updates_update_all_item_failed,
                                    name, event.step.name,
                                    ContainerText.display(event.message, 200)
                                )
                                else -> Unit
                            }
                        }
                        if (success) ok++ else failed++
                        textOutput.text = ContainerText.block(log.joinToString("\n"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("UpdatesOverview", "update all threw", e)
                failed += targets.size - ok - failed
                log += ContainerText.display(e.message, 200)
                textOutput.text = ContainerText.block(log.joinToString("\n"))
            }
            textStep.setText(R.string.container_update_apply_done)
            textStatus.visibility = View.VISIBLE
            textStatus.text = getString(R.string.container_updates_update_all_summary, ok, failed)
            progressBar.visibility = View.GONE
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
}

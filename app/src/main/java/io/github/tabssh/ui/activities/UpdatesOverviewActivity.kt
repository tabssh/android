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
import io.github.tabssh.R
import io.github.tabssh.background.ContainerUpdateCheckWorker
import io.github.tabssh.containers.ContainerSessionManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates_overview)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setTitle(R.string.nav_item_container_updates)

        textSummary = findViewById(R.id.text_summary)
        listHosts = findViewById(R.id.list_hosts)
        textEmpty = findViewById(R.id.text_empty)

        findViewById<MaterialButton>(R.id.button_check_all).setOnClickListener {
            ContainerUpdateCheckWorker.checkNow(applicationContext)
            Toast.makeText(this, R.string.container_updates_check_all_started, Toast.LENGTH_SHORT).show()
        }

        val dao = tabSSHApp.database.containerAutoUpdatePolicyDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observePending().collect { render(it) }
            }
        }
    }

    private suspend fun render(pending: List<ContainerAutoUpdatePolicy>) {
        val hosts = tabSSHApp.database.containerHostDao().getAllList()
        val names = hosts.associate { it.id to it.name }
        val byHost = pending.filter { it.containerHostId in names }.groupBy { it.containerHostId }

        listHosts.removeAllViews()
        val total = byHost.values.sumOf { it.size }
        textSummary.visibility = if (total == 0) View.GONE else View.VISIBLE
        textSummary.text = resources.getQuantityString(
            R.plurals.container_updates_overview_summary, total, total, byHost.size
        )
        textEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
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
                    setOnClickListener { applyUpdate(hostId, policy, name) }
                }
                listHosts.addView(row)
            }
        }
    }

    /** Connect to the host (or reuse its session), then run the confirmed update with progress. */
    private fun applyUpdate(hostId: Long, policy: ContainerAutoUpdatePolicy, name: String) {
        Toast.makeText(this, R.string.container_updates_overview_connecting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val result = ContainerSessionManager.acquire(tabSSHApp, hostId)) {
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

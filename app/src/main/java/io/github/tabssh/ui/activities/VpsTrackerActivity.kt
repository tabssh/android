package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.databinding.ActivityVpsTrackerBinding
import io.github.tabssh.storage.database.entities.VpsHost
import io.github.tabssh.tracker.VpsMarkdownImportExport
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List screen for the VPS Hosting Tracker: tracked VPS/hosting instances
 * grouped by tenant, mirroring [VncHostsActivity]'s pattern and the row
 * conventions established by [DomainTrackerActivity].
 */
class VpsTrackerActivity : TabSSHActivity() {

    private companion object {
        private const val TAG = "VpsTrackerActivity"
    }

    private lateinit var binding: ActivityVpsTrackerBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: VpsHostAdapter

    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFrom(it) } }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> uri?.let { exportTo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        binding = ActivityVpsTrackerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.nav_item_vps_tracker)

        binding.sectionHeader.textHeaderEmoji.text = getString(R.string.vps_tracker_header_emoji)
        binding.sectionHeader.textHeaderTitle.text = getString(R.string.nav_item_vps_tracker)
        binding.sectionHeader.textHeaderSubtitle.text = getString(R.string.vps_tracker_header_subtitle)

        adapter = VpsHostAdapter(onLongPress = { host -> showHostMenu(host) })
        binding.recyclerVpsHosts.layoutManager = LinearLayoutManager(this)
        binding.recyclerVpsHosts.adapter = adapter
        adapter.setOnItemClickListener { host -> launchEditHost(host) }

        binding.fabAdd.setOnClickListener { launchAddHost() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.vpsHostDao().getAll().collect { hosts ->
                    adapter.submitList(hosts)
                    if (hosts.isEmpty()) {
                        binding.recyclerVpsHosts.visibility = View.GONE
                        binding.emptyState.visibility = View.VISIBLE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.recyclerVpsHosts.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_tracker, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import -> {
                importLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                true
            }
            R.id.action_export -> {
                exportLauncher.launch("VPS.md")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun launchAddHost() {
        startActivity(Intent(this, VpsHostEditActivity::class.java))
    }

    private fun launchEditHost(host: VpsHost) {
        startActivity(
            Intent(this, VpsHostEditActivity::class.java).apply {
                putExtra(VpsHostEditActivity.EXTRA_VPS_HOST_ID, host.id)
            }
        )
    }

    // ── Import / Export (SAF) ────────────────────────────────────────────────

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: return@launch
                val result = VpsMarkdownImportExport.parse(text)
                withContext(Dispatchers.IO) {
                    app.database.vpsHostDao().insertAll(result.hosts)
                }
                for (warning in result.warnings) Logger.w(TAG, "Markdown import: $warning")
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_tracker_import_success_fmt, result.hosts.size), Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "VPS markdown import failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_tracker_import_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val hosts = app.database.vpsHostDao().getAllList()
                    val markdown = VpsMarkdownImportExport.export(hosts)
                    contentResolver.openOutputStream(uri)?.use { out -> out.write(markdown.toByteArray()) }
                }
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_tracker_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "VPS markdown export failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_tracker_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Long-press menu ───────────────────────────────────────────────────────

    private fun showHostMenu(host: VpsHost) {
        MaterialAlertDialogBuilder(this)
            .setTitle(host.hostname)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> launchEditHost(host)
                    1 -> confirmDelete(host)
                }
            }
            .show()
    }

    private fun confirmDelete(host: VpsHost) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vps_host_delete_title, host.hostname))
            .setMessage(getString(R.string.vps_host_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.vpsHostDao().deleteById(host.id)
                            TombstoneRecorder.record(app, TombstoneRecorder.VPS_HOST, host.id)
                        }
                        Logger.d(TAG, "Deleted VPS host: ${host.hostname}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to delete VPS host", e)
                        if (!isAlive) return@launch
                        Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_host_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── RecyclerView adapter ─────────────────────────────────────────────────

    private inner class VpsHostAdapter(
        private val onLongPress: (VpsHost) -> Unit
    ) : ListAdapter<VpsHost, VpsHostAdapter.ViewHolder>(VpsHostDiff) {

        private var onClick: ((VpsHost) -> Unit)? = null
        fun setOnItemClickListener(listener: (VpsHost) -> Unit) { onClick = listener }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hostname: TextView = view.findViewById(R.id.text_vps_hostname)
            val ipv4: TextView = view.findViewById(R.id.text_vps_ipv4)
            val ipv6: TextView = view.findViewById(R.id.text_vps_ipv6)
            val specs: TextView = view.findViewById(R.id.text_vps_specs)
            val domain: TextView = view.findViewById(R.id.text_vps_domain)
            val renewal: TextView = view.findViewById(R.id.text_vps_renewal)
            val cycle: TextView = view.findViewById(R.id.text_vps_cycle)
            val price: TextView = view.findViewById(R.id.text_vps_price)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vps_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val host = getItem(position)
            holder.hostname.text = "${host.tenant} · ${host.hostname}"
            holder.ipv4.text = getString(R.string.vps_tracker_col_ipv4) + ": " + (host.ipv4 ?: "—")
            holder.ipv6.text = getString(R.string.vps_tracker_col_ipv6) + ": " + (host.ipv6 ?: "—")
            holder.specs.text = getString(R.string.vps_tracker_col_specs) + ": " + (host.specs ?: "—")
            holder.domain.text = getString(R.string.vps_tracker_col_domain) + ": " + (host.linkedDomain ?: "—")
            holder.renewal.text = getString(R.string.vps_tracker_col_renewal) + ": " +
                (host.renewalDate?.let { VpsMarkdownImportExport.formatRenewalDate(it) } ?: host.renewalRaw ?: "—")
            holder.cycle.text = getString(R.string.vps_tracker_col_cycle) + ": " + (host.billingCycle ?: "—")
            holder.price.text = getString(R.string.vps_tracker_col_price) + ": " + (host.price ?: "—")
            holder.itemView.setOnClickListener { onClick?.invoke(host) }
            holder.itemView.setOnLongClickListener {
                onLongPress(host)
                true
            }
        }
    }

    private object VpsHostDiff : DiffUtil.ItemCallback<VpsHost>() {
        override fun areItemsTheSame(old: VpsHost, new: VpsHost) = old.id == new.id
        override fun areContentsTheSame(old: VpsHost, new: VpsHost) = old == new
    }
}

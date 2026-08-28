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
import io.github.tabssh.utils.RenewalUrgency
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

/**
 * List screen for the VPS Hosting Tracker: tracked VPS/hosting instances
 * grouped by tenant, mirroring [VncHostsActivity]'s pattern and the row
 * conventions established by [DomainTrackerActivity]. The detail line no
 * longer wraps — it lives inside a `HorizontalScrollView` so long rows
 * scroll horizontally instead. Because a `HorizontalScrollView` intercepts
 * the touch stream for taps landing on it, the row's click/long-click
 * listeners are duplicated onto it (see `onBindViewHolder`) so opening/
 * deleting a host still works no matter where in the row the user taps.
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
        app = tabSSHApp

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
        adapter.setOnRenewalPillClickListener { host -> maybeShowRenewalConfirm(host) }

        binding.fabAdd.setOnClickListener { launchAddHost() }

        // Grace-window purge: a host the user confirmed "not renewed" 30+
        // days ago is now removed. Confirming "renewed" (clears canceled_at)
        // or ignoring the prompt (canceled_at stays null) both keep it.
        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            app.database.vpsHostDao().deleteStaleCanceled(cutoff)
        }

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
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showImportSource(
                    this,
                    onFile = { importLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) },
                    onPaste = {
                        io.github.tabssh.ui.dialogs.TextImportDialog.show(
                            this, getString(R.string.nav_item_vps_tracker)
                        ) { text -> importText(text) }
                    }
                )
                true
            }
            R.id.action_export -> {
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showExportTarget(
                    this,
                    onFile = { exportLauncher.launch("VPS.md") },
                    onText = { showExportText() }
                )
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

    // ── Overdue-renewal confirmation ────────────────────────────────────────

    /** Only overdue hosts prompt — tapping the pill on a non-overdue row is a no-op. */
    private fun maybeShowRenewalConfirm(host: VpsHost) {
        val effectiveRenewalDate = RenewalUrgency.effectiveDate(host.renewalDate, host.billingCycle)
        if (RenewalUrgency.of(effectiveRenewalDate) != RenewalUrgency.OVERDUE) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.renewal_confirm_title)
            .setMessage(getString(R.string.renewal_confirm_message_fmt, "${host.tenant} · ${host.hostname}"))
            .setPositiveButton(R.string.renewal_confirm_yes) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = app.database.vpsHostDao()
                        // Roll the due date forward one cycle from where it
                        // actually stood (not just "now") — the same
                        // projection used for display, applied for real.
                        val nextDate = RenewalUrgency.effectiveDate(host.renewalDate, host.billingCycle, System.currentTimeMillis() + 1)
                        dao.update(host.copy(renewalDate = nextDate ?: host.renewalDate, canceledAt = null, modifiedAt = System.currentTimeMillis()))
                    }
                    if (!isAlive) return@launch
                    Toast.makeText(this@VpsTrackerActivity, getString(R.string.renewal_confirm_marked_renewed_fmt, host.hostname), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.renewal_confirm_no) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.database.vpsHostDao().setCanceledAt(host.id, System.currentTimeMillis())
                    }
                    if (!isAlive) return@launch
                    Toast.makeText(this@VpsTrackerActivity, getString(R.string.renewal_confirm_marked_canceled_fmt, host.hostname), Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ── Import / Export (SAF) ────────────────────────────────────────────────

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Logger.e(TAG, "VPS markdown read failed", e)
                    null
                }
            } ?: return@launch
            importText(text)
        }
    }

    /**
     * Parse [text] (from a file or pasted directly) and merge into the
     * database. Matched by ipv4 first when the parsed row has one — a
     * host's tenant label or hostname alias can be renamed between exports
     * but its IPv4 address is effectively stable — falling back to
     * (tenant, hostname) for rows with no ipv4 (e.g. a still-provisioning
     * host).
     */
    private fun importText(text: String) {
        lifecycleScope.launch {
            try {
                val result = VpsMarkdownImportExport.parse(text)
                var added = 0
                var updated = 0
                withContext(Dispatchers.IO) {
                    val dao = app.database.vpsHostDao()
                    val merged = result.hosts.map { parsed ->
                        val existing = parsed.ipv4?.takeIf { it.isNotBlank() }?.let { dao.getByIpv4(it) }
                            ?: dao.getByTenantAndHostname(parsed.tenant, parsed.hostname)
                        if (existing != null) {
                            updated++
                            parsed.copy(
                                id = existing.id,
                                reminderDaysBefore = existing.reminderDaysBefore,
                                lastReminderSentAt = existing.lastReminderSentAt,
                                createdAt = existing.createdAt
                            )
                        } else {
                            added++
                            parsed
                        }
                    }
                    dao.insertAll(merged)
                }
                for (warning in result.warnings) Logger.w(TAG, "Markdown import: $warning")
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.vps_tracker_import_success_fmt, added, updated), Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "VPS markdown import failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.import_qr_import_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.identity_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Build the Markdown export and show it as a themed, copyable text block instead of a file. */
    private fun showExportText() {
        lifecycleScope.launch {
            try {
                val markdown = withContext(Dispatchers.IO) {
                    VpsMarkdownImportExport.export(app.database.vpsHostDao().getAllList())
                }
                if (!isAlive) return@launch
                io.github.tabssh.ui.dialogs.TextExportDialog.show(
                    this@VpsTrackerActivity, getString(R.string.nav_item_vps_tracker), markdown
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "VPS markdown export (text) failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@VpsTrackerActivity, getString(R.string.identity_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
            .setTitle(getString(R.string.domain_delete_title, host.hostname))
            .setMessage(getString(R.string.vps_host_delete_confirm_message))
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
                        Toast.makeText(this@VpsTrackerActivity, getString(R.string.domain_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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

        private var onRenewalPillClick: ((VpsHost) -> Unit)? = null
        fun setOnRenewalPillClickListener(listener: (VpsHost) -> Unit) { onRenewalPillClick = listener }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val hostname: TextView = view.findViewById(R.id.text_vps_hostname)
            val renewalPill: TextView = view.findViewById(R.id.text_vps_renewal_pill)
            val detail: TextView = view.findViewById(R.id.text_vps_detail)
            val detailScroll: View = view.findViewById(R.id.scroll_vps_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vps_host, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val host = getItem(position)
            holder.hostname.text = "${host.tenant} · ${host.hostname}"
            // A recurring host's stored date may be a stale past occurrence
            // (see RenewalUrgency.effectiveDate) — project it forward to the
            // actual next due date before showing it or coloring the pill.
            val effectiveRenewalDate = RenewalUrgency.effectiveDate(host.renewalDate, host.billingCycle)
            val renewal = effectiveRenewalDate?.let { VpsMarkdownImportExport.formatRenewalDate(it) } ?: host.renewalRaw ?: "—"

            // A host awaiting deletion in the 30-day cancellation grace window
            // overrides the urgency pill entirely — its underlying date is no
            // longer meaningful once the user has confirmed it was canceled.
            val urgency = if (host.canceledAt != null) RenewalUrgency.UNKNOWN else RenewalUrgency.of(effectiveRenewalDate)
            holder.renewalPill.text = if (host.canceledAt != null) {
                getString(R.string.renewal_pill_canceled)
            } else {
                RenewalUrgency.pillText(this@VpsTrackerActivity, effectiveRenewalDate)
            }
            val pillBackground = androidx.core.content.ContextCompat.getColor(this@VpsTrackerActivity, urgency.containerColorAttrRes)
            val pillTextColor = androidx.core.content.ContextCompat.getColor(this@VpsTrackerActivity, urgency.colorAttrRes)
            (holder.renewalPill.background.mutate() as android.graphics.drawable.GradientDrawable).setColor(pillBackground)
            holder.renewalPill.setTextColor(pillTextColor)
            // Tapping the pill itself (not the whole row) opens the "was
            // this renewed?" prompt — kept separate from the row's own
            // click-to-edit so the two actions don't collide.
            holder.renewalPill.setOnClickListener { onRenewalPillClick?.invoke(host) }

            holder.detail.text = listOf(
                getString(R.string.vps_tracker_col_ipv4) + ": " + (host.ipv4 ?: "—"),
                getString(R.string.vps_tracker_col_ipv6) + ": " + (host.ipv6 ?: "—"),
                getString(R.string.vps_tracker_col_specs) + ": " + (host.specs ?: "—"),
                getString(R.string.vps_tracker_col_domain) + ": " + (host.linkedDomain ?: "—"),
                getString(R.string.vps_tracker_col_renewal) + ": " + renewal,
                getString(R.string.vps_tracker_col_cycle) + ": " + (host.billingCycle ?: "—"),
                getString(R.string.vps_tracker_col_price) + ": " + (host.price ?: "—")
            ).joinToString("  ·  ")

            // Zebra-striped rows: the HorizontalScrollView wrapping the detail
            // text intercepts the touch stream for any tap that lands on it, so
            // the same click/long-click listeners must be attached there too —
            // relying solely on itemView's listener silently swallows taps in
            // that region (see the `VpsTrackerActivity` class doc for context).
            val zebraColorAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurface
            } else {
                com.google.android.material.R.attr.colorSurfaceVariant
            }
            val zebraColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, zebraColorAttr)
            holder.itemView.setBackgroundColor(zebraColor)

            val clickListener = View.OnClickListener { onClick?.invoke(host) }
            val longClickListener = View.OnLongClickListener {
                onLongPress(host)
                true
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.itemView.setOnLongClickListener(longClickListener)
            holder.detailScroll.setOnClickListener(clickListener)
            holder.detailScroll.setOnLongClickListener(longClickListener)
        }
    }

    private object VpsHostDiff : DiffUtil.ItemCallback<VpsHost>() {
        override fun areItemsTheSame(old: VpsHost, new: VpsHost) = old.id == new.id
        override fun areContentsTheSame(old: VpsHost, new: VpsHost) = old == new
    }
}

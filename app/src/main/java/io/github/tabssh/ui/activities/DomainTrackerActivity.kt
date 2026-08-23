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
import io.github.tabssh.databinding.ActivityDomainTrackerBinding
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.tracker.DomainCsvImportExport
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List screen for the Domain Tracker: tracked domain registrations,
 * expirations, and renewal status, mirroring [VncHostsActivity]'s pattern.
 *
 * The detail line no longer wraps — it lives inside a `HorizontalScrollView`
 * so long rows scroll horizontally instead. Because a `HorizontalScrollView`
 * intercepts the touch stream for taps landing on it, the row's click/
 * long-click listeners are duplicated onto it (see `onBindViewHolder`) so
 * opening/deleting a domain still works no matter where in the row the
 * user taps.
 */
class DomainTrackerActivity : TabSSHActivity() {

    private companion object {
        private const val TAG = "DomainTrackerActivity"
    }

    private lateinit var binding: ActivityDomainTrackerBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: DomainAdapter

    private val isAlive: Boolean
        get() = !isFinishing && !isDestroyed

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importFrom(it) } }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> uri?.let { exportTo(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TabSSHApplication

        binding = ActivityDomainTrackerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.nav_item_domain_tracker)

        binding.sectionHeader.textHeaderEmoji.text = getString(R.string.domain_tracker_header_emoji)
        binding.sectionHeader.textHeaderTitle.text = getString(R.string.nav_item_domain_tracker)
        binding.sectionHeader.textHeaderSubtitle.text = getString(R.string.domain_tracker_header_subtitle)

        adapter = DomainAdapter(onLongPress = { domain -> showDomainMenu(domain) })
        binding.recyclerDomains.layoutManager = LinearLayoutManager(this)
        binding.recyclerDomains.adapter = adapter
        adapter.setOnItemClickListener { domain -> launchEditDomain(domain) }

        binding.fabAdd.setOnClickListener { launchAddDomain() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.domainDao().getAll().collect { domains ->
                    adapter.submitList(domains)
                    if (domains.isEmpty()) {
                        binding.recyclerDomains.visibility = View.GONE
                        binding.emptyState.visibility = View.VISIBLE
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.recyclerDomains.visibility = View.VISIBLE
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
                    onFile = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "*/*")) },
                    onPaste = {
                        io.github.tabssh.ui.dialogs.TextImportDialog.show(
                            this, getString(R.string.nav_item_domain_tracker)
                        ) { text -> importText(text) }
                    }
                )
                true
            }
            R.id.action_export -> {
                io.github.tabssh.ui.dialogs.ImportExportChooserDialog.showExportTarget(
                    this,
                    onFile = { exportLauncher.launch("Domain_List.csv") },
                    onText = { showExportText() }
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun launchAddDomain() {
        startActivity(Intent(this, DomainEditActivity::class.java))
    }

    private fun launchEditDomain(domain: Domain) {
        startActivity(
            Intent(this, DomainEditActivity::class.java).apply {
                putExtra(DomainEditActivity.EXTRA_DOMAIN_ID, domain.id)
            }
        )
    }

    // ── Import / Export (SAF) ────────────────────────────────────────────────

    private fun importFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Logger.e(TAG, "Domain CSV read failed", e)
                    null
                }
            } ?: return@launch
            importText(text)
        }
    }

    /** Parse [text] (from a file or pasted directly) and merge into the database by domain name. */
    private fun importText(text: String) {
        lifecycleScope.launch {
            try {
                val result = DomainCsvImportExport.parse(text)
                var added = 0
                var updated = 0
                withContext(Dispatchers.IO) {
                    val dao = app.database.domainDao()
                    val merged = result.domains.map { parsed ->
                        val existing = dao.getByDomainName(parsed.domainName)
                        if (existing != null) {
                            updated++
                            parsed.copy(
                                id = existing.id,
                                reminderDaysBefore = existing.reminderDaysBefore,
                                lastReminderSentAt = existing.lastReminderSentAt,
                                notes = existing.notes,
                                createdAt = existing.createdAt
                            )
                        } else {
                            added++
                            parsed
                        }
                    }
                    dao.insertAll(merged)
                }
                for (warning in result.warnings) Logger.w(TAG, "CSV import: $warning")
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_import_success_fmt, added, updated), Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Domain CSV import failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_import_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val domains = app.database.domainDao().getAllList()
                    val csv = DomainCsvImportExport.export(domains)
                    contentResolver.openOutputStream(uri)?.use { out -> out.write(csv.toByteArray()) }
                }
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_export_success), Toast.LENGTH_SHORT).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Domain CSV export failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Build the CSV export and show it as a themed, copyable text block instead of a file. */
    private fun showExportText() {
        lifecycleScope.launch {
            try {
                val csv = withContext(Dispatchers.IO) {
                    DomainCsvImportExport.export(app.database.domainDao().getAllList())
                }
                if (!isAlive) return@launch
                io.github.tabssh.ui.dialogs.TextExportDialog.show(
                    this@DomainTrackerActivity, getString(R.string.nav_item_domain_tracker), csv
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Domain CSV export (text) failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Long-press menu ───────────────────────────────────────────────────────

    private fun showDomainMenu(domain: Domain) {
        MaterialAlertDialogBuilder(this)
            .setTitle(domain.domainName)
            .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                when (which) {
                    0 -> launchEditDomain(domain)
                    1 -> confirmDelete(domain)
                }
            }
            .show()
    }

    private fun confirmDelete(domain: Domain) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.domain_delete_title, domain.domainName))
            .setMessage(getString(R.string.domain_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            app.database.domainDao().deleteById(domain.id)
                            TombstoneRecorder.record(app, TombstoneRecorder.DOMAIN, domain.id)
                        }
                        Logger.d(TAG, "Deleted domain: ${domain.domainName}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(TAG, "Failed to delete domain", e)
                        if (!isAlive) return@launch
                        Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── RecyclerView adapter ─────────────────────────────────────────────────

    private inner class DomainAdapter(
        private val onLongPress: (Domain) -> Unit
    ) : ListAdapter<Domain, DomainAdapter.ViewHolder>(DomainDiff) {

        private var onClick: ((Domain) -> Unit)? = null
        fun setOnItemClickListener(listener: (Domain) -> Unit) { onClick = listener }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.text_domain_name)
            val detail: TextView = view.findViewById(R.id.text_domain_detail)
            val detailScroll: View = view.findViewById(R.id.scroll_domain_detail)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_domain, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val domain = getItem(position)
            holder.name.text = domain.domainName
            val expiration = domain.expirationDate?.let { android.text.format.DateFormat.getMediumDateFormat(this@DomainTrackerActivity).format(it) }
                ?: getString(R.string.domain_edit_expiration_unset)
            holder.detail.text = listOf(
                getString(R.string.domain_tracker_col_privacy) + ": " + domain.privacyProtection,
                getString(R.string.domain_tracker_col_status) + ": " + domain.statusAtRegistrar,
                getString(R.string.domain_tracker_col_auto_renew) + ": " + domain.autoRenew,
                getString(R.string.domain_tracker_col_expires) + ": " + expiration
            ).joinToString("  ·  ")

            // Zebra-striped rows: the HorizontalScrollView wrapping the detail
            // text intercepts the touch stream for any tap that lands on it, so
            // the same click/long-click listeners must be attached there too —
            // relying solely on itemView's listener silently swallows taps in
            // that region (matches the pattern established in VpsTrackerActivity).
            val zebraColorAttr = if (position % 2 == 0) {
                com.google.android.material.R.attr.colorSurface
            } else {
                com.google.android.material.R.attr.colorSurfaceVariant
            }
            val zebraColor = com.google.android.material.color.MaterialColors.getColor(holder.itemView, zebraColorAttr)
            holder.itemView.setBackgroundColor(zebraColor)

            val clickListener = View.OnClickListener { onClick?.invoke(domain) }
            val longClickListener = View.OnLongClickListener {
                onLongPress(domain)
                true
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.itemView.setOnLongClickListener(longClickListener)
            holder.detailScroll.setOnClickListener(clickListener)
            holder.detailScroll.setOnLongClickListener(longClickListener)
        }
    }

    private object DomainDiff : DiffUtil.ItemCallback<Domain>() {
        override fun areItemsTheSame(old: Domain, new: Domain) = old.id == new.id
        override fun areContentsTheSame(old: Domain, new: Domain) = old == new
    }
}

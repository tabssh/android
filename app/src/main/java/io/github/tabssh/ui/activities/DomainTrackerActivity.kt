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
import androidx.annotation.StringRes
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
import io.github.tabssh.utils.RenewalUrgency
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.tabSSHApp

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
        private const val PREFS_NAME = "TabSSH"
        private const val PREF_SORT_OPTION = "domain_tracker_sort"
        private const val PREF_FILTER_OPTION = "domain_tracker_filter"

        // "Expiring soon" spans both urgency tiers short of overdue — the
        // 0-13 day CRITICAL band and the 14-27 day WARNING band.
        private val EXPIRING_SOON_TIERS = setOf(RenewalUrgency.CRITICAL, RenewalUrgency.WARNING)
    }

    private enum class SortOption(@StringRes val displayNameRes: Int) {
        NAME_ASC(R.string.domain_tracker_sort_name_asc),
        NAME_DESC(R.string.domain_tracker_sort_name_desc),
        EXPIRATION_ASC(R.string.domain_tracker_sort_expiration_asc),
        EXPIRATION_DESC(R.string.domain_tracker_sort_expiration_desc)
    }

    /**
     * Row subsets offered by the toolbar's filter action. Urgency tiers come
     * from [RenewalUrgency] so the filter and the row accent stripe always
     * agree on what "overdue" and "expiring soon" mean.
     */
    private enum class FilterOption(@StringRes val displayNameRes: Int) {
        ALL(R.string.tracker_filter_all),
        OVERDUE(R.string.tracker_filter_overdue),
        EXPIRING_SOON(R.string.tracker_filter_expiring_soon),
        CANCELED(R.string.tracker_filter_canceled)
    }

    private lateinit var binding: ActivityDomainTrackerBinding
    private lateinit var app: TabSSHApplication
    private lateinit var adapter: DomainAdapter
    private var allDomains: List<Domain> = emptyList()
    private var currentSortOption: SortOption = SortOption.NAME_ASC
    private var currentFilterOption: FilterOption = FilterOption.ALL

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
        app = tabSSHApp

        binding = ActivityDomainTrackerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBar.toolbar)
        supportActionBar?.setTitle(R.string.nav_item_domain_tracker)

        binding.sectionHeader.textHeaderEmoji.text = getString(R.string.domain_tracker_header_emoji)
        binding.sectionHeader.textHeaderTitle.text = getString(R.string.nav_item_domain_tracker)
        binding.sectionHeader.textHeaderSubtitle.text = getString(R.string.domain_tracker_header_subtitle)

        currentSortOption = loadSortPreference()
        currentFilterOption = loadFilterPreference()

        adapter = DomainAdapter(onLongPress = { domain -> showDomainMenu(domain) })
        binding.recyclerDomains.layoutManager = LinearLayoutManager(this)
        binding.recyclerDomains.adapter = adapter
        adapter.setOnItemClickListener { domain -> launchEditDomain(domain) }
        adapter.setOnRenewalPillClickListener { domain -> maybeShowRenewalConfirm(domain) }

        binding.fabAdd.setOnClickListener { launchAddDomain() }

        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            app.database.domainDao().deleteStaleCanceled(cutoff)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.database.domainDao().getAll().collect { domains ->
                    allDomains = domains
                    applyFilterAndSort()
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
            R.id.action_sort -> {
                showSortDialog()
                true
            }
            R.id.action_filter -> {
                showFilterDialog()
                true
            }
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

    // ── Filtering and sorting ────────────────────────────────────────────────

    /**
     * Narrow [allDomains] to the current filter, sort the survivors, and pick
     * the matching empty state: "no domains at all" and "no domains match this
     * filter" are different situations and get different copy.
     */
    private fun applyFilterAndSort() {
        val filtered = allDomains.filter { matchesFilter(it) }
        val sorted = when (currentSortOption) {
            SortOption.NAME_ASC -> filtered.sortedBy { it.domainName.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.domainName.lowercase() }
            SortOption.EXPIRATION_ASC -> filtered.sortedBy { it.expirationDate ?: Long.MAX_VALUE }
            SortOption.EXPIRATION_DESC -> filtered.sortedByDescending { it.expirationDate ?: Long.MIN_VALUE }
        }
        adapter.submitList(sorted)
        updateEmptyState(sorted.isEmpty())
    }

    private fun matchesFilter(domain: Domain): Boolean {
        val canceled = domain.canceledAt != null
        return when (currentFilterOption) {
            FilterOption.ALL -> true
            FilterOption.CANCELED -> canceled
            FilterOption.OVERDUE -> !canceled && RenewalUrgency.of(domain.expirationDate) == RenewalUrgency.OVERDUE
            FilterOption.EXPIRING_SOON -> !canceled && RenewalUrgency.of(domain.expirationDate) in EXPIRING_SOON_TIERS
        }
    }

    private fun updateEmptyState(listIsEmpty: Boolean) {
        if (!listIsEmpty) {
            binding.emptyState.visibility = View.GONE
            binding.recyclerDomains.visibility = View.VISIBLE
            return
        }
        val filterHidEverything = allDomains.isNotEmpty()
        binding.textEmptyTitle.setText(
            if (filterHidEverything) R.string.tracker_filter_empty_title else R.string.domain_tracker_empty_title
        )
        binding.textEmptySubtitle.setText(
            if (filterHidEverything) R.string.tracker_filter_empty_subtitle else R.string.domain_tracker_empty_subtitle
        )
        binding.recyclerDomains.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
    }

    private fun showFilterDialog() {
        val options = FilterOption.values()
        val labels = options.map { getString(it.displayNameRes) }.toTypedArray()
        val checkedIndex = options.indexOf(currentFilterOption)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tracker_filter_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                currentFilterOption = options[which]
                saveFilterPreference(currentFilterOption)
                applyFilterAndSort()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveFilterPreference(option: FilterOption) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_FILTER_OPTION, option.name)
            .apply()
    }

    private fun loadFilterPreference(): FilterOption {
        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_FILTER_OPTION, null)
            ?: return FilterOption.ALL
        return try {
            FilterOption.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            FilterOption.ALL
        }
    }

    private fun showSortDialog() {
        val options = SortOption.values()
        val labels = options.map { getString(it.displayNameRes) }.toTypedArray()
        val checkedIndex = options.indexOf(currentSortOption)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connections_sort_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                currentSortOption = options[which]
                saveSortPreference(currentSortOption)
                applyFilterAndSort()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveSortPreference(option: SortOption) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_SORT_OPTION, option.name)
            .apply()
    }

    private fun loadSortPreference(): SortOption {
        val stored = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SORT_OPTION, null)
            ?: return SortOption.NAME_ASC
        return try {
            SortOption.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            SortOption.NAME_ASC
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

    // ── Overdue-renewal confirmation ────────────────────────────────────────

    /** Only overdue domains prompt — tapping the pill on a non-overdue row is a no-op. */
    private fun maybeShowRenewalConfirm(domain: Domain) {
        if (RenewalUrgency.of(domain.expirationDate) != RenewalUrgency.OVERDUE) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.renewal_confirm_title)
            .setMessage(getString(R.string.renewal_confirm_message_fmt, domain.domainName))
            .setPositiveButton(R.string.renewal_confirm_yes) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val dao = app.database.domainDao()
                        // Domains have no billing-cycle field to project from —
                        // default to rolling the expiration forward exactly one
                        // year from where it actually stood, mirroring the VPS
                        // side's "advance from the real due date, not now" rule.
                        val calendar = java.util.Calendar.getInstance()
                        calendar.timeInMillis = domain.expirationDate ?: System.currentTimeMillis()
                        while (calendar.timeInMillis < System.currentTimeMillis()) {
                            calendar.add(java.util.Calendar.YEAR, 1)
                        }
                        dao.update(domain.copy(expirationDate = calendar.timeInMillis, canceledAt = null, modifiedAt = System.currentTimeMillis()))
                    }
                    if (!isAlive) return@launch
                    Toast.makeText(this@DomainTrackerActivity, getString(R.string.renewal_confirm_marked_renewed_fmt, domain.domainName), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.renewal_confirm_no) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        app.database.domainDao().setCanceledAt(domain.id, System.currentTimeMillis())
                    }
                    if (!isAlive) return@launch
                    Toast.makeText(this@DomainTrackerActivity, getString(R.string.renewal_confirm_marked_canceled_fmt, domain.domainName), Toast.LENGTH_LONG).show()
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
                if (result.domains.isEmpty()) {
                    for (warning in result.warnings) Logger.w(TAG, "CSV import: $warning")
                    if (!isAlive) return@launch
                    Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_import_empty), Toast.LENGTH_LONG).show()
                    return@launch
                }
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
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.import_qr_import_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportTo(uri: Uri) {
        lifecycleScope.launch {
            try {
                var isEmpty = false
                withContext(Dispatchers.IO) {
                    val domains = app.database.domainDao().getAllList()
                    isEmpty = domains.isEmpty()
                    val csv = DomainCsvImportExport.export(domains)
                    contentResolver.openOutputStream(uri)?.use { out -> out.write(csv.toByteArray()) }
                }
                if (!isAlive) return@launch
                if (isEmpty) {
                    Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_export_empty), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@DomainTrackerActivity, getString(R.string.domain_tracker_export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Domain CSV export failed", e)
                if (!isAlive) return@launch
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.identity_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@DomainTrackerActivity, getString(R.string.identity_export_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
            .setMessage(getString(R.string.domain_delete_confirm_message))
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

        private var onRenewalPillClick: ((Domain) -> Unit)? = null
        fun setOnRenewalPillClickListener(listener: (Domain) -> Unit) { onRenewalPillClick = listener }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.text_domain_name)
            val renewalPill: TextView = view.findViewById(R.id.text_domain_renewal_pill)
            val detail: TextView = view.findViewById(R.id.text_domain_detail)
            val detailScroll: View = view.findViewById(R.id.scroll_domain_detail)
            val statusAccent: View = view.findViewById(R.id.view_domain_status_accent)
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

            // A domain awaiting deletion in the 30-day cancellation grace
            // window overrides the urgency pill entirely — its underlying
            // date is no longer meaningful once canceled is confirmed.
            val urgency = if (domain.canceledAt != null) RenewalUrgency.UNKNOWN else RenewalUrgency.of(domain.expirationDate)
            holder.renewalPill.text = if (domain.canceledAt != null) {
                getString(R.string.renewal_pill_canceled)
            } else {
                RenewalUrgency.pillText(this@DomainTrackerActivity, domain.expirationDate)
            }
            val pillBackground = androidx.core.content.ContextCompat.getColor(this@DomainTrackerActivity, urgency.containerColorAttrRes)
            val pillTextColor = androidx.core.content.ContextCompat.getColor(this@DomainTrackerActivity, urgency.colorAttrRes)
            (holder.renewalPill.background.mutate() as android.graphics.drawable.GradientDrawable).setColor(pillBackground)
            holder.renewalPill.setTextColor(pillTextColor)
            holder.renewalPill.setOnClickListener { onRenewalPillClick?.invoke(domain) }

            // Leading accent stripe, tinted from the same urgency tier as the
            // pill so renewal status is readable without parsing the label.
            holder.statusAccent.setBackgroundColor(pillTextColor)

            holder.detail.text = listOf(
                getString(R.string.domain_tracker_col_privacy) + ": " + domain.privacyProtection,
                getString(R.string.domain_tracker_col_status) + ": " + domain.statusAtRegistrar,
                getString(R.string.domain_edit_switch_auto_renew) + ": " + domain.autoRenew,
                getString(R.string.domain_tracker_col_expires) + ": " + expiration
            ).joinToString("  ·  ")

            // The HorizontalScrollView wrapping the detail text intercepts the
            // touch stream for any tap that lands on it, so the same click/
            // long-click listeners must be attached there too — relying solely
            // on itemView's listener silently swallows taps in that region
            // (matches the pattern established in VpsTrackerActivity).
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

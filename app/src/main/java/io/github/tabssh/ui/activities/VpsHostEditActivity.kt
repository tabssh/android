package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.VpsHost
import io.github.tabssh.tracker.BillingCycle
import io.github.tabssh.tracker.BillingCycleText
import io.github.tabssh.tracker.RenewalDateInput
import io.github.tabssh.utils.ThrowableMapper
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import io.github.tabssh.utils.tabSSHApp

/**
 * Create or edit a [VpsHost] tracker record.
 *
 * Launch extras:
 *   [EXTRA_VPS_HOST_ID] — String UUID; omit for a new host.
 */
class VpsHostEditActivity : TabSSHActivity() {

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    companion object {
        private const val TAG = "VpsHostEditActivity"

        /** String — UUID of the VpsHost to edit. Omit (or pass null) to create a new one. */
        const val EXTRA_VPS_HOST_ID = "vps_host_id"

        private const val DATE_PICKER_TAG = "vps_renewal_date_picker"
        private const val DEFAULT_REMINDER_DAYS = 7

        // Positions in the mode and period dropdown lists.
        private const val MODE_EVERY = 0
        private const val MODE_TIMES_PER = 1
        private const val MODE_NONE = 2
        private const val PERIOD_MONTH = 0
        private const val PERIOD_YEAR = 1
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var layoutTenant: TextInputLayout
    private lateinit var editTenant: TextInputEditText
    private lateinit var layoutHostname: TextInputLayout
    private lateinit var editHostname: TextInputEditText
    private lateinit var editIpv4: TextInputEditText
    private lateinit var editIpv6: TextInputEditText
    private lateinit var editSpecs: TextInputEditText
    private lateinit var editLinkedDomain: TextInputEditText
    private lateinit var layoutRenewal: TextInputLayout
    private lateinit var editRenewalRaw: TextInputEditText
    private lateinit var dropdownCycleMode: MaterialAutoCompleteTextView
    private lateinit var layoutCycleCount: TextInputLayout
    private lateinit var dropdownCycleCount: MaterialAutoCompleteTextView
    private lateinit var layoutCyclePeriod: TextInputLayout
    private lateinit var dropdownCyclePeriod: MaterialAutoCompleteTextView
    private lateinit var textCycleSummary: android.widget.TextView
    private lateinit var editPrice: TextInputEditText
    private lateinit var editDescription: TextInputEditText
    private lateinit var editReminderDays: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var editingHostId: String? = null
    private var editingExisting: VpsHost? = null

    /**
     * A stored cycle the dropdowns cannot show ("10 years", "weekly", or text
     * no parser reads). Saved back unchanged until the user picks a dropdown.
     */
    private var legacyCycle: String? = null

    private val modeLabels by lazy {
        listOf(
            getString(R.string.vps_host_edit_cycle_mode_every),
            getString(R.string.vps_host_edit_cycle_mode_times_per),
            getString(R.string.vps_host_edit_cycle_mode_none)
        )
    }
    private val countLabels = (1..BillingCycle.MAX_DROPDOWN_COUNT).map { it.toString() }
    private val periodLabels by lazy {
        listOf(
            getString(R.string.vps_host_edit_cycle_period_month),
            getString(R.string.vps_host_edit_cycle_period_year)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vps_host_edit)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        layoutTenant = findViewById(R.id.layout_tenant)
        editTenant = findViewById(R.id.edit_tenant)
        layoutHostname = findViewById(R.id.layout_hostname)
        editHostname = findViewById(R.id.edit_hostname)
        editIpv4 = findViewById(R.id.edit_ipv4)
        editIpv6 = findViewById(R.id.edit_ipv6)
        editSpecs = findViewById(R.id.edit_specs)
        editLinkedDomain = findViewById(R.id.edit_linked_domain)
        layoutRenewal = findViewById(R.id.layout_renewal_raw)
        editRenewalRaw = findViewById(R.id.edit_renewal_raw)
        dropdownCycleMode = findViewById(R.id.dropdown_cycle_mode)
        layoutCycleCount = findViewById(R.id.layout_cycle_count)
        dropdownCycleCount = findViewById(R.id.dropdown_cycle_count)
        layoutCyclePeriod = findViewById(R.id.layout_cycle_period)
        dropdownCyclePeriod = findViewById(R.id.dropdown_cycle_period)
        textCycleSummary = findViewById(R.id.text_cycle_summary)
        editPrice = findViewById(R.id.edit_price)
        editDescription = findViewById(R.id.edit_description)
        editReminderDays = findViewById(R.id.edit_reminder_days)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDelete = findViewById(R.id.btn_delete)

        val hostIdToEdit = intent.getStringExtra(EXTRA_VPS_HOST_ID)
        editingHostId = hostIdToEdit
        val isEditing = hostIdToEdit != null

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (isEditing) R.string.vps_host_edit_title_edit else R.string.vps_host_edit_title_new
        )

        setupRenewalField()
        setupCycleDropdowns()

        if (hostIdToEdit != null) {
            btnDelete.visibility = View.VISIBLE
            populateFromDb(hostIdToEdit)
        }

        btnSave.setOnClickListener { saveHost() }
        btnCancel.setOnClickListener { confirmDiscardIfNeeded { finish() } }
        btnDelete.setOnClickListener { confirmDelete() }

        setupUnsavedChangesGuard()
    }

    /**
     * Wires every form field to flip [hasUnsavedChanges] and opts this
     * screen into the shared discard-confirmation guard.
     */
    private fun setupUnsavedChangesGuard() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { hasUnsavedChanges = true }
        }
        editTenant.addTextChangedListener(watcher)
        editHostname.addTextChangedListener(watcher)
        editIpv4.addTextChangedListener(watcher)
        editIpv6.addTextChangedListener(watcher)
        editSpecs.addTextChangedListener(watcher)
        editLinkedDomain.addTextChangedListener(watcher)
        editRenewalRaw.addTextChangedListener(watcher)
        editPrice.addTextChangedListener(watcher)
        editDescription.addTextChangedListener(watcher)
        editReminderDays.addTextChangedListener(watcher)

        enableUnsavedChangesGuard()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    /**
     * The renewal field takes typed text ("May 15, 2027", "2027, May 15",
     * "N/A") and a calendar end icon that fills it. Validation runs on save;
     * leaving the field turns N/A into the next January 1st right away.
     */
    private fun setupRenewalField() {
        layoutRenewal.setEndIconOnClickListener { showDatePicker() }
        editRenewalRaw.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { layoutRenewal.error = null }
        })
        editRenewalRaw.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            when (val parsed = RenewalDateInput.parse(editRenewalRaw.text?.toString())) {
                is RenewalDateInput.Parsed.NotApplicable -> applyNotApplicable(parsed.epochMillis)
                is RenewalDateInput.Parsed.Date -> {
                    val canonical = RenewalDateInput.format(parsed.epochMillis)
                    if (editRenewalRaw.text?.toString() != canonical) editRenewalRaw.setText(canonical)
                }
                null -> Unit
            }
        }
    }

    private fun showDatePicker() {
        val current = RenewalDateInput.parse(editRenewalRaw.text?.toString())?.epochMillis
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.vps_host_edit_pick_date)
            .setSelection(current ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        // The picker's selection is UTC midnight, the same form renewalDate is stored in.
        picker.addOnPositiveButtonClickListener { selection ->
            editRenewalRaw.setText(RenewalDateInput.format(selection))
        }
        picker.show(supportFragmentManager, DATE_PICKER_TAG)
    }

    /** A free host renews yearly on January 1st, so N/A also sets the cycle to Every 1 Year. */
    private fun applyNotApplicable(epochMillis: Long) {
        editRenewalRaw.setText(RenewalDateInput.format(epochMillis))
        legacyCycle = null
        showCycle(BillingCycle.ANNUALLY)
    }

    private fun setupCycleDropdowns() {
        dropdownCycleMode.setSimpleItems(modeLabels.toTypedArray())
        dropdownCycleCount.setSimpleItems(countLabels.toTypedArray())
        dropdownCyclePeriod.setSimpleItems(periodLabels.toTypedArray())
        val onPicked = android.widget.AdapterView.OnItemClickListener { _, _, _, _ ->
            hasUnsavedChanges = true
            legacyCycle = null
            refreshCycleUi()
        }
        dropdownCycleMode.onItemClickListener = onPicked
        dropdownCycleCount.onItemClickListener = onPicked
        dropdownCyclePeriod.onItemClickListener = onPicked
        // Most VPS plans renew yearly; an existing host overwrites this in populateFromDb().
        showCycle(BillingCycle.ANNUALLY)
    }

    /** Set the dropdowns to [cycle], or to One-time when it is null. Only call with a cycle that fits the dropdowns. */
    private fun showCycle(cycle: BillingCycle?) {
        if (cycle == null) {
            dropdownCycleMode.setText(modeLabels[MODE_NONE], false)
        } else {
            val mode = if (cycle.mode == BillingCycle.Mode.TIMES_PER) MODE_TIMES_PER else MODE_EVERY
            dropdownCycleMode.setText(modeLabels[mode], false)
            dropdownCycleCount.setText(cycle.count.toString(), false)
            val period = if (cycle.period == BillingCycle.Period.YEAR) PERIOD_YEAR else PERIOD_MONTH
            dropdownCyclePeriod.setText(periodLabels[period], false)
        }
        refreshCycleUi()
    }

    /** The cycle the dropdowns show; null for One-time. */
    private fun selectedCycle(): BillingCycle? {
        val mode = when (modeLabels.indexOf(dropdownCycleMode.text?.toString())) {
            MODE_TIMES_PER -> BillingCycle.Mode.TIMES_PER
            MODE_NONE -> return null
            else -> BillingCycle.Mode.EVERY
        }
        val count = dropdownCycleCount.text?.toString()?.toIntOrNull()?.coerceIn(1, BillingCycle.MAX_DROPDOWN_COUNT) ?: 1
        val period = if (periodLabels.indexOf(dropdownCyclePeriod.text?.toString()) == PERIOD_MONTH) {
            BillingCycle.Period.MONTH
        } else {
            BillingCycle.Period.YEAR
        }
        return BillingCycle.of(mode, count, period)
    }

    private fun refreshCycleUi() {
        val oneTime = modeLabels.indexOf(dropdownCycleMode.text?.toString()) == MODE_NONE
        layoutCycleCount.isEnabled = !oneTime
        layoutCyclePeriod.isEnabled = !oneTime
        val legacy = legacyCycle
        textCycleSummary.text = when {
            legacy != null -> getString(
                R.string.vps_host_edit_cycle_summary_legacy_fmt,
                BillingCycleText.label(this, legacy) ?: legacy
            )
            oneTime -> getString(R.string.vps_host_edit_cycle_summary_none)
            else -> getString(
                R.string.vps_host_edit_cycle_summary_fmt,
                selectedCycle()?.let { BillingCycleText.label(this, it) }.orEmpty()
            )
        }
    }

    private fun populateFromDb(hostId: String) {
        lifecycleScope.launch {
            val host = withContext(Dispatchers.IO) { app.database.vpsHostDao().getById(hostId) }
            if (host == null) {
                Toast.makeText(this@VpsHostEditActivity, getString(R.string.vps_host_edit_not_found), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            editingExisting = host
            editTenant.setText(host.tenant)
            editHostname.setText(host.hostname)
            editIpv4.setText(host.ipv4 ?: "")
            editIpv6.setText(host.ipv6 ?: "")
            editSpecs.setText(host.specs ?: "")
            editLinkedDomain.setText(host.linkedDomain ?: "")
            editRenewalRaw.setText(host.renewalDate?.let(RenewalDateInput::format) ?: host.renewalRaw.orEmpty())
            val storedCycle = host.billingCycle?.trim()?.takeIf { it.isNotEmpty() }
            val parsedCycle = BillingCycle.parse(storedCycle)
            when {
                storedCycle == null -> {
                    legacyCycle = null
                    showCycle(null)
                }
                parsedCycle != null && parsedCycle.fitsDropdowns -> {
                    legacyCycle = null
                    showCycle(parsedCycle)
                }
                else -> {
                    legacyCycle = storedCycle
                    // The dropdowns sit on a neutral default; the summary names the kept cycle.
                    showCycle(BillingCycle.ANNUALLY)
                }
            }
            editPrice.setText(host.price ?: "")
            editDescription.setText(host.description ?: "")
            editReminderDays.setText(host.reminderDaysBefore.toString())

            // Every field set above flips the dirty-flag listeners installed
            // in setupUnsavedChangesGuard() — DB-driven population is not a
            // user edit, so clear the flag once population is done.
            hasUnsavedChanges = false
        }
    }

    /**
     * Clears any previously shown inline validation errors so a fixed field
     * doesn't keep showing a stale Material error state.
     */
    private fun clearFieldErrors() {
        layoutTenant.error = null
        layoutHostname.error = null
        layoutRenewal.error = null
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun saveHost() {
        clearFieldErrors()
        val tenant = editTenant.text?.toString()?.trim()
        if (tenant.isNullOrBlank()) {
            layoutTenant.error = getString(R.string.vps_host_edit_error_tenant_required)
            return
        }
        val hostname = editHostname.text?.toString()?.trim()
        if (hostname.isNullOrBlank()) {
            layoutHostname.error = getString(R.string.vps_host_edit_error_hostname_required)
            return
        }

        val renewalText = editRenewalRaw.text?.toString()?.trim().orEmpty()
        // Blank means "no date tracked"; anything else must be a real date or N/A.
        val renewalInput = if (renewalText.isEmpty()) {
            null
        } else {
            RenewalDateInput.parse(renewalText) ?: run {
                layoutRenewal.error = getString(R.string.vps_host_edit_error_renewal_invalid)
                editRenewalRaw.requestFocus()
                return
            }
        }
        if (renewalInput is RenewalDateInput.Parsed.NotApplicable) applyNotApplicable(renewalInput.epochMillis)
        val renewalDate = renewalInput?.epochMillis
        val renewalRaw = renewalDate?.let(RenewalDateInput::format)
        val billingCycle = legacyCycle ?: selectedCycle()?.storageText()
        val reminderDays = editReminderDays.text?.toString()?.toIntOrNull() ?: DEFAULT_REMINDER_DAYS
        val now = System.currentTimeMillis()
        val existing = editingExisting
        val id = existing?.id ?: editingHostId ?: UUID.randomUUID().toString()

        val host = VpsHost(
            id = id,
            tenant = tenant,
            hostname = hostname,
            ipv4 = editIpv4.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            ipv6 = editIpv6.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            specs = editSpecs.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            linkedDomain = editLinkedDomain.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            renewalRaw = renewalRaw,
            renewalDate = renewalDate,
            billingCycle = billingCycle,
            price = editPrice.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            description = editDescription.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            reminderDaysBefore = reminderDays,
            // A changed renewal date invalidates any previous reminder dedupe
            // marker so the worker can fire again for the new date.
            lastReminderSentAt = if (existing?.renewalDate == renewalDate) existing?.lastReminderSentAt else null,
            createdAt = existing?.createdAt ?: now,
            modifiedAt = now
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        app.database.vpsHostDao().update(host)
                    } else {
                        app.database.vpsHostDao().insert(host)
                    }
                }
                Toast.makeText(this@VpsHostEditActivity, getString(R.string.vps_host_edit_saved), Toast.LENGTH_SHORT).show()
                hasUnsavedChanges = false
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@VpsHostEditActivity, TAG, e, "Failed to save VPS host")
                showError(
                    getString(R.string.cloud_save_failed, mapped.message),
                    copyText = mapped.technicalDetail,
                    onRetry = { saveHost() }
                )
            }
        }
    }

    private fun confirmDelete() {
        val hostId = editingHostId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vps_host_delete_confirm_title))
            .setMessage(getString(R.string.vps_host_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteHost(hostId) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteHost(hostId: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.database.vpsHostDao().deleteById(hostId)
                    TombstoneRecorder.record(app, TombstoneRecorder.VPS_HOST, hostId)
                }
                Toast.makeText(this@VpsHostEditActivity, getString(R.string.vps_host_edit_deleted), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@VpsHostEditActivity, TAG, e, "Failed to delete VPS host")
                showError(
                    getString(R.string.domain_delete_failed_fmt, mapped.message),
                    copyText = mapped.technicalDetail,
                    onRetry = { deleteHost(hostId) }
                )
            }
        }
    }
}

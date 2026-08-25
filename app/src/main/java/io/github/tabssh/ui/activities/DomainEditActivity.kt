package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.Domain
import io.github.tabssh.utils.ThrowableMapper
import io.github.tabssh.utils.logging.Logger
import io.github.tabssh.utils.showError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import io.github.tabssh.utils.tabSSHApp

/**
 * Create or edit a [Domain] tracker record.
 *
 * Launch extras:
 *   [EXTRA_DOMAIN_ID] — String UUID; omit for a new domain.
 */
class DomainEditActivity : TabSSHActivity() {

    // Edit screens use an up arrow instead of the hamburger, routed
    // through the same OnBackPressedDispatcher as system Back.
    override val navigationAffordance: NavigationAffordance = NavigationAffordance.UP

    companion object {
        private const val TAG = "DomainEditActivity"

        /** String — UUID of the Domain to edit. Omit (or pass null) to create a new one. */
        const val EXTRA_DOMAIN_ID = "domain_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var switchPrivacy: SwitchMaterial
    private lateinit var editStatus: TextInputEditText
    private lateinit var switchAutoRenew: SwitchMaterial
    private lateinit var editExpiration: TextInputEditText
    private lateinit var editReminderDays: TextInputEditText
    private lateinit var editNotes: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var editingDomainId: String? = null
    private var editingExisting: Domain? = null
    private var selectedExpirationMillis: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domain_edit)

        app = tabSSHApp

        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        switchPrivacy = findViewById(R.id.switch_privacy)
        editStatus = findViewById(R.id.edit_status)
        switchAutoRenew = findViewById(R.id.switch_auto_renew)
        editExpiration = findViewById(R.id.edit_expiration)
        editReminderDays = findViewById(R.id.edit_reminder_days)
        editNotes = findViewById(R.id.edit_notes)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDelete = findViewById(R.id.btn_delete)

        editingDomainId = intent.getStringExtra(EXTRA_DOMAIN_ID)
        val isEditing = editingDomainId != null

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (isEditing) R.string.domain_edit_title_edit else R.string.domain_edit_title_new
        )

        editExpiration.setText(getString(R.string.domain_edit_expiration_unset))
        editExpiration.setOnClickListener { showExpirationPicker() }

        if (isEditing) {
            btnDelete.visibility = View.VISIBLE
            populateFromDb(editingDomainId!!)
        }

        btnSave.setOnClickListener { saveDomain() }
        btnCancel.setOnClickListener { confirmDiscardIfNeeded { finish() } }
        btnDelete.setOnClickListener { confirmDelete() }

        setupUnsavedChangesGuard()
    }

    /**
     * Wires every primary form field to flip [hasUnsavedChanges] and opts
     * this screen into the shared discard-confirmation guard.
     */
    private fun setupUnsavedChangesGuard() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { hasUnsavedChanges = true }
        }
        editName.addTextChangedListener(watcher)
        editStatus.addTextChangedListener(watcher)
        editReminderDays.addTextChangedListener(watcher)
        editNotes.addTextChangedListener(watcher)
        switchPrivacy.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }
        switchAutoRenew.setOnCheckedChangeListener { _, _ -> hasUnsavedChanges = true }

        enableUnsavedChangesGuard()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun showExpirationPicker() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        selectedExpirationMillis?.let { cal.timeInMillis = it }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                picked.clear()
                picked.set(year, month, dayOfMonth)
                selectedExpirationMillis = picked.timeInMillis
                editExpiration.setText(
                    android.text.format.DateFormat.getMediumDateFormat(this).format(picked.time)
                )
                hasUnsavedChanges = true
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun populateFromDb(domainId: String) {
        lifecycleScope.launch {
            val domain = withContext(Dispatchers.IO) { app.database.domainDao().getById(domainId) }
            if (domain == null) {
                Toast.makeText(this@DomainEditActivity, getString(R.string.domain_edit_not_found), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            editingExisting = domain
            editName.setText(domain.domainName)
            switchPrivacy.isChecked = domain.privacyProtection.equals("enabled", ignoreCase = true) ||
                domain.privacyProtection.equals("true", ignoreCase = true) ||
                domain.privacyProtection.equals("yes", ignoreCase = true)
            editStatus.setText(domain.statusAtRegistrar)
            switchAutoRenew.isChecked = domain.autoRenew.equals("enabled", ignoreCase = true) ||
                domain.autoRenew.equals("true", ignoreCase = true) ||
                domain.autoRenew.equals("yes", ignoreCase = true)
            selectedExpirationMillis = domain.expirationDate
            editExpiration.setText(
                domain.expirationDate?.let { android.text.format.DateFormat.getMediumDateFormat(this@DomainEditActivity).format(it) }
                    ?: getString(R.string.domain_edit_expiration_unset)
            )
            editReminderDays.setText(domain.reminderDaysBefore.toString())
            editNotes.setText(domain.notes ?: "")
            hasUnsavedChanges = false
        }
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun saveDomain() {
        val name = editName.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            editName.error = getString(R.string.domain_edit_error_name_required)
            return
        }

        val reminderDays = editReminderDays.text?.toString()?.toIntOrNull() ?: 7
        val now = System.currentTimeMillis()
        val existing = editingExisting
        val id = existing?.id ?: editingDomainId ?: UUID.randomUUID().toString()

        val domain = Domain(
            id = id,
            domainName = name,
            privacyProtection = if (switchPrivacy.isChecked) "Enabled" else "Disabled",
            statusAtRegistrar = editStatus.text?.toString()?.trim().orEmpty(),
            autoRenew = if (switchAutoRenew.isChecked) "Enabled" else "Disabled",
            expirationDate = selectedExpirationMillis,
            reminderDaysBefore = reminderDays,
            // A changed expiration invalidates any previous reminder dedupe marker
            // so the worker can fire again for the new date.
            lastReminderSentAt = if (existing?.expirationDate == selectedExpirationMillis) existing?.lastReminderSentAt else null,
            notes = editNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: now,
            modifiedAt = now
        )

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        app.database.domainDao().update(domain)
                    } else {
                        app.database.domainDao().insert(domain)
                    }
                }
                Toast.makeText(this@DomainEditActivity, getString(R.string.domain_edit_saved), Toast.LENGTH_SHORT).show()
                hasUnsavedChanges = false
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@DomainEditActivity, TAG, e, "Failed to save domain")
                showError(
                    getString(R.string.cloud_save_failed, mapped.message),
                    copyText = mapped.technicalDetail,
                    onRetry = { saveDomain() }
                )
            }
        }
    }

    private fun confirmDelete() {
        val domainId = editingDomainId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.domain_delete_confirm_title))
            .setMessage(getString(R.string.domain_delete_confirm_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteDomain(domainId) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteDomain(domainId: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.database.domainDao().deleteById(domainId)
                    TombstoneRecorder.record(app, TombstoneRecorder.DOMAIN, domainId)
                }
                Toast.makeText(this@DomainEditActivity, getString(R.string.domain_edit_deleted), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val mapped = ThrowableMapper.map(this@DomainEditActivity, TAG, e, "Failed to delete domain")
                showError(
                    getString(R.string.domain_delete_failed_fmt, mapped.message),
                    copyText = mapped.technicalDetail,
                    onRetry = { deleteDomain(domainId) }
                )
            }
        }
    }
}

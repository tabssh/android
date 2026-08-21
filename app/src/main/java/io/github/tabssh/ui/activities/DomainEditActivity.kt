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
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID

/**
 * Create or edit a [Domain] tracker record.
 *
 * Launch extras:
 *   [EXTRA_DOMAIN_ID] — String UUID; omit for a new domain.
 */
class DomainEditActivity : TabSSHActivity() {

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

        app = application as TabSSHApplication

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
        btnCancel.setOnClickListener { finish() }
        btnDelete.setOnClickListener { confirmDelete() }
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
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save domain", e)
                Toast.makeText(this@DomainEditActivity, getString(R.string.domain_edit_save_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
                Logger.e(TAG, "Failed to delete domain", e)
                Toast.makeText(this@DomainEditActivity, getString(R.string.domain_edit_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}

package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.VpsHost
import io.github.tabssh.tracker.VpsMarkdownImportExport
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Create or edit a [VpsHost] tracker record.
 *
 * Launch extras:
 *   [EXTRA_VPS_HOST_ID] — String UUID; omit for a new host.
 */
class VpsHostEditActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "VpsHostEditActivity"

        /** String — UUID of the VpsHost to edit. Omit (or pass null) to create a new one. */
        const val EXTRA_VPS_HOST_ID = "vps_host_id"
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editTenant: TextInputEditText
    private lateinit var editHostname: TextInputEditText
    private lateinit var editIpv4: TextInputEditText
    private lateinit var editIpv6: TextInputEditText
    private lateinit var editSpecs: TextInputEditText
    private lateinit var editLinkedDomain: TextInputEditText
    private lateinit var editRenewalRaw: TextInputEditText
    private lateinit var editBillingCycle: TextInputEditText
    private lateinit var editPrice: TextInputEditText
    private lateinit var editDescription: TextInputEditText
    private lateinit var editReminderDays: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var editingHostId: String? = null
    private var editingExisting: VpsHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vps_host_edit)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        editTenant = findViewById(R.id.edit_tenant)
        editHostname = findViewById(R.id.edit_hostname)
        editIpv4 = findViewById(R.id.edit_ipv4)
        editIpv6 = findViewById(R.id.edit_ipv6)
        editSpecs = findViewById(R.id.edit_specs)
        editLinkedDomain = findViewById(R.id.edit_linked_domain)
        editRenewalRaw = findViewById(R.id.edit_renewal_raw)
        editBillingCycle = findViewById(R.id.edit_billing_cycle)
        editPrice = findViewById(R.id.edit_price)
        editDescription = findViewById(R.id.edit_description)
        editReminderDays = findViewById(R.id.edit_reminder_days)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDelete = findViewById(R.id.btn_delete)

        editingHostId = intent.getStringExtra(EXTRA_VPS_HOST_ID)
        val isEditing = editingHostId != null

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (isEditing) R.string.vps_host_edit_title_edit else R.string.vps_host_edit_title_new
        )

        if (isEditing) {
            btnDelete.visibility = View.VISIBLE
            populateFromDb(editingHostId!!)
        }

        btnSave.setOnClickListener { saveHost() }
        btnCancel.setOnClickListener { finish() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    // ── Setup ────────────────────────────────────────────────────────────────

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
            editRenewalRaw.setText(host.renewalRaw ?: "")
            editBillingCycle.setText(host.billingCycle ?: "")
            editPrice.setText(host.price ?: "")
            editDescription.setText(host.description ?: "")
            editReminderDays.setText(host.reminderDaysBefore.toString())
        }
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun saveHost() {
        val tenant = editTenant.text?.toString()?.trim()
        if (tenant.isNullOrBlank()) {
            editTenant.error = getString(R.string.vps_host_edit_error_tenant_required)
            return
        }
        val hostname = editHostname.text?.toString()?.trim()
        if (hostname.isNullOrBlank()) {
            editHostname.error = getString(R.string.vps_host_edit_error_hostname_required)
            return
        }

        val renewalRaw = editRenewalRaw.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        // Best-effort parse the free-text renewal field to a concrete date so
        // the renewal reminder worker has something to compare against, same
        // as VpsMarkdownImportExport.parse() does for imported rows.
        val renewalDate = renewalRaw?.let { VpsMarkdownImportExport.parseBestEffortDate(it) }
        val reminderDays = editReminderDays.text?.toString()?.toIntOrNull() ?: 7
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
            billingCycle = editBillingCycle.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
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
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save VPS host", e)
                Toast.makeText(this@VpsHostEditActivity, getString(R.string.vps_host_edit_save_failed_fmt, e.message), Toast.LENGTH_LONG).show()
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
                Logger.e(TAG, "Failed to delete VPS host", e)
                Toast.makeText(this@VpsHostEditActivity, getString(R.string.vps_host_edit_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}

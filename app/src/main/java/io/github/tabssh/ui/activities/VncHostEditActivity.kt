package io.github.tabssh.ui.activities

import io.github.tabssh.sync.tombstone.TombstoneRecorder

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.storage.database.entities.VncHost
import io.github.tabssh.storage.database.entities.VncIdentity
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Create or edit a [VncHost] record.
 *
 * Launch extras:
 *   [EXTRA_VNC_HOST_ID] — String UUID; omit for new host.
 */
class VncHostEditActivity : TabSSHActivity() {

    companion object {
        private const val TAG = "VncHostEditActivity"

        /** String — UUID of the VncHost to edit. Omit (or pass null) to create a new one. */
        const val EXTRA_VNC_HOST_ID = "vnc_host_id"

        /** Highest X display number that keeps `5900 + display` a valid TCP port. */
        private const val MAX_DISPLAY_NUMBER = 65535 - 5900

        private val SECURITY_TYPE_LABELS = listOf(
            "Auto" to "auto",
            "None" to "none",
            "VNC Auth" to "vnc_auth",
            "VeNCrypt TLS (no auth)" to "vencrypt_tls_none",
            "VeNCrypt X.509 (no auth)" to "vencrypt_x509_none"
        )
    }

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var editHost: TextInputEditText
    private lateinit var layoutPort: TextInputLayout
    private lateinit var editPort: TextInputEditText
    private lateinit var switchUseDisplay: SwitchMaterial
    private lateinit var layoutDisplay: TextInputLayout
    private lateinit var editDisplay: TextInputEditText
    private lateinit var dropdownIdentity: AutoCompleteTextView
    private lateinit var dropdownSecurity: AutoCompleteTextView
    private lateinit var switchTlsVerify: SwitchMaterial
    private lateinit var switchKeepAlive: SwitchMaterial
    private lateinit var editColorTag: TextInputEditText
    private lateinit var editNotes: TextInputEditText
    private lateinit var layoutPassword: TextInputLayout
    private lateinit var editPassword: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDelete: MaterialButton

    private var editingHostId: String? = null
    private var availableIdentities: List<VncIdentity> = emptyList()
    private var selectedIdentityId: String? = null

    // Preserved across an edit so save() does not clobber fields the editor
    // doesn't expose (group membership, original creation time, etc.). Null
    // for new-host mode.
    private var editingExisting: VncHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vnc_host_edit)

        app = application as TabSSHApplication

        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        editHost = findViewById(R.id.edit_host)
        layoutPort = findViewById(R.id.layout_port)
        editPort = findViewById(R.id.edit_port)
        switchUseDisplay = findViewById(R.id.switch_use_display)
        layoutDisplay = findViewById(R.id.layout_display)
        editDisplay = findViewById(R.id.edit_display)
        dropdownIdentity = findViewById(R.id.dropdown_identity)
        dropdownSecurity = findViewById(R.id.dropdown_security)
        switchTlsVerify = findViewById(R.id.switch_tls_verify)
        switchKeepAlive = findViewById(R.id.switch_keep_alive_in_background)
        editColorTag = findViewById(R.id.edit_color_tag)
        editNotes = findViewById(R.id.edit_notes)
        layoutPassword = findViewById(R.id.layout_password)
        editPassword = findViewById(R.id.edit_password)
        btnSave = findViewById(R.id.btn_save)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDelete = findViewById(R.id.btn_delete)

        editingHostId = intent.getStringExtra(EXTRA_VNC_HOST_ID)
        val isEditing = editingHostId != null

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(
            if (isEditing) R.string.vnc_host_edit_title_edit else R.string.vnc_host_edit_title_new
        )

        if (isEditing) btnDelete.visibility = View.VISIBLE

        switchUseDisplay.setOnCheckedChangeListener { _, checked ->
            layoutPort.visibility = if (checked) View.GONE else View.VISIBLE
            layoutDisplay.visibility = if (checked) View.VISIBLE else View.GONE
        }

        setupSecurityDropdown()
        loadIdentities(editingHostId)

        btnSave.setOnClickListener { saveHost() }
        btnCancel.setOnClickListener { finish() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupSecurityDropdown() {
        val labels = SECURITY_TYPE_LABELS.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        dropdownSecurity.setAdapter(adapter)
        dropdownSecurity.setText(labels[0], false)
    }

    private fun loadIdentities(editingHostId: String?) {
        lifecycleScope.launch {
            val identities = withContext(Dispatchers.IO) {
                app.database.vncIdentityDao().getAllIdentitiesList()
            }
            availableIdentities = identities

            val labels = mutableListOf(getString(R.string.vnc_host_edit_identity_none))
            labels.addAll(identities.map { it.name })
            val adapter = ArrayAdapter(this@VncHostEditActivity, android.R.layout.simple_dropdown_item_1line, labels)
            dropdownIdentity.setAdapter(adapter)
            dropdownIdentity.setText(getString(R.string.vnc_host_edit_identity_none), false)
            dropdownIdentity.setOnItemClickListener { _, _, position, _ ->
                selectedIdentityId = if (position == 0) null else identities[position - 1].id
            }

            if (editingHostId != null) {
                populateFromDb(editingHostId)
            }
        }
    }

    private fun populateFromDb(hostId: String) {
        lifecycleScope.launch {
            val host = withContext(Dispatchers.IO) { app.database.vncHostDao().getById(hostId) }
            if (host == null) {
                Toast.makeText(this@VncHostEditActivity, getString(R.string.vnc_host_edit_host_not_found), Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            editingExisting = host
            editName.setText(host.name)
            editHost.setText(host.host)
            if (host.displayNumber != null) {
                switchUseDisplay.isChecked = true
                editDisplay.setText(host.displayNumber.toString())
                layoutPort.visibility = View.GONE
                layoutDisplay.visibility = View.VISIBLE
            } else {
                editPort.setText(host.port.toString())
            }
            selectedIdentityId = host.identityId
            val identityIndex = if (host.identityId == null) 0
            else availableIdentities.indexOfFirst { it.id == host.identityId }.let { if (it < 0) 0 else it + 1 }
            dropdownIdentity.setText(
                if (identityIndex == 0) getString(R.string.vnc_host_edit_identity_none) else availableIdentities[identityIndex - 1].name,
                false
            )
            val secLabel = SECURITY_TYPE_LABELS.firstOrNull { it.second == host.securityType }?.first
                ?: SECURITY_TYPE_LABELS[0].first
            dropdownSecurity.setText(secLabel, false)
            switchTlsVerify.isChecked = host.tlsVerify
            switchKeepAlive.isChecked = host.keepAliveInBackground
            if (host.colorTag != 0) editColorTag.setText(host.colorTag.toString(16))
            editNotes.setText(host.notes ?: "")

            val storedPw = withContext(Dispatchers.IO) {
                try { app.securePasswordManager.retrievePassword("vnc_host_$hostId") } catch (_: Exception) { null }
            }
            if (storedPw != null) {
                layoutPassword.helperText = getString(R.string.vnc_host_edit_password_helper_existing)
            }
        }
    }

    // ── Save / Delete ─────────────────────────────────────────────────────────

    private fun saveHost() {
        val name = editName.text?.toString()?.trim()
        if (name.isNullOrBlank()) {
            editName.error = getString(R.string.xcpng_name_required)
            return
        }
        val host = editHost.text?.toString()?.trim()
        if (host.isNullOrBlank()) {
            editHost.error = getString(R.string.vnc_host_edit_host_required)
            return
        }
        val useDisplay = switchUseDisplay.isChecked
        val displayNumber = if (useDisplay) editDisplay.text?.toString()?.toIntOrNull() else null
        val port = if (!useDisplay) (editPort.text?.toString()?.toIntOrNull() ?: 5900) else 5900
        // VncHost.effectivePort is 5900 + displayNumber, so both fields have to
        // land inside the TCP port range. Silently accepting 0, -1 or 99999
        // only surfaced later as an opaque socket-connect failure.
        if (useDisplay) {
            if (displayNumber == null || displayNumber !in 0..MAX_DISPLAY_NUMBER) {
                editDisplay.error = getString(R.string.vnc_host_edit_display_range_fmt, MAX_DISPLAY_NUMBER)
                return
            }
        } else if (port !in 1..65535) {
            editPort.error = getString(R.string.vnc_host_edit_port_error)
            return
        }

        val secLabel = dropdownSecurity.text?.toString() ?: SECURITY_TYPE_LABELS[0].first
        val secType = SECURITY_TYPE_LABELS.firstOrNull { it.first == secLabel }?.second ?: "auto"

        val colorTagStr = editColorTag.text?.toString()?.trim()
        val colorTag = if (colorTagStr.isNullOrBlank()) 0
        else colorTagStr.removePrefix("0x").toLongOrNull(16)?.toInt() ?: 0

        val now = System.currentTimeMillis()
        val existing = editingExisting
        val id = existing?.id ?: editingHostId ?: UUID.randomUUID().toString()
        // Preserve groupId and createdAt from the existing record so editing a
        // host doesn't kick it out of its group or rewrite history. The edit
        // UI doesn't expose group membership — that's set elsewhere.
        val vncHost = VncHost(
            id = id,
            name = name,
            host = host,
            port = port,
            displayNumber = displayNumber,
            identityId = selectedIdentityId,
            securityType = secType,
            tlsVerify = switchTlsVerify.isChecked,
            keepAliveInBackground = switchKeepAlive.isChecked,
            groupId = existing?.groupId,
            colorTag = colorTag,
            notes = editNotes.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            createdAt = existing?.createdAt ?: now,
            modifiedAt = now
        )
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (existing != null) {
                        app.database.vncHostDao().update(vncHost)
                    } else {
                        app.database.vncHostDao().insert(vncHost)
                    }
                    val newPw = editPassword.text?.toString().orEmpty()
                    if (newPw.isNotBlank()) {
                        app.securePasswordManager.storePassword(
                            "vnc_host_$id",
                            newPw,
                            io.github.tabssh.crypto.storage.SecurePasswordManager.StorageLevel.ENCRYPTED
                        )
                    }
                }
                Toast.makeText(this@VncHostEditActivity, getString(R.string.remote_editor_saved), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to save VNC host", e)
                Toast.makeText(this@VncHostEditActivity, getString(R.string.vps_host_edit_save_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmDelete() {
        val hostId = editingHostId ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vnc_host_edit_delete_title))
            .setMessage(getString(R.string.vnc_host_edit_delete_message))
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteHost(hostId) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteHost(hostId: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.database.vncHostDao().deleteById(hostId)
                    // Drop the Keystore secret with the record — see the same
                    // deletion path in VncHostsActivity.
                    app.securePasswordManager.clearPassword("vnc_host_$hostId")
                    // H6 — record the deletion so it propagates and is not resurrected.
                    TombstoneRecorder.record(app, TombstoneRecorder.VNC_HOST, hostId)
                }
                Toast.makeText(this@VncHostEditActivity, getString(R.string.vnc_host_edit_deleted), Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to delete VNC host", e)
                Toast.makeText(this@VncHostEditActivity, getString(R.string.vps_host_edit_delete_failed_fmt, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}

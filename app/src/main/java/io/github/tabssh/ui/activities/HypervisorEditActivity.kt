package io.github.tabssh.ui.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import io.github.tabssh.R
import io.github.tabssh.TabSSHApplication
import io.github.tabssh.crypto.storage.HypervisorPasswordStore
import io.github.tabssh.storage.database.entities.HypervisorProfile
import io.github.tabssh.storage.database.entities.HypervisorType
import io.github.tabssh.hypervisor.proxmox.ProxmoxApiClient
import io.github.tabssh.hypervisor.xcpng.XCPngApiClient
import io.github.tabssh.hypervisor.vmware.VMwareApiClient
import io.github.tabssh.utils.logging.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tabssh.utils.showError

class HypervisorEditActivity : AppCompatActivity() {

    private lateinit var app: TabSSHApplication
    private lateinit var toolbar: MaterialToolbar
    private lateinit var editName: TextInputEditText
    private lateinit var spinnerType: Spinner
    private lateinit var editHost: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var editRealm: TextInputEditText
    private lateinit var layoutRealm: LinearLayout
    private lateinit var layoutUsername: com.google.android.material.textfield.TextInputLayout
    private lateinit var layoutPassword: com.google.android.material.textfield.TextInputLayout
    private lateinit var layoutHost: com.google.android.material.textfield.TextInputLayout
    private lateinit var layoutPort: com.google.android.material.textfield.TextInputLayout
    private lateinit var layoutOci: LinearLayout
    private lateinit var buttonConfigureOci: MaterialButton
    private lateinit var switchVerifySsl: SwitchMaterial
    private lateinit var layoutApiType: com.google.android.material.textfield.TextInputLayout
    private lateinit var dropdownApiType: AutoCompleteTextView
    private lateinit var textApiTypeHint: TextView
    private lateinit var editNotes: TextInputEditText
    private lateinit var buttonTestConnection: MaterialButton
    private lateinit var buttonSave: MaterialButton
    private lateinit var buttonCancel: MaterialButton
    private lateinit var buttonImportHost: MaterialButton

    // Reusable account dropdown (added 2026-05-02). When set, the
    // host pulls username + password (+ optional realm) from the
    // account; the inline username/password/realm fields hide.
    private lateinit var dropdownAccount: AutoCompleteTextView
    private lateinit var layoutAccount: com.google.android.material.textfield.TextInputLayout
    private var availableAccounts: List<io.github.tabssh.storage.database.entities.HypervisorAccount> = emptyList()
    private var selectedAccountId: Long? = null

    // SSH identity picker — visible only for LIBVIRT type. Maps display
    // label to StoredKey.keyId; "(none)" entry maps to null.
    private lateinit var dropdownSshIdentity: AutoCompleteTextView
    private lateinit var layoutSshIdentity: com.google.android.material.textfield.TextInputLayout
    private var availableSshKeys: List<io.github.tabssh.storage.database.entities.StoredKey> = emptyList()
    private var selectedSshIdentityId: String? = null

    // Phase 1 cert pinning UI. Visible only when verifySsl is on.
    private lateinit var layoutPinnedCert: LinearLayout
    private lateinit var textPinnedCert: TextView
    private lateinit var buttonForgetPin: MaterialButton
    /** Holds the current pin while the activity is open. Reset to null
     *  when the user taps "Forget" so the save path writes pinnedCertSha256=null. */
    private var currentPin: String? = null

    private var hypervisorId: Long? = null
    private var editingHypervisor: HypervisorProfile? = null
    private var linkedConnectionId: String? = null

    /**
     * The types available in the spinner. OCI is intentionally omitted — new OCI
     * hypervisor connections should be managed via Cloud Accounts. HypervisorType.OCI
     * stays in the enum to preserve existing DB ordinals; we just hide it in the UI.
     */
    private val hypervisorTypes = listOf(
        HypervisorType.PROXMOX,
        HypervisorType.XCPNG,
        HypervisorType.VMWARE,
        HypervisorType.LIBVIRT
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hypervisor_edit)
        
        app = application as TabSSHApplication
        
        setupViews()
        setupToolbar()
        setupSpinner()
        setupApiTypeDropdown()
        setupAccountDropdown()
        setupSshIdentityDropdown()
        setupClickListeners()

        hypervisorId = intent.getLongExtra("hypervisor_id", -1).takeIf { it != -1L }
        hypervisorId?.let { loadHypervisor(it) }
    }

    /**
     * Populate the account dropdown with `(none — use inline credentials)`
     * + every saved `HypervisorAccount`. Selecting a real account hides
     * the inline username/password/realm rows; selecting "(none)" shows
     * them. The user can still pop into Hypervisor Accounts to add a
     * new one — they re-enter this screen and the new account appears.
     */
    private fun setupAccountDropdown() {
        lifecycleScope.launch {
            // app.database access triggers getDatabase() → Room.databaseBuilder().build()
            // which can open the SQLite connection synchronously. Force to IO so the
            // Main thread is never blocked by database initialization or migrations.
            availableAccounts = withContext(Dispatchers.IO) {
                try {
                    app.database.hypervisorAccountDao().getAllAccountsList()
                } catch (e: Exception) {
                    io.github.tabssh.utils.logging.Logger.w(
                        "HypervisorEditActivity", "Failed to load accounts", e
                    )
                    emptyList()
                }
            }
            val labels = mutableListOf("(none — use inline credentials)")
            labels += availableAccounts.map { it.getDisplayName() }
            dropdownAccount.setAdapter(
                ArrayAdapter(
                    this@HypervisorEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    labels
                )
            )
            // Default to "(none)" until loadHypervisor() picks the right
            // entry on edit, or the user changes it on a fresh row.
            if (dropdownAccount.text.isNullOrEmpty()) {
                dropdownAccount.setText(labels.first(), false)
                applyAccountSelection(null)
            }
            dropdownAccount.setOnItemClickListener { _, _, position, _ ->
                // position 0 = "(none)", subsequent positions index into
                // availableAccounts.
                val acc = if (position == 0) null else availableAccounts.getOrNull(position - 1)
                applyAccountSelection(acc?.id)
            }
        }
    }

    /**
     * Load stored SSH keys and populate the identity dropdown. Only called for
     * LIBVIRT connections; the layout row starts hidden and is revealed by
     * [updateUIForType].
     */
    private fun setupSshIdentityDropdown() {
        lifecycleScope.launch {
            availableSshKeys = withContext(Dispatchers.IO) {
                try {
                    app.database.keyDao().getAllKeysList()
                } catch (e: Exception) {
                    io.github.tabssh.utils.logging.Logger.w(
                        "HypervisorEditActivity", "Failed to load SSH keys", e
                    )
                    emptyList()
                }
            }
            val labels = mutableListOf("(none — password only)")
            labels += availableSshKeys.map { it.getDisplayName() }
            dropdownSshIdentity.setAdapter(
                ArrayAdapter(
                    this@HypervisorEditActivity,
                    android.R.layout.simple_dropdown_item_1line,
                    labels
                )
            )
            if (dropdownSshIdentity.text.isNullOrEmpty()) {
                dropdownSshIdentity.setText(labels.first(), false)
                selectedSshIdentityId = null
            }
            dropdownSshIdentity.setOnItemClickListener { _, _, position, _ ->
                selectedSshIdentityId = if (position == 0) null
                else availableSshKeys.getOrNull(position - 1)?.keyId
                // Hide password field when a key is selected; reveal it when reverting to none.
                // Only applies when no account is chosen (account selection owns layoutPassword too).
                if (selectedAccountId == null) {
                    layoutPassword.visibility = if (position > 0) View.GONE else View.VISIBLE
                }
            }
        }
    }

    /**
     * Hide/show the inline username/password/realm rows based on whether
     * an account is currently selected. Doesn't TOUCH the field values
     * — they remain as fallback if the user toggles back to "(none)".
     * `selectedAccountId` is the source of truth for save.
     */
    private fun applyAccountSelection(accountId: Long?) {
        selectedAccountId = accountId
        val accountChosen = accountId != null
        layoutUsername.visibility = if (accountChosen) View.GONE else View.VISIBLE
        layoutPassword.visibility = if (accountChosen) View.GONE else View.VISIBLE
        // Realm row visibility is driven by the selected hypervisor type (Proxmox-only).
        // When an account is chosen, realm is always hidden (the account row owns it).
        // When no account is chosen, restore type-driven visibility directly — do NOT
        // call updateUIForType() here: that calls refreshAccountDropdownForType() which
        // calls applyAccountSelection(null) again → infinite recursion → ANR.
        if (accountChosen) {
            layoutRealm.visibility = View.GONE
        } else {
            val isProxmox = hypervisorTypes.getOrNull(spinnerType.selectedItemPosition) == HypervisorType.PROXMOX
            layoutRealm.visibility = if (isProxmox) View.VISIBLE else View.GONE
        }
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        editName = findViewById(R.id.edit_name)
        spinnerType = findViewById(R.id.spinner_type)
        editHost = findViewById(R.id.edit_host)
        editPort = findViewById(R.id.edit_port)
        editUsername = findViewById(R.id.edit_username)
        editPassword = findViewById(R.id.edit_password)
        editRealm = findViewById(R.id.edit_realm)
        layoutRealm = findViewById(R.id.layout_realm)
        layoutUsername = findViewById(R.id.layout_username)
        layoutPassword = findViewById(R.id.layout_password)
        layoutHost = findViewById(R.id.layout_host)
        layoutPort = findViewById(R.id.layout_port)
        layoutOci = findViewById(R.id.layout_oci)
        buttonConfigureOci = findViewById(R.id.button_configure_oci)
        switchVerifySsl = findViewById(R.id.switch_verify_ssl)
        layoutApiType = findViewById(R.id.layout_api_type)
        dropdownApiType = findViewById(R.id.dropdown_api_type)
        textApiTypeHint = findViewById(R.id.text_api_type_hint)
        editNotes = findViewById(R.id.edit_notes)
        buttonTestConnection = findViewById(R.id.button_test)
        buttonSave = findViewById(R.id.button_save)
        buttonCancel = findViewById(R.id.button_cancel)
        buttonImportHost = findViewById(R.id.button_import_host)
        dropdownAccount = findViewById(R.id.dropdown_account)
        layoutAccount = findViewById(R.id.layout_account)
        dropdownSshIdentity = findViewById(R.id.dropdown_ssh_identity)
        layoutSshIdentity = findViewById(R.id.layout_ssh_identity)
        layoutPinnedCert = findViewById(R.id.layout_pinned_cert)
        textPinnedCert = findViewById(R.id.text_pinned_cert)
        buttonForgetPin = findViewById(R.id.button_forget_pin)
        // Pinned-cert row visibility tracks the verify-SSL switch.
        switchVerifySsl.setOnCheckedChangeListener { _, checked ->
            updatePinnedCertVisibility(checked)
        }
        buttonForgetPin.setOnClickListener {
            currentPin = null
            renderPinnedCertText()
            io.github.tabssh.utils.logging.Logger.i(
                "HypervisorEditActivity",
                "User cleared pinned cert — next connect will TOFU re-capture"
            )
        }
    }

    /**
     * Show/hide the pin row based on the verify-SSL switch, and refresh
     * the displayed value. Called from the switch's listener and from
     * loadHypervisor() once the value has been read in.
     */
    private fun updatePinnedCertVisibility(verifySslOn: Boolean) {
        layoutPinnedCert.visibility = if (verifySslOn) View.VISIBLE else View.GONE
        renderPinnedCertText()
    }

    private fun renderPinnedCertText() {
        val pin = currentPin
        if (pin.isNullOrBlank()) {
            textPinnedCert.text = "(not pinned yet — will pin on next successful connect)"
            buttonForgetPin.visibility = View.GONE
        } else {
            textPinnedCert.text = "SHA-256: $pin"
            buttonForgetPin.visibility = View.VISIBLE
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (hypervisorId != null) "Edit Hypervisor" else "Add Hypervisor"
    }

    private fun setupSpinner() {
        // Build labels from the explicit hypervisorTypes list (OCI excluded).
        // Spinner positions map to hypervisorTypes indices, not raw enum ordinals.
        val labels = hypervisorTypes.map { type ->
            when (type) {
                HypervisorType.PROXMOX -> "Proxmox"
                HypervisorType.XCPNG   -> "XCP-ng"
                HypervisorType.VMWARE  -> "VMware"
                HypervisorType.LIBVIRT -> "QEMU/libvirt"
                else -> type.name
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerType.adapter = adapter

        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateUIForType(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateUIForType(0)
    }

    private fun setupApiTypeDropdown() {
        // Setup API type dropdown with values from arrays.xml
        val apiTypes = resources.getStringArray(R.array.api_type_entries)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, apiTypes)
        dropdownApiType.setAdapter(adapter)

        // Set default to auto-detect
        dropdownApiType.setText(apiTypes[0], false)
    }

    private fun setupClickListeners() {
        buttonTestConnection.setOnClickListener {
            testConnection()
        }

        buttonSave.setOnClickListener {
            saveHypervisor()
        }

        buttonCancel.setOnClickListener {
            finish()
        }

        buttonImportHost.setOnClickListener {
            showImportFromExistingHostDialog()
        }

        buttonConfigureOci.setOnClickListener {
            // Take the user to the Identities tab (tab index 2) in MainActivity
            // to create or edit an OCI virtualization identity.
            val intent = android.content.Intent(this, MainActivity::class.java)
            intent.putExtra("start_tab", 2)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            startActivity(intent)
        }
    }

    /**
     * Show dialog to import from an existing SSH connection
     */
    private fun showImportFromExistingHostDialog() {
        lifecycleScope.launch {
            try {
                // app.database access can trigger lazy init — run on IO.
                val connections = withContext(Dispatchers.IO) {
                    app.database.connectionDao().getAllConnectionsList()
                }

                if (connections.isEmpty()) {
                    Toast.makeText(
                        this@HypervisorEditActivity,
                        "No existing connections found. Create a connection first.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                // Build display list
                val connectionNames = connections.map { conn: io.github.tabssh.storage.database.entities.ConnectionProfile ->
                    "${conn.name ?: conn.getDisplayName()} (${conn.host})"
                }.toTypedArray()

                androidx.appcompat.app.AlertDialog.Builder(this@HypervisorEditActivity)
                    .setTitle("Import from Existing Host")
                    .setItems(connectionNames) { _: android.content.DialogInterface, which: Int ->
                        val selectedConnection = connections[which]
                        importFromConnection(selectedConnection)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(
                    this@HypervisorEditActivity,
                    "Error loading connections: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Import host details from an existing SSH connection
     */
    private fun importFromConnection(connection: io.github.tabssh.storage.database.entities.ConnectionProfile) {
        // Set linked connection ID
        linkedConnectionId = connection.id

        // Pre-fill fields from the connection
        editName.setText(connection.name ?: connection.getDisplayName())
        editHost.setText(connection.host)
        editUsername.setText(connection.username)

        // Try to get password from secure storage (Keystore AES-GCM — must run on IO)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val password = app.securePasswordManager.retrievePassword(connection.id)
                if (password != null) {
                    withContext(Dispatchers.Main) {
                        editPassword.setText(password)
                    }
                }
            } catch (e: Exception) {
                // Password retrieval failed; user will need to enter it manually
            }
        }

        // Update port based on hypervisor type
        when (hypervisorTypes.getOrNull(spinnerType.selectedItemPosition)) {
            HypervisorType.PROXMOX -> {
                editPort.setText("8006")
                editRealm.setText("pam")
            }
            HypervisorType.XCPNG -> editPort.setText("443")
            HypervisorType.VMWARE -> editPort.setText("443")
            HypervisorType.LIBVIRT -> editPort.setText(connection.port.toString())
            else -> {}
        }

        // Show confirmation
        Toast.makeText(
            this,
            "Imported from ${connection.host}. Adjust port and credentials as needed.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Set the port field to a type-default, but only if the current value
     * is empty or equals one of the other types' defaults — i.e. the user
     * hasn't typed a custom port. The previous behaviour gated the update
     * on `isEmpty()`, which meant flipping the type spinner from Proxmox
     * (8006) to XCP-ng/VMware never replaced 8006 with 443.
     */
    private val typeDefaultPorts = setOf("8006", "443", "22")
    private fun applyTypeDefaultPort(default: String) {
        val current = editPort.text.toString()
        if (current.isEmpty() || current in typeDefaultPorts) {
            editPort.setText(default)
        }
    }

    private fun updateUIForType(typePosition: Int) {
        val selectedType = hypervisorTypes.getOrNull(typePosition) ?: return
        val isLibvirt = selectedType == HypervisorType.LIBVIRT
        // SSH identity picker is LIBVIRT-only
        layoutSshIdentity.visibility = if (isLibvirt) View.VISIBLE else View.GONE
        // OCI panel is not exposed via this spinner; hide it always
        layoutOci.visibility = View.GONE
        layoutHost.visibility = View.VISIBLE
        layoutPort.visibility = View.VISIBLE
        layoutUsername.visibility = View.VISIBLE
        layoutPassword.visibility = View.VISIBLE
        // SSL verify does not apply to SSH-backed libvirt connections
        switchVerifySsl.visibility = if (isLibvirt) View.GONE else View.VISIBLE
        layoutAccount.visibility = View.VISIBLE

        refreshAccountDropdownForType(typePosition)

        when (selectedType) {
            HypervisorType.PROXMOX -> {
                applyTypeDefaultPort("8006")
                layoutRealm.visibility = View.VISIBLE
                if (editRealm.text.toString().isEmpty()) editRealm.setText("pam")
                layoutApiType.visibility = View.GONE
                textApiTypeHint.visibility = View.GONE
            }
            HypervisorType.XCPNG -> {
                applyTypeDefaultPort("443")
                layoutRealm.visibility = View.GONE
                layoutApiType.visibility = View.VISIBLE
                textApiTypeHint.visibility = View.VISIBLE
                textApiTypeHint.text = "Auto: Try XO REST → XCP-ng XML-RPC\nDirect: XCP-ng host (XML-RPC)\nCentralized: Xen Orchestra (REST API)"
            }
            HypervisorType.VMWARE -> {
                applyTypeDefaultPort("443")
                layoutRealm.visibility = View.GONE
                layoutApiType.visibility = View.VISIBLE
                textApiTypeHint.visibility = View.VISIBLE
                textApiTypeHint.text = "Auto: Try vCenter → ESXi\nDirect: ESXi host\nCentralized: vCenter/vSphere"
            }
            HypervisorType.LIBVIRT -> {
                applyTypeDefaultPort("22")
                layoutRealm.visibility = View.GONE
                layoutApiType.visibility = View.GONE
                textApiTypeHint.visibility = View.GONE
            }
            else -> {
                layoutRealm.visibility = View.GONE
                layoutApiType.visibility = View.GONE
                textApiTypeHint.visibility = View.GONE
            }
        }
    }

    /**
     * Reload the account dropdown with accounts matching the selected type's
     * auth model. Password-type hypervisors show `authType="password"` accounts;
     * OCI shows `authType="oci_api_key"` accounts.
     *
     * Re-selects the current [selectedAccountId] if it is still in the filtered
     * list; otherwise clears to "(none)".
     */
    private fun refreshAccountDropdownForType(typePosition: Int) {
        // OCI accounts are never shown via this spinner; filter them out for all types
        val filtered = availableAccounts.filter { acc -> acc.authType != "oci_api_key" }
        val labels = mutableListOf("(none — use inline credentials)")
        labels += filtered.map { it.getDisplayName() }
        dropdownAccount.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        )
        val currentId = selectedAccountId
        val matchIdx = if (currentId != null) filtered.indexOfFirst { it.id == currentId }
                           .takeIf { it >= 0 }?.plus(1) ?: 0
                       else 0
        dropdownAccount.setText(labels[matchIdx], false)
        applyAccountSelection(if (matchIdx == 0) null else filtered[matchIdx - 1].id)

        dropdownAccount.setOnItemClickListener { _, _, position, _ ->
            val acc = if (position == 0) null else filtered.getOrNull(position - 1)
            applyAccountSelection(acc?.id)
        }
    }

    private fun loadHypervisor(id: Long) {
        lifecycleScope.launch {
            try {
                // DB access triggers app.database lazy init — run on IO to avoid
                // blocking the main thread (ANR). HypervisorPasswordStore.retrieve()
                // has its own withContext(Dispatchers.IO) so it is safe here too.
                val hypervisor = withContext(Dispatchers.IO) {
                    app.database.hypervisorDao().getById(id)
                }
                if (hypervisor != null) {
                    // OCI profiles are no longer editable here; direct users to Cloud Accounts.
                    if (hypervisor.type == HypervisorType.OCI) {
                        com.google.android.material.snackbar.Snackbar.make(
                            findViewById(android.R.id.content),
                            "OCI hypervisors have moved to Cloud Accounts. Manage them there.",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        ).show()
                        finish()
                        return@launch
                    }

                    editingHypervisor = hypervisor
                    linkedConnectionId = hypervisor.linkedConnectionId

                    editName.setText(hypervisor.name)
                    val typeIdx = hypervisorTypes.indexOf(hypervisor.type).takeIf { it >= 0 } ?: 0
                    spinnerType.setSelection(typeIdx)
                    editHost.setText(hypervisor.host)
                    editPort.setText(hypervisor.port.toString())
                    editUsername.setText(hypervisor.username)
                    // P1 fix: hypervisor passwords now live in the
                    // Keystore-backed SecurePasswordManager. The DB
                    // column is empty for migrated rows; legacy rows
                    // are auto-migrated on first read by the helper.
                    editPassword.setText(
                        HypervisorPasswordStore.retrieve(this@HypervisorEditActivity, hypervisor)
                    )
                    editRealm.setText(hypervisor.realm ?: "pam")
                    switchVerifySsl.isChecked = hypervisor.verifySsl
                    currentPin = hypervisor.pinnedCertSha256
                    updatePinnedCertVisibility(hypervisor.verifySsl)

                    // Account dropdown — re-fetch on IO for the same reason.
                    val accounts = withContext(Dispatchers.IO) {
                        try {
                            app.database.hypervisorAccountDao().getAllAccountsList()
                        } catch (e: Exception) { emptyList() }
                    }
                    availableAccounts = accounts
                    val labels = mutableListOf("(none — use inline credentials)")
                    labels += accounts.map { it.getDisplayName() }
                    dropdownAccount.setAdapter(
                        ArrayAdapter(
                            this@HypervisorEditActivity,
                            android.R.layout.simple_dropdown_item_1line,
                            labels
                        )
                    )
                    val linkedIdx = if (hypervisor.accountId != null)
                        accounts.indexOfFirst { it.id == hypervisor.accountId }
                            .takeIf { it >= 0 }?.plus(1) ?: 0
                    else 0
                    dropdownAccount.setText(labels[linkedIdx], false)
                    applyAccountSelection(if (linkedIdx == 0) null else accounts[linkedIdx - 1].id)

                    // Load API type override
                    val apiTypeEntries = resources.getStringArray(R.array.api_type_entries)
                    val apiTypeValues = resources.getStringArray(R.array.api_type_values)
                    val apiTypeIndex = apiTypeValues.indexOf(hypervisor.apiTypeOverride)
                    if (apiTypeIndex >= 0) {
                        dropdownApiType.setText(apiTypeEntries[apiTypeIndex], false)
                    }

                    editNotes.setText(hypervisor.notes ?: "")

                    // SSH identity dropdown — re-load keys on IO and pre-select the
                    // saved identity when editing an existing LIBVIRT profile.
                    if (hypervisor.type == HypervisorType.LIBVIRT) {
                        val keys = withContext(Dispatchers.IO) {
                            try { app.database.keyDao().getAllKeysList() }
                            catch (e: Exception) { emptyList() }
                        }
                        availableSshKeys = keys
                        val keyLabels = mutableListOf("(none — password only)")
                        keyLabels += keys.map { it.getDisplayName() }
                        dropdownSshIdentity.setAdapter(
                            ArrayAdapter(
                                this@HypervisorEditActivity,
                                android.R.layout.simple_dropdown_item_1line,
                                keyLabels
                            )
                        )
                        val keyIdx = if (hypervisor.sshIdentityId != null)
                            keys.indexOfFirst { it.keyId == hypervisor.sshIdentityId }
                                .takeIf { it >= 0 }?.plus(1) ?: 0
                        else 0
                        dropdownSshIdentity.setText(keyLabels[keyIdx], false)
                        selectedSshIdentityId = if (keyIdx == 0) null else keys[keyIdx - 1].keyId
                        // Reflect key selection in password field visibility immediately on load.
                        if (selectedSshIdentityId != null && selectedAccountId == null) {
                            layoutPassword.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                showError("Failed to load hypervisor", "Error")
                finish()
            }
        }
    }

    private fun testConnection() {
        if (!validateFields()) return
        
        buttonTestConnection.isEnabled = false
        buttonTestConnection.text = "Testing..."
        
        lifecycleScope.launch {
            try {
                val type = hypervisorTypes[spinnerType.selectedItemPosition]
                val host = editHost.text.toString()
                val port = editPort.text.toString().toInt()
                val verifySsl = switchVerifySsl.isChecked

                // Resolve credentials: account row takes precedence over inline fields.
                val accountId = selectedAccountId
                val account = if (accountId != null) {
                    withContext(Dispatchers.IO) {
                        try { app.database.hypervisorAccountDao().getById(accountId) }
                        catch (e: Exception) { null }
                    }
                } else null
                val username = account?.username ?: editUsername.text.toString()
                val password = if (account != null) {
                    withContext(Dispatchers.IO) {
                        HypervisorPasswordStore.retrieveAccountPassword(
                            this@HypervisorEditActivity, accountId!!
                        )
                    } ?: ""
                } else {
                    editPassword.text.toString()
                }
                val realm = account?.realm ?: editRealm.text.toString()

                val success = when (type) {
                    HypervisorType.PROXMOX -> {
                        val client = ProxmoxApiClient(host, port, username, password, realm, verifySsl)
                        client.authenticate()
                    }
                    HypervisorType.XCPNG -> {
                        val client = XCPngApiClient(host, port, username, password, verifySsl)
                        client.authenticate()
                    }
                    HypervisorType.VMWARE -> {
                        val client = VMwareApiClient(host, username, password, verifySsl)
                        client.authenticate()
                    }
                    HypervisorType.LIBVIRT -> {
                        // SSH-based; just do a quick connect/disconnect
                        val client = io.github.tabssh.hypervisor.libvirt.LibvirtApiClient(
                            this@HypervisorEditActivity,
                            io.github.tabssh.storage.database.entities.HypervisorProfile(
                                name = "",
                                type = HypervisorType.LIBVIRT,
                                host = host,
                                port = port,
                                username = username,
                                password = password,
                                verifySsl = verifySsl,
                                sshIdentityId = selectedSshIdentityId
                            )
                        )
                        client.connect()
                        client.disconnect()
                        true
                    }
                    else -> {
                        Toast.makeText(this@HypervisorEditActivity,
                            "Test not supported for this type", Toast.LENGTH_SHORT).show()
                        false
                    }
                }
                
                if (success) {
                    Toast.makeText(this@HypervisorEditActivity, "✓ Connection successful", Toast.LENGTH_LONG).show()
                } else {
                    showError("✗ Connection failed", "Error")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}", "Error")
            } finally {
                buttonTestConnection.isEnabled = true
                buttonTestConnection.text = "Test Connection"
            }
        }
    }

    private fun saveHypervisor() {
        if (!validateFields()) return

        lifecycleScope.launch {
            try {
                val type = hypervisorTypes[spinnerType.selectedItemPosition]

                // Get API type override value
                val apiTypeEntries = resources.getStringArray(R.array.api_type_entries)
                val apiTypeValues = resources.getStringArray(R.array.api_type_values)
                val selectedApiType = dropdownApiType.text.toString()
                val apiTypeIndex = apiTypeEntries.indexOf(selectedApiType)
                val apiTypeOverride = if (apiTypeIndex >= 0) apiTypeValues[apiTypeIndex] else "auto"

                // P1 fix: NEVER write the password to the entity; route
                // it through the Keystore-backed HypervisorPasswordStore
                // instead. The entity's `password` column stays empty
                // for any row this code touches.
                //
                // Account-aware: if the user picked a reusable
                // HypervisorAccount from the dropdown, we store the
                // accountId on the row, and skip writing inline username
                // / password / realm (those resolve from the account at
                // connect time via HypervisorPasswordStore.resolveCredentials).
                val accountId = selectedAccountId
                val plaintextPassword = editPassword.text.toString()
                val hypervisor = HypervisorProfile(
                    id = hypervisorId ?: 0,
                    name = editName.text.toString(),
                    type = type,
                    host = editHost.text.toString(),
                    port = editPort.text.toString().toInt(),
                    username = if (accountId != null) "" else editUsername.text.toString(),
                    password = "",
                    realm = if (accountId != null) {
                        // Account is the realm source-of-truth when set.
                        // Per-host realm override stays a future option.
                        null
                    } else if (type == HypervisorType.PROXMOX) {
                        editRealm.text.toString()
                    } else null,
                    verifySsl = switchVerifySsl.isChecked,
                    pinnedCertSha256 = currentPin,
                    apiTypeOverride = apiTypeOverride,
                    linkedConnectionId = linkedConnectionId,
                    accountId = accountId,
                    notes = editNotes.text.toString().takeIf { it.isNotBlank() },
                    lastConnected = editingHypervisor?.lastConnected ?: 0,
                    createdAt = editingHypervisor?.createdAt ?: System.currentTimeMillis(),
                    sshIdentityId = if (type == HypervisorType.LIBVIRT) selectedSshIdentityId else null
                )

                val savedId = if (hypervisorId != null) {
                    app.database.hypervisorDao().update(hypervisor)
                    Toast.makeText(this@HypervisorEditActivity, "Updated ${hypervisor.name}", Toast.LENGTH_SHORT).show()
                    hypervisorId!!
                } else {
                    val newId = app.database.hypervisorDao().insert(hypervisor)
                    Toast.makeText(this@HypervisorEditActivity, "Added ${hypervisor.name}", Toast.LENGTH_SHORT).show()
                    newId
                }

                if (accountId == null) {
                    if (plaintextPassword.isNotBlank()) {
                        // Inline-credential path: persist the per-host password.
                        val storeOk = HypervisorPasswordStore.store(
                            this@HypervisorEditActivity, savedId, plaintextPassword
                        )
                        if (!storeOk) {
                            Toast.makeText(
                                this@HypervisorEditActivity,
                                "⚠ Password stored insecurely (Keystore unavailable). Re-edit to retry.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    // If plaintextPassword is blank (LIBVIRT + SSH key only), leave
                    // any existing Keystore entry intact — the user may have a
                    // previously saved password they want to keep as a fallback.
                } else {
                    // Account-linked: drop any per-host password we may
                    // have left over from a previous inline configuration
                    // so a future account-detach doesn't surface a stale
                    // ghost credential. clear() is suspend + dispatches to IO internally.
                    HypervisorPasswordStore.clear(this@HypervisorEditActivity, savedId)
                }
                
                finish()
            } catch (e: Exception) {
                showError("Failed to save: ${e.message}", "Error")
            }
        }
    }

    private fun validateFields(): Boolean {
        if (editName.text.toString().isBlank()) {
            editName.error = "Name is required"
            return false
        }
        if (editHost.text.toString().isBlank()) {
            editHost.error = "Host is required"
            return false
        }
        if (editPort.text.toString().isBlank()) {
            editPort.error = "Port is required"
            return false
        }
        // Skip inline credential checks when a reusable account is selected;
        // those fields are hidden and credentials resolve from the account.
        if (selectedAccountId == null) {
            if (editUsername.text.toString().isBlank()) {
                editUsername.error = "Username is required"
                return false
            }
            // Password is optional for LIBVIRT when an SSH key identity is
            // selected — the key alone is sufficient for pubkey auth.
            val isLibvirtWithKey = hypervisorTypes.getOrNull(spinnerType.selectedItemPosition) == HypervisorType.LIBVIRT
                && selectedSshIdentityId != null
            if (!isLibvirtWithKey && editPassword.text.toString().isBlank()) {
                editPassword.error = "Password is required"
                return false
            }
        }

        val port = editPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) {
            editPort.error = "Invalid port"
            return false
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
